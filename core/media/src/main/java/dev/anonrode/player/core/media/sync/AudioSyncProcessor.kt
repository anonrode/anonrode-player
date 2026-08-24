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
    private val audioBins = FloatArray(40 * 10 * 2 + 15 * 60 * 10)
    private var binCount = 0
    @Volatile private var locked = false

    private var totalFrames: Long = 0
    @Volatile var startPositionMs: Long = 0

    private val windowSamples: ShortArray = ShortArray(320) // max 10ms @ 32kHz stereo
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

    fun setStartPosition(positionMs: Long) {
        startPositionMs = positionMs
        totalFrames = 0
        java.util.Arrays.fill(audioBins, 0f)
        binCount = 0; windowN = 0
        floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        locked = false
    }

    override fun configure(fmt: AudioFormat): AudioFormat {
        sampleRate = fmt.sampleRate; channelCount = fmt.channelCount
        active = fmt.encoding == C.ENCODING_PCM_16BIT && sampleRate > 0 && channelCount > 0
        if (active) {
            windowTarget = max(1, sampleRate / 100)
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
        java.util.Arrays.fill(audioBins, 0f); binCount = 0; totalFrames = 0
        windowN = 0; floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
    }

    override fun reset() { flush(); cues = emptyList(); active = false }

    private fun resetAll() {
        java.util.Arrays.fill(audioBins, 0f); binCount = 0; totalFrames = 0
        windowN = 0; floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        locked = false
    }

    private fun analyze(pcm: ByteBuffer) {
        if (locked) return
        val nCh = channelCount; val frameBytes = 2 * nCh
        while (pcm.remaining() >= frameBytes) {
            // fill one window
            for (ch in 0 until nCh) {
                if (windowN < windowSamples.size) {
                    windowSamples[windowN++] = pcm.short
                    pcm.position(pcm.position() + 2)
                }
            }
            totalFrames += nCh

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
        val idx = (posMs / 100).toInt().coerceIn(0, audioBins.size - 1)
        binCount = max(binCount, idx + 1)
        audioBins[idx] = max(audioBins[idx], speech)
        
        if (posMs - lastEvalPos >= 1400 || lastEvalPos == Long.MIN_VALUE) {
            if (lastEvalPos == Long.MIN_VALUE) lastEvalPos = posMs
            else {
                lastEvalPos = posMs
                val minBins = (SpeechCorrelator.MIN_AUDIO_SECONDS / SpeechCorrelator.ALIGN_BIN).toInt()
                if (binCount >= minBins && cues.isNotEmpty()) {
                    evaluate(posMs)
                }
            }
        }
    }

    private fun evaluate(posMs: Long) {
        val result = SpeechCorrelator.findOffset(audioBins, binCount, cues) ?: return
        
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
        }
    }

    companion object {
        fun min(a: Double, b: Double): Double = if (a < b) a else b
        fun max(a: Long, b: Int): Long = if (a > b) a else b.toLong()
        fun max(a: Double, b: Double): Double = if (a > b) a else b
        fun max(a: Float, b: Float): Float = if (a > b) a else b
        fun max(a: Int, b: Int): Int = if (a > b) a else b
    }
}
