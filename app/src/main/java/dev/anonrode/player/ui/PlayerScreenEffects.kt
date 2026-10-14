package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.core.media.log.AppLog
import kotlin.math.roundToInt
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

/** Rotation lock: maps the 3-state [RotateMode] to the activity's
 *  requested orientation. Sensor → full sensor; landscape → sensor
 *  landscape; portrait → portrait. On dispose always restores full
 *  sensor so the next screen entry starts in free rotation. */
@Composable
internal fun RotationLockEffect(activity: Activity?, mode: RotateMode) {
    DisposableEffect(mode) {
        activity?.requestedOrientation = when (mode) {
            RotateMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            RotateMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            RotateMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
                // This MUST gate on STATE_ENDED directly: it is mutually
                // exclusive with STATE_READY, so nesting it inside the READY
                // branch above made the whole end-of-episode sleep option
                // unreachable (and let auto-advance ignore the sleep timer).
                if (playbackState == Player.STATE_ENDED && sleep.atEpisodeEnd.value) {
                    player.pause()
                    sleep.atEpisodeEnd.value = false
                    sleep.selection.value = SleepOptions.first()
                    // Tell the activity to hold auto-advance for this finish.
                    onHoldAutoAdvance()
                }
            }

            override fun onRenderedFirstFrame() {
                ui.posterBitmap.value = null
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

/**
 * HUD auto-hide while playing. v0.7.3: the old extra brake was
 * `menuOpen` — a flag NOBODY ever set (its toggle lived in a deleted
 * dropdown), so it was permanently false and chrome faded out under the
 * open overflow sheet / sync popover / subtitle dropdown. `stayAwake`
 * replaces it with the real union of every open surface, computed by
 * PlayerScreen.
 */
@Composable
internal fun AutoHideControlsEffect(
    controlsVisible: Boolean,
    isPlaying: Boolean,
    locked: Boolean,
    stayAwake: Boolean,
    autoHideControlsMs: Long,
    userInteractionToken: Long = 0L,
    /** Polled INSIDE the effect (never read in composition, so the screen
     *  body doesn't recompose on drag ticks): true while a gesture — e.g. a
     *  slow swipe-seek whose scrub bubble lives in the chrome — must keep
     *  the chrome visible regardless of how long the gesture runs. */
    extraStayAwake: () -> Boolean = { false },
    onHide: () -> Unit,
) {
    LaunchedEffect(controlsVisible, isPlaying, locked, stayAwake, autoHideControlsMs, userInteractionToken) {
        if (controlsVisible && isPlaying && !locked && !stayAwake) {
            // Hold for the whole gesture, then run the hide window fresh
            // from gesture end.
            while (extraStayAwake()) delay(100)
            delay(autoHideControlsMs)
            onHide()
        }
    }
}

/**
 * Tech sub-line truth source (hub 1.3). Publishes the LIVE video geometry —
 * coded width/height from [Player.getVideoSize], frame rate from
 * [Player.getVideoFormat] — into the states the top bar's tech sub-line
 * reads, so the line under the filename states real engine values or
 * nothing at all (rule 11: no fake "1080p · 24fps"). A segment whose source
 * has no signal (size 0, unparsed frame rate) reads 0 and the UI omits it.
 *
 * Published once on attach, on every [Player.Listener.onVideoSizeChanged]
 * (mid-stream size/crop changes) and on every READY transition (a fresh
 * media item / episode switch fills the track format there). Keyed on the
 * player, so a decoder swap re-seeds automatically.
 */
@UnstableApi
@Composable
internal fun VideoTechLineEffect(
    player: Player,
    videoWidth: MutableIntState,
    videoHeight: MutableIntState,
    videoFps: MutableIntState,
) {
    DisposableEffect(player) {
        fun publish() {
            val size = player.videoSize
            videoWidth.intValue = size.width
            videoHeight.intValue = size.height
            // Format.frameRate is NO_VALUE (-1) when the container does not
            // declare an average frame rate — zero means "omit the segment".
            val frameRate = player.videoFormat?.frameRate ?: -1f
            videoFps.intValue = if (frameRate > 0f) frameRate.roundToInt() else 0
        }
        publish()
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) publish()
            }
        }
        player.addListener(l)
        onDispose {
            try {
                player.removeListener(l)
            } catch (_: Throwable) {
                // Player already released — nothing left to detach.
            }
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
        // SP read + parse is a disk + JSON-adjacent cost; the first call
        // after process death can take tens of ms on a budget device, so
        // do it off the main thread. The floatValue writes are Compose
        // snapshot writes — safe from any thread (they enqueue onto the
        // main looper internally).
        val saved = withContext(Dispatchers.IO) {
            PlayerPrefs.subtitlePosition(context, mediaId)
        }
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
        // SP write (MRU prune, two key puts) — off the main thread so a
        // drag of the position preset doesn't jank the next frame.
        withContext(Dispatchers.IO) {
            PlayerPrefs.saveSubtitlePosition(
                context, mediaId, gestures.subX.floatValue, gestures.subY.floatValue,
            )
        }
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
                // Perf fix: a native-res ARGB_8888 frame is ~8MB on 1080p
                // — the poster only needs to cover the black flash before
                // STATE_READY, and it competes with prepare() for the same
                // container/IO. Scaled decode: same cover, ~0.5MB, and the
                // decoder does far less work while ExoPlayer opens the file.
                val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270,
                    )
                } else {
                    retriever.getFrameAtTime(0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
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

/**
 * Scrub frame preview (v0.7.1 UI pass): while the user is scrubbing —
 * slider drag ([PlayerUiState.localSeek]) OR horizontal swipe
 * ([GestureUiState.pendingSeekMs]) — keep ONE MediaMetadataRetriever open
 * for the whole scrub session and decode throttled, downscaled frames at
 * the pending target, published to [PlayerUiState.scrubPreview] for the
 * scrub bubble.
 *
 * Keying: the effect keys on the derived "scrubbing" boolean, never on the
 * target itself — the target moves at pointer-event rate, and re-keying
 * per tick would reopen the retriever dozens of times a second. The
 * derivedStateOf read lives inside THIS composable's scope, so the
 * pointer-event stream never invalidates PlayerScreen's body; only the
 * drag-start / drag-end transitions do.
 *
 * Frame source notes: OPTION_CLOSEST_SYNC decodes from the nearest
 * keyframe, which is the budget-safe choice — an exact (OPTION_CLOSEST)
 * decode must walk from the previous keyframe and can cost 300ms+ per
 * frame on a 10s-GOP rip. The preview frame is therefore approximate (up
 * to one GOP away); the time label on the bubble carries the exact target.
 * The retriever's codec use can contend with active playback decode on
 * low-end devices, hence the small 256×144 target and the ~8fps cap.
 */
@Composable
internal fun ScrubPreviewEffect(
    mediaId: String,
    context: Context,
    ui: PlayerUiState,
    gestures: GestureUiState,
) {
    val scrubbing by remember {
        derivedStateOf {
            ui.localSeek.floatValue >= 0f || gestures.pendingSeekMs.floatValue >= 0f
        }
    }
    LaunchedEffect(scrubbing, mediaId) {
        if (!scrubbing || mediaId.isBlank()) {
            ui.scrubPreview.value = null
            return@LaunchedEffect
        }
        val retriever = MediaMetadataRetriever()
        try {
            withContext(Dispatchers.IO) {
                try {
                    retriever.setDataSource(context, Uri.parse(mediaId))
                } catch (t: Throwable) {
                    AppLog.e("SCRUB", "retriever open failed for $mediaId", t)
                    return@withContext
                }
                while (true) {
                    val targetMs = when {
                        ui.localSeek.floatValue >= 0f ->
                            (ui.localSeek.floatValue * 1000).toLong()
                        gestures.pendingSeekMs.floatValue >= 0f ->
                            gestures.pendingSeekMs.floatValue.toLong()
                        else -> break
                    }
                    val bmp: Bitmap? = try {
                        if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(
                                targetMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                256, 144,
                            )
                        } else {
                            retriever.getFrameAtTime(
                                targetMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        }
                    } catch (t: Throwable) {
                        AppLog.e("SCRUB", "frame decode failed at ${targetMs}ms", t)
                        null
                    }
                    if (bmp != null) {
                        ui.scrubPreview.value = bmp.asImageBitmap()
                    }
                    delay(120)
                }
            }
        } finally {
            ui.scrubPreview.value = null
            try { retriever.release() } catch (_: Throwable) {}
        }
    }
}

/**
 * A-B repeat playback loop: when both A and B timestamps are set and B > A,
 * playback loops back to A when the position reaches or exceeds B.
 *
 * ONE effect for the whole life of the region — keyed on the region and the
 * player, never on the live position (a position key relaunched this effect
 * every 10Hz tick). Seeks BEFORE A are respected (no yank forward); the
 * seek guard absorbs the position ticks that still report the pre-seek
 * value in the ~100ms before the player acknowledges the seek, so one
 * user-visible loop is exactly one seekTo.
 */
@Composable
internal fun AbRepeatEffect(
    abStartMs: Long?,
    abEndMs: Long?,
    player: Player,
    positionSec: State<Float>,
) {
    val active = abStartMs != null && abEndMs != null && abEndMs > abStartMs
    val startMs = abStartMs ?: 0L
    val endMs = abEndMs ?: 0L
    LaunchedEffect(player, active, startMs, endMs) {
        if (!active) return@LaunchedEffect
        var seekGuardUntil = 0L
        snapshotFlow { (positionSec.value * 1000f).toLong() }
            .collect { currentMs ->
                if (currentMs >= endMs && System.currentTimeMillis() >= seekGuardUntil) {
                    seekGuardUntil = System.currentTimeMillis() + 800L
                    player.seekTo(startMs)
                }
            }
    }
}
