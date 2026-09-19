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
 * 100 ms of media time -> v0.8 pass schedule ([SpeechCorrelator.PASS_BINS]:
 * ~18 s of listening for clean pairs, ~4.8 min of accumulated audio for
 * tough ones) hands a snapshot of the bin window to [SyncAnalysisWorker],
 * which runs the expensive [SpeechCorrelator.findOffset] on a dedicated
 * low-priority thread and publishes the lock decision through [SyncListener].
 *
 * Audio-thread budget: this processor runs inside Media3's audio sink
 * thread, so [queueInput] does ONLY cheap, allocation-free work: a
 * single pass over the samples updating running window sums, a passthrough
 * copy into a reused output buffer, and (on the v0.8 pass schedule only) a
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
    private var binCount = 0

    @Volatile private var locked = false

    // v0.8 pass budget: the scheduler fires SpeechCorrelator.PASS_BINS.size
    // passes as the window grows (see accumulateBin), and feature
    // extraction stops at the last one (gaveUp) or a lock. The CADENCE —
    // not per-outcome failure counting — is the cost bound: undecidable
    // and refused passes both just wait for the next scheduled one, which
    // removes the v0.7.4-era sparse-dialogue starvation structurally.
    // flush()/reset()/position re-anchor and a fresh non-empty setCues
    // re-arm the schedule.
    @Volatile private var passesUsed = 0
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
            // the position reset anyway). Gated on [enabled] so the user
            // toggle's OFF state keeps the processor dormant even after a
            // track switch.
            if (enabled) {
                passesUsed = 0
                gaveUp = false
            } else {
                // Stay dormant — but DO clear a stale lock so a future flip
                // back to ON begins from a clean slate, not a previously
                // locked state.
                locked = false
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
        this.enabled = enabled
        if (!enabled) {
            // Forced give-up so we stop spending CPU on a user-disabled
            // feature; a later re-arm via setCues(true) clears gaveUp.
            gaveUp = true
            // P1-6: "turn sync off" must un-apply the lock too. A stale
            // `locked` short-circuits analyze() forever, so a later OFF→ON
            // flip stayed dead until the next seek even though the budget
            // re-armed (the gaveUp branch below was unreachable-by-effect).
            locked = false
        } else {
            // Re-arm on the OFF→ON flip: keeps already-accumulated bins and
            // resumes evaluation at the next eval slot.
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
        configured = true
        if (active) {
            // ~10 ms of audio per window at the real sample rate.
            windowFillTarget = max(1, sampleRate / 100) * channelCount
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

    private fun resetWindow(anchorPosMs: Long = -1L) {
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
        windowN = 0; wSumSq = 0.0; wSumAbs = 0.0; wSumSig = 0.0; wZcr = 0
        floor = 0.0; peak = 0.0; lastSpeech = 0.0
        stableHits = 0; lastOffset = Double.NaN
        passesUsed = 0; gaveUp = false
        // P1-4: a fresh window must also drop the drift history. Without
        // this the six points a post-seek / episode-switch lock fits can
        // straddle the boundary; the resulting fake span clears
        // MIN_DRIFT_SPAN_SEC, the fit is clamped to ±2.5 %, and a ~9 s/min
        // subtitle-speed error gets persisted as if it were measured.
        driftTracker.reset()
        generation++ // invalidate any in-flight evaluation
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
     * background worker. The audio render thread only pays one
     * System.arraycopy (into the worker's preallocated snapshot buffer,
     * under the single-flight gate); if an evaluation is still in flight
     * the pass is dropped (v0.8: passes are scheduled by window growth,
     * and a correlation now costs milliseconds — a drop means the worker
     * was somehow slower than the audio thread, and the NEXT threshold
     * re-arms it anyway).
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
        // Gate #2 (v0.6.2 sub-sync UX pass): the user disabled live re-lock
        // mid-evaluation — drop the result instead of publishing a lock the
        // user no longer wants.
        if (!enabled) return
        val result: SpeechCorrelator.Result = when (
            val outcome = SpeechCorrelator.findOffset(
                req.bins, req.binCount, req.cues,
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
                // The agreement chain DOES break here, matching the oracle
                // (`if kind != "MATCH": stable_hits = 0` in livesim's
                // replay_v2): a NotReady pass between two agreeing MATCHes
                // means the middle of that window had too little speech to
                // judge, so the two matches are not CONSECUTIVE evidence.
                // Leaving the chain intact would let sparse content lock on
                // two agreements that never had a decidable pass between
                // them — untested behavior the sim numbers don't cover.
                stableHits = 0
                lastOffset = Double.NaN
                AppLog.d("SYNC", "pass t=${req.posMs / 1000}s bc=${req.binCount}: not ready (thin speech mass)")
                return
            }
            is SpeechCorrelator.Outcome.NoMatch -> {
                // Judged and refused: breaks the agreement chain — a lock
                // needs two CONSECUTIVE agreeing MATCH passes (13/30
                // false locks proved that in simulation without it).
                stableHits = 0
                lastOffset = Double.NaN
                AppLog.d("SYNC", "pass t=${req.posMs / 1000}s bc=${req.binCount}: judged, gates refused")
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

        if (stableHits >= 2) {
            locked = true
            listener.onSyncLocked(baseOffset.toFloat(), speedF)
        }
        // First agreeing pass: wait for the NEXT scheduled pass to
        // confirm — no failure is counted, the cadence bounds the cost.
    }

    companion object {
        /**
         * Bin window span in 100 ms bins: ±40 s of offset room x2 plus
         * 15 minutes of audio history. Indexed RELATIVE to [baseIdx] (the
         * window slides / re-anchors), so this is a memory bound, never a
         * media-position bound.
         */
        private const val BIN_WINDOW = 40 * 10 * 2 + 15 * 60 * 10
    }
}
