package dev.anonrode.player.core.media.sync

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.anonrode.player.core.media.log.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Silero VAD speech-START onsets — port of tools/_silero_track.py and the
 * debounce stage of tools/_fix1_vad_onsets.py (validated hybrid 10/10 on
 * Growling Tiger 2; rescues wall-to-wall-music content where silencedetect
 * yields ~0 onsets, and the Knockout C-drama case).
 *
 * Faithful to the validated Python pipeline:
 *  - model: assets/silero_vad.onnx (silero_vad_16k_op15.onnx, opset 15 so
 *    onnxruntime-android runs it without the opset-18 If-node problem)
 *  - 16 kHz mono, 512-sample chunks (32 ms per inference bin)
 *  - every chunk after the first is prepended with the trailing 64 samples
 *    of the previous INPUT (without this context the model output is a
 *    constant ~0.003 — the bug already found and fixed in _silero_track.py)
 *  - state [2,1,128] threaded across calls; speech prob > 0.5 → bin = 1
 *  - onsets from 0→1 transitions with debounce: >= 150 ms contiguous
 *    speech AFTER the edge, >= 100 ms non-speech BEFORE it, no merge
 *    (the CHOSEN DEFAULT from the fix-1 sweep)
 *
 * Input buffers arrive already positioned (offset applied, limit = end of
 * valid data) by the caller; remaining() is the valid byte count.
 */
class SileroVad(context: Context) : AutoCloseable {

    companion object {
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val SR = 16000L
        private const val WINDOW = 512
        private const val CONTEXT = 64
        private const val THRESHOLD = 0.5f
        private const val STATE_LEN = 2 * 1 * 128
        // Debounce defaults tuned by the fix-1 sweep (see _fix1_vad_onsets.py)
        private const val MIN_SPEECH_MS = 150
        private const val MIN_SILENCE_MS = 100
        private val FRAME_SEC = WINDOW.toDouble() / SR.toDouble() // 0.032
        private const val RING_CAPACITY = 4000 // 400 seconds of 100ms bins

        fun modelAvailable(context: Context): Boolean = try {
            context.assets.open(MODEL_ASSET).use { true }
        } catch (t: Throwable) {
            false
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession?
    private var srTensor: OnnxTensor? = null
    private val inputsMap = HashMap<String, OnnxTensor>(3)
    private val state = FloatArray(STATE_LEN)
    private val contextSamples = FloatArray(CONTEXT)
    private var hasContext = false
    private val chunk = FloatArray(WINDOW)
    private var chunkN = 0

    // Online debounce tracking (zero heap allocations)
    private var totalFrames = 0
    private var curRunVal: Byte = -1
    private var curRunStart = 0
    private var curRunLen = 0
    private var prevZeroLen: Int? = null
    private var regionStart = -1
    private var regionFirstLen = 0
    private val detectedOnsets = ArrayList<Double>()

    // Bounded circular 100ms envelope ring buffer (4000 bins = 400s)
    private val ringBuffer = FloatArray(RING_CAPACITY)
    private val envelopeSums = FloatArray(RING_CAPACITY)
    private val envelopeCounts = IntArray(RING_CAPACITY)
    private var totalBins = 0
    private var currentEnvIdx = -1

    /** v0.8.3: absolute media time the first bin belongs to (non-zero only
     *  on a resumed decode pass) — added to every onset time so the caller
     *  can merge with the cached prefix. */
    var onsetOffsetSec = 0.0

    // Reused direct buffers (ONNX requires direct FloatBuffers)
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect((WINDOW + CONTEXT) * 4).order(ByteOrder.nativeOrder())
    private val stateBuf: ByteBuffer =
        ByteBuffer.allocateDirect(STATE_LEN * 4).order(ByteOrder.nativeOrder())

    init {
        var s: OrtSession? = null
        try {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setMemoryPatternOptimization(true)
            }
            s = env.createSession(bytes, opts)
            val srT = OnnxTensor.createTensor(env, SR)
            srTensor = srT
            inputsMap["sr"] = srT
        } catch (t: Throwable) {
            AppLog.e("VAD", "failed to load silero model", t)
        }
        session = s
    }

    val isReady: Boolean get() = session != null

    /** Feed one decoded PCM buffer (any rate / channel count). */
    fun processPcm(buf: ByteBuffer, sampleRate: Int, channels: Int, isFloat: Boolean) {
        if (session == null || sampleRate <= 0 || channels <= 0) return
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val frames = buf.remaining() / (if (isFloat) 4 else 2) / channels
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            for (i in 0 until frames) {
                var sum = 0f
                for (ch in 0 until channels) sum += fb.get(i * channels + ch)
                resampler.push(sum / channels)
            }
        } else {
            val sb = buf.asShortBuffer()
            for (i in 0 until frames) {
                var sum = 0
                for (ch in 0 until channels) sum += sb.get(i * channels + ch)
                resampler.push(sum / channels.toFloat() / 32768f)
            }
        }
        resampler.drain(sampleRate)
    }

    private fun onSample16k(x: Float) {
        chunk[chunkN++] = x
        if (chunkN < WINDOW) return
        chunkN = 0
        runInference()
    }

    private fun runInference() {
        val sess = session ?: return
        val srT = srTensor ?: return
        // input = [context(64)? , chunk(512)] — first call has no context
        val width = if (hasContext) WINDOW + CONTEXT else WINDOW
        // (clear() returns the Buffer base type in the Android stubs, so
        // the asFloatBuffer() call must not chain off it.)
        inputBuf.clear()
        val fb = inputBuf.asFloatBuffer()
        if (hasContext) fb.put(contextSamples)
        fb.put(chunk)
        fb.flip() // position=0, limit=width — ONNX reads remaining()

        stateBuf.clear()
        val stBuf = stateBuf.asFloatBuffer()
        stBuf.put(state)
        stBuf.rewind() // position=0, limit=STATE_LEN

        OnnxTensor.createTensor(env, fb, longArrayOf(1L, width.toLong())).use { inputT ->
            OnnxTensor.createTensor(env, stBuf, longArrayOf(2L, 1L, 128L)).use { stateT ->
                inputsMap["input"] = inputT
                inputsMap["state"] = stateT
                inputsMap["sr"] = srT
                sess.run(inputsMap).use { result ->
                    // model output order (validated): [output, stateN]
                    // Direct buffer reading eliminates GC pauses and multi-dimensional array reflection
                    val outVal = result.get(0) as OnnxTensor
                    val prob = outVal.floatBuffer.get(0)

                    // 1. Online debounce for speech onsets (zero heap allocation)
                    val v: Byte = if (prob > THRESHOLD) 1.toByte() else 0.toByte()
                    val frameIdx = totalFrames++
                    if (curRunVal == (-1).toByte()) {
                        curRunVal = v
                        curRunStart = frameIdx
                        curRunLen = 1
                    } else if (v == curRunVal) {
                        curRunLen++
                    } else {
                        processRun(curRunVal, curRunStart, curRunLen)
                        curRunVal = v
                        curRunStart = frameIdx
                        curRunLen = 1
                    }

                    // 2. Incremental 100ms envelope ring buffer (capacity 4000 bins = 400s)
                    val envIdx = (frameIdx * FRAME_SEC / 0.1).toInt()
                    if (envIdx > currentEnvIdx) {
                        for (b in (currentEnvIdx + 1)..envIdx) {
                            val slot = b % RING_CAPACITY
                            envelopeSums[slot] = 0f
                            envelopeCounts[slot] = 0
                            ringBuffer[slot] = 0f
                        }
                        currentEnvIdx = envIdx
                        totalBins = envIdx + 1
                    }
                    val slot = envIdx % RING_CAPACITY
                    envelopeSums[slot] += prob
                    envelopeCounts[slot]++
                    ringBuffer[slot] = envelopeSums[slot] / envelopeCounts[slot]

                    val stVal = result.get(1) as OnnxTensor
                    val stFb = stVal.floatBuffer
                    stFb.position(0)
                    stFb.get(state, 0, STATE_LEN)
                }
            }
        }
        // thread the trailing 64 samples of THIS input forward
        System.arraycopy(chunk, WINDOW - CONTEXT, contextSamples, 0, CONTEXT)
        hasContext = true
    }

    private fun processRun(runVal: Byte, start: Int, len: Int) {
        val sp = maxOf(1, (MIN_SPEECH_MS / (1000.0 * FRAME_SEC)).toInt())
        val sil = maxOf(1, (MIN_SILENCE_MS / (1000.0 * FRAME_SEC)).toInt())
        if (runVal.toInt() == 1) {
            if (regionStart < 0) {
                regionStart = start
                regionFirstLen = len
            }
        } else {
            if (regionStart >= 0) {
                if (regionFirstLen >= sp && prevZeroLen != null && prevZeroLen!! >= sil) {
                    detectedOnsets.add(regionStart * FRAME_SEC + onsetOffsetSec)
                }
                regionStart = -1
            }
            prevZeroLen = len
        }
    }

    /** Speech-START onsets from the collected bins (fix-1 debounce). */
    fun finish(): List<Double> {
        if (curRunVal != (-1).toByte()) {
            processRun(curRunVal, curRunStart, curRunLen)
            curRunVal = (-1).toByte()
        }
        val sp = maxOf(1, (MIN_SPEECH_MS / (1000.0 * FRAME_SEC)).toInt())
        val sil = maxOf(1, (MIN_SILENCE_MS / (1000.0 * FRAME_SEC)).toInt())
        if (regionStart >= 0 && regionFirstLen >= sp && prevZeroLen != null && prevZeroLen!! >= sil) {
            detectedOnsets.add(regionStart * FRAME_SEC + onsetOffsetSec)
            regionStart = -1
        }
        AppLog.d("VAD", "$totalFrames bins -> ${detectedOnsets.size} speech-start onsets")
        return ArrayList(detectedOnsets)
    }

    /**
     * Resamples the continuous 32ms model probability bins into a 100ms
     * soft speech envelope (FloatArray) covering the entire analyzed audio (capped at 400s).
     */
    fun getSpeechEnvelope(targetBinSec: Double = 0.1): FloatArray {
        val total = totalBins
        if (total == 0) return FloatArray(0)
        val count = minOf(total, RING_CAPACITY)
        val out = FloatArray(count)
        if (total <= RING_CAPACITY) {
            System.arraycopy(ringBuffer, 0, out, 0, count)
        } else {
            val start = (total - RING_CAPACITY) % RING_CAPACITY
            val len1 = RING_CAPACITY - start
            System.arraycopy(ringBuffer, start, out, 0, len1)
            if (start > 0) {
                System.arraycopy(ringBuffer, 0, out, len1, start)
            }
        }
        return out
    }

    /** Absolute media time (s) the collected bins cover (call after [finish]). */
    fun coveredSec(): Double = onsetOffsetSec + totalFrames * FRAME_SEC

    /**
     * Clears every piece of accumulated state so the detector can be re-anchored
     * to a new media position, WITHOUT reloading the model.
     *
     * The live sync path needs this on every seek and every cue-track change:
     * its bin grid re-anchors at the new position, and an envelope still
     * describing the old position would be compared against cues on a
     * different timeline — which is exactly the "seek poisoning" class of bug.
     * Reloading the ONNX session per seek would be far too expensive, so the
     * state is cleared in place instead.
     *
     * Everything that carries meaning across calls is reset: the bin history,
     * the probability history, the recurrent `state` tensor the model threads
     * between windows, the trailing-context samples, the partial chunk, the
     * resampler tail, and the "first window has no context" flag. Missing any
     * one of those leaves the model primed on audio from before the seek.
     *
     * The ONNX session itself is deliberately kept — it is the expensive part
     * and it holds no timeline state.
     *
     * @param newOnsetOffsetSec absolute media time the next window belongs to.
     */
    fun reset(newOnsetOffsetSec: Double = onsetOffsetSec) {
        totalFrames = 0
        curRunVal = (-1).toByte()
        curRunStart = 0
        curRunLen = 0
        prevZeroLen = null
        regionStart = -1
        regionFirstLen = 0
        detectedOnsets.clear()

        totalBins = 0
        currentEnvIdx = -1
        java.util.Arrays.fill(ringBuffer, 0f)
        java.util.Arrays.fill(envelopeSums, 0f)
        java.util.Arrays.fill(envelopeCounts, 0)
        onsetOffsetSec = newOnsetOffsetSec
        java.util.Arrays.fill(state, 0f)
        java.util.Arrays.fill(contextSamples, 0f)
        java.util.Arrays.fill(chunk, 0f)
        chunkN = 0
        hasContext = false
        resampler.reset()
    }

    override fun close() {
        try { srTensor?.close() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        // env is the shared singleton; never close it
    }

    // ── linear resampler to 16 kHz mono ──────────────────────────────
    // v0.7.1 speed pass: pending was an ArrayList<Float> — one boxed
    // allocation per sample (48kHz stereo → 48k boxes/second, GC churn
    // through the whole multi-minute decode). A growable FloatArray
    // with manual sizing does the same job with zero per-sample cost.
    private val resampler = object {
        private var srcRate = 0
        private var fracPos = 0.0
        private var prevLast = 0f
        private var pending = FloatArray(8192)
        private var n = 0

        fun push(x: Float) {
            if (n == pending.size) pending = pending.copyOf(n * 2)
            pending[n++] = x
        }

        fun drain(sampleRate: Int) {
            if (srcRate != sampleRate) {
                srcRate = sampleRate
                fracPos = 0.0
            }
            if (n < 2) {
                n = 0
                return
            }
            val step = srcRate.toDouble() / SR.toDouble()
            var p = fracPos
            while (p < n - 1) {
                val idx = floor(p).toInt()
                val f = (p - idx).toFloat()
                val a = if (idx < 0) prevLast else pending[idx]
                val bVal = pending[idx + 1]
                onSample16k(a + f * (bVal - a))
                p += step
            }
            fracPos = p - n
            prevLast = if (n > 0) pending[n - 1] else 0f
            n = 0
        }

        /**
         * Clears rate/phase/history so a re-anchored stream does not resume
         * mid-interpolation. Called from [SileroVad.reset] on seek: keeping
         * `prevLast` would blend one sample from before the seek into the
         * first window after it, and keeping `fracPos` would shift the whole
         * 16 kHz grid by a fraction of a sample.
         */
        fun reset() {
            srcRate = 0
            fracPos = 0.0
            prevLast = 0f
            n = 0
        }
    }
}
