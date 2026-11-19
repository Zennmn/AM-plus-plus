package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import dev.amenhancer.backdrop.VibrantBlur
import dev.amenhancer.glass.GlassBarCollapseLayout
import dev.amenhancer.glass.GlassBarCollapsePolicy
import dev.amenhancer.glass.GlassBarCollapseSpec
import dev.amenhancer.glass.GlassBarCollapseSprings
import dev.amenhancer.glass.GlassBarExpandSpec
import dev.amenhancer.glass.GlassCollapseShapes
import dev.amenhancer.glass.GlassGeometry
import dev.amenhancer.glass.GlassNavigation
import dev.amenhancer.glass.GlassOverlayHost
import dev.amenhancer.glass.GlassOverlayItem
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.GlassTab
import dev.amenhancer.glass.OverlayBounds
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installs multi-surface Liquid Glass (collapsible bottom capsule nav, interactive mini player
 * pill with swipe-up/transition morph, floating search rim, and modal dialog translucency)
 * on Apple Music Classical using shared [GlassOverlayHost] and [WindowBackdropPool].
 */
@RequiresApi(33)
internal object ClassicalGlassRuntime {
    private val installed = AtomicBoolean(false)
    private val sessions = WeakHashMap<Activity, ClassicalMainSession>()

    @Volatile
    private var chromePort: ClassicalChromePort? = null

    fun install(
        application: Application,
        classLoader: ClassLoader,
        config: TargetConfigClient,
    ): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < 33) {
            return TargetCapabilityInstall.Unsupported("完整玻璃折射需要 Android 13+")
        }
        val build = targetBuild(application)
        if (!dev.amenhancer.host.classical.ClassicalHostProfiles.supportsGlass(build.packageName, build.versionName, build.versionCode)) {
            return TargetCapabilityInstall.Unsupported("当前古典乐版本未配置液态玻璃契约")
        }

        AmMultiSurfaceGlassRuntime.configure(config)
        val port = chromePort ?: ClassicalHostFactory.installChromeHooks(
            application = application,
            classLoader = classLoader,
            dialogGlassObserver = AmMultiSurfaceGlassRuntime,
        )?.also { chromePort = it }
            ?: return TargetCapabilityInstall.Degraded("Classical chrome port unavailable")
        port.setChromeGlassActive(config.settings().phoneLiquidGlassEnabled)

        if (installed.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) {
                    if (!ClassicalHostFactory.isMainActivity(activity)) return
                    if (!config.settings().phoneLiquidGlassEnabled) {
                        sessions.remove(activity)?.close()
                        return
                    }
                    val existing = sessions[activity]
                    if (existing != null) {
                        existing.foreground(true)
                    } else {
                        runCatching { ClassicalMainSession(activity, config, port) }
                            .onSuccess { sessions[activity] = it }
                            .onFailure { ModernXposedRuntime.log("Classical glass session start failed", it) }
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    sessions[activity]?.foreground(false)
                }

                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    sessions.remove(activity)?.close()
                }
            })
        }
        return TargetCapabilityInstall.Active("Classical 多表面液态玻璃已就绪（底栏收放、胶囊过渡、搜索栏、弹窗）")
    }
}

internal data class ClassicalMiniFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val contentAlpha: Float,
    val materialProgress: Float,
    val restLeft: Float,
    val restTop: Float,
    val restWidth: Float,
    val restHeight: Float,
)

private data class ClassicalMetrics(
    val width: Int,
    val height: Int,
    val density: Float,
    val bottomInset: Int,
    val bottomGapPx: Int,
    val rtl: Boolean,
)

internal data class ClassicalFlyingArtworkFrame(
    val left: Float,
    val top: Float,
    val size: Float,
    val cornerDp: Float,
    val alpha: Float,
)

internal object ClassicalChromeLayoutPolicy {
    fun smoothStep(value: Float, start: Float, end: Float): Float {
        if (!value.isFinite()) return 0f
        if (end <= start) return if (value >= end) 1f else 0f
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun computeMiniFrame(
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        bottomInset: Int,
        bottomGapPx: Int,
        rtl: Boolean,
        hasDetachedSearch: Boolean,
        miniVisible: Boolean,
        collapseProgress: Float,
        hasCollapsePresentation: Boolean,
        miniReboundScale: Float,
        miniSinkBounceDp: Int,
        playerExpansion: Float,
        sheetTopPx: Float = Float.NaN,
        sheetBottomPx: Float = Float.NaN,
    ): ClassicalMiniFrame {
        val w = containerWidth.coerceAtLeast(0).toFloat()
        val h = containerHeight.coerceAtLeast(0).toFloat()
        val hMargin = (GlassPolicy.HORIZONTAL_DP * density).roundToInt().toFloat()
        val navHeightPx = (GlassPolicy.NAV_HEIGHT_DP * density).roundToInt().toFloat()
        val miniHeightPx = (GlassPolicy.MINI_HEIGHT_DP * density).roundToInt().toFloat()
        val gapPx = (GlassBarCollapsePolicy.GAP_DP * density).roundToInt().toFloat()
        val capsulePx = (GlassBarCollapsePolicy.CAPSULE_DP * density).roundToInt().toFloat()
        val searchPx = (GlassBarCollapsePolicy.SEARCH_DP * density).roundToInt().toFloat()

        val navBottom = (h - bottomInset - bottomGapPx).coerceAtLeast(navHeightPx)
        val navTop = (navBottom - navHeightPx).coerceAtLeast(0f)
        val expandedMiniBottom = (navTop - gapPx).coerceAtLeast(miniHeightPx)

        val restLeft: Float
        val restRight: Float
        val restBottom: Float
        if (hasCollapsePresentation) {
            val fraction = GlassBarCollapsePolicy.sprungFraction(
                collapseProgress,
                miniReboundScale,
                miniReboundScale,
            )
            val endReserve = if (hasDetachedSearch) searchPx + gapPx else 0f
            val startMargin = GlassBarCollapsePolicy.miniStartMargin(
                hMargin,
                capsulePx,
                gapPx,
                fraction,
            ).coerceAtLeast(0f)
            val endMargin = GlassBarCollapsePolicy.miniEndMargin(
                hMargin,
                endReserve,
                fraction,
            ).coerceAtLeast(0f)
            restLeft = if (rtl) endMargin else startMargin
            restRight = (w - if (rtl) startMargin else endMargin).coerceAtLeast(restLeft)
            val sinkDip = GlassBarCollapsePolicy.miniSinkOvershoot(
                miniSinkBounceDp * density,
                GlassBarCollapseSprings.CollapseOvershoot,
                collapseProgress,
            )
            restBottom = GlassBarCollapsePolicy.lerp(
                expandedMiniBottom,
                navBottom,
                GlassBarCollapsePolicy.progress(collapseProgress),
            ) + sinkDip
        } else {
            restLeft = hMargin
            restRight = (w - hMargin).coerceAtLeast(restLeft)
            restBottom = expandedMiniBottom
        }
        val restTop = (restBottom - miniHeightPx).coerceAtLeast(0f)
        val restWidth = (restRight - restLeft).coerceAtLeast(0f)

        val p = if (playerExpansion.isFinite()) playerExpansion.coerceIn(0f, 1f) else 0f
        val materialProgress = smoothStep(p, 0f, 0.35f)
        val miniAlpha = if (!miniVisible && p <= 0.001f) 0f else 1f - smoothStep(p, 0.35f, 0.60f)
        val contentAlpha = 1f - smoothStep(p, 0f, 0.22f)

        val hasLiveSheet = p > 0.001f &&
            sheetTopPx.isFinite() &&
            sheetBottomPx.isFinite() &&
            sheetBottomPx > sheetTopPx
        val targetTop = if (hasLiveSheet) sheetTopPx.coerceIn(0f, h) else restTop * (1f - p)
        val targetBottom = if (hasLiveSheet) sheetBottomPx.coerceIn(targetTop, h) else restBottom + (h - restBottom) * p
        val curLeft = restLeft * (1f - materialProgress)
        val curRight = w - (w - restRight) * (1f - materialProgress)
        val curTop = restTop + (targetTop - restTop) * materialProgress
        val curBottom = restBottom + (targetBottom - restBottom) * materialProgress
        val curWidth = (curRight - curLeft).coerceAtLeast(0f)
        val curHeight = (curBottom - curTop).coerceAtLeast(miniHeightPx)

        return ClassicalMiniFrame(
            left = curLeft,
            top = curTop,
            width = curWidth,
            height = curHeight,
            alpha = miniAlpha,
            contentAlpha = contentAlpha,
            materialProgress = materialProgress,
            restLeft = restLeft,
            restTop = restTop,
            restWidth = restWidth,
            restHeight = miniHeightPx,
        )
    }

    fun computeArtworkFrame(
        frame: ClassicalMiniFrame,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        rtl: Boolean,
        collapseProgress: Float,
        playerExpansion: Float,
        miniVisible: Boolean,
        artworkLeftPx: Float = Float.NaN,
        artworkTopPx: Float = Float.NaN,
        artworkSizePx: Float = Float.NaN,
    ): ClassicalFlyingArtworkFrame {
        val p = if (playerExpansion.isFinite()) playerExpansion.coerceIn(0f, 1f) else 0f
        if (p <= 0.001f || (!miniVisible && p <= 0.001f)) {
            return ClassicalFlyingArtworkFrame(0f, 0f, 0f, 6f, 0f)
        }
        val w = containerWidth.coerceAtLeast(0).toFloat()
        val h = containerHeight.coerceAtLeast(0).toFloat()
        val collapseC = GlassBarCollapsePolicy.progress(collapseProgress)
        val miniArtSize = lerp(34f, 30f, collapseC) * density
        val miniPad = lerp(14f, 10f, collapseC) * density
        val miniArtLeft = if (rtl) {
            frame.restLeft + frame.restWidth - miniPad - miniArtSize
        } else {
            frame.restLeft + miniPad
        }
        val miniArtTop = frame.restTop + (frame.restHeight - miniArtSize) * 0.5f

        val hasLiveArtwork = artworkLeftPx.isFinite() &&
            artworkTopPx.isFinite() &&
            artworkSizePx.isFinite() &&
            artworkSizePx > 0f
        val targetSize: Float
        val targetLeft: Float
        val targetTop: Float
        if (hasLiveArtwork) {
            targetSize = artworkSizePx
            targetLeft = artworkLeftPx
            targetTop = artworkTopPx
        } else {
            targetSize = minOf(w * 0.62f, h * 0.36f)
            targetLeft = (w - targetSize) * 0.5f
            targetTop = frame.top + frame.height * 0.14f
        }

        val m = frame.materialProgress
        return ClassicalFlyingArtworkFrame(
            left = miniArtLeft + (targetLeft - miniArtLeft) * m,
            top = miniArtTop + (targetTop - miniArtTop) * m,
            size = (miniArtSize + (targetSize - miniArtSize) * m).coerceAtLeast(1f),
            cornerDp = lerp(6f, 10f, m),
            alpha = 1f - smoothStep(p, 0.62f, 0.78f),
        )
    }

    fun isNavHit(
        localX: Float,
        localY: Float,
        rowWidth: Float,
        rowHeight: Float,
        density: Float,
        rtl: Boolean,
        tabsCount: Int,
        detachedSearch: Boolean,
        collapseProgress: Float,
        collapseReboundScale: Float,
        expandReboundScale: Float,
    ): Boolean {
        if (rowWidth <= 0f || rowHeight <= 0f || tabsCount < 2) return false
        if (GlassBarCollapsePolicy.dragEnabled(collapseProgress)) {
            return localX >= 0f && localX < rowWidth && localY >= 0f && localY < rowHeight
        }
        val controls = GlassBarCollapseLayout.controls(
            rowWidthPx = rowWidth,
            rowHeightPx = rowHeight,
            density = density,
            tabsCount = tabsCount,
            progress = collapseProgress,
            collapseReboundScale = collapseReboundScale,
            expandReboundScale = expandReboundScale,
        )
        fun hits(left: Float, width: Float): Boolean {
            val extraX = (48f * density - width).coerceAtLeast(0f) / 2f
            val extraY = (48f * density - controls.height).coerceAtLeast(0f) / 2f
            return localX >= left - extraX && localX < left + width + extraX &&
                localY >= controls.top - extraY && localY < controls.top + controls.height + extraY
        }
        if (hits(if (rtl) rowWidth - controls.capsuleWidth else 0f, controls.capsuleWidth)) return true
        return detachedSearch && GlassBarCollapsePolicy.searchInteractive(collapseProgress) &&
            hits(if (rtl) 0f else rowWidth - controls.searchWidth, controls.searchWidth)
    }
}

private class ClassicalCollapseController(
    private val activity: Activity,
    private val chromePort: ClassicalChromePort,
    private val isEligible: () -> Boolean,
) : AutoCloseable {
    private val animation = Animatable(0f)
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
    private val density get() = activity.resources.displayMetrics.density
    private var inputDensity = density
    private var tracker = newTracker()
    private var scrollOwner: View? = null
    private var downTime = 0L
    private var touching = false
    private var closed = false
    private var targetCollapsed = false

    var enabled by mutableStateOf(false)
        private set
    var appearance by mutableStateOf(GlassBarCollapseAppearance())
        private set
    val progress: Float get() = animation.value
    val isRunning: Boolean get() = animation.isRunning
    val hasPresentation: Boolean get() = enabled || isRunning || progress != 0f

    private fun newTracker(): GlassTouchCollapseTracker {
        val configuration = ViewConfiguration.get(activity)
        return GlassTouchCollapseTracker(
            slopPx = configuration.scaledTouchSlop.toFloat(),
            flingPxPerSecond = configuration.scaledMinimumFlingVelocity.toFloat(),
            thresholdPx = SCROLL_THRESHOLD_DP * density,
        )
    }

    fun configure(settings: ModuleSettings, supported: Boolean) {
        if (closed) return
        appearance = GlassBarCollapseAppearance(
            collapseReboundScale = ModuleSettings.normalizePhoneLiquidGlassCollapseReboundPercent(settings.phoneLiquidGlassCollapseReboundPercent) / 100f,
            expandReboundScale = ModuleSettings.normalizePhoneLiquidGlassExpandReboundPercent(settings.phoneLiquidGlassExpandReboundPercent) / 100f,
            miniReboundScale = ModuleSettings.normalizePhoneLiquidGlassMiniReboundPercent(settings.phoneLiquidGlassMiniReboundPercent) / 100f,
            miniSinkBounceDp = ModuleSettings.normalizePhoneLiquidGlassMiniSinkBounceDp(settings.phoneLiquidGlassMiniSinkBounceDp),
        )
        if (inputDensity != density) {
            tracker = newTracker()
            inputDensity = density
        }
        val wanted = supported && settings.phoneLiquidGlassCollapseEnabled
        if (wanted == enabled) return
        enabled = wanted
        resetInput()
        if (!enabled) expand()
    }

    fun observeTouch(event: MotionEvent, root: View, chromeOrigin: () -> Boolean) {
        val active = enabled && !closed && isEligible()
        if (!active || event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount > 1) {
            resetInput()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetInput()
            if (chromeOrigin()) return
            scrollOwner = chromePort.findScrollOwner(root)
            downTime = event.downTime
            touching = true
            tracker.down(event.rawX, event.rawY, event.eventTime)
            return
        }
        if (!touching || downTime != event.downTime) return
        val decision = when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> tracker.move(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_UP -> tracker.up(event.rawX, event.rawY, event.eventTime)
            else -> null
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) touching = false
        if (decision == null) return
        val target = scrollOwner?.takeIf { it.isShown && it.isAttachedToWindow } ?: return
        val direction = if (decision) 1 else -1
        if (chromePort.canScrollVertically(target, direction) == true) {
            request(decision)
        }
    }

    fun expand() {
        resetInput()
        request(false)
    }

    fun resetInput() {
        touching = false
        scrollOwner = null
        tracker.reset()
    }

    private fun request(collapsed: Boolean) {
        if (closed || (collapsed && !enabled) || targetCollapsed == collapsed) return
        targetCollapsed = collapsed
        val spec = if (collapsed) GlassBarCollapseSpec else GlassBarExpandSpec
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            animation.animateTo(if (collapsed) 1f else 0f, spec)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        resetInput()
        scope.cancel()
    }

    private companion object {
        const val SCROLL_THRESHOLD_DP = 6f
    }
}

private class ClassicalWindowTouchCallback(
    val delegate: Window.Callback,
    private val onTouch: (MotionEvent) -> Unit,
) : Window.Callback by delegate {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouch(event)
        return delegate.dispatchTouchEvent(event)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        delegate.onPointerCaptureChanged(hasCapture)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }
}

@RequiresApi(33)
private class ClassicalMainSession(
    private val activity: Activity,
    private val config: TargetConfigClient,
    private val chromePort: ClassicalChromePort,
) {
    private val content = checkNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
    private val composeRoot = checkNotNull(content.getChildAt(0))
    private val moduleContext = moduleGlassContext(activity)
    private val overlay = GlassOverlayHost(moduleContext, bleedDp = 16, traceName = "AM++/Classical")
    private val lease: WindowBackdropPool.Lease
    private var snapshot by mutableStateOf(
        ClassicalChromeSnapshot(0, emptyList(), playerExpanded = false, searchActive = false, miniPlayer = null, playerExpansion = 0f),
    )
    private val collapse = ClassicalCollapseController(activity, chromePort) {
        !closed && snapshot.playerExpansion <= 0.001f && snapshot.tabs.size >= 2
    }
    private var tabDrawables: Map<Int, Drawable?> = emptyMap()
    private var closed = false
    private val subscription: HostSubscription
    private var touchCallback: ClassicalWindowTouchCallback? = null
    private var settings by mutableStateOf(config.settings())
    private var nextSettingsCheck = 0L
    private var lastTabIndex = -1
    private var lastLayoutWidth = -1
    private val overlayLocation = IntArray(2)

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncItems() }
    private val insetsListener = View.OnApplyWindowInsetsListener { view, insets ->
        syncItems()
        view.onApplyWindowInsets(insets)
    }
    private var lastNightMode: Boolean? = null

    init {
        check(BackdropSafetyPolicy.isSafeBackdropSource(overlay, composeRoot)) {
            "Classical overlay cannot sample itself"
        }
        lease = WindowBackdropPool.acquire(composeRoot, minCaptureIntervalMs = 16L) { close() }
        content.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addOnLayoutChangeListener(layoutListener)
        composeRoot.addOnLayoutChangeListener(layoutListener)
        content.setOnApplyWindowInsetsListener(insetsListener)
        val originalCallback = activity.window.callback
        if (originalCallback != null && originalCallback !is ClassicalWindowTouchCallback) {
            val wrapper = ClassicalWindowTouchCallback(originalCallback, ::onWindowTouch)
            touchCallback = wrapper
            activity.window.callback = wrapper
        }
        refreshSettings(force = true)
        subscription = chromePort.observe { next ->
            if (!closed) {
                val prev = snapshot
                if (lastTabIndex != -1 && next.selectedTabIndex != lastTabIndex) {
                    collapse.expand()
                }
                lastTabIndex = next.selectedTabIndex
                snapshot = next
                val needsSync = overlay.itemCount == 0 ||
                    prev.tabs != next.tabs ||
                    prev.selectedTabIndex != next.selectedTabIndex ||
                    prev.miniPlayer != next.miniPlayer ||
                    (prev.playerExpansion > 0.001f) != (next.playerExpansion > 0.001f)
                if (needsSync) {
                    syncItems()
                }
            }
        }
        chromePort.setChromeGlassActive(true)
        content.post {
            content.requestApplyInsets()
            syncItems()
        }
    }

    private fun refreshSettings(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now < nextSettingsCheck) return
        nextSettingsCheck = now + 500L
        val latest = config.settings()
        settings = latest
        collapse.configure(latest, supported = true)
    }

    private fun onWindowTouch(event: MotionEvent) {
        if (closed) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            refreshSettings()
        }
        collapse.observeTouch(event, composeRoot) { isTouchOnChrome(event) }
    }

    private fun isTouchOnChrome(event: MotionEvent): Boolean {
        val width = content.width
        val height = content.height
        if (width <= 0 || height <= 0) return false
        overlay.getLocationOnScreen(overlayLocation)
        val x = event.rawX - overlayLocation[0]
        val y = event.rawY - overlayLocation[1]
        if (isNavHitAt(x, y, width, height)) return true
        return isMiniHitAt(x, y, width, height)
    }

    private fun currentMetrics(width: Int, height: Int): ClassicalMetrics {
        val density = activity.resources.displayMetrics.density
        val bottomInset = content.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        val configuredGapPx = (ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(settings.phoneLiquidGlassBottomGapDp) * density).roundToInt()
        val bottomGapPx = if (bottomInset == 0) maxOf(configuredGapPx, (14f * density).roundToInt()) else configuredGapPx
        val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return ClassicalMetrics(width, height, density, bottomInset, bottomGapPx, rtl)
    }

    private fun isNavHitAt(x: Float, y: Float, width: Int, height: Int): Boolean {
        val current = snapshot
        if (current.playerExpansion > 0.02f || current.tabs.size < 2) return false
        val m = currentMetrics(width, height)
        val hMargin = (GlassPolicy.HORIZONTAL_DP * m.density).roundToInt()
        val navHeight = (GlassPolicy.NAV_HEIGHT_DP * m.density).roundToInt()
        val navBottom = height - m.bottomInset - m.bottomGapPx
        val navTop = (navBottom - navHeight).coerceAtLeast(0)
        val rowWidth = (width - 2 * hMargin).coerceAtLeast(0).toFloat()
        val hasDetachedSearch = current.tabs.size > 2 && current.tabs.last().index == 2
        val appearance = collapse.appearance
        return ClassicalChromeLayoutPolicy.isNavHit(
            localX = x - hMargin,
            localY = y - navTop,
            rowWidth = rowWidth,
            rowHeight = navHeight.toFloat(),
            density = m.density,
            rtl = m.rtl,
            tabsCount = current.tabs.size,
            detachedSearch = hasDetachedSearch,
            collapseProgress = collapse.progress,
            collapseReboundScale = appearance.collapseReboundScale,
            expandReboundScale = appearance.expandReboundScale,
        )
    }

    private fun isMiniHitAt(x: Float, y: Float, width: Int, height: Int): Boolean {
        val current = snapshot
        val mini = current.miniPlayer
        if (mini == null || !mini.visible || current.playerExpansion > 0.35f) return false
        val m = currentMetrics(width, height)
        val hasDetachedSearch = current.tabs.size > 2 && current.tabs.last().index == 2
        val appearance = collapse.appearance
        val frame = ClassicalChromeLayoutPolicy.computeMiniFrame(
            containerWidth = width,
            containerHeight = height,
            density = m.density,
            bottomInset = m.bottomInset,
            bottomGapPx = m.bottomGapPx,
            rtl = m.rtl,
            hasDetachedSearch = hasDetachedSearch,
            miniVisible = true,
            collapseProgress = collapse.progress,
            hasCollapsePresentation = collapse.hasPresentation,
            miniReboundScale = appearance.miniReboundScale,
            miniSinkBounceDp = appearance.miniSinkBounceDp,
            playerExpansion = current.playerExpansion,
            sheetTopPx = current.playerSheetTopPx,
            sheetBottomPx = current.playerSheetBottomPx,
        )
        return x >= frame.left && x < frame.left + frame.width &&
            y >= frame.top && y < frame.top + frame.height
    }

    fun foreground(active: Boolean) {
        if (closed) return
        if (active) {
            refreshSettings(force = true)
        } else {
            collapse.resetInput()
        }
        overlay.foreground(active)
        chromePort.setChromeGlassActive(active && settings.phoneLiquidGlassEnabled)
        if (active) syncItems()
    }

    fun close() {
        if (closed) return
        closed = true
        val window = activity.window
        val wrapper = touchCallback
        if (wrapper != null && window.callback === wrapper) {
            window.callback = wrapper.delegate
        }
        touchCallback = null
        collapse.close()
        chromePort.setChromeGlassActive(false)
        subscription.close()
        runCatching { content.setOnApplyWindowInsetsListener(null) }
        runCatching { content.removeOnLayoutChangeListener(layoutListener) }
        runCatching { composeRoot.removeOnLayoutChangeListener(layoutListener) }
        runCatching { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        overlay.clear()
        lease.close()
    }

    private fun ensureTabIcons(tabs: List<ClassicalNavTab>, night: Boolean, fgArgb: Int) {
        if (lastNightMode == night && tabDrawables.size == tabs.size) return
        if (tabs.isEmpty()) return
        lastNightMode = night
        val res = activity.resources
        tabDrawables = tabs.associate { tab ->
            val drawable = if (tab.iconResId != 0) {
                runCatching {
                    res.getDrawable(tab.iconResId, activity.theme)
                        ?.constantState?.newDrawable()?.mutate()
                        ?: res.getDrawable(tab.iconResId, activity.theme)?.mutate()
                }.getOrNull()?.apply { setTint(fgArgb) }
            } else null
            tab.index to drawable
        }
    }

    private fun syncItems() {
        if (closed) return
        val width = content.width
        val height = content.height
        if (width <= 0 || height <= 0) return

        refreshSettings()
        if (!settings.phoneLiquidGlassEnabled) {
            overlay.clear()
            chromePort.setChromeGlassActive(false)
            return
        }
        chromePort.setChromeGlassActive(true)

        if (lastLayoutWidth != -1 && lastLayoutWidth != width) {
            collapse.expand()
        }
        lastLayoutWidth = width

        val m = currentMetrics(width, height)
        val blurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(settings.phoneLiquidGlassPanelBlurDp).dp
        val hMargin = (GlassPolicy.HORIZONTAL_DP * m.density).roundToInt()
        val navHeight = (GlassPolicy.NAV_HEIGHT_DP * m.density).roundToInt()

        val current = snapshot
        if (current.tabs.size < 2) {
            overlay.remove("classical_nav")
            overlay.remove("classical_mini")
            return
        }

        val navBottom = height - m.bottomInset - m.bottomGapPx
        val navTop = (navBottom - navHeight).coerceAtLeast(0)
        val night = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val fgColor = if (night) Color.White else Color.Black
        ensureTabIcons(current.tabs, night, fgColor.toArgb())
        val accentColor = Color(0xFFFA233B)
        val glassTabs = current.tabs.map { tab ->
            GlassTab(
                id = tab.index,
                title = tab.label,
                icon = tabDrawables[tab.index],
                enabled = true,
                isSearch = tab.index == 2,
            )
        }

        val configDensity = Density(
            activity.resources.configuration.densityDpi / 160f,
            activity.resources.configuration.fontScale,
        )
        val configLayoutDir = if (m.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        val bottomGapDp = ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(settings.phoneLiquidGlassBottomGapDp)
        val effectiveBottomGapDp = if (m.bottomInset == 0) maxOf(bottomGapDp, 14) else bottomGapDp

        // Keep "classical_nav" inserted BEFORE "classical_mini" so the collapsed mini pill sits on top in Z-order.
        overlay.upsert(
            GlassOverlayItem(
                key = "classical_nav",
                bounds = OverlayBounds(hMargin, navTop, (width - hMargin).coerceAtLeast(hMargin), navBottom),
                interactive = true,
                alpha = 1f,
                hitTest = { x, y -> isNavHitAt(x, y, width, height) },
            ) {
                CompositionLocalProvider(
                    LocalConfiguration provides activity.resources.configuration,
                    LocalDensity provides configDensity,
                    LocalLayoutDirection provides configLayoutDir,
                ) {
                    val liveSnapshot = snapshot
                    val liveMiniVisible = liveSnapshot.miniPlayer?.visible == true
                    val appearance = collapse.appearance
                    val hasCollapse = collapse.hasPresentation
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val expansion = liveSnapshot.playerExpansion.coerceIn(0f, 1f)
                                val collapseP = if (hasCollapse) collapse.progress else 0f
                                val extent = GlassPolicy.occupiedHeight(
                                    density = m.density,
                                    bottomInset = m.bottomInset,
                                    miniVisible = liveMiniVisible,
                                    bottomGapDp = effectiveBottomGapDp,
                                    geometry = GlassGeometry.Phone,
                                    collapse = collapseP,
                                )
                                translationY = FragmentPhoneGlassPolicy.navigationExit(expansion, extent)
                                alpha = if (expansion >= 0.99f) 0f else 1f
                            },
                    ) {
                        GlassNavigation(
                            tabs = glassTabs,
                            selectedId = liveSnapshot.selectedTabIndex,
                            accent = accentColor,
                            foreground = fgColor,
                            backdrop = lease.backdrop,
                            onSelect = { id ->
                                collapse.expand()
                                chromePort.selectTab(id)
                                id
                            },
                            panelHeight = GlassPolicy.NAV_HEIGHT_DP.dp,
                            panelBlur = blurDp,
                            collapseProgress = if (hasCollapse) collapse::progress else null,
                            onExpandRequest = collapse::expand,
                            collapseReboundScale = appearance.collapseReboundScale,
                            expandReboundScale = appearance.expandReboundScale,
                        )
                    }
                }
            },
        )

        val mini = current.miniPlayer
        if (mini != null && (mini.visible || current.playerExpansion > 0.001f)) {
            overlay.upsert(
                GlassOverlayItem(
                    key = "classical_mini",
                    bounds = OverlayBounds(0, 0, width, height),
                    interactive = true,
                    alpha = 1f,
                    hitTest = { x, y -> isMiniHitAt(x, y, width, height) },
                ) {
                    CompositionLocalProvider(
                        LocalConfiguration provides activity.resources.configuration,
                        LocalDensity provides configDensity,
                        LocalLayoutDirection provides configLayoutDir,
                    ) {
                        ClassicalMiniPlayerOverlay(
                            backdrop = lease.backdrop,
                            blurDp = blurDp,
                            mini = snapshot.miniPlayer ?: mini,
                            foreground = fgColor,
                            isDark = night,
                            containerWidth = width,
                            containerHeight = height,
                            metrics = m,
                            hasDetachedSearch = glassTabs.size > 2 && glassTabs.last().isSearch,
                            collapse = collapse,
                            playerExpansionProvider = { snapshot.playerExpansion },
                            sheetTopProvider = { snapshot.playerSheetTopPx },
                            sheetBottomProvider = { snapshot.playerSheetBottomPx },
                            artworkLeftProvider = { snapshot.playerArtworkLeftPx },
                            artworkTopProvider = { snapshot.playerArtworkTopPx },
                            artworkSizeProvider = { snapshot.playerArtworkSizePx },
                            chromePort = chromePort,
                        )
                    }
                },
            )
        } else {
            overlay.remove("classical_mini")
        }
    }
}

private object ClassicalArtworkLoader {
    private val cache = LruCache<String, Bitmap>(12)
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-classical-art").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun cached(url: String?): Bitmap? = url?.let(cache::get)

    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        return withContext(dispatcher) {
            cache.get(url)?.let { return@withContext it }
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    instanceFollowRedirects = true
                }
                try {
                    conn.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.also { bmp ->
                cache.put(url, bmp)
            }
        }
    }
}

@Composable
private fun ClassicalMiniPlayerOverlay(
    backdrop: Backdrop,
    blurDp: Dp,
    mini: ClassicalMiniPlayerSnapshot,
    foreground: Color,
    isDark: Boolean,
    containerWidth: Int,
    containerHeight: Int,
    metrics: ClassicalMetrics,
    hasDetachedSearch: Boolean,
    collapse: ClassicalCollapseController,
    playerExpansionProvider: () -> Float,
    sheetTopProvider: () -> Float,
    sheetBottomProvider: () -> Float,
    artworkLeftProvider: () -> Float,
    artworkTopProvider: () -> Float,
    artworkSizeProvider: () -> Float,
    chromePort: ClassicalChromePort,
) {
    val frameState = remember(
        containerWidth,
        containerHeight,
        metrics,
        hasDetachedSearch,
        collapse,
        playerExpansionProvider,
        sheetTopProvider,
        sheetBottomProvider,
    ) {
        derivedStateOf {
            val appearance = collapse.appearance
            ClassicalChromeLayoutPolicy.computeMiniFrame(
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                density = metrics.density,
                bottomInset = metrics.bottomInset,
                bottomGapPx = metrics.bottomGapPx,
                rtl = metrics.rtl,
                hasDetachedSearch = hasDetachedSearch,
                miniVisible = mini.visible,
                collapseProgress = collapse.progress,
                hasCollapsePresentation = collapse.hasPresentation,
                miniReboundScale = appearance.miniReboundScale,
                miniSinkBounceDp = appearance.miniSinkBounceDp,
                playerExpansion = playerExpansionProvider(),
                sheetTopPx = sheetTopProvider(),
                sheetBottomPx = sheetBottomProvider(),
            )
        }
    }
    val artFrameState = remember(
        containerWidth,
        containerHeight,
        metrics,
        mini.visible,
        collapse,
        playerExpansionProvider,
        artworkLeftProvider,
        artworkTopProvider,
        artworkSizeProvider,
        frameState,
    ) {
        derivedStateOf {
            ClassicalChromeLayoutPolicy.computeArtworkFrame(
                frame = frameState.value,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                density = metrics.density,
                rtl = metrics.rtl,
                collapseProgress = collapse.progress,
                playerExpansion = playerExpansionProvider(),
                miniVisible = mini.visible,
                artworkLeftPx = artworkLeftProvider(),
                artworkTopPx = artworkTopProvider(),
                artworkSizePx = artworkSizeProvider(),
            )
        }
    }
    val reserveLayer by remember(collapse, playerExpansionProvider) {
        derivedStateOf { collapse.isRunning && playerExpansionProvider() <= 0.001f }
    }
    val frame by frameState
    val collapseC = GlassBarCollapsePolicy.progress(collapse.progress)
    val minLayerSize = if (reserveLayer) {
        IntSize(
            frame.restWidth.roundToInt().coerceAtLeast(1),
            frame.restHeight.roundToInt().coerceAtLeast(1),
        )
    } else {
        IntSize.Zero
    }
    val radiusPx = with(LocalDensity.current) { blurDp.toPx() }
    val material = remember(radiusPx) { VibrantBlur(radiusPx) }
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val containerColor = remember(isDark) {
        if (isDark) Color(0xFF121212).copy(alpha = 0.4f)
        else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    }
    val latestMini by rememberUpdatedState(mini)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var placedTopPx by remember { mutableStateOf(0f) }

    val artwork by produceState(
        initialValue = ClassicalArtworkLoader.cached(mini.artworkUrl),
        key1 = mini.artworkUrl,
    ) {
        value = ClassicalArtworkLoader.load(mini.artworkUrl)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val current = frameState.value
                    if (current.width < 1f || current.height < 1f || containerWidth <= 0 || containerHeight <= 0) {
                        layout(0, 0) {}
                    } else {
                        val w = current.width.roundToInt().coerceAtLeast(1)
                        val h = current.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        val left = current.left.roundToInt()
                        val top = current.top.roundToInt()
                        layout(containerWidth, containerHeight) {
                            placedTopPx = top.toFloat()
                            placeable.place(left, top)
                        }
                    }
                }
                .graphicsLayer { alpha = frameState.value.alpha }
                .drawBackdrop(
                    backdrop = backdrop,
                    minLayerSize = if (minLayerSize != IntSize.Zero) material.paddedSize(minLayerSize) else IntSize.Zero,
                    clipCompositingStrategy = CompositingStrategy.Auto,
                    shape = {
                        val current = frameState.value
                        if (current.materialProgress <= 0.001f) {
                            GlassCollapseShapes.capsule(true)
                        } else {
                            RoundedCornerShape(lerp(GlassPolicy.MINI_HEIGHT_DP / 2f, 24f, current.materialProgress).dp)
                        }
                    },
                    effects = {
                        material.applyTo(this)
                        val edge = minOf(24f.dp.toPx(), size.minDimension * 0.375f)
                        lens(edge, edge)
                    },
                    layerBlock = {
                        val w = size.width
                        val h = size.height
                        if (w > 0f && h > 0f) {
                            val progress = highlight.pressProgress
                            val maxPull = 4f.dp.toPx() / h
                            val scale = lerp(1f, 1f + maxPull, progress)
                            val maxOffset = size.minDimension
                            val off = highlight.offset
                            translationX = maxOffset * tanh(0.05f * off.x / maxOffset)
                            translationY = maxOffset * tanh(0.05f * off.y / maxOffset)
                            val angle = atan2(off.y, off.x)
                            scaleX = scale + maxPull * abs(cos(angle) * off.x / size.maxDimension) *
                                (w / h).fastCoerceAtMost(1f)
                            scaleY = scale + maxPull * abs(sin(angle) * off.y / size.maxDimension) *
                                (h / w).fastCoerceAtMost(1f)
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(highlight.modifier)
                .pointerInput(chromePort, touchSlop) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        var dragMode = 0 // 0 = undecided, 1 = vertical sheet drag, -1 = ignored
                        var settled = false
                        val downScreenY = placedTopPx + down.position.y
                        var prevScreenY = downScreenY
                        val startLocalX = down.position.x
                        highlight.pressAt(down.position)
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    ?: break
                                if (!change.pressed) {
                                    if (dragMode == 1) {
                                        settled = true
                                        chromePort.settlePlayerDrag()
                                    } else if (dragMode == 0 && !change.isConsumed) {
                                        change.consume()
                                        latestMini.onExpand?.invoke()
                                    }
                                    break
                                }
                                val curScreenY = placedTopPx + change.position.y
                                val cumDy = curScreenY - downScreenY
                                val cumDx = change.position.x - startLocalX
                                when (dragMode) {
                                    0 -> {
                                        if (cumDy < -touchSlop && abs(cumDy) >= abs(cumDx)) {
                                            dragMode = 1
                                            chromePort.beginPlayerDrag()
                                            val delta = curScreenY - prevScreenY
                                            if (delta != 0f) {
                                                chromePort.dragPlayerBy(delta, change.uptimeMillis, curScreenY)
                                                prevScreenY = curScreenY
                                            }
                                            highlight.moveTo(change.position)
                                            change.consume()
                                        } else if ((abs(cumDx) > touchSlop && abs(cumDx) > abs(cumDy)) || cumDy > touchSlop) {
                                            dragMode = -1
                                            highlight.releasePress()
                                        } else {
                                            highlight.moveTo(change.position)
                                        }
                                    }
                                    1 -> {
                                        val delta = curScreenY - prevScreenY
                                        if (delta != 0f) {
                                            chromePort.dragPlayerBy(delta, change.uptimeMillis, curScreenY)
                                            prevScreenY = curScreenY
                                        }
                                        highlight.moveTo(change.position)
                                        change.consume()
                                    }
                                }
                            }
                        } finally {
                            highlight.releasePress()
                            if (dragMode == 1 && !settled) {
                                chromePort.settlePlayerDrag()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GlassPolicy.MINI_HEIGHT_DP.dp)
                    .graphicsLayer { alpha = frameState.value.contentAlpha }
                    .padding(horizontal = lerp(14f, 10f, collapseC).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artSize = lerp(34f, 30f, collapseC).dp
                val artShape = RoundedCornerShape(6.dp)
                val inlineArtModifier = Modifier
                    .size(artSize)
                    .graphicsLayer { alpha = if (playerExpansionProvider() <= 0.001f) 1f else 0f }
                    .clip(artShape)
                val bmp = artwork
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = inlineArtModifier,
                    )
                } else {
                    Box(
                        modifier = inlineArtModifier.background(foreground.copy(alpha = 0.08f)),
                    )
                }
                Spacer(Modifier.width(lerp(10f, 8f, collapseC).dp))
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = mini.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = foreground,
                            fontSize = 13.5f.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    if (mini.subtitle.isNotBlank()) {
                        BasicText(
                            text = mini.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                color = foreground.copy(alpha = 0.68f),
                                fontSize = 11.5f.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                        ) { latestMini.onPlayPause?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseGlyph(isPlaying = mini.isPlaying, tint = foreground, modifier = Modifier.size(16.dp))
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                        ) { latestMini.onNext?.invoke() },
                    contentAlignment = Alignment.Center,
                ) {
                    NextGlyph(tint = foreground, modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val art = artFrameState.value
                    if (art.alpha <= 0.001f || art.size < 1f || containerWidth <= 0 || containerHeight <= 0) {
                        layout(0, 0) {}
                    } else {
                        val sz = art.size.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(sz, sz))
                        val left = art.left.roundToInt()
                        val top = art.top.roundToInt()
                        layout(containerWidth, containerHeight) {
                            placeable.place(left, top)
                        }
                    }
                }
                .graphicsLayer {
                    val art = artFrameState.value
                    alpha = art.alpha
                    shape = RoundedCornerShape(art.cornerDp.dp)
                    clip = true
                },
        ) {
            val bmp = artwork
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(foreground.copy(alpha = 0.12f)),
                )
            }
        }
    }
}

@Composable
private fun PlayPauseGlyph(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (isPlaying) {
            val barWidth = size.width * 0.24f
            val barHeight = size.height * 0.78f
            val top = (size.height - barHeight) * 0.5f
            val gap = size.width * 0.22f
            val left1 = (size.width - (2f * barWidth + gap)) * 0.5f
            val left2 = left1 + barWidth + gap
            val corner = CornerRadius(barWidth * 0.35f, barWidth * 0.35f)
            drawRoundRect(tint, topLeft = Offset(left1, top), size = Size(barWidth, barHeight), cornerRadius = corner)
            drawRoundRect(tint, topLeft = Offset(left2, top), size = Size(barWidth, barHeight), cornerRadius = corner)
        } else {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.22f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.50f)
                lineTo(w * 0.22f, h * 0.86f)
                close()
            }
            drawPath(path, color = tint)
        }
    }
}

@Composable
private fun NextGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.22f)
            lineTo(w * 0.52f, h * 0.50f)
            lineTo(w * 0.08f, h * 0.78f)
            close()
            moveTo(w * 0.50f, h * 0.22f)
            lineTo(w * 0.94f, h * 0.50f)
            lineTo(w * 0.50f, h * 0.78f)
            close()
        }
        drawPath(path, color = tint)
    }
}
