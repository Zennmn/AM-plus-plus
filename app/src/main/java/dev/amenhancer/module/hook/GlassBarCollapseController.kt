package dev.amenhancer.module.hook

import android.app.Activity
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import dev.amenhancer.glass.GlassBarCollapsePolicy
import dev.amenhancer.glass.GlassBarCollapseSpec
import dev.amenhancer.glass.GlassBarCollapseSprings
import dev.amenhancer.glass.GlassBarExpandSpec
import dev.amenhancer.module.model.EnhancementDefaults
import dev.amenhancer.module.model.ModuleSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class GlassBarCollapseAppearance(
    val collapseReboundScale: Float = EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT / 100f,
    val expandReboundScale: Float = EnhancementDefaults.GLASS_EXPAND_REBOUND_PERCENT / 100f,
    val miniReboundScale: Float = EnhancementDefaults.GLASS_MINI_REBOUND_PERCENT / 100f,
    val miniSinkBounceDp: Int = EnhancementDefaults.GLASS_MINI_SINK_BOUNCE_DP,
)

/** One animation owner for the navigation, native mini, content padding and sheet peek. */
@RequiresApi(33)
internal class GlassBarCollapseController(
    private val activity: Activity,
    private val host: ChromeHostBinding,
    private val inputActive: () -> Boolean,
    private val failure: (Throwable) -> Unit,
) : AutoCloseable {
    private val animation = Animatable(0f)
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private val density get() = activity.resources.displayMetrics.density
    private var inputDensity = density
    private val input = newInput()
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var targetCollapsed = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        runCatching { input.onScroll(enabled && inputActive()) }.onFailure(failure)
    }

    var enabled by mutableStateOf(false)
        private set
    var appearance by mutableStateOf(GlassBarCollapseAppearance())
        private set
    val progress: Float get() = animation.value
    val isRunning: Boolean get() = animation.isRunning
    // Keep every surface on the same curve while disabling the setting expands the row.
    val hasPresentation: Boolean get() = enabled || isRunning || progress != 0f

    private fun newInput(): GlassBarCollapseInput {
        val configuration = ViewConfiguration.get(activity)
        return GlassBarCollapseInput(host, configuration.scaledTouchSlop.toFloat(),
            configuration.scaledMinimumFlingVelocity.toFloat(), SCROLL_THRESHOLD_DP * density,
            isAnimating = { isRunning }, onIntent = ::request)
    }

    fun configure(settings: ModuleSettings, supported: Boolean) {
        if (closed) return
        if (observer != null) attach(activity.window.decorView.viewTreeObserver)
        appearance = GlassBarCollapseAppearance(
            ModuleSettings.normalizePhoneLiquidGlassCollapseReboundPercent(settings.phoneLiquidGlassCollapseReboundPercent) / 100f,
            ModuleSettings.normalizePhoneLiquidGlassExpandReboundPercent(settings.phoneLiquidGlassExpandReboundPercent) / 100f,
            ModuleSettings.normalizePhoneLiquidGlassMiniReboundPercent(settings.phoneLiquidGlassMiniReboundPercent) / 100f,
            ModuleSettings.normalizePhoneLiquidGlassMiniSinkBounceDp(settings.phoneLiquidGlassMiniSinkBounceDp),
        )
        if (inputDensity != density) {
            val configuration = ViewConfiguration.get(activity)
            input.configureMetrics(configuration.scaledTouchSlop.toFloat(),
                configuration.scaledMinimumFlingVelocity.toFloat(), SCROLL_THRESHOLD_DP * density)
            inputDensity = density
        }
        val wanted = supported && settings.phoneLiquidGlassCollapseEnabled
        if (wanted == enabled) return
        enabled = wanted
        input.reset()
        updateScrollListener()
        if (!enabled) expand()
    }

    fun attach(tree: ViewTreeObserver) {
        if (closed || !tree.isAlive || observer === tree) return
        observer?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        observer = tree
        updateScrollListener()
    }

    private fun updateScrollListener() {
        val tree = observer?.takeIf { it.isAlive } ?: return
        tree.removeOnScrollChangedListener(scrollListener)
        if (enabled) tree.addOnScrollChangedListener(scrollListener)
    }

    fun updateTargets(native: List<View>, compose: List<View>) = input.updateTargets(native, compose)

    fun observeTouch(event: MotionEvent, chromeOrigin: () -> Boolean) {
        input.onTouch(event, enabled && !closed && inputActive(), chromeOrigin)
    }

    fun resetInput() = input.reset()

    fun expand() {
        input.reset()
        request(false)
    }

    private fun request(collapsed: Boolean) {
        if (closed || (collapsed && !enabled) || targetCollapsed == collapsed) return
        targetCollapsed = collapsed
        val spec = if (collapsed) GlassBarCollapseSpec else GlassBarExpandSpec
        // Start immediately so reversing before the first frame cancels the old target too.
        // Animatable retains the velocity; repeating the same target never restarts a spring.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                animation.animateTo(if (collapsed) 1f else 0f, spec) { invalidateFrame() }
            } finally {
                // Re-enter native sheet reconciliation after the final frame or a cancellation.
                invalidateFrame()
            }
        }
    }

    private fun invalidateFrame() {
        if (Trace.isEnabled()) {
            Trace.setCounter("AM++/collapse/progress", (progress * 10000f).toLong())
            Trace.setCounter("AM++/collapse/running", if (isRunning) 1L else 0L)
        }
        ScrollCollapseBridge.update(if (closed) 0f else progress)
        if (!closed) activity.window.decorView.postInvalidateOnAnimation()
    }

    fun miniDropPx(expandedMiniHeightPx: Float): Float {
        if (!hasPresentation) return 0f
        val rowHeightPx = (GlassBarCollapsePolicy.ROW_DP * density).roundToInt().toFloat()
        return GlassBarCollapsePolicy.miniDrop(expandedMiniHeightPx, rowHeightPx, progress) +
            GlassBarCollapsePolicy.miniSinkOvershoot(appearance.miniSinkBounceDp * density,
                GlassBarCollapseSprings.CollapseOvershoot, progress)
    }

    fun miniMargins(expanded: IntArray, detachedSearch: Boolean, rtl: Boolean): IntArray {
        if (!hasPresentation) return expanded
        val policy = GlassBarCollapsePolicy
        val fraction = policy.sprungFraction(progress, appearance.miniReboundScale, appearance.miniReboundScale)
        val capsulePx = (policy.CAPSULE_DP * density).roundToInt().toFloat()
        val gapPx = (policy.GAP_DP * density).roundToInt().toFloat()
        val endReserve = if (detachedSearch) (policy.SEARCH_DP * density).roundToInt() + gapPx else 0f
        val start = policy.miniStartMargin(expanded[if (rtl) 1 else 0].toFloat(),
            capsulePx, gapPx, fraction).roundToInt().coerceAtLeast(0)
        val end = policy.miniEndMargin(expanded[if (rtl) 0 else 1].toFloat(), endReserve, fraction)
            .roundToInt().coerceAtLeast(0)
        return if (rtl) intArrayOf(end, start) else intArrayOf(start, end)
    }

    override fun close() {
        if (closed) return
        closed = true
        ScrollCollapseBridge.update(0f)
        observer?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        observer = null
        input.clear()
        scope.cancel()
    }

    private companion object {
        const val SCROLL_THRESHOLD_DP = 6f
    }
}
