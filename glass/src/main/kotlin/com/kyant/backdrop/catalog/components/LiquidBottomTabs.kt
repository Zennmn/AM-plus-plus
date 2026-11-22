/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: optional host accent, tap-only native reselection, free thumb dragging with
 * multi-finger hand-over, a panel highlight pinned to the thumb's centre (the reference draws
 * the same light a row inset to the left), and a scroll-collapse progress that animates the
 * panel's width/height and swaps the tab row for a compact face. Every collapse parameter
 * defaults to the expanded bar. Collapse hosts retain material modifiers and fixed effects,
 * read continuous geometry during layout/drawing, reserve expanded effect-layer bounds,
 * avoid redundant offscreen clipping, suspend the unused refraction source layout, and
 * accept an optional refraction row that can reuse the visible labels' text layouts.
 * The panel can expose its recorded material for adaptive tab foregrounds.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.amenhancer.backdrop.VibrantBlur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.collapseSize
import dev.amenhancer.glass.collapseAlpha
import dev.amenhancer.glass.GlassCollapseShapes
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    accentOverride: Color = Color.Unspecified,
    onSelectedTabClick: ((Int) -> Unit)? = null,
    isTabEnabled: (Int) -> Boolean = { true },
    panelHeight: androidx.compose.ui.unit.Dp = 64f.dp,
    panelBlur: androidx.compose.ui.unit.Dp = GlassPolicy.PANEL_BLUR_DP.dp,
    leadingWidth: androidx.compose.ui.unit.Dp = 0f.dp,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    /** AM++ scroll collapse: 0 = expanded bar, 1 = collapsed capsule. */
    collapseProgress: () -> Float = { 0f },
    /** AM++: drawn in place of the tab row once the capsule has withdrawn. */
    compactContent: (@Composable () -> Unit)? = null,
    /** AM++: the collapsed capsule is the only way back, so it must accept the tap. */
    onExpandRequest: (() -> Unit)? = null,
    /**
     * AM++: user-set rebound amplitudes, as fractions of each spring's own overshoot. Both collapsed
     * side buttons share this pair so they cannot animate differently. [collapseReboundScale] is the
     * collapse direction (the visible Q-bounce); [expandReboundScale] is the return direction, where
     * the overshoot makes each button grow back **towards the mini player**.
     */
    collapseReboundScale: Float = 1f,
    expandReboundScale: Float = 1f,
    /** Only collapse-capable hosts reuse fixed material effects and suspend the hidden source. */
    optimizeCollapseRendering: Boolean = false,
    onSampleMaterial: (DrawScope.(DrawScope.() -> Unit) -> Unit)? = null,
    sampleCompactForeground: Boolean = false,
    /** AM++: optional equivalent content for the semantically hidden refraction source only. */
    refractionContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    // AM++: preserve Apple's reselect action without changing drag/animation behavior.
    val selectedTabClick = androidx.compose.runtime.rememberUpdatedState(onSelectedTabClick)
    val isLightTheme = !isSystemInDarkTheme()
    // AM++: allow the host accent without changing material or animation.
    val accentColor = if (accentOverride != Color.Unspecified) accentOverride else
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.2f)
        else Color(0xFF121212).copy(0.2f)

    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val material = if (optimizeCollapseRendering) {
        val radiusPx = with(density) { panelBlur.toPx() }
        remember(radiusPx) { VibrantBlur(radiusPx) }
    } else null
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(0f) }
    val squeeze = with(density) { 4f.dp.toPx() }
    val panelInset = with(density) { 4f.dp.toPx() }

    // AM++: the squeeze nudge is read by both the panel layer and the highlight, so it lives
    // in one place and is evaluated against whichever width is being drawn.
    fun squeezeOffset(width: Float): Float {
        if (!width.isFinite() || width <= 0f) return 0f
        val fraction = (offsetAnimation.value / width).fastCoerceIn(-1f, 1f)
        return squeeze * fraction.sign * EaseOut.transform(abs(fraction))
    }

    // AM++: the light is part of the control rather than a spot beside it, so it is placed on
    // the glass thumb's centre and rides it wherever it goes. The thumb's geometry only exists
    // inside the box scope below, so the two are wired together through this coupling.
    val highlightAnchor = remember { PillCentre() }
    val freeDragBridge = remember { FreeDragBridge() }

    val interactiveHighlight = remember(animationScope, optimizeCollapseRendering) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ -> Offset(highlightAnchor.x(), size.height / 2f) },
            // Clipped to the capsule the glass draws, so the press glow stays round on the
            // withdrawn 43dp circle.
            shape = GlassCollapseShapes.capsule(optimizeCollapseRendering),
        )
    }

    /**
     * AM++: whether the tab-drag gesture may exist **at all**, rather than merely being inactive.
     *
     * `freeDragBridge.modifier` is a `pointerInput` on the fillMaxWidth row box, and Compose
     * reports a pointer that lands on any pointer-input node as handled even when that node does
     * not consume it. The collapsed mini player sinks into that band, so the node has to be
     * **absent** while the row owns no drag. The gate is a `derivedStateOf` over the progress
     * lambda, so this scope recomposes when the gate flips and not on every animation frame.
     */
    val collapseProgressState = rememberUpdatedState(collapseProgress)
    val dragEnabled by remember {
        derivedStateOf {
            dev.amenhancer.glass.GlassBarCollapsePolicy.dragEnabled(collapseProgressState.value())
        }
    }

    BoxWithConstraints(
        // AM++ scroll collapse: the box is pinned to the *expanded* bar height so the panel can
        // withdraw inside it, and `constraints.maxWidth` stays the expanded row width the panel
        // interpolates away from. The alignment stays `CenterStart` as in the reference.
        //
        // AM++: the drag gesture exists only while the row owns it; its full-width hit region
        // would otherwise cover the collapsed mini player.
        modifier.then(if (dragEnabled) freeDragBridge.modifier else Modifier)
            .fillMaxWidth().height(panelHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        val leadingPx = with(density) { if (leadingContent == null) 0f else leadingWidth.toPx() }
        // AM++ scroll collapse. The unclamped value drives geometry so the expand spring's
        // overshoot stays visible; the clamped value drives alpha and input gating, where a
        // value outside [0, 1] would be invalid.
        val expandedWidthPx = constraints.maxWidth.toFloat()
        val expandedPanelHeightPx = with(density) { panelHeight.toPx() }
        val controlPx = with(density) { dev.amenhancer.glass.GlassBarCollapsePolicy.CONTROL_DP.dp.toPx() }
        // The panel's width, height and bottom-anchored placement come from the shared entry point,
        // the one the host's native touch hit test calls, so the drawn capsule and the region that
        // answers for it cannot drift apart.
        fun frame(progress: Float): CollapseFrame {
            val controls = dev.amenhancer.glass.GlassBarCollapseLayout.controls(
                rowWidthPx = expandedWidthPx,
                rowHeightPx = expandedPanelHeightPx,
                density = density.density,
                tabsCount = tabsCount,
                progress = progress,
                collapseReboundScale = collapseReboundScale,
                expandReboundScale = expandReboundScale,
                leadingWidthPx = leadingPx,
            )
            // Cells follow clamped travel; the material alone carries the rebound.
            val restingWidthPx = dev.amenhancer.glass.GlassBarCollapsePolicy.lerp(
                expandedWidthPx, controlPx,
                dev.amenhancer.glass.GlassBarCollapsePolicy.progress(progress),
            )
            val collapseDropPx = controls.top - (expandedPanelHeightPx - controls.height) / 2f
            val expandedContentAlpha = dev.amenhancer.glass.GlassBarCollapsePolicy.expandedContentAlpha(progress)
            val compactContentAlpha = dev.amenhancer.glass.GlassBarCollapsePolicy.compactContentAlpha(progress)
            val geometry = dev.amenhancer.glass.GlassTabGeometry(restingWidthPx, panelInset, leadingPx, tabsCount)
            return CollapseFrame(progress, controls, restingWidthPx, geometry, collapseDropPx,
                expandedContentAlpha, compactContentAlpha)
        }
        val readFrame: () -> CollapseFrame = if (optimizeCollapseRendering) {
            val state = remember(expandedWidthPx, expandedPanelHeightPx, density, tabsCount,
                leadingPx, collapseReboundScale, expandReboundScale) {
                derivedStateOf { frame(collapseProgressState.value()) }
            }
            remember(state) { { state.value } }
        } else {
            val current = frame(collapseProgress())
            remember(current) { { current } }
        }
        val gates by remember(readFrame) {
            derivedStateOf {
                val current = readFrame()
                CollapseGates(current.expandedAlpha > 0.001f, current.compactAlpha > 0.001f,
                    dev.amenhancer.glass.GlassBarCollapsePolicy.compactInteractive(current.progress),
                    current.progress != 0f && current.progress != 1f)
            }
        }
        // Reserve the expanded material bounds, including the policy's maximum rebound room.
        // Stable endpoints return to their actual allocation; only the transition reserves space.
        // Layout, lens uniforms and hit regions always use the current geometry.
        val reserveLayer = optimizeCollapseRendering && gates.reserveLayer
        val panelLayerSize = if (reserveLayer) IntSize(
            (expandedWidthPx + dev.amenhancer.glass.GlassBarCollapsePolicy.GAP_DP * density.density).fastRoundToInt(),
            expandedPanelHeightPx.fastRoundToInt(),
        ) else IntSize.Zero
        val refractionLayerSize = if (reserveLayer) IntSize(
            panelLayerSize.width,
            (expandedPanelHeightPx - 8f * density.density).fastRoundToInt().coerceAtLeast(0),
        ) else IntSize.Zero
        val selectionLayerSize = if (reserveLayer) IntSize(
            dev.amenhancer.glass.GlassTabGeometry(expandedWidthPx, panelInset, leadingPx, tabsCount)
                .tabWidth.fastRoundToInt().coerceAtLeast(1),
            refractionLayerSize.height,
        ) else IntSize.Zero

        val panelOffsetState = remember(density, readFrame) {
            derivedStateOf { squeezeOffset(readFrame().restingWidth) }
        }
        val readPanelOffset = remember(panelOffsetState) { { panelOffsetState.value } }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        // The first composition can be the 1px warm-up host. Gestures must use the current
        // measured slot, never the width captured when the animation object was constructed.
        val liveGeometry = rememberUpdatedState(readFrame)
        val liveIsLtr = rememberUpdatedState(isLtr)
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                onTap = { selectedTabClick.value?.invoke(currentIndex) },
                pressedScale = 1.5f,
                pressedScaleY = 1.45f,
                // Only the thumb's own gesture may light the highlight. A tap on another tab
                // selects that tab without lighting the thumb while it settles there.
                onDragStarted = {
                    interactiveHighlight.pressAt(Offset.Zero)
                },
                onDragStopped = {
                    interactiveHighlight.releasePress()
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(liveGeometry.value().geometry.draggedIndex(targetValue, dragAmount.x, liveIsLtr.value))
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }
        LaunchedEffect(selectedTabIndex()) {
            // Drawer/back navigation changes the native selected ID without a glass gesture.
            currentIndex = selectedTabIndex()
            if (dampedDragAnimation.targetValue != currentIndex.toFloat()) {
                dampedDragAnimation.animateToValue(currentIndex.toFloat())
            }
        }

        // AM++: hand the thumb's live geometry to the highlight built above the box scope.
        highlightAnchor.pillValue = { dampedDragAnimation.value }
        highlightAnchor.panelInset = panelInset + leadingPx
        highlightAnchor.isLtr = isLtr
        // The light must migrate to the panel's own centre as the bar withdraws. Expanded it
        // belongs on the selected tab's cell, which is what `x()` computes; collapsed the panel is a
        // 43dp circle and that cell centre lands near the circle's *left edge*, so the press glow
        // appeared offset from the button. Interpolating on the same progress the geometry uses
        // keeps the light on the glass at every point of the travel.
        highlightAnchor.frame = if (optimizeCollapseRendering) readFrame else null
        freeDragBridge.animation = dampedDragAnimation
        freeDragBridge.panelInset = panelInset + leadingPx
        freeDragBridge.frame = if (optimizeCollapseRendering) readFrame else null
        freeDragBridge.offset = if (optimizeCollapseRendering) readPanelOffset else null
        if (!optimizeCollapseRendering) {
            val current = readFrame()
            val collapseRaw = current.progress
            val restingWidthPx = current.restingWidth
            val tabWidth = current.geometry.tabWidth
            val panelOffset = readPanelOffset()
            highlightAnchor.panelWidth = restingWidthPx
            highlightAnchor.tabWidth = tabWidth
            highlightAnchor.collapse = collapseRaw
            freeDragBridge.tabWidth = tabWidth
            freeDragBridge.panelWidth = restingWidthPx
            freeDragBridge.panelOffset = panelOffset
        }
        freeDragBridge.tabsCount = tabsCount
        freeDragBridge.isLtr = isLtr
        freeDragBridge.isTabEnabled = isTabEnabled
        // A withdrawn capsule must not keep owning the row's touches.
        freeDragBridge.enabled = dragEnabled
        freeDragBridge.onCanceled = {
            interactiveHighlight.releasePress()
        }

        val expandedVisible = gates.expandedVisible
        val sampleMaterial = onSampleMaterial.takeIf { expandedVisible || (sampleCompactForeground && gates.compactVisible) }
        val panelMaterial = if (optimizeCollapseRendering) {
            remember(backdrop, panelBlur, containerColor, dampedDragAnimation, material, panelLayerSize, sampleMaterial) {
                glassPanelMaterial(backdrop, panelBlur, containerColor, dampedDragAnimation, material,
                    panelLayerSize, sampleMaterial)
            }
        } else glassPanelMaterial(backdrop, panelBlur, containerColor, dampedDragAnimation, null,
            onSampleMaterial = sampleMaterial)
        val refractionMaterial = if (optimizeCollapseRendering) {
            remember(backdrop, panelBlur, containerColor, dampedDragAnimation, material, refractionLayerSize) {
                glassRefractionMaterial(backdrop, panelBlur, containerColor, dampedDragAnimation, material, refractionLayerSize)
            }
        } else glassRefractionMaterial(backdrop, panelBlur, containerColor, dampedDragAnimation, null)
        // Keep the source nodes/layers alive, but do not measure or place their subtree when
        // the selection droplet has no need for it. Expanding places it again before drawing.
        val expandedLayout = if (optimizeCollapseRendering) Modifier.layout { measurable, constraints ->
            if (expandedVisible) {
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            } else layout(0, 0) {}
        } else Modifier

        Row(
            Modifier
                .graphicsLayer {
                    // The capsule is anchored to the row's start edge and only its end edge travels,
                    // so the whole expand rebound shows up on the side facing the mini player.
                    translationX = readPanelOffset()
                    translationY = readFrame().drop
                }
                .then(panelMaterial)
                .then(interactiveHighlight.modifier)
                // Required sizing preserves the outward rebound beyond incoming constraints.
                // The shared policy bounds it to the gap; GlassHostView provides drawing bleed.
                .collapseSize(optimizeCollapseRendering, density, required = true,
                    heightPx = { readFrame().controls.height })
                .collapseSize(optimizeCollapseRendering, density, required = true,
                    widthPx = { readFrame().controls.capsuleWidth })
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only the cells fade, on the clamped resting width minus the panel's 4dp insets.
            // They must not stretch with the backplate's rebound.
            Row(
                Modifier.then(expandedLayout)
                    .collapseSize(optimizeCollapseRendering, density, widthPx = { readFrame().restingWidth - 8f * density.density })
                    .fillMaxHeight().collapseAlpha(optimizeCollapseRendering) { readFrame().expandedAlpha },
                verticalAlignment = Alignment.CenterVertically,
                content = { leadingContent?.invoke(this); content() }
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .then(expandedLayout)
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = readPanelOffset()
                        translationY = readFrame().drop
                    }
                    .then(refractionMaterial)
                    .then(interactiveHighlight.modifier)
                    // `required*` to match the backplate above: this layer is invisible but is what
                    // the droplet refracts, so a coerced size would refract a strip that no longer
                    // lines up with the glass.
                    .collapseSize(optimizeCollapseRendering, density, required = true,
                        heightPx = { readFrame().controls.height - 8f * density.density })
                    .collapseSize(optimizeCollapseRendering, density, required = true,
                        widthPx = { readFrame().controls.capsuleWidth })
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The droplet refracts this layer, so its tabs narrow and fade with the visible row
                // and stay pinned to the resting width.
                Row(
                    Modifier.collapseSize(optimizeCollapseRendering, density,
                        widthPx = { readFrame().restingWidth - 8f * density.density })
                        .fillMaxHeight().collapseAlpha(optimizeCollapseRendering) { readFrame().expandedAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                    content = { leadingContent?.invoke(this); (refractionContent ?: content).invoke(this) }
                )
            }
        }

        // AM++ scroll collapse: the droplet is the tab row's selection indicator, so it withdraws
        // with that row. It is **omitted** rather than faded because `Modifier.alpha` is an inner
        // layer here: the droplet's own surface, highlight and shadow are drawn by `drawBackdrop`
        // and would escape the alpha, leaving a sliver on the capsule.
        if (gates.expandedVisible) {
            val selectionBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
            val thumbMaterial = if (optimizeCollapseRendering) {
                remember(selectionBackdrop, dampedDragAnimation, isLightTheme, selectionLayerSize) {
                    glassSelectionMaterial(selectionBackdrop, dampedDragAnimation, isLightTheme, selectionLayerSize,
                        collapseOutline = true)
                }
            } else glassSelectionMaterial(selectionBackdrop, dampedDragAnimation, isLightTheme)
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        val current = readFrame()
                        val tabWidth = current.geometry.tabWidth
                        val panelOffset = readPanelOffset()
                        translationX =
                            if (leadingPx == 0f) {
                                if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                                else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                            } else current.geometry.thumbOffset(dampedDragAnimation.value, isLtr) + panelOffset
                        translationY = current.drop
                    }
                    .then(thumbMaterial)
                    // The thumb's width must follow the animated panel: `fillMaxWidth(1f / tabsCount)`
                    // measures against the full box, so it would keep the droplet at its expanded
                    // width while the capsule narrowed. `tabWidth` is the same value whenever no
                    // leading action exists, because `tabWidth = (panelWidth - 2 * inset) / count`
                    // and the 4dp inset is applied on both sides here — exact at progress 0.
                    .collapseSize(optimizeCollapseRendering, density,
                        heightPx = { readFrame().controls.height - 8f * density.density })
                    .collapseSize(optimizeCollapseRendering, density,
                        widthPx = { readFrame().geometry.tabWidth.coerceAtLeast(density.density) })
            )
        }

        // AM++ scroll collapse: the withdrawn capsule's replacement face. It carries the
        // selected tab's glyph and is the only way back to the expanded bar, so it owns the
        // tap once it is the visible affordance. Drawn above the fading tab row.
        if (compactContent != null && gates.compactVisible) {
            // Keep the compact face concentric with the backplate and gate its capsule indication.
            val interactive = onExpandRequest != null &&
                gates.compactInteractive
            Box(
                Modifier
                    // Share the backplate's CenterStart alignment and bottom-anchoring translation.
                    .collapseSize(optimizeCollapseRendering, density,
                        widthPx = { readFrame().controls.capsuleWidth })
                    .collapseSize(optimizeCollapseRendering, density,
                        heightPx = { readFrame().controls.height })
                    .collapseAlpha(optimizeCollapseRendering) { readFrame().compactAlpha }
                    // The same drop the glass circle gets, so the glyph stays locked to it through
                    // the shrink and the rebound.
                    .graphicsLayer { translationY = readFrame().drop }
                    // Clip before clickable so the indication shares the material's capsule.
                    .clip(GlassCollapseShapes.capsule(optimizeCollapseRendering))
                    .then(
                        if (interactive) Modifier.clickable(onClick = onExpandRequest!!) else Modifier
                    ),
                contentAlignment = Alignment.Center,
                content = { compactContent() }
            )
        }
    }
}

// Material modifiers are independent of animated geometry; only opted-in hosts retain them.
private fun glassPanelMaterial(
    backdrop: Backdrop, panelBlur: androidx.compose.ui.unit.Dp, containerColor: Color,
    dampedDragAnimation: DampedDragAnimation, material: VibrantBlur?,
    minLayerSize: IntSize = IntSize.Zero,
    onSampleMaterial: (DrawScope.(DrawScope.() -> Unit) -> Unit)? = null,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    minLayerSize = material?.paddedSize(minLayerSize) ?: minLayerSize,
    clipCompositingStrategy = if (minLayerSize != IntSize.Zero) CompositingStrategy.Auto else CompositingStrategy.Offscreen,
    shape = { GlassCollapseShapes.capsule(material != null) },
    effects = {
        if (material == null) {
            vibrancy()
            blur(panelBlur.toPx())
        } else material.applyTo(this)
        lens(24f.dp.toPx(), 24f.dp.toPx())
    },
    layerBlock = {
        val progress = dampedDragAnimation.pressProgress
        val scale = dev.amenhancer.glass.GlassPressGeometry.panelScale(size.width, 8f.dp.toPx(), progress)
        scaleX = scale
        scaleY = scale
    },
    onDrawSurface = { drawRect(containerColor) },
    onSampleMaterial = onSampleMaterial,
)

private fun glassRefractionMaterial(
    backdrop: Backdrop, panelBlur: androidx.compose.ui.unit.Dp, containerColor: Color,
    dampedDragAnimation: DampedDragAnimation, material: VibrantBlur?,
    minLayerSize: IntSize = IntSize.Zero,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    minLayerSize = material?.paddedSize(minLayerSize) ?: minLayerSize,
    clipCompositingStrategy = if (minLayerSize != IntSize.Zero) CompositingStrategy.Auto else CompositingStrategy.Offscreen,
    shape = { GlassCollapseShapes.capsule(material != null) },
    effects = {
        val progress = dampedDragAnimation.pressProgress
        if (material == null) {
            vibrancy()
            blur(panelBlur.toPx())
        } else material.applyTo(this)
        lens(
            24f.dp.toPx() * progress,
            24f.dp.toPx() * progress
        )
    },
    highlight = {
        val progress = dampedDragAnimation.pressProgress
        Highlight.Default.copy(alpha = progress)
    },
    onDrawSurface = { drawRect(containerColor) }
)

private fun glassSelectionMaterial(
    backdrop: Backdrop, dampedDragAnimation: DampedDragAnimation, isLightTheme: Boolean,
    minLayerSize: IntSize = IntSize.Zero,
    collapseOutline: Boolean = false,
): Modifier = Modifier.drawBackdrop(
    backdrop = backdrop,
    minLayerSize = minLayerSize,
    clipCompositingStrategy = if (minLayerSize != IntSize.Zero) CompositingStrategy.Auto else CompositingStrategy.Offscreen,
    shape = { GlassCollapseShapes.capsule(collapseOutline) },
    effects = {
        val progress = dampedDragAnimation.pressProgress
        lens(
            10f.dp.toPx() * progress,
            14f.dp.toPx() * progress,
            chromaticAberration = true
        )
    },
    highlight = {
        val progress = dampedDragAnimation.pressProgress
        Highlight.Default.copy(alpha = progress)
    },
    shadow = {
        val progress = dampedDragAnimation.pressProgress
        Shadow(alpha = progress)
    },
    innerShadow = {
        val progress = dampedDragAnimation.pressProgress
        InnerShadow(
            radius = 8f.dp * progress,
            alpha = progress
        )
    },
    layerBlock = {
        scaleX = dampedDragAnimation.scaleX
        scaleY = dampedDragAnimation.scaleY
        val velocity = dampedDragAnimation.velocity / 10f * dampedDragAnimation.pressProgress
        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
    },
    onDrawSurface = {
        val progress = dampedDragAnimation.pressProgress
        drawRect(
            if (isLightTheme) Color.Black.copy(0.1f)
            else Color.White.copy(0.1f),
            alpha = 1f - progress
        )
        drawRect(Color.Black.copy(alpha = 0.03f * progress))
    }
)

private data class CollapseFrame(
    val progress: Float,
    val controls: dev.amenhancer.glass.GlassBarControls,
    val restingWidth: Float,
    val geometry: dev.amenhancer.glass.GlassTabGeometry,
    val drop: Float,
    val expandedAlpha: Float,
    val compactAlpha: Float,
)

private data class CollapseGates(
    val expandedVisible: Boolean,
    val compactVisible: Boolean,
    val compactInteractive: Boolean,
    val reserveLayer: Boolean,
)

/** The pressed light reads the same current frame as the panel and its selected thumb. */
private class PillCentre {
    var frame: (() -> CollapseFrame)? = null
    var pillValue: () -> Float = { 0f }
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var tabWidth: Float = 0f
    var isLtr: Boolean = true

    /** AM++: 0 = the light sits on the selected tab's cell, 1 = on the panel's own centre. */
    var collapse: Float = 0f

    /**
     * The light's x within the panel.
     *
     * Expanded this is the selected tab's cell centre, which is where the glass thumb is and where
     * the reference draws the light. Collapsed the panel is the 43dp circle and the tab row has
     * faded away entirely, so there is no cell to sit on — and the cell centre would land near the
     * circle's left edge, which is what made the pressed glow look offset from the button. Both
     * ends are blended on the collapse progress so the light tracks the glass through the travel.
     *
     * The result is clamped into the panel, so a stale `tabWidth` during a menu swap cannot push the
     * light outside the glass it belongs to.
     */
    fun x(): Float {
        val current = frame?.invoke()
        val panelWidth = current?.restingWidth ?: this.panelWidth
        val tabWidth = current?.geometry?.tabWidth ?: this.tabWidth
        val t = (current?.progress ?: collapse).coerceIn(0f, 1f)
        val onTab = panelInset + (pillValue() + 0.5f) * tabWidth
        val fromStart = if (isLtr) onTab else panelWidth - onTab
        val centred = panelWidth / 2f
        val blended = fromStart + (centred - fromStart) * t
        return if (panelWidth > 0f) blended.coerceIn(0f, panelWidth) else blended
    }
}

/**
 * AM++: owns every touch on the bar. A pointer that starts on another tab takes the thumb over
 * immediately and drags it freely across the whole bar, while one that starts on the thumb's own
 * tab holds it in place. A second finger landing mid-drag is ignored outright and only inherits
 * the drag when it is still held as the finger in charge lifts.
 */
private class FreeDragBridge {
    var frame: (() -> CollapseFrame)? = null
    var offset: (() -> Float)? = null
    var animation: DampedDragAnimation? = null
    var tabWidth: Float = 0f
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var panelOffset: Float = 0f
    var tabsCount: Int = 0
    var isLtr: Boolean = true
    /** AM++: false once the capsule has withdrawn, so it cannot keep owning the row. */
    var enabled: Boolean = true
    var isTabEnabled: (Int) -> Boolean = { true }
    var onCanceled: () -> Unit = {}

    val modifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            if (!enabled) return@awaitEachGesture
            val targetIndex = tabIndexAt(down.position.x)
            val damped = animation
            val originalIndex = damped?.targetValue
                ?.fastRoundToInt()
                ?.fastCoerceIn(0, tabsCount - 1)

            if (
                damped != null &&
                targetIndex != null &&
                originalIndex != null &&
                isTabEnabled(targetIndex)
            ) {
                // A press on the tab the thumb already occupies is a plain hold, so the thumb stays
                // where it is and only follows the finger's motion. A press on any other tab is
                // taken over, and the thumb rides across to that finger instead.
                val grabsInPlace = targetIndex == originalIndex
                // Take ownership at the initial pass so the tab's ordinary clickable cannot
                // finish this sequence before the thumb's own gesture does.
                down.consume()
                damped.onDragStarted.invoke(damped, down.position)
                damped.press()
                if (!grabsInPlace) {
                    damped.updateValue(valueAt(down.position.x))
                }

                var ownerId = down.id
                var ownerPosition = down.position
                var traveled = 0f
                // Handing the drag over moves the thumb, so only a hold that never changed hands
                // may still count as a plain tap on it.
                var tapEligible = grabsInPlace
                // Other fingers that land during this drag, oldest first. They are swallowed while
                // the owner holds, and the newest one still held inherits the drag when it lifts.
                val held = mutableListOf<Pair<PointerId, Offset>>()
                var canceled = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)

                        for (change in event.changes) {
                            if (change.id == ownerId) continue
                            if (!change.pressed && !change.previousPressed) continue
                            change.consume()
                            when {
                                !change.previousPressed -> {
                                    // A press that lands mid-drag is only a hand-over candidate, and
                                    // only when it targets a tab that may be selected.
                                    if (tabIndexAt(change.position.x)?.let { isTabEnabled(it) } == true) {
                                        held.removeAll { it.first == change.id }
                                        held.add(change.id to change.position)
                                    }
                                }

                                change.pressed -> {
                                    val index = held.indexOfFirst { it.first == change.id }
                                    if (index >= 0) {
                                        held[index] = change.id to change.position
                                    }
                                }

                                else -> held.removeAll { it.first == change.id }
                            }
                        }

                        val change = event.changes.fastFirstOrNull { it.id == ownerId }
                        if (change == null) {
                            canceled = true
                            break
                        }
                        change.consume()
                        if (change.changedToUpIgnoreConsumed()) {
                            // Hand the drag over to the newest finger still down. One that already
                            // lifted leaves nothing behind, so the drag simply ends where it is.
                            val next = held.lastOrNull() ?: break
                            held.removeAt(held.lastIndex)
                            ownerId = next.first
                            ownerPosition = next.second
                            tapEligible = false
                            damped.updateValue(valueAt(ownerPosition.x))
                            continue
                        }
                        val dragAmount = change.position - ownerPosition
                        if (dragAmount != Offset.Zero) {
                            if (tapEligible) {
                                traveled += dragAmount.getDistance()
                            }
                            damped.onDrag.invoke(damped, IntSize.Zero, dragAmount)
                        }
                        ownerPosition = change.position
                    }

                    if (canceled) {
                        onCanceled()
                        damped.animateToValue(originalIndex.toFloat())
                    } else {
                        damped.onDragStopped.invoke(damped)
                        // The thumb's own gesture reports a tap the same way: a hold that never moved
                        // beyond the touch slop is the finger pressing and lifting in place.
                        if (tapEligible && traveled <= viewConfiguration.touchSlop) {
                            damped.onTap?.invoke()
                        }
                    }
                } finally {
                    // Pointer cancellation and menu disposal must not leave a held lens behind.
                    onCanceled()
                    damped.release()
                }
            }
        }
    }

    private fun tabIndexAt(x: Float): Int? {
        val tabWidth = frame?.invoke()?.geometry?.tabWidth ?: this.tabWidth
        if (tabWidth <= 0f) return null
        val contentX = logicalX(x)
        if (contentX < 0f || contentX >= tabWidth * tabsCount) return null
        val visualIndex = (contentX / tabWidth).toInt().fastCoerceIn(0, tabsCount - 1)
        return visualIndex
    }

    private fun valueAt(x: Float): Float =
        ((logicalX(x) / (frame?.invoke()?.geometry?.tabWidth ?: tabWidth)) - 0.5f)
            .fastCoerceIn(0f, (tabsCount - 1).toFloat())

    private fun logicalX(x: Float): Float {
        val panelWidth = frame?.invoke()?.restingWidth ?: this.panelWidth
        val panelOffset = offset?.invoke() ?: this.panelOffset
        return if (isLtr) {
            x - panelOffset - panelInset
        } else {
            panelWidth - x + panelOffset - panelInset
        }
    }
}
