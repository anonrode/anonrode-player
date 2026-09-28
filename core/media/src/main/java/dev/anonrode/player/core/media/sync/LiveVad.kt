package dev.anonrode.player.core.media.sync

import android.content.Context
import dev.anonrode.player.core.media.log.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Live-path neural voice-activity detection, driven off the render thread.
 *
 * ## Why
 *
 * The live sync path derived "is there speech" from window RMS, which cannot
 * separate speech from loud music — so it is structurally unable to sync
 * content with a continuous mix. The whole-file engine has always had a real
 * answer ([SileroVad], used by [OnsetExtractor]); this makes it usable during
 * playback so the fast path stops being the weak one.
 *
 * ## Threading
 *
 * `SileroVad.processPcm` runs ONNX inference. Doing that on the audio render
 * thread risks an underrun, which the user hears as a stutter — strictly worse
 * than not syncing. So the render thread's entire involvement is [submit],
 * which copies PCM into a preallocated buffer and returns: no maths, no
 * allocation, no inference. A dedicated low-priority worker drains that
 * buffer, resamples, runs the model, and publishes a 100 ms probability
 * envelope for the correlator to read.
 *
 * ## Alignment
 *
 * The published envelope is indexed RELATIVE to the audio the worker has seen
 * since its last [reanchor], exactly like `SpeechCorrelator`'s `baseSeconds`.
 * [reanchor] is called from the processor's position-reset path so the two
 * grids move together; without it an envelope describing the old position
 * would be compared against cues on a different timeline.
 *
 * ## Failure
 *
 * [create] returns null when the model is unavailable, and the caller falls
 * back to the energy envelope. Silero is an upgrade, never a dependency.
 */
internal class LiveVad private constructor(
    private val vad: SileroVad,
) : AutoCloseable {

    private val lock = Object()
    private val pending = ByteArray(PENDING_BYTES)
    private var pendingLen = 0
    private var pendingDropped = 0L

    private val closed = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>(null)

    @Volatile private var envelope = FloatArray(0)
    @Volatile private var envelopeBins = 0
    @Volatile private var formatRate = 0
    @Volatile private var formatChannels = 0
    @Volatile private var formatIsFloat = false
    @Volatile private var failed = false
    private val vadLock = Any()
    private var consecutiveFailures = 0

    init {
        val t = Thread({ pump() }, "sync-vad")
        // Below NORMAL so inference never competes with the audio thread.
        t.priority = Thread.MIN_PRIORITY + 1
        t.isDaemon = true
        worker.set(t)
        t.start()
    }

    /** Bins produced since the last [reanchor]; 0 before the first window. */
    val binCount: Int get() = envelopeBins

    /** True until the model has enough audio to judge from. */
    val isWarming: Boolean get() = envelopeBins < MIN_WARM_BINS

    /** Set once the processor knows the live PCM format. */
    fun setFormat(sampleRate: Int, channels: Int, isFloat: Boolean) {
        formatRate = sampleRate
        formatChannels = channels
        formatIsFloat = isFloat
    }

    /**
     * Render-thread entry point. Copies [buf] and returns immediately — the
     * actual work happens on the worker. Must not block, allocate per sample,
     * or throw.
     */
    fun submit(buf: ByteBuffer, sampleRate: Int, channels: Int, isFloat: Boolean) {
        if (closed.get() || failed) return
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return
        val frames = buf.remaining() / frameBytes
        if (frames <= 0) return
        val need = frames * frameBytes
        synchronized(lock) {
            var skip = 0
            if (need > pending.size) {
                // A single buffer larger than the entire ring. Keeping it whole
                // is impossible, and the old code computed a negative `keep`,
                // zeroed pendingLen, and then copied `need` bytes into a buffer
                // of `pending.size` -- an IndexOutOfBoundsException thrown ON
                // THE RENDER THREAD, which takes playback down rather than
                // merely skipping a sync attempt. Take the newest
                // frame-aligned tail instead and account for the rest.
                val keepIn = (pending.size / frameBytes) * frameBytes
                if (keepIn <= 0) {
                    pendingDropped += need.toLong()
                    return
                }
                pendingDropped += (need - keepIn).toLong()
                skip = need - keepIn
                pendingLen = 0
            } else if (pendingLen + need > pending.size) {
                // Overflow drops the OLDEST audio, not the newest. The model
                // wants contiguous speech: a gap in the middle poisons its
                // recurrent state, whereas a truncated head only costs a little
                // history. Trimming is frame-aligned so the copy stays
                // de-interleavable.
                val keep = ((pending.size - need) / frameBytes) * frameBytes
                if (keep <= 0) {
                    pendingDropped += pendingLen.toLong()
                    pendingLen = 0
                } else {
                    System.arraycopy(pending, pendingLen - keep, pending, 0, keep)
                    pendingLen = keep
                }
            }
            val toCopy = need - skip
            val dup = buf.duplicate().order(buf.order())
            (dup as java.nio.Buffer).position(buf.position() + skip)
            dup.get(pending, pendingLen, toCopy)
            pendingLen += toCopy
            lock.notifyAll()
        }
    }

    private fun pump() {
        val scratch = ByteArray(PENDING_BYTES)
        while (!closed.get()) {
            val n = synchronized(lock) {
                while (pendingLen == 0 && !closed.get()) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (closed.get()) 0 else pendingLen.also { pendingLen = 0 }
            }
            if (n <= 0) continue
            try {
                synchronized(lock) { System.arraycopy(pending, 0, scratch, 0, n) }
                val wrapped = ByteBuffer.wrap(scratch, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                synchronized(vadLock) {
                    vad.processPcm(wrapped, formatRate, formatChannels, formatIsFloat)
                    publish()
                }
                consecutiveFailures = 0
            } catch (t: Throwable) {
                consecutiveFailures++
                if (consecutiveFailures >= 5 && !failed) {
                    failed = true
                    AppLog.e(TAG, "inference failed repeatedly — falling back to energy envelope", t)
                } else if (!failed) {
                    AppLog.w(TAG, "transient inference glitch (count=$consecutiveFailures): ${t.message}")
                }
            }
        }
    }

    private fun publish() {
        val raw = vad.getSpeechEnvelope()
        if (raw.isEmpty()) return
        // Keep the TAIL: the newest bins are what the correlator needs.
        val take = if (raw.size <= ENVELOPE_BINS) raw.size else ENVELOPE_BINS
        val from = raw.size - take
        val out = FloatArray(take)
        System.arraycopy(raw, from, out, 0, take)
        envelope = out
        envelopeBins = take
    }

    /** The published 100 ms speech probabilities, or null before first output. */
    fun snapshot(): FloatArray? = if (envelopeBins > 0) envelope else null

    /**
     * Re-anchors to a new media position and discards accumulated audio.
     *
     * Must be called from the same path that resets the processor's own bin
     * grid (the position-reset handler), so both envelopes describe the same
     * stretch of the timeline. Also called on a cue-track change, because a
     * fresh track is a fresh matching problem.
     */
    fun reanchor() {
        synchronized(lock) {
            pendingLen = 0
            pendingDropped = 0L
        }
        synchronized(vadLock) {
            try {
                vad.reset()
            } catch (t: Throwable) {
                AppLog.e(TAG, "reset failed", t)
            }
            envelope = FloatArray(0)
            envelopeBins = 0
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { lock.notifyAll() }
        worker.get()?.interrupt()
        synchronized(vadLock) {
            try {
                vad.close()
            } catch (t: Throwable) {
                AppLog.e(TAG, "close failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "LIVEVAD"

        /**
         * Pending PCM capacity in bytes — about 2 s of 48 kHz stereo 16-bit.
         * Big enough to absorb a scheduling hiccup, small enough that losing
         * the tail on overflow is brief and self-healing.
         */
        private const val PENDING_BYTES = 192 * 1024

        /** Published envelope length in 100 ms bins (400 s of history, covering the 3300 PASS_BINS max). */
        private const val ENVELOPE_BINS = 4000

        /** Below this the model has not seen enough audio to be worth trusting. */
        private const val MIN_WARM_BINS = 80   // 8 s

        /** Returns null when the model is unavailable; caller uses energy instead. */
        fun create(context: Context): LiveVad? = try {
            if (!SileroVad.modelAvailable(context)) {
                AppLog.d(TAG, "model asset missing — staying on the energy envelope")
                null
            } else {
                val v = SileroVad(context)
                if (v.isReady) LiveVad(v) else {
                    v.close()
                    null
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "create failed", t)
            null
        }
    }
}
