package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.core.media.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/* ── PlayerScreen side-effects, extracted 1:1 ──────────────────────────────
 * Every effect below keeps EXACTLY the same keys it had before the split,
 * and PlayerScreen invokes them in the same order, so launch/dispose
 * sequencing is unchanged.
 * ------------------------------------------------------------------------- */

/** Restore the per-video zoom persisted by the host (keyed as before). */
@Composable
internal fun ZoomRestoreEffect(initialZoomIdx: Int, ui: PlayerUiState) {
    LaunchedEffect(initialZoomIdx) {
        if (initialZoomIdx in ZoomModes.indices && initialZoomIdx != ui.zoomIdx.intValue) {
            ui.zoomIdx.intValue = initialZoomIdx
        }
    }
}

/** Apply the active zoom mode to the surface frame even if the PlayerView
 *  was created before this index changed. */
@UnstableApi
@Composable
internal fun ZoomApplyEffect(zoomIdx: Int, ui: PlayerUiState) {
    LaunchedEffect(zoomIdx) {
        ui.playerViewRef.value?.resizeMode = ZoomModes[zoomIdx].resizeMode
    }
}

/** Rotation lock: sensor landscape while engaged, full sensor otherwise. */
@Composable
internal fun RotationLockEffect(activity: Activity?, rotationLocked: Boolean) {
    DisposableEffect(rotationLocked) {
        activity?.requestedOrientation = if (rotationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        onDispose {
            // Leaving the screen always restores free rotation.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
}

/**
 * Sleep-timer countdown: checks every second; pause + clear past expiry.
 * Keyed on the deadline only — NOT the player — so the countdown survives
 * decoder rebuilds.
 *
 * Audit #16 (preserved): at expiry the player is read fresh through
 * [playerAtExpiry] (which PlayerScreen binds to `engine?.player`) because
 * the player captured at composition may already be released by then —
 * pause() on a released ExoPlayer throws.
 */
@Composable
internal fun SleepTimerEffect(sleep: SleepTimerUiState, playerAtExpiry: () -> Player) {
    LaunchedEffect(sleep.endMs.value) {
        while (sleep.endMs.value != null) {
            delay(1000)
            val endMs = sleep.endMs.value ?: break
            val remaining = endMs - System.currentTimeMillis()
            sleep.remainingMs.value = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) {
                playerAtExpiry().pause()
                sleep.endMs.value = null
                sleep.selection.value = SleepOptions.first()
            }
        }
    }
}

/**
 * Player.Listener bridge: mirrors play/buffer state into the UI, re-asserts
 * the chosen speed on STATE_READY, drops the first-frame poster, and fires
 * the "end of episode" sleep timer on STATE_ENDED.
 */
@Composable
internal fun PlayerEventListenerEffect(
    player: Player,
    ui: PlayerUiState,
    sleep: SleepTimerUiState,
    readySpeed: () -> Float,
    onHoldAutoAdvance: () -> Unit,
) {
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                ui.isPlaying.value = p
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                AppLog.d("PLAYER", "state=" + playbackState)
                ui.isBuffering.value = playbackState == Player.STATE_BUFFERING
                // Re-assert the chosen speed when a new media item is ready
                // (or the boost rate while a hold-to-boost is engaged, so a
                // buffer stall mid-hold can't silently drop the 2×).
                if (playbackState == Player.STATE_READY) {
                    player.setPlaybackSpeed(readySpeed())
                    // First frame has painted — drop the poster.
                    ui.posterBitmap.value = null
                }
                // "End of episode" sleep timer fires when playback finishes.
                if (playbackState == Player.STATE_ENDED && sleep.atEpisodeEnd.value) {
                    player.pause()
                    sleep.atEpisodeEnd.value = false
                    sleep.selection.value = SleepOptions.first()
                    // Tell the activity to hold auto-advance for this finish.
                    onHoldAutoAdvance()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Drop the spinner so an error doesn't render as "buffering";
                // the recoverable error dialog is owned by the host activity.
                ui.isBuffering.value = false
                AppLog.e(
                    "PLAYER",
                    "error " + error.errorCodeName + ": " +
                        (error.cause?.message ?: error.message ?: "unknown"),
                    error,
                )
            }
        }
        player.addListener(l)
        onDispose {
            // PlaybackEngine.rebuild may have released this player before
            // the effect is disposed (the ExoPlayer instance is swapped out
            // from under us). Media3 generally tolerates removeListener on
            // a released player, but never let cleanup crash the UI.
            try {
                player.removeListener(l)
            } catch (_: Throwable) {
                // Player already released — nothing left to detach.
            }
        }
    }
}

/** Settings → player bridge: a sleep timer set in the settings screen arms
 *  here once, when the player composes. The in-screen menu can still
 *  override it afterwards. */
@Composable
internal fun InitialSleepTimerEffect(initialSleepTimerMinutes: Int, sleep: SleepTimerUiState) {
    LaunchedEffect(Unit) {
        if (initialSleepTimerMinutes != 0) {
            SleepOptions.firstOrNull { it.minutes == initialSleepTimerMinutes }
                ?.let { sleep.selectSleep(it) }
        }
    }
}

/** HUD auto-hide while playing. */
@Composable
internal fun AutoHideControlsEffect(
    controlsVisible: Boolean,
    isPlaying: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    autoHideControlsMs: Long,
    onHide: () -> Unit,
) {
    LaunchedEffect(controlsVisible, isPlaying, locked, menuOpen, autoHideControlsMs) {
        if (controlsVisible && isPlaying && !locked && !menuOpen) {
            delay(autoHideControlsMs)
            onHide()
        }
    }
}

/**
 * Hold-to-2× boost: the gesture HUD pill auto-hides 900ms after each
 * showHud, so re-post the "2× speed" pill while the finger stays down
 * and the boost is engaged.
 */
@Composable
internal fun BoostHudKeepAliveEffect(boostActive: Boolean, hud: HudUiState, view: View) {
    LaunchedEffect(boostActive) {
        while (boostActive) {
            hud.showHud(view, Icons.Filled.FastForward, "2× speed")
            delay(800)
        }
    }
}

/**
 * Restore the saved subtitle position for this video (global default
 * fallback) whenever the media item changes.
 */
@Composable
internal fun SubtitlePositionRestoreEffect(
    mediaId: String,
    context: Context,
    gestures: GestureUiState,
) {
    LaunchedEffect(mediaId) {
        val saved = PlayerPrefs.subtitlePosition(context, mediaId)
        gestures.subX.floatValue = saved?.first ?: SUB_DEFAULT_X
        gestures.subY.floatValue = saved?.second ?: SUB_DEFAULT_Y
    }
}

/**
 * The style system's Position row anchors the cue box to preset vertical
 * bands. The first invocation is skipped so the per-video saved position
 * (restored by [SubtitlePositionRestoreEffect]) wins on entry; afterwards
 * any position change (host sheet or long-press dropdown) moves the cue
 * live and persists it as the new per-video position.
 */
@Composable
internal fun SubtitlePositionPresetEffect(
    position: SubtitlePosition,
    context: Context,
    mediaId: String,
    gestures: GestureUiState,
) {
    LaunchedEffect(position) {
        if (!gestures.subPosInitialized.value) {
            gestures.subPosInitialized.value = true
            return@LaunchedEffect
        }
        gestures.subY.floatValue = when (position) {
            SubtitlePosition.TOP -> 0.18f
            SubtitlePosition.HIGH -> 0.34f
            SubtitlePosition.MID -> 0.50f
            SubtitlePosition.LOW -> SUB_DEFAULT_Y
        }
        PlayerPrefs.saveSubtitlePosition(context, mediaId, gestures.subX.floatValue, gestures.subY.floatValue)
    }
}

/**
 * First-frame poster: decode the video URI off the main thread, grab
 * frame 0, and stash it as an ImageBitmap. The poster is dropped
 * automatically when the player's STATE_READY fires (see
 * [PlayerEventListenerEffect]).
 */
@Composable
internal fun FirstFramePosterEffect(mediaId: String, context: Context, ui: PlayerUiState) {
    LaunchedEffect(mediaId) {
        if (mediaId.isBlank()) {
            ui.posterBitmap.value = null
            return@LaunchedEffect
        }
        ui.posterBitmap.value = null
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(mediaId))
                val bmp: Bitmap? = retriever.getFrameAtTime(0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    ui.posterBitmap.value = bmp.asImageBitmap()
                }
            } catch (e: Throwable) {
                AppLog.e("POSTER", "failed to grab first frame for $mediaId", e)
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }
}
