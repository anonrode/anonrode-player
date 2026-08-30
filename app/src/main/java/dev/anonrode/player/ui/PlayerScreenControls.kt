package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/* ── Player controls chrome (post-redesign) ───────────────────────────────
 *
 *   Top bar (auto-hide)        ←──  title + more
 *   Right-edge rail (auto-hide) ←──  CC / audio / sync / rotate / more
 *   Seek bar (always visible)   ←── 12:34 ──●── 45:21
 *   Transport (auto-hide)       ←── 🔒 ⏪10 ⏮ ▶(BIG) ⏭ ⏩10
 *
 * Refactor (Aug 2026):
 *   • The pre-redesign file was a ~700-line god-composable. Each visual
 *     block now lives in its own file (PlayerScreenTopBar lives here;
 *     bottom block in PlayerScreenBottomBar.kt; right rail in
 *     PlayerScreenActionRail.kt; overflow sheet in
 *     PlayerScreenOverflowSheet.kt). The dropdown menu that previously
 *     hosted the "more" entries is GONE — it's a bottom sheet now.
 *   • PlayerQuickRow / PlayerOverflowMenu were removed (their
 *     responsibilities moved to the rail and the sheet).
 *   • The 60+ parameter signature shrank: PlayerControlsOverlay no
 *     longer takes zoomAbbreviation, sleep, equalizerOn, headphonesOn,
 *     castRouteName, volumeBoostPct, etc. — those moved up to
 *     PlayerScreen.kt, which feeds them to the overflow sheet directly.
 *
 * Per design: the "more" button in the top bar AND the "more" button in
 * the right rail both open the SAME overflow sheet — a single
 * `overflowOpen` MutableState<Boolean> is shared between the two call
 * sites (held at the PlayerScreen level). The top bar receives the
 * `onMore` callback; the rail is rendered by PlayerScreen.kt directly.
 * ------------------------------------------------------------------------- */

/** Whole top chrome behind one fade: top bar + bottom dock. The right
 *  rail is rendered at the PlayerScreen level (it's a sibling in the
 *  root Box, not a child of the chrome). */
@UnstableApi
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    title: String,
    accent: Color,
    currentPositionMs: Long,
    positionSec: Float,
    durationSec: Float,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    locked: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onMore: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(220)),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Top chrome — back, title (truncated), more. Keyed on
            // menuOpen + abStartMs + hwDecoder so unrelated state flips
            // don't recompose it.
            key(actions.ui.menuOpen.value) {
                PlayerScreenTopBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    title = title,
                    accent = accent,
                    actions = actions,
                    onBack = onBack,
                    onMore = onMore,
                )
            }
            // Bottom chrome — seek bar (always visible) + transport
            // (auto-hide). The seek bar is intentionally OUTSIDE the
            // bottom-bar's AnimatedVisibility — it's always visible.
            // Keyed on isPlaying (BIG play icon flip) + locked.
            key(isPlaying, locked, actions.quick.subSyncEnabled.value) {
                PlayerScreenBottomBar(
                    visible = visible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    accent = accent,
                    currentPositionMs = currentPositionMs,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    localSeek = localSeek,
                    isPlaying = isPlaying,
                    locked = locked,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                    seekIncrementSec = seekIncrementSec,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    onLockToggle = { actions.lockControls() },
                    actions = actions,
                    subSyncEnabled = actions.quick.subSyncEnabled.value,
                    subSyncRunning = actions.quick.subSyncRunning.value,
                    onSetSubSyncEnabled = { actions.setSubSyncEnabled(it) },
                    onResyncNow = { actions.resyncNow() },
                    rotationLocked = actions.quick.rotateMode.value != RotateMode.SENSOR,
                    onCycleRotation = { actions.cycleRotateMode() },
                    onEnterPip = { actions.enterPip() },
                )
            }
        }
    }
}

/** Top bar — back, title (truncated), "more". The audio/CC/HW/SW/quick
 *  chips from the pre-redesign top bar moved to the right-edge rail
 *  (PlayerScreenActionRail.kt) and the overflow sheet
 *  (PlayerScreenOverflowSheet.kt). */
@UnstableApi
@Composable
internal fun PlayerScreenTopBar(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Same gradient scrim as v0.6.1 — black@80% top, transparent
            // bottom — so the title stays readable regardless of the
            // frame underneath.
            .background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            .padding(bottom = PlayerDimens.gapMd)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 4dp vertical / 8dp horizontal — keeps the top bar
                // aligned with the player chip row while leaving room
                // for the title's second line to stay inside the scrim.
                .padding(horizontal = PlayerDimens.gapSm, vertical = PlayerDimens.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                title,
                color = Color.White,
                // 2 lines max so a multi-word episode title can wrap
                // instead of ellipsising aggressively. Material's default
                // titleMedium body line-height keeps two lines inside
                // the top-bar scrim.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = PlayerDimens.gapXs),
            )
            // The "more" button — opens the same overflow sheet as the
            // rail's "more" (single shared state at PlayerScreen level).
            Box(
                modifier = Modifier
                    // 48dp tap target to match the rail / transport row's
                    // 48dp chip grid; the kdoc's 44dp was off-grid.
                    .size(PlayerDimens.chipMd)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = Color.White),
                        onClick = {
                            actions.view.performHapticFeedback(
                                HapticFeedbackConstants.KEYBOARD_TAP
                            )
                            onMore()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = Color.White,
                )
        }
    }
}

/* Bottom block (seek bar + transport row) lives in PlayerScreenBottomBar.kt. */
}