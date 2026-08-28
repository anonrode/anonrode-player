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

/**
 * Live subtitle-sync audio analyzer, injected into Media3's audio sink.
 *
 * Pipeline: 10 ms windows -> multi-feature speech score (energy / syllable
 * variance / ZCR against an adaptive floor-peak VAD) -> one soft bin per
 * 100 ms of media time -> every ~1.4 s of audio a snapshot of the bin
 * window is handed to [SyncAnalysisWorker], which runs the expensive
 * [SpeechCorrelator.findOffset] on a dedicated low-priority thread and
 * publishes the lock decision through [SyncListener].
 *
 * Audio-thread budget: this processor runs inside Media3's audio sink
 * thread, so [queueInput] does ONLY cheap, allocation-free work: a
 * single pass over the samples updating running window sums, a passthrough
 * copy into a reused output buffer, and (once per ~1.4 s of audio) a
 * System.arraycopy snapshot under the worker's single-flight gate. All
 * correlation and lock decisions happen on the worker thread — running
 * findOffset here caused underruns on budget devices.
 */
@UnstableApi
class AudioSyncProcessor(
    private val listener: SyncListener,
) : AudioProcessor {

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var inputIsFloat = false
    @Volatile private var active = false
    private var inputEnded = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /**
     * Reusable passthrough output buffer, grown on demand (same pattern as
     * VolumeBoostProcessor): Media3 fully drains the previous output before
     * queueing the next input, so one reused buffer is safe and keeps
     * [queueInput] allocation-free.
     */
    private var reuseBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    @Volatile private var cues: List<SubtitleCue> = emptyList()

    // Sliding window over absolute media time: audioBins[i] covers
    // (baseIdx + i) * 100ms. The window slides forward once playback runs
    // past its end, so live sync keeps working deep into (or after a
    // resume far into) an episode instead of dying at a fixed cap.
    private val audioBins = FloatArray(BIN_WINDOW)
    private var baseIdx = 0
    private var binCount = 0

    @Volatile private var locked = false

    // Evaluation attempt budget: each findOffset that returns without a
    // lock counts one failure; after MAX_EVAL_ATTEMPTS we stop evaluating
    // AND stop feature extraction (gaveUp) so un-lockable content costs
    // nothing after the budget is spent. flush() / reset() / a position
    // re-anchor (setStartPosition — fired on every seek, discontinuity and
    // episode switch) and a fresh non-empty setCues re-arm the budget.
    @Volatile private var failedEvals = 0
    @Volatile private var gaveUp = false

    // Bumped on every window reset (flush/reset/position re-anchor) so an
    // evaluation already in flight against stale bins is discarded instead
    // of locking or counting against the new window.
    @Volatile private var generation = 0

    private var totalFrames: Long = 0
    private var startPositionMs: Long = 0

    // One ~10 ms analysis window, accumulated as running sums so the hot
    // path is a single pass with zero allocations. windowFillTarget is the
    // number of interleaved samples per window (sampleRate/100 frames x
    // channelCount); at 48 kHz stereo that is 960 samples = exactly 10 ms
    // (the old fixed 320-sample buffer truncated the window to 3.3 ms).
    private var windowFillTarget = 0
    private var windowN = 0
    private var wSumSq = 0.0   // Σ x²
    private var wSumAbs = 0.0  // Σ |x|
    private var wSumSig = 0.0  // Σ x
    private var wZcr = 0
    private var wPrevSign = false

    private var floor = 0.0
    private var peak = 0.0
    private var lastSpeech = 0.0

    private val driftTracker = DriftTracker()
    private var lastEvalPos = Long.MIN_VALUE
    // Written by the eval worker, reset by the audio thread, read back by
    // the worker on the next evaluation — needs cross-thread visibility.
    @Volatile private var stableHits = 0
    @Volatile private var lastOffset = Double.NaN

    private val worker = SyncAnalysisWorker(BIN_WINDOW, this::evaluate)

    fun setCues(cues: List<SubtitleCue>) {
        this.cues = cues
        if (cues.isNotEmpty()) {
            // A fresh subtitle track is a fresh matching problem: re-arm the
            // attempt budget so a track attached after a previous give-up
            // still gets its chance (a seek/episode switch would re-arm via
            // the position reset anyway).
            failedEvals = 0
            gaveUp = false
        }
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
        resetWindow()
        locked = false
    }

    override fun configure(fmt: AudioFormat): AudioFormat {
        sampleRate = fmt.sampleRate; channelCount = fmt.channelCount
        inputIsFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        // Accept 16-bit AND float PCM: float-passthrough devices used to be
        // silently inactive (no sync at all). Float input is converted
        // sample-by-sample to Q15 on ingest, so the feature math below is
        // the same computation as native 16-bit input. The output format is
        // the untouched input format either way (pure passthrough).
        active = (fmt.encoding == C.ENCODING_PCM_16BIT || inputIsFloat) &&
            sampleRate > 0 && channelCount > 0
        if (active) {
            // ~10 ms of audio per window at the real sample rate.
            windowFillTarget = max(1, sampleRate / 100) * channelCount
            resetAll()
        }
        return fmt
    }

    override fun isActive() = active

    override fun queueInput(input: ByteBuffer) {
        val remaining = input.remaining()
        if (remaining == 0) return

        // Analyze on a duplicate: the view carries its own position cursor,
        // so the passthrough copy below still sees the full buffer.
        if (active) analyze(input.duplicate().order(ByteOrder.nativeOrder()))

        // Passthrough: copy the input into the reused output buffer. The
        // copy consumes the input (position -> limit) AND produces a fresh
        // buffer at position=0 — the Media3 AudioProcessor contract. (Handing
        // back the consumed input buffer would deliver 0 bytes downstream:
        // silence. See VolumeBoostProcessor for the same fix.)
        val out = if (reuseBuffer.capacity() < remaining) {
            ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
                .also { reuseBuffer = it }
        } else {
            reuseBuffer
        }
        out.clear()
        out.put(input) // advances input.position to limit
        out.flip()
        outputBuffer = out
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val b = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return b
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        inputEnded = false
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        resetWindow()
    }

    override fun reset() {
        flush()
        cues = emptyList()
        active = false
        // The worker thread is deliberately kept alive: the processor is
        // reused across player rebuilds (reset() then configure() again).
    }

    private fun resetAll() {
        resetWindow()
        locked = false
    }

    private fun resetWindow() {
        java.util.Arrays.fill(audioBins, 0f)
        baseIdx = 0; binCount = 0; totalFrames = 0
        windowN = 0; wSumSq = 0.0; wSumAbs = 0.0; wSumSig = 0.0; wZcr = 0
        floor = 0.0; peak = 0.0; lastSpeech = 0.0
        lastEvalPos = Long.MIN_VALUE; stableHits = 0; lastOffset = Double.NaN
        failedEvals = 0; gaveUp = false
        generation++ // invalidate any in-flight evaluation
    }

    /**
     * Feature extraction — audio render thread, allocation-free. Reads the
     * samples of one input buffer (a duplicate of the input; the original's
     * position is untouched) and folds them into the running 10 ms window
     * sums. Completing a window emits one speech score into the bin window
     * and, at most once per ~1.4 s of audio, schedules a background eval.
     */
    private fun analyze(pcm: ByteBuffer) {
        checkAndApplyReset()
        // Locked: the offset is live; gaveUp: the attempt budget is spent.
        // Either way there is nothing left to compute — skip the feature
        // pass so un-lockable content costs zero CPU. A position reset
        // (seek / episode switch) or a fresh setCues clears the flags.
        if (locked || gaveUp) {
            // Keep the sample clock honest even while analysis is off: a
            // re-arm (non-empty setCues after a give-up) must resume
            // binning at the TRUE media position, not where give-up froze
            // the counter. One division per buffer, nothing else.
            val frameBytes = (if (inputIsFloat) 4 else 2) * channelCount
            totalFrames += pcm.remaining() / frameBytes
            return
        }

        val nCh = channelCount
        val frameBytes = (if (inputIsFloat) 4 else 2) * nCh
        if (inputIsFloat) {
            // Float input is converted to Q15 (clamped) so the features are
            // the same computation as native 16-bit input.
            while (pcm.remaining() >= frameBytes) {
                for (ch in 0 until nCh) {
                    ingestSample((pcm.float * 32768f).coerceIn(-32768f, 32767f).toInt())
                }
                totalFrames++
            }
        } else {
            while (pcm.remaining() >= frameBytes) {
                for (ch in 0 until nCh) ingestSample(pcm.short.toInt())
                totalFrames++
            }
        }
    }

    /** Folds one Q15 sample into the running window sums (no allocation). */
    private fun ingestSample(s: Int) {
        val positive = s >= 0
        if (windowN == 0) {
            wSumSq = 0.0; wSumAbs = 0.0; wSumSig = 0.0; wZcr = 0
            wPrevSign = positive
        } else if (positive != wPrevSign) {
            wZcr++
            wPrevSign = positive
        }
        wSumSq += s.toDouble() * s
        wSumAbs += if (positive) s.toDouble() else -s.toDouble()
        wSumSig += s
        windowN++
        if (windowN >= windowFillTarget) finishWindow()
    }

    /** Computes the multi-feature speech score for one completed window. */
    private fun finishWindow() {
        val n = windowN
        val rms = sqrt(wSumSq / n)
        val meanAmp = (wSumAbs / n).toFloat()
        // Var(x - meanAmp) = E[x²] - 2·meanAmp·E[x] + meanAmp² — identical
        // to a second pass of (x - meanAmp)² but folded into the one pass
        // above (values stay far inside double precision at Q15 scale).
        val variance =
            (wSumSq / n - 2.0 * meanAmp * (wSumSig / n) + meanAmp.toDouble() * meanAmp).toFloat()
        val normVar = variance / max(meanAmp * meanAmp, 1f)
        val zcrNorm = wZcr.toFloat() / n

        val uf = floor * 1.08
        val up = max(uf + 0.0012, peak)
        val energyScore = ((rms - uf) / max(up - uf, 0.001)).coerceIn(0.0, 1.0).toFloat()
        val varianceScore = min(normVar / 2f, 1f)
        val zcrScore = when {
            zcrNorm in 0.02f..0.15f -> 1f
            zcrNorm < 0.02f -> 0.5f
            else -> max(0f, 1f - (zcrNorm - 0.15f) / 0.2f)
        }

        val sp = (energyScore * 0.5f + varianceScore * 0.3f + zcrScore * 0.2f).coerceIn(0f, 1f)
        lastSpeech = sp.toDouble() * 0.72 + lastSpeech * 0.28

        // adapt floor/peak
        if (floor == 0.0) {
            floor = rms; peak = rms * 1.9 + 0.0001
        } else {
            floor = floor * 0.986 + min(rms, floor * 1.45) * 0.014
            peak = max(floor + 0.00035, max(peak * 0.992, rms))
        }

        windowN = 0
        accumulateBin(sp)
    }

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

        if (lastEvalPos == Long.MIN_VALUE) {
            lastEvalPos = posMs
        } else if (posMs - lastEvalPos >= EVAL_INTERVAL_MS) {
            lastEvalPos = posMs
            val minBins = (SpeechCorrelator.MIN_AUDIO_SECONDS / SpeechCorrelator.ALIGN_BIN).toInt()
            if (binCount >= minBins && cues.isNotEmpty() && !gaveUp) {
                scheduleEvaluate(posMs)
            }
        }
    }

    /**
     * Hands a snapshot of the current bin window to the single-flight
     * background worker. The audio render thread only pays one
     * System.arraycopy (into the worker's preallocated snapshot buffer,
     * under the single-flight gate); if an evaluation is already in flight
     * this slot is dropped and the next one (~1.4 s of audio later)
     * retries.
     */
    private fun scheduleEvaluate(posMs: Long) {
        worker.submit(
            binCount = binCount,
            baseSeconds = baseIdx * SpeechCorrelator.ALIGN_BIN,
            cues = cues,
            posMs = posMs,
            generation = generation,
        ) { snapshot ->
            System.arraycopy(audioBins, 0, snapshot, 0, audioBins.size)
        }
    }

    /** Runs on the sync-eval worker thread — never on the audio render thread. */
    private fun evaluate(req: SyncAnalysisWorker.Request) {
        if (req.generation != generation || locked) return
        val result = SpeechCorrelator.findOffset(
            req.bins, req.binCount, req.cues,
            baseSeconds = req.baseSeconds,
        ) ?: run {
            countFailedEval(req.posMs, req.generation)
            return
        }

        // Discard stale results: a flush()/reset()/position re-anchor may
        // have landed on the audio thread while findOffset was running.
        if (req.generation != generation || locked) return

        stableHits = if (!lastOffset.isNaN() &&
            abs(result.offsetSeconds - lastOffset) <= 0.25) stableHits + 1 else 1
        lastOffset = result.offsetSeconds

        // drift detection from segment offsets
        driftTracker.add(req.posMs / 1000.0, result.offsetSeconds)
        val (baseOffset, speedF) = driftTracker.getCorrection(req.posMs / 1000.0)

        AppLog.d("SYNC", "eval t=${req.posMs / 1000}s off=${result.offsetSeconds}s speed=$speedF hits=$stableHits")

        if (stableHits >= 2) {
            locked = true
            listener.onSyncLocked(baseOffset.toFloat(), speedF)
        } else {
            countFailedEval(req.posMs, req.generation)
        }
    }

    /**
     * Counts an evaluation that returned without locking; once the budget
     * is exhausted, [gaveUp] stops further evaluations AND feature
     * extraction until the next flush()/reset()/position reset (or a fresh
     * non-empty setCues). Evaluations invalidated by a window reset (stale
     * generation) are not counted against the new window. Runs on the
     * worker thread; [SyncListener.onSyncNoMatch] therefore fires there too.
     */
    private fun countFailedEval(posMs: Long, gen: Int) {
        if (gen != generation) return
        failedEvals++
        if (failedEvals >= MAX_EVAL_ATTEMPTS) {
            gaveUp = true
            AppLog.d("SYNC", "no lock after $failedEvals attempts, giving up at t=${posMs / 1000}s")
            listener.onSyncNoMatch()
        }
    }

    companion object {
        /**
         * Bin window span in 100 ms bins: ±40 s of offset room x2 plus
         * 15 minutes of audio history. Indexed RELATIVE to [baseIdx] (the
         * window slides / re-anchors), so this is a memory bound, never a
         * media-position bound.
         */
        private const val BIN_WINDOW = 40 * 10 * 2 + 15 * 60 * 10

        /** One evaluation slot per ~1.4 s of analyzed audio. */
        private const val EVAL_INTERVAL_MS = 1400L

        /**
         * Consecutive no-lock evaluations before we stop trying. A lock
         * needs two agreeing evaluations, so 10 slots is generous for
         * lockable content while capping un-lockable content at ~14 s of
         * evaluation slots (plus the 16 s of audio needed to arm the first
         * one). Re-armed by flush()/reset()/position reset/fresh cues.
         */
        private const val MAX_EVAL_ATTEMPTS = 10
    }
}
