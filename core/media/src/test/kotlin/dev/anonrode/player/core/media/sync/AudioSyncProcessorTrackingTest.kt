package dev.anonrode.player.core.media.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AudioSyncProcessorTrackingTest {

    private data class CutEvent(val posSec: Double, val offsetSec: Float)

    private class TrackingListener : SyncListener {
        val cuts = mutableListOf<CutEvent>()
        val refinements = mutableListOf<Pair<Double, Float>>()
        override fun onSyncLocked(offsetSeconds: Float, speedFactor: Float) = Unit
        override fun onSyncTrackingUpdate(
            positionSec: Double,
            offsetSeconds: Float,
            speedFactor: Float,
            isPiecewiseJump: Boolean,
        ) {
            if (isPiecewiseJump) {
                cuts.add(CutEvent(positionSec, offsetSeconds))
            } else {
                refinements.add(positionSec to offsetSeconds)
            }
        }
        override fun onSyncNoMatch() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 8000
        const val CHANNELS = 1
        const val WINDOW_SAMPLES = SAMPLE_RATE / 100 * CHANNELS // 10ms
        const val SPEECH_AMP = 9000
        const val GAP_AMP = 60

        class Seg(val start: Double, val end: Double)

        fun generateDialogueSegments(totalSeconds: Int): List<Seg> {
            var seed = 0x2545F4914F6CDD1DL
            fun next(bound: Int): Int {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                return ((seed ushr 33).toInt() and 0x7FFFFFFF) % bound
            }
            val out = ArrayList<Seg>()
            var t = 1.0
            while (t < totalSeconds) {
                val on = 0.8 + next(18) / 10.0  // 0.8 .. 2.5s
                val off = 0.4 + next(13) / 10.0 // 0.4 .. 1.6s
                if (t + on > totalSeconds) break
                out.add(Seg(t, t + on))
                t += on + off
            }
            return out
        }

        fun feedRange(
            p: AudioSyncProcessor,
            segs: List<Seg>,
            fromSec: Int,
            toSec: Int,
            ampSpeech: Int = SPEECH_AMP,
            ampGap: Int = GAP_AMP,
        ) {
            val buf = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 2).order(ByteOrder.nativeOrder())
            var seed = -7046029254386353131L + fromSec * 31L
            var idx = 0
            while (idx < segs.size && segs[idx].end <= fromSec) idx++
            for (sec in fromSec until toSec) {
                for (sub in 0 until 100) {
                    val t = sec + sub / 100.0
                    while (idx < segs.size && segs[idx].end <= t) idx++
                    val speaking = idx < segs.size && t >= segs[idx].start && t < segs[idx].end
                    val amp = if (speaking) ampSpeech else ampGap
                    buf.clear()
                    repeat(WINDOW_SAMPLES) {
                        seed = seed * 1103515245L + 12345L
                        val noise = ((seed ushr 20).toInt() and 0x1FFF) - 0x1000
                        val v = (noise.toLong() * amp / 4096L).toInt().coerceIn(-32768, 32767)
                        buf.putShort(v.toShort())
                    }
                    buf.flip()
                    p.queueInput(buf)
                    p.getOutput()
                }
                // Let single-flight background tracking worker process scheduled evaluations deterministically
                p.awaitWorkerIdleForTest()
            }
        }
    }

    @Test
    fun `setBaselineLock 0s survives configure and post-seek resetWindow`() {
        val listener = TrackingListener()
        val p = AudioSyncProcessor(listener)
        val segs = generateDialogueSegments(120)
        p.setCues(segs.map { SubtitleCue(it.start, it.end, listOf("line")) })

        // Arm verified 0ms in-sync baseline BEFORE configure() — mirrors PlaybackEngine.play()
        p.setBaselineLock(0f, 1.0f, "0.0:0.000;600.0:8.500")
        p.configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        assertTrue("baseline lock must survive configure()", p.locked)
        assertTrue("trackingMode must remain armed after configure()", p.trackingMode)

        // Simulate user scrubbing to 10m (600s) and feeding 1s of audio
        p.setStartPosition(600_000L)
        feedRange(p, segs, fromSec = 0, toSec = 2)
        assertTrue("baseline lock must survive seek resetWindow()", p.locked)
        assertTrue("trackingMode must remain armed after seek", p.trackingMode)
    }

    @Test
    fun `continuous tracking catches multiple mid-episode cuts beyond 1_5s and past 400s`() {
        // 13-minute episode (780s > 400s ring wrap threshold):
        //   0s..300s   : in sync (offset = 0.0s)
        //   300s..540s : mid-episode cut #1 -> subtitles shifted so required applied offset = +8.5s
        //   540s..780s : mid-episode cut #2 -> subtitles shifted so required applied offset = -6.0s
        val totalSec = 780
        val segs = generateDialogueSegments(totalSec)

        // Renderer convention: audio_time = cue_time + applied_offset => cue_time = audio_time - applied_offset
        val cues = segs.map { seg ->
            val appliedOffset = when {
                seg.start < 300.0 -> 0.0
                seg.start < 540.0 -> 8.5
                else -> -6.0
            }
            SubtitleCue(
                start = (seg.start - appliedOffset).coerceAtLeast(0.0),
                end = (seg.end - appliedOffset).coerceAtLeast(0.1),
                lines = listOf("line"),
            )
        }.sortedBy { it.start }

        val listener = TrackingListener()
        val p = AudioSyncProcessor(listener).apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
            setCues(cues)
            setBaselineLock(0f, 1.0f)
        }

        // Feed Segment 1 (0..300s): clean synced playback -> ZERO false cuts allowed
        feedRange(p, segs, fromSec = 0, toSec = 300)
        p.awaitWorkerIdleForTest()
        assertEquals("expected zero false cuts during clean 0..300s segment", 0, listener.cuts.size)

        // Feed Segment 2 (300..540s): cut #1 (+8.5s) must be caught
        feedRange(p, segs, fromSec = 300, toSec = 540)
        p.awaitWorkerIdleForTest()
        assertTrue("expected cut #1 (+8.5s) to be detected, cuts=${listener.cuts}", listener.cuts.isNotEmpty())
        val cut1 = listener.cuts.first()
        assertTrue(
            "cut #1 offset ${cut1.offsetSec}s should match +8.5s within 0.4s",
            abs(cut1.offsetSec - 8.5f) <= 0.4f,
        )

        // Feed Segment 3 (540..780s, past 400s): cut #2 (-6.0s) must also be caught
        val cutsBeforeSeg3 = listener.cuts.size
        feedRange(p, segs, fromSec = 540, toSec = 780)
        p.awaitWorkerIdleForTest()
        assertTrue(
            "expected cut #2 (-6.0s) to be detected, cuts=${listener.cuts}",
            listener.cuts.size > cutsBeforeSeg3,
        )
        val cut2 = listener.cuts.last()
        assertTrue(
            "cut #2 offset ${cut2.offsetSec}s should match -6.0s within 0.4s",
            abs(cut2.offsetSec - (-6.0f)) <= 0.4f,
        )
    }

    @Test
    fun `continuous tracking refuses false cuts during silence and continuous wall of sound`() {
        val segs = generateDialogueSegments(240)
        val cues = segs.map { SubtitleCue(it.start, it.end, listOf("line")) }

        val listener = TrackingListener()
        val p = AudioSyncProcessor(listener).apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
            setCues(cues)
            setBaselineLock(0f, 1.0f)
        }

        // 120s of pure silence followed by 120s of continuous wall-of-sound barrage
        feedRange(p, emptyList(), fromSec = 0, toSec = 120, ampSpeech = 0, ampGap = 0)
        val barrageSeg = listOf(Seg(120.0, 240.0))
        feedRange(p, barrageSeg, fromSec = 120, toSec = 240, ampSpeech = SPEECH_AMP, ampGap = SPEECH_AMP)
        p.awaitWorkerIdleForTest()

        assertEquals("must never hallucinate a cut on silence or music wall", 0, listener.cuts.size)
    }

    @Test
    fun `findJointSync honors non-zero baseSeconds without timestamp offset error`() {
        val baseSec = 200.0
        val bursts = listOf(
            215.0, 232.0, 250.0, 272.0, 295.0, 320.0, 345.0, 372.0, 400.0, 430.0,
            462.0, 495.0, 530.0, 565.0, 600.0, 638.0, 675.0, 715.0, 755.0,
        )
        val spanSec = 600.0
        val audio = FloatArray((spanSec / 0.1).toInt())
        for (t in bursts) {
            val b0 = ((t - baseSec) / 0.1).toInt()
            val b1 = ((t + 2.0 - baseSec) / 0.1).toInt()
            for (b in maxOf(0, b0) until minOf(audio.size, b1)) audio[b] = 0.85f
        }
        val expectedBeta = 4.2
        val cues = bursts.mapIndexed { idx, aStart ->
            val subStart = aStart - expectedBeta
            SubtitleCue(subStart, subStart + 2.0, listOf("Line $idx"))
        }

        val res = SpeechCorrelator.findJointSync(audio, cues, baseSeconds = baseSec)
        assertNotNull("findJointSync must lock when envelope starts at baseSeconds=$baseSec", res)
        assertTrue(
            "expected beta +4.2s, got ${res!!.beta}s",
            abs(res.beta - expectedBeta) <= 0.15,
        )
    }

    @Test
    fun `findBestShiftInWindow recovers large shift inside 300s chunk with wide cue corridor`() {
        // Chunk audio spans 900s..1200s; true post-cut offset is +42.0s (beta=42.0)
        val chunkOnsets = mutableListOf<Double>()
        var t = 905.0
        var i = 1
        while (t < 1195.0) {
            chunkOnsets.add(t)
            t += 6.5 + (i * 2.9 % 7.0)
            i++
        }
        // Full episode cues include cues before and after the chunk, shifted by +42.0s in chunk
        val wideCues = chunkOnsets.map { it - 42.0 }

        val cand = SyncFinder.findBestShiftInWindow(
            onsets = chunkOnsets,
            cueStarts = wideCues,
            alpha = 1.0,
            audioStartSec = 900.0,
            audioEndSec = 1200.0,
            centerBeta = 0.0,
            radius = 150.0,
        )
        assertTrue("expected shift +42.0s, got ${cand.beta}s", abs(cand.beta - 42.0) <= 0.25)
        assertTrue("expected high recall, got ${cand.recall}", cand.recall >= 0.85)
        assertTrue("expected strong margin, got ${cand.margin}", cand.margin >= 0.20)
    }
}
