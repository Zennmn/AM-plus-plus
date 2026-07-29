package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.R
import dev.amenhancer.module.config.TargetConfigClient
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.roundToInt

/**
 * Mirrors the modified APK's landscape resource overlays without replacing
 * the target application's player root. The target owns the bottom-sheet
 * lifecycle; the resource overlays only replace its static constraints.
 */
internal object DualPaneResourceHook {
    fun install() {
        LayoutInflationRegistry.register("bottom_navigation") { view ->
            // XmlPullParser inflation can report the outer activity layout when
            // its tree merely contains bottom_navigation. Recover the included
            // navigation root so this keeps the exact-root semantics of the old
            // resource hook.
            val root = ConstraintLayoutPane.resolveBottomNavigationRoot(view) ?: return@register
            debug(
                "bottom navigation layout callback root=" + root.javaClass.name +
                    " orientation=" + root.resources.configuration.orientation,
            )
            ConstraintLayoutPane.installLandscapeBottomNavigation(root)
        }
        LayoutInflationRegistry.register("fragment_player_main") { view ->
            val root = view as? ViewGroup ?: return@register
            debug("layout callback root=" + root.javaClass.name + " orientation=" + root.resources.configuration.orientation)
            DualPaneShell.installImmediately(root)
        }
        hookTabletLandscapeLyricsSheet()
        // The modified package changes `lyrics_line_text_size` only in its
        // w640dp resource table. Hook the two layouts that actually reference
        // that dimension so normal and karaoke lyrics receive the same 35sp
        // value at inflation time, without globally replacing phone resources.
        hookTabletLyricTextLayout("lyrics_line")
        hookTabletLyricTextLayout("lyrics_word_karaoke")
    }

    private fun hookTabletLandscapeLyricsSheet() {
        LayoutInflationRegistry.register("fragment_player_lyrics_sheet", RightLyricsPaneLayout::apply)
    }

    private fun hookTabletLyricTextLayout(
        layoutName: String,
    ) {
        LayoutInflationRegistry.register(layoutName, TabletLyricTypography::applyToInflatedLayout)
    }
}

/**
 * Mirrors the modified layout-land/fragment_player_lyrics_sheet.xml exactly.
 * This callback is scoped to the dedicated lyrics layout, not the player
 * layout, so it never changes the left song/album-art pane.
 */
private object RightLyricsPaneLayout {
    private const val CURRENT_PLAYER_ITEM = "current_player_item"
    private const val RECYCLER_VIEW_GRADIENTS = "recycler_view_gradients"
    private const val CONTROLS = "controls"
    private const val CONTROLS_TAP_TARGET = "controls_tap_target"
    private const val ALPHA_GRADIENT_FRAME_LAYOUT =
        "com.apple.android.music.common.views.AlphaGradientFrameLayout"
    private const val TOP_EDGE_FRACTION = 0.30f
    private const val TOP_CLEAR_FRACTION = 0.075f
    private const val TOP_CLEAR_WITHIN_FADE_FRACTION = 0.25f
    private const val BOTTOM_EDGE_FRACTION = 0.15f
    private val gradientEdgeBooleans = listOf("R", "S", "T", "U")

    fun apply(root: View) {
        if (!TabletModeQualifier.isEligible(root.context)) return
        val resources = root.resources
        val gradients = root.findViewById<View>(targetId(resources, RECYCLER_VIEW_GRADIENTS))
            ?: return

        // The modified root has layout_marginTop="0dp".
        val rootParams = root.layoutParams as? ViewGroup.MarginLayoutParams
        if (rootParams != null) {
            rootParams.topMargin = 0
            root.layoutParams = rootParams
        }
        hide(root, resources, CURRENT_PLAYER_ITEM)
        hide(root, resources, CONTROLS)
        hide(root, resources, CONTROLS_TAP_TARGET)
        ConstraintLayoutPane.anchorTopToParent(gradients, RECYCLER_VIEW_GRADIENTS)
        configureVerticalGradientEdges(gradients)
        root.requestLayout()
        debug(
            "right lyrics pane landscape resource installed root=" + System.identityHashCode(root) +
                " gradients=" + gradients.id,
        )
    }

    private fun targetId(resources: android.content.res.Resources, name: String): Int =
        resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
            .takeIf { it != 0 }
            ?: error("id/$name resource ID was unavailable")

    private fun hide(root: View, resources: android.content.res.Resources, name: String) {
        root.findViewById<View>(targetId(resources, name))?.visibility = View.GONE
    }

    /** Uses the target view's single DST_IN layer so scrolling rows fade continuously. */
    private fun configureVerticalGradientEdges(gradients: View) {
        if (gradients.javaClass.name != ALPHA_GRADIENT_FRAME_LAYOUT) return
        val applyGradient = fun() {
            if (gradients.height <= 0) return
            runCatching {
                gradientEdgeBooleans.forEach { fieldName ->
                    val field = findField(gradients.javaClass, fieldName)
                        ?: error("AlphaGradientFrameLayout.$fieldName was unavailable")
                    field.setBoolean(gradients, fieldName == "R" || fieldName == "S")
                }
                val topFadeColors = intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.BLACK,
                )
                val topFadePositions = floatArrayOf(
                    0f,
                    TOP_CLEAR_WITHIN_FADE_FRACTION,
                    1f,
                )
                val topFadeColorsField = findField(gradients.javaClass, "a")
                    ?: error("AlphaGradientFrameLayout.a was unavailable")
                val topFadePositionsField = findField(gradients.javaClass, "e")
                    ?: error("AlphaGradientFrameLayout.e was unavailable")
                topFadeColorsField.set(gradients, topFadeColors)
                topFadePositionsField.set(gradients, topFadePositions)
                val topEdgeSize = (gradients.height * TOP_EDGE_FRACTION)
                    .roundToInt()
                    .coerceAtLeast(1)
                val bottomEdgeSize = (gradients.height * BOTTOM_EDGE_FRACTION)
                    .roundToInt()
                    .coerceAtLeast(1)
                val setVerticalFadeSizes = gradients.javaClass.getDeclaredMethod(
                    "d",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                setVerticalFadeSizes.invoke(gradients, topEdgeSize, bottomEdgeSize)
                gradients.invalidate()
            }.onFailure {
                debug("right lyrics pane vertical gradient setup failed: $it")
            }
        }
        gradients.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyGradient()
        }
        gradients.post(applyGradient)
    }
}

internal class AppleMusicDualPaneTarget(
    private val symbols: TargetSymbolResolver,
) : DualPaneTarget {
    override fun install(): TargetCapabilityInstall {
        val controllerResolution = symbols.resolve(AppleMusicSymbols.PlayerController)
        val controller = controllerResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(controllerResolution.summary)
        val controllerHooks = installControllerHooks(controller)
        val navigationMenuResolution = symbols.resolve(AppleMusicSymbols.StackedNavigationMenu)
        val navigationMenuMeasureHooks = installStackedBottomNavigationMenuMeasureHook(
            navigationMenuResolution.valueOrNull(),
        )
        val activityResolution = symbols.resolve(AppleMusicSymbols.PlayerActivity)
        val chromeHooks = activityResolution.valueOrNull()?.let { activityClass ->
            installNativeStackedNavigationHolderHook(activityClass)
        } ?: 0
        val lyricsFragmentResolution = symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val lyricsFragmentClass = lyricsFragmentResolution.valueOrNull()
        val lyricsChromeResolution = symbols.resolve(AppleMusicSymbols.LyricsChromeFragment)
        val lyricsChromeHooks = lyricsChromeResolution.valueOrNull()?.let { chromeClass ->
            lyricsFragmentClass?.let { lyricsClass ->
                installLandscapeLyricsChromeHook(chromeClass, lyricsClass)
            }
        } ?: 0
        val lyricsMetricsHooks = lyricsFragmentClass?.let(::installLandscapeLyricsMetricsHook) ?: 0
        val lyricsTypographyHooks = lyricsFragmentClass?.let(::installLandscapeLyricsTypographyHook) ?: 0
        if (
            controllerHooks == 0 ||
            chromeHooks == 0 ||
            lyricsChromeHooks == 0 ||
            lyricsMetricsHooks == 0 ||
            lyricsTypographyHooks == 0
        ) {
            return TargetCapabilityInstall.Degraded(
                "Installed controller=$controllerHooks chrome=$chromeHooks lyricsChrome=$lyricsChromeHooks " +
                    "lyricsMetrics=$lyricsMetricsHooks lyricsTypography=$lyricsTypographyHooks hook(s); " +
                    listOf(
                        navigationMenuResolution,
                        activityResolution,
                        lyricsFragmentResolution,
                        lyricsChromeResolution,
                    ).filterNot { it is TargetResolution.Found<*> }
                        .joinToString { it.summary },
            )
        }
        return TargetCapabilityInstall.Active(
            "Installed player controller and chrome hooks; " +
                "stackedNavigationMenuMeasure=$navigationMenuMeasureHooks; " +
                controllerResolution.summary,
        )
    }

    /**
     * The modified resource XML gives the Material menu its 56dp height on
     * its first measure. At the resource-hook boundary this app has already
     * constructed the menu from the stock flat XML, so its direct child keeps
     * the cached 40dp spec even when the public parent becomes full-width.
     * Feed the exact same 56dp height into that direct menu on every measure.
     */
    private fun installStackedBottomNavigationMenuMeasureHook(menuClass: Class<*>?): Int {
        menuClass ?: return 0
        val onMeasure = menuClass.declaredMethods.firstOrNull { method ->
            method.name == "onMeasure" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: return 0
        return if (hook(onMeasure, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val menu = param.thisObject as? View ?: return
                    val navigation = menu.parent as? View ?: return
                    if (!TabletModeQualifier.isEligible(navigation.context)) return
                    val resources = navigation.resources
                    val bottomNavigationId = resources.getIdentifier(
                        "bottom_navigation",
                        "id",
                        ModuleConstants.TARGET_PACKAGE,
                    )
                    if (bottomNavigationId == 0) return
                    if (navigation.id != bottomNavigationId) return
                    val tabsHeightId = resources.getIdentifier(
                        "navigation_tabs_height",
                        "dimen",
                        ModuleConstants.TARGET_PACKAGE,
                    )
                    if (tabsHeightId == 0) return
                    val tabsHeight = resources.getDimensionPixelSize(tabsHeightId)
                    param.args[1] = View.MeasureSpec.makeMeasureSpec(
                        tabsHeight,
                        View.MeasureSpec.EXACTLY,
                    )
                }
            })
        ) {
            1
        } else {
            0
        }
    }

    /**
     * The modified APK obtains its 35sp value through a w640dp resource.
     * Our module leaves target resources untouched, so install the equivalent
     * row-scoped runtime typography when the lyrics view has resumed. This is
     * owned by dual-pane rather than future-blur: disabling blur must not
     * shrink the tablet player back to the stock 24sp size.
     */
    private fun installLandscapeLyricsTypographyHook(lyricsFragmentClass: Class<*>): Int {
        val onResume = lyricsFragmentClass.declaredMethods.firstOrNull { method ->
            method.name == "onResume" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.isEmpty()
        } ?: return 0
        return if (hook(onResume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let(TabletLyricTypography::attach)
                }
            })
        ) {
            1
        } else {
            0
        }
    }

    /**
     * Mirrors the modified e.a2(int, int[]) prefix exactly: on the dedicated
     * lyrics fragment in tablet landscape, hide f2() and skip the stock
     * chrome animation. This removes the duplicate controls from the right
     * lyrics pane without touching the left player pane.
     */
    private fun installLandscapeLyricsChromeHook(
        chromeClass: Class<*>,
        lyricsFragmentClass: Class<*>,
    ): Int {
        val animateChrome = chromeClass.declaredMethods.firstOrNull { method ->
            method.name == "a2" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == IntArray::class.java
        } ?: return 0
        return if (hook(animateChrome, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    if (!lyricsFragmentClass.isInstance(fragment)) return
                    val chrome = runCatching {
                        ModernXposedRuntime.callMethod(fragment, "f2") as? View
                    }.getOrNull() ?: return
                    if (!TabletModeQualifier.isEligible(chrome.context)) return
                    chrome.visibility = View.GONE
                    param.result = null
                    debug("suppressed duplicate lyrics pane chrome")
                }
            })
        ) {
            1
        } else {
            0
        }
    }

    /**
     * Mirrors the landscape-only tail inserted into PlayerLyricsViewFragment
     * j2(): stock code stores round(anchor) + f2().height into both x.c
     * bounds, while the modified APK stores round(anchor). Let stock code do
     * all of its normal calculations, then remove the same control height and
     * refresh the same RecyclerView it refreshes in j2().
     */
    private fun installLandscapeLyricsMetricsHook(lyricsFragmentClass: Class<*>): Int {
        val updateMetrics = lyricsFragmentClass.declaredMethods.firstOrNull { method ->
            method.name == "j2" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.isEmpty()
        } ?: return 0
        return if (hook(updateMetrics, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    val controls = runCatching {
                        ModernXposedRuntime.callMethod(fragment, "f2") as? View
                    }.getOrNull() ?: return
                    if (!TabletModeQualifier.isEligible(controls.context)) return
                    val highlightAnchorAligned = alignSynchronizedLyricsHighlightAnchor(fragment)
                    val controlsHeight = controls.height
                    val endPaddingCorrected = controlsHeight == 0 ||
                        listOf("z0", "A0").all { fieldName ->
                            subtractControlsHeightFromLyricsBoundary(fragment, fieldName, controlsHeight)
                        }
                    if (!endPaddingCorrected) return
                    if (!highlightAnchorAligned && controlsHeight == 0) {
                        debug("landscape lyrics metrics unchanged")
                        return
                    }
                    refreshLyricsRecycler(fragment)
                    debug(
                        "corrected landscape lyrics metrics highlightAnchorAligned=" +
                            highlightAnchorAligned + " controlsHeight=" + controlsHeight,
                    )
                }
            })
        ) {
            1
        } else {
            0
        }
    }

    private fun alignSynchronizedLyricsHighlightAnchor(fragment: Any): Boolean = runCatching {
        val binding = findField(fragment.javaClass, "i0")?.get(fragment)
            ?: return@runCatching false
        val container = findField(binding.javaClass, "U")?.get(binding) as? View
            ?: return@runCatching false
        if (container.height <= 0) return@runCatching false
        val metrics = findField(fragment.javaClass, "z0")?.get(fragment)
            ?: return@runCatching false
        val highlightOffset = findField(metrics.javaClass, "a")
            ?: return@runCatching false
        highlightOffset.setInt(
            metrics,
            TabletLyricAnchorPolicy.highlightOffset(
                currentOffset = highlightOffset.getInt(metrics),
                containerHeight = container.height,
            ),
        )
        true
    }.getOrDefault(false)

    private fun subtractControlsHeightFromLyricsBoundary(
        fragment: Any,
        fieldName: String,
        controlsHeight: Int,
    ): Boolean = runCatching {
        val bounds = findField(fragment.javaClass, fieldName)?.get(fragment) ?: return@runCatching false
        val lowerBoundary = findField(bounds.javaClass, "c") ?: return@runCatching false
        lowerBoundary.setInt(bounds, lowerBoundary.getInt(bounds) - controlsHeight)
        true
    }.getOrDefault(false)

    private fun refreshLyricsRecycler(fragment: Any) {
        val recycler = runCatching {
            val binding = findField(fragment.javaClass, "i0")?.get(fragment) ?: return@runCatching null
            findField(binding.javaClass, "a0")?.get(binding)
        }.getOrNull() as? View ?: return
        if (runCatching { ModernXposedRuntime.callMethod(recycler, "S") }.isFailure) {
            recycler.requestLayout()
        }
    }

    private fun installControllerHooks(controller: Class<*>): Int {
        var hooks = 0
        val initialize = controller.declaredMethods.firstOrNull { method ->
            method.name == "w1" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.singleOrNull()?.name?.endsWith(".BagConfig") == true
        }
        if (initialize != null && hook(initialize, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val controllerInstance = param.thisObject ?: return
                    installForControllerRoot(controllerInstance, controllerRoot(controllerInstance), "w1")
                }
            })
        ) {
            hooks += 1
        }

        // On this release w1() runs before Fragment#getView() is available.
        // The modified APK's transaction is still the model; onCreateView's
        // result is the first deterministic moment at which its two target
        // containers can be resolved by the child FragmentManager.
        val createView = controller.declaredMethods.firstOrNull { method ->
            method.name == "onCreateView" &&
                !Modifier.isStatic(method.modifiers) &&
                View::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.map { it.name } == listOf(
                "android.view.LayoutInflater",
                "android.view.ViewGroup",
                "android.os.Bundle",
            )
        }
        if (createView != null && hook(createView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val controllerInstance = param.thisObject ?: return
                    installForControllerRoot(controllerInstance, param.result as? View, "onCreateView")
                }
            })
        ) {
            hooks += 1
        }

        val selectPane = controller.declaredMethods.firstOrNull { method ->
            method.name == "F1" &&
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].isEnum &&
                method.parameterTypes[1] == Bundle::class.java
        }
        if (selectPane != null && hook(selectPane, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requested = param.args.firstOrNull() as? Enum<*> ?: return
                    val controllerInstance = param.thisObject ?: return
                    if (requested.name != LYRICS_STATE || stateFor(controllerInstance) == null) return
                    param.result = null
                }
            })
        ) {
            hooks += 1
        }
        return hooks
    }

    /**
     * Return Apple Music's own phone holder before k1() can instantiate the
     * tablet holder. The native object then owns slide interpolation, peek
     * height, navigation translation, colors and system-bar transitions.
     */
    private fun installNativeStackedNavigationHolderHook(activityClass: Class<*>): Int {
        val method = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "k1" &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.isEmpty() &&
                method.returnType.name == "com.apple.android.music.common.activity.PlayerActivity\$m"
        } ?: return 0
        val holderClass = activityClass.declaredClasses.firstOrNull { nested ->
            nested.simpleName == "StackedBottomNavigationHolder" ||
                nested.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.map { it.name } == listOf(
                        activityClass.name,
                        "android.view.View",
                        "com.apple.android.music.player.PlayerBottomSheetBehavior",
                    )
                }
        } ?: return 0
        val constructor = holderClass.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.map { it.name } == listOf(
                activityClass.name,
                "android.view.View",
                "com.apple.android.music.player.PlayerBottomSheetBehavior",
            )
        }?.apply { isAccessible = true } ?: return 0
        val behaviorField = findField(activityClass, "c1") ?: return 0

        return if (hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!TabletModeQualifier.isEligible(activity)) return
                    val root = DualPaneShell.activityRoot(activity) ?: return
                    val resources = activity.resources
                    val stackedRootId = resources.getIdentifier(
                        "bottom_navigation_root_stacked",
                        "id",
                        ModuleConstants.TARGET_PACKAGE,
                    )
                    val flatRootId = resources.getIdentifier(
                        "bottom_navigation_root_flat",
                        "id",
                        ModuleConstants.TARGET_PACKAGE,
                    )
                    val navigationRoot = sequenceOf(stackedRootId, flatRootId)
                        .filter { it != 0 }
                        .mapNotNull(root::findViewById)
                        .firstOrNull() ?: return
                    val behavior = runCatching { behaviorField.get(activity) }.getOrNull() ?: return
                    if (!constructor.parameterTypes[2].isInstance(behavior)) return
                    val stackedHolder = runCatching {
                        constructor.newInstance(activity, navigationRoot, behavior)
                    }.onFailure {
                        debug("native stacked holder construction failed: $it")
                    }.getOrNull() ?: return
                    param.result = stackedHolder
                }
            })
        ) {
            1
        } else {
            0
        }
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean = runCatching {
        ModernXposedRuntime.hookMethod(method, callback)
        true
    }.onFailure {
        debug("hook registration failed for " + method.name + ": " + it)
    }.getOrDefault(false)

    private fun attachLyricsPane(controller: Any, root: View) {
        val state = stateFor(root)
        if (state == null) {
            debug("lyrics attachment skipped: no synchronous dual-pane state on w0 root")
            return
        }
        if (!TabletModeQualifier.isEligible(state.root.context)) return
        synchronized(state) {
            if (state.lyricsAttached || state.lyricsAttachRequested) return
            state.lyricsAttachRequested = true
        }

        val attached = runCatching { attachPairedFragments(controller, state) }
            .onFailure {
                debug("paired player fragment attachment failed: " + it)
            }
            .getOrDefault(false)
        synchronized(state) {
            state.lyricsAttachRequested = false
            state.lyricsAttached = attached
        }
        if (attached) {
            debug("lyrics fragment attached to right host=" + state.lyricsHost.id + "; awaiting target expand transition")
        }
    }

    private fun attachPairedFragments(controller: Any, state: DualPaneState): Boolean {
        val playerStateClass = controller.javaClass.declaredClasses.firstOrNull {
            it.isEnum && it.enumConstants.orEmpty().any { constant -> (constant as? Enum<*>)?.name == SONG_STATE }
        } ?: return false
        val song = enumConstant(playerStateClass, SONG_STATE) ?: return false
        val lyrics = enumConstant(playerStateClass, LYRICS_STATE) ?: return false

        forceSongState(controller, playerStateClass, song)
        val manager = ModernXposedRuntime.callMethod(controller, "getChildFragmentManager") ?: return false
        if (isTargetStateSaved(manager)) return false

        val songFragment = ModernXposedRuntime.callMethod(song, "f") ?: return false
        val songTag = ModernXposedRuntime.callMethod(song, "g") as? String ?: return false
        val lyricsFragment = ModernXposedRuntime.callMethod(lyrics, "f") ?: return false
        val lyricsTag = ModernXposedRuntime.callMethod(lyrics, "g") as? String ?: return false
        val transaction = createTargetTransaction(manager)
        invokeCompatible(transaction, listOf("e", "replace"), state.playerHost.id, songFragment, songTag)
        invokeCompatible(transaction, listOf("e", "replace"), state.lyricsHost.id, lyricsFragment, lyricsTag)
        invokeCompatible(transaction, listOf("h", "commit"), false)
        return true
    }

    /**
     * Mirrors the modified w0.w1 bytecode: its shaded FragmentManager is E,
     * its concrete transaction is a(E), and its state-saved method is P().
     * The public-name fallbacks keep the hook usable on unshaded releases.
     */
    private fun isTargetStateSaved(manager: Any): Boolean = runCatching {
        invokeCompatible(manager, listOf("P", "isStateSaved")) as? Boolean
    }.getOrNull() == true

    private fun createTargetTransaction(manager: Any): Any {
        val loader = manager.javaClass.classLoader ?: error("child FragmentManager class loader was null")
        val transactionClass = Class.forName("androidx.fragment.app.a", false, loader)
        val constructor = transactionClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].isAssignableFrom(manager.javaClass)
        } ?: error("androidx.fragment.app.a(E) constructor was unavailable")
        constructor.isAccessible = true
        return constructor.newInstance(manager)
    }

    private fun invokeCompatible(receiver: Any, names: List<String>, vararg args: Any?): Any? {
        var lastFailure: Throwable? = null
        for (name in names) {
            try {
                return ModernXposedRuntime.callMethod(receiver, name, *args)
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: NoSuchMethodError(receiver.javaClass.name + "#" + names.joinToString("/"))
    }

    private fun forceSongState(controller: Any, stateClass: Class<*>, song: Any) {
        findField(controller.javaClass, "a")
            ?.takeIf { stateClass.isAssignableFrom(it.type) }
            ?.let { setField(it, controller, song) }
            ?: controller.javaClass.declaredFields
                .firstOrNull { !Modifier.isStatic(it.modifiers) && stateClass.isAssignableFrom(it.type) }
                ?.let { setField(it, controller, song) }

        runCatching { ModernXposedRuntime.callMethod(controller, "C1", false) }
        findField(controller.javaClass, "S")
            ?.takeIf { it.type.name == "androidx.lifecycle.MutableLiveData" }
            ?.let { field ->
                runCatching {
                    val liveData = field.apply { isAccessible = true }.get(controller)
                    if (liveData != null) ModernXposedRuntime.callMethod(liveData, "setValue", song)
                }
            }
    }

    private fun enumConstant(stateClass: Class<*>, name: String): Any? =
        stateClass.enumConstants.orEmpty().firstOrNull { (it as? Enum<*>)?.name == name }

    private fun stateFor(controller: Any): DualPaneState? = controllerRoot(controller)?.let { root -> stateFor(root) }

    private fun stateFor(root: View): DualPaneState? =
        root.getTag(R.id.am_enhancer_dual_pane_state) as? DualPaneState

    private fun controllerRoot(controller: Any): View? = runCatching {
        ModernXposedRuntime.callMethod(controller, "getView") as? View
    }.getOrNull()

    private fun installForControllerRoot(controller: Any, root: View?, source: String) {
        val rootGroup = root as? ViewGroup
        if (rootGroup == null) {
            debug("controller root unavailable source=$source")
            return
        }
        debug(
            "controller root observed source=$source identity=" +
                System.identityHashCode(rootGroup) +
                " attached=" + rootGroup.isAttachedToWindow,
        )
        if (DualPaneShell.installImmediately(rootGroup) != null) {
            attachLyricsPane(controller, rootGroup)
        }
    }
}

internal object TabletModeQualifier {
    fun isOfficialTablet(context: Context): Boolean {
        val tabletId = context.resources.getIdentifier(
            "is_tablet",
            "bool",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (tabletId == 0) return false
        return runCatching { context.resources.getBoolean(tabletId) }.getOrDefault(false)
    }

    fun isOfficialTabletLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            isOfficialTablet(context)

    fun isEligible(context: Context): Boolean =
        isOfficialTabletLandscape(context) && TargetConfigClient.currentSettings().dualPaneEnabled
}

internal object DualPaneShell {
    /** Mirrors the modified PlayerActivity's BaseActivity.n0() root lookup. */
    fun activityRoot(activity: Activity): View? = runCatching {
        ModernXposedRuntime.callMethod(activity, "n0") as? View
    }.onFailure {
        debug("BaseActivity.n0 root lookup failed: $it")
    }.getOrNull()

    /**
     * Mirrors a compiled layout-land resource: mutate immediately after
     * fragment_player_main inflation, even if this pre-created view has not
     * attached to the window yet.
     */
    fun installImmediately(root: ViewGroup): DualPaneState? {
        if (!TabletModeQualifier.isEligible(root.context)) {
            debug("layout skipped: tablet-landscape predicate is false")
            return null
        }
        (root.getTag(R.id.am_enhancer_dual_pane_state) as? DualPaneState)?.let { return it }
        val state = runCatching { ConstraintLayoutPane.install(root) }
            .onFailure { debug("layout install failed: $it") }
            .getOrNull()
            ?: return null
        root.setTag(R.id.am_enhancer_dual_pane_state, state)
        debug(
            "layout installed synchronously root=" + System.identityHashCode(root) +
                " playerHost=" + state.playerHost.id +
                " lyricsHost=" + state.lyricsHost.id,
        )
        return state
    }
}

/**
 * Uses reflection deliberately: ConstraintLayout belongs to Apple Music's
 * class loader, and the module must not package or shadow its own copy.
 */
private object ConstraintLayoutPane {
    private const val BOTTOM_NAVIGATION_ROOT_STACKED = "bottom_navigation_root_stacked"
    private const val BOTTOM_NAVIGATION_ROOT_FLAT = "bottom_navigation_root_flat"
    private const val BOTTOM_NAVIGATION_TABS = "bottom_navigation_tabs_frame"
    private const val BOTTOM_NAVIGATION = "bottom_navigation"
    private const val NAVIGATION_TABS_DIVIDER = "navigation_tabs_divider"
    private const val NAV_TABS_TOP_SHADOW = "nav_tabs_top_shadow"
    private const val NAVIGATION_TABS_HEIGHT = "navigation_tabs_height"
    private const val STACKED_TABS_VERTICAL_INSET_DP = 8
    private const val PLAYER_CONTAINER = "player_container"
    private const val PLAYER_CONTAINER_ELEVATION = "player_container_elevation"
    private const val PLAYER_ROOT = "player_root"
    private const val PLAYER_FRAGMENTS_HOST = "player_fragments_host"
    private const val PARENT_ID = 0
    private object BottomNavigationLandscapeInstalled
    /**
     * Apple Music 6.5.0 repackages ConstraintLayout. Its LayoutParams keeps
     * the public class name but R8 renames its instance fields. The mapping
     * below is recovered from ConstraintLayout$b.resolveLayoutDirection() and
     * the modified APK's layout-land XML, rather than guessed from field order.
     */
    private const val TARGET_650_LAYOUT_PARAMS = "androidx.constraintlayout.widget.ConstraintLayout\$b"
    private val TARGET_650_FIELD_NAMES = mapOf(
        "guidePercent" to "c",
        "leftToLeft" to "h",
        "leftToRight" to "g",
        "rightToLeft" to "f",
        "rightToRight" to "e",
        "topToTop" to "i",
        "topToBottom" to "j",
        "bottomToTop" to "k",
        "bottomToBottom" to "l",
        "orientation" to "V",
        "endToStart" to "u",
        "endToEnd" to "v",
        "startToEnd" to "s",
        "startToStart" to "t",
        "dimensionRatio" to "G",
        "matchConstraintDefaultWidth" to "L",
        "matchConstraintMinWidth" to "N",
        "matchConstraintMaxWidth" to "P",
        "constrainedWidth" to "W",
    )

    fun resolveBottomNavigationRoot(view: View): ViewGroup? {
        val resources = view.resources
        val candidateIds = listOf(
            BOTTOM_NAVIGATION_ROOT_STACKED,
            BOTTOM_NAVIGATION_ROOT_FLAT,
        ).mapNotNull { name ->
            resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
                .takeIf { it != 0 }
        }
        val suppliedRoot = view as? ViewGroup
        if (suppliedRoot != null && suppliedRoot.id in candidateIds) return suppliedRoot
        return candidateIds.asSequence()
            .mapNotNull { candidateId -> view.findViewById<ViewGroup>(candidateId) }
            .firstOrNull()
    }

    /**
     * Mirrors the modified layout-land/bottom_navigation.xml by converting the
     * stock flat resource tree into full-width stacked chrome. Apple Music's
     * native StackedBottomNavigationHolder owns its peek height and transitions.
     */
    fun installLandscapeBottomNavigation(root: ViewGroup) {
        if (!TabletModeQualifier.isEligible(root.context)) {
            debug("bottom navigation skipped: tablet-landscape predicate is false")
            return
        }
        if (root.getTag(R.id.am_enhancer_dual_pane_state) === BottomNavigationLandscapeInstalled) return

        runCatching {
            val resources = root.resources
            val rootId = targetId(resources, BOTTOM_NAVIGATION_ROOT_FLAT)
            if (root.id != rootId) {
                debug("bottom navigation skipped: unexpected root id=" + root.id)
                return
            }
            val menuHeight = resources.getDimensionPixelSize(targetId(resources, NAVIGATION_TABS_HEIGHT, "dimen"))
            val tabsHeight = stackedTabsContainerHeight(root.context, menuHeight)
            val tabsFrame = root.findViewById<FrameLayout>(targetId(resources, BOTTOM_NAVIGATION_TABS))
                ?: error("bottom_navigation_tabs_frame was absent from inflated layout")
            val playerContainer = root.findViewById<View>(targetId(resources, PLAYER_CONTAINER))
                ?: error("player_container was absent from inflated layout")
            val topShadow = root.findViewById<View>(targetId(resources, NAV_TABS_TOP_SHADOW))
                ?: error("nav_tabs_top_shadow was absent from inflated layout")
            val bottomNavigation = tabsFrame.findViewById<View>(targetId(resources, BOTTOM_NAVIGATION))
                ?: error("bottom_navigation was absent from tabs frame")

            configureTabsFrame(tabsFrame, tabsHeight, resources)
            configureTabsContent(bottomNavigation)
            // XC_LayoutInflated runs before the target finishes initializing its
            // material navigation child. Re-apply the modified XML's child
            // params on the next UI queue turn, after that initialization.
            bottomNavigation.post {
                configureTabsContent(bottomNavigation)
            }
            configureTabsTopShadow(topShadow)
            configurePlayerContainer(playerContainer)
            installTabsDivider(tabsFrame, resources)

            root.setTag(R.id.am_enhancer_dual_pane_state, BottomNavigationLandscapeInstalled)
            root.requestLayout()
            debug(
                "bottom navigation landscape installed root=" + System.identityHashCode(root) +
                    " player=" + playerContainer.id +
                    " tabsHeight=" + tabsHeight,
            )
        }.onFailure {
            debug("bottom navigation landscape install failed: $it")
        }
    }

    /** Mirrors the modified lyrics-sheet top constraint without changing its parent tree. */
    fun anchorTopToParent(view: View, resourceName: String) {
        val params = constraintMarginParams(view, resourceName)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("topToBottom", -1)
        view.layoutParams = params
        view.requestLayout()
    }

    fun install(root: ViewGroup): DualPaneState {
        val resources = root.resources
        val playerRootId = resources.getIdentifier(PLAYER_ROOT, "id", ModuleConstants.TARGET_PACKAGE)
        val playerHostId = resources.getIdentifier(PLAYER_FRAGMENTS_HOST, "id", ModuleConstants.TARGET_PACKAGE)
        require(playerRootId != 0 && playerHostId != 0) { "player root/host resource IDs were unavailable" }

        val playerRoot = root.findViewById<View>(playerRootId) as? ViewGroup
            ?: error("player_root was absent from inflated layout")
        val playerHost = playerRoot.findViewById<View>(playerHostId)
            ?: error("player_fragments_host was absent from player_root")
        val layoutParamsClass = playerHost.layoutParams.javaClass
        check(constraintField(layoutParamsClass, "startToStart") != null) {
            "player_fragments_host does not use ConstraintLayout params: " + layoutParamsClass.name
        }

        val classLoader = playerRoot.javaClass.classLoader ?: root.javaClass.classLoader
            ?: error("player_root class loader was unavailable")
        val guidelineClass = Class.forName("androidx.constraintlayout.widget.Guideline", false, classLoader)
        val guideline = guidelineClass.getConstructor(Context::class.java).newInstance(root.context) as? View
            ?: error("ConstraintLayout Guideline was not a View")
        val splitId = View.generateViewId()
        guideline.id = splitId
        playerRoot.addView(
            guideline,
            newLayoutParams(
                playerHost.layoutParams,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setInt("orientation", 1)
                setFloat("guidePercent", 0.5f)
            },
        )

        configureExistingPlayerHost(playerHost, splitId, layoutParamsClass)
        val lyricsHost = FrameLayout(root.context).apply {
            id = View.generateViewId()
            contentDescription = "AM++ lyrics pane"
        }
        playerRoot.addView(
            lyricsHost,
            newLayoutParams(playerHost.layoutParams, 0, 0).apply {
                setInt("leftToRight", splitId)
                setInt("rightToRight", PARENT_ID)
                setInt("startToEnd", splitId)
                setInt("endToEnd", PARENT_ID)
                setInt("topToTop", PARENT_ID)
                setInt("bottomToBottom", PARENT_ID)
                setHorizontalMargins(this, dp(root.context, 16))
            },
        )

        val divider = View(root.context).apply {
            id = View.generateViewId()
            visibility = View.INVISIBLE
        }
        playerRoot.addView(
            divider,
            newLayoutParams(playerHost.layoutParams, dp(root.context, 1), 0).apply {
                setInt("leftToLeft", splitId)
                setInt("rightToRight", splitId)
                setInt("startToStart", splitId)
                setInt("endToEnd", splitId)
                setInt("topToTop", PARENT_ID)
                setInt("bottomToBottom", PARENT_ID)
            },
        )

        return DualPaneState(root, playerHost, lyricsHost)
    }

    private fun configureTabsFrame(
        tabsFrame: FrameLayout,
        tabsHeight: Int,
        resources: android.content.res.Resources,
    ) {
        val params = constraintMarginParams(tabsFrame, BOTTOM_NAVIGATION_TABS)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = tabsHeight
        clearLegacyWidthContract(params)
        constrainFullWidth(params)
        params.setInt("bottomToBottom", PARENT_ID)
        params.setInt("bottomToTop", -1)
        tabsFrame.layoutParams = params
        val elevationId = resources.getIdentifier(
            PLAYER_CONTAINER_ELEVATION,
            "dimen",
            ModuleConstants.TARGET_PACKAGE,
        )
        if (elevationId != 0) tabsFrame.elevation = resources.getDimension(elevationId)
        tabsFrame.requestLayout()
    }

    private fun configureTabsContent(bottomNavigation: View) {
        val params = bottomNavigation.layoutParams as? FrameLayout.LayoutParams
            ?: error("bottom_navigation does not use FrameLayout.LayoutParams")
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.CENTER
        bottomNavigation.layoutParams = params
        bottomNavigation.requestLayout()
    }

    private fun configureTabsTopShadow(topShadow: View) {
        val params = constraintMarginParams(topShadow, NAV_TABS_TOP_SHADOW)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        constrainFullWidth(params)
        topShadow.layoutParams = params
        topShadow.requestLayout()
    }

    private fun configurePlayerContainer(playerContainer: View) {
        val params = constraintMarginParams(playerContainer, PLAYER_CONTAINER)
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.bottomMargin = 0
        clearLegacyWidthContract(params)
        constrainFullWidth(params)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("topToBottom", -1)
        params.setInt("bottomToBottom", PARENT_ID)
        params.setInt("bottomToTop", -1)
        playerContainer.layoutParams = params
        playerContainer.requestLayout()
    }

    private fun installTabsDivider(tabsFrame: FrameLayout, resources: android.content.res.Resources) {
        val dividerId = targetId(resources, NAVIGATION_TABS_DIVIDER)
        if (tabsFrame.findViewById<View>(dividerId) != null) return
        val separatorId = resources.getIdentifier("separator_color", "color", ModuleConstants.TARGET_PACKAGE)
        val divider = View(tabsFrame.context).apply {
            id = dividerId
            if (separatorId != 0) setBackgroundColor(resources.getColor(separatorId, tabsFrame.context.theme))
        }
        tabsFrame.addView(
            divider,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(tabsFrame.context, 1),
                Gravity.TOP,
            ),
        )
    }

    private fun constrainFullWidth(params: ViewGroup.MarginLayoutParams) {
        params.setInt("leftToLeft", PARENT_ID)
        params.setInt("leftToRight", -1)
        params.setInt("rightToLeft", -1)
        params.setInt("rightToRight", PARENT_ID)
        params.setInt("startToStart", PARENT_ID)
        params.setInt("startToEnd", -1)
        params.setInt("endToStart", -1)
        params.setInt("endToEnd", PARENT_ID)
    }

    /**
     * The official tablet layout uses match-constraint width plus a ratio,
     * min-width and constrained-width flag to form the narrow right rail.
     * The modified landscape XML omits all of them, so reset the same fields
     * before changing the width to MATCH_PARENT.
     */
    private fun clearLegacyWidthContract(params: ViewGroup.MarginLayoutParams) {
        params.setObject("dimensionRatio", null)
        params.setInt("matchConstraintDefaultWidth", 0)
        params.setInt("matchConstraintMinWidth", 0)
        params.setInt("matchConstraintMaxWidth", 0)
        params.setBoolean("constrainedWidth", false)
    }

    private fun constraintMarginParams(view: View, resourceName: String): ViewGroup.MarginLayoutParams {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            ?: error("$resourceName does not use MarginLayoutParams")
        check(constraintField(params.javaClass, "startToStart") != null) {
            "$resourceName does not use ConstraintLayout params: " + params.javaClass.name
        }
        return params
    }

    private fun targetId(
        resources: android.content.res.Resources,
        name: String,
        type: String = "id",
    ): Int = resources.getIdentifier(name, type, ModuleConstants.TARGET_PACKAGE)
        .takeIf { it != 0 }
        ?: error("$type/$name resource ID was unavailable")

    private fun configureExistingPlayerHost(
        playerHost: View,
        splitId: Int,
        layoutParamsClass: Class<*>,
    ) {
        val params = playerHost.layoutParams as? ViewGroup.MarginLayoutParams
            ?: error("player_fragments_host does not use MarginLayoutParams")
        check(layoutParamsClass.isInstance(params))
        params.width = 0
        params.height = 0
        params.setInt("leftToLeft", PARENT_ID)
        params.setInt("rightToLeft", splitId)
        params.setInt("startToStart", PARENT_ID)
        params.setInt("endToStart", splitId)
        params.setInt("leftToRight", -1)
        params.setInt("rightToRight", -1)
        params.setInt("startToEnd", -1)
        params.setInt("endToEnd", -1)
        params.setInt("topToTop", PARENT_ID)
        params.setInt("bottomToBottom", PARENT_ID)
        setHorizontalMargins(params, dp(playerHost.context, 48))
        playerHost.layoutParams = params
    }

    /**
     * The target's repackaged LayoutParams exposes only the
     * LayoutParams-copy constructor. Copy the real host's params just like
     * LayoutInflater does, then overwrite the dimensions and constraints.
     */
    private fun newLayoutParams(template: ViewGroup.LayoutParams, width: Int, height: Int): ViewGroup.MarginLayoutParams {
        val layoutParamsClass = template.javaClass
        val constructor = layoutParamsClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.singleOrNull()?.isAssignableFrom(template.javaClass) == true
        } ?: error("ConstraintLayout.LayoutParams copy constructor was not found: " + layoutParamsClass.name)
        constructor.isAccessible = true
        return (constructor.newInstance(template) as? ViewGroup.MarginLayoutParams
            ?: error("ConstraintLayout.LayoutParams copy constructor returned an incompatible type")).apply {
            this.width = width
            this.height = height
        }
    }

    private fun setHorizontalMargins(params: ViewGroup.MarginLayoutParams, margin: Int) {
        params.leftMargin = margin
        params.rightMargin = margin
        params.marginStart = margin
        params.marginEnd = margin
    }

    private fun ViewGroup.LayoutParams.setInt(name: String, value: Int) {
        constraintField(javaClass, name)?.setInt(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setFloat(name: String, value: Float) {
        constraintField(javaClass, name)?.setFloat(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setBoolean(name: String, value: Boolean) {
        constraintField(javaClass, name)?.setBoolean(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun ViewGroup.LayoutParams.setObject(name: String, value: Any?) {
        constraintField(javaClass, name)?.set(this, value)
            ?: error("ConstraintLayout.LayoutParams.$name was not found on " + javaClass.name)
    }

    private fun constraintField(type: Class<*>, semanticName: String): Field? {
        findField(type, semanticName)?.let { return it }
        val obfuscatedName = TARGET_650_FIELD_NAMES[semanticName]
            ?.takeIf { type.name == TARGET_650_LAYOUT_PARAMS }
            ?: return null
        return findField(type, obfuscatedName)
    }

    /**
     * Keep the modified APK's 56dp Material menu unchanged, but give it a
     * centered 8dp breathing space above and below on this tablet layout.
     */
    fun stackedTabsContainerHeight(context: Context, menuHeight: Int): Int =
        menuHeight + dp(context, STACKED_TABS_VERTICAL_INSET_DP * 2)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

internal class DualPaneState(
    val root: ViewGroup,
    val playerHost: View,
    val lyricsHost: FrameLayout,
) {
    var lyricsAttachRequested: Boolean = false
    var lyricsAttached: Boolean = false
}

private const val SONG_STATE = "SONG"
private const val LYRICS_STATE = "LYRICS"
private const val DEBUG_PREFIX = "[AMENH-2]"

private fun debug(message: String) {
    val line = DEBUG_PREFIX + " " + message
    ModernXposedRuntime.log(line)
    android.util.Log.e("AMENH-LIVE", line)
}

private fun findField(type: Class<*>, name: String): Field? {
    var current: Class<*>? = type
    while (true) {
        val candidate = current ?: return null
        runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let {
            it.isAccessible = true
            return it
        }
        current = candidate.superclass
    }
}

private fun setField(field: Field, receiver: Any, value: Any) {
    runCatching {
        field.isAccessible = true
        field.set(receiver, value)
    }
}
