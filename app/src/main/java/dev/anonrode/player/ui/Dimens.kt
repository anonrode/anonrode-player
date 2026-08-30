package dev.anonrode.player.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide 8dp spacing grid.
 *
 * Every padding / spacer / `Arrangement.spacedBy(...)` value used by the
 * library + settings screens (and the overlay sheets under `audio/`)
 * MUST come from one of these tokens. Ad-hoc dp literals are reserved
 * for intrinsic component sizes (icon, thumbnail, slider thumb) where the
 * dimension is part of the component spec, not visual rhythm.
 *
 * The grid follows a strict 4 → 8 → 12 → 16 → 24 → 32 → 48 step so that
 * vertical and horizontal rhythm stay aligned across screens.
 *
 *  gapXs (4)  — hairline separators, badge insets
 *  gapSm (8)  — tight grouping (row ↔ icon, section ↔ header)
 *  gapMd (12) — default row / chip padding
 *  gapLg (16) — block-level breathing room
 *  gapXl (24) — section / sheet top padding
 *  gapXxl (32) — empty-state horizontal padding
 *  gapXxxl (48) — empty-state vertical padding
 *
 * Note: This object is `internal` to the `ui` package. Other agents
 * (player layout, library scanner, subtitle strategy) keep their own
 * ad-hoc dp values and may adopt this file separately if they want to.
 */
internal object Dimens {
    /** 4dp — tightest spacing: badge insets, hairline gaps. */
    val gapXs: Dp = 4.dp

    /** 8dp — close grouping: icon ↔ text, row internal padding. */
    val gapSm: Dp = 8.dp

    /** 12dp — default inline gap (between row groups, chip gutters). */
    val gapMd: Dp = 12.dp

    /** 16dp — block-level breathing room, sheet horizontal padding. */
    val gapLg: Dp = 16.dp

    /** 24dp — section top padding, dialog inset. */
    val gapXl: Dp = 24.dp

    /** 32dp — empty-state horizontal inset, large gutters. */
    val gapXxl: Dp = 32.dp

    /** 48dp — empty-state vertical breathing room. */
    val gapXxxl: Dp = 48.dp
}