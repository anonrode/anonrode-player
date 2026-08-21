package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.max
import kotlin.math.min

/**
 * Speech model built from subtitle cues, ported from the mxweb-player JS
 * (`buildCueModel`). Bins at 0.1s resolution; `speech` = expected speech
 * density per bin, `edge` = expected cue-boundary emphasis.
 */
class CueModel(val speech: FloatArray, val edge: FloatArray) {
    val length: Int get() = speech.size
}

object CueModelBuilder {
    const val ALIGN_BIN = 0.1
    const val ALIGN_SCAN_SECONDS = 95.0
    const val ALIGN_MAX_OFFSET = 40.0

    fun build(cueSnapshot: List<SubtitleCue>): CueModel {
        val limit = ALIGN_SCAN_SECONDS + ALIGN_MAX_OFFSET + 6.0
        val bins = (limit / ALIGN_BIN).toInt() + 4
        val speech = FloatArray(bins)
        val edge = FloatArray(bins)

        for (cue in cueSnapshot) {
            val start = cue.start.coerceIn(0.0, limit)
            val end = cue.end.coerceIn(0.0, limit)
            val dur = end - start
            if (!(dur > 0.12)) continue
            val text = cue.lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            val chars = text.replace(" ", "").length
            val density = (chars / max(10.0, dur * 13.0)).coerceIn(0.72, 1.75)
            val startBin = max(0, (start / ALIGN_BIN).toInt())
            val endBin = min(bins - 1, (end / ALIGN_BIN).toInt().let { if (end % ALIGN_BIN == 0.0) it - 1 else it })

            for (i in startBin..endBin) {
                val t = i * ALIGN_BIN
                val inner = min(t - start, end - t) / max(0.12, min(0.7, dur * 0.22))
                val base = 0.52 + inner.coerceIn(0.0, 1.0) * 0.7
                speech[i] = max(speech[i], base * density.toFloat())
            }

            edge[startBin] = max(edge[startBin], 1.35f)
            if (startBin + 1 < bins) edge[startBin + 1] = max(edge[startBin + 1], 0.75f)
            edge[endBin] = max(edge[endBin], 0.42f)
        }

        // Smooth speech with a 1-2-1 kernel.
        for (i in 1 until speech.size - 1) {
            speech[i] = speech[i - 1] * 0.22f + speech[i] * 0.56f + speech[i + 1] * 0.22f
        }
        return CueModel(speech, edge)
    }
}
