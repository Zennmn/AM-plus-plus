package dev.amenhancer.glass

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.amenhancer.backdrop.traceBackdrop

/** Owns only module AndroidX objects. Never reads the host application's AndroidX owners. */
class GlassHostView(context: Context, bleedDp: Int = 32, traceName: String? = null) : FrameLayout(context) {
    private val measureTrace = traceName?.let { "$it/measure" }
    private val layoutTrace = traceName?.let { "$it/layout" }
    private val drawTrace = traceName?.let { "$it/recordView" }
    // Keep layout/hit bounds unchanged while giving Compose's RenderNode room for
    // the reference lens expansion and its shadow on every side.
    private val bleed = (bleedDp * resources.displayMetrics.density).roundToInt()
    private var owner = Owner()
    private var compositionScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    val compose = ComposeView(context).apply {
        clipChildren = false
        clipToPadding = false
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    /** Fade the expanded drawing bounds, including shadows, without clipping to the hit bounds. */
    var contentAlpha: Float
        get() = compose.alpha
        set(value) {
            compose.alpha = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        }

    init {
        clipChildren = false
        clipToPadding = false
        bindOwners()
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun content(block: @Composable () -> Unit) {
        compose.setContent {
            val padding = with(LocalDensity.current) { bleed.toDp() }
            Box(Modifier.fillMaxSize().padding(padding)) { block() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = traceBackdrop(measureTrace) {
        // Measure the composition once at the final bleed size. FrameLayout's first pass at
        // the unpadded size followed by a second pass made pressed controls alternate constraints.
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        compose.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth + bleed * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight + bleed * 2, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = traceBackdrop(layoutTrace) {
        compose.layout(-bleed, -bleed, width + bleed, height + bleed)
    }

    override fun dispatchDraw(canvas: Canvas) = traceBackdrop(drawTrace) { super.dispatchDraw(canvas) }

    private fun bindOwners() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    override fun onAttachedToWindow() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            owner = Owner()
            bindOwners()
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        // WindowRecomposer searches the host's content root, whose obfuscated AndroidX
        // owners cannot satisfy our module's types. Keep recomposition inside this island.
        val context = AndroidUiDispatcher.CurrentThread
        val ownRecomposer = Recomposer(context).also { recomposer = it }
        compositionScope = CoroutineScope(context + SupervisorJob()).also { scope ->
            scope.launch { ownRecomposer.runRecomposeAndApplyChanges() }
        }
        compose.setParentCompositionContext(ownRecomposer)
        super.onAttachedToWindow()
    }

    fun foreground(active: Boolean) {
        if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            owner.registry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
    }

    override fun onDetachedFromWindow() {
        compose.disposeComposition()
        recomposer?.cancel()
        compositionScope?.cancel()
        recomposer = null
        compositionScope = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}

/** Pixel bounds relative to [GlassOverlayHost]; kept Android-free for JVM hit-test specs. */
data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun contains(x: Float, y: Float): Boolean =
        width > 0 && height > 0 && x >= left && x < right && y >= top && y < bottom
}

data class GlassOverlayItem(
    val key: String,
    val bounds: OverlayBounds,
    val interactive: Boolean = true,
    val alpha: Float = 1f,
    val hitTest: ((Float, Float) -> Boolean)? = null,
    val content: @Composable () -> Unit,
)

internal fun glassOverlayHitTest(items: Collection<GlassOverlayItem>, x: Float, y: Float): Boolean =
    items.any { item ->
        item.interactive && item.alpha.isFinite() && item.alpha > 0.001f &&
            (item.hitTest?.invoke(x, y) ?: item.bounds.contains(x, y))
    }

/**
 * Shares one [GlassHostView] (and thus one ComposeView + Recomposer) across all floating glass
 * items in a window, passing through any touches outside interactive visible items.
 */
class GlassOverlayHost(
    context: Context,
    bleedDp: Int = 16,
    traceName: String? = null,
) : FrameLayout(context) {
    private val items = mutableStateMapOf<String, GlassOverlayItem>()
    private var activeTouch = false

    val host = GlassHostView(context, bleedDp, traceName).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        content {
            Box(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                items.values.forEach { item ->
                    key(item.key) {
                        val widthDp = with(density) { item.bounds.width.toDp() }
                        val heightDp = with(density) { item.bounds.height.toDp() }
                        Box(
                            Modifier
                                .offset { IntOffset(item.bounds.left, item.bounds.top) }
                                .size(widthDp, heightDp)
                                .graphicsLayer {
                                    alpha = if (item.alpha.isFinite()) item.alpha.coerceIn(0f, 1f) else 0f
                                },
                        ) {
                            item.content()
                        }
                    }
                }
            }
        }
    }

    val itemCount: Int get() = items.size

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun upsert(item: GlassOverlayItem) {
        items[item.key] = item
    }

    fun remove(key: String) {
        items.remove(key)
    }

    fun clear() {
        items.clear()
    }

    fun foreground(active: Boolean) = host.foreground(active)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = glassOverlayHitTest(items.values, ev.x, ev.y)
                if (!activeTouch) return false
            }
            MotionEvent.ACTION_MOVE -> if (!activeTouch) return false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!activeTouch) return false
                activeTouch = false
                return super.dispatchTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
