package dev.amenhancer.module.hook

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.NativeButtonInput
import dev.amenhancer.glass.NativeLiquidButton
import dev.amenhancer.glass.GlassNavigation
import dev.amenhancer.glass.GlassNavigationStyle
import dev.amenhancer.glass.GlassTab
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** One native MusicContentFragment view lifetime, with separate top and bottom materials. */
@RequiresApi(33)
internal class FragmentTabletGlassSession(
    private val surface: FragmentPlayerSurfacePort,
    private val config: dev.amenhancer.module.config.TargetConfigClient,
    private val ready: () -> Unit,
    private val failure: (Throwable) -> Unit,
) : FragmentGlassSessionLifecycle,
    ViewTreeObserver.OnPreDrawListener {
    override val activity = surface.activity
    val root = surface.snapshot().contentRoot
    private val native = checkNotNull(surface.tabletChrome)
    private var observer: ViewTreeObserver? = null
    private var overlay: FrameLayout? = null
    private var backdrop: ViewBackdrop? = null
    private var navigation: Surface? = null
    private var mini: Surface? = null
    private var miniFrame: FragmentTabletGlassLayer? = null
    private var playerRoot: ViewGroup? = null
    private var collapsedMini: FragmentTabletGlassPolicy.Bounds? = null
    private var miniWasVisible = false
    private var tabs by mutableStateOf(emptyList<GlassTab>())
    private var selectedId by mutableIntStateOf(View.NO_ID)
    private var panelHeight by mutableStateOf(56.dp)
    private var foreground by mutableStateOf(Color.Black)
    private var accent by mutableStateOf(Color.Red)
    private var drawerIcon by mutableStateOf<Drawable?>(null)
    private var navKey: Any? = null
    private var navGesture: Long? = null
    private var navContentWidth = 0
    private var navContentHeight = 0
    private var slide = 0f
    private var glassExpansion by mutableFloatStateOf(0f)
    private var miniContent: View? = null
    private var configuration by mutableStateOf(Configuration(root.resources.configuration))
    private var blurDp = -1
    private var bottomGapDp = dev.amenhancer.module.model.EnhancementDefaults.GLASS_BOTTOM_DP
    private var closed = false
    private var failed = false
    private var failurePending = false
    private var activationPending = false
    private var active = true
    private var nextMenuCheck = 0L
    var replacing = false
        private set

    private inner class Surface(val anchor: View, val content: View, val glass: GlassHostView) {
        val input = NativeButtonInput()
        val press = FragmentTabletGlassPress(ViewConfiguration.get(activity).scaledTouchSlop.toFloat())
        var pressed = false
    }

    private val captureWait = dev.amenhancer.module.host.GlassCaptureWait()
    private var subscription: HostSubscription? = null
    init { subscription = surface.observe { state -> if (state.expansion != slide) onSlide(state.expansion) }; start() }

    fun safely(action: FragmentTabletGlassSession.() -> Unit) {
        if (closed || failurePending) return
        try { action() } catch (error: Throwable) { fail(error) }
    }

    fun start() {
        if (closed || observer != null) return
        if (!root.isAttachedToWindow) {
            root.post { safely { start() } }
            return
        }
        observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        root.invalidate()
    }

    override fun onPreDraw(): Boolean {
        if (closed || failurePending) return true
        var draw = true
        safely {
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= nextMenuCheck) {
                nextMenuCheck = now + 500
                val settings = config.settings()
                bottomGapDp = dev.amenhancer.module.model.ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(
                    settings.phoneLiquidGlassBottomGapDp)
                if (blurDp >= 0 && blurDp != settings.phoneLiquidGlassPanelBlurDp) refreshSettings()
            }
            if (configuration != root.resources.configuration) {
                configuration = Configuration(root.resources.configuration)
            }
            if (!config.settings().phoneLiquidGlassEnabled || !eligible()) {
                if (overlay != null) scheduleRemove()
                return@safely
            }
            if (failed) return@safely
            if (overlay == null) {
                // Never add/remove Views during traversal.
                if (!activationPending) {
                    activationPending = true
                    root.post {
                        activationPending = false
                        safely { if (config.settings().phoneLiquidGlassEnabled && !closed) buildMaterials() }
                    }
                }
                return@safely
            }
            if (miniContent !== find(TabletChromeRegion.MINI)) {
                scheduleRemove()
                return@safely
            }
            val nav = navigation ?: return@safely
            val min = mini ?: return@safely
            if (native.setMiniBottomGap((bottomGapDp * root.resources.displayMetrics.density).roundToInt())) {
                // Let the native sheet and the sibling material lay out together before sampling bounds.
                root.postInvalidateOnAnimation()
                draw = false
                return@safely
            }
            if (slide <= .001f) refreshNavigation()
            refreshCollapsedMini(min)
            val transition = FragmentPlayerSurfaceMotion.tabletFrame(slide)
            val miniOpacity = if (miniWasVisible) transition.alpha else 0f
            val miniVisible = miniOpacity > .001f
            if (min.glass.visibility != if (miniVisible) View.VISIBLE else View.GONE) {
                min.glass.visibility = if (miniVisible) View.VISIBLE else View.GONE
            }
            // A resized anchor makes navigation temporarily unready/transparent. Still follow
            // its bounds, otherwise readiness can never recover without rebuilding the session.
            val layoutChanged = position(nav) or
                (if (miniVisible || !replacing) positionMini(min) else false)
            // Positioning can request layout; native ownership, glass and capture must agree
            // on readiness after those writes, including frames that only move the anchor.
            val navigationStable = navigationReady() && navigationLaidOut()
            val navOpacity = if (navigationStable) native.navigationOpacity() * transition.alpha else 0f
            val visible = active && (navOpacity > 0.001f || miniOpacity > 0.001f)
            val captureChanged = backdrop?.setCaptureEnabled(visible) == true
            if (!replacing && navigationStable && backdrop?.ready == true && !layoutChanged) {
                if (!activationPending) {
                    activationPending = true
                    root.post {
                        activationPending = false
                        safely { activate() }
                    }
                }
            }
            if (replacing) {
                stripMiniBackground()
                syncNativeNavigation(navigationStable)
                overlay?.z = maxOf(nav.anchor.z, nav.content.z)
                updatePlayerLayers(transition)
                glassExpansion = transition.expansion
                nav.glass.contentAlpha = navOpacity
                min.glass.alpha = miniOpacity
                if (navOpacity <= 0.001f) release(nav)
                if (miniOpacity <= 0.001f) release(min)
                // Moving the glass does not change its sampling source or require canceling native frames.
                val waitingForCapture = visible && backdrop?.ready != true
                check(!captureWait.timedOut(now, waitingForCapture)) {
                    "Tablet glass backdrop resume timed out; restoring native drawing"
                }
                if (visible && (captureChanged || waitingForCapture)) {
                    root.postInvalidateOnAnimation()
                    draw = false
                }
            }
        }
        return draw
    }

    private fun buildMaterials() {
        if (overlay != null || failed || !root.isAttachedToWindow || !eligible()) return
        val nativeTabs = find(TabletChromeRegion.NAVIGATION) ?: error("Native tablet top navigation missing")
        val topBlur = find(TabletChromeRegion.NAVIGATION_MATERIAL) ?: error("Native tablet navigation material missing")
        val miniBlur = find(TabletChromeRegion.MINI_MATERIAL) ?: error("Native mini material missing")
        val content = find(TabletChromeRegion.MINI) ?: error("Native mini content missing")
        val source = find(TabletChromeRegion.BACKDROP) ?: error("Native content sampling target missing")
        check(topBlur.parent === root && miniBlur.parent === root)
        check(!contains(source, nativeTabs) && !contains(source, content)) { "Glass sampling target contains native chrome" }
        blurDp = config.settings().phoneLiquidGlassPanelBlurDp
        bottomGapDp = dev.amenhancer.module.model.ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(
            config.settings().phoneLiquidGlassBottomGapDp)
        miniContent = content
        // Native transform ownership is maintained by the host port.
        val sheet = find(TabletChromeRegion.SHEET) ?: error("Native sheet missing")
        slide = surface.snapshot().expansion
        refreshNavigation()
        val bg = ViewBackdrop(source, ::fail).also { backdrop = it; it.start() }
        val frame = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = dispatchNavigation(event)
        }.apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        overlay = frame
        // Above both native navigation layers, while remaining below the native player.
        // Native blur can draw from a cached RenderNode without entering BlurView.draw.
        frame.z = maxOf(topBlur.z, nativeTabs.z)
        root.addView(frame, root.indexOfChild(nativeTabs) + 1, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val context = moduleContext()
        fun surface(anchor: View, native: View, navigation: Boolean): Surface {
            // The default 24dp shadow extends 48dp plus its 4dp offset. Fade the
            // padded Compose layer, not the smaller capsule/hit rectangle: fractional
            // View alpha on that rectangle clips the shadow before the tabs fade.
            val glass = GlassHostView(context, bleedDp = if (navigation) 64 else 32).apply {
                if (navigation) contentAlpha = 0f else alpha = 0f
                importantForAccessibility = if (navigation) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val surface = Surface(anchor, native, glass)
            glass.content {
                HostConfiguration {
                    if (navigation) {
                        Box(Modifier.onGloballyPositioned { coordinates ->
                            val size = coordinates.size
                            if (navContentWidth != size.width || navContentHeight != size.height) {
                                navContentWidth = size.width
                                navContentHeight = size.height
                                root.postInvalidateOnAnimation()
                            }
                        }) {
                            GlassNavigation(tabs, selectedId, accent, foreground, bg, ::selectTab,
                                panelHeight = panelHeight, panelBlur = blurDp.dp,
                                style = GlassNavigationStyle.TabletLabels, drawerIcon = drawerIcon,
                                drawerDescription = ModuleText.OPEN_SIDEBAR.text(), onDrawer = { safely { this@FragmentTabletGlassSession.surface.navigation.openDrawer() } })
                        }
                    } else {
                        NativeLiquidButton(bg, surface.input, glassExpansion, panelBlur = blurDp.dp, autoClip = true,
                            miniHeightDp = (anchor.height / root.resources.displayMetrics.density).toInt().coerceAtLeast(1)) { sx, sy, x, y ->
                            transformMini(sx, sy, x, y)
                        }
                    }
                }
            }
            if (navigation) frame.addView(glass, FrameLayout.LayoutParams(1, 1))
            else checkNotNull(miniFrame).attach(glass)
            return surface
        }
        val nativePlayer = find(TabletChromeRegion.PLAYER) as? ViewGroup ?: error("Native player content missing")
        playerRoot = nativePlayer
        val miniLayer = FragmentTabletGlassLayer(activity)
        miniFrame = miniLayer
        // Ordinary player content, not FragmentContainerView: follows native sheet translation.
        nativePlayer.addView(miniLayer, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        navigation = surface(topBlur, nativeTabs, true)
        mini = surface(miniBlur, content, false)
        position(checkNotNull(navigation))
        positionMini(checkNotNull(mini))
        root.invalidate()
    }

    private fun activate() {
        if (closed || overlay == null || failed || !config.settings().phoneLiquidGlassEnabled ||
            !eligible() || backdrop?.ready != true ||
            !navigationReady() || !navigationLaidOut()) return
        replacing = true
        stripMiniBackground()
        syncNativeNavigation()
        native.replaceMini(true)
        native.allowOverflow()
        ready()
        root.invalidate()
        ModernXposedRuntime.log("7.0 tablet glass: navigation attached; tabs=${tabs.size}, selected=$selectedId, content=${navContentWidth}x$navContentHeight")
    }

    private fun stripMiniBackground() { native.replaceMini(true) }

    private fun position(surface: Surface): Boolean {
        val origin = IntArray(2).also(root::getLocationOnScreen)
        val point = IntArray(2).also(surface.anchor::getLocationOnScreen)
        val bounds = FragmentTabletGlassPolicy.bounds(origin[0], origin[1], point[0], point[1],
            surface.anchor.width, surface.anchor.height) ?: return false
        val params = surface.glass.layoutParams as FrameLayout.LayoutParams
        if (params.width == bounds.width && params.height == bounds.height &&
            params.leftMargin == bounds.left && params.topMargin == bounds.top) return false
        params.width = bounds.width
        params.height = bounds.height
        params.leftMargin = bounds.left
        params.topMargin = bounds.top
        surface.glass.layoutParams = params
        return true
    }

    private fun positionMini(surface: Surface): Boolean {
        val player = playerRoot ?: return false
        refreshCollapsedMini(surface)
        val collapsed = collapsedMini ?: return false
        val material = FragmentPlayerSurfaceMotion.tabletFrame(slide).expansion
        val inset = player.width * 0.10f * material
        val left = (collapsed.left * (1f - material) + inset).roundToInt()
        val right = ((player.width - collapsed.left - collapsed.width) * (1f - material) + inset).roundToInt()
        val top = (collapsed.top * (1f - material)).roundToInt()
        val height = (collapsed.height + (player.height - collapsed.height) * slide).roundToInt().coerceAtLeast(collapsed.height)
        val width = (player.width - left - right).coerceAtLeast(1)
        return miniFrame?.place(FragmentTabletGlassPolicy.Bounds(left, top, width, height)) == true
    }

    private fun refreshCollapsedMini(surface: Surface) {
        val player = playerRoot ?: return
        if (slide <= 0.001f) {
            val origin = IntArray(2).also(player::getLocationOnScreen)
            val point = IntArray(2).also(surface.anchor::getLocationOnScreen)
            collapsedMini = FragmentTabletGlassPolicy.bounds(origin[0], origin[1], point[0], point[1], surface.anchor.width, surface.anchor.height)
            miniWasVisible = surface.anchor.isShown && surface.content.isShown && opacity(surface.anchor) > 0.001f
        }
    }

    private fun refreshNavigation() {
        val snapshot = surface.navigation.snapshot()
        val state = surface.snapshot()
        val key = snapshot.tabs to state.foregroundColor
        if (navKey != key) {
            navKey = key
            foreground = Color(state.foregroundColor)
            accent = Color(state.accentColor)
            tabs = snapshot.tabs.map { tab -> GlassTab(tab.id, tab.label,
                tab.icon?.constantState?.newDrawable()?.mutate()?.apply { setTint(state.foregroundColor) }, tab.enabled) }
            drawerIcon = dev.amenhancer.glass.GlassSidebarIcon(state.foregroundColor)
        }
        selectedId = snapshot.selectedId ?: View.NO_ID
        navigation?.anchor?.height?.takeIf { it > 0 }?.let { panelHeight = (it / root.resources.displayMetrics.density).dp }
    }

    private fun navigationReady(): Boolean = tabs.size > 1 && tabs.any { it.id == selectedId }

    private fun navigationLaidOut(): Boolean = navigation?.glass?.let {
        val anchor = checkNotNull(navigation).anchor
        !it.isLayoutRequested && !anchor.isLayoutRequested &&
            it.width == anchor.width && it.height == anchor.height &&
            FragmentTabletGlassPolicy.navigationLayoutReady(it.width, it.height, navContentWidth, navContentHeight)
    } == true

    private fun selectTab(id: Int): Int {
        safely {
            surface.navigation.select(id)
            refreshNavigation()
        }
        return selectedId
    }

    private fun syncNativeNavigation(ready: Boolean = navigationReady() && navigationLaidOut()) {
        native.replaceNavigation(ready)
    }

    private fun dispatchNavigation(event: MotionEvent): Boolean {
        val nav = navigation ?: return false
        if (!replacing || !navigationReady() || !navigationLaidOut()) return false
        val point = IntArray(2).also(nav.glass::getLocationOnScreen)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.rawX - point[0]; val y = event.rawY - point[1]
            navGesture = event.downTime.takeIf { nav.glass.contentAlpha > 0.001f &&
                FragmentTabletGlassPolicy.Bounds(0, 0, nav.glass.width, nav.glass.height).contains(x, y) }
        }
        if (navGesture != event.downTime) return false
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation(event.rawX - point[0] - event.x, event.rawY - point[1] - event.y)
        try { nav.glass.dispatchTouchEvent(forwarded) } finally { forwarded.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) navGesture = null
        return true
    }

    fun ownsSheet(sheet: View): Boolean = find(TabletChromeRegion.SHEET) === sheet

    fun onSlide(progress: Float) {
        slide = progress.coerceIn(0f, 1f)
        if (slide > 0.001f) mini?.let(::release)
        root.invalidate()
    }

    private fun updatePlayerLayers(frame: FragmentTabletGlassFrame) { native.playerLayers(frame, slide) }

    private fun transformMini(sx: Float, sy: Float, x: Float, y: Float) {
        if (replacing && slide <= .001f) native.transformMini(sx, sy, x, y)
        else native.transformMini(1f, 1f, 0f, 0f)
    }
    private fun opacity(view: View): Float = native.opacity(view)

    override fun observeMiniTouch(event: MotionEvent): Boolean? {
        if (!replacing) return null
        listOfNotNull(mini).forEach { surface ->
            val point = IntArray(2).also(surface.glass::getLocationOnScreen)
            val x = event.rawX - point[0]
            val y = event.rawY - point[1]
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    release(surface)
                    val hit = surface.glass.alpha > 0.001f && x >= 0f && y >= 0f &&
                        x < surface.glass.width && y < surface.glass.height
                    if (hit && slide <= 0.001f) {
                        surface.pressed = true
                        surface.press.start(event.downTime, event.rawX, event.rawY)
                        surface.input.event(MotionEvent.ACTION_DOWN, x, y)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (surface.pressed) {
                    if (surface.press.move(event.downTime, event.rawX, event.rawY)) {
                        surface.input.event(MotionEvent.ACTION_MOVE, x, y)
                    } else release(surface)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> release(surface)
            }
        }
        // Observation only: the tablet row never consumes the mini's touches.
        return null
    }

    private fun release(surface: Surface) {
        if (surface.pressed) surface.input.event(MotionEvent.ACTION_CANCEL, 0f, 0f)
        surface.pressed = false
        surface.press.finish()
    }

    override fun foreground(value: Boolean) {
        active = value
        navigation?.glass?.foreground(value)
        mini?.glass?.foreground(value)
        if (!value) {
            captureWait.timedOut(0L, false)
            listOfNotNull(navigation, mini).forEach(::release)
            backdrop?.setCaptureEnabled(false)
        } else root.invalidate()
    }

    fun refreshSettings() {
        root.post {
            if (closed) return@post
            safely {
                removeMaterials()
                failed = false
                root.invalidate()
            }
        }
    }

    private fun scheduleRemove() {
        if (activationPending) return
        activationPending = true
        root.post {
            activationPending = false
            if (!closed) safely { removeMaterials() }
        }
    }

    private fun removeMaterials() {
        captureWait.timedOut(0L, false)
        replacing = false; navGesture = null; navContentWidth = 0; navContentHeight = 0
        listOfNotNull(navigation, mini).forEach(::release)
        native.restore()
        miniFrame?.let { (it.parent as? ViewGroup)?.removeView(it) }; miniFrame = null
        playerRoot = null; collapsedMini = null; miniWasVisible = false; glassExpansion = 0f; miniContent = null
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; overlay = null
        navigation = null; mini = null; backdrop?.close(); backdrop = null
    }

    private fun fail(error: Throwable) {
        if (failurePending || closed) return
        failurePending = true
        root.post {
            failurePending = false
            failed = true
            runCatching { removeMaterials() }
            failure(error)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        subscription?.close(); subscription = null
        removeMaterials()
    }

    private fun eligible(): Boolean = surface.tabletChrome != null && root.resources.configuration.screenWidthDp >= 600
    private fun find(region: TabletChromeRegion): View? = native.view(region)

    private fun contains(parent: View, child: View): Boolean =
        generateSequence(child) { it.parent as? View }.any { it === parent }

    @Composable private fun HostConfiguration(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalConfiguration provides configuration,
            LocalDensity provides Density(configuration.densityDpi / 160f, configuration.fontScale),
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content,
        )
    }

    private fun moduleContext(): Context {
        val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
        val resources = activity.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        val isolated = Resources(resources.assets, root.resources.displayMetrics, Configuration(root.resources.configuration))
        val theme = isolated.newTheme().apply {
            applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
        }
        return object : ContextWrapper(activity) {
            override fun getResources(): Resources = isolated
            override fun getAssets() = isolated.assets
            override fun getClassLoader(): ClassLoader = GlassHostView::class.java.classLoader!!
            override fun getTheme(): Resources.Theme = theme
        }
    }
}
