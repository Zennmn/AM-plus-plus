package dev.amenhancer.glass

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.pow

@Composable
internal fun rememberGlassTextContrast(enabled: Boolean = true): GlassTextContrast? {
    if (!enabled) return null
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    return remember(layer, scope) { GlassTextContrast(layer, scope) }
}

/** Samples the finished material before text is drawn, at most once per interval. */
internal class GlassTextContrast(
    private val layer: GraphicsLayer,
    private val scope: CoroutineScope,
) {
    var foreground by mutableStateOf<Color?>(null)
        private set
    private val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
    private var nextSampleAt = 0L
    private var readback: Job? = null
    private var deferredDraw: Job? = null
    private var drawRequest by mutableIntStateOf(0)

    val sampleMaterial: DrawScope.(DrawScope.() -> Unit) -> Unit = { paint ->
        drawRequest // Subscribe the material draw to a deferred sample request.
        val panel = size
        if (panel.width >= SAMPLE_SIZE && panel.height >= SAMPLE_SIZE) {
            val now = SystemClock.uptimeMillis()
            if (readback?.isActive == true || now < nextSampleAt) {
                requestDeferredDraw()
            } else {
                deferredDraw?.cancel()
                nextSampleAt = now + SAMPLE_INTERVAL_MS
                layer.record(this, layoutDirection, IntSize(SAMPLE_SIZE, SAMPLE_SIZE)) {
                    val sampleSize = size
                    // Replay the middle of the panel into the tiny target, clear of rounded edges.
                    withTransform({
                        scale(sampleSize.width / (panel.width * .7f), sampleSize.height / (panel.height * .5f), Offset.Zero)
                        translate(-panel.width * .15f, -panel.height * .25f)
                    }) {
                        drawContext.size = panel
                        try {
                            paint()
                        } finally {
                            drawContext.size = sampleSize
                        }
                    }
                }
                readback = scope.launch {
                    val luminance = try {
                        layer.toImageBitmap().readPixels(pixels)
                        averageLuminance()
                    } catch (_: IllegalStateException) {
                        ensureActive()
                        null
                    }
                    ensureActive()
                    foreground = luminance?.let {
                        // Split near equal black/white contrast, with a gap to prevent flicker.
                        val boundary = when (foreground) {
                            Color.Black -> .16
                            Color.White -> .20
                            else -> .18
                        }
                        if (it >= boundary) Color.Black else Color.White
                    }
                }
            }
        }
    }

    private fun requestDeferredDraw() {
        if (deferredDraw?.isActive == true) return
        deferredDraw = scope.launch {
            readback?.join()
            delay((nextSampleAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
            drawRequest++
        }
    }

    private fun averageLuminance(): Double? {
        var total = 0.0
        var opaque = 0
        for (pixel in pixels) {
            if (pixel ushr 24 < 250) continue
            total += .2126 * linear[pixel ushr 16 and 255] +
                .7152 * linear[pixel ushr 8 and 255] + .0722 * linear[pixel and 255]
            opaque++
        }
        return if (opaque >= pixels.size * 3 / 4) total / opaque else null
    }

    private companion object {
        const val SAMPLE_SIZE = 8
        const val SAMPLE_INTERVAL_MS = 250L
        val linear = DoubleArray(256) { channel ->
            val srgb = channel / 255.0
            if (srgb <= .04045) srgb / 12.92 else ((srgb + .055) / 1.055).pow(2.4)
        }
    }
}
