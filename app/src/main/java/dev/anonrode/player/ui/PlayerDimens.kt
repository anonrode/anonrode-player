package dev.anonrode.player.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Player-overlay sizing tokens (v0.7.3 chrome redesign).
 *
 * The app-wide `Dimens` object covers the library + settings screens. The
 * player overlay is a separate visual layer pinned over a video frame — its
 * grid is denser and its component sizes are part of the spec — so this
 * object is scoped to the `ui/Player*` files. Before this round most of
 * these tokens were fiction (defined but never used, with component files
 * hardcoding one-off sizes that then drifted from the docs); the redesign
 * made them real — every value declared below has at least one consumer.
 *
 * The invariant here is one-directional: EVERY value declared in this
 * object is consumed. The converse is deliberately NOT claimed — geometry
 * that is intrinsic to a single component stays beside that component
 * rather than becoming a token with exactly one user. Those live with
 * their component today: the status strip's 26dp pill, the style tray's
 * 16dp radius / 13×12dp padding / 34dp option height, the ribbon cell's
 * 34dp circle + 18dp glyph, and the Control Center tile's 88dp/32dp.
 *
 * Spacing rhythm (4/8dp grid):
 *   gapXs 4 · gapSm 8 · gapMd 12 · gapLg 16
 *
 * Touch discipline (Material: 48dp minimum on Android):
 *   touchMin 48dp — every control's clickable cell, whatever the visual
 *   chipVisual 40dp — the painted circle inside a touchMin cell (top bar +
 *   utility chips: dense rows of 40dp circles with 48dp hit areas)
 *   chipMd 48dp — visual == touch (play siblings, rotate, dialog-ish rows)
 *   playBig 64dp — the central play/pause
 *
 * Glyphs:
 *   glyphMd 22dp — every ControlChip icon · glyphPlay 32dp in the BIG play
 *
 * Text/pill row parts:
 *   skipW/skipH 48×44 — the ±N s pills (label carries the REAL configured
 *   step — the v0.7.2 audit's "pill says 10 while the setting says 30" lie
 *   is structurally impossible now: the pill renders seekIncrementSec)
 *   timeLabelMinW 52 — seek-row timestamps (reserve width so the slider
 *   doesn't jitter when the value flips to −remaining)
 *   pillH 40 — height of a labelled text pill (sync chip, speed, aspect)
 */
internal object PlayerDimens {
    val gapXs: Dp = 4.dp
    val gapSm: Dp = 8.dp
    val gapMd: Dp = 12.dp
    val gapLg: Dp = 16.dp

    val touchMin: Dp = 48.dp
    val chipVisual: Dp = 40.dp
    val chipMd: Dp = 48.dp
    val playBig: Dp = 64.dp

    val glyphMd: Dp = 22.dp
    val glyphPlay: Dp = 32.dp

    val skipW: Dp = 48.dp
    val skipH: Dp = 44.dp
    val pillH: Dp = 40.dp
    val timeLabelMinW: Dp = 52.dp
}
