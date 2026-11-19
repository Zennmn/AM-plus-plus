package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Process-local bridge publishing the active bar scroll-collapse progress (0f expanded .. 1f collapsed). */
object ScrollCollapseBridge {
    private val listeners = CopyOnWriteArrayList<(Float) -> Unit>()

    @Volatile
    var progress: Float = 0f
        private set

    fun update(value: Float) {
        val clamped = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (clamped == progress) return
        val snapEdge = clamped == 0f || clamped == 1f
        if (!snapEdge && abs(clamped - progress) < 0.0005f) return
        progress = clamped
        for (listener in listeners) {
            runCatching { listener(clamped) }
        }
    }

    fun addListener(listener: (Float) -> Unit): HostSubscription {
        listeners.add(listener)
        return HostSubscription { listeners.remove(listener) }
    }
}

/** Multi-surface Liquid Glass semantic roles shared across Apple Music and Classical hosts. */
enum class GlassSurfaceRole {
    TOP_APP_BAR,
    FLOATING_SEARCH_BAR,
    FLOATING_ACTION_BUTTON,
    PLAYER_ACTION_BUTTON,
    ACTION_SHEET_DIALOG,
    CONTEXT_POPUP_MENU,
    CARD_OVERLAY,
}

data class GlassSurfaceAnchor(
    val role: GlassSurfaceRole,
    val container: ViewGroup,
    val backdropSource: View,
    val cornerRadiusDp: Float,
    val replaceBackgroundView: View? = null,
    val extraSuppressViews: List<View> = emptyList(),
    val minCaptureIntervalMs: Long = 16L,
    val sourceRenderer: ((android.graphics.Canvas) -> Boolean)? = null,
    val forceRefreshOnPreDraw: Boolean = false,
)

fun interface MultiSurfaceGlassObserver {
    fun onSurfaceAttached(anchor: GlassSurfaceAnchor)
}

data class ClassicalNavTab(
    val index: Int,
    val label: String,
    val iconResId: Int,
    val routeKey: String,
)

data class ClassicalMiniPlayerSnapshot(
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
    val visible: Boolean,
    val onPlayPause: (() -> Unit)?,
    val onNext: (() -> Unit)?,
    val artworkUrl: String? = null,
    val onExpand: (() -> Unit)? = null,
)

data class ClassicalChromeSnapshot(
    val selectedTabIndex: Int,
    val tabs: List<ClassicalNavTab>,
    val playerExpanded: Boolean,
    val searchActive: Boolean,
    val miniPlayer: ClassicalMiniPlayerSnapshot?,
    val playerExpansion: Float = if (playerExpanded) 1f else 0f,
    val playerSheetTopPx: Float = Float.NaN,
    val playerSheetBottomPx: Float = Float.NaN,
    val playerArtworkLeftPx: Float = Float.NaN,
    val playerArtworkTopPx: Float = Float.NaN,
    val playerArtworkSizePx: Float = Float.NaN,
)

interface ClassicalChromePort {
    fun observe(listener: (ClassicalChromeSnapshot) -> Unit): HostSubscription
    fun selectTab(index: Int)
    fun setChromeGlassActive(active: Boolean)
    fun beginPlayerDrag() = Unit
    fun dragPlayerBy(deltaY: Float, eventTimeMs: Long, positionY: Float): Boolean = false
    fun settlePlayerDrag() = Unit
    fun findScrollOwner(root: View): View? = null
    fun canScrollVertically(view: View, direction: Int): Boolean? = null
}
