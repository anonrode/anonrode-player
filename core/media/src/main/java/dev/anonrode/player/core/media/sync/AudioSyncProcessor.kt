package dev.anonrode.player.core.media.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Callbacks from the live sync analysis. */
interface SyncListener {
    /** A validated, stable offset was found. */
    fun onSyncLocked(offsetSeconds: Float)
    /** Analysis finished with no lockable alignment. */
    fun onSyncNoMatch()
}

/**
 * Live subtitle auto-sync: a pass-through Media3 [AudioProcessor] that turns
 * playback PCM into a binary speech-activity track (adaptive floor/peak VAD,
 * 100ms bins) and periodically runs [SpeechCorrelator] against the subtitle
 * cues.
 *
 * THREADING (critical): ExoPlayer methods throw when called off the main
 * thread, and queueInput runs on the audio thread. Media time is therefore
 * derived by COUNTING SAMPLES (frames × 1000 / sampleRate) offset by
 * [startPositionMs], which is set from the main thread via [setStartPosition]
 * (wired to onPositionDiscontinuity for seeks). The player is never touched
 * from this thread.
 *
 * Pass-through: input is copied to output unchanged. Buffers are reused to
 * avoid per-buffer allocation churn.
 */
@UnstableApi
class AudioSyncProcessor(
    private val listener: SyncListener,
) : AudioProcessor {

    // ── configuration ──────────────────────────────────────────────
    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var active = false
    private var inputEnded = false
    private var outputEnded = false

    // ── pass-through buffering (reused, grown on demand) ───────────
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // ── analysis state ─────────────────────────────────────────────
    @Volatile private var cues: List<SubtitleCue> = emptyList()
    private val audioBins = FloatArray(40 * 10 * 2 + 15 * 60 * 10) // ±40s search + 15min @ 0.1s
    private var binCount = 0
    @Volatile private var locked = false

    // sample-count clock (audio thread only)
    private var totalFrames: Long = 0

    // set from main thread; read from audio thread
    @Volatile var startPositionMs: Long = 0

    // per-window accumulation (10ms RMS windows)
    private var windowSamples = 0
    private var windowSumSq = 0.0
    private var windowTarget = 0
    private var floor = 0.0
    private var peak = 0.0
    private var lastSpeech = 0.0

    private var lastEvalFrames = Long.MIN_VALUE
    private var stableHits = 0
    private var lastOffset = Double.NaN

    /** Main thread: media position the next flushed buffer starts at. */
    fun setStartPosition(positionMs: Long) {
        startPositionMs = positionMs
        totalFrames = 0
        java.util.Arrays.fill(audioBins, 0f)
        binCount = 0
        windowSamples = 0
        windowSumSq = 0.0
        floor = 0.0
        peak = 0.0
        lastSpeech = 0.0
        lastEvalFrames = Long.MIN_VALUE
        stableHits = 0
        lastOffset = Double.NaN
    }

    fun setCues(cues: List<SubtitleCue>) {
        this.cues = cues
        AppLog.d("SYNC", "setCues: " + cues.size + " cues, locked=" + locked)
    }

    // ── AudioProcessor ─────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            sampleRate > 0 && channelCount > 0
        if (active) {
            windowTarget = max(1, sampleRate / 100) // 10ms RMS windows
            java.util.Arrays.fill(audioBins, 0f)
            binCount = 0
            totalFrames = 0
            windowSamples = 0
            windowSumSq = 0.0
            floor = 0.0
            peak = 0.0
            lastSpeech = 0.0
            lastEvalFrames = Long.MIN_VALUE
            stableHits = 0
            lastOffset = Double.NaN
        }
        return inputAudioFormat // pass-through
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return

        val copy = inputBuffer.duplicate()
        copy.order(ByteOrder.nativeOrder())
        analyze(copy)
        inputBuffer.position(inputBuffer.limit())

        // pass-through: reuse/replace pending output (contract: previous
        // output was fully consumed by the sink before more input arrives)
        if (outputBuffer.capacity() < bytes) {
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        val src = inputBuffer.duplicate()
        src.position(0)
        outputBuffer.put(src)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
        outputEnded = outputBuffer.remaining() == 0
    }

    override fun getOutput(): ByteBuffer = outputBuffer

    override fun isEnded(): Boolean = outputEnded

    override fun flush() {
        inputEnded = false
        outputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        // Keep the VAD track; startPositionMs will be refreshed from main
        // via onPositionDiscontinuity → setStartPosition on seeks.
        java.util.Arrays.fill(audioBins, 0f)
        binCount = 0
        totalFrames = 0
        windowSamples = 0
        windowSumSq = 0.0
        floor = 0.0
        peak = 0.0
        lastSpeech = 0.0
        lastEvalFrames = Long.MIN_VALUE
        stableHits = 0
        lastOffset = Double.NaN
    }

    override fun reset() {
        flush()
        cues = emptyList()
        active = false
    }

    // ── analysis (audio thread) ────────────────────────────────────

    private fun analyze(pcm: ByteBuffer) {
        if (locked) return
        val nCh = channelCount

        while (pcm.remaining() >= 2 * nCh) {
            var sum = 0.0
            repeat(nCh) {
                val v = pcm.short.toDouble() / 32768.0
                sum += v * v
                pcm.position(pcm.position() + 2)
            }
            windowSumSq += sum
            windowSamples++
            totalFrames += nCh

            if (windowSamples >= windowTarget) {
                val rms = sqrt(windowSumSq / (windowSamples * nCh))
                windowSamples = 0
                windowSumSq = 0.0
                accumulateWindow(rms)
            }
        }
    }

    private fun currentPosMs(): Long =
        startPositionMs + totalFrames * 1000L / sampleRate.coerceAtLeast(1)

    private fun accumulateWindow(rms: Double) {
        if (floor == 0.0) {
            floor = rms
            peak = rms * 1.9 + 0.0001
        } else {
            floor = floor * 0.986 + min(rms, floor * 1.45) * 0.014
            peak = max(floor + 0.00035, max(peak * 0.992, rms))
        }

        val usableFloor = floor * 1.08
        val usablePeak = max(usableFloor + 0.0012, peak)
        val speech = ((rms - usableFloor) / (usablePeak - usableFloor)).coerceIn(0.0, 1.0)

        val posMs = currentPosMs()
        val idx = (posMs / 100).toInt().coerceIn(0, audioBins.size - 1)
        binCount = max(binCount, idx + 1)
        audioBins[idx] = max(audioBins[idx], speech.toFloat())

        maybeEvaluate(posMs)
    }

    private fun maybeEvaluate(posMs: Long) {
        if (posMs - lastEvalFrames < 1400) return
        lastEvalFrames = posMs
        if (binCount < (SpeechCorrelator.MIN_AUDIO_SECONDS / SpeechCorrelator.ALIGN_BIN).toInt()) return
        if (cues.isEmpty()) return

        val result = SpeechCorrelator.findOffset(audioBins, binCount, cues) ?: return

        stableHits = if (!lastOffset.isNaN() &&
            abs(result.offsetSeconds - lastOffset) <= 0.25
        ) stableHits + 1 else 1
        lastOffset = result.offsetSeconds

        if (stableHits >= 2) {
            locked = true
            AppLog.d("SYNC", "LOCKED offset=" + result.offsetSeconds + "s")
            listener.onSyncLocked(result.offsetSeconds.toFloat())
        }
    }
}
