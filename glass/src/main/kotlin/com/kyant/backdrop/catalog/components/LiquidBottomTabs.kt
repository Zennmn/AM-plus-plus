/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: optional host accent, tap-only native reselection, free thumb dragging with
 * multi-finger hand-over and a panel highlight pinned to the thumb's centre (the reference draws
 * the same light a row inset to the left).
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.4f)
        else Color(0xFF121212).copy(0.4f)

    val tabsBackdrop = rememberLayerBackdrop()

    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val offsetAnimation = remember { Animatable(0f) }
    val squeeze = with(density) { 4f.dp.toPx() }
    val panelInset = with(density) { 4f.dp.toPx() }

    // AM++: the squeeze nudge is read by both the panel layer and the highlight, so it lives
    // in one place and is evaluated against whichever width is being drawn.
    fun squeezeOffset(width: Float): Float {
        val fraction = (offsetAnimation.value / width).fastCoerceIn(-1f, 1f)
        return squeeze * fraction.sign * EaseOut.transform(abs(fraction))
    }

    // AM++: the light is part of the control rather than a spot beside it, so it is placed on
    // the glass thumb's centre and rides it wherever it goes. The thumb's geometry only exists
    // inside the box scope below, so the two are wired together through this coupling.
    val highlightAnchor = remember { PillCentre() }
    val freeDragBridge = remember { FreeDragBridge() }

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ -> Offset(highlightAnchor.x(), size.height / 2f) }
        )
    }

    BoxWithConstraints(
        modifier.then(freeDragBridge.modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val panelOffset by remember(density) {
            derivedStateOf { squeezeOffset(constraints.maxWidth.toFloat()) }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
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
                pressedScale = 78f / 56f,
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
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
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

        // AM++: hand the thumb's live geometry to the highlight built above the box scope.
        highlightAnchor.pillValue = { dampedDragAnimation.value }
        highlightAnchor.panelWidth = constraints.maxWidth.toFloat()
        highlightAnchor.panelInset = panelInset
        highlightAnchor.tabWidth = tabWidth
        highlightAnchor.isLtr = isLtr
        freeDragBridge.animation = dampedDragAnimation
        freeDragBridge.tabWidth = tabWidth
        freeDragBridge.panelWidth = constraints.maxWidth.toFloat()
        freeDragBridge.panelInset = panelInset
        freeDragBridge.panelOffset = panelOffset
        freeDragBridge.tabsCount = tabsCount
        freeDragBridge.isLtr = isLtr
        freeDragBridge.isTabEnabled = isTabEnabled
        freeDragBridge.onCanceled = {
            interactiveHighlight.releasePress()
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(panelBlur.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(panelHeight)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(panelBlur.toPx())
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
                    .then(interactiveHighlight.modifier)
                    .height(panelHeight - 8f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
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
                        val velocity = dampedDragAnimation.velocity / 10f
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
                .height(panelHeight - 8f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

/**
 * AM++: where the light sits, in the panel's own drawing space — the glass thumb's centre.
 *
 * The thumb's geometry only exists inside the box scope while the highlight is built above it,
 * so the two are wired together through this coupling. Only the thumb decides the position, so
 * the light cannot drift away from the control it belongs to. The squeeze cancels out on its
 * own: the panel and the thumb are nudged by the same amount.
 */
private class PillCentre {
    var pillValue: () -> Float = { 0f }
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var tabWidth: Float = 0f
    var isLtr: Boolean = true

    /** Mirrors the thumb's own translation: half a cell in from its inset at the start edge. */
    fun x(): Float {
        val fromStart = panelInset + (pillValue() + 0.5f) * tabWidth
        return if (isLtr) fromStart else panelWidth - fromStart
    }
}

/**
 * AM++: owns every touch on the bar. A pointer that starts on another tab takes the thumb over
 * immediately and drags it freely across the whole bar, while one that starts on the thumb's own
 * tab holds it in place. A second finger landing mid-drag is ignored outright and only inherits
 * the drag when it is still held as the finger in charge lifts.
 */
private class FreeDragBridge {
    var animation: DampedDragAnimation? = null
    var tabWidth: Float = 0f
    var panelWidth: Float = 0f
    var panelInset: Float = 0f
    var panelOffset: Float = 0f
    var tabsCount: Int = 0
    var isLtr: Boolean = true
    var isTabEnabled: (Int) -> Boolean = { true }
    var onCanceled: () -> Unit = {}

    val modifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
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
                    damped.release()
                }
            }
        }
    }

    private fun tabIndexAt(x: Float): Int? {
        if (tabWidth <= 0f) return null
        val contentX = logicalX(x)
        if (contentX < 0f || contentX >= tabWidth * tabsCount) return null
        val visualIndex = (contentX / tabWidth).toInt().fastCoerceIn(0, tabsCount - 1)
        return visualIndex
    }

    private fun valueAt(x: Float): Float =
        ((logicalX(x) / tabWidth) - 0.5f)
            .fastCoerceIn(0f, (tabsCount - 1).toFloat())

    private fun logicalX(x: Float): Float =
        if (isLtr) {
            x - panelOffset - panelInset
        } else {
            panelWidth - x + panelOffset - panelInset
        }
}
