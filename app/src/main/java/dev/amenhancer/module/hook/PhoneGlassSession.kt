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
import com.kyant.backdrop.backdrops.ViewBackdrop
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
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

@RequiresApi(33)
internal class PhoneGlassSession(
    private val activity: Activity,
    private val config: TargetConfigClient,
    private val failure: (Throwable) -> Unit,
) : AutoCloseable, ViewTreeObserver.OnPreDrawListener {
    private val states = IdentityHashMap<View, NativeViewState>()
    private var navFrame: FrameLayout? = null
    private var navigation: View? = null
    private var source: ViewGroup? = null
    private var backdrop: ViewBackdrop? = null
    private var navGlass: GlassHostView? = null
    private var miniGlass: GlassHostView? = null
    var miniRoot: FrameLayout? = null
        private set
    private var miniContent: View? = null
    var playerBehavior: Any? = null
        private set
    private var observer: ViewTreeObserver? = null
    private var closed = false
    private var failureScheduled = false
    var activated = false
        private set
    private var tabs by mutableStateOf(emptyList<GlassTab>())
    private var selectedId by mutableIntStateOf(View.NO_ID)
    private var accent by mutableStateOf(Color.Red)
    private var foreground by mutableStateOf(Color.Black)
    private var hostConfiguration by mutableStateOf(Configuration(activity.resources.configuration))
    private var menuKey: List<Any?> = emptyList()
    private val input = NativeButtonInput()
    private var slide = 0f
    private var lastPeek = -1
    private var originalPeek = -1
    private var contentDownX = 0f
    private var contentDownY = 0f
    private var observingPress = false
    private var underlap = false
    private var scanNeeded = true
    private var scrollTargets: List<View> = emptyList()
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanNeeded = true }
    private var nextSettingsCheck = 0L
    private val density get() = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val bottomInset get() = activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    private val miniVisible get() = miniRoot?.isShown == true

    private fun find(name: String): View? = activity.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
        .takeIf { it != 0 }?.let { activity.findViewById(it) }

    private fun dimen(name: String): Int = activity.resources.getIdentifier(name, "dimen", ModuleConstants.TARGET_PACKAGE)
        .takeIf { it != 0 }?.let { activity.resources.getDimensionPixelSize(it) } ?: 0

    private fun save(view: View): NativeViewState = states.getOrPut(view) { NativeViewState(view) }

    private fun allowGlassOverflow(view: View) {
        generateSequence(view as View?) { it.parent as? View }.takeWhile { it.layoutParams != null }.forEach {
            if (it is ViewGroup) {
                save(it)
                it.clipChildren = false
                it.clipToPadding = false
            }
        }
    }

    fun attachAvailableViews() {
        if (closed) return
        if (navGlass == null) {
            if (find("bottom_navigation_root_stacked") == null) return
            val frame = find("bottom_navigation_tabs_frame") as? FrameLayout ?: return
            val nav = find("bottom_navigation") ?: return
            val content = find("navigation_host_group") as? ViewGroup ?: return
            check(!isDescendant(frame, content)) { "Backdrop source contains the glass consumer" }
            navFrame = frame
            navigation = nav
            source = content
            playerBehavior = findPlayerBehavior()
            checkNotNull(playerBehavior) { "1586 player behavior not ready" }
            val bg = ViewBackdrop(content, ::scheduleFailure).also { backdrop = it; it.start() }
            refreshMenu()
            val glass = GlassHostView(moduleContext()).also { navGlass = it }
            glass.alpha = 0f
            glass.content { HostConfiguration { GlassNavigation(tabs, selectedId, accent, foreground, bg, ::selectTab) } }
            frame.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP).apply {
                leftMargin = dp(16); rightMargin = dp(16)
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
                    NativeLiquidButton(bg, input) { sx, sy, x, y ->
                        miniContent?.let { v -> v.scaleX = sx; v.scaleY = sy; v.translationX = x; v.translationY = y }
                    }
                }
            }
            root.addView(glass, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
            if (activated) prepareMini()
        }
    }

    fun ownsCurrentHierarchy(): Boolean = !closed && (navFrame == null || find("bottom_navigation_tabs_frame") === navFrame)

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
        val accentId = activity.resources.getIdentifier("color_primary", "color", ModuleConstants.TARGET_PACKAGE)
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
                if (!config.settings().phoneLiquidGlassEnabled || TabletModeQualifier.isOfficialTablet(activity)) {
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
                navGlass?.alpha = if (menuReady) 1f else 0f
                navigation?.alpha = if (menuReady) 0f else 1f
                updateGeometry()
                updateUnderlap()
                updateTransition()
            }
        } catch (error: Throwable) { scheduleFailure(error) }
        return true
    }

    private fun activate() {
        val nav = navigation ?: return
        val frame = navFrame ?: return
        save(nav)
        save(frame)
        nav.alpha = 0f
        nav.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.background = null
        frame.clipChildren = false
        frame.clipToPadding = false
        allowGlassOverflow(frame)
        find("navigation_tabs_divider")?.let { save(it); it.visibility = View.GONE }
        source?.let { save(it) }
        originalPeek = bottomInset + dimen("navigation_tabs_height") + if (miniVisible) dimen("miniplayer_height") else 0
        activated = true
        prepareMini()
        navGlass?.alpha = 1f
        updateGeometry()
        updateUnderlap()
        updateTransition()
        config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS, FeatureState.ACTIVE,
            "AndroidLiquidGlass 已挂载：实时背景、底栏透镜及迷你播放器；真机视觉验收另行记录", targetBuild(activity).displayName))
    }

    private fun prepareMini() {
        miniRoot?.let(::allowGlassOverflow)
        miniRoot?.let { save(it); it.background = null; it.clipChildren = false; it.clipToPadding = false }
        miniContent?.let { content ->
            save(content)
            val params = content.layoutParams
            if (params is ViewGroup.MarginLayoutParams) { params.leftMargin = dp(16); params.rightMargin = dp(16) }
            content.layoutParams = params
        }
        listOf("player_root", "player_top_shadow", "background_layers", "motion_switcher", "player_fragments_host").mapNotNull(::find).forEach(::save)
    }

    private fun updateGeometry() {
        val height = dp(GlassPolicy.NAV_HEIGHT_DP + GlassPolicy.BOTTOM_DP) + bottomInset
        navFrame?.let { frame ->
            // A generic copy constructor drops the host ConstraintLayout's bottom anchor.
            if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
        }
        val peek = peekHeight()
        if (lastPeek != peek) {
            lastPeek = peek
            playerBehavior?.let { PhoneGlassRuntime.method(it.javaClass, "F", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!).invoke(it, peek, false) }
        }
    }

    fun peekHeight(): Int = GlassPolicy.occupiedHeight(density, bottomInset, miniVisible) + if (miniVisible) dimen("shadow_height") else 0

    private fun updateUnderlap() {
        val root = source ?: return
        // 1586 applies the navigation-bar inset as a margin on this source, even
        // in an edge-to-edge window. Extend the scene, not the controls, beneath
        // the gesture area. Leave larger margins (e.g. IME avoidance) untouched.
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (bottomInset > 0 && params.bottomMargin == bottomInset) {
                save(root)
                params.bottomMargin = 0
                root.layoutParams = params
            }
        }
        if (scanNeeded) {
            scanNeeded = false
            val candidates = descendants(root).filter { view ->
            view.isShown && view.height >= root.height / 2 && view.height > 0 && generateSequence(view.javaClass as Class<*>?) { it.superclass }.any {
                it.name in setOf("androidx.recyclerview.widget.RecyclerView", "androidx.core.widget.NestedScrollView", "android.widget.ScrollView", "android.widget.ListView")
            }
            }.toList()
            scrollTargets = candidates.filter { child ->
                generateSequence(child.parent) { it.parent }.takeWhile { it !== root }.none { parent -> candidates.any { it === parent } }
            }
        }
        val terminal = scrollTargets
        // Library / New / parts of Search are Compose scenes in 1586. They do
        // not expose RecyclerView children. Padding their View viewport removes
        // the very pixels the backdrop needs; their own content owns scrolling.
        val composeScene = descendants(root).any { view ->
            view.isShown && view.height > 0 && view.javaClass.name == "androidx.compose.ui.platform.ComposeView"
        }
        val occupied = if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible) else 0
        if (terminal.isEmpty() && !composeScene) {
            underlap = false
            if (root.paddingBottom != occupied) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, occupied)
            return
        }
        underlap = true
        if (root.paddingBottom != 0) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
        root.clipToPadding = false
        terminal.forEach { view ->
            val initial = save(view).also { it.scrollPadding = true }
            val desired = initial.bottomPadding + occupied
            if (view.paddingBottom != desired) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desired)
            (view as? ViewGroup)?.clipToPadding = false
        }
    }

    private fun updateTransition() {
        navFrame?.background = null
        // Keep Z ordering (also used for touch dispatch); remove only the old
        // rectangular shadow outline, not the navigation view's elevation.
        navFrame?.outlineProvider = null
        miniRoot?.background = null
        miniGlass?.alpha = miniContent?.alpha ?: 1f
        val progress = if (!miniVisible) 1f else maxOf(slide, 1f - (miniContent?.alpha ?: 1f)).coerceIn(0f, 1f)
        find("player_sheet_container")?.let { v ->
            val original = save(v)
            v.outlineProvider = if (progress == 0f) null else original.outlineProvider
        }
        listOf("player_top_shadow", "background_layers", "motion_switcher", "player_fragments_host").mapNotNull(::find).forEach { v ->
            val original = save(v)
            v.alpha = original.alpha * progress
        }
        find("player_root")?.background = if (progress == 0f) null else states[find("player_root")]?.background
    }

    fun onSlide(progress: Float) { slide = progress.coerceIn(0f, 1f) }

    fun redirectedPadding(view: Any?): Int? = if (activated && view === source) {
        if (underlap) 0 else if (navFrame?.isShown == true) GlassPolicy.occupiedHeight(density, bottomInset, miniVisible) else 0
    } else null

    fun observeTouch(event: MotionEvent) {
        if (!activated) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { contentDownX = event.x; contentDownY = event.y; observingPress = true }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - contentDownY) > ViewConfiguration.get(activity).scaledTouchSlop && abs(event.y - contentDownY) > abs(event.x - contentDownX)) {
                if (observingPress) input.event(MotionEvent.ACTION_CANCEL, 0f, 0f)
                observingPress = false
            }
        }
        if (observingPress) input.event(event.actionMasked, event.x - dp(16), event.y)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) observingPress = false
    }

    fun foreground(active: Boolean) { navGlass?.foreground(active); miniGlass?.foreground(active) }

    private fun findPlayerBehavior(): Any? = generateSequence(activity.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }.firstNotNullOfOrNull {
        if (it.type.name.contains("BottomSheetBehavior")) runCatching { it.isAccessible = true; it.get(activity) }.getOrNull() else null
    }

    private fun scheduleFailure(error: Throwable) {
        if (failureScheduled || closed) return
        failureScheduled = true
        activity.window.decorView.post { failure(error) }
    }

    override fun close() {
        if (closed) return
        closed = true
        activated = false
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        backdrop?.close()
        listOfNotNull(navGlass, miniGlass).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        states.forEach { (view, state) -> state.restore(view) }
        states.clear()
        if (originalPeek >= 0) runCatching { playerBehavior?.let { PhoneGlassRuntime.method(it.javaClass, "F", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!).invoke(it, originalPeek, false) } }
        activity.window.decorView.requestLayout()
    }

    private fun descendants(root: ViewGroup): Sequence<View> = sequence {
        for (i in 0 until root.childCount) { val view = root.getChildAt(i); yield(view); if (view is ViewGroup) yieldAll(descendants(view)) }
    }

    private fun isDescendant(child: View, parent: View) = generateSequence(child.parent) { it.parent }.any { it === parent }

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
        private val clipChildren = (view as? ViewGroup)?.clipChildren
        private val clipPadding = (view as? ViewGroup)?.clipToPadding
        val outlineProvider = view.outlineProvider
        private val transform = floatArrayOf(view.scaleX, view.scaleY, view.translationX, view.translationY)
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
