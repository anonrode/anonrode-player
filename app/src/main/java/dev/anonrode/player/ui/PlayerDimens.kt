package dev.anonrode.player.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Player-overlay spacing tokens.
 *
 * The app-wide `Dimens` object (`gapXs` … `gapXxxl`) covers the library +
 * settings screens. The player overlay is a separate visual layer pinned
 * over a video frame — its grid is denser and its component sizes are
 * part of the spec, not just rhythm — so this object is intentionally
 * scoped to the `ui/Player*` files.
 *
 * 8dp grid: gapXs (4) · gapSm (8) · gapMd (12) · gapLg (16) · gapXl (24).
 *
 * Chip / icon sizes (component spec, not visual rhythm):
 *   chipSm   40dp   — lock toggle
 *   chipMd   48dp   — standard rail / transport / utility circles
 *   chipLg   56dp   — sub-sync toggle (room for the spinning ring)
 *   playBig  72dp   — central play / pause (50% bigger than siblings)
 *   railWidth 64dp  — outer pill around the rail icons
 *
 * Icon glyph sizes (Material guidance for touch targets):
 *   iconSm   20dp   — small / lock glyphs
 *   iconMd   24dp   — default inside 48dp circles
 *
 * Anything not listed here (one-off paddings, scrim alpha) stays an
 * ad-hoc literal — these tokens only cover values used in 2+ places or
 * that form a documented part of the player spec.
 */
internal object PlayerDimens {
    val gapXs: Dp = 4.dp
    val gapSm: Dp = 8.dp
    val gapMd: Dp = 12.dp
    val gapLg: Dp = 16.dp
    val gapXl: Dp = 24.dp

    val iconSm: Dp = 20.dp
    val iconMd: Dp = 24.dp

    val chipSm: Dp = 40.dp
    val chipMd: Dp = 48.dp
    val chipLg: Dp = 56.dp
    val playBig: Dp = 72.dp

    val railWidth: Dp = 64.dp
}