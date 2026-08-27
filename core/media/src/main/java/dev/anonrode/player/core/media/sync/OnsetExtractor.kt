package dev.anonrode.player.core.media.sync

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.anonrode.player.core.media.log.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Onset extraction for the subtitle-sync engine — v3.
 *
 * Two complementary sources (fix-1 validated strategy):
 *  1. silencedetect onsets: silence-endings via ffmpeg's silencedetect
 *     filter (noise=-25dB:d=0.3 on a 300-3400 Hz band) — the source the
 *     engine was validated on (10/10 Growling Tiger 2). Where no ffmpeg
 *     BINARY exists (stock devices; nextlib bundles only .so decoders),
 *     a pure-Kotlin equivalent runs over [MediaCodec]-decoded PCM:
 *     1-pole 300Hz highpass + 3400Hz lowpass, 10ms RMS windows, -25dB
 *     threshold, 0.3s min silence, onset = silence_end.
 *  2. Silero-VAD speech-START onsets ([SileroVad]): the fallback that
 *     rescues wall-to-wall-music / silence-sparse content where
 *     silencedetect yields too few onsets (fix-1: hybrid 10/10, VAD
 *     alone locks the pink-noise stress case silencedetect refuses).
 *
 * [extractSources] runs BOTH in a single decode pass and returns them
 * separately plus the hybrid union (dedup 50ms) — the fix-1 hybrid was
 * the strongest onset source on clean content AND the rescue on music
 * content. The caller (SyncFingerprintJob) tries silencedetect first,
 * then hybrid, then VAD-only, each through the same confidence gates.
 *
 * v2 history note: the first 10 minutes of an episode can be misaligned
 * with the subtitle file even when the whole episode aligns (EP31: 24%
 * recall in the first 600s vs 59% full-episode), so extraction always
 * covers the FULL file. No temp files.
 */
class OnsetExtractor(private val context: Context) {

    /** Both onset sources from one pass over the file. */
    data class OnsetSources(
        val silencedetect: List<Double>,
        val vad: List<Double>,
    ) {
        /** Union with 50 ms dedup — the fix-1 hybrid source. */
        val hybrid: List<Double> by lazy {
            val out = ArrayList<Double>(silencedetect.size + vad.size)
            for (t in (silencedetect + vad).sorted()) {
                if (out.isEmpty() || t - out.last() > 0.05) out.add(t)
            }
            out
        }
    }

    /**
     * Extract BOTH onset sources. silencedetect uses the ffmpeg binary
     * when available (exact reference pipeline), else the Kotlin
     * equivalent; VAD runs only when the Silero model asset is present.
     */
    fun extractSources(videoPath: String): OnsetSources {
        val sil = resolveFfmpegPath()?.let {
            extractWithFfmpeg(it, videoPath, 0.0)
        }
        val vadAvailable = SileroVad.modelAvailable(context)
        if (sil != null && !vadAvailable) {
            return OnsetSources(sil, emptyList())
        }

        // Single MediaCodec decode pass feeding both detectors.
        val silence = SilenceState()
        val vad = if (vadAvailable) SileroVad(context) else null
        decodeAudio(videoPath) { buf, sr, ch, isFloat ->
            silence.process(buf, sr, ch, isFloat)
            vad?.processPcm(buf, sr, ch, isFloat)
            true
        }
        val silOnsets = sil ?: silence.finish()
        val vadOnsets = if (vad != null) {
            try { vad.finish() } finally { vad.close() }
        } else emptyList()
        return OnsetSources(silOnsets, vadOnsets)
    }

    /**
     * Backwards-compatible single-source API: silencedetect onsets only
     * (ffmpeg binary preferred, Kotlin fallback).
     */
    fun extract(videoPath: String, maxSeconds: Double = 0.0): List<Double> {
        val ffmpegPath = resolveFfmpegPath()
        if (ffmpegPath != null) {
            val onsets = extractWithFfmpeg(ffmpegPath, videoPath, maxSeconds)
            if (onsets != null) return onsets
            AppLog.d("ONSET", "ffmpeg path failed, falling back to MediaCodec")
        }
        val silence = SilenceState(maxSeconds)
        decodeAudio(videoPath) { buf, sr, ch, isFloat ->
            silence.process(buf, sr, ch, isFloat)
            !silence.limitReached
        }
        return silence.finish()
    }

    /** Returns null when ffmpeg failed (bad exit / exec error) so the
     *  caller can fall back; empty list is a legitimate result. */
    private fun extractWithFfmpeg(
        ffmpegPath: String,
        videoPath: String,
        maxSeconds: Double,
    ): List<Double>? {
        // -v info is REQUIRED: silencedetect emits its silence_end lines at
        // info level, so -v error would suppress the very output we parse
        // (the validated Python reference runs at the default info level).
        // -nostats/-hide_banner keep the pipe small by suppressing the
        // per-frame progress spam.
        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-nostats", "-v", "info",
            "-i", videoPath,
            "-af", "highpass=f=300,lowpass=f=3400,silencedetect=noise=-25dB:d=0.3",
            "-f", "null", "-"
        )
        if (maxSeconds > 0) {
            // -t before -i limits the DEMUX to maxSeconds (input option),
            // which is what actually caps decode work.
            val i = args.indexOf("-i")
            args.add(i, "-t")
            args.add(i + 1, maxSeconds.toString())
        }

        val result = runProcess(args)
        if (result.exitCode != 0) {
            AppLog.e("ONSET", "ffmpeg silencedetect failed: ${result.stderr.take(200)}")
            return null
        }

        // Parse "silence_end: T" lines
        val onsets = mutableListOf<Double>()
        val re = Regex("""silence_end:\s+([\d.]+)""")
        for (line in result.stderr.lines() + result.stdout.lines()) {
            val m = re.find(line) ?: continue
            val t = m.groupValues[1].toDoubleOrNull() ?: continue
            onsets.add(t)
        }
        onsets.sort()
        AppLog.d("ONSET", "extracted ${onsets.size} onsets from $videoPath (ffmpeg)")
        return onsets
    }

    // ── shared MediaCodec decode loop ────────────────────────────────

    private companion object {
        const val DECODE_TIMEOUT_MS = 600_000L

        // silencedetect=noise=-25dB → amplitude threshold vs full scale
        val NOISE_AMP = 10.0.pow(-25.0 / 20.0)
        const val MIN_SILENCE_SEC = 0.3
    }

    /**
     * Decode the first audio track of [videoPath] to PCM, invoking
     * [onPcm] for every output buffer (positioned: offset applied,
     * limit = end of valid data). Return false from [onPcm] to stop
     * early. Runs the whole file; single-threaded, blocking — call from
     * a background worker.
     */
    private fun decodeAudio(
        videoPath: String,
        onPcm: (buf: ByteBuffer, sampleRate: Int, channels: Int, isFloat: Boolean) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(videoPath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) {
                AppLog.e("ONSET", "no audio track in $videoPath")
                return
            }
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            extractor.selectTrack(track)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = 0
            var channels = 0
            var isFloat = false
            val t0 = System.currentTimeMillis()

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        } else {
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                    channels = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                        AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    if (info.size > 0 && sampleRate > 0 && channels > 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (!onPcm(buf, sampleRate, channels, isFloat)) {
                                outputDone = true
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }

                if (System.currentTimeMillis() - t0 > DECODE_TIMEOUT_MS) {
                    AppLog.e("ONSET", "MediaCodec decode timeout after ${DECODE_TIMEOUT_MS}ms")
                    break
                }
            }
        } catch (t: Throwable) {
            AppLog.e("ONSET", "MediaCodec decode failed", t)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            extractor.release()
        }
    }

    // ── silencedetect equivalent over decoded PCM ────────────────────

    /**
     * Streaming silencedetect state machine: 1-pole 300Hz highpass +
     * 3400Hz lowpass per channel (approximating ffmpeg's bandpass),
     * 10ms RMS windows on the mono downmix, onset = silence_end after
     * >= 0.3s below -25dB.
     */
    private class SilenceState(private val maxSeconds: Double = 0.0) {
        val onsets = mutableListOf<Double>()
        private var sampleRate = 0
        private var channels = 0

        // 1-pole bandpass state per channel
        private var hpBeta = 0.0
        private var lpAlpha = 0.0
        private var hpX = DoubleArray(0)
        private var hpY = DoubleArray(0)
        private var lpY = DoubleArray(0)

        // window + silence machine
        private var windowTarget = 0
        private var windowSumSq = 0.0
        private var windowN = 0
        private var monoSamples = 0L
        private var frameSum = 0.0
        private var sampleIdx = 0L
        private var silenceStart = -1.0

        val limitReached: Boolean
            get() = maxSeconds > 0 && sampleRate > 0 &&
                monoSamples.toDouble() / sampleRate >= maxSeconds

        private fun configure(rate: Int, ch: Int) {
            sampleRate = rate
            channels = ch
            hpBeta = exp(-2.0 * PI * 300.0 / sampleRate)
            lpAlpha = 1.0 - exp(-2.0 * PI * 3400.0 / sampleRate)
            hpX = DoubleArray(channels)
            hpY = DoubleArray(channels)
            lpY = DoubleArray(channels)
            windowTarget = sampleRate / 100 // 10ms windows
        }

        fun process(buf: ByteBuffer, rate: Int, ch: Int, isFloat: Boolean) {
            if (sampleRate == 0) configure(rate, ch)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val frames = buf.remaining() / (if (isFloat) 4 else 2) / channels
            if (isFloat) {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    for (c in 0 until channels) onSample(fb.get(i * channels + c).toDouble())
                }
            } else {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    for (c in 0 until channels) onSample(sb.get(i * channels + c) / 32768.0)
                }
            }
        }

        private fun onSample(x: Double) {
            val ch = (sampleIdx % channels).toInt()
            // 1-pole highpass then lowpass (ffmpeg bandpass approximation)
            val hpOut = hpBeta * (hpY[ch] + x - hpX[ch])
            hpX[ch] = x
            hpY[ch] = hpOut
            lpY[ch] += lpAlpha * (hpOut - lpY[ch])

            frameSum += lpY[ch]
            sampleIdx++
            if (sampleIdx % channels == 0L) {
                val mono = frameSum / channels
                frameSum = 0.0
                windowSumSq += mono * mono
                windowN++
                if (windowN >= windowTarget) flushWindow()
            }
        }

        private fun flushWindow() {
            val rms = sqrt(windowSumSq / windowN)
            val wStart = monoSamples.toDouble() / sampleRate
            val wEnd = wStart + windowN.toDouble() / sampleRate
            monoSamples += windowN
            windowSumSq = 0.0
            windowN = 0

            val loud = rms > NOISE_AMP
            if (!loud) {
                if (silenceStart < 0) silenceStart = wStart
            } else if (silenceStart >= 0) {
                // silence_end semantics: onset at the first loud window
                // after a silence of at least MIN_SILENCE_SEC
                if (wEnd - silenceStart >= MIN_SILENCE_SEC) onsets.add(wEnd)
                silenceStart = -1.0
            }
        }

        fun finish(): List<Double> {
            if (windowN > 0) flushWindow()
            onsets.sort()
            AppLog.d("ONSET", "silencedetect equivalent: ${onsets.size} onsets")
            return onsets
        }
    }

    /**
     * Find the bundled ffmpeg binary. Only a binary inside the app's own
     * files dir (installed by us) is trusted: scanning PATH or world-writable
     * locations like /data/local/tmp would execute whatever arbitrary binary
     * another app or adb left there, with this app's permissions. Returns
     * null when absent — the MediaCodec fallback takes over.
     */
    private fun resolveFfmpegPath(): String? {
        val appFiles = File(context.applicationInfo.dataDir, "ffmpeg")
        if (appFiles.isFile && appFiles.canExecute()) return appFiles.absolutePath
        AppLog.d("ONSET", "no ffmpeg binary found, will use MediaCodec fallback")
        return null
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runProcess(args: List<String>): ProcessOutput {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
            // Drain BOTH pipes concurrently: ffmpeg writes nearly everything
            // (incl. the silencedetect lines) to stderr, and a child blocked
            // on a full 64KB pipe while we read the other stream deadlocks
            // until the timeout.
            val stderrHolder = arrayOfNulls<String>(1)
            val stderrThread = Thread {
                stderrHolder[0] = try {
                    process.errorStream.bufferedReader().readText()
                } catch (e: Exception) {
                    ""
                }
            }
            stderrThread.start()
            val stdout = try {
                process.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                ""
            }
            val finished = process.waitFor(600, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                stderrThread.join(2000)
                ProcessOutput(-1, stdout, "timeout")
            } else {
                stderrThread.join()
                ProcessOutput(process.exitValue(), stdout, stderrHolder[0] ?: "")
            }
        } catch (e: Exception) {
            AppLog.e("ONSET", "process exec failed", e)
            ProcessOutput(-1, "", e.message ?: "unknown")
        }
    }
}
