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

        fun modelAvailable(context: Context): Boolean = try {
            context.assets.open(MODEL_ASSET).use { true }
        } catch (t: Throwable) {
            false
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession?
    private val state = FloatArray(STATE_LEN)
    private val contextSamples = FloatArray(CONTEXT)
    private var hasContext = false
    private val chunk = FloatArray(WINDOW)
    private var chunkN = 0
    private val bins = ArrayList<Byte>(4096)

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
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            s = env.createSession(bytes, opts)
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
        resampler.drain(sampleRate, this::onSample16k)
    }

    private fun onSample16k(x: Float) {
        chunk[chunkN++] = x
        if (chunkN < WINDOW) return
        chunkN = 0
        runInference()
    }

    private fun runInference() {
        val sess = session ?: return
        // input = [context(64)? , chunk(512)] — first call has no context
        val width = if (hasContext) WINDOW + CONTEXT else WINDOW
        val fb = inputBuf.clear().asFloatBuffer()
        if (hasContext) fb.put(contextSamples)
        fb.put(chunk)
        fb.flip() // position=0, limit=width — ONNX reads remaining()

        val stBuf = stateBuf.clear().asFloatBuffer()
        stBuf.put(state)
        stBuf.rewind() // position=0, limit=STATE_LEN

        OnnxTensor.createTensor(env, fb, longArrayOf(1L, width.toLong())).use { inputT ->
            OnnxTensor.createTensor(env, stBuf, longArrayOf(2L, 1L, 128L)).use { stateT ->
                OnnxTensor.createTensor(env, SR).use { srT ->
                    val inputs = mapOf(
                        "input" to inputT,
                        "state" to stateT,
                        "sr" to srT,
                    )
                    sess.run(inputs).use { result ->
                        // model output order (validated): [output, stateN]
                        val outVal = result.get(0) as OnnxTensor
                        val prob = (outVal.value as Array<FloatArray>)[0][0]
                        bins.add(if (prob > THRESHOLD) 1.toByte() else 0.toByte())
                        val stVal = result.get(1) as OnnxTensor
                        val stArr = stVal.value as Array<Array<FloatArray>>
                        var k = 0
                        for (a in stArr) for (b in a) for (v in b) state[k++] = v
                    }
                }
            }
        }
        // thread the trailing 64 samples of THIS input forward
        System.arraycopy(chunk, WINDOW - CONTEXT, contextSamples, 0, CONTEXT)
        hasContext = true
    }

    /** Speech-START onsets from the collected bins (fix-1 debounce). */
    fun finish(): List<Double> {
        val onsets = onsetsFromBins(bins)
        AppLog.d("VAD", "${bins.size} bins -> ${onsets.size} speech-start onsets")
        return onsets
    }

    override fun close() {
        try { session?.close() } catch (_: Throwable) {}
        // env is the shared singleton; never close it
    }

    // ── debounce: 0/1 bins -> speech-START events ────────────────────
    // Port of _fix1_vad_onsets.onsets_from_bins with the chosen defaults
    // (min_speech 150 ms, min_silence 100 ms, merge_gap 0 = no merge).
    private fun onsetsFromBins(
        b: List<Byte>,
        minSpeechMs: Int = MIN_SPEECH_MS,
        minSilenceMs: Int = MIN_SILENCE_MS,
    ): List<Double> {
        val n = b.size
        if (n == 0) return emptyList()
        val sp = maxOf(1, (minSpeechMs / (1000.0 * FRAME_SEC)).toInt())
        val sil = maxOf(1, (minSilenceMs / (1000.0 * FRAME_SEC)).toInt())

        // run-length encode
        data class Run(val v: Byte, val start: Int, val len: Int)
        val runs = ArrayList<Run>()
        var i = 0
        while (i < n) {
            val v = b[i]
            var j = i + 1
            while (j < n && b[j] == v) j++
            runs.add(Run(v, i, j - i))
            i = j
        }

        val onsets = ArrayList<Double>()
        var prevZeroLen: Int? = null
        var regionStart = -1
        var regionFirstLen = 0
        for (run in runs) {
            if (run.v.toInt() == 1) {
                if (regionStart < 0) {
                    regionStart = run.start
                    regionFirstLen = run.len
                }
            } else {
                if (regionStart >= 0) {
                    if (regionFirstLen >= sp &&
                        prevZeroLen != null && prevZeroLen >= sil
                    ) {
                        onsets.add(regionStart * FRAME_SEC)
                    }
                    regionStart = -1
                }
                prevZeroLen = run.len
            }
        }
        if (regionStart >= 0 && regionFirstLen >= sp &&
            prevZeroLen != null && prevZeroLen >= sil
        ) {
            onsets.add(regionStart * FRAME_SEC)
        }
        return onsets
    }

    // ── linear resampler to 16 kHz mono ──────────────────────────────
    private val resampler = object {
        private var srcRate = 0
        private var fracPos = 0.0
        private var prevLast = 0f
        private val pending = ArrayList<Float>(8192)

        fun push(x: Float) {
            pending.add(x)
        }

        fun drain(sampleRate: Int, emit: (Float) -> Unit) {
            if (srcRate != sampleRate) {
                srcRate = sampleRate
                fracPos = 0.0
            }
            val n = pending.size
            if (n < 2) return
            val step = srcRate.toDouble() / SR.toDouble()
            var p = fracPos
            while (p < n - 1) {
                val idx = floor(p).toInt()
                val f = (p - idx).toFloat()
                val a = if (idx < 0) prevLast else pending[idx]
                val bVal = pending[idx + 1]
                emit(a + f * (bVal - a))
                p += step
            }
            fracPos = p - n
            prevLast = pending[n - 1]
            pending.clear()
        }
    }
}
