package com.kyant.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.rememberRenderEffect
import com.kyant.backdrop.isRenderEffectSupported

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (radius <= 0f) return

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (radius > padding) {
            padding = radius
        }
    }

    val parent = renderEffect
    renderEffect = rememberRenderEffect(
        kind = "blur",
        parent = parent,
        parameters = BlurCacheParameters(radius, edgeTreatment),
    ) {
        BlurEffect(parent, radius, radius, edgeTreatment)
    }
}

private data class BlurCacheParameters(val radius: Float, val edgeTreatment: TileMode)
