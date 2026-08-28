package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated single-thread worker that runs the expensive subtitle/audio
 * correlation ([SpeechCorrelator.findOffset], ~801 shifts x binCount) off
 * Media3's audio render thread. The render thread only accumulates cheap
 * speech features into bins and hands a snapshot to this worker; running
 * the correlation inside the audio sink caused underruns/skips on budget
 * devices.
 *
 * Threading contract:
 *  - [submit] is called from the audio render thread. It is SINGLE-FLIGHT:
 *    at most one evaluation is in flight at any time. If the worker is
 *    busy the snapshot is DROPPED and the next slot (~1.4 s of audio
 *    later) retries — correlation must never back up the audio pipeline.
 *  - The [snapshot] buffer is preallocated here and filled by the caller
 *    through [submit]'s `fill` lambda while holding the single-flight
 *    gate, so the render thread performs zero allocations on the hot path
 *    and the worker can never observe a half-written snapshot.
 *  - [Handler.evaluate] runs on the worker thread; results are published
 *    from there through the processor's existing [SyncListener] callbacks.
 *
 * The worker thread is a daemon at [Thread.MIN_PRIORITY] and lives as long
 * as the processor that owns it. It is deliberately never shut down: the
 * processor instance survives player rebuilds (Media3 calls reset() and
 * then configure() again on the next sink), so a terminal shutdown() here
 * would kill live sync after the first decoder swap.
 */
class SyncAnalysisWorker(
    windowBins: Int,
    private val handler: Handler,
) {

    /** One evaluation request. [bins] is the worker-owned [snapshot]. */
    class Request(
        val bins: FloatArray,
        val binCount: Int,
        val baseSeconds: Double,
        val cues: List<SubtitleCue>,
        val posMs: Long,
        val generation: Int,
    )

    fun interface Handler {
        /** Invoked on the worker thread, once per accepted snapshot. */
        fun evaluate(request: Request)
    }

    /** Linearized copy of the processor's bin window; see class KDoc. */
    private val snapshot = FloatArray(windowBins)

    private val inFlight = AtomicBoolean(false)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sync-eval").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * Acquires the single-flight gate, linearizes the current bin window
     * into [snapshot] via [fill] (runs on the caller's thread, under the
     * gate) and schedules exactly one evaluation.
     *
     * @return true if the snapshot was accepted, false if an evaluation
     *   was already in flight and this slot was dropped.
     */
    fun submit(
        binCount: Int,
        baseSeconds: Double,
        cues: List<SubtitleCue>,
        posMs: Long,
        generation: Int,
        fill: (snapshot: FloatArray) -> Unit,
    ): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false // busy: drop
        var submitted = false
        try {
            fill(snapshot)
            executor.execute {
                try {
                    handler.evaluate(
                        Request(snapshot, binCount, baseSeconds, cues, posMs, generation)
                    )
                } catch (t: Throwable) {
                    AppLog.e("SYNC", "background eval failed", t)
                } finally {
                    inFlight.set(false)
                }
            }
            submitted = true
        } finally {
            if (!submitted) inFlight.set(false)
        }
        return true
    }
}
