package dev.anonrode.player.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import dev.anonrode.player.core.media.log.AppLog

/**
 * Bottom-sheet picker for [androidx.mediarouter.media.MediaRouter] audio
 * output routes. Mirrors the UX of the system MediaRouter dialog used by
 * MX Player / VLC / YouTube: tap a route to [onSelectRoute], tap the
 * "Phone speaker" entry (the system default route) to return audio to
 * the device.
 *
 * The router's route set is observed live: discovering a new Bluetooth
 * or Cast device while the sheet is open refreshes the list without
 * closing it. Empty states ("No external devices found") show a
 * guidance line so the user isn't staring at a blank panel.
 */
@Composable
fun CastRoutePickerSheet(
    mediaRouter: MediaRouter,
    accent: Color,
    onSelectRoute: (RouteInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }
    // MediaRouter has no "query by selector" call — snapshot its route set
    // and filter with RouteInfo.matchesSelector instead.
    fun refreshRoutes(): List<RouteInfo> =
        mediaRouter.routes.filter { it.matchesSelector(selector) }

    var routes by remember { mutableStateOf(refreshRoutes()) }
    var selectedRouteId by remember { mutableStateOf(mediaRouter.selectedRoute?.id) }

    DisposableEffect(mediaRouter) {
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: RouteInfo) {
                if (route.matchesSelector(selector)) {
                    AppLog.d("CAST", "route added: " + route.name + " id=" + route.id)
                    routes = refreshRoutes()
                }
            }
            override fun onRouteRemoved(router: MediaRouter, route: RouteInfo) {
                AppLog.d("CAST", "route removed: " + route.name)
                routes = refreshRoutes()
            }
            override fun onRouteSelected(router: MediaRouter, route: RouteInfo) {
                AppLog.d("CAST", "route selected: " + route.name)
                selectedRouteId = route.id
            }
            override fun onRouteUnselected(router: MediaRouter, route: RouteInfo) {
                AppLog.d("CAST", "route unselected: " + route.name)
                selectedRouteId = router.selectedRoute?.id
            }
        }
        mediaRouter.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        onDispose { mediaRouter.removeCallback(callback) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 480.dp)
            .background(Color(0xFF0E1017).copy(alpha = 0.96f))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CAST AUDIO",
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(if (selectedRouteId != null) "ROUTED" else "PHONE",
                color = if (selectedRouteId != null) accent else Color(0xFF5B6070),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))

        val defaultRoute = remember(mediaRouter) { mediaRouter.defaultRoute }
        val displayList = remember(routes, defaultRoute) {
            buildList {
                add(defaultRoute)
                addAll(routes.filter { it.id != defaultRoute.id })
            }
        }

        if (displayList.size <= 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF171A22)),
                contentAlignment = Alignment.Center,
            ) {
                Text("No external devices found.\nMake sure Bluetooth or Cast is on.",
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(displayList, key = { it.id }) { route ->
                    CastRouteRow(
                        route = route,
                        isSelected = route.id == selectedRouteId,
                        accent = accent,
                        onClick = {
                            onSelectRoute(route)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Close", color = accent)
            }
        }
    }
}

@Composable
private fun CastRouteRow(
    route: RouteInfo,
    isSelected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val icon: ImageVector = when {
        // isDefault() is a RouteInfo method exposed as a Kotlin property.
        route.isDefault -> Icons.Filled.Speaker
        // isBluetooth() covers both A2DP and BLE headset routes (the old
        // DEVICE_TYPE_BLUETOOTH constant was split in mediarouter 1.8).
        route.isBluetooth -> Icons.Filled.Headphones
        route.deviceType == RouteInfo.DEVICE_TYPE_HDMI -> Icons.Filled.Tv
        route.deviceType == RouteInfo.DEVICE_TYPE_WIRED_HEADSET -> Icons.Filled.Headphones
        else -> Icons.Filled.Cast
    }
    val typeLabel = when {
        route.isDefault -> "This phone"
        route.isBluetooth -> "Bluetooth"
        route.deviceType == RouteInfo.DEVICE_TYPE_HDMI -> "Display"
        route.deviceType == RouteInfo.DEVICE_TYPE_WIRED_HEADSET -> "Wired headset"
        route.deviceType == RouteInfo.DEVICE_TYPE_USB_DEVICE ||
            route.deviceType == RouteInfo.DEVICE_TYPE_USB_ACCESSORY -> "USB"
        else -> "External output"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) accent.copy(alpha = 0.12f)
                else Color(0xFF171A22)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) accent.copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) accent else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                route.name,
                color = Color(0xFFF2F4F8),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                typeLabel,
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}
