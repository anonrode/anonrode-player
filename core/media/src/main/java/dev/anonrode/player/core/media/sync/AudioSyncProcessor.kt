package dev.anonrode.player.core.media.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Algorithm validated in tools/subtitle_engine_sim.py (23/23 cases) before
 * implementation: ffsubsync-style cross-correlation + margin/containment/
 * cross-half-validation gates. Runs silently; the only outward signal is
 * [SyncListener.onSyncLocked].
 *
 * Pass-through: input is copied to output unchanged, so playback audio is
 * never altered. Non-16-bit-PCM formats report inactive and are bypassed.
 */
@UnstableApi
class AudioSyncProcessor(
    /** Current playback position in ms, maps bins to media time. */
    private val positionProvider: () -> Long,
    private val listener: SyncListener,
) : AudioProcessor {

    // ── configuration ──────────────────────────────────────────────
    private var sampleRate = 0
    private var channelCount = 0
    private var active = false
    private var inputEnded = false
    private var outputEnded = false

    // ── pass-through buffering ─────────────────────────────────────
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // ── analysis state ─────────────────────────────────────────────
    private var cues: List<SubtitleCue> = emptyList()
    private val audioBins = FloatArray(SpeechCorrelator.MAX_OFFSET_SEC.toInt() * 10 * 2 +
        (15 * 60 * 10)) // covers ±40s search + 15 min of media at 0.1s bins
    private var binCount = 0
    private var locked = false

    // per-window accumulation (10ms RMS windows → 100ms bins)
    private var windowSamples = 0
    private var windowSumSq = 0.0
    private var windowTarget = 0
    private var floor = 0.0
    private var peak = 0.0
    private var lastSpeech = 0.0

    private var lastEvalPosMs = 0L
    private var stableHits = 0
    private var lastOffset = Double.NaN

    fun setCues(cues: List<SubtitleCue>) {
        this.cues = cues
        resetAnalysis()
    }

    private fun resetAnalysis() {
        audioBins.fill(0f)
        binCount = 0
        windowSamples = 0
        windowSumSq = 0.0
        floor = 0.0
        peak = 0.0
        lastSpeech = 0.0
        lastEvalPosMs = 0L
        stableHits = 0
        lastOffset = Double.NaN
        locked = false
    }

    // ── AudioProcessor ─────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        active = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            sampleRate > 0 && channelCount > 0
        if (active) {
            windowTarget = max(1, sampleRate / 100) // 10ms RMS windows
            resetAnalysis()
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

        // Contract: previous output was consumed by the caller.
        val copy = inputBuffer.duplicate()
        copy.order(ByteOrder.nativeOrder())
        analyze(copy)
        inputBuffer.position(inputBuffer.limit())

        outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val src = inputBuffer.duplicate()
        src.position(0)
        outputBuffer.put(src)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
        outputEnded = outputBuffer.remaining() == 0
        finishIfDone()
    }

    override fun getOutput(): ByteBuffer = outputBuffer

    override fun isEnded(): Boolean = outputEnded

    override fun flush() {
        inputEnded = false
        outputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        // Keep the VAD track and any lock across seeks; the position
        // provider re-maps new samples to the correct bins automatically.
    }

    override fun reset() {
        flush()
        cues = emptyList()
        resetAnalysis()
        active = false
    }

    // ── analysis ───────────────────────────────────────────────────

    private fun analyze(pcm: ByteBuffer) {
        if (locked) return // done; stop burning CPU
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

            if (windowSamples >= windowTarget) {
                val rms = sqrt(windowSumSq / (windowSamples * nCh))
                windowSamples = 0
                windowSumSq = 0.0
                accumulateWindow(rms)
            }
        }
    }

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

        val posMs = positionProvider()
        val idx = (posMs / 100).toInt().coerceIn(0, audioBins.size - 1)
        binCount = max(binCount, idx + 1)
        audioBins[idx] = max(audioBins[idx], speech.toFloat())

        maybeEvaluate(posMs)
    }

    private fun maybeEvaluate(posMs: Long) {
        if (posMs - lastEvalPosMs < 1400) return
        lastEvalPosMs = posMs
        if (binCount < (SpeechCorrelator.MIN_AUDIO_SECONDS / SpeechCorrelator.ALIGN_BIN).toInt()) return
        if (cues.isEmpty()) return

        val result = SpeechCorrelator.findOffset(audioBins, binCount, cues) ?: return

        // Stability: two consecutive evaluations agreeing within 0.25s.
        stableHits = if (!lastOffset.isNaN() &&
            abs(result.offsetSeconds - lastOffset) <= 0.25
        ) stableHits + 1 else 1
        lastOffset = result.offsetSeconds

        if (stableHits >= 2) {
            locked = true
            listener.onSyncLocked(result.offsetSeconds.toFloat())
        }
    }

    private fun finishIfDone() {
        if (locked || cues.isEmpty()) return
        val result = SpeechCorrelator.findOffset(audioBins, binCount, cues)
        if (result != null) {
            locked = true
            listener.onSyncLocked(result.offsetSeconds.toFloat())
        } else {
            listener.onSyncNoMatch()
        }
    }
}
