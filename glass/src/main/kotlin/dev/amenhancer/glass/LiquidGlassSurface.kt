package dev.amenhancer.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import dev.amenhancer.backdrop.VibrantBlur
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Material quality tier for multi-surface windows.
 *
 * [Hero] keeps optical edge lens refraction and elastic press feedback for focal surfaces;
 * [Chrome] keeps cached vibrancy/blur and specular rim highlights without continuous per-item lens passes.
 */
enum class GlassTier { Hero, Chrome }

/** Pure optical/blur invariants shared by multi-surface Liquid Glass hosts and unit tests. */
object LiquidGlassMaterialPolicy {
    fun usesLens(tier: GlassTier): Boolean = tier == GlassTier.Hero

    fun maxRefractionPx(minDimensionPx: Float, maxDpPx: Float): Float =
        minOf(maxDpPx.coerceAtLeast(0f), (minDimensionPx * 0.375f).coerceAtLeast(0f))

    fun highlightWidthDp(tier: GlassTier): Float = if (tier == GlassTier.Hero) 0.75f else 0.5f

    fun highlightAlpha(isDark: Boolean, tier: GlassTier): Float = when (tier) {
        GlassTier.Hero -> if (isDark) 0.58f else 0.85f
        GlassTier.Chrome -> if (isDark) 0.42f else 0.68f
    }

    fun surfaceTintAlpha(isDark: Boolean, tier: GlassTier): Float = when (tier) {
        GlassTier.Hero -> if (isDark) 0.34f else 0.42f
        GlassTier.Chrome -> if (isDark) 0.44f else 0.52f
    }

    fun effectiveBlurDp(panelBlurDp: Float, tier: GlassTier): Float {
        val base = panelBlurDp.coerceAtLeast(0f)
        return if (tier == GlassTier.Hero) base else base * 0.85f
    }
}

/**
 * Shared iOS-inspired Liquid Glass surface reusing cached [VibrantBlur], [CompositingStrategy.Auto],
 * specular rim highlights, and optional elastic press physics.
 */
@Composable
fun LiquidGlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    tier: GlassTier = GlassTier.Chrome,
    shape: () -> Shape = { GlassCollapseShapes.capsule(true) },
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    tint: Color = Color.Unspecified,
    interactive: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    refractionScale: Float = 1f,
    lensDepthEffect: Boolean = false,
    lensChromaticAberration: Boolean = false,
    highlightAlphaOverride: Float? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val effectiveBlurDp = LiquidGlassMaterialPolicy.effectiveBlurDp(panelBlur.value, tier).dp
    val blurPx = with(LocalDensity.current) { effectiveBlurDp.toPx() }
    val material = remember(blurPx) { VibrantBlur(blurPx) }
    val baseSurfaceAlpha = LiquidGlassMaterialPolicy.surfaceTintAlpha(isDark, tier)
    val surfaceAlpha = if (tint == Color.Transparent) baseSurfaceAlpha * 0.22f else baseSurfaceAlpha
    val surfaceColor = (if (isDark) Color(0xFF141416) else Color(0xFFF9F9FB))
        .copy(alpha = surfaceAlpha)
    val rimAlpha = (highlightAlphaOverride ?: LiquidGlassMaterialPolicy.highlightAlpha(isDark, tier))
        .coerceIn(0f, 1f)
    val rimHighlight = Highlight(
        width = LiquidGlassMaterialPolicy.highlightWidthDp(tier).dp,
        alpha = rimAlpha,
    )
    val highlight = if (interactive) {
        val scope = rememberCoroutineScope()
        remember(scope) { InteractiveHighlight(scope) }
    } else null

    val glassModifier = modifier
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            highlight = { rimHighlight },
            shadow = null,
            clipCompositingStrategy = CompositingStrategy.Auto,
            effects = {
                material.applyTo(this)
                if (LiquidGlassMaterialPolicy.usesLens(tier)) {
                    val refraction = LiquidGlassMaterialPolicy.maxRefractionPx(size.minDimension, 24f.dp.toPx())
                    if (refraction > 0f) {
                        lens(
                            refractionHeight = refraction,
                            refractionAmount = refraction * refractionScale.coerceAtLeast(0.5f),
                            depthEffect = lensDepthEffect,
                            chromaticAberration = lensChromaticAberration,
                        )
                    }
                }
            },
            layerBlock = if (highlight != null) {
                {
                    val width = size.width
                    val height = size.height
                    if (width > 0f && height > 0f) {
                        val progress = highlight.pressProgress
                        val maxPull = 4f.dp.toPx() / height
                        val scale = lerp(1f, 1f + maxPull, progress)
                        val maxOffset = size.minDimension
                        val offset = highlight.offset
                        translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
                        translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)
                        val angle = atan2(offset.y, offset.x)
                        scaleX = scale + maxPull * abs(cos(angle) * offset.x / size.maxDimension) *
                            (width / height).fastCoerceAtMost(1f)
                        scaleY = scale + maxPull * abs(sin(angle) * offset.y / size.maxDimension) *
                            (height / width).fastCoerceAtMost(1f)
                    }
                }
            } else null,
            onDrawSurface = {
                drawRect(surfaceColor)
                if (tint.isSpecified) {
                    drawRect(tint.copy(alpha = (tint.alpha * 0.35f).coerceIn(0f, 1f)))
                }
            },
        )
        .let { base ->
            if (onClick != null) {
                base.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            } else base
        }
        .let { base ->
            if (highlight != null) base.then(highlight.modifier).then(highlight.gestureModifier)
            else base
        }

    Box(modifier = glassModifier, contentAlignment = contentAlignment, content = content)
}
