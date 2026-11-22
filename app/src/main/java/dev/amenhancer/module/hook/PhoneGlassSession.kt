package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.ViewBackdrop
import dev.amenhancer.glass.BottomScrim
import dev.amenhancer.glass.GlassGeometry
import dev.amenhancer.glass.GlassHostView
import dev.amenhancer.glass.GlassNavigation
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.glass.GlassTab
import dev.amenhancer.glass.NativeButtonInput
import dev.amenhancer.glass.NativeLiquidButton
import dev.amenhancer.glass.GlassCollapseShapes
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.ModuleSettings
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

@RequiresApi(33)
internal open class PhoneGlassSession(
    val activity: Activity,
    protected val config: TargetConfigClient,
    protected val hostBinding: ChromeHostBinding = AppleMusicHostFactory.bindChrome(activity),
    private val failure: (Throwable) -> Unit,
) : GlassSession, ViewTreeObserver.OnPreDrawListener {
    private val transformOwners = IdentityHashMap<View, MutableMap<String, dev.amenhancer.module.host.OwnedHostProperty<Float>>>()
    private val states = IdentityHashMap<View, NativeViewState>()
    private val layerAlphas = IdentityHashMap<View, NativeLayerAlpha>()
    private val visibleGlassRect = android.graphics.Rect()
    private val visibleGlassLocation = IntArray(2)
    private val windowLocation = IntArray(2)
    private var glassConsumersVisible = false
    private var writingLayerAlpha = false
    protected var navFrame: FrameLayout? = null
    private var navigation: View? = null
    private var source: ViewGroup? = null
    private var backdrop: ViewBackdrop? = null
    protected var navGlass: GlassHostView? = null
    protected var navScrim: GlassHostView? = null
    protected var miniGlass: GlassHostView? = null
    final override var miniRoot: FrameLayout? = null
        private set
    private var miniContent: View? = null
    private var miniForeground: GlassMiniForeground? = null
    final override var playerBehavior: Any? = null
        private set
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var failureScheduled = false
    protected var hostRoot: View? = null
    protected var playerSheet: View? = null
    final override var activated = false
        private set
    private var tabs by mutableStateOf(emptyList<GlassTab>())
    private var selectedId by mutableIntStateOf(View.NO_ID)
    private var accent by mutableStateOf(Color.Red)
    private var foreground by mutableStateOf(Color.Black)
    private var hostConfiguration by mutableStateOf(Configuration(activity.resources.configuration))
    private var menuKey: List<Any?> = emptyList()
    private val input = NativeButtonInput()
    private var slide = 0f
    private var returningToMini = false
    private var foregroundActive = true
    private var miniForegroundEnabled by mutableStateOf(false)
    protected val isCollapsed: Boolean get() = slide <= 0.001f
    protected val glassMenuReady: Boolean get() = tabs.size > 1 && tabs.any { it.id == selectedId }
    private var glassExpansion by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var miniOffsetInSheet = 0
    private var navMarginPx = intArrayOf(0, 0)
    private var miniMarginPx = intArrayOf(0, 0)
    private var slottedMiniContent: View? = null
    private var lastPeek = -1
    private val nativePeek = NativePeekHeight()
    private val overflowClips = dev.amenhancer.module.host.HostClippingLeases<ViewGroup> { view ->
        dev.amenhancer.module.host.OwnedHostClipping(
            { view.clipChildren }, { view.clipChildren = it },
            { view.clipToPadding }, { view.clipToPadding = it },
        )
    }
    private val captureWait = dev.amenhancer.module.host.GlassCaptureWait()
    private val attachHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var behaviorRetries = 0
    private var retryPending = false
    private val retryAttach = Runnable {
        retryPending = false
        if (!closed && !failureScheduled && !activity.isDestroyed && !activity.isFinishing) {
            try { attachAvailableViews() } catch (error: Throwable) { scheduleFailure(error) }
        }
    }
    private var contentDownX = 0f
    private var contentDownY = 0f
    private var observingPress = false
    private var underlap = false
    private var contentInset: Int? = null
    private var expandedContentInset = -1
    private var scanNeeded = true
    private var scrollTargets: List<View> = emptyList()
    private var composeScenes: List<View> = emptyList()
    private var hierarchyDirty = false
    protected var glassSettings = config.settings()
        private set
    private var adaptiveForeground by mutableStateOf(glassSettings.glassAdaptiveForegroundEnabled)
    private val barCollapse = GlassBarCollapseController(activity, hostBinding,
        inputActive = { activated && !closed && isCollapsed }, failure = ::scheduleFailure)
    private val collapseSupported: Boolean get() = !geometry.sideBySide && hostBinding.supportsScrollCollapse
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scanNeeded = true; hierarchyDirty = true; hostBinding.invalidateViews()
    }
    private var nextSettingsCheck = 0L
    protected val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    protected val bottomInset get() = activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    protected open val miniVisible get() = miniRoot?.isShown == true
    // AM++: user-adjustable glass lift/material, captured with the session so every
    // height consumer (frame, content padding, peek) agrees within a frame.
    protected var bottomGapDp = GlassPolicy.BOTTOM_DP
    private var navBlurDp = GlassPolicy.PANEL_BLUR_DP.toInt()
    private var miniBackdropSize by mutableStateOf(IntSize.Zero)
    /** Capsule geometry shared by every occupied-height consumer; a diverging form overrides this. */
    protected open val geometry: GlassGeometry get() = GlassGeometry.Phone

    protected open fun playerFragmentsAlphaFactor(progress: Float, materialProgress: Float): Float = materialProgress

    /**
     * Horizontal slot of a floating capsule as [left, right] margins. The phone
     * keeps the tuned symmetric margins; the tablet row carves asymmetric slots
     * (nav pill left, mini pill right) inside one centered row.
     */
    protected open fun capsuleMarginsPx(frameWidth: Int, mini: Boolean): IntArray {
        val side = dp(geometry.horizontalDp)
        return intArrayOf(side, side)
    }

    // Resource IDs are stable for this Activity's host APK. Keep values and Views live so
    // configuration changes and replaced page/player hierarchies still take effect.
    protected fun resourceId(role: ChromeResource): Int = hostBinding.resourceId(role)
    protected open fun find(role: ChromeResource): View? = hostBinding.find(role)
    protected fun dimen(role: ChromeResource): Int = hostBinding.dimension(role)

    protected fun writeOwnedTransform(view: View, property: String, value: Float) {
        val owner = transformOwners.getOrPut(view) { HashMap() }.getOrPut(property) {
            when (property) {
                "scaleX" -> dev.amenhancer.module.host.OwnedHostProperty({ view.scaleX }, { view.scaleX = it })
                "scaleY" -> dev.amenhancer.module.host.OwnedHostProperty({ view.scaleY }, { view.scaleY = it })
                "translationX" -> dev.amenhancer.module.host.OwnedHostProperty({ view.translationX }, { view.translationX = it })
                "translationY" -> dev.amenhancer.module.host.OwnedHostProperty({ view.translationY }, { view.translationY = it })
                else -> error("Unknown transform property")
            }
        }
        owner.set(value)
    }

    private fun save(view: View): NativeViewState = states.getOrPut(view) { NativeViewState(view) }

    /**
     * Native chrome seams must stay gone under the floating capsule. The phone
     * host carries only the tabs divider; the flat host adds more (see the
     * tablet session). Idempotent compare-then-write, run at activation and on
     * every transition frame, so a late (re)creation by host or installer code
     * cannot resurrect a seam; close() restores the saved states.
     */
    protected open fun suppressNativeChromeSeams() {
        hideSeam(find(ChromeResource.NAVIGATION_TABS_DIVIDER))
        navigation?.let { nav ->
            if (glassMenuReady) {
                // An alpha-hidden native strip still accepts taps across its full width.
                hideSeam(nav)
                nav.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                // A transient unmappable menu needs the native strip and its controls.
                states[nav]?.restoreInteraction(nav)
            }
        }
    }

    protected fun hideSeam(view: View?) {
        view ?: return
        save(view)
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    protected fun clearNativeBackground(view: View?) {
        view ?: return
        save(view)
        if (view.background != null) view.background = null
    }

    private fun overflowAncestors(view: View?): Sequence<ViewGroup> =
        generateSequence(view) { it.parent as? View }
            .takeWhile { it.layoutParams != null }.filterIsInstance<ViewGroup>()

    private fun allowGlassOverflow(view: View) {
        // Ancestors include DecorView: own clipping, never its visibility/alpha/layout.
        overflowAncestors(view).forEach(overflowClips::allowOverflow)
    }

    // Form seams overridden by the dual-pane session; the phone behavior below stays
    // exactly what shipped on the stacked host.
    protected open fun sessionEligible(): Boolean =
        glassSettings.phoneLiquidGlassEnabled && !TabletModeQualifier.isOfficialTablet(activity)

    protected open fun resolveBottomNavigationRoot(): View? = find(ChromeResource.BOTTOM_NAVIGATION_ROOT_STACKED)

    // The stacked native holder (also installed by the tablet dual-pane adaptation)
    // reserves miniplayer_height even when mini is hidden, plus tabs and bottom inset.
    protected open fun nativePeekBaseline(): Int =
        bottomInset + dimen(ChromeResource.NAVIGATION_TABS_HEIGHT) + dimen(ChromeResource.MINIPLAYER_HEIGHT)

    /** Capsule exit driver; the phone host translates the frame from its own holder. */
    protected open fun driveNavFrameExit(progress: Float) = Unit

    protected open val navigationScrimEnabled: Boolean = true

    protected open fun miniMaterialParent(root: FrameLayout): FrameLayout =
        find(ChromeResource.PLAYER_SHEET_CONTAINER) as? FrameLayout ?: root

    protected open fun initialSlide(sheet: View, behavior: Any): Float = hostBinding.sheetSnapshot(behavior).let {
        InitialGlassSlide.resolve(it.state, sheet.top, it.collapsedTop, it.expandedTop)
    }

    /** Host-specific collapse target/layout coordination, before sampling the mini's anchor. */
    protected open fun reconcileCollapsedPlayer(): Boolean = false

    /** Phone keeps its bottom fade inside the native tabs frame. */
    protected open fun attachNavigationScrim(frame: FrameLayout, scrim: GlassHostView) {
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Chrome ownership hand-off; only the dual-pane session arbitrates ownership. */
    protected open fun onGlassOwnership(root: View?) = Unit

    protected open fun releaseGlassOwnership(root: View?) = Unit

    override fun attachAvailableViews() {
        if (closed || failureScheduled) return
        if (!sessionEligible()) {
            close()
            return
        }
        if (navGlass == null) {
            bottomGapDp = ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(glassSettings.phoneLiquidGlassBottomGapDp)
            navBlurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(glassSettings.phoneLiquidGlassPanelBlurDp)
            hostRoot = resolveBottomNavigationRoot() ?: return
            val frame = find(ChromeResource.BOTTOM_NAVIGATION_TABS_FRAME) as? FrameLayout ?: return
            val nav = find(ChromeResource.BOTTOM_NAVIGATION) ?: return
            val content = find(ChromeResource.NAVIGATION_HOST_GROUP) as? ViewGroup ?: return
            check(!isDescendant(frame, content)) { "Backdrop source contains the glass consumer" }
            navFrame = frame
            navigation = nav
            source = content
            playerBehavior = findPlayerBehavior()
            if (playerBehavior == null) {
                if (!retryPending) {
                    check(behaviorRetries < 20) { "Native player behavior not ready after retries" }
                    behaviorRetries++
                    retryPending = true
                    attachHandler.postDelayed(retryAttach, 50L)
                }
                return
            }
            attachHandler.removeCallbacks(retryAttach)
            retryPending = false
            barCollapse.configure(glassSettings, collapseSupported)
            val bg = ViewBackdrop(content, ::scheduleFailure,
                sourceRenderer = { hostBinding.drawContentBackdrop(content, it) }).also { backdrop = it; it.start() }
            refreshMenu()
            // Bottom fade: blurred, washed-out strip under the tabs, matching the
            // reference apps' gradient bar. Added first so the tabs stay on top.
            if (navigationScrimEnabled) {
                val scrim = GlassHostView(moduleContext(), bleedDp = 0).also { navScrim = it }
                scrim.alpha = 0f
                scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                scrim.content { HostConfiguration { BottomScrim(bg) } }
                attachNavigationScrim(frame, scrim)
            }
            val glass = GlassHostView(moduleContext(), traceName = "AM++/nav").also { navGlass = it }
            glass.alpha = 0f
            glass.content { HostConfiguration { GlassNavigation(tabs, selectedId, accent, foreground, bg, ::selectTab, panelBlur = navBlurDp.dp,
                // A null progress keeps legacy and tablet navigation in its expanded layout.
                collapseProgress = if (barCollapse.hasPresentation) ::collapseProgress else null,
                onExpandRequest = barCollapse::expand,
                collapseReboundScale = barCollapse.appearance.collapseReboundScale,
                expandReboundScale = barCollapse.appearance.expandReboundScale,
                adaptiveForeground = adaptiveForeground) } }
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GlassPolicy.NAV_HEIGHT_DP), Gravity.TOP).apply {
                leftMargin = navSlot[0]; rightMargin = navSlot[1]
            })
            observer = activity.window.decorView.viewTreeObserver.also {
                it.addOnPreDrawListener(this)
                it.addOnGlobalLayoutListener(layoutListener)
            }
        }
        barCollapse.attach(activity.window.decorView.viewTreeObserver)
        val root = (find(ChromeResource.MINI_PLAYER) ?: find(ChromeResource.MINI_PLAYER_TOUCH_PANEL)) as? FrameLayout
        val content = root?.findViewById<View>(resourceId(ChromeResource.MINI_PLAYER_CONTENT))
        if (root != null && (root !== miniRoot || content !== miniContent)) {
            miniGlass?.let { (it.parent as? ViewGroup)?.removeView(it) }
            miniForeground?.reset()
            miniRoot?.let { states.remove(it)?.restore(it) }
            miniContent?.let {
                transformOwners.remove(it)?.values?.forEach(AutoCloseable::close)
                states.remove(it)?.restore(it)
            }
            // Restore old NativeViewState first; its clipping baseline may already be false.
            // Drop detached roots/private ancestors, keeping those used by either live surface.
            overflowClips.retainOnly((overflowAncestors(navFrame) + overflowAncestors(root)).asIterable())
            miniRoot = root
            miniContent = content
            miniForeground = GlassMiniForeground(content)
            val bg = backdrop ?: return
            val glass = GlassHostView(moduleContext(), traceName = "AM++/mini").also { miniGlass = it }
            glass.alpha = 0f
            glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            glass.content {
                HostConfiguration {
                    val miniForegroundSink: ((Color?) -> Unit)? = if (miniForegroundEnabled) {
                        { color -> if (miniForegroundEnabled && miniGlass === glass) miniForeground?.apply(color) }
                    } else null
                    val reserveBackdrop by remember { derivedStateOf { barCollapse.isRunning } }
                    NativeLiquidButton(bg, input, glassExpansion, panelBlur = navBlurDp.dp,
                        autoClip = geometry.sideBySide || (barCollapse.hasPresentation && glassExpansion == 0f),
                        miniHeightDp = geometry.miniHeightDp,
                        minLayerSize = if (reserveBackdrop && glassExpansion == 0f) miniBackdropSize else IntSize.Zero,
                        capsuleShape = GlassCollapseShapes.capsule(barCollapse.hasPresentation),
                        onForegroundColor = miniForegroundSink) { sx, sy, x, y ->
                        miniContent?.let { v -> writeOwnedTransform(v, "scaleX", sx); writeOwnedTransform(v, "scaleY", sy); writeOwnedTransform(v, "translationX", x); writeOwnedTransform(v, "translationY", y) }
                    }
                }
            }
            // The native mini container disappears early in the opening animation.
            // Keep the material behind the whole sheet, independent of that container.
            val surfaceParent = miniMaterialParent(root)
            playerSheet = surfaceParent
            surfaceParent.addView(glass, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(geometry.miniHeightDp), Gravity.TOP).apply {
                val slot = capsuleMarginsPx(navFrame?.width ?: 0, mini = true)
                leftMargin = slot[0]; rightMargin = slot[1]
            })
            if (activated) prepareMini()
        }
        states.forEach { (view,state) -> state.captureOwned(view) }
    }

    override fun ownsCurrentHierarchy(): Boolean {
        hostBinding.invalidateViews()
        return !closed && (navFrame == null || find(ChromeResource.BOTTOM_NAVIGATION_TABS_FRAME) === navFrame)
    }

    @androidx.compose.runtime.Composable
    private fun HostConfiguration(content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(
            LocalConfiguration provides hostConfiguration,
            LocalDensity provides Density(hostConfiguration.densityDpi / 160f, hostConfiguration.fontScale),
            LocalLayoutDirection provides if (hostConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content,
        )
    }

    private fun moduleContext(): Context {
        // Package-name lookup is filtered inside the host process. The framework supplies
        // the installed module's ApplicationInfo, including its readable APK paths.
        val info = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
        val apkResources = activity.packageManager.getResourcesForApplication(info)
        @Suppress("DEPRECATION")
        val isolatedResources = Resources(apkResources.assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
        val moduleTheme = isolatedResources.newTheme().apply {
            applyStyle(info.theme.takeIf { it != 0 } ?: android.R.style.Theme_Material_Light_NoActionBar, true)
        }
        return object : ContextWrapper(activity) {
            override fun getResources(): Resources = isolatedResources
            override fun getAssets() = isolatedResources.assets
            override fun getClassLoader(): ClassLoader = GlassHostView::class.java.classLoader!!
            override fun getTheme(): Resources.Theme = moduleTheme
        }
    }

    private fun refreshMenu() {
        if (hostConfiguration != activity.resources.configuration) {
            hostConfiguration = Configuration(activity.resources.configuration)
            // A new configuration re-measures every slot, so the collapsed geometry would be
            // computed against a stale width. Return to the expanded bar and start over.
            barCollapse.expand()
        }
        val nav = navigation ?: return
        val snapshot = hostBinding.navigation(nav)
        val menu = snapshot.menu
        val selected = snapshot.selectedId
        val night = activity.resources.configuration.uiMode and 0x30 == 0x20
        val fg = if (night) AndroidColor.WHITE else AndroidColor.BLACK
        val accentId = resourceId(ChromeResource.COLOR_PRIMARY)
        val hostAccent = if (accentId != 0) activity.getColor(accentId) else 0xfffa233b.toInt()
        val items = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }
        val key = items.flatMap { listOf(it.itemId, it.title?.toString(), it.isEnabled, it.icon) } + listOf(night, hostAccent)
        if (key != menuKey) {
            menuKey = key
            tabs = items.map {
                val icon = it.icon?.constantState?.newDrawable(activity.resources)?.mutate()?.apply { setTint(fg) }
                    ?: it.icon
                GlassTab(it.itemId, it.title?.toString().orEmpty(), icon, it.isEnabled, isSearch = isSearchItem(it.itemId))
            }
            foreground = Color(fg)
            accent = Color(hostAccent)
        }
        if (selectedId != selected) {
            barCollapse.expand()
            selectedId = selected
        }
    }

    /**
     * The legacy host exposes only a [Menu] and its item IDs, so the search destination is
     * identified from the item's own resource entry name. Unreadable or unmatched names
     * report false on purpose: the scroll collapse then keeps every tab inside one capsule
     * instead of detaching a control that cannot be proven to be search.
     */
    private fun isSearchItem(itemId: Int): Boolean =
        runCatching { activity.resources.getResourceEntryName(itemId).contains("search", ignoreCase = true) }
            .getOrDefault(false)

    /** Shared animation read by Compose, the native mini, content padding and peek. */
    protected fun collapseProgress(): Float = barCollapse.progress

    private fun selectTab(id: Int): Int {
        if (tabs.none { it.id == id && it.enabled }) return selectedId
        // Choosing a destination returns the bar to its expanded form: the collapsed capsule
        // shows one glyph, so a selection made from it would otherwise be unverifiable.
        barCollapse.expand()
        runCatching {
            navigation?.let { hostBinding.selectNavigation(it, id) }
            refreshMenu()
        }.onFailure(::scheduleFailure)
        return selectedId
    }

    override fun onPreDraw(): Boolean {
        if (closed || failureScheduled) return true
        states.forEach { (view,state) -> state.observeNative(view) }
        try {
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= nextSettingsCheck) {
                nextSettingsCheck = now + 500
                glassSettings = config.settings()
                adaptiveForeground = glassSettings.glassAdaptiveForegroundEnabled
                if (!sessionEligible()) {
                    activity.window.decorView.post { close() }
                    return true
                }
                barCollapse.configure(glassSettings, collapseSupported)
            }
            if (hierarchyDirty) { hierarchyDirty = false; attachAvailableViews() }
            refreshMenu()
            if (!activated && backdrop?.ready == true && navGlass?.isLaidOut == true && glassMenuReady) {
                activate()
                return false // Layout the new occupied area before exposing either surface.
            }
            if (activated) {
                val menuReady = glassMenuReady
                val navAlpha = if (menuReady) 1f else 0f
                navGlass?.alpha = navAlpha
                navScrim?.alpha = navAlpha
                val navGlassRevealed = menuReady && navGlass?.visibility != View.VISIBLE
                navGlass?.let { glass ->
                    val visibility = if (menuReady) View.VISIBLE else View.GONE
                    if (glass.visibility != visibility) glass.visibility = visibility
                }
                navigation?.alpha = if (menuReady) 0f else 1f
                updateGeometry()
                // Peek is moving while the bar animates; reconcile the native sheet once it settles.
                if (!barCollapse.isRunning && reconcileCollapsedPlayer()) return false
                val sourceNeedsLayout = updateUnderlap()
                val transitionNeedsLayout = updateTransition()
                val waitingForCapture = glassConsumersVisible && backdrop?.ready == false && canRefreshBackdrop()
                if (captureWait.timedOut(now, waitingForCapture)) {
                    scheduleFailure(IllegalStateException("Glass backdrop resume timed out; restoring native drawing"))
                    return true
                }
                // Insets can be reapplied when the native player finishes collapsing.
                // setLayoutParams only schedules layout: do not expose the old, shorter
                // content bounds (and window background beneath them) in this frame.
                if (navGlassRevealed || sourceNeedsLayout || transitionNeedsLayout || waitingForCapture) {
                    if (waitingForCapture) activity.window.decorView.postInvalidateOnAnimation()
                    return false
                }
            } else if (backdrop?.ready == true) {
                // Keep the initial capture for activation, but stop recording while
                // the menu has not produced a visible glass surface yet.
                backdrop?.setCaptureEnabled(
                    backdropConsumerVisible(navGlass) || backdropConsumerVisible(navScrim) ||
                        backdropConsumerVisible(miniGlass),
                )
            }
        } catch (error: Throwable) { scheduleFailure(error) }
        finally { states.forEach { (view,state) -> state.captureOwned(view) } }
        return true
    }

    private fun activate() {
        val nav = navigation ?: return
        val frame = navFrame ?: return
        // First layout may have dispatched its slide callback before this session existed.
        // Read the laid-out native state before changing peek height or hiding any layer.
        val sheet = find(ChromeResource.PLAYER_SHEET_CONTAINER) ?: return
        if (!sheet.isLaidOut) return
        playerSheet = sheet
        val behavior = checkNotNull(playerBehavior)
        slide = initialSlide(sheet, behavior)
        save(nav)
        save(frame)
        nav.alpha = 0f
        nav.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.background = null
        frame.clipChildren = false
        frame.clipToPadding = false
        allowGlassOverflow(frame)
        suppressNativeChromeSeams()
        source?.let { save(it) }
        nativePeek.initialize(nativePeekBaseline())
        activated = true
        prepareMini()
        navGlass?.alpha = 1f
        navScrim?.alpha = 1f
        updateGeometry()
        updateUnderlap()
        updateTransition()
        // The dual-pane boundary sync yields geometry ownership once activation completes.
        onGlassOwnership(hostRoot)
        config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS, FeatureState.ACTIVE,
            "AndroidLiquidGlass 已挂载：实时背景、底栏透镜及迷你播放器；真机视觉验收另行记录", targetBuild(activity).displayName))
    }

    private fun prepareMini() {
        miniRoot?.let(::allowGlassOverflow)
        miniRoot?.let { save(it); it.background = null; it.clipChildren = false; it.clipToPadding = false }
        miniRoot?.let { root ->
            root.layoutParams = root.layoutParams.apply { height = dp(geometry.miniHeightDp) }
        }
        miniContent?.let { content ->
            save(content)
            val params = content.layoutParams
            val contentHeight = dp(minOf(GlassPolicy.MINI_HEIGHT_DP, geometry.miniHeightDp))
            params.height = contentHeight
            if (params is ViewGroup.MarginLayoutParams) {
                val slot = capsuleMarginsPx(navFrame?.width ?: 0, mini = true)
                params.leftMargin = slot[0]; params.rightMargin = slot[1]
                val topOffset = (dp(geometry.miniHeightDp) - contentHeight) / 2
                if (topOffset != 0) params.topMargin = topOffset
            }
            content.layoutParams = params
            listOf(ChromeResource.VIDEO_SURFACE_CONTAINER, ChromeResource.MINI_PLAYER_PLAY_BTN, ChromeResource.MINI_PLAYER_NEXT_BTN).forEach { name ->
                val id = resourceId(name)
                content.findViewById<View>(id)?.let { child ->
                    save(child)
                    child.layoutParams = child.layoutParams.apply {
                        width = dp(32)
                        height = dp(32)
                    }
                }
            }
        }
        listOf(ChromeResource.PLAYER_ROOT, ChromeResource.PLAYER_TOP_SHADOW, ChromeResource.BACKGROUND_LAYERS, ChromeResource.MOTION_SWITCHER, ChromeResource.PLAYER_FRAGMENTS_HOST).mapNotNull(::find).forEach(::save)
    }

    protected fun miniDropPx(): Float = barCollapse.miniDropPx(dp(geometry.miniHeightDp).toFloat())

    private fun updateGeometry() {
        val height = dp(GlassPolicy.NAV_HEIGHT_DP + bottomGapDp) + bottomInset
        navFrame?.let { frame ->
            // A generic copy constructor drops the host ConstraintLayout's bottom anchor.
            if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
            // The capsule slots may follow the host width (see the tablet row);
            // resync every surface whenever a resolved slot edge changes.
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            val expandedMiniSlot = capsuleMarginsPx(frame.width, mini = true)
            // Mini margins never become negative: the parent's width bounds even its rebound.
            // Reserve only during animation and recompute after width/configuration changes.
            val expandedMiniWidth = frame.width - expandedMiniSlot[0] - expandedMiniSlot[1]
            miniBackdropSize = if (barCollapse.hasPresentation && expandedMiniWidth > 0) {
                IntSize(frame.width, dp(geometry.miniHeightDp))
            } else IntSize.Zero
            val miniSlot = barCollapse.miniMargins(expandedMiniSlot, hasDetachedSearch,
                hostConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            fun applySlot(view: View?, slot: IntArray) {
                val surface = view ?: return
                val params = surface.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (params.leftMargin != slot[0] || params.rightMargin != slot[1]) {
                    params.leftMargin = slot[0]; params.rightMargin = slot[1]
                    val nativeRoot = miniRoot
                    if (surface === miniContent && barCollapse.isRunning && isCollapsed &&
                        nativeRoot != null && surface.parent === nativeRoot && nativeRoot.isLaidOut &&
                        nativeRoot.isShown && nativeRoot.width > 0 && nativeRoot.height > 0 &&
                        !nativeRoot.isLayoutRequested && !surface.isLayoutRequested) {
                        // Only this native mini subtree changes width. Measuring its existing
                        // FrameLayout locally keeps the host page out of the animation layout.
                        surface.forceLayout()
                        nativeRoot.forceLayout()
                        nativeRoot.measure(View.MeasureSpec.makeMeasureSpec(nativeRoot.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(nativeRoot.height, View.MeasureSpec.EXACTLY))
                        nativeRoot.layout(nativeRoot.left, nativeRoot.top, nativeRoot.right, nativeRoot.bottom)
                        nativeRoot.invalidate()
                    } else surface.layoutParams = params
                }
            }
            val slotsChanged = !navSlot.contentEquals(navMarginPx) || !miniSlot.contentEquals(miniMarginPx)
            if (slotsChanged) {
                navMarginPx = navSlot
                miniMarginPx = miniSlot
                applySlot(navGlass, navSlot)
                applySlot(miniGlass, miniSlot)
            }
            // A new mini_player can arrive without a frame-width change. Its native
            // content must still occupy the cached slot, without resetting the
            // animated miniGlass margins on every transition frame.
            if (slotsChanged || slottedMiniContent !== miniContent) {
                applySlot(miniContent, miniSlot)
                slottedMiniContent = miniContent
            }
        }
        val peek = peekHeight()
        if (lastPeek != peek) {
            lastPeek = peek
            writePeek(peek)
        }
    }

    /**
     * True only when the current menu offers a detachable search tab, i.e. search is the last
     * tab and detaching it still leaves a real tab strip behind. Mirrors
     * [NavigationSnapshot.searchTab] for the legacy menu path, which builds its own tab list.
     */
    private val hasDetachedSearch: Boolean
        get() = tabs.size > 2 && tabs.last().isSearch

    override fun peekHeight(): Int =
        GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry,
            collapse = barCollapse.progress) + if (miniVisible) dimen(ChromeResource.SHADOW_HEIGHT) else 0

    override fun observeNativePeek(height: Int) = nativePeek.observe(height)

    private fun writePeek(height: Int) = nativePeek.writeByModule {
        playerBehavior?.let { hostBinding.writePeek(it, height) }
    }

    private fun updateUnderlap(): Boolean {
        val root = source ?: return false
        var sourceNeedsLayout = false
        // 1586 applies the navigation-bar inset as a margin on this source, even
        // in an edge-to-edge window. Extend the scene, not the controls, beneath
        // the gesture area. Leave larger margins (e.g. IME avoidance) untouched.
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (bottomInset > 0 && params.bottomMargin == bottomInset) {
                save(root)
                params.bottomMargin = 0
                root.layoutParams = params
                sourceNeedsLayout = true
            }
        }
        if (scanNeeded) {
            scanNeeded = false
            val entries = states.entries.iterator()
            while (entries.hasNext()) {
                val (view, state) = entries.next()
                if (state.scrollPadding && !isDescendant(view, root)) {
                    state.restoreScroll(view)
                    entries.remove()
                }
            }
            val targets = ArrayList<View>()
            val scenes = ArrayList<View>()
            val pending = ArrayDeque<ViewGroup>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                val parent = pending.removeLast()
                for (index in 0 until parent.childCount) {
                    val view = parent.getChildAt(index)
                    // Compose owns its virtual children; native list rows cannot introduce
                    // another outer scroll target. Do not visit recycled cells on each layout.
                    // Keep hidden Compose scenes registered: visibility can change without layout.
                    if (hostBinding.isComposeScene(view)) {
                        scenes.add(view)
                    } else if (view.isShown && view.height > 0 && view.height >= root.height / 2 &&
                        !isViewPagerPageHost(view) && hostBinding.isScrollContainer(view)) {
                        targets.add(view)
                    } else if (view is ViewGroup) {
                        pending.add(view)
                    }
                }
            }
            scrollTargets = targets
            composeScenes = scenes
            barCollapse.updateTargets(scrollTargets,
                composeScenes.mapNotNull(hostBinding::verticalScrollTouchTarget).distinct())
        }
        val terminal = scrollTargets
        // A view that stopped being a target (for example the pager RecyclerView we no
        // longer pad) keeps its old padding until we release it here.
        states.entries.forEach { (view, state) ->
            if (state.scrollPaddingActive && terminal.none { it === view }) {
                state.restoreScroll(view)
                state.scrollPaddingActive = false
            }
        }
        // Library / New / parts of Search are Compose scenes in 1586. They do
        // not expose RecyclerView children. Padding their View viewport removes
        // the very pixels the backdrop needs; their own content owns scrolling.
        val occupied = if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry, collapse = barCollapse.progress) else 0
        val expanded = if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry) else 0
        if (expandedContentInset != expanded) {
            expandedContentInset = expanded
            contentInset = null
        }
        // Off the end of a long list, bottom padding is outside the visible content.
        // Commit it once the spring settles; short/end/unknown targets stay live.
        val deferContentInset = barCollapse.isRunning && isCollapsed && navFrame?.isShown == true &&
            terminal.isNotEmpty() && terminal.all {
                states[it]?.scrollPaddingActive == true && hostBinding.canScrollVertically(it, 1) == true
            }
        val layoutOccupied = if (deferContentInset) contentInset ?: occupied else occupied
        contentInset = layoutOccupied
        val composeSceneVisible = composeScenes.any { it.isShown && it.height > 0 }
        if (terminal.isEmpty() && !composeSceneVisible) {
            underlap = false
            if (root.paddingBottom != occupied) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, occupied)
            return sourceNeedsLayout
        }
        underlap = true
        if (root.paddingBottom != 0) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
        if (root.clipToPadding) root.clipToPadding = false
        terminal.forEach { view ->
            val initial = save(view).also { it.scrollPadding = true; it.scrollPaddingActive = true }
            val desired = initial.bottomPadding + layoutOccupied
            if (view.paddingBottom != desired) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desired)
            // RecyclerView requests layout even when setClipToPadding receives
            // the current value. Writing it on each pre-draw keeps the window
            // laying out and recording the backdrop while the list is idle.
            if (view is ViewGroup && view.clipToPadding) view.clipToPadding = false
        }
        return sourceNeedsLayout
    }

    private fun updateTransition(): Boolean {
        suppressNativeChromeSeams()
        navFrame?.background = null
        // Keep Z ordering (also used for touch dispatch); remove only the old
        // rectangular shadow outline, not the navigation view's elevation.
        navFrame?.let { if (it.outlineProvider != null) it.outlineProvider = null }
        miniRoot?.background = null
        val progress = slide.coerceIn(0f, 1f)
        driveNavFrameExit(progress)
        fun blend(start: Float, end: Float): Float {
            val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        val materialProgress = blend(0f, 0.35f)
        glassExpansion = materialProgress
        val miniAlpha = if (!miniVisible && progress == 0f) 0f else 1f - blend(0.35f, 0.6f)
        val hideTransparentMiniGlass = miniAlpha <= 0f
        updateMiniForeground(adaptiveForeground && foregroundActive && isCollapsed && miniVisible && backdrop?.ready == true)
        var revivedMiniGlass = false
        miniGlass?.let { glass ->
            if (!hideTransparentMiniGlass) {
                val sheet = glass.parent as? FrameLayout
                if (sheet != null && sheet !== miniRoot) {
                    if (miniVisible && progress == 0f) {
                        val miniPosition = IntArray(2).also { miniRoot?.getLocationInWindow(it) }
                        val sheetPosition = IntArray(2).also(sheet::getLocationInWindow)
                        miniOffsetInSheet = miniPosition[1] - sheetPosition[1]
                    }
                    // Keep the tablet's original capsule anchor, but stop the
                    // horizontal morph at 80% of the sheet width. This reduces
                    // the fading surface area without changing its timing.
                    val sideInset = if (geometry.sideBySide) sheet.width * 0.10f * materialProgress else 0f
                    // The cached mini slot already carries the scroll collapse, so the
                    // material follows the native content into the collapsed row. Both are
                    // multiplied by the player's own morph so opening the player returns the
                    // row to its expanded geometry smoothly.
                    val left = (miniMarginPx[0] * (1f - materialProgress) + sideInset).roundToInt()
                    val right = (miniMarginPx[1] * (1f - materialProgress) + sideInset).roundToInt()
                    // The native position already includes miniDropPx; do not apply it twice.
                    val top = (miniOffsetInSheet * (1f - materialProgress)).roundToInt()
                    val collapsedHeight = dp(geometry.miniHeightDp)
                    val height = (collapsedHeight + (sheet.height - collapsedHeight) * progress).roundToInt().coerceAtLeast(collapsedHeight)
                    val params = glass.layoutParams as FrameLayout.LayoutParams
                    if (params.height != height || params.topMargin != top || params.leftMargin != left || params.rightMargin != right) {
                        params.height = height; params.topMargin = top
                        params.leftMargin = left; params.rightMargin = right
                        glass.layoutParams = params
                    }
                }
            }
            if (glass.alpha != miniAlpha) glass.alpha = miniAlpha
            val visibility = if (hideTransparentMiniGlass) View.GONE else View.VISIBLE
            if (glass.visibility != visibility) {
                revivedMiniGlass = visibility == View.VISIBLE
                glass.visibility = visibility
            }
        }
        find(ChromeResource.PLAYER_SHEET_CONTAINER)?.let { v ->
            val original = save(v)
            val desired = if (progress == 0f) null else original.outlineProvider
            if (v.outlineProvider !== desired) v.outlineProvider = desired
        }
        listOf(ChromeResource.PLAYER_TOP_SHADOW, ChromeResource.BACKGROUND_LAYERS).mapNotNull(::find).forEach { v ->
            applyLayerAlpha(v, materialProgress)
        }
        find(ChromeResource.PLAYER_FRAGMENTS_HOST)?.let { v ->
            applyLayerAlpha(v, playerFragmentsAlphaFactor(progress, materialProgress))
        }
        // The motion subtree includes rectangular legibility/blur overlays and can
        // still have thumbnail-sized bounds early in the native transition. Reveal
        // it only after the glass has faded and the opaque player background is back.
        find(ChromeResource.MOTION_SWITCHER)?.let { v ->
            applyLayerAlpha(v, blend(0.6f, 0.85f))
        }
        find(ChromeResource.PLAYER_ROOT)?.background = if (materialProgress < 1f) null else states[find(ChromeResource.PLAYER_ROOT)]?.background
        // The flat tablet row is parked offscreen at 60%; the stacked phone row
        // moves under native control, so test its actual window bounds instead.
        val consumerVisible = !(geometry.sideBySide && progress >= 0.6f) &&
            ((revivedMiniGlass && miniAlpha > 0f) ||
                backdropConsumerVisible(navGlass) || backdropConsumerVisible(navScrim) ||
                backdropConsumerVisible(miniGlass))
        glassConsumersVisible = consumerVisible
        // On the way back, capture the source while native player content still
        // covers the screen. The mini glass stays GONE until the original 60% boundary.
        val prewarmCapture = !consumerVisible && returningToMini && progress in 0.6f..0.85f
        val captureResumed = backdrop?.setCaptureEnabled(consumerVisible || prewarmCapture) == true
        return revivedMiniGlass || (consumerVisible && captureResumed)
    }

    private fun backdropConsumerVisible(view: View?): Boolean {
        if (view == null || !view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return false
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.alpha <= 0f) return false
            ancestor = ancestor.parent as? View
        }
        if (!view.getGlobalVisibleRect(visibleGlassRect)) return false
        // clipChildren=false can report an offscreen row as globally visible.
        // Its translated screen bounds must still intersect this window.
        view.getLocationOnScreen(visibleGlassLocation)
        val window = activity.window.decorView
        window.getLocationOnScreen(windowLocation)
        return visibleGlassLocation[0] < windowLocation[0] + window.width &&
            visibleGlassLocation[0] + view.width > windowLocation[0] &&
            visibleGlassLocation[1] < windowLocation[1] + window.height &&
            visibleGlassLocation[1] + view.height > windowLocation[1]
    }

    private fun canRefreshBackdrop(): Boolean = source?.let {
        it.isAttachedToWindow && it.width > 0 && it.height > 0
    } == true

    override fun onSlide(progress: Float) {
        val next = progress.coerceIn(0f, 1f)
        if (next < slide - 0.001f) returningToMini = true
        else if (next > slide + 0.001f) returningToMini = false
        slide = next
    }

    override fun redirectedLayerAlpha(view: Any?, alpha: Float): Float? {
        if (closed || writingLayerAlpha) return null
        return layerAlphas[view]?.hostWrite(alpha)
    }

    private fun applyLayerAlpha(view: View, factor: Float) {
        save(view)
        val state = layerAlphas.getOrPut(view) { NativeLayerAlpha(view.alpha) }
        state.factor = factor
        if (view.alpha != state.effective) {
            writingLayerAlpha = true
            try { view.alpha = state.effective } finally { writingLayerAlpha = false }
        }
    }

    override fun redirectedPadding(view: Any?): Int? = if (activated && view === source) {
        if (underlap) 0 else if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry, collapse = barCollapse.progress) else 0
    } else null

    // Only the tablet form narrows the full-width host touch surfaces.
    override fun shouldPassThroughTouch(view: View, event: MotionEvent): Boolean = false

    override fun shouldBypassPlayerIntercept(event: MotionEvent): Boolean = false

    /** The phone mini stays native; the tablet form separately narrows its host touch surface. */
    override fun dispatchCollapsedMiniTouch(view: View, event: MotionEvent): Boolean? = null

    private fun miniGlassPosition(event: MotionEvent): Pair<Float, Float>? {
        val glass = miniGlass ?: return null
        if (glass.width <= 0 || glass.height <= 0) return null
        val location = IntArray(2)
        glass.getLocationOnScreen(location)
        return (
            (event.rawX - location[0]).coerceIn(0f, glass.width.toFloat()) to
                (event.rawY - location[1]).coerceIn(0f, glass.height.toFloat())
            )
    }

    /** Observes one root event; the host still owns dispatch and every native mini control. */
    open fun observeContentTouch(event: MotionEvent): Boolean? {
        barCollapse.observeTouch(event) {
            GlassBarTouchRegions.mini(event, barCollapse.progress, miniRoot, miniContent, miniGlass) ||
                GlassBarTouchRegions.navigation(event, navGlass, barCollapse.progress, density,
                    hostConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL, tabs.size,
                    hasDetachedSearch, barCollapse.appearance)
        }
        return null
    }

    override fun observeTouch(event: MotionEvent) {
        if (!activated) return
        val position = miniGlassPosition(event) ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { contentDownX = event.x; contentDownY = event.y; observingPress = true }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - contentDownY) > ViewConfiguration.get(activity).scaledTouchSlop && abs(event.y - contentDownY) > abs(event.x - contentDownX)) {
                if (observingPress) input.event(MotionEvent.ACTION_CANCEL, position.first, position.second)
                observingPress = false
            }
        }
        if (observingPress) input.event(event.actionMasked, position.first, position.second)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) observingPress = false
    }

    override fun foreground(active: Boolean) {
        foregroundActive = active
        if (active) {
            glassSettings = config.settings()
            adaptiveForeground = glassSettings.glassAdaptiveForegroundEnabled
            nextSettingsCheck = android.os.SystemClock.uptimeMillis() + 500L
            contentInset = null
            hierarchyDirty = true
            scanNeeded = true
        }
        if (!active) {
            updateMiniForeground(false)
            captureWait.timedOut(0L, false)
            barCollapse.resetInput()
        }
        navGlass?.foreground(active); navScrim?.foreground(active); miniGlass?.foreground(active)
    }

    private fun updateMiniForeground(enabled: Boolean) {
        if (miniForegroundEnabled != enabled) {
            miniForegroundEnabled = enabled
            if (!enabled) miniForeground?.reset()
        }
        if (enabled) miniForeground?.refresh()
    }

    protected open fun findPlayerBehavior(): Any? = hostBinding.playerBehavior(false)

    private fun scheduleFailure(error: Throwable) {
        if (failureScheduled || closed) return
        failureScheduled = true
        releaseGlassOwnership(hostRoot)
        activity.window.decorView.post { failure(error) }
    }

    override fun close() {
        if (closed) return
        closed = true
        updateMiniForeground(false)
        activated = false
        playerSheet = null
        attachHandler.removeCallbacks(retryAttach)
        retryPending = false
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        barCollapse.close()
        scrollTargets = emptyList()
        composeScenes = emptyList()
        backdrop?.close()
        listOfNotNull(navGlass, navScrim, miniGlass).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        miniForeground = null
        states.forEach { (view, state) -> state.restore(view) }
        states.clear()
        overflowClips.close()
        transformOwners.values.forEach { it.values.forEach(AutoCloseable::close) }
        transformOwners.clear()
        layerAlphas.forEach { (view, state) -> view.alpha = state.native }
        layerAlphas.clear()
        nativePeek.latest?.let { runCatching { writePeek(it) } }
        releaseGlassOwnership(hostRoot)
        hostBinding.close()
        activity.window.decorView.requestLayout()
    }

    private fun isDescendant(child: View, parent: View) = generateSequence(child.parent) { it.parent }.any { it === parent }

    /** ViewPager2 hosts its pages in an internal RecyclerView. Padding that RecyclerView
     * shrinks every page instead of adding scroll space, so the page content stops above
     * the glass and the bar samples empty background. Pad the lists inside the pages. */
    private fun isViewPagerPageHost(view: View): Boolean = hostBinding.isPagerPageHost(view)

    private class NativeViewState(view: View) {
        private val currentValues = HashMap<String,Any?>(24)
        private val ownership = dev.amenhancer.module.host.OwnedHostState(snapshot(view))
        val background get() = ownership.nativeValue("background") as? android.graphics.drawable.Drawable
        val bottomPadding get() = ownership.nativeValue("paddingBottom") as Int
        val outlineProvider get() = ownership.nativeValue("outline") as? android.view.ViewOutlineProvider
        var scrollPadding = false
        var scrollPaddingActive = false
        fun observeNative(view: View) = ownership.observeNative(snapshot(view))
        fun captureOwned(view: View) = ownership.captureOwned(snapshot(view))
        fun restoreInteraction(view: View) {
            view.visibility = ownership.nativeValue("visibility") as Int
            view.importantForAccessibility = ownership.nativeValue("accessibility") as Int
        }
        fun restoreScroll(view: View) {
            val restored=ownership.restoreValues(snapshot(view))
            view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,restored.getValue("paddingBottom") as Int)
            if (view is ViewGroup) view.clipToPadding=restored.getValue("clipPadding") as Boolean
        }
        fun restore(view: View) {
            val values=ownership.restoreValues(snapshot(view))
            view.background=values["background"] as? android.graphics.drawable.Drawable
            view.alpha=values.getValue("alpha") as Float
            view.visibility=values.getValue("visibility") as Int
            view.importantForAccessibility=values.getValue("accessibility") as Int
            view.outlineProvider=values["outline"] as? android.view.ViewOutlineProvider
            val params=view.layoutParams
            params.width=values.getValue("width") as Int; params.height=values.getValue("height") as Int
            if (params is ViewGroup.MarginLayoutParams) params.setMargins(values.getValue("leftMargin") as Int,
                values.getValue("topMargin") as Int,values.getValue("rightMargin") as Int,values.getValue("bottomMargin") as Int)
            view.layoutParams=params
            view.setPadding(values.getValue("paddingLeft") as Int,values.getValue("paddingTop") as Int,
                values.getValue("paddingRight") as Int,values.getValue("paddingBottom") as Int)
            if (view is ViewGroup) { view.clipChildren=values.getValue("clipChildren") as Boolean;view.clipToPadding=values.getValue("clipPadding") as Boolean }
        }
        private fun snapshot(view: View): Map<String,Any?> = currentValues.apply {
            put("background",view.background);put("alpha",view.alpha);put("visibility",view.visibility)
            put("accessibility",view.importantForAccessibility);put("outline",view.outlineProvider)
            put("width",view.layoutParams.width);put("height",view.layoutParams.height)
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                put("leftMargin",it.leftMargin);put("topMargin",it.topMargin);put("rightMargin",it.rightMargin);put("bottomMargin",it.bottomMargin)
            }
            put("paddingLeft",view.paddingLeft);put("paddingTop",view.paddingTop);put("paddingRight",view.paddingRight);put("paddingBottom",view.paddingBottom)
            (view as? ViewGroup)?.let { put("clipChildren",it.clipChildren);put("clipPadding",it.clipToPadding) }
        }
    }
}
