package dev.anonrode.player.ui

/* ── Rotation mode ───────────────────────────────────────────────────────
 * The three-state rotation truth for the player overlay. Nothing owns
 * orientation directly — they all drive this enum.
 *
 *   SENSOR         free rotation (default; what the system + sensor would
 *                  normally pick)
 *   LANDSCAPE      screen forced to sensor landscape
 *   PORTRAIT       screen forced to portrait
 *
 * Cycle order ([next]): SENSOR → LANDSCAPE → PORTRAIT → SENSOR.
 *
 * Writers:
 *   - QuickRowUiState.rotateMode (PlayerScreenState.kt) holds the value
 *   - PlayerScreenActions.cycleRotateMode / setRotateMode /
 *     applyRotateMode flip it
 *
 * Readers:
 *   - RotationLockEffect (PlayerScreenEffects.kt) mirrors it onto the
 *     activity's [android.content.pm.ActivityInfo] orientation
 *   - the ribbon's ROTATION tool (PlayerScreenControls.kt) renders the
 *     current mode as its label and cycles it on tap
 *   - the dock's transport control (PlayerScreenBottomBar.kt) cycles it
 * ------------------------------------------------------------------------- */

/** Three-state rotation mode. Mirrors the orientation flags in
 *  [android.content.pm.ActivityInfo] that [RotationLockEffect] applies. */
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
