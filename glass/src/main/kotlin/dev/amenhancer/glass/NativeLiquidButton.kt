package dev.amenhancer.glass

import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

class NativeButtonInput {
    internal var receiver: ((Int, Float, Float) -> Unit)? = null
    fun event(action: Int, x: Float, y: Float) { receiver?.invoke(action, x, y) }
}

/** Bottom-tabs panel material with LiquidButton motion; native content and touch ownership. */
@Composable
fun NativeLiquidButton(
    backdrop: Backdrop,
    input: NativeButtonInput,
    expansion: Float = 0f,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    transformContent: (Float, Float, Float, Float) -> Unit,
) {
    val containerColor = if (isSystemInDarkTheme()) Color(0xFF121212).copy(alpha = 0.4f)
        else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    DisposableEffect(input, highlight) {
        input.receiver = { action, x, y ->
            when (action) {
                MotionEvent.ACTION_DOWN -> highlight.pressAt(Offset(x, y))
                MotionEvent.ACTION_MOVE -> highlight.moveTo(Offset(x, y))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> highlight.releasePress()
            }
        }
        onDispose { input.receiver = null; transformContent(1f, 1f, 0f, 0f) }
    }
    Box(
        Modifier.fillMaxSize().drawBackdrop(
            backdrop = backdrop,
            shape = { if (expansion == 0f) Capsule() else RoundedCornerShape(lerp(GlassPolicy.MINI_HEIGHT_DP / 2f, 24f, expansion).dp) },
            effects = {
                vibrancy()
                blur(panelBlur.toPx())
                // Keep the capsule center outside refraction, including compact layouts.
                val refraction = minOf(24f.dp.toPx(), size.minDimension * 0.375f)
                lens(refraction, refraction)
            },
            onDrawSurface = { drawRect(containerColor) },
            layerBlock = {
                val width = size.width
                val height = size.height
                if (width > 0f && height > 0f) {
                    val progress = highlight.pressProgress
                    val scale = lerp(1f, 1f + 4f.dp.toPx() / height, progress)
                    val maxOffset = size.minDimension
                    val offset = highlight.offset
                    translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                    translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                    val maxDragScale = 4f.dp.toPx() / height
                    val angle = atan2(offset.y, offset.x)
                    scaleX = scale + maxDragScale * abs(cos(angle) * offset.x / size.maxDimension) * (width / height).fastCoerceAtMost(1f)
                    scaleY = scale + maxDragScale * abs(sin(angle) * offset.y / size.maxDimension) * (height / width).fastCoerceAtMost(1f)
                    transformContent(scaleX, scaleY, translationX, translationY)
                }
            },
        ).then(highlight.modifier),
    )
}
