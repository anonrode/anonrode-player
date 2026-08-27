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

interface SyncListener {
    fun onSyncLocked(offsetSeconds: Float, speedFactor: Float)
    fun onSyncNoMatch()
}

@UnstableApi
class AudioSyncProcessor(
    private val listener: SyncListener,
) : AudioProcessor {

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var active = false
    private var inputEnded = false
    private var outputEnded = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    @Volatile private var cues: List<SubtitleCue> = emptyList()
    // Sliding window over absolute media time: audioBins[i] covers
    // (baseIdx + i) * 100ms. The window slides forward once playback runs
    // past its end, so live sync keeps working deep into (or after a
    // resume far into) an episode instead of dying at a fixed cap.
    private val audioBins = FloatArray(40 * 10 * 2 + 15 * 60 * 10)
    private var baseIdx = 0
    private var binCount = 0
    @Volatile private var locked = false
    // Evaluation attempt cap: findOffset is ~10M ops and runs on the audio
    // render thread; after MAX_EVAL_ATTEMPTS consecutive attempts without a
    // lock we stop evaluating (gaveUp) until the next flush()/reset()/
    // position reset — seeks and episode switches re-arm naturally.
    private var failedEvals = 0
    private var gaveUp = false

    private var totalFrames: Long = 0
    @Volatile var startPositionMs: Long = 0

    // One 10ms analysis window; sized in configure() once the real sample
    // rate is known (320 = the old fixed size, kept as the floor).
    private var windowSamples: ShortArray = ShortArray(320)
    private var windowN = 0
    private var windowTarget = 0

    private var floor = 0.0
    private var peak = 0.0
    private var lastSpeech = 0.0

    private val driftTracker = DriftTracker()
    private var lastEvalPos = Long.MIN_VALUE
    private var stableHits = 0
    private var lastOffset = Double.NaN

    fun setCues(cues: List<SubtitleCue>) {
        this.cues = cues
        AppLog.d("SYNC", "setCues: ${cues.size} cues")
    }

    // Written from the main thread, consumed by the audio thread.
    @Volatile private var pendingResetPosition: Long? = null

    fun setStartPosition(positionMs: Long) {
        pendingResetPosition = positionMs
    }

    /** Applies a pending position reset on the audio thread. */
    private fun checkAndApplyReset() {
        val reset = pendingResetPosition ?: return
        pendingResetPosition = null
        startPositionMs = reset
        java.util.Arrays.fill(audioBins, 0f)
        baseIdx = 0; binCount = 0; totalFrames = 0
        windowN = 0; floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        failedEvals = 0; gaveUp = false
        locked = false
    }

    override fun configure(fmt: AudioFormat): AudioFormat {
        sampleRate = fmt.sampleRate; channelCount = fmt.channelCount
        active = fmt.encoding == C.ENCODING_PCM_16BIT && sampleRate > 0 && channelCount > 0
        if (active) {
            windowTarget = max(1, sampleRate / 100)
            // A full 10ms window at the real rate: windowTarget frames per
            // channel. The 320-sample floor keeps the old minimum for very
            // low rates; the fill loop guards on windowSamples.size.
            windowSamples = ShortArray(max(windowTarget * channelCount, 320))
            resetAll()
        }
        return fmt
    }

    override fun isActive() = active

    override fun queueInput(input: ByteBuffer) {
        if (!active) { input.position(input.limit()); return }
        val bytes = input.remaining(); if (bytes == 0) return

        val copy = input.duplicate(); copy.order(ByteOrder.nativeOrder())
        analyze(copy); input.position(input.limit())

        if (outputBuffer.capacity() < bytes)
            outputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        else outputBuffer.clear()
        val src = input.duplicate(); src.position(0)
        outputBuffer.put(src); outputBuffer.flip()
    }

    override fun queueEndOfStream() { inputEnded = true; outputEnded = outputBuffer.remaining() == 0 }
    override fun getOutput(): ByteBuffer = outputBuffer
    override fun isEnded(): Boolean = outputEnded

    override fun flush() {
        inputEnded = false; outputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        java.util.Arrays.fill(audioBins, 0f); baseIdx = 0; binCount = 0; totalFrames = 0
        windowN = 0; floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        failedEvals = 0; gaveUp = false
    }

    override fun reset() { flush(); cues = emptyList(); active = false }

    private fun resetAll() {
        java.util.Arrays.fill(audioBins, 0f); baseIdx = 0; binCount = 0; totalFrames = 0
        windowN = 0; floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        failedEvals = 0; gaveUp = false
        locked = false
    }

    private fun analyze(pcm: ByteBuffer) {
        checkAndApplyReset()
        if (locked) return
        val nCh = channelCount; val frameBytes = 2 * nCh
        while (pcm.remaining() >= frameBytes) {
            checkAndApplyReset()
            // fill one window
            for (ch in 0 until nCh) {
                if (windowN < windowSamples.size) {
                    windowSamples[windowN++] = pcm.short // pcm.short advances position by 2 bytes
                }
            }
            totalFrames += 1

            if (windowN >= min(windowSamples.size, windowTarget * nCh)) {
                val rms = sqrt(windowSamples.take(windowN).sumOf { it.toDouble() * it } / windowN)
                
                // multi-feature speech detection
                var zcr = 0; var prevSign = windowSamples[0] >= 0
                for (k in 0 until windowN) {
                    val curSign = windowSamples[k] >= 0
                    if (curSign != prevSign) zcr++
                    prevSign = curSign
                }
                val zcrNorm = zcr.toFloat() / windowN
                val meanAmp = windowSamples.take(windowN).map { abs(it.toFloat()) }.average().toFloat()
                val varianceBuf = windowSamples.take(windowN)
                    .map { d -> (d - meanAmp) * (d - meanAmp) }
                    .average().toFloat()
                val normVar = varianceBuf / max(meanAmp * meanAmp, 1f)

                val uf = floor * 1.08; val up = max(uf + 0.0012, peak)
                val energyScore = ((rms - uf) / (up - uf).coerceAtLeast(0.001)).coerceIn(0.0, 1.0).toFloat()
                val varianceScore = min(normVar / 2f, 1f)
                val zcrScore = when { zcrNorm in 0.02f..0.15f -> 1f; zcrNorm < 0.02f -> 0.5f; else -> max(0f, 1f - (zcrNorm - 0.15f) / 0.2f) }

                val sp = (energyScore * 0.5f + varianceScore * 0.3f + zcrScore * 0.2f).coerceIn(0f, 1f)
                lastSpeech = sp.toDouble() * 0.72 + lastSpeech * 0.28

                // adapt floor/peak
                if (floor == 0.0) { floor = rms; peak = rms*1.9+0.0001 }
                else { floor = floor*0.986 + min(rms, floor*1.45)*0.014; peak = max(floor+0.00035, max(peak*0.992, rms)) }
                
                windowN = 0
                accumulateBin(sp)
            }
        }
    }

    private var varianceBuf: Float = 0f

    private fun accumulateBin(speech: Float) {
        val posMs = startPositionMs + totalFrames * 1000L / max(sampleRate, 1)
        val idx = (posMs / 100).toInt()
        if (idx < 0 || idx < baseIdx) return
        if (idx - baseIdx >= audioBins.size) {
            if (binCount == 0) {
                // Fresh window far into the media (mid-episode resume):
                // anchor the window at the current position so the data
                // grows from index 0 and cross-half validation stays sane.
                baseIdx = idx
                java.util.Arrays.fill(audioBins, 0f)
            } else {
                // Slide forward just enough to fit the new bin at the end.
                val newBase = idx - audioBins.size + 1
                val shift = newBase - baseIdx
                if (shift >= audioBins.size) {
                    java.util.Arrays.fill(audioBins, 0f)
                    binCount = 0
                } else {
                    System.arraycopy(audioBins, shift, audioBins, 0, audioBins.size - shift)
                    java.util.Arrays.fill(audioBins, audioBins.size - shift, audioBins.size, 0f)
                    binCount = max(0, binCount - shift)
                }
                baseIdx = newBase
            }
        }
        val rel = idx - baseIdx
        binCount = max(binCount, rel + 1)
        audioBins[rel] = max(audioBins[rel], speech)

        if (posMs - lastEvalPos >= 1400 || lastEvalPos == Long.MIN_VALUE) {
            if (lastEvalPos == Long.MIN_VALUE) lastEvalPos = posMs
            else {
                lastEvalPos = posMs
                val minBins = (SpeechCorrelator.MIN_AUDIO_SECONDS / SpeechCorrelator.ALIGN_BIN).toInt()
                if (binCount >= minBins && cues.isNotEmpty() && !gaveUp) {
                    evaluate(posMs)
                }
            }
        }
    }

    private fun evaluate(posMs: Long) {
        val result = SpeechCorrelator.findOffset(
            audioBins, binCount, cues,
            baseSeconds = baseIdx * SpeechCorrelator.ALIGN_BIN,
        ) ?: run {
            countFailedEval(posMs)
            return
        }
        
        stableHits = if (!lastOffset.isNaN() &&
            abs(result.offsetSeconds - lastOffset) <= 0.25) stableHits + 1 else 1
        lastOffset = result.offsetSeconds
        
        // drift detection from segment offsets
        driftTracker.add(posMs / 1000.0, result.offsetSeconds)
        val (base, speedF) = driftTracker.getCorrection(posMs / 1000.0)
        
        AppLog.d("SYNC", "eval t=${posMs/1000}s off=${result.offsetSeconds}s speed=$speedF hits=$stableHits")

        if (stableHits >= 2) {
            locked = true
            listener.onSyncLocked(base.toFloat(), speedF)
        } else {
            countFailedEval(posMs)
        }
    }

    /**
     * Counts an evaluation that returned without locking; once the cap is
     * reached, [gaveUp] stops further (expensive) attempts until the next
     * flush()/reset()/position reset.
     */
    private fun countFailedEval(posMs: Long) {
        failedEvals++
        if (failedEvals >= MAX_EVAL_ATTEMPTS) {
            gaveUp = true
            AppLog.d("SYNC", "no lock after $failedEvals attempts, giving up at t=${posMs / 1000}s")
        }
    }

    companion object {
        /** Consecutive no-lock evaluations before we stop trying. */
        private const val MAX_EVAL_ATTEMPTS = 40

        fun min(a: Double, b: Double): Double = if (a < b) a else b
        fun max(a: Long, b: Int): Long = if (a > b) a else b.toLong()
        fun max(a: Double, b: Double): Double = if (a > b) a else b
        fun max(a: Float, b: Float): Float = if (a > b) a else b
        fun max(a: Int, b: Int): Int = if (a > b) a else b
    }
}
