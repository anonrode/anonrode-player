package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ── Rotate button (bottom-right of the new transport dock) ───────────────
 * Three states, cycled by tap:
 *
 *   SENSOR         — free rotation (default; what the system + sensor would
 *                    normally pick). Icon: ScreenRotation (rotation arrow).
 *   LANDSCAPE LOCK — screen forced to sensor landscape. Icon:
 *                    ScreenLockRotation (arrow with a tiny lock badge).
 *   PORTRAIT LOCK  — screen forced to portrait. Icon: StayCurrentPortrait.
 *
 * Long-press opens a 3-row dropdown that lets the user jump straight to a
 * specific state. The cycle order is: SENSOR → LANDSCAPE → PORTRAIT → SENSOR.
 *
 * Wired to the existing [RotationLockEffect] in PlayerScreenEffects.kt via
 * a callback — this composable only renders the icon + dropdown; the host
 * composable (PlayerScreen) flips the rotation flag and the effect applies
 * the new [android.content.pm.ActivityInfo] orientation.
 *
 * Visual contract:
 *   - 48dp circle (matches the surrounding PiP / sibling controls).
 *   - 1dp white-22% border, black-35% fill (frosted look).
 *   - Accent tint while locked (landscape OR portrait) — sensor state is
 *     white so the user can tell at a glance which mode is active.
 *   - Icon rotates 90° on every state change so it animates the transition
 *     instead of popping.
 * ------------------------------------------------------------------------- */

/** The rotation mode surfaced by the button. Mirrors the orientation flags
 *  in [android.content.pm.ActivityInfo] used by [RotationLockEffect]. */
internal enum class RotateMode {
    /** Free rotation (sensor). The default mode on first entry. */
    SENSOR,

    /** Forced to sensor landscape. */
    LANDSCAPE,

    /** Forced to portrait. */
    PORTRAIT;

    fun next(): RotateMode = when (this) {
        SENSOR -> LANDSCAPE
        LANDSCAPE -> PORTRAIT
        PORTRAIT -> SENSOR
    }
}

/** Tap target — 48dp circle with icon centered. Long-press surfaces a
 *  dropdown with all three options for a direct jump. */
@Composable
internal fun PlayerScreenRotateButton(
    mode: RotateMode,
    accent: Color,
    onCycle: () -> Unit,
    onSetMode: (RotateMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .border(
                    width = 1.dp,
                    color = if (mode == RotateMode.SENSOR) Color.White.copy(alpha = 0.20f)
                    else accent.copy(alpha = 0.55f),
                    shape = CircleShape,
                )
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true,
                        color = if (mode == RotateMode.SENSOR) Color.White else accent),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        menuOpen = false
                        onCycle()
                    },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        menuOpen = true
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Spin the icon 90° per state change so the user sees the
            // transition rather than a hard swap. graphicsLayer is the
            // GPU-friendly way to apply a rotateZ without triggering a
            // layout pass.
            val targetRot = when (mode) {
                RotateMode.SENSOR -> 0f
                RotateMode.LANDSCAPE -> 90f
                RotateMode.PORTRAIT -> 180f
            }
            Icon(
                imageVector = iconFor(mode),
                contentDescription = contentDescriptionFor(mode),
                tint = if (mode == RotateMode.SENSOR) Color.White else accent,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = targetRot },
            )
        }
        // Long-press dropdown — three explicit entries so the user can
        // jump directly to a mode instead of cycling.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MxPanel,
        ) {
            RotateDropdownItem(
                icon = Icons.Filled.ScreenRotation,
                label = "Sensor (auto)",
                selected = mode == RotateMode.SENSOR,
                accent = accent,
                onClick = {
                    menuOpen = false
                    if (mode != RotateMode.SENSOR) onSetMode(RotateMode.SENSOR)
                },
            )
            HorizontalDivider(color = MxMenuDivider)
            RotateDropdownItem(
                icon = Icons.Filled.StayCurrentLandscape,
                label = "Landscape locked",
                selected = mode == RotateMode.LANDSCAPE,
                accent = accent,
                onClick = {
                    menuOpen = false
                    if (mode != RotateMode.LANDSCAPE) onSetMode(RotateMode.LANDSCAPE)
                },
            )
            HorizontalDivider(color = MxMenuDivider)
            RotateDropdownItem(
                icon = Icons.Filled.StayCurrentPortrait,
                label = "Portrait locked",
                selected = mode == RotateMode.PORTRAIT,
                accent = accent,
                onClick = {
                    menuOpen = false
                    if (mode != RotateMode.PORTRAIT) onSetMode(RotateMode.PORTRAIT)
                },
            )
        }
    }
}

@Composable
private fun RotateDropdownItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) accent else Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingIcon = {
            Icon(
                icon, null,
                tint = if (selected) accent
                else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}

private fun iconFor(mode: RotateMode): ImageVector = when (mode) {
    RotateMode.SENSOR -> Icons.Filled.ScreenRotation
    RotateMode.LANDSCAPE -> Icons.Filled.ScreenLockRotation
    RotateMode.PORTRAIT -> Icons.Filled.PhoneAndroid
}

private fun contentDescriptionFor(mode: RotateMode): String = when (mode) {
    RotateMode.SENSOR -> "Rotation: auto (sensor). Long-press for more."
    RotateMode.LANDSCAPE -> "Rotation: landscape locked. Long-press for more."
    RotateMode.PORTRAIT -> "Rotation: portrait locked. Long-press for more."
}