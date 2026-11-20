package dev.miubridge.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.miubridge.R
import dev.miubridge.phone.PhoneController
import kotlinx.coroutines.delay

/**
 * The background display, refreshed about once a second while open, so the owner can see what an
 * agent (the one on this phone or any connected one) is doing there.
 */
@Composable
fun ScreenWatchDialog(phone: PhoneController, onDismiss: () -> Unit) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            phone.preview()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap() } }
            tried = true
            delay(800)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_title)) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                val image = frame
                when {
                    image != null -> Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium))
                    tried -> Text(stringResource(R.string.watch_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> CircularProgressIndicator()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** How long after an agent's last action the background display still counts as in use. */
private const val IN_USE_MS = 90_000L
private val THUMB_WIDTH = 104.dp

/** The owner's choice to show the floating live view, remembered across launches. On by default. */
@Composable
fun rememberLiveViewEnabled(): MutableState<Boolean> {
    val prefs = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
    return remember { mutableStateOf(prefs.getBoolean("live_view", true)) }
}

@Composable
private fun inUse(phone: PhoneController): Boolean {
    val state by phone.state.collectAsState()
    // Re-evaluated on a slow tick so the view goes away some time after the agent stops.
    val now by produceState(SystemClock.elapsedRealtime()) { while (true) { delay(5_000); value = SystemClock.elapsedRealtime() } }
    return state.shellReady && state.lastUsedAt > 0 && maxOf(now, state.lastUsedAt) - state.lastUsedAt < IN_USE_MS
}

/** App-bar button that shows or hides the floating live view. A dot marks activity while it is hidden. */
@Composable
fun LiveToggle(phone: PhoneController, enabled: MutableState<Boolean>) {
    val prefs = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
    val active = inUse(phone)
    IconButton(onClick = {
        enabled.value = !enabled.value
        prefs.edit().putBoolean("live_view", enabled.value).apply()
    }) {
        BadgedBox(badge = { if (active && !enabled.value) Badge() }) {
            Icon(
                AppIcons.Smartphone,
                stringResource(if (enabled.value) R.string.cd_live_hide else R.string.cd_live_show),
                tint = if (enabled.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The background display as a small live picture, floating in a corner while an agent is using it.
 * Just the screen; tap it for the full view. Hidden when the display is idle or the owner turned it off.
 */
@Composable
fun LiveThumbnail(phone: PhoneController, enabled: Boolean, modifier: Modifier = Modifier) {
    var enlarged by remember { mutableStateOf(false) }
    if (enlarged) ScreenWatchDialog(phone) { enlarged = false }

    // Draggable: the owner moves it off whatever it covers; the spot is remembered. It starts in the
    // top-right corner, so x runs from 0 (there) to the left edge, and y from 0 downwards.
    val prefs = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var offset by remember { mutableStateOf(Offset(prefs.getFloat("live_dx", 0f), prefs.getFloat("live_dy", 0f))) }
    val density = LocalDensity.current
    val window = LocalConfiguration.current
    val minX = with(density) { -(window.screenWidthDp.dp - THUMB_WIDTH - 32.dp).toPx() }
    val maxY = with(density) { (window.screenHeightDp.dp * 0.6f).toPx() }
    fun clamp(o: Offset) = Offset(o.x.coerceIn(minX, 0f), o.y.coerceIn(0f, maxY))
    val placed = modifier
        .offset { clamp(offset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = { prefs.edit().putFloat("live_dx", offset.x).putFloat("live_dy", offset.y).apply() }) { change, drag ->
                change.consume()
                offset = clamp(offset + drag)
            }
        }

    AnimatedVisibility(enabled && inUse(phone), modifier = placed) {
        var frame by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                phone.preview()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap() } }
                delay(800)
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.width(THUMB_WIDTH).clip(MaterialTheme.shapes.medium).clickable { enlarged = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                val image = frame
                if (image != null) {
                    Image(image, stringResource(R.string.watch_title), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                } else Box(Modifier.fillMaxWidth().height(208.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            }
        }
    }
}

/** Window widths from this up (an unfolded foldable, a tablet) have room for the background display beside the content. */
val TWO_PANE_MIN_WIDTH = 600.dp

/**
 * The background display as a pane of its own, for wide windows: always there while the owner has
 * the live view on, live whenever the display exists, so an unfolded phone shows the agent's screen
 * next to the app instead of a small picture over it.
 */
@Composable
fun LivePane(phone: PhoneController, modifier: Modifier = Modifier) {
    val state by phone.state.collectAsState()
    val active = inUse(phone)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(state.shellReady) {
        if (!state.shellReady) { frame = null; return@LaunchedEffect }
        while (true) {
            phone.preview()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap() } }
            delay(800)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.watch_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val image = frame
                when {
                    image != null -> Image(
                        image, stringResource(R.string.watch_title), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
                    )
                    state.shellReady -> CircularProgressIndicator()
                    else -> Text(stringResource(R.string.watch_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
