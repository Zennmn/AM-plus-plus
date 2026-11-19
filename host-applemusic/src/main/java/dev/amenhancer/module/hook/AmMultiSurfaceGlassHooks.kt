package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderNode
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers verified Apple Music 7.0.1 multi-surface Liquid Glass anchors via [LayoutInflationRegistry]
 * and [PopupWindow] hooks, emitting only semantic [GlassSurfaceAnchor]s to `:app`.
 */
object AmMultiSurfaceGlassFactory {
    private const val POPUP_MARKER_KEY = 0x414D5050 // "AMPP"
    private const val OPTION_BUTTON_BLUR_MARKER_KEY = 0x414D5042 // "AMPB"
    private const val NOOP_CONTROLLER_KEY = 0x414D5043 // "AMPC"
    private const val SUPPORTED_HAZE_VERSION_CODE = 1622L

    private class HazeReflector(
        val areaField: Field,
        val stateField: Field?,
        val disposableField: Field,
        val hostViewField: Field,
        val areaGetLayer: Method,
        val areaGetPos: Method,
        val zIndexField: Field?,
        val zIndexGetValue: Method?,
        val areaContentDrawingField: Field?,
        val layerImplField: Field,
        val layerReleasedField: Field,
        val renderNodeField: Field,
        val nodeCoordinatorField: Field,
        val coordinatorLayoutNodeField: Field,
        val layoutNodeParentMethod: Method,
        val androidViewHolderClass: Class<*>,
        val androidViewHolderGetLayoutNode: Method,
    )

    private class HazeAreaEntry(
        val areaRef: WeakReference<Any>,
        val stateRef: WeakReference<Any?>,
        val nodeRef: WeakReference<Any>,
    )

    private val resourcesRegistered = AtomicBoolean(false)
    private val popupHooked = AtomicBoolean(false)
    private val blurViewHooked = AtomicBoolean(false)
    private val scope = HookRegistrationScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedPopups = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val hazeAreasByHostView =
        Collections.synchronizedMap(WeakHashMap<View, CopyOnWriteArrayList<HazeAreaEntry>>())
    private val toolbarHazeStates = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val floatingButtonDepth = ThreadLocal.withInitial { 0 }
    private val dropdownShapeStack = ThreadLocal.withInitial { ArrayDeque<Any>() }
    private val dropdownEnteredStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private val navBackComposeDepth = ThreadLocal.withInitial { 0 }
    private val overflowComposeDepth = ThreadLocal.withInitial { 0 }
    private val overflowComposeHidden = ThreadLocal.withInitial { false }
    private val overflowScopeEnteredStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private val overflowBlurFactories = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val overflowBlurViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val overflowNativeViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var collapseSubscription: HostSubscription? = null

    @Volatile
    private var observer: MultiSurfaceGlassObserver? = null

    @Volatile
    private var sentinelBlurTarget: View? = null

    @Volatile
    private var hazeReflector: HazeReflector? = null

    fun registerResources(glassObserver: MultiSurfaceGlassObserver) {
        observer = glassObserver
        if (!resourcesRegistered.compareAndSet(false, true)) return

        // 1. Search landing floating search bar (over blur_target).
        LayoutInflationRegistry.register("fragment_landing_search") { root ->
            val group = root as? ViewGroup ?: return@register
            val blurTarget = findByName(group, "blur_target") ?: return@register
            val blurView = findByName(group, "floating_search_bar_blur")
            val searchBar = findByName(group, "floating_search_bar") as? ViewGroup ?: return@register
            val targetHost = (blurView?.parent as? ViewGroup) ?: (searchBar.parent as? ViewGroup) ?: return@register
            val extraSuppress = listOfNotNull(
                findByName(searchBar, "search_view_background"),
                findByName(searchBar, "search_view_background_stroke"),
            )
            emitWhenAttached(targetHost) {
                if (!isDescendant(targetHost, blurTarget)) {
                    GlassSurfaceAnchor(
                        role = GlassSurfaceRole.FLOATING_SEARCH_BAR,
                        container = targetHost,
                        backdropSource = blurTarget,
                        cornerRadiusDp = 24f,
                        replaceBackgroundView = blurView ?: searchBar,
                        extraSuppressViews = extraSuppress,
                        forceRefreshOnPreDraw = true,
                        minCaptureIntervalMs = 16L,
                    )
                } else null
            }
        }

        // 1b. Floating circular action buttons & dropdown menus using R.layout.option_button_blur.
        LayoutInflationRegistry.register("option_button_blur") { root ->
            root.setTag(OPTION_BUTTON_BLUR_MARKER_KEY, true)
        }

        // 1c. Native top-right overflow ("more") button platter.
        LayoutInflationRegistry.register("options_more_button") { root ->
            overflowNativeViews[root] = true
            applyOverflowViewState(root, ScrollCollapseBridge.progress)
        }

        // 2. Library search bar.
        LayoutInflationRegistry.register("library_details_search_bar") { root ->
            val group = root as? ViewGroup ?: return@register
            val pill = findByName(group, "search_view_container") as? ViewGroup ?: return@register
            emitWhenAttached(pill) {
                val blurTarget = findNearestBlurTarget(pill) ?: return@emitWhenAttached null
                if (isDescendant(pill, blurTarget)) return@emitWhenAttached null
                GlassSurfaceAnchor(
                    role = GlassSurfaceRole.FLOATING_SEARCH_BAR,
                    container = pill,
                    backdropSource = blurTarget,
                    cornerRadiusDp = 24f,
                    replaceBackgroundView = pill,
                    forceRefreshOnPreDraw = true,
                    minCaptureIntervalMs = 16L,
                )
            }
        }

        // 3. Player action buttons (shuffle, repeat, autoplay & header action pills).
        listOf("layout_player_buttons", "button_group_play_shuffle_action_buttons").forEach { layoutName ->
            LayoutInflationRegistry.register(layoutName) { root ->
                val group = root as? ViewGroup ?: return@register
                emitWhenAttached(group) {
                    val playerBg = findPlayerBackdropSibling(group) ?: return@emitWhenAttached null
                    listOf("button_shuffle", "button_repeat", "button_autoplay", "button_play", "button_action")
                        .forEach { buttonIdName ->
                            val btn = findByName(group, buttonIdName) as? ViewGroup ?: return@forEach
                            val imageContainer = findByName(btn, "image_container")
                            val targetContainer = if (btn is FrameLayout) btn else (imageContainer as? ViewGroup) ?: btn
                            val replaceBg = imageContainer ?: targetContainer
                            if (!isDescendant(targetContainer, playerBg)) {
                                observer?.onSurfaceAttached(
                                    GlassSurfaceAnchor(
                                        role = GlassSurfaceRole.PLAYER_ACTION_BUTTON,
                                        container = targetContainer,
                                        backdropSource = playerBg,
                                        cornerRadiusDp = 20f,
                                        replaceBackgroundView = replaceBg,
                                        minCaptureIntervalMs = 16L,
                                    ),
                                )
                            }
                        }
                    null
                }
            }
        }

        // 4. Bottom ActionSheet & modal dialogs (refracting owner Activity content behind separate Window).
        LayoutInflationRegistry.register("action_sheet") { root ->
            val coordinator = root as? ViewGroup ?: return@register
            val list = findByName(coordinator, "action_sheet_list") ?: return@register
            emitWhenAttached(coordinator) {
                val hostContent = resolveActivityContent(coordinator.context) ?: return@emitWhenAttached null
                if (coordinator === hostContent || isDescendant(coordinator, hostContent)) return@emitWhenAttached null
                GlassSurfaceAnchor(
                    role = GlassSurfaceRole.ACTION_SHEET_DIALOG,
                    container = coordinator,
                    backdropSource = hostContent,
                    cornerRadiusDp = 28f,
                    replaceBackgroundView = list,
                    minCaptureIntervalMs = 16L,
                )
            }
        }

        LayoutInflationRegistry.register("default_dialog_fragment") { root ->
            val dialogRoot = root as? ViewGroup ?: return@register
            emitWhenAttached(dialogRoot) {
                val hostContent = resolveActivityContent(dialogRoot.context) ?: return@emitWhenAttached null
                if (dialogRoot === hostContent || isDescendant(dialogRoot, hostContent)) return@emitWhenAttached null
                GlassSurfaceAnchor(
                    role = GlassSurfaceRole.ACTION_SHEET_DIALOG,
                    container = dialogRoot,
                    backdropSource = hostContent,
                    cornerRadiusDp = 24f,
                    replaceBackgroundView = dialogRoot,
                    minCaptureIntervalMs = 16L,
                )
            }
        }

        // 5. Context / more popup menu marker.
        LayoutInflationRegistry.register("recycler_popup_window") { root ->
            root.setTag(POPUP_MARKER_KEY, true)
            trackedPopups[root] = true
        }

        // 6. Card editorial overlay band (super_hero_container_a: leaf sibling artwork image -> legibility band).
        LayoutInflationRegistry.register("super_hero_container_a") { root ->
            val card = root as? ViewGroup ?: return@register
            val cardContent = (findByName(card, "card_content") as? ViewGroup) ?: return@register
            val artwork = findByName(cardContent, "image") ?: return@register
            val legibility = findByName(cardContent, "legibility_gradient") ?: return@register
            emitWhenAttached(cardContent) {
                if (legibility.visibility != View.VISIBLE) return@emitWhenAttached null
                if (isDescendant(cardContent, artwork)) return@emitWhenAttached null
                GlassSurfaceAnchor(
                    role = GlassSurfaceRole.CARD_OVERLAY,
                    container = cardContent,
                    backdropSource = artwork,
                    cornerRadiusDp = 16f,
                    replaceBackgroundView = legibility,
                    minCaptureIntervalMs = 33L,
                )
            }
        }
    }

    fun installRuntimeHooks(
        context: Context,
        classLoader: ClassLoader,
        build: TargetBuild,
        glassObserver: MultiSurfaceGlassObserver,
    ): HostSubscription {
        observer = glassObserver
        if (!FragmentChromeFactory.supports(build)) return HostSubscription {}
        val appContext = context.applicationContext ?: context
        if (popupHooked.compareAndSet(false, true)) {
            runCatching { installPopupHooks() }
                .onFailure { error ->
                    scope.close()
                    ModernXposedRuntime.log("AM multi-surface popup hook degraded", error)
                }
        }
        if (blurViewHooked.compareAndSet(false, true)) {
            runCatching { installOptionButtonBlurHooks(appContext, classLoader, build) }
                .onFailure { error ->
                    ModernXposedRuntime.log("AM option_button_blur hook skipped", error)
                }
        }
        return HostSubscription {
            if (observer === glassObserver) observer = null
        }
    }

    private fun ensureScopeActivated() {
        if (!scope.isActive && !scope.isClosed) scope.activate()
    }

    private fun installOptionButtonBlurHooks(
        appContext: Context,
        classLoader: ClassLoader,
        build: TargetBuild,
    ) {
        if (build.versionCode == SUPPORTED_HAZE_VERSION_CODE) {
            runCatching { installLocalBlurTargetSentinel(appContext, classLoader) }
                .onFailure { error ->
                    ModernXposedRuntime.log("AM LocalBlurTarget sentinel skipped", error)
                }
            runCatching { installFloatingElementOverlaySuppressionHooks(classLoader) }
                .onFailure { error ->
                    ModernXposedRuntime.log("AM FloatingElement overlay suppression skipped", error)
                }
            runCatching { installFloatingButtonBorderSuppressionHooks(classLoader) }
                .onFailure { error ->
                    ModernXposedRuntime.log("AM FloatingButton border suppression skipped", error)
                }
            runCatching { installOverflowScrollHideHooks(appContext, classLoader) }
                .onFailure { error ->
                    ModernXposedRuntime.log("AM overflow scroll hide skipped", error)
                }
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { installHazeSourceHooks(classLoader) }
                    .onFailure { error ->
                        ModernXposedRuntime.log("AM HazeSource hook skipped", error)
                    }
            }
        }

        val blurViewClass = classLoader.loadClass("eightbitlab.com.blurview.BlurView")
        val setupMethod = blurViewClass.declaredMethods.single { method ->
            method.parameterCount == 1 && method.parameterTypes[0].name == "eightbitlab.com.blurview.BlurTarget"
        }.apply { isAccessible = true }
        val controllerType = setupMethod.returnType
        val controllerField = blurViewClass.declaredFields.first { it.type == controllerType }.apply { isAccessible = true }
        val destroyMethod = runCatching { controllerType.getMethod("destroy") }.getOrNull()

        ModernXposedRuntime.hookMethod(setupMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive) return
                val blurView = param.thisObject as? ViewGroup ?: return
                val isTarget = blurView.getTag(OPTION_BUTTON_BLUR_MARKER_KEY) == true ||
                    resourceName(blurView) == "option_button_blur"
                if (!isTarget) return
                if (blurView.getTag(NOOP_CONTROLLER_KEY) == null) {
                    runCatching { controllerField.get(blurView) }
                        .getOrNull()
                        ?.let { blurView.setTag(NOOP_CONTROLLER_KEY, it) }
                }
                val targetArg = param.args.getOrNull(0)
                if (sentinelBlurTarget != null && targetArg === sentinelBlurTarget) {
                    param.result = blurView.getTag(NOOP_CONTROLLER_KEY) ?: runCatching {
                        controllerField.get(blurView)
                    }.getOrNull()
                    emitWhenAttached(blurView) { buildFallbackActionButtonAnchor(blurView) }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val blurView = param.thisObject as? ViewGroup ?: return
                val isTarget = blurView.getTag(OPTION_BUTTON_BLUR_MARKER_KEY) == true ||
                    resourceName(blurView) == "option_button_blur"
                if (!isTarget) return
                val blurTarget = param.args.getOrNull(0) as? View ?: return
                if (sentinelBlurTarget != null && blurTarget === sentinelBlurTarget) return
                if (isDescendant(blurView, blurTarget)) return

                val savedNoOp = blurView.getTag(NOOP_CONTROLLER_KEY)
                val createdController = param.result
                if (savedNoOp != null) {
                    if (createdController != null && createdController !== savedNoOp) {
                        runCatching { destroyMethod?.invoke(createdController) }
                    }
                    runCatching { controllerField.set(blurView, savedNoOp) }
                }
                emitWhenAttached(blurView) {
                    buildDetachedPopupMenuAnchor(blurView)?.let { return@emitWhenAttached it }
                    if (!isDescendant(blurView, blurTarget)) {
                        GlassSurfaceAnchor(
                            role = GlassSurfaceRole.FLOATING_ACTION_BUTTON,
                            container = blurView,
                            backdropSource = blurTarget,
                            cornerRadiusDp = 999f,
                            forceRefreshOnPreDraw = true,
                            minCaptureIntervalMs = 16L,
                        )
                    } else null
                }
            }
        }, scope)
        ensureScopeActivated()
    }

    private fun installLocalBlurTargetSentinel(context: Context, classLoader: ClassLoader) {
        val blurTargetClass = classLoader.loadClass("eightbitlab.com.blurview.BlurTarget")
        val sentinel = blurTargetClass.getConstructor(Context::class.java).newInstance(context) as View
        val compositionLocals = classLoader.loadClass("Rb.n")
        val localBlurTarget = compositionLocals.getDeclaredField("a").apply { isAccessible = true }.get(null)
            ?: return
        val holderField = findFieldInHierarchy(localBlurTarget.javaClass, "a")
            ?: error("CompositionLocal defaultValueHolder field missing")
        val lazyHolder = holderField.get(localBlurTarget) ?: return
        val lazyField = findFieldInHierarchy(lazyHolder.javaClass, "a")
            ?: error("LazyValueHolder current field missing")
        val lazy = lazyField.get(lazyHolder) ?: return
        val lazyClass = lazy.javaClass
        val initField = lazyClass.getDeclaredField("a").apply { isAccessible = true }
        val valueField = lazyClass.getDeclaredField("b").apply { isAccessible = true }
        val lock = runCatching { lazyClass.getDeclaredField("c").apply { isAccessible = true }.get(lazy) }
            .getOrNull() ?: lazy
        synchronized(lock) {
            valueField.set(lazy, sentinel)
            initField.set(lazy, null)
        }
        sentinelBlurTarget = sentinel
    }

    private fun installFloatingElementOverlaySuppressionHooks(classLoader: ClassLoader) {
        val floatingElementClass = classLoader.loadClass("Ba.j0")
        val schemeClass = classLoader.loadClass("Ba.l0")
        val applyCircleMethod = floatingElementClass.declaredMethods.firstOrNull { method ->
            method.name == "d" && method.parameterCount == 2 && method.parameterTypes[1] == schemeClass
        }?.apply { isAccessible = true }
        if (applyCircleMethod != null) {
            ModernXposedRuntime.hookMethod(applyCircleMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val modifier = param.args.getOrNull(0) ?: return
                    param.result = modifier
                }
            }, scope)
        }

        val roundRectDrawClass = runCatching { classLoader.loadClass("Ba.h0") }.getOrNull()
        val roundRectInvoke = roundRectDrawClass?.declaredMethods?.firstOrNull { method ->
            method.name == "invoke" && method.parameterCount == 1
        }?.apply { isAccessible = true }
        if (roundRectInvoke != null) {
            ModernXposedRuntime.hookMethod(roundRectInvoke, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    param.result = null
                }
            }, scope)
        }
        ensureScopeActivated()
    }

    private fun installFloatingButtonBorderSuppressionHooks(classLoader: ClassLoader) {
        val containerClass = classLoader.loadClass("Ba.C")
        val modifierClass = classLoader.loadClass("O0.i")
        val eMethod = containerClass.declaredMethods.firstOrNull { method ->
            method.name == "e" && method.parameterCount == 8 && method.parameterTypes[0] == modifierClass
        }?.apply { isAccessible = true }
        if (eMethod != null) {
            ModernXposedRuntime.hookMethod(eMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    floatingButtonDepth.set((floatingButtonDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val current = floatingButtonDepth.get() ?: 0
                    floatingButtonDepth.set((current - 1).coerceAtLeast(0))
                }
            }, scope)
        }

        val dropdownClass = runCatching { classLoader.loadClass("Ba.D") }.getOrNull()
        val dropdownShapeField = dropdownClass?.let { cls ->
            runCatching { cls.getDeclaredField("a").apply { isAccessible = true } }.getOrNull()
        }
        val dropdownInvoke = dropdownClass?.declaredMethods?.firstOrNull { method ->
            method.name == "invoke" && method.parameterCount == 3
        }?.apply { isAccessible = true }
        if (dropdownInvoke != null && dropdownShapeField != null) {
            ModernXposedRuntime.hookMethod(dropdownInvoke, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entered = dropdownEnteredStack.get() ?: ArrayDeque<Boolean>().also {
                        dropdownEnteredStack.set(it)
                    }
                    val shape = if (scope.isActive) {
                        runCatching { dropdownShapeField.get(param.thisObject) }.getOrNull()
                    } else null
                    if (shape != null) {
                        val stack = dropdownShapeStack.get() ?: ArrayDeque<Any>().also {
                            dropdownShapeStack.set(it)
                        }
                        stack.addLast(shape)
                        entered.addLast(true)
                    } else {
                        entered.addLast(false)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val entered = dropdownEnteredStack.get()
                    if (entered != null && !entered.isEmpty() && entered.removeLast()) {
                        dropdownShapeStack.get()?.takeIf { !it.isEmpty() }?.removeLast()
                    }
                }
            }, scope)
        }

        val borderMethod = classLoader.loadClass("K.E").declaredMethods.firstOrNull { method ->
            method.name == "a" && method.parameterCount == 4 && method.parameterTypes[2] == modifierClass
        }?.apply { isAccessible = true }
        if (borderMethod != null) {
            ModernXposedRuntime.hookMethod(borderMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val inFab = (floatingButtonDepth.get() ?: 0) > 0
                    val menuShape = dropdownShapeStack.get()?.peekLast()
                    val inMenu = menuShape != null && param.args.getOrNull(3) === menuShape
                    if (!inFab && !inMenu) return
                    val modifier = param.args.getOrNull(2) ?: return
                    param.result = modifier
                }
            }, scope)
        }

        val circleShape = runCatching {
            classLoader.loadClass("a0.h").getDeclaredField("a").apply { isAccessible = true }.get(null)
        }.getOrNull()
        val bgMethod = classLoader.loadClass("K.w").declaredMethods.firstOrNull { method ->
            method.name == "b" && method.parameterCount == 3 && method.parameterTypes[0] == modifierClass
        }?.apply { isAccessible = true }
        if (bgMethod != null) {
            ModernXposedRuntime.hookMethod(bgMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val shapeArg = param.args.getOrNull(2) ?: return
                    val inFab = (floatingButtonDepth.get() ?: 0) > 0 && circleShape != null && shapeArg === circleShape
                    val menuShape = dropdownShapeStack.get()?.peekLast()
                    val inMenu = menuShape != null && shapeArg === menuShape
                    if (inFab || inMenu) {
                        param.result = param.args.getOrNull(0)
                    }
                }
            }, scope)
        }
        ensureScopeActivated()
    }

    private fun installOverflowScrollHideHooks(appContext: Context, classLoader: ClassLoader) {
        val containerClass = classLoader.loadClass("Ba.C")
        val modifierClass = classLoader.loadClass("O0.i")
        val cMethod = containerClass.declaredMethods.firstOrNull { method ->
            method.name == "c" && method.parameterCount == 6 && method.parameterTypes[0] == modifierClass
        }?.apply { isAccessible = true }
        val fMethod = containerClass.declaredMethods.firstOrNull { method ->
            method.name == "f" &&
                method.parameterCount == 9 &&
                method.parameterTypes[0] == modifierClass &&
                method.parameterTypes[2] == String::class.java
        }?.apply { isAccessible = true } ?: return

        val protectedStringIds = listOf("navigate_up", "ax_close_button", "library_edit_done")
            .map { name -> appContext.resources.getIdentifier(name, "string", appContext.packageName) }
            .filter { it != 0 }

        fun isProtectedToolbarButton(contentDescription: String?): Boolean {
            if ((navBackComposeDepth.get() ?: 0) > 0) return true
            if (contentDescription == null) return false
            return protectedStringIds.any { id ->
                runCatching { appContext.resources.getString(id) == contentDescription }.getOrDefault(false)
            }
        }

        val graphicsLayerMethod = classLoader.loadClass("V0.L").declaredMethods.firstOrNull { method ->
            method.name == "a" && method.parameterCount == 2 && method.parameterTypes[0] == modifierClass
        }?.apply { isAccessible = true } ?: return

        val scopeInterface = classLoader.loadClass("V0.M")
        val setAlpha = scopeInterface.getDeclaredMethod("r", Float::class.javaPrimitiveType).apply { isAccessible = true }
        val setScaleX = scopeInterface.getDeclaredMethod("r0", Float::class.javaPrimitiveType).apply { isAccessible = true }
        val setScaleY = scopeInterface.getDeclaredMethod("f1", Float::class.javaPrimitiveType).apply { isAccessible = true }
        val setTranslationY = scopeInterface.getDeclaredMethod("F", Float::class.javaPrimitiveType).apply { isAccessible = true }

        val unitClass = classLoader.loadClass("kotlin.Unit")
        val unitInstance = unitClass.declaredFields.first { it.type == unitClass }.apply { isAccessible = true }.get(null)

        val floatStateClass = classLoader.loadClass("z0.A0")
        val hostCollapseFloatState = floatStateClass.getConstructor(Float::class.javaPrimitiveType).newInstance(0.0f)
        val floatStateGet = findMethodInHierarchy(floatStateClass, "a") ?: return
        val floatStateSet = findMethodInHierarchy(floatStateClass, "o", Float::class.javaPrimitiveType!!) ?: return

        val snapshotKtClass = classLoader.loadClass("Bh.b")
        val mutableStateOfMethod = snapshotKtClass.getDeclaredMethod("j", Any::class.java).apply { isAccessible = true }
        val hostOverflowHiddenState = mutableStateOfMethod.invoke(null, false) ?: return
        val stateGet = findMethodInHierarchy(hostOverflowHiddenState.javaClass, "getValue") ?: return
        val stateSet = findMethodInHierarchy(hostOverflowHiddenState.javaClass, "setValue", Any::class.java) ?: return

        val function1Class = classLoader.loadClass("oi.l")
        val density = appContext.resources.displayMetrics.density
        val newGraphicsLayerProxy: () -> Any = {
            Proxy.newProxyInstance(classLoader, arrayOf(function1Class)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AM++OverflowGraphicsLayer"
                    "invoke" -> {
                        val layer = args?.getOrNull(0)
                        if (layer != null) {
                            runCatching {
                                val p = ((floatStateGet.invoke(hostCollapseFloatState) as? Float) ?: 0f).coerceIn(0f, 1f)
                                val vis = (1f - p).coerceIn(0f, 1f)
                                val scale = 1f - 0.14f * p
                                setAlpha.invoke(layer, vis)
                                setScaleX.invoke(layer, scale)
                                setScaleY.invoke(layer, scale)
                                setTranslationY.invoke(layer, -12f * density * p)
                            }
                        }
                        unitInstance
                    }
                    else -> null
                }
            }
        }
        val visibleLayerProxy = newGraphicsLayerProxy()
        val hiddenLayerProxy = newGraphicsLayerProxy()

        if (cMethod != null) {
            ModernXposedRuntime.hookMethod(cMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    navBackComposeDepth.set((navBackComposeDepth.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val current = navBackComposeDepth.get() ?: 0
                    if (current > 0) navBackComposeDepth.set(current - 1)
                }
            }, scope)
        }

        ModernXposedRuntime.hookMethod(fMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val enteredStack = overflowScopeEnteredStack.get() ?: ArrayDeque<Boolean>().also {
                    overflowScopeEnteredStack.set(it)
                }
                val desc = param.args.getOrNull(2) as? String
                if (!scope.isActive || isProtectedToolbarButton(desc)) {
                    enteredStack.addLast(false)
                    return
                }
                val hidden = (runCatching { stateGet.invoke(hostOverflowHiddenState) }.getOrNull() as? Boolean) == true
                enteredStack.addLast(true)
                overflowComposeDepth.set((overflowComposeDepth.get() ?: 0) + 1)
                overflowComposeHidden.set(hidden)
                val baseMod = param.args.getOrNull(0) ?: return
                val wrapped = runCatching {
                    graphicsLayerMethod.invoke(null, baseMod, if (hidden) hiddenLayerProxy else visibleLayerProxy)
                }.getOrNull() ?: return
                param.args[0] = wrapped
                val changed = param.args.getOrNull(7) as? Int
                if (changed != null) {
                    param.args[7] = changed and 6.inv()
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val enteredStack = overflowScopeEnteredStack.get()
                val entered = if (enteredStack != null && !enteredStack.isEmpty()) {
                    enteredStack.removeLast()
                } else {
                    false
                }
                if (!entered) return
                val current = overflowComposeDepth.get() ?: 0
                overflowComposeDepth.set((current - 1).coerceAtLeast(0))
                if (current <= 1) overflowComposeHidden.set(false)
            }
        }, scope)

        val clickableClass = classLoader.loadClass("K.U")
        val clickableMethod = clickableClass.declaredMethods.firstOrNull { method ->
            method.name == "b" && method.parameterCount == 7 && method.parameterTypes[0] == modifierClass
        }?.apply { isAccessible = true }
        if (clickableMethod != null) {
            ModernXposedRuntime.hookMethod(clickableMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || (overflowComposeDepth.get() ?: 0) <= 0) return
                    if (overflowComposeHidden.get() == true) {
                        param.args[3] = false
                        val defaultFlags = param.args[6] as? Int ?: return
                        param.args[6] = defaultFlags and 4.inv()
                    }
                }
            }, scope)
        }

        val blurFactoryClass = classLoader.loadClass("Ba.g")
        val blurFactoryCtor = blurFactoryClass.getDeclaredConstructor(
            Any::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        ).apply { isAccessible = true }
        ModernXposedRuntime.hookMethod(blurFactoryCtor, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                if ((param.args.getOrNull(1) as? Int) == 0 && (overflowComposeDepth.get() ?: 0) > 0) {
                    val factory = param.thisObject ?: return
                    overflowBlurFactories[factory] = true
                }
            }
        }, scope)
        val blurFactoryInvoke = blurFactoryClass.declaredMethods.firstOrNull { method ->
            method.name == "invoke" && method.parameterCount == 1
        }?.apply { isAccessible = true }
        if (blurFactoryInvoke != null) {
            ModernXposedRuntime.hookMethod(blurFactoryInvoke, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || param.throwable != null) return
                    val factory = param.thisObject ?: return
                    if (!overflowBlurFactories.containsKey(factory)) return
                    val blurView = param.result as? View ?: return
                    overflowBlurViews[blurView] = true
                    applyOverflowViewState(blurView, ScrollCollapseBridge.progress)
                }
            }, scope)
        }

        val applyCollapse: (Float) -> Unit = { rawProgress ->
            val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
            runCatching { floatStateSet.invoke(hostCollapseFloatState, p) }
            val hidden = p >= 0.5f
            val prevHidden = (runCatching { stateGet.invoke(hostOverflowHiddenState) }.getOrNull() as? Boolean) == true
            if (hidden != prevHidden) {
                runCatching { stateSet.invoke(hostOverflowHiddenState, hidden) }
            }
            val blurSnapshot = synchronized(overflowBlurViews) { overflowBlurViews.keys.toList() }
            blurSnapshot.forEach { view -> applyOverflowViewState(view, p) }
            val nativeSnapshot = synchronized(overflowNativeViews) { overflowNativeViews.keys.toList() }
            nativeSnapshot.forEach { view -> applyOverflowViewState(view, p) }
        }
        collapseSubscription?.close()
        collapseSubscription = ScrollCollapseBridge.addListener { rawProgress ->
            if (!scope.isActive) return@addListener
            if (Looper.myLooper() === Looper.getMainLooper()) {
                applyCollapse(rawProgress)
            } else {
                mainHandler.post { if (scope.isActive) applyCollapse(rawProgress) }
            }
        }
        applyCollapse(ScrollCollapseBridge.progress)
        ensureScopeActivated()
    }

    private fun applyOverflowViewState(view: View, rawProgress: Float) {
        val p = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
        val vis = (1f - p).coerceIn(0f, 1f)
        val scale = 1f - 0.14f * p
        val ty = -12f * view.resources.displayMetrics.density * p
        if (view.alpha != vis) view.alpha = vis
        if (view.scaleX != scale) view.scaleX = scale
        if (view.scaleY != scale) view.scaleY = scale
        if (view.translationY != ty) view.translationY = ty
        val targetVis = if (p >= 0.99f) View.INVISIBLE else View.VISIBLE
        if (view.visibility != targetVis) view.visibility = targetVis
    }

    private fun installHazeSourceHooks(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val localHazeState = runCatching {
            classLoader.loadClass("Rb.n").getDeclaredField("b").apply { isAccessible = true }.get(null)
        }.getOrNull()
        if (localHazeState != null) {
            val providesMethod = localHazeState.javaClass.declaredMethods.firstOrNull { method ->
                method.name == "b" && method.parameterCount == 1
            }?.apply { isAccessible = true }
            if (providesMethod != null) {
                ModernXposedRuntime.hookMethod(providesMethod, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!scope.isActive || param.thisObject !== localHazeState) return
                        val hazeState = param.args.getOrNull(0) ?: return
                        toolbarHazeStates[hazeState] = true
                    }
                }, scope)
            }
        }

        val nodeClass = classLoader.loadClass("mh.u")
        val areaField = nodeClass.getDeclaredField("L").apply { isAccessible = true }
        val stateField = runCatching { nodeClass.getDeclaredField("Q").apply { isAccessible = true } }.getOrNull()
        val disposableField = nodeClass.getDeclaredField("U").apply { isAccessible = true }
        val disposableClass = classLoader.loadClass("mh.E")
        val hostViewField = disposableClass.declaredFields
            .first { View::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }

        val nodeCoordinatorField = findFieldInHierarchy(nodeClass, "h")
            ?: error("Compose Modifier.Node coordinator field missing")
        val coordinatorLayoutNodeField = findFieldInHierarchy(nodeCoordinatorField.type, "M")
            ?: error("Compose NodeCoordinator layoutNode field missing")
        val layoutNodeParentMethod = findMethodInHierarchy(coordinatorLayoutNodeField.type, "H")
            ?: error("Compose LayoutNode parent method missing")
        val androidViewHolderClass = classLoader.loadClass("R1.b")
        val androidViewHolderGetLayoutNode = androidViewHolderClass.getDeclaredMethod("getLayoutNode")
            .apply { isAccessible = true }

        val areaClass = classLoader.loadClass("mh.g")
        val layerClass = classLoader.loadClass("Y0.e")
        val implClass = classLoader.loadClass("Y0.h")
        val zIndexField = runCatching { areaClass.getDeclaredField("c").apply { isAccessible = true } }.getOrNull()
        val zIndexGetValue = zIndexField?.let { f ->
            runCatching { findMethodInHierarchy(f.type, "a")?.apply { isAccessible = true } }.getOrNull()
        }
        val areaContentDrawingField = runCatching { areaClass.getDeclaredField("g").apply { isAccessible = true } }.getOrNull()
        hazeReflector = HazeReflector(
            areaField = areaField,
            stateField = stateField,
            disposableField = disposableField,
            hostViewField = hostViewField,
            areaGetLayer = areaClass.getDeclaredMethod("a").apply { isAccessible = true },
            areaGetPos = areaClass.getDeclaredMethod("b").apply { isAccessible = true },
            zIndexField = zIndexField,
            zIndexGetValue = zIndexGetValue,
            areaContentDrawingField = areaContentDrawingField,
            layerImplField = layerClass.declaredFields.first { it.type == implClass }.apply { isAccessible = true },
            layerReleasedField = layerClass.getDeclaredField("s").apply { isAccessible = true },
            renderNodeField = implClass.declaredFields.first { it.type == RenderNode::class.java }.apply { isAccessible = true },
            nodeCoordinatorField = nodeCoordinatorField,
            coordinatorLayoutNodeField = coordinatorLayoutNodeField,
            layoutNodeParentMethod = layoutNodeParentMethod,
            androidViewHolderClass = androidViewHolderClass,
            androidViewHolderGetLayoutNode = androidViewHolderGetLayoutNode,
        )

        val onAttach = nodeClass.getDeclaredMethod("j2").apply { isAccessible = true }
        val onDetach = nodeClass.getDeclaredMethod("k2").apply { isAccessible = true }

        ModernXposedRuntime.hookMethod(onAttach, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val node = param.thisObject ?: return
                val disposable = disposableField.get(node) ?: return
                val hostView = hostViewField.get(disposable) as? View ?: return
                val area = areaField.get(node) ?: return
                val hazeState = stateField?.let { runCatching { it.get(node) }.getOrNull() }
                val list = synchronized(hazeAreasByHostView) {
                    hazeAreasByHostView.getOrPut(hostView) { CopyOnWriteArrayList() }
                }
                list.removeAll { it.areaRef.get() == null || it.areaRef.get() === area }
                list.add(HazeAreaEntry(WeakReference(area), WeakReference(hazeState), WeakReference(node)))
            }
        }, scope)

        ModernXposedRuntime.hookMethod(onDetach, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val node = param.thisObject ?: return
                val area = runCatching { areaField.get(node) }.getOrNull() ?: return
                val hostView = runCatching { disposableField.get(node) }.getOrNull()
                    ?.let { runCatching { hostViewField.get(it) as? View }.getOrNull() }
                if (hostView != null) {
                    hazeAreasByHostView[hostView]?.removeAll { it.areaRef.get() == null || it.areaRef.get() === area }
                } else {
                    synchronized(hazeAreasByHostView) {
                        hazeAreasByHostView.values.forEach { list ->
                            list.removeAll { it.areaRef.get() == null || it.areaRef.get() === area }
                        }
                    }
                }
            }
        }, scope)
        ensureScopeActivated()
    }

    private fun resolveConsumerLayoutNode(blurView: View, reflector: HazeReflector): Any? {
        var current: View? = blurView.parent as? View
        while (current != null) {
            if (reflector.androidViewHolderClass.isInstance(current)) {
                return runCatching { reflector.androidViewHolderGetLayoutNode.invoke(current) }.getOrNull()
            }
            current = current.parent as? View
        }
        return null
    }

    private fun resolveSourceLayoutNode(node: Any, reflector: HazeReflector): Any? {
        val coordinator = runCatching { reflector.nodeCoordinatorField.get(node) }.getOrNull() ?: return null
        return runCatching { reflector.coordinatorLayoutNodeField.get(coordinator) }.getOrNull()
    }

    private fun isComposeAncestor(candidateAncestor: Any, descendant: Any, reflector: HazeReflector): Boolean {
        var cur: Any? = descendant
        var steps = 0
        while (cur != null && steps++ < 128) {
            if (cur === candidateAncestor) return true
            cur = runCatching { reflector.layoutNodeParentMethod.invoke(cur) }.getOrNull()
        }
        return false
    }

    private fun safeHazeAreasFor(
        host: View,
        consumerLayoutNode: Any,
        reflector: HazeReflector,
    ): Pair<Boolean, List<Any>> {
        val entries = hazeAreasByHostView[host] ?: return false to emptyList()
        val safeEntries = entries.mapNotNull { entry ->
            val area = entry.areaRef.get() ?: return@mapNotNull null
            val node = entry.nodeRef.get() ?: return@mapNotNull null
            val sourceLayoutNode = resolveSourceLayoutNode(node, reflector) ?: return@mapNotNull null
            if (isComposeAncestor(sourceLayoutNode, consumerLayoutNode, reflector)) return@mapNotNull null
            area to entry.stateRef.get()
        }
        if (safeEntries.isEmpty()) return false to emptyList()
        val matched = safeEntries.filter { (_, state) ->
            state != null && toolbarHazeStates.containsKey(state)
        }.map { it.first }
        return if (matched.isNotEmpty()) {
            true to matched
        } else {
            false to safeEntries.map { it.first }
        }
    }

    private fun buildDetachedPopupMenuAnchor(blurView: ViewGroup): GlassSurfaceAnchor? {
        val activityContent = resolveActivityContent(blurView.context) ?: return null
        if (blurView.rootView === activityContent.rootView) return null
        if (blurView === activityContent || isDescendant(blurView, activityContent)) return null
        return GlassSurfaceAnchor(
            role = GlassSurfaceRole.CONTEXT_POPUP_MENU,
            container = blurView,
            backdropSource = activityContent,
            cornerRadiusDp = 24f,
            forceRefreshOnPreDraw = true,
            minCaptureIntervalMs = 16L,
        )
    }

    private fun buildFallbackActionButtonAnchor(blurView: ViewGroup): GlassSurfaceAnchor? {
        buildDetachedPopupMenuAnchor(blurView)?.let { return it }
        if (Build.VERSION.SDK_INT >= 33) {
            val reflector = hazeReflector
            if (reflector != null) {
                val hostView = findHazeHostView(blurView)
                if (hostView != null && hostView !== blurView) {
                    val consumerLayoutNode = resolveConsumerLayoutNode(blurView, reflector)
                    if (consumerLayoutNode != null &&
                        safeHazeAreasFor(hostView, consumerLayoutNode, reflector).second.isNotEmpty()
                    ) {
                        val hostRef = WeakReference(hostView)
                        val consumerNodeRef = WeakReference(consumerLayoutNode)
                        val hazeRenderer: (Canvas) -> Boolean = { canvas ->
                            drawHostHazeAreas(canvas, hostRef.get(), consumerNodeRef.get(), reflector)
                            true
                        }
                        return GlassSurfaceAnchor(
                            role = GlassSurfaceRole.FLOATING_ACTION_BUTTON,
                            container = blurView,
                            backdropSource = hostView,
                            cornerRadiusDp = 999f,
                            sourceRenderer = hazeRenderer,
                            forceRefreshOnPreDraw = true,
                            minCaptureIntervalMs = 16L,
                        )
                    }
                }
            }
        }

        val albumRecycler = findAlbumSiblingRecyclerView(blurView)
        if (albumRecycler != null) {
            val rvRef = WeakReference(albumRecycler)
            val albumRenderer: (Canvas) -> Boolean = { canvas ->
                val rv = rvRef.get()
                if (rv != null) {
                    val parentBg = (rv.parent as? View)?.background
                    if (parentBg is ColorDrawable) {
                        canvas.drawColor(parentBg.color)
                    } else if (parentBg != null) {
                        val save = canvas.save()
                        try {
                            canvas.translate(-rv.left.toFloat(), -rv.top.toFloat())
                            parentBg.draw(canvas)
                        } finally {
                            canvas.restoreToCount(save)
                        }
                    }
                    rv.draw(canvas)
                }
                true
            }
            return GlassSurfaceAnchor(
                role = GlassSurfaceRole.FLOATING_ACTION_BUTTON,
                container = blurView,
                backdropSource = albumRecycler,
                cornerRadiusDp = 999f,
                sourceRenderer = albumRenderer,
                forceRefreshOnPreDraw = true,
                minCaptureIntervalMs = 16L,
            )
        }

        val blurTarget = findNearestBlurTarget(blurView)?.takeIf { !isDescendant(blurView, it) } ?: return null
        return GlassSurfaceAnchor(
            role = GlassSurfaceRole.FLOATING_ACTION_BUTTON,
            container = blurView,
            backdropSource = blurTarget,
            cornerRadiusDp = 999f,
            forceRefreshOnPreDraw = true,
            minCaptureIntervalMs = 16L,
        )
    }

    private fun drawHostHazeAreas(
        canvas: Canvas,
        host: View?,
        consumerLayoutNode: Any?,
        reflector: HazeReflector,
    ) {
        if (host == null || consumerLayoutNode == null || Build.VERSION.SDK_INT < 29) return
        val (hasMatchedStates, candidateAreas) = safeHazeAreasFor(host, consumerLayoutNode, reflector)
        if (candidateAreas.isEmpty()) return
        val areas = if (candidateAreas.size > 1 && reflector.zIndexField != null && reflector.zIndexGetValue != null) {
            candidateAreas.sortedBy { area ->
                runCatching {
                    val stateObj = reflector.zIndexField.get(area) ?: return@runCatching 0f
                    (reflector.zIndexGetValue.invoke(stateObj) as? Float) ?: 0f
                }.getOrDefault(0f)
            }
        } else candidateAreas

        val hostLoc = IntArray(2)
        host.getLocationOnScreen(hostLoc)
        val hostX = hostLoc[0].toFloat()
        val hostY = hostLoc[1].toFloat()
        val hostW = host.width
        val hostH = host.height

        for (area in areas) {
            var drewFullScreen = false
            runCatching {
                if (reflector.areaContentDrawingField?.getBoolean(area) == true) return@runCatching
                val layer = reflector.areaGetLayer.invoke(area) ?: return@runCatching
                if (reflector.layerReleasedField.getBoolean(layer)) return@runCatching
                val impl = reflector.layerImplField.get(layer) ?: return@runCatching
                val renderNode = reflector.renderNodeField.get(impl) as? RenderNode ?: return@runCatching
                if (!renderNode.hasDisplayList() || renderNode.width <= 0 || renderNode.height <= 0) return@runCatching
                val packed = (reflector.areaGetPos.invoke(area) as? Long) ?: return@runCatching
                val x = Float.fromBits((packed ushr 32).toInt())
                val y = Float.fromBits((packed and 0xFFFFFFFFL).toInt())
                val tx = if (x.isFinite()) x - hostX else 0f
                val ty = if (y.isFinite()) y - hostY else 0f
                val save = canvas.save()
                try {
                    canvas.translate(tx, ty)
                    canvas.drawRenderNode(renderNode)
                } finally {
                    canvas.restoreToCount(save)
                }
                if (hostW > 0 && hostH > 0 &&
                    kotlin.math.abs(tx) < 2f && kotlin.math.abs(ty) < 2f &&
                    renderNode.width >= (hostW * 9) / 10 &&
                    renderNode.height >= (hostH * 9) / 10
                ) {
                    drewFullScreen = true
                }
            }
            if (!hasMatchedStates && drewFullScreen) break
        }
    }

    private fun findHazeHostView(start: View): View? {
        var current: View? = start.parent as? View
        while (current != null) {
            val areas = hazeAreasByHostView[current]
            if (areas != null && areas.any { it.areaRef.get() != null }) return current
            current = current.parent as? View
        }
        return null
    }

    private fun findAlbumSiblingRecyclerView(start: View): View? {
        var current: View? = start.parent as? View
        while (current != null) {
            if (resourceName(current) == "floating_app_bar_container") {
                val parent = current.parent as? ViewGroup ?: return null
                val rv = findByName(parent, "recycler_view") ?: return null
                return if (!isDescendant(start, rv)) rv else null
            }
            current = current.parent as? View
        }
        return null
    }

    private fun findFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { return current.getDeclaredField(name).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun findMethodInHierarchy(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { return current.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun installPopupHooks() {
        val hook = object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val popup = param.thisObject as? PopupWindow ?: return
                val content = popup.contentView as? ViewGroup ?: return
                val isMarked = content.getTag(POPUP_MARKER_KEY) == true || trackedPopups.containsKey(content)
                if (!isMarked) return
                val anchor = param.args.getOrNull(0) as? View ?: return
                val backdropRoot = resolveActivityContent(anchor.context) ?: anchor.rootView ?: return
                val popupDecor = (content.rootView as? FrameLayout) ?: return
                if (popupDecor === backdropRoot || isDescendant(popupDecor, backdropRoot)) return
                runCatching { popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
                observer?.onSurfaceAttached(
                    GlassSurfaceAnchor(
                        role = GlassSurfaceRole.CONTEXT_POPUP_MENU,
                        container = popupDecor,
                        backdropSource = backdropRoot,
                        cornerRadiusDp = 24f,
                        replaceBackgroundView = content,
                        forceRefreshOnPreDraw = true,
                        minCaptureIntervalMs = 16L,
                    ),
                )
            }
        }
        ModernXposedRuntime.hookMethod(
            PopupWindow::class.java.getDeclaredMethod(
                "showAsDropDown",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            hook,
            scope,
        )
        ModernXposedRuntime.hookMethod(
            PopupWindow::class.java.getDeclaredMethod(
                "showAtLocation",
                View::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ),
            hook,
            scope,
        )
        ensureScopeActivated()
    }

    private fun emitWhenAttached(view: View, buildAnchor: () -> GlassSurfaceAnchor?) {
        val dispatch = Runnable {
            if (!view.isAttachedToWindow) return@Runnable
            buildAnchor()?.let { observer?.onSurfaceAttached(it) }
        }
        if (view.isAttachedToWindow) {
            view.post(dispatch)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    v.post(dispatch)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun findPlayerBackdropSibling(start: View): View? {
        var current: View? = start
        while (current != null) {
            val idName = resourceName(current)
            if (idName == "player_root") {
                val root = current as? ViewGroup ?: return null
                return findByName(root, "player_background") ?: findByName(root, "player_motion")
            }
            current = current.parent as? View
        }
        return null
    }

    private fun findNearestBlurTarget(start: View): View? {
        var current: View? = start.parent as? View
        while (current != null) {
            if (current is ViewGroup) {
                val found = findByName(current, "blur_target")
                if (found != null) return found
            }
            current = current.parent as? View
        }
        return null
    }

    private fun findByName(root: ViewGroup, entryName: String): View? {
        val id = root.resources.getIdentifier(entryName, "id", root.context.packageName)
        if (id != 0) root.findViewById<View>(id)?.let { return it }
        return null
    }

    private fun resourceName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    private fun resolveActivityContent(context: Context): ViewGroup? {
        var current: Context? = context
        val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        while (current != null && seen.add(current)) {
            if (current is Activity) {
                val content = current.findViewById<ViewGroup>(android.R.id.content)
                return (content?.getChildAt(0) as? ViewGroup) ?: content
            }
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun isDescendant(candidate: View, ancestor: View): Boolean {
        var current: View? = candidate
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
}
