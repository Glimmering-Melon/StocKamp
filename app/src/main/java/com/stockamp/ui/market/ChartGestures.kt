package com.stockamp.ui.market

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

private const val ZOOM_MIN = 1f
private const val ZOOM_MAX = 10f
private const val CROSSHAIR_HIDE_DELAY_MS = 1000L

data class ChartGestureState(
    val zoomLevel: Float = 1f,       // constrained [1, 10]
    val panOffset: Float = 0f,       // horizontal pan in pixels
    val crosshairPosition: Offset? = null
)

/**
 * Wraps chart content with pinch-to-zoom, drag-to-pan, double-tap reset,
 * and touch crosshair gestures.
 */
@Composable
fun ChartGestureWrapper(
    contentWidth: Float,
    modifier: Modifier = Modifier,
    onCrosshairMoved: (Offset?) -> Unit = {},
    content: @Composable (zoomLevel: Float, panOffset: Float) -> Unit
) {
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableFloatStateOf(0f) }
    var crosshairVisible by remember { mutableStateOf(false) }
    var crosshairPos by remember { mutableStateOf(Offset.Zero) }

    // Animate zoom reset to 1x on double-tap
    val animatedZoom by animateFloatAsState(
        targetValue = zoomLevel,
        animationSpec = tween(durationMillis = 200),
        label = "zoom_anim"
    )

    // Auto-hide crosshair after delay
    LaunchedEffect(crosshairPos, crosshairVisible) {
        if (crosshairVisible) {
            delay(CROSSHAIR_HIDE_DELAY_MS)
            crosshairVisible = false
            onCrosshairMoved(null)
        }
    }

    Box(
        modifier = modifier
            // Pinch-to-zoom and drag-to-pan
            .pointerInput(contentWidth) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (zoomLevel * zoom).coerceIn(ZOOM_MIN, ZOOM_MAX)
                    zoomLevel = newZoom

                    val maxPan = contentWidth * (newZoom - 1f)
                    panOffset = (panOffset + pan.x).coerceIn(-maxPan, 0f)
                }
            }
            // Double-tap to reset zoom
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        zoomLevel = 1f
                        panOffset = 0f
                    },
                    onPress = { pos ->
                        // Show crosshair on touch
                        crosshairPos = pos
                        crosshairVisible = true
                        onCrosshairMoved(pos)
                        tryAwaitRelease()
                    }
                )
            }
    ) {
        content(animatedZoom, panOffset)
    }
}
