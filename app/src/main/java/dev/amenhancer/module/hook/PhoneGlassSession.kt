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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
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
    protected val activity: Activity,
    protected val config: TargetConfigClient,
    private val failure: (Throwable) -> Unit,
) : GlassSession, ViewTreeObserver.OnPreDrawListener {
    private val states = IdentityHashMap<View, NativeViewState>()
    private val layerAlphas = IdentityHashMap<View, NativeLayerAlpha>()
    private var writingLayerAlpha = false
    protected var navFrame: FrameLayout? = null
    private var navigation: View? = null
    private var source: ViewGroup? = null
    private var backdrop: ViewBackdrop? = null
    private var navGlass: GlassHostView? = null
    private var navScrim: GlassHostView? = null
    private var miniGlass: GlassHostView? = null
    final override var miniRoot: FrameLayout? = null
        private set
    private var miniContent: View? = null
    final override var playerBehavior: Any? = null
        private set
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var failureScheduled = false
    protected var hostRoot: View? = null
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
    private var glassExpansion by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var miniOffsetInSheet = 0
    private var navMarginPx = intArrayOf(0, 0)
    private var miniMarginPx = intArrayOf(0, 0)
    private var lastPeek = -1
    private val nativePeek = NativePeekHeight()
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
    private var scanNeeded = true
    private var scrollTargets: List<View> = emptyList()
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanNeeded = true }
    private var nextSettingsCheck = 0L
    protected val density get() = activity.resources.displayMetrics.density
    protected fun dp(value: Int) = (value * density).roundToInt()
    protected val bottomInset get() = activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    protected val miniVisible get() = miniRoot?.isShown == true
    // AM++: user-adjustable glass lift/material, captured with the session so every
    // height consumer (frame, content padding, peek) agrees within a frame.
    protected var bottomGapDp = GlassPolicy.BOTTOM_DP
    private var navBlurDp = GlassPolicy.PANEL_BLUR_DP.toInt()

    /** Capsule geometry shared by every occupied-height consumer; a diverging form overrides this. */
    protected open val geometry: GlassGeometry get() = GlassGeometry.Phone

    /**
     * Horizontal slot of a floating capsule as [left, right] margins. The phone
     * keeps the tuned symmetric margins; the tablet row carves asymmetric slots
     * (nav pill left, mini pill right) inside one centered row.
     */
    protected open fun capsuleMarginsPx(frameWidth: Int, mini: Boolean): IntArray {
        val side = dp(geometry.horizontalDp)
        return intArrayOf(side, side)
    }

    /** Tablet-only fallback: the side-by-side mini drives its own tap-to-expand. */
    protected open fun armMiniTap(content: View) = Unit

    /**
     * Side-by-side rows keep the sheet's drag capture inside the mini capsule: the
     * collapsed band spans the full width, so the native sheet Behavior treats the
     * empty side areas as part of the drag handle. The tablet form publishes that band
     * and its capsule slots to the row gesture gate; the phone form keeps the native
     * touch chain untouched, so this is a no-op there.
     */
    protected open fun updateRowGestureOwnership(frameWidth: Int, atRest: Boolean) = Unit

    /**
     * Expand driver for the armed mini tap. The host's R8 renames material's
     * BottomSheetBehavior API (state field G, state setter G(int) — verified in
     * the decompiled PlayerBottomSheetBehavior), so drive the renamed setter
     * with 3 = STATE_EXPANDED exactly like the native expand path.
     */
    protected fun expandPlayer() {
        val behavior = playerBehavior ?: return
        runCatching {
            val base = activity.classLoader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior")
            PhoneGlassRuntime.method(base, "G", Int::class.javaPrimitiveType!!).invoke(behavior, 3)
        }
    }

    // Resource IDs are stable for this Activity's host APK. Keep values and Views live so
    // configuration changes and replaced page/player hierarchies still take effect.
    private val resourceIds = HashMap<String, Int>()

    protected fun resourceId(name: String, type: String): Int {
        val key = "$type/$name"
        resourceIds[key]?.let { return it }
        val id = activity.resources.getIdentifier(name, type, ModuleConstants.TARGET_PACKAGE)
        if (id != 0) resourceIds[key] = id
        return id
    }

    protected fun find(name: String): View? = resourceId(name, "id")
        .takeIf { it != 0 }?.let { activity.findViewById(it) }

    protected fun dimen(name: String): Int = resourceId(name, "dimen")
        .takeIf { it != 0 }?.let { activity.resources.getDimensionPixelSize(it) } ?: 0

    private fun save(view: View): NativeViewState = states.getOrPut(view) { NativeViewState(view) }

    /**
     * Native chrome seams must stay gone under the floating capsule. The phone
     * host carries only the tabs divider; the flat host adds more (see the
     * tablet session). Idempotent compare-then-write, run at activation and on
     * every transition frame, so a late (re)creation by host or installer code
     * cannot resurrect a seam; close() restores the saved states.
     */
    protected open fun suppressNativeChromeSeams() {
        hideSeam(find("navigation_tabs_divider"))
        // The native tab strip stays alpha-hidden but touchable across its full
        // width; invisible taps must never select a native menu item, so seam
        // suppression owns its visibility too (restored on close like any seam).
        hideSeam(navigation)
    }

    protected fun hideSeam(view: View?) {
        view ?: return
        save(view)
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun allowGlassOverflow(view: View) {
        generateSequence(view as View?) { it.parent as? View }.takeWhile { it.layoutParams != null }.forEach {
            if (it is ViewGroup) {
                save(it)
                it.clipChildren = false
                it.clipToPadding = false
            }
        }
    }

    // Form seams overridden by the dual-pane session; the phone behavior below stays
    // exactly what shipped on the stacked host.
    protected open fun sessionEligible(): Boolean =
        config.settings().phoneLiquidGlassEnabled && !TabletModeQualifier.isOfficialTablet(activity)

    protected open fun resolveBottomNavigationRoot(): View? = find("bottom_navigation_root_stacked")

    // The stacked native holder reserves miniplayer_height even when mini is hidden,
    // on top of the tabs height and the bottom inset.
    protected open fun nativePeekBaseline(): Int =
        bottomInset + dimen("navigation_tabs_height") + dimen("miniplayer_height")

    /** Capsule exit driver; the phone host translates the frame from its own holder. */
    protected open fun driveNavFrameExit(progress: Float) = Unit

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
            val glassSettings = config.settings()
            bottomGapDp = ModuleSettings.normalizePhoneLiquidGlassBottomGapDp(glassSettings.phoneLiquidGlassBottomGapDp)
            navBlurDp = ModuleSettings.normalizePhoneLiquidGlassPanelBlurDp(glassSettings.phoneLiquidGlassPanelBlurDp)
            hostRoot = resolveBottomNavigationRoot() ?: return
            val frame = find("bottom_navigation_tabs_frame") as? FrameLayout ?: return
            val nav = find("bottom_navigation") ?: return
            val content = find("navigation_host_group") as? ViewGroup ?: return
            check(!isDescendant(frame, content)) { "Backdrop source contains the glass consumer" }
            navFrame = frame
            navigation = nav
            source = content
            playerBehavior = findPlayerBehavior()
            if (playerBehavior == null) {
                if (!retryPending) {
                    check(behaviorRetries < 20) { "1586 player behavior not ready after retries" }
                    behaviorRetries++
                    retryPending = true
                    attachHandler.postDelayed(retryAttach, 50L)
                }
                return
            }
            attachHandler.removeCallbacks(retryAttach)
            retryPending = false
            val bg = ViewBackdrop(content, ::scheduleFailure).also { backdrop = it; it.start() }
            refreshMenu()
            // Bottom fade: blurred, washed-out strip under the tabs, matching the
            // reference apps' gradient bar. Added first so the tabs stay on top.
            val scrim = GlassHostView(moduleContext(), bleedDp = 0).also { navScrim = it }
            scrim.alpha = 0f
            scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            scrim.content { HostConfiguration { BottomScrim(bg) } }
            frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val glass = GlassHostView(moduleContext()).also { navGlass = it }
            glass.alpha = 0f
            glass.content { HostConfiguration { GlassNavigation(tabs, selectedId, accent, foreground, bg, ::selectTab, panelBlur = navBlurDp.dp) } }
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GlassPolicy.NAV_HEIGHT_DP), Gravity.TOP).apply {
                leftMargin = navSlot[0]; rightMargin = navSlot[1]
            })
            observer = activity.window.decorView.viewTreeObserver.also { it.addOnPreDrawListener(this); it.addOnGlobalLayoutListener(layoutListener) }
        }
        val root = (find("mini_player") ?: find("mini_player_touch_panel")) as? FrameLayout
        if (root != null && root !== miniRoot) {
            miniGlass?.let { (it.parent as? ViewGroup)?.removeView(it) }
            miniRoot?.let { states.remove(it)?.restore(it) }
            miniContent?.let { states.remove(it)?.restore(it) }
            miniRoot = root
            miniContent = root.findViewById(activity.resources.getIdentifier("mini_player_content", "id", ModuleConstants.TARGET_PACKAGE))
            val bg = backdrop ?: return
            val glass = GlassHostView(moduleContext()).also { miniGlass = it }
            glass.alpha = 0f
            glass.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            glass.content {
                HostConfiguration {
                    NativeLiquidButton(bg, input, glassExpansion, panelBlur = navBlurDp.dp) { sx, sy, x, y ->
                        miniContent?.let { v -> v.scaleX = sx; v.scaleY = sy; v.translationX = x; v.translationY = y }
                    }
                }
            }
            // The native mini container disappears early in the opening animation.
            // Keep the material behind the whole sheet, independent of that container.
            val surfaceParent = find("player_sheet_container") as? FrameLayout ?: root
            surfaceParent.addView(glass, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(geometry.miniHeightDp), Gravity.TOP).apply {
                val slot = capsuleMarginsPx(0, mini = true)
                leftMargin = slot[0]; rightMargin = slot[1]
            })
            if (activated) prepareMini()
        }
    }

    override fun ownsCurrentHierarchy(): Boolean = !closed && (navFrame == null || find("bottom_navigation_tabs_frame") === navFrame)

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
        if (hostConfiguration != activity.resources.configuration) hostConfiguration = Configuration(activity.resources.configuration)
        val nav = navigation ?: return
        val menu = ModernXposedRuntime.callMethod(nav, "getMenu") as Menu
        val selected = (ModernXposedRuntime.callMethod(nav, "getSelectedItemId") as Number).toInt()
        val night = activity.resources.configuration.uiMode and 0x30 == 0x20
        val fg = if (night) AndroidColor.WHITE else AndroidColor.BLACK
        val accentId = resourceId("color_primary", "color")
        val hostAccent = if (accentId != 0) activity.getColor(accentId) else 0xfffa233b.toInt()
        val items = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }
        val key = items.flatMap { listOf(it.itemId, it.title?.toString(), it.isEnabled, it.icon) } + listOf(night, hostAccent)
        if (key != menuKey) {
            menuKey = key
            tabs = items.map {
                val icon = it.icon?.constantState?.newDrawable(activity.resources)?.mutate()?.apply { setTint(fg) }
                    ?: it.icon
                GlassTab(it.itemId, it.title?.toString().orEmpty(), icon, it.isEnabled)
            }
            foreground = Color(fg)
            accent = Color(hostAccent)
        }
        if (selectedId != selected) selectedId = selected
    }

    private fun selectTab(id: Int): Int {
        if (tabs.none { it.id == id && it.enabled }) return selectedId
        runCatching {
            navigation?.let { ModernXposedRuntime.callMethod(it, "setSelectedItemId", id) }
            refreshMenu()
        }.onFailure(::scheduleFailure)
        return selectedId
    }

    override fun onPreDraw(): Boolean {
        if (closed || failureScheduled) return true
        try {
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= nextSettingsCheck) {
                nextSettingsCheck = now + 500
                if (!sessionEligible()) {
                    activity.window.decorView.post { close() }
                    return true
                }
            }
            refreshMenu()
            if (!activated && backdrop?.ready == true && navGlass?.isLaidOut == true && tabs.size > 1 && tabs.any { it.id == selectedId }) {
                activate()
                return false // Layout the new occupied area before exposing either surface.
            }
            if (activated) {
                val menuReady = tabs.size > 1 && tabs.any { it.id == selectedId }
                val navAlpha = if (menuReady) 1f else 0f
                navGlass?.alpha = navAlpha
                navScrim?.alpha = navAlpha
                navigation?.alpha = if (menuReady) 0f else 1f
                updateGeometry()
                val sourceNeedsLayout = updateUnderlap()
                updateTransition()
                // Insets can be reapplied when the native player finishes collapsing.
                // setLayoutParams only schedules layout: do not expose the old, shorter
                // content bounds (and window background beneath them) in this frame.
                if (sourceNeedsLayout) return false
            }
        } catch (error: Throwable) { scheduleFailure(error) }
        return true
    }

    private fun activate() {
        val nav = navigation ?: return
        val frame = navFrame ?: return
        // First layout may have dispatched its slide callback before this session existed.
        // Read the laid-out native state before changing peek height or hiding any layer.
        val sheet = find("player_sheet_container") ?: return
        if (!sheet.isLaidOut) return
        val behavior = checkNotNull(playerBehavior)
        val base = activity.classLoader.loadClass("com.google.android.material.bottomsheet.BottomSheetBehavior")
        val state = base.getDeclaredField("G").apply { isAccessible = true }.getInt(behavior)
        val collapsedTop = base.getDeclaredField("B").apply { isAccessible = true }.getInt(behavior)
        val expandedTop = (PhoneGlassRuntime.method(base, "B").invoke(behavior) as Number).toInt()
        slide = InitialGlassSlide.resolve(state, sheet.top, collapsedTop, expandedTop)
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
                val slot = capsuleMarginsPx(0, mini = true)
                params.leftMargin = slot[0]; params.rightMargin = slot[1]
                val topOffset = (dp(geometry.miniHeightDp) - contentHeight) / 2
                if (topOffset != 0) params.topMargin = topOffset
            }
            content.layoutParams = params
            listOf("video_surface_container", "mini_player_play_btn", "mini_player_next_btn").forEach { name ->
                val id = activity.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
                content.findViewById<View>(id)?.let { child ->
                    save(child)
                    child.layoutParams = child.layoutParams.apply {
                        width = dp(32)
                        height = dp(32)
                    }
                }
            }
        }
        miniContent?.let(::armMiniTap)
        listOf("player_root", "player_top_shadow", "background_layers", "motion_switcher", "player_fragments_host").mapNotNull(::find).forEach(::save)
    }

    private fun updateGeometry() {
        val height = dp(GlassPolicy.NAV_HEIGHT_DP + bottomGapDp) + bottomInset
        navFrame?.let { frame ->
            // A generic copy constructor drops the host ConstraintLayout's bottom anchor.
            if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
            // The capsule slots may follow the host width (see the tablet row);
            // resync every surface whenever a resolved slot edge changes.
            val navSlot = capsuleMarginsPx(frame.width, mini = false)
            val miniSlot = capsuleMarginsPx(frame.width, mini = true)
            if (!navSlot.contentEquals(navMarginPx) || !miniSlot.contentEquals(miniMarginPx)) {
                navMarginPx = navSlot
                miniMarginPx = miniSlot
                fun applySlot(view: View?, slot: IntArray) {
                    val surface = view ?: return
                    val params = surface.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    if (params.leftMargin != slot[0] || params.rightMargin != slot[1]) {
                        params.leftMargin = slot[0]; params.rightMargin = slot[1]
                        surface.layoutParams = params
                    }
                }
                applySlot(navGlass, navSlot)
                applySlot(miniGlass, miniSlot)
                applySlot(miniContent, miniSlot)
            }
            updateRowGestureOwnership(frame.width, slide == 0f)
        }
        val peek = peekHeight()
        if (lastPeek != peek) {
            lastPeek = peek
            writePeek(peek)
        }
    }

    override fun peekHeight(): Int = GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry) + if (miniVisible) dimen("shadow_height") else 0

    override fun observeNativePeek(height: Int) = nativePeek.observe(height)

    private fun writePeek(height: Int) = nativePeek.writeByModule {
        playerBehavior?.let { PhoneGlassRuntime.method(it.javaClass, "F", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!).invoke(it, height, false) }
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
            val candidates = descendants(root).filter { view ->
            view.isShown && view.height >= root.height / 2 && view.height > 0 && !isViewPagerPageHost(view) && generateSequence(view.javaClass as Class<*>?) { it.superclass }.any {
                it.name in setOf("androidx.recyclerview.widget.RecyclerView", "androidx.core.widget.NestedScrollView", "android.widget.ScrollView", "android.widget.ListView")
            }
            }.toList()
            scrollTargets = candidates.filter { child ->
                generateSequence(child.parent) { it.parent }.takeWhile { it !== root }.none { parent -> candidates.any { it === parent } }
            }
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
        val composeScene = descendants(root).any { view ->
            view.isShown && view.height > 0 && view.javaClass.name == "androidx.compose.ui.platform.ComposeView"
        }
        val occupied = if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry) else 0
        if (terminal.isEmpty() && !composeScene) {
            underlap = false
            if (root.paddingBottom != occupied) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, occupied)
            return sourceNeedsLayout
        }
        underlap = true
        if (root.paddingBottom != 0) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
        root.clipToPadding = false
        terminal.forEach { view ->
            val initial = save(view).also { it.scrollPadding = true; it.scrollPaddingActive = true }
            val desired = initial.bottomPadding + occupied
            if (view.paddingBottom != desired) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desired)
            (view as? ViewGroup)?.clipToPadding = false
        }
        return sourceNeedsLayout
    }

    private fun updateTransition() {
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
        miniGlass?.let { glass ->
            val sheet = glass.parent as? FrameLayout
            if (sheet != null && sheet !== miniRoot) {
                if (miniVisible && progress == 0f) {
                    val miniPosition = IntArray(2).also { miniRoot?.getLocationInWindow(it) }
                    val sheetPosition = IntArray(2).also(sheet::getLocationInWindow)
                    miniOffsetInSheet = miniPosition[1] - sheetPosition[1]
                }
                // Per-edge morph (tablet row): each side interpolates from its own
                // slot edge to zero, so the pill unfolds from its bottom-right
                // anchor into the full sheet while the sheet slides up.
                val left = (miniMarginPx[0] * (1f - materialProgress)).roundToInt()
                val right = (miniMarginPx[1] * (1f - materialProgress)).roundToInt()
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
            glass.alpha = if (!miniVisible && progress == 0f) 0f else 1f - blend(0.35f, 0.6f)
        }
        find("player_sheet_container")?.let { v ->
            val original = save(v)
            val desired = if (progress == 0f) null else original.outlineProvider
            if (v.outlineProvider !== desired) v.outlineProvider = desired
        }
        listOf("player_top_shadow", "background_layers", "player_fragments_host").mapNotNull(::find).forEach { v ->
            applyLayerAlpha(v, materialProgress)
        }
        // The motion subtree includes rectangular legibility/blur overlays and can
        // still have thumbnail-sized bounds early in the native transition. Reveal
        // it only after the glass has faded and the opaque player background is back.
        find("motion_switcher")?.let { v ->
            applyLayerAlpha(v, blend(0.6f, 0.85f))
        }
        find("player_root")?.background = if (materialProgress < 1f) null else states[find("player_root")]?.background
    }

    override fun onSlide(progress: Float) { slide = progress.coerceIn(0f, 1f) }

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
        if (underlap) 0 else if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible, bottomGapDp, geometry) else 0
    } else null

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

    override fun foreground(active: Boolean) { navGlass?.foreground(active); navScrim?.foreground(active); miniGlass?.foreground(active) }

    protected open fun findPlayerBehavior(): Any? = generateSequence(activity.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstNotNullOfOrNull {
        if (it.type.name.contains("BottomSheetBehavior")) runCatching { it.isAccessible = true; it.get(activity) }.getOrNull() else null
    }

    private fun scheduleFailure(error: Throwable) {
        if (failureScheduled || closed) return
        failureScheduled = true
        releaseGlassOwnership(hostRoot)
        activity.window.decorView.post { failure(error) }
    }

    override fun close() {
        if (closed) return
        closed = true
        activated = false
        attachHandler.removeCallbacks(retryAttach)
        retryPending = false
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        backdrop?.close()
        listOfNotNull(navGlass, navScrim, miniGlass).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        states.forEach { (view, state) -> state.restore(view) }
        states.clear()
        layerAlphas.forEach { (view, state) -> view.alpha = state.native }
        layerAlphas.clear()
        nativePeek.latest?.let { runCatching { writePeek(it) } }
        releaseGlassOwnership(hostRoot)
        activity.window.decorView.requestLayout()
    }

    private fun descendants(root: ViewGroup): Sequence<View> = sequence {
        for (i in 0 until root.childCount) { val view = root.getChildAt(i); yield(view); if (view is ViewGroup) yieldAll(descendants(view)) }
    }

    private fun isDescendant(child: View, parent: View) = generateSequence(child.parent) { it.parent }.any { it === parent }

    /** ViewPager2 hosts its pages in an internal RecyclerView. Padding that RecyclerView
     * shrinks every page instead of adding scroll space, so the page content stops above
     * the glass and the bar samples empty background. Pad the lists inside the pages. */
    private fun isViewPagerPageHost(view: View): Boolean =
        (view.parent as? View)?.javaClass?.name == "androidx.viewpager2.widget.ViewPager2"

    private class NativeViewState(view: View) {
        val background = view.background
        val alpha = view.alpha
        private val visibility = view.visibility
        private val accessibility = view.importantForAccessibility
        private val params = view.layoutParams
        private val originalWidth = params.width
        private val originalHeight = params.height
        private val margins = (params as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
        private val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        val bottomPadding get() = padding[3]
        var scrollPadding = false
        var scrollPaddingActive = false
        private val clipChildren = (view as? ViewGroup)?.clipChildren
        private val clipPadding = (view as? ViewGroup)?.clipToPadding
        val outlineProvider = view.outlineProvider
        private val transform = floatArrayOf(view.scaleX, view.scaleY, view.translationX, view.translationY)
        fun restoreScroll(view: View) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, padding[3])
            if (view is ViewGroup) clipPadding?.let { view.clipToPadding = it }
        }
        fun restore(view: View) {
            view.background = background; view.alpha = alpha; view.visibility = visibility
            view.outlineProvider = outlineProvider
            view.importantForAccessibility = accessibility
            params.width = originalWidth
            params.height = originalHeight
            if (params is ViewGroup.MarginLayoutParams && margins != null) {
                params.setMargins(margins[0], margins[1], margins[2], margins[3])
            }
            view.layoutParams = params
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
            if (view is ViewGroup) { clipChildren?.let { view.clipChildren = it }; clipPadding?.let { view.clipToPadding = it } }
            view.scaleX = transform[0]; view.scaleY = transform[1]; view.translationX = transform[2]; view.translationY = transform[3]
        }
    }
}
