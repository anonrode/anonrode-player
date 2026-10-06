package dev.anonrode.player.core.media.sync

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
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

    /**
     * v0.8 P2-2: the decode budget is CALLER-SCALED now. The old fixed
     * 600 s cap truncated 2 h movies at the 10–20× realtime claimed speed
     * on low-end SoCs — and the partial onset set then FAILED the gates,
     * so the video was marked as an unsyncable VERDICT when the real story
     * was "we stopped listening early". The job sets this from the video's
     * duration; the default keeps short files on the old floor.
     */
    var decodeTimeoutMs: Long = DECODE_TIMEOUT_MS

    /** True when the last [extractSources]/[extract] stopped at the budget
     *  instead of the end of stream — the job must NOT treat that as a
     *  verdict (it retries with a bigger allowance instead). */
    @Volatile var lastDecodeTruncated = false
        private set

    /** True when the last MediaCodec pass reached the actual end of the audio track. */
    @Volatile var isEndOfStream = false
        private set

    /** v0.8.3: absolute media time (s) the last [extractSources] pass
     *  covered, resumable or not. The fingerprint job stores it in the
     *  onset cache so the NEXT attempt seeks straight to it instead of
     *  re-decoding the first 15 minutes for the third time (09-15 log:
     *  every budget-truncated attempt restarted the decode from zero, and
     *  a process-death retry threw a finished extraction away entirely).
     *  Meaningful only when [lastDecodeTruncated] is true; on a complete
     *  pass the cache is flagged complete and this is ignored. */
    @Volatile var lastCoveredSec = 0.0
        private set

    /** Both onset sources from one pass over the file. */
    data class OnsetSources(
        val silencedetect: List<Double>,
        val vad: List<Double>,
        val envelope: FloatArray = FloatArray(0),
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
     * [videoUri] (v0.8 P1-5) is the content:// fallback used for the
     * MediaCodec decode when the path is missing/unreadable.
     *
     * [resumeFromSec] seeks the MediaCodec pass straight to that
     * absolute media time and shifts both detectors' onset clocks to match.
     * [maxMediaDurationSec] caps the decoding pass to a specific span of media
     * presentation time (e.g. 600s for Tier 1 fast lock, 300s for Tier 2 chunks),
     * preventing 45-minute battery thrashing on low-end SoCs. -1 decodes to EOF.
     */
    fun extractSources(
        videoPath: String? = null,
        videoUri: Uri? = null,
        resumeFromSec: Double = -1.0,
        maxMediaDurationSec: Double = -1.0,
        includeVad: Boolean = true,
        isCancelled: () -> Boolean = { false },
    ): OnsetSources {
        lastDecodeTruncated = false
        isEndOfStream = false
        lastCoveredSec = 0.0
        val sil = if (resumeFromSec < 0 && !videoPath.isNullOrEmpty()) resolveFfmpegPath()?.let {
            extractWithFfmpeg(it, videoPath, if (maxMediaDurationSec > 0.0) maxMediaDurationSec else 0.0)
        } else null
        val vadAvailable = includeVad && SileroVad.modelAvailable(context)
        if (sil != null && !vadAvailable) {
            lastCoveredSec = if (maxMediaDurationSec > 0.0) maxMediaDurationSec else 0.0
            return OnsetSources(sil, emptyList())
        }

        // Single MediaCodec decode pass feeding detectors.
        val silence = SilenceState()
        val vad = if (vadAvailable) SileroVad(context) else null
        // The resumed segment's onset clock must start where the decoder
        // actually begins emitting samples (post-seek pts of the FIRST
        // output buffer), not at the nominal seek target — the detectors
        // are created before the loop, so the offset is injected on the
        // first callback below.
        var offsetSec = 0.0
        var offsetSet = false
        decodeAudio(videoPath, videoUri, resumeFromSec, maxMediaDurationSec, isCancelled) { buf, sr, ch, isFloat, ptsUs ->
            if (isCancelled()) return@decodeAudio false
            if (!offsetSet) {
                if (resumeFromSec >= 0.0) {
                    offsetSec = if (ptsUs > 0L) ptsUs / 1_000_000.0 else resumeFromSec
                    silence.onsetOffset = offsetSec
                    vad?.onsetOffsetSec = offsetSec
                }
                offsetSet = true
            }
            silence.process(buf, sr, ch, isFloat)
            vad?.processPcm(buf, sr, ch, isFloat)
            !isCancelled()
        }
        val silOnsets = sil ?: silence.finish()
        val (vadOnsets, vadEnvelope) = if (vad != null) {
            try {
                Pair(vad.finish(), vad.getSpeechEnvelope())
            } finally {
                vad.close()
            }
        } else Pair(emptyList(), FloatArray(0))
        lastCoveredSec = if (vad != null) vad.coveredSec() else silence.coveredSec()
        if (isCancelled()) {
            lastDecodeTruncated = true
        }
        return OnsetSources(silOnsets, vadOnsets, vadEnvelope)
    }

    /**
     * Spot-Sync fast seek probe (<500ms): decodes a short window (default 6s)
     * at [positionSec] and matches local cues via [SyncFinder.findBestShift].
     */
    fun probeSpotSync(
        videoPath: String?,
        videoUri: Uri? = null,
        cues: List<SubtitleCue>,
        positionSec: Double,
        activeOffsetSec: Double = 0.0,
        probeDurationSec: Double = 6.0,
        radiusSec: Double = 60.0,
        isCancelled: () -> Boolean = { false },
    ): Double? {
        if (cues.isEmpty() || positionSec < 0.0 || (videoPath.isNullOrEmpty() && videoUri == null)) return null
        val sources = extractSources(
            videoPath = videoPath,
            videoUri = videoUri,
            includeVad = true,
            resumeFromSec = positionSec,
            maxMediaDurationSec = probeDurationSec,
            isCancelled = isCancelled,
        )
        if (isCancelled()) return null

        val audioOnsets = sources.hybrid
        if (audioOnsets.isEmpty()) return null

        val minAudio = audioOnsets.minOrNull() ?: positionSec
        val maxAudio = audioOnsets.maxOrNull() ?: (positionSec + probeDurationSec)

        val startB = activeOffsetSec - radiusSec
        val endB = activeOffsetSec + radiusSec

        // Filter cues that can match within the probe search range
        // audio_time = cue.start + beta => cue.start = audio_time - beta
        val minCue = minAudio - endB - 1.0
        val maxCue = maxAudio - startB + 1.0
        val candidateCues = cues.filter { it.start in minCue..maxCue }
        if (candidateCues.isEmpty()) return null

        val sortedOnsets = audioOnsets.sorted()
        val sortedStarts = candidateCues.map { it.start }.sorted()

        var bestBeta = activeOffsetSec
        var bestScore = 0.0
        var bestHits = 0
        var bestLocalCues = 0

        var b = startB
        while (b <= endB + 1e-9) {
            var hits = 0
            var localCueCount = 0
            for (s in sortedStarts) {
                val t = s + b
                if (t in (minAudio - 0.5)..(maxAudio + 0.5)) {
                    localCueCount++
                    val k = SyncFinder.lowerBound(sortedOnsets, t)
                    if ((k > 0 && abs(sortedOnsets[k - 1] - t) <= 0.35) ||
                        (k < sortedOnsets.size && abs(sortedOnsets[k] - t) <= 0.35)) {
                        hits++
                    }
                }
            }
            if (hits > 0 && localCueCount > 0) {
                val recall = hits.toDouble() / localCueCount
                val precision = hits.toDouble() / sortedOnsets.size
                val score = 0.5 * recall + 0.5 * precision
                if (score > bestScore) {
                    bestScore = score
                    bestBeta = b
                    bestHits = hits
                    bestLocalCues = localCueCount
                }
            }
            b += 0.1
        }

        // Refine peak around bestBeta
        var bf = bestBeta - 0.1
        val fineEnd = bestBeta + 0.1
        while (bf <= fineEnd + 1e-9) {
            var hits = 0
            var localCueCount = 0
            for (s in sortedStarts) {
                val t = s + bf
                if (t in (minAudio - 0.5)..(maxAudio + 0.5)) {
                    localCueCount++
                    val k = SyncFinder.lowerBound(sortedOnsets, t)
                    if ((k > 0 && abs(sortedOnsets[k - 1] - t) <= 0.35) ||
                        (k < sortedOnsets.size && abs(sortedOnsets[k] - t) <= 0.35)) {
                        hits++
                    }
                }
            }
            if (hits > 0 && localCueCount > 0) {
                val recall = hits.toDouble() / localCueCount
                val precision = hits.toDouble() / sortedOnsets.size
                val score = 0.5 * recall + 0.5 * precision
                if (score > bestScore) {
                    bestScore = score
                    bestBeta = bf
                    bestHits = hits
                    bestLocalCues = localCueCount
                }
            }
            bf += 0.02
        }

        // Runner-up check outside +-2.0s
        var runner = 0.0
        b = startB
        while (b <= endB + 1e-9) {
            if (abs(b - bestBeta) > 2.0) {
                var hits = 0
                var localCueCount = 0
                for (s in sortedStarts) {
                    val t = s + b
                    if (t in (minAudio - 0.5)..(maxAudio + 0.5)) {
                        localCueCount++
                        val k = SyncFinder.lowerBound(sortedOnsets, t)
                        if ((k > 0 && abs(sortedOnsets[k - 1] - t) <= 0.35) ||
                            (k < sortedOnsets.size && abs(sortedOnsets[k] - t) <= 0.35)) {
                            hits++
                        }
                    }
                }
                if (hits > 0 && localCueCount > 0) {
                    val recall = hits.toDouble() / localCueCount
                    val precision = hits.toDouble() / sortedOnsets.size
                    val score = 0.5 * recall + 0.5 * precision
                    if (score > runner) runner = score
                }
            }
            b += 0.1
        }

        val margin = bestScore - runner
        val recall = if (bestLocalCues > 0) bestHits.toDouble() / bestLocalCues else 0.0
        val precision = if (sortedOnsets.isNotEmpty()) bestHits.toDouble() / sortedOnsets.size else 0.0

        if (bestHits >= 3 && bestLocalCues >= 3 && recall >= 0.60 && precision >= 0.40 && margin >= 0.08) {
            AppLog.d("SPOT_SYNC", "spot probe locked: beta=%.3fs hits=%d localCues=%d recall=%.2f prec=%.2f margin=%.2f".format(bestBeta, bestHits, bestLocalCues, recall, precision, margin))
            return bestBeta
        }
        AppLog.d("SPOT_SYNC", "spot probe refused at pos=%.1fs: bestBeta=%.3fs hits=%d/%d recall=%.2f prec=%.2f margin=%.2f".format(positionSec, bestBeta, bestHits, bestLocalCues, recall, precision, margin))
        return null
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
        decodeAudio(videoPath, null) { buf, sr, ch, isFloat, _ ->
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
        const val MIN_SILENCE_SEC = 0.18
    }

    /**
     * Decode the first audio track of [videoPath] to PCM, invoking
     * [onPcm] for every output buffer (positioned: offset applied,
     * limit = end of valid data, plus the buffer's presentation time in
     * microseconds on the absolute media clock). Return false from
     * [onPcm] to stop early. Runs the whole file; single-threaded,
     * blocking — call from a background worker.
     *
     * [resumeFromSec] (v0.8.3) seeks the extractor to that absolute media
     * time first; the loop itself is unchanged, so the resumed pass covers
     * exactly the tail the previous budget-truncated pass could not.
     */
    private fun decodeAudio(
        videoPath: String?,
        videoUri: Uri?,
        resumeFromSec: Double = -1.0,
        maxMediaDurationSec: Double = -1.0,
        isCancelled: () -> Boolean = { false },
        onPcm: (buf: ByteBuffer, sampleRate: Int, channels: Int, isFloat: Boolean, ptsUs: Long) -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        lastDecodeTruncated = false
        isEndOfStream = false
        try {
            // v0.8 P1-5: prefer the resolved path (what the reference
            // pipeline was validated on), fall back to the content URI so
            // files MediaStore's DATA column can't describe — or that this
            // process can't open() directly — still get decoded through
            // the resolver, instead of failing the whole fingerprint.
            when {
                videoUri != null -> {
                    val opened = try {
                        context.contentResolver.openFileDescriptor(videoUri, "r")?.use { pfd ->
                            extractor.setDataSource(pfd.fileDescriptor)
                            true
                        } ?: false
                    } catch (t: Throwable) {
                        AppLog.d("ONSET", "openFileDescriptor failed for $videoUri, falling back to path: ${t.message}")
                        false
                    }
                    if (!opened) {
                        if (videoPath != null && File(videoPath).canRead()) {
                            extractor.setDataSource(videoPath)
                        } else {
                            extractor.setDataSource(context, videoUri, null)
                        }
                    }
                }
                videoPath != null && File(videoPath).canRead() ->
                    extractor.setDataSource(videoPath)
                else ->
                    // No readable path AND no URI: a programming error, but
                    // let the extractor throw with the real reason inside
                    // the surrounding catch.
                    extractor.setDataSource(videoPath!!)
            }
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
            if (resumeFromSec >= 0.0) {
                // SEEK_TO_CLOSEST_SYNC + a decoder flush: the first decoded
                // output lands at (or just before) the seek target; the
                // caller timestamps the resumed onsets from the first
                // buffer's actual presentation time, not this nominal one.
                try {
                    extractor.seekTo((resumeFromSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                } catch (t: Throwable) {
                    AppLog.e("ONSET", "resume seek failed, decoding from start", t)
                }
            }

            // Null surface + releaseOutputBuffer(idx, false) means the
            // decoder is never paced to a render timeline — the loop below
            // drains PCM as fast as the codec produces it (CPU-bound,
            // typically 10-20x realtime for audio), which is what makes
            // the whole-file fingerprint pass tractable.
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = 0
            var channels = 0
            var isFloat = false
            val targetEndPtsUs = if (maxMediaDurationSec > 0.0) {
                ((if (resumeFromSec > 0.0) resumeFromSec else 0.0) + maxMediaDurationSec) * 1_000_000.0
            } else -1.0
            val t0 = System.currentTimeMillis()

            while (!outputDone) {
                if (isCancelled()) {
                    AppLog.d("ONSET", "decode cancelled by caller, aborting")
                    lastDecodeTruncated = true
                    break
                }
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
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        isEndOfStream = true
                    }
                    if (info.size > 0 && sampleRate > 0 && channels > 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (!onPcm(buf, sampleRate, channels, isFloat, info.presentationTimeUs)) {
                                outputDone = true
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (targetEndPtsUs > 0.0 && info.presentationTimeUs >= targetEndPtsUs) {
                        outputDone = true
                    }
                }

                if (System.currentTimeMillis() - t0 > decodeTimeoutMs) {
                    AppLog.e("ONSET", "decode stopped at budget ${decodeTimeoutMs}ms before end of stream")
                    lastDecodeTruncated = true
                    break
                }
            }
            if (isCancelled() || (!outputDone && !isEndOfStream)) {
                lastDecodeTruncated = true
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

        /** v0.8.3: absolute media time the first decoded sample belongs to
         *  (non-zero only on a resumed pass); added to every onset time so
         *  they line up with the cached prefix. */
        var onsetOffset = 0.0

        // 1-pole bandpass state (mono downmix)
        private var hpBeta = 0.0
        private var lpAlpha = 0.0
        private var hpX = 0.0
        private var hpY = 0.0
        private var lpY = 0.0

        // window + silence machine
        private var windowTarget = 0
        private var windowSumSq = 0.0
        private var windowN = 0
        private var monoSamples = 0L
        private var silenceStart = -1.0

        // adaptive dynamic noise floor
        private var floorRms = 0.02
        private val floorLeak = 1.0005
        private val staticFloor = 10.0.pow(-36.0 / 20.0) // ~0.0158

        val limitReached: Boolean
            get() = maxSeconds > 0 && sampleRate > 0 &&
                monoSamples.toDouble() / sampleRate >= maxSeconds

        private fun configure(rate: Int, ch: Int) {
            sampleRate = rate
            channels = ch
            hpBeta = exp(-2.0 * PI * 300.0 / sampleRate)
            lpAlpha = 1.0 - exp(-2.0 * PI * 3400.0 / sampleRate)
            hpX = 0.0
            hpY = 0.0
            lpY = 0.0
            windowTarget = sampleRate / 100 // 10ms windows
        }

        fun process(buf: ByteBuffer, rate: Int, ch: Int, isFloat: Boolean) {
            if (sampleRate == 0) configure(rate, ch)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val frames = buf.remaining() / (if (isFloat) 4 else 2) / channels
            if (isFloat) {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    var sum = 0.0
                    for (c in 0 until channels) sum += fb.get(i * channels + c).toDouble()
                    onMonoSample(sum / channels)
                }
            } else {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += sb.get(i * channels + c)
                    onMonoSample(sum / channels.toDouble() / 32768.0)
                }
            }
        }

        private fun onMonoSample(mono: Double) {
            // 1-pole highpass then lowpass (ffmpeg bandpass approximation)
            val hpOut = hpBeta * (hpY + mono - hpX)
            hpX = mono
            hpY = hpOut
            lpY += lpAlpha * (hpOut - lpY)

            windowSumSq += lpY * lpY
            windowN++
            if (windowN >= windowTarget) flushWindow()
        }

        private fun flushWindow() {
            val rms = sqrt(windowSumSq / windowN)
            val wStart = onsetOffset + monoSamples.toDouble() / sampleRate
            val wEnd = wStart + windowN.toDouble() / sampleRate
            monoSamples += windowN
            windowSumSq = 0.0
            windowN = 0

            // Update adaptive background noise floor: drops quickly on quiet, leaks up slowly
            if (rms < floorRms) {
                floorRms = (rms * 0.9 + floorRms * 0.1).coerceAtLeast(staticFloor)
            } else {
                floorRms = (floorRms * floorLeak).coerceAtMost(0.15)
            }

            // Dynamic speech threshold: +4 dB above local noise floor (x1.6) or static floor
            val dynamicThreshold = maxOf(staticFloor, floorRms * 1.6)
            val loud = rms > dynamicThreshold

            if (!loud) {
                if (silenceStart < 0) silenceStart = wStart
            } else if (silenceStart >= 0) {
                // silence_end semantics: onset at the first loud window
                // after a silence of at least MIN_SILENCE_SEC (180ms)
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

        /** Absolute media time (s) processed so far (call after [finish]). */
        fun coveredSec(): Double =
            if (sampleRate > 0) onsetOffset + monoSamples.toDouble() / sampleRate else onsetOffset
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
