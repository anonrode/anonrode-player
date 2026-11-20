package dev.anonrode.player.core.media.sync

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

interface SyncListener {
    fun onSyncLocked(offsetSeconds: Float, speedFactor: Float)
    fun onSyncTrackingUpdate(
        positionSec: Double,
        offsetSeconds: Float,
        speedFactor: Float,
        isPiecewiseJump: Boolean,
    ) {}
    fun onSyncNoMatch()
}

/**
 * Live subtitle-sync audio analyzer, injected into Media3's audio sink.
 *
 * Uses neural voice activity detection ([LiveVad]) on a low-priority background
 * worker thread when available, with a min-statistics noise-floor +
 * long-horizon energy envelope as a reliable zero-dependency fallback
 * (evidence + validation: tools/_sync_fallback_study.md; the plain
 * floor/peak AGC this replaced pinned music into mid-range scores and
 * could not lock real dramas or films). Hands snapshots of the speech envelope
 * to [SyncAnalysisWorker] on the [SpeechCorrelator.PASS_BINS] schedule.
 */
@UnstableApi
class AudioSyncProcessor(
    private val listener: SyncListener,
    context: Context? = null,
) : AudioProcessor {

    private val liveVad: LiveVad? = context?.let { LiveVad.create(it) }

    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var inputIsFloat = false
    @Volatile private var active = false
    /** True once the sink has configured us at least this session —
     *  distinguishes "analyzer declined this format" (inactive after a
     *  configure) from "no format yet" (before first configure). */
    @Volatile private var configured = false
    /**
     * Live-re-lock gate (v0.6.2 sub-sync UX pass). Default true (legacy
     * behaviour). The host mirrors
     * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
     * into this flag via [setEnabled]. When false: [setCues] refuses to
     * re-arm the attempt budget, [evaluate] refuses to publish a lock,
     * and [accumulateBin] refuses to schedule evaluations. The audio
     * render-thread hot path stays allocation-free — the flag is a
     * single volatile read at decision points, never per sample.
     */
    @Volatile private var enabled = true
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
    // v0.8.7: internal (not private) so [SyncScheduleRegressionTest] can assert
    // the pass schedule's exact position. The 09-19 device log's collapsed
    // budget was invisible to every black-box observation — it needed these.
    internal var binCount = 0

    @Volatile internal var locked = false
    val isLocked: Boolean get() = locked

    @Volatile internal var trackingMode = false
    @Volatile private var activeOffsetSeconds = Double.NaN
    @Volatile private var lastTrackingEvalWallMs = 0L
    @Volatile private var lastTrackingEvalPosMs = 0L
    @Volatile private var trackingCandidateOffset = Double.NaN
    @Volatile private var trackingCandidateHits = 0
    @Volatile private var trackingDesyncStreak = 0
    @Volatile private var trackingDesyncStartSec = Double.NaN

    // Verified baseline lock persisted across configure() and seeks so continuous
    // tracking stays armed on every seek (including 0ms in-sync baselines).
    @Volatile private var hasBaselineLock = false
    @Volatile private var baselineOffsetSec = 0.0
    @Volatile private var baselineSpeed = 1.0f
    @Volatile private var baselinePiecewise: List<Pair<Double, Double>> = emptyList()

    // Worker-thread reusable tail buffer for continuous tracking (avoids per-eval allocation).
    private val trackingTailBuf = FloatArray(TRACKING_TAIL_BINS)



    // v0.8 pass budget: the scheduler fires SpeechCorrelator.PASS_BINS.size
    // passes as the window grows (see accumulateBin), and feature
    // extraction stops at the last one (gaveUp) or a lock. The CADENCE —
    // not per-outcome failure counting — is the cost bound: undecidable
    // and refused passes both just wait for the next scheduled one, which
    // removes the v0.7.4-era sparse-dialogue starvation structurally.
    // flush()/reset()/position re-anchor and a fresh non-empty setCues
    // re-arm the schedule.
    @Volatile internal var passesUsed = 0
    @Volatile internal var gaveUp = false

    /**
     * v0.8.7: total passes ever scheduled. Monotonic — never reset.
     *
     * [passesUsed] ALONE CANNOT EXPOSE A REDUNDANT RE-ARM: zeroing it makes it
     * re-climb through the same thresholds it already crossed, so it converges
     * back to the exact value it had. That is precisely why the 09-19
     * collapse was invisible in the state and only visible in the log's
     * timestamps. The RATE is the invariant that matters — a redundant
     * setEnabled(true) must schedule zero new passes — and this counter is
     * what [SyncScheduleRegressionTest] asserts on.
     */
    @Volatile internal var passesScheduled = 0

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

    private var noiseFloor = 0.0
    private var hiEnergy = 0.0

    // 300Hz-3000Hz vocal formant bandpass filter (Biquad IIR)
    private var bpB0 = 1f
    private var bpB2 = -1f
    private var bpA1 = 0f
    private var bpA2 = 0f
    private var bpX1 = FloatArray(2)
    private var bpX2 = FloatArray(2)
    private var bpY1 = FloatArray(2)
    private var bpY2 = FloatArray(2)

    private val driftTracker = DriftTracker()
    // Written by the eval worker, reset by the audio thread, read back by
    // the worker on the next evaluation — needs cross-thread visibility.
    @Volatile private var stableHits = 0
    @Volatile private var lastOffset = Double.NaN

    private val worker = SyncAnalysisWorker(BIN_WINDOW, this::evaluate)

    fun setCues(cues: List<SubtitleCue>) {
        // v0.8.7 P0: only a GENUINE track change is a fresh matching problem.
        // The host re-pushes the SAME list on every settings emission (see
        // PlaybackEngine.setSubSyncEnabled), and the unconditional re-arm
        // below was the second half of the collapsed-budget bug documented on
        // [setEnabled]: each redundant push zeroed [passesUsed] while a large
        // [binCount] was already banked. SubtitleCue is a data class, so this
        // is a structural list comparison (~2 µs for 800-odd cues).
        val prevNonEmpty = this.cues.isNotEmpty()
        val changed = this.cues != cues
        this.cues = cues
        if (cues.isNotEmpty() && changed) {
            if (prevNonEmpty) {
                // Switching from one loaded track to a genuinely different track invalidates
                // the previous track's baseline lock. Deferring the FIRST cue attachment
                // (empty -> cues, ~200ms after play()) preserves the persisted baseline lock.
                clearBaselineLock()
                val curSec = if (sampleRate > 0) (startPositionMs + totalFrames * 1000L / sampleRate) / 1000.0 else startPositionMs / 1000.0
                liveVad?.reanchor(curSec)
            }
            if (enabled) {
                if (!hasBaselineLock) {
                    passesUsed = 0
                    gaveUp = false
                    locked = false
                    trackingMode = false
                }
            } else {
                // Stay dormant — but DO clear a stale lock so a future flip
                // back to ON begins from a clean slate, not a previously
                // locked state.
                clearBaselineLock()
                locked = false
                trackingMode = false
            }
            // P2-1 mirror of configure()'s handoff: cues usually attach
            // ~200 ms AFTER the sink configured (deferred sidecar parse),
            // so the inactive branch there saw empty cues; hand off now
            // instead of binning nothing forever behind a "Syncing…" chip.
            if (configured && !active && enabled && !gaveUp) {
                gaveUp = true
                AppLog.d("SYNC", "analyzer inactive for this format — cue attach hands off")
                listener.onSyncNoMatch()
            }
        }
        AppLog.d("SYNC", "setCues: ${cues.size} cues enabled=$enabled")
    }

    /**
     * Live-re-lock gate (v0.6.2 sub-sync UX pass). The host mirrors
     * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
     * into this flag on every settings change. When [enabled] is false:
     *   - [setCues] does not re-arm the attempt budget (processor stays dormant)
     *   - [accumulateBin] skips the worker submission
     *   - [evaluate] refuses to publish a lock
     * When flipped back to true mid-playback, the next seek (or fresh
     * setCues) re-arms the budget naturally.
     */
    fun setEnabled(enabled: Boolean) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        if (!enabled) {
            // Forced give-up so we stop spending CPU on a user-disabled
            // feature; a later re-arm via setCues(true) clears gaveUp.
            gaveUp = true
            clearBaselineLock()
            // P1-6: "turn sync off" must un-apply the lock too. A stale
            // `locked` short-circuits analyze() forever, so a later OFF→ON
            // flip stayed dead until the next seek even though the budget
            // re-armed (the gaveUp branch below was unreachable-by-effect).
            locked = false
            trackingMode = false
        } else if (!wasEnabled) {
            // Re-arm on a GENUINE OFF→ON flip only: keeps already-accumulated
            // bins and resumes evaluation at the next eval slot.
            //
            // v0.8.7 P0: this used to run on EVERY setEnabled(true) call. The
            // host mirrors the whole settings DataStore into this processor
            // (PlayerActivity's settings collector), so any unrelated settings
            // write — every subtitle size/colour/position tap — landed here.
            // [passesUsed] was zeroed while [binCount] was NOT, so the very
            // next 10 ms window already satisfied PASS_BINS[0], the one after
            // satisfied PASS_BINS[1], and so on: the entire 24-pass listening
            // budget collapsed into ~240 ms of audio, all of it judging the
            // same tiny window. Every collapsed pass was refused (NoMatch) and
            // reset stableHits, so the two CONSECUTIVE agreeing passes a lock
            // requires became unreachable and the engine handed off having
            // "listened" to a quarter of a second. The 09-19 device log is the
            // exact signature: `bc=205` three times in 25 ms, then a wall of
            // `judged, gates refused`. It also dropped a confirmed lock via
            // `locked = false` on every style tap.
            passesUsed = 0
            gaveUp = false
            locked = false // belt & braces: never inherit a stale lock
        }
        AppLog.d("SYNC", "setEnabled=$enabled")
    }

    // Written from the main thread, consumed by the audio thread.
    @Volatile private var pendingResetPosition: Long? = null
    @Volatile private var pendingQuietPosition: Long? = null

    fun setStartPosition(positionMs: Long) {
        pendingResetPosition = positionMs
    }

    /**
     * Explicitly re-arm the attempt budget on user demand ("Resync now").
     * Clears gaveUp and resets passesUsed, stableHits, driftTracker, and VAD,
     * allowing the engine to start a fresh calibration pass immediately.
     */
    fun rearm(positionMs: Long = -1L) {
        clearBaselineLock()
        passesUsed = 0
        gaveUp = false
        locked = false
        trackingMode = false
        activeOffsetSeconds = Double.NaN
        lastTrackingEvalWallMs = 0L
        lastTrackingEvalPosMs = 0L
        trackingCandidateOffset = Double.NaN
        trackingCandidateHits = 0
        trackingDesyncStreak = 0
        trackingDesyncStartSec = Double.NaN
        stableHits = 0
        lastOffset = Double.NaN
        generation++
        driftTracker.reset()
        liveVad?.reanchor(if (positionMs >= 0L) positionMs / 1000.0 else 0.0)
        if (positionMs >= 0L) {
            pendingResetPosition = positionMs
        }
        AppLog.d("SYNC", "rearmed live sync on demand")
    }

    /**
     * Seeds the processor with a verified baseline lock (e.g. from background fingerprinting
     * or stored database state) and immediately arms continuous tracking mode.
     * Survives [configure] and post-seek [resetWindow] until cleared by [rearm],
     * [clearBaselineLock], a cue-track change, or turning auto-sync off.
     */
    fun setBaselineLock(offsetSeconds: Float, speedFactor: Float, piecewise: String = "") {
        val parsedPw = parsePiecewise(piecewise)
        hasBaselineLock = true
        baselineOffsetSec = offsetSeconds.toDouble()
        baselineSpeed = speedFactor
        baselinePiecewise = parsedPw
        val curPosSec = if (sampleRate > 0) {
            (startPositionMs + totalFrames * 1000L / sampleRate) / 1000.0
        } else {
            startPositionMs / 1000.0
        }
        val activeAtPos = resolveBaselineOffsetAt(curPosSec)
        locked = true
        trackingMode = true
        activeOffsetSeconds = activeAtPos
        trackingCandidateOffset = Double.NaN
        trackingCandidateHits = 0
        trackingDesyncStreak = 0
        trackingDesyncStartSec = Double.NaN
        lastTrackingEvalWallMs = System.currentTimeMillis()
        lastTrackingEvalPosMs = (curPosSec * 1000.0).toLong().coerceAtLeast(0L)
        driftTracker.reset()
        driftTracker.add(curPosSec, activeAtPos)
        AppLog.d("SYNC", "initialized live baseline lock to ${activeAtPos}s (pw=${parsedPw.size} segs, tracking armed)")
    }

    fun clearBaselineLock() {
        hasBaselineLock = false
        baselineOffsetSec = 0.0
        baselineSpeed = 1.0f
        baselinePiecewise = emptyList()
    }

    private fun resolveBaselineOffsetAt(posSec: Double): Double {
        val segs = baselinePiecewise
        if (segs.isNotEmpty()) {
            return segs.lastOrNull { it.first <= posSec }?.second
                ?: segs.first().second
        }
        return baselineOffsetSec
    }

    private fun parsePiecewise(storage: String): List<Pair<Double, Double>> {
        if (storage.isBlank()) return emptyList()
        return storage.split(';').mapNotNull { seg ->
            val parts = seg.split(':')
            if (parts.size != 2) return@mapNotNull null
            val s = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val b = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            s to b
        }.sortedBy { it.first }
    }

    /**
     * Re-anchor the media-time clock WITHOUT discarding the window
     * (v0.8, A-B repeat fix). The old blanket re-anchor on every seek
     * reset the window each loop iteration — bins, pass schedule and all —
     * so live sync could never accumulate enough data during A-B.
     *
     * Mechanics: the anchor is recomputed so the clock agrees with
     * [positionMs] at the current frame count. The audio recorded BEFORE
     * the loop-back now carries labels shifted earlier by one loop length,
     * while the re-played pass fills fresh slots at the TRUE positions.
     * Consequence (by design, verified safe): each further loop-back
     * shifts the older passes' labels by another loop length, so the
     * correlation sees near-equal peaks at the true shift and at every
     * k·loopLength — the prominence gate therefore REFUSES to lock while
     * the loop is sustained (never a lock at a loop-shifted peak — that
     * is the whole point of keeping the duplicate visible to the margin).
     * Releasing A-B seeks forward, which takes the full re-anchor path,
     * and the fresh window locks as fast as a cold start (~18 s on clean
     * content). A session already `locked` keeps applying its stored
     * offset across loop-backs (analyze() short-circuits on locked).
     */
    fun setStartPositionQuiet(positionMs: Long) {
        pendingQuietPosition = positionMs
    }

    /** Applies a pending position reset on the audio thread. */
    private fun checkAndApplyReset() {
        val quiet = pendingQuietPosition
        if (quiet != null) {
            pendingQuietPosition = null
            // Offset the anchor so posMs(quiet) == now: labels keep
            // tracking media time, bins/gates/schedule untouched.
            startPositionMs = quiet - totalFrames * 1000L / max(sampleRate, 1)
        }
        val reset = pendingResetPosition ?: return
        pendingResetPosition = null
        startPositionMs = reset
        resetWindow(anchorPosMs = reset)
    }

    override fun configure(fmt: AudioFormat): AudioFormat {
        if (fmt == AudioFormat.NOT_SET) {
            configured = false
            active = false
            return AudioFormat.NOT_SET
        }
        sampleRate = fmt.sampleRate; channelCount = fmt.channelCount
        inputIsFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        // Accept 16-bit AND float PCM: float-passthrough devices used to be
        // silently inactive (no sync at all). Float input is converted
        // sample-by-sample to Q15 on ingest, so the feature math below is
        // the same computation as native 16-bit input. The output format is
        // the untouched input format either way (pure passthrough).
        active = (fmt.encoding == C.ENCODING_PCM_16BIT || inputIsFloat) &&
            sampleRate > 0 && channelCount > 0
        configured = true
        if (active) {
            // ~10 ms of audio per window at the real sample rate.
            windowFillTarget = max(1, sampleRate / 100) * channelCount
            liveVad?.setFormat(sampleRate, channelCount, inputIsFloat)
            val f0 = sqrt(300.0 * 3000.0)
            val q = f0 / (3000.0 - 300.0)
            val w0 = 2.0 * Math.PI * f0 / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha
            bpB0 = (alpha / a0).toFloat()
            bpB2 = (-alpha / a0).toFloat()
            bpA1 = ((-2.0 * cosW0) / a0).toFloat()
            bpA2 = ((1.0 - alpha) / a0).toFloat()
            val ch = channelCount.coerceAtLeast(1)
            if (bpX1.size < ch) {
                bpX1 = FloatArray(ch)
                bpX2 = FloatArray(ch)
                bpY1 = FloatArray(ch)
                bpY2 = FloatArray(ch)
            }
            resetAll()
        } else {
            // P2-1 (v0.8): encoded passthrough / offload output routes (AC-3,
            // E-AC3, DTS on devices with encoded AudioTrack support) deliver
            // no PCM to AudioProcessors — Media3's DefaultAudioSink says so
            // in its own comment. Until now that was SILENT: the analyzer
            // just never ran, no evals, no give-up, and the "Syncing…"
            // chip spun forever on formats where only the whole-file
            // fingerprint engine can work. Hand off loudly instead.
            AppLog.d(
                "SYNC",
                "analyzer INACTIVE encoding=" + fmt.encoding +
                    " sr=" + fmt.sampleRate + " ch=" + fmt.channelCount +
                    " — no PCM path, handing off to fingerprint",
            )
            if (cues.isNotEmpty() && enabled && !gaveUp) {
                gaveUp = true
                listener.onSyncNoMatch()
            }
        }
        return fmt
    }

    override fun isActive() = active

    override fun queueInput(input: ByteBuffer) {
        val remaining = input.remaining()
        if (remaining == 0) return

        // Analyze in-place with position restore: eliminates per-buffer allocation
        if (active) {
            val origPos = input.position()
            analyze(input)
            (input as java.nio.Buffer).position(origPos)
        }

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
        val anchor = pendingResetPosition ?: -1L
        resetWindow(anchorPosMs = anchor)
    }

    override fun reset() {
        clearBaselineLock()
        flush()
        cues = emptyList()
        active = false
        configured = false
        liveVad?.reanchor(0.0)
        // The worker thread is deliberately kept alive: the processor is
        // reused across player rebuilds (reset() then configure() again).
    }

    private fun resetAll() {
        resetWindow(anchorPosMs = startPositionMs)
    }

    private fun resetWindow(anchorPosMs: Long = -1L) {
        val effectiveAnchorMs = if (anchorPosMs >= 0L) anchorPosMs else 0L
        liveVad?.reanchor(effectiveAnchorMs / 1000.0)
        java.util.Arrays.fill(audioBins, 0f)
        // v0.8.2: anchor the bin grid AT the new position. With baseIdx=0
        // the first bin after a seek to media position P lands at
        // rel = P/100ms directly — inside the 9800-bin array that means
        // binCount jumps to P/bins (09-15 device log: a seek to 665 s
        // produced "bc=6636" and burned ALL 24 passes on 6600 ZEROED bins
        // in 40 ms; every resume or scrub under ~16 min did the same).
        // With the anchor, the fresh window is exactly the bins played
        // after the seek. The accumulateBin safety net (binCount==0 →
        // baseIdx=idx) stays for position-unknown starts.
        baseIdx = if (anchorPosMs >= 0L) (anchorPosMs / 100L).toInt() else 0
        binCount = 0; totalFrames = 0
        windowN = 0; wSumSq = 0.0
        noiseFloor = 0.0; hiEnergy = 0.0
        java.util.Arrays.fill(bpX1, 0f)
        java.util.Arrays.fill(bpX2, 0f)
        java.util.Arrays.fill(bpY1, 0f)
        java.util.Arrays.fill(bpY2, 0f)
        stableHits = 0; lastOffset = Double.NaN
        passesUsed = 0; gaveUp = false
        trackingCandidateOffset = Double.NaN
        trackingCandidateHits = 0
        trackingDesyncStreak = 0
        trackingDesyncStartSec = Double.NaN
        // P1-4: a fresh window must also drop the drift history. Without
        // this the six points a post-seek / episode-switch lock fits can
        // straddle the boundary; the resulting fake span clears
        // MIN_DRIFT_SPAN_SEC, the fit is clamped to ±2.5 %, and a ~9 s/min
        // subtitle-speed error gets persisted as if it were measured.
        driftTracker.reset()
        if (hasBaselineLock && enabled) {
            val anchorSec = effectiveAnchorMs / 1000.0
            val activeAtPos = resolveBaselineOffsetAt(anchorSec)
            locked = true
            trackingMode = true
            activeOffsetSeconds = activeAtPos
            lastTrackingEvalWallMs = System.currentTimeMillis()
            lastTrackingEvalPosMs = effectiveAnchorMs
            driftTracker.add(anchorSec, activeAtPos)
        } else {
            locked = false
            trackingMode = false
            activeOffsetSeconds = Double.NaN
            lastTrackingEvalWallMs = 0L
            lastTrackingEvalPosMs = 0L
        }
        generation++ // invalidate any in-flight evaluation
    }

    private fun filterSample(s: Float, ch: Int): Float {
        val y = bpB0 * (s - bpX2[ch]) - bpA1 * bpY1[ch] - bpA2 * bpY2[ch]
        bpX2[ch] = bpX1[ch]
        bpX1[ch] = s
        bpY2[ch] = bpY1[ch]
        bpY1[ch] = y
        return y
    }

    /**
     * Feature extraction — audio render thread, allocation-free. Reads the
     * samples of one input buffer (a duplicate of the input; the original's
     * position is untouched) and folds them into the running 10 ms window
     * sums. Completing a window emits one speech score into the bin window
     * and, when the bin window crosses the next PASS_BINS threshold,
     * schedules a background eval (the v0.8 pass schedule — not a 1 Hz loop).
     */
    private fun analyze(pcm: ByteBuffer) {
        checkAndApplyReset()
        // Locked without tracking: offset is live; gaveUp without tracking: budget spent.
        // When trackingMode is active, continue low-overhead feature extraction so
        // continuous playback adapts to clock drift or commercial cuts automatically.
        if ((locked && !trackingMode) || (gaveUp && !trackingMode)) {
            val frameBytes = (if (inputIsFloat) 4 else 2) * channelCount
            totalFrames += pcm.remaining() / frameBytes
            return
        }

        val nCh = channelCount
        val frameBytes = (if (inputIsFloat) 4 else 2) * nCh
        liveVad?.submit(pcm, sampleRate, nCh, inputIsFloat)
        if (inputIsFloat) {
            // Float input is converted to Q15 (clamped) so the features are
            // the same computation as native 16-bit input.
            while (pcm.remaining() >= frameBytes) {
                for (ch in 0 until nCh) {
                    val raw = (pcm.float * 32768f).coerceIn(-32768f, 32767f)
                    ingestSample(filterSample(raw, ch))
                }
                totalFrames++
            }
        } else {
            while (pcm.remaining() >= frameBytes) {
                for (ch in 0 until nCh) {
                    val raw = pcm.short.toFloat()
                    ingestSample(filterSample(raw, ch))
                }
                totalFrames++
            }
        }
    }

    /** Folds one filtered sample into the running window sums (no allocation). */
    private fun ingestSample(s: Float) {
        if (windowN == 0) wSumSq = 0.0
        wSumSq += s.toDouble() * s
        windowN++
        if (windowN >= windowFillTarget) finishWindow()
    }

    /**
     * Computes the speech score for one completed window (energy fallback).
     *
     * v0.9.11 fallback envelope: a min-statistics noise floor plus a
     * long-horizon loudness scale. The old floor/peak AGC let music and
     * effects ride into mid-range scores (65% of bins read as "speech" on
     * real dramas), collapsing the Pearson contrast below the lock gate.
     * Here the floor falls instantly to any quieter window and creeps up
     * onto true noise; the scale follows the loudest sustained content with
     * ~50 s of memory; and the score measures how far a window sits above
     * [NOISE_GUARD]× the floor — silence and music stay low, dialogue pins.
     */
    private fun finishWindow() {
        val n = windowN
        val rms = sqrt(wSumSq / n)

        if (noiseFloor <= 0.0) {
            noiseFloor = rms * 0.5
            hiEnergy = rms
        } else {
            noiseFloor = min(rms, noiseFloor * NOISE_DECAY)
            noiseFloor += (rms - noiseFloor) * NOISE_RISE
            hiEnergy = max(rms, hiEnergy * HI_DECAY)
        }
        val span = hiEnergy * SPAN_HI - noiseFloor * NOISE_GUARD
        val sp = if (span > 1e-6f) {
            ((rms - noiseFloor * NOISE_GUARD) / span).coerceIn(0.0, 1.0).toFloat()
        } else 0f

        windowN = 0
        accumulateBin(sp)
    }

    private fun accumulateBin(speech: Float) {
        val posMs = startPositionMs + totalFrames * 1000L / max(sampleRate, 1)
        val idx = (posMs / 100).toInt()
        if (idx < 0 || idx < baseIdx) return
        if (binCount == 0) {
            // Fresh window: anchor the grid at the first bin that actually
            // has data (v0.8.2). This used to happen only when the position
            // was beyond the array; a flush/resetAll mid-array (decoder
            // rebuild, in-app seek the sink restarted without an explicit
            // setStartPosition) left baseIdx=0 and binCount exploded to
            // position/100 zeroed bins — the 09-15 seek poisoning.
            baseIdx = idx
        }
        if (idx - baseIdx >= audioBins.size) {
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
        val rel = idx - baseIdx
        binCount = max(binCount, rel + 1)
        audioBins[rel] = max(audioBins[rel], speech)



        // If already locked and in tracking mode: schedule periodic drift & cut checks
        // at low overhead (25s normal cadence, or 10s when confirming a suspected desync/cut)
        if (trackingMode && cues.isNotEmpty() && enabled) {
            val now = System.currentTimeMillis()
            if (lastTrackingEvalWallMs == 0L) {
                lastTrackingEvalWallMs = now
                lastTrackingEvalPosMs = posMs
            }
            val fastConfirm = (!trackingCandidateOffset.isNaN() && trackingCandidateHits >= 1) ||
                trackingDesyncStreak >= 1
            val requiredIntervalMs = if (fastConfirm) {
                TRACKING_CONFIRM_INTERVAL_MS
            } else {
                TRACKING_CADENCE_INTERVAL_MS
            }
            val wallElapsed = now - lastTrackingEvalWallMs
            val posElapsed = posMs - lastTrackingEvalPosMs
            if (wallElapsed >= requiredIntervalMs || posElapsed >= requiredIntervalMs) {
                if (scheduleEvaluate(posMs)) {
                    lastTrackingEvalWallMs = now
                    lastTrackingEvalPosMs = posMs
                }
            }
            return
        }

        // v0.8 pass scheduler: thresholds on the GROWING window, not a
        // 1 Hz retry loop. The first threshold (80 bins) is the
        // correlator's not-ready floor, so clean content gets its first
        // ~10 s of listening exactly as before — then the schedule
        // deliberately slows (5 s, 30 s gaps) because each additional
        // bin of evidence matters more than another correlation. The
        // audio render thread still pays only one volatile compare here
        // per 100 ms bin.
        if (passesUsed < SpeechCorrelator.PASS_BINS.size &&
            binCount >= SpeechCorrelator.PASS_BINS[passesUsed] &&
            cues.isNotEmpty() && !gaveUp && enabled
        ) {
            passesUsed++
            passesScheduled++
            scheduleEvaluate(posMs)
            if (passesUsed == SpeechCorrelator.PASS_BINS.size) {
                // Last pass scheduled: the listening budget is spent —
                // stop binning and hand off to the whole-file engine.
                // If that final pass still locks, onSyncLocked lands
                // normally (evaluate() is gated on `locked`, not
                // `gaveUp`) and a post-handoff live lock is superseded
                // by the fingerprint's verdict by design (trust rule).
                gaveUp = true
                AppLog.d("SYNC", "pass budget spent ($passesUsed), handing off to fingerprint")
                listener.onSyncNoMatch()
            }
        }
    }

    /**
     * Hands a snapshot of the current bin window to the single-flight
     * background worker. Uses the neural LiveVad envelope when available
     * and warm, with the energy envelope as zero-dependency fallback.
     */
    private fun scheduleEvaluate(posMs: Long): Boolean {
        val vad = liveVad
        val vadSnap = if (vad != null && !vad.isWarming) vad.snapshot() else null
        return if (vadSnap != null && vadSnap.bins.size >= SpeechCorrelator.ELIGIBLE_BINS) {
            val count = minOf(vadSnap.bins.size, BIN_WINDOW)
            val fromIdx = vadSnap.bins.size - count
            val snapBaseSec = vadSnap.baseSeconds + fromIdx * SpeechCorrelator.ALIGN_BIN
            worker.submit(
                binCount = count,
                baseSeconds = snapBaseSec,
                cues = cues,
                posMs = posMs,
                generation = generation,
            ) { snapshot ->
                System.arraycopy(vadSnap.bins, fromIdx, snapshot, 0, count)
            }
        } else {
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
    }

    /** Runs on the sync-eval worker thread — never on the audio render thread. */
    private fun evaluate(req: SyncAnalysisWorker.Request) {
        if (req.generation != generation) return
        // Gate #2 (v0.6.2 sub-sync UX pass): the user disabled live re-lock
        // mid-evaluation — drop the result instead of publishing a lock the
        // user no longer wants.
        if (!enabled) return

        if (locked && trackingMode) {
            evaluateTracking(req)
            return
        }
        if (locked) return
        val maxOffset = if (req.binCount < 450) 15.0 else SpeechCorrelator.MAX_OFFSET_SEC
        val result: SpeechCorrelator.Result = when (
            val outcome = SpeechCorrelator.findOffset(
                req.bins, req.binCount, req.cues,
                maxOffsetSec = maxOffset,
                baseSeconds = req.baseSeconds,
            )
        ) {
            // Undecidable slot: the correlator had too little data to judge
            // the pairing (sparse dialogue, long silence, short cue track).
            // NOT evidence against the subtitle track — v0.7.4 took it out
            // of the failure budget (livesim S5: sparse content charged all
            // 22 attempts on undecidable seconds and handed off at ~30 s,
            // while the same track was gate-passable from ~60 s). In v0.8
            // the PASS_BINS cadence is the only cost bound at all.
            is SpeechCorrelator.Outcome.NotReady -> {
                // v0.8: the cadence bounds cost, so an undecidable window
                // is logged (this is the sync-log evidence line) and simply
                // waits for the next scheduled pass.
                //
                // A NotReady pass between two agreeing MATCHes means a short
                // dialogue pause or music interlude occurred; preserving the
                // agreement chain allows real dialogue to lock across natural
                // speech pauses instead of endlessly wiping tentative hits.
                AppLog.d("SYNC", "pass t=${req.posMs / 1000}s bc=${req.binCount}: not ready (thin speech mass)")
                return
            }
            is SpeechCorrelator.Outcome.NoMatch -> {
                // Judged and refused: decay agreement hits rather than instantly
                // dropping tentative lock progress on a momentary speech pause.
                if (stableHits > 0) {
                    stableHits--
                } else {
                    lastOffset = Double.NaN
                }
                AppLog.d("SYNC", "pass t=${req.posMs / 1000}s bc=${req.binCount}: judged, gates refused (hits=$stableHits)")
                return
            }
            is SpeechCorrelator.Outcome.Match -> outcome.result
        }

        // Discard stale results: a flush()/reset()/position re-anchor may
        // have landed on the audio thread while findOffset was running.
        if (req.generation != generation || locked) return

        stableHits = if (!lastOffset.isNaN() &&
            abs(result.offsetSeconds - lastOffset) <= 0.25) stableHits + 1 else 1
        lastOffset = result.offsetSeconds

        // drift detection from segment offsets — the tracker owns the policy
        // (offset-only until a drift rate is actually measurable across
        // enough media time), so a freshly-locked live pass can never
        // persist a subtitle speed fitted from two one-second slots.
        driftTracker.add(req.posMs / 1000.0, result.offsetSeconds)
        val (baseOffset, speedF) = driftTracker.getCorrection()

        AppLog.d("SYNC", "eval t=${req.posMs / 1000}s off=${result.offsetSeconds}s speed=$speedF hits=$stableHits")

        val requiredHits = if (req.binCount < 600 || result.containment < 0.85) 3 else 2
        if (stableHits >= requiredHits) {
            locked = true
            trackingMode = true
            hasBaselineLock = true
            baselineOffsetSec = baseOffset
            baselineSpeed = speedF
            activeOffsetSeconds = baseOffset
            trackingCandidateOffset = Double.NaN
            trackingCandidateHits = 0
            trackingDesyncStreak = 0
            trackingDesyncStartSec = Double.NaN
            lastTrackingEvalWallMs = System.currentTimeMillis()
            lastTrackingEvalPosMs = req.posMs
            listener.onSyncLocked(baseOffset.toFloat(), speedF)
        }
        // First agreeing pass: wait for the NEXT scheduled pass to
        // confirm — no failure is counted, the cadence bounds the cost.
    }

    /**
     * Continuous background tracking: runs periodically during playback on the
     * recent sliding tail (90 s) in two stages:
     *   - Stage A (Local Leash ±6.0 s so EXCLUSION_BINS=3.5 s enforces a genuine
     *     runner-up prominence floor): accepts ONLY smooth drift (|delta| <= 0.80 s).
     *   - Stage B (Wide Cut-Recovery ±90 s): escalates after >= 90 s playback and
     *     >= 50 s tail when >= 2 consecutive checks confirm Pearson r at active
     *     offset has collapsed (< 0.10) on speech, requiring 2 consecutive agreeing
     *     full-gate wide passes (margin >= 0.10) before committing a piecewise cut.
     */
    private fun evaluateTracking(req: SyncAnalysisWorker.Request) {
        if (req.binCount < SpeechCorrelator.ELIGIBLE_BINS) return

        // Barrage / wall-of-sound check on recent audio (last 20s / 200 bins):
        // If energy is a continuous wall of sound without natural speech pauses,
        // freeze tracking to avoid false slew or false cuts from theme songs or battles.
        val checkBins = minOf(200, req.binCount)
        var sumEnergy = 0.0
        var minEnergy = 1.0f
        val startBin = req.binCount - checkBins
        for (i in startBin until req.binCount) {
            val v = req.bins[i]
            sumEnergy += v
            if (v < minEnergy) minEnergy = v
        }
        val meanEnergy = sumEnergy / checkBins
        if (meanEnergy >= 0.40 && minEnergy >= 0.15) {
            AppLog.d("SYNC", "tracking pass t=${req.posMs / 1000}s skipped: continuous barrage / music wall (mean=$meanEnergy min=$minEnergy)")
            return
        }

        val active = activeOffsetSeconds
        if (active.isNaN()) {
            AppLog.d("SYNC", "tracking pass t=${req.posMs / 1000}s skipped: activeOffsetSeconds is NaN")
            return
        }

        // Slice the recent 90s sliding tail so minutes of pre-cut history cannot drown
        // out fresh post-cut dialogue when a mid-episode commercial/recap jump happens.
        val tailLen = minOf(req.binCount, TRACKING_TAIL_BINS)
        val tailStart = req.binCount - tailLen
        val tailBaseSec = req.baseSeconds + tailStart * SpeechCorrelator.ALIGN_BIN
        System.arraycopy(req.bins, tailStart, trackingTailBuf, 0, tailLen)

        // Stage A: Local Leash (±6.0s around activeOffsetSeconds so EXCLUSION_BINS=3.5s
        // has a genuine runner-up background floor). Smooth drift only (|delta| <= 0.80s).
        val localOutcome = SpeechCorrelator.findOffset(
            audio = trackingTailBuf,
            binCount = tailLen,
            cues = req.cues,
            maxOffsetSec = LOCAL_LEASH_SEARCH_SEC,
            baseSeconds = tailBaseSec - active,
        )

        if (req.generation != generation) return

        when (localOutcome) {
            is SpeechCorrelator.Outcome.NotReady -> {
                AppLog.d("SYNC", "tracking pass t=${req.posMs / 1000}s: not ready (sparse speech)")
                return
            }
            is SpeechCorrelator.Outcome.Match -> {
                val rawResult = localOutcome.result
                val measuredOffset = active + rawResult.offsetSeconds
                val delta = abs(measuredOffset - active)
                if (delta <= 0.80) {
                    trackingDesyncStreak = 0
                    trackingDesyncStartSec = Double.NaN
                    trackingCandidateOffset = Double.NaN
                    trackingCandidateHits = 0
                    AppLog.d(
                        "SYNC",
                        "tracking eval t=${req.posMs / 1000}s: measured off=${measuredOffset}s active=${active}s delta=${delta}s cont=${rawResult.containment} margin=${rawResult.margin}",
                    )
                    if (rawResult.containment >= 0.65 && rawResult.margin >= 0.10) {
                        driftTracker.add(req.posMs / 1000.0, measuredOffset)
                        val (baseOffset, speedF) = driftTracker.getCorrection()
                        activeOffsetSeconds = baseOffset
                        listener.onSyncTrackingUpdate(
                            positionSec = req.posMs / 1000.0,
                            offsetSeconds = baseOffset.toFloat(),
                            speedFactor = speedF,
                            isPiecewiseJump = false,
                        )
                    }
                    return
                }
                // Peak inside ±6s is >0.80s away: do NOT commit from narrow leash; fall through
                // to the desync check + Stage B ±90s wide search so any cut beats the full ±90s landscape.
            }
            is SpeechCorrelator.Outcome.NoMatch -> {
                // Local leash refused: verify whether active alignment has genuinely broken.
            }
        }

        // Cold-open / short-tail guard: never escalate to wide cut recovery during opening 90s
        // or before at least 50s (500 bins) of recent audio is available.
        if (req.posMs < TRACKING_MIN_CUT_POS_MS || tailLen < TRACKING_MIN_WIDE_TAIL_BINS) {
            return
        }

        // Verify direct alignment at `active` on the recent tail.
        val probe = SpeechCorrelator.measureAlignmentAt(
            audio = trackingTailBuf,
            binCount = tailLen,
            cues = req.cues,
            offsetSeconds = active,
            baseSeconds = tailBaseSec,
        )
        val tailEndSec = tailBaseSec + tailLen * SpeechCorrelator.ALIGN_BIN
        val corridorCues = req.cues.count {
            it.start in (tailBaseSec - active - TRACKING_CUT_SEARCH_RADIUS_SEC)..(tailEndSec - active + TRACKING_CUT_SEARCH_RADIUS_SEC)
        }
        if (probe.speechBins < TRACKING_MIN_DESYNC_SPEECH_BINS || corridorCues < 5) {
            AppLog.d("SYNC", "tracking pass t=${req.posMs / 1000}s: gates refused (thin speech/cues: sp=${probe.speechBins} cues=$corridorCues)")
            return
        }

        // Pearson r collapses to ~0 on misaligned speech even when dense dialogue has 40-55% random overlap.
        val alignmentBroken = probe.r < TRACKING_DESYNC_MAX_R
        if (!alignmentBroken) {
            trackingDesyncStreak = 0
            trackingDesyncStartSec = Double.NaN
            AppLog.d("SYNC", "tracking pass t=${req.posMs / 1000}s: active alignment still healthy (r=%.2f cont=%.2f)".format(probe.r, probe.containment))
            return
        }

        trackingDesyncStreak++
        if (trackingDesyncStartSec.isNaN()) {
            trackingDesyncStartSec = (req.posMs / 1000.0 - 20.0).coerceAtLeast(0.0)
        }
        AppLog.d(
            "SYNC",
            "tracking desync confirmed at t=${req.posMs / 1000}s (streak=$trackingDesyncStreak r=%.2f cont=%.2f sp=${probe.speechBins})".format(probe.r, probe.containment),
        )
        if (trackingDesyncStreak < 2) {
            return
        }

        // Stage B: Wide Cut-Recovery Search (±90s around activeOffsetSeconds) on the 90s tail.
        val wideOutcome = SpeechCorrelator.findOffset(
            audio = trackingTailBuf,
            binCount = tailLen,
            cues = req.cues,
            maxOffsetSec = TRACKING_CUT_SEARCH_RADIUS_SEC,
            baseSeconds = tailBaseSec - active,
        )
        if (req.generation != generation) return

        if (wideOutcome is SpeechCorrelator.Outcome.Match) {
            val wRes = wideOutcome.result
            val newOffset = active + wRes.offsetSeconds
            val shift = abs(newOffset - active)
            if (shift >= 1.0 && wRes.containment >= TRACKING_WIDE_MIN_CONTAINMENT && wRes.margin >= TRACKING_WIDE_MIN_MARGIN) {
                AppLog.d(
                    "SYNC",
                    "tracking wide-search candidate at t=${req.posMs / 1000}s: ${active}s -> ${newOffset}s (r=%.2f margin=%.2f cont=%.2f)".format(wRes.score, wRes.margin, wRes.containment),
                )
                handleCutCandidate(
                    reqPosSec = req.posMs / 1000.0,
                    active = active,
                    candidateOffset = newOffset,
                    containment = wRes.containment,
                    minContainment = TRACKING_WIDE_MIN_CONTAINMENT,
                )
            } else {
                AppLog.d(
                    "SYNC",
                    "tracking wide-search candidate refused extra cut bar at t=${req.posMs / 1000}s: shift=%.2fs cont=%.2f margin=%.2f".format(shift, wRes.containment, wRes.margin),
                )
            }
        } else {
            AppLog.d("SYNC", "tracking wide-search t=${req.posMs / 1000}s: no cut lock yet")
        }
    }

    private fun handleCutCandidate(
        reqPosSec: Double,
        active: Double,
        candidateOffset: Double,
        containment: Double,
        minContainment: Double,
    ) {
        val cand = trackingCandidateOffset
        if (!cand.isNaN() && abs(candidateOffset - cand) <= 0.35 && containment >= minContainment) {
            trackingCandidateHits++
            if (trackingCandidateHits >= 2) {
                val tailSpanSec = TRACKING_TAIL_BINS * SpeechCorrelator.ALIGN_BIN
                val rawCutSec = if (!trackingDesyncStartSec.isNaN()) trackingDesyncStartSec else reqPosSec
                val cutPosSec = rawCutSec.coerceIn((reqPosSec - tailSpanSec).coerceAtLeast(0.0), reqPosSec)
                AppLog.d("SYNC", "tracking CONFIRMED PIECEWISE CUT at t=${cutPosSec}s (eval t=${reqPosSec}s): ${active}s -> ${candidateOffset}s")
                activeOffsetSeconds = candidateOffset
                trackingCandidateOffset = Double.NaN
                trackingCandidateHits = 0
                trackingDesyncStreak = 0
                trackingDesyncStartSec = Double.NaN
                // Record confirmed segment in our internal baseline piecewise map so future seeks know it.
                val updatedPw = baselinePiecewise.toMutableList()
                if (updatedPw.isEmpty()) {
                    updatedPw.add(0.0 to baselineOffsetSec)
                }
                val nearIdx = updatedPw.indexOfLast { abs(it.first - cutPosSec) <= 45.0 }
                if (nearIdx >= 0) {
                    updatedPw[nearIdx] = cutPosSec to candidateOffset
                } else {
                    updatedPw.add(cutPosSec to candidateOffset)
                }
                baselinePiecewise = updatedPw.sortedBy { it.first }
                hasBaselineLock = true
                driftTracker.reset()
                driftTracker.add(reqPosSec, candidateOffset)
                listener.onSyncTrackingUpdate(
                    positionSec = cutPosSec,
                    offsetSeconds = candidateOffset.toFloat(),
                    speedFactor = 1.0f,
                    isPiecewiseJump = true,
                )
            }
        } else if (containment >= minContainment) {
            trackingCandidateOffset = candidateOffset
            trackingCandidateHits = 1
            AppLog.d("SYNC", "tracking tentative piecewise jump detected at t=${reqPosSec}s: off=${candidateOffset}s (awaiting confirmation pass)")
        }
    }

    /** Releases background resources held by the neural VAD worker. */
    fun release() {
        liveVad?.close()
    }

    /** Test-only synchronization barrier: waits until any in-flight evaluation finishes. */
    internal fun awaitWorkerIdleForTest(timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (worker.isBusy() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
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

        // v0.9.11 fallback-envelope constants (tools/_sync_fallback_study.md):
        // validated in the 1:1 Python replica — synthetic ground-truth suite
        // (0 / ±2.7 / ±9.3 / +64 s all recovered to the bin) + real films
        // where the old AGC could not lock (Kill To Love now locks at +0.00 s
        // with r=0.295, margin=0.146, containment=0.44, z=15.0).
        private const val NOISE_DECAY = 0.9995
        private const val NOISE_RISE = 0.005
        private const val HI_DECAY = 0.9998
        private const val NOISE_GUARD = 2.0
        private const val SPAN_HI = 0.7

        /** Local leash search radius: ±6.0s so EXCLUSION_BINS (3.5s) enforces a real prominence floor. */
        private const val LOCAL_LEASH_SEARCH_SEC = 6.0

        /** Sliding tail length for continuous tracking: 90 seconds (900 x 100ms bins). */
        private const val TRACKING_TAIL_BINS = 900

        /** Minimum tail bins (50s) and playback position (90s) before Stage B wide cut recovery may fire. */
        private const val TRACKING_MIN_WIDE_TAIL_BINS = 500
        private const val TRACKING_MIN_CUT_POS_MS = 90_000L

        /** Wide cut-recovery search radius once active alignment is confirmed broken: ±90s. */
        private const val TRACKING_CUT_SEARCH_RADIUS_SEC = 90.0

        /** Minimum speech bins (3.5s) in the tail and max Pearson r before declaring alignment broken. */
        private const val TRACKING_MIN_DESYNC_SPEECH_BINS = 35
        private const val TRACKING_DESYNC_MAX_R = 0.10

        /** Extra confirmation thresholds for Stage B wide cut jumps (on top of SpeechCorrelator gates). */
        private const val TRACKING_WIDE_MIN_CONTAINMENT = 0.40
        private const val TRACKING_WIDE_MIN_MARGIN = 0.10

        /** Low-overhead evaluation cadence during continuous tracking: 25 seconds. */
        private const val TRACKING_CADENCE_INTERVAL_MS = 25_000L

        /** Fast re-eval slot to confirm a suspected desync or piecewise cut: 10 seconds. */
        private const val TRACKING_CONFIRM_INTERVAL_MS = 10_000L
    }
}
