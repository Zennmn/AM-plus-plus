package dev.amenhancer.module.hook

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.classical.ClassicalHostProfile
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Arrays
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

internal fun flattenClassicalMenuGroups(groups: List<*>): List<List<Any?>>? {
    if (groups.size <= 1) return null
    val flattened = ArrayList<Any?>()
    for (group in groups) {
        val items = group as? List<*> ?: return null
        flattened.addAll(items)
    }
    return listOf(flattened)
}

internal fun resolveClassicalArtworkUrl(rawUrl: String?, sizePx: Int = 200): String? {
    val template = rawUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val size = sizePx.coerceAtLeast(1).toString()
    return template
        .replace("{w}", size)
        .replace("{h}", size)
        .replace("{c}", "")
        .replace("{f}", "jpg")
}

internal fun computeClassicalPlayerExpansion(
    startExpanded: Boolean,
    endExpanded: Boolean,
    motionProgress: Float,
): Float {
    val p = if (motionProgress.isFinite()) motionProgress.coerceIn(0f, 1f) else 0f
    val start = if (startExpanded) 1f else 0f
    val end = if (endExpanded) 1f else 0f
    return (start + (end - start) * p).coerceIn(0f, 1f)
}

private fun smoothStep(value: Float, start: Float, end: Float): Float {
    if (!value.isFinite()) return 0f
    if (end <= start) return if (value >= end) 1f else 0f
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun computeClassicalBackgroundAlpha(expansion: Float): Float =
    smoothStep(expansion, 0.35f, 0.60f)

internal fun computeClassicalArtworkAlpha(expansion: Float): Float =
    smoothStep(expansion, 0.55f, 0.72f)

internal fun shouldSuppressCollapsedClassicalLayer(expansion: Float): Boolean =
    !expansion.isFinite() || expansion <= 0.02f

internal fun mapClassicalPlayerState(
    current: ClassicalChromeSnapshot,
    isHidden: Boolean,
    isMinimized: Boolean,
): ClassicalChromeSnapshot {
    val expanded = !isHidden && !isMinimized
    return if (isHidden) {
        current.copy(
            playerExpanded = false,
            playerExpansion = 0f,
            miniPlayer = current.miniPlayer?.copy(visible = false),
        )
    } else {
        current.copy(
            playerExpanded = expanded,
            playerExpansion = if (expanded) 1f else 0f,
        )
    }
}

/**
 * Installs Classical 4.1 Compose-tree chrome & dialog hooks behind [ClassicalChromePort] and
 * [MultiSurfaceGlassObserver] without leaking reflection types out of `:host-classical`.
 */
internal class ClassicalChromeHooks(
    private val applicationContext: Context,
    private val classLoader: ClassLoader,
    private val profile: ClassicalHostProfile,
    private val dialogGlassObserver: MultiSurfaceGlassObserver? = null,
) : ClassicalChromePort {
    private val scope = HookRegistrationScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ClassicalChromeSnapshot) -> Unit>()
    private val installed = AtomicBoolean(false)
    private val chromeGlassActive = AtomicBoolean(false)
    private val activeDialogCount = AtomicInteger(0)
    private val trackedDialogs = Collections.synchronizedMap(WeakHashMap<Dialog, Boolean>())
    private val trackedDropdownPopups = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var hostExpansionState: Any? = null

    @Volatile
    private var hostGlassActiveState: Any? = null

    @Volatile
    private var floatStateGetMethod: Method? = null

    @Volatile
    private var floatStateSetMethod: Method? = null

    @Volatile
    private var artworkVisualScale: Float = 1f

    @Volatile
    private var snapshot = ClassicalChromeSnapshot(
        selectedTabIndex = 0,
        tabs = defaultTabs(),
        playerExpanded = false,
        searchActive = false,
        miniPlayer = null,
    )

    @Volatile
    private var latestTabCallback: Any? = null

    @Volatile
    private var callbackInvokeMethod: Method? = null

    @Volatile
    private var homeRouteFactory: (() -> Any)? = null

    @Volatile
    private var libraryRouteFactory: (() -> Any)? = null

    @Volatile
    private var searchRouteFactory: (() -> Any)? = null

    @Volatile
    private var latestPlayerController: Any? = null

    @Volatile
    private var controllerEventMethod: Method? = null

    @Volatile
    private var miniPlayerClickEvent: Any? = null

    @Volatile
    private var motionObserved = false

    @Volatile
    private var latestDraggable: Any? = null

    @Volatile
    private var latestVelocityTracker: Any? = null

    @Volatile
    private var latestSettleCallback: Any? = null

    @Volatile
    private var unitInvokeNameValue: String = "invoke"

    @Volatile
    private var yaDispatchRawDelta: Method? = null

    @Volatile
    private var yaDragScopeField: Field? = null

    @Volatile
    private var yaDragScopeToMethod: Method? = null

    @Volatile
    private var trackerHolderField: Field? = null

    @Volatile
    private var holderTimeField: Field? = null

    @Volatile
    private var holderXTrackerField: Field? = null

    @Volatile
    private var holderYTrackerField: Field? = null

    @Volatile
    private var tracker1DPointsField: Field? = null

    @Volatile
    private var tracker1DIndexField: Field? = null

    @Volatile
    private var tracker1DAddMethod: Method? = null

    private val composeScrollOwners = ConcurrentHashMap<Class<*>, Boolean>()

    fun install(): HostSubscription {
        if (installed.compareAndSet(false, true)) {
            runCatching { installHooks() }
                .onFailure { error ->
                    scope.close()
                    ModernXposedRuntime.log("Classical chrome hooks degraded", error)
                }
        }
        return HostSubscription {
            chromeGlassActive.set(false)
            listeners.clear()
        }
    }

    override fun observe(listener: (ClassicalChromeSnapshot) -> Unit): HostSubscription {
        listeners += listener
        listener(snapshot)
        return HostSubscription { listeners.remove(listener) }
    }

    override fun selectTab(index: Int) {
        val callback = latestTabCallback ?: return
        val invoke = callbackInvokeMethod ?: return
        val route = when (index) {
            0 -> homeRouteFactory?.invoke()
            1 -> libraryRouteFactory?.invoke()
            2 -> searchRouteFactory?.invoke()
            else -> null
        } ?: return
        runCatching { invoke.invoke(callback, route) }
            .onFailure { ModernXposedRuntime.log("Classical tab selection failed", it) }
    }

    override fun setChromeGlassActive(active: Boolean) {
        chromeGlassActive.set(active)
        val state = hostGlassActiveState ?: return
        val setter = floatStateSetMethod ?: return
        val target = if (active) 1f else 0f
        if (Looper.myLooper() === Looper.getMainLooper()) {
            runCatching { setter.invoke(state, target) }
        } else {
            mainHandler.post { runCatching { setter.invoke(state, target) } }
        }
    }

    private fun setHostExpansion(expansion: Float) {
        val state = hostExpansionState ?: return
        val setter = floatStateSetMethod ?: return
        val value = if (expansion.isFinite()) expansion.coerceIn(0f, 1f) else 0f
        runCatching { setter.invoke(state, value) }
    }

    override fun beginPlayerDrag() {
        val tracker = latestVelocityTracker ?: return
        val holderField = trackerHolderField ?: return
        val xField = holderXTrackerField ?: return
        val yField = holderYTrackerField ?: return
        val timeField = holderTimeField ?: return
        val pointsField = tracker1DPointsField ?: return
        val indexField = tracker1DIndexField ?: return
        runCatching {
            val holder = holderField.get(tracker) ?: return
            for (field in arrayOf(xField, yField)) {
                val tracker1D = field.get(holder) ?: continue
                val points = pointsField.get(tracker1D) as? Array<*>
                if (points != null) Arrays.fill(points, null)
                indexField.setInt(tracker1D, 0)
            }
            timeField.setLong(holder, 0L)
        }
    }

    override fun dragPlayerBy(deltaY: Float, eventTimeMs: Long, positionY: Float): Boolean {
        if (!deltaY.isFinite() || snapshot.miniPlayer?.visible != true) return false
        val draggable = latestDraggable ?: return false
        val dispatchMethod = yaDispatchRawDelta ?: return false
        val scopeField = yaDragScopeField ?: return false
        val dragToMethod = yaDragScopeToMethod ?: return false
        return runCatching {
            val tracker = latestVelocityTracker
            val holder = tracker?.let { trackerHolderField?.get(it) }
            val yTracker = holder?.let { holderYTrackerField?.get(it) }
            if (holder != null && yTracker != null && positionY.isFinite()) {
                tracker1DAddMethod?.invoke(yTracker, eventTimeMs, positionY)
                holderTimeField?.setLong(holder, eventTimeMs)
            }
            val clamped = dispatchMethod.invoke(draggable, deltaY) as? Float ?: return false
            val dragScope = scopeField.get(draggable) ?: return false
            dragToMethod.invoke(dragScope, clamped, 0f)
            true
        }.getOrElse { error ->
            ModernXposedRuntime.log("Classical player drag failed", error)
            false
        }
    }

    override fun settlePlayerDrag() {
        val settle = latestSettleCallback
        if (settle != null) {
            val invoked = runCatching {
                settle.javaClass.getMethod(unitInvokeNameValue).invoke(settle)
                true
            }.getOrElse { error ->
                ModernXposedRuntime.log("Classical player drag settle failed", error)
                false
            }
            if (invoked) return
        }
        if (snapshot.playerExpansion >= 0.25f) {
            val controller = latestPlayerController ?: return
            val eventMethod = controllerEventMethod ?: return
            val event = miniPlayerClickEvent ?: return
            runCatching { eventMethod.invoke(controller, event) }
        }
    }

    override fun findScrollOwner(root: View): View? {
        val ownsScrollSemantics = composeScrollOwners.getOrPut(root.javaClass) {
            runCatching {
                root.javaClass.getMethod("canScrollVertically", Int::class.javaPrimitiveType)
                    .declaringClass != View::class.java
            }.getOrDefault(false)
        }
        if (ownsScrollSemantics) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findScrollOwner(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    override fun canScrollVertically(view: View, direction: Int): Boolean? =
        runCatching { view.canScrollVertically(direction) }.getOrNull()

    private fun publish(next: ClassicalChromeSnapshot) {
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { listener ->
            runCatching { listener(next) }
        }
    }

    private fun defaultTabs(): List<ClassicalNavTab> {
        val res = applicationContext.resources
        val pkg = profile.packageName
        fun str(name: String, fallback: String): String {
            val id = res.getIdentifier(name, "string", pkg)
            return if (id != 0) runCatching { res.getString(id) }.getOrDefault(fallback) else fallback
        }
        fun icon(name: String): Int = res.getIdentifier(name, "drawable", pkg)
        return listOf(
            ClassicalNavTab(0, str("navigation_home", "Listen Now"), icon("ic_tab_home"), "navigation_home"),
            ClassicalNavTab(1, str("navigation_library", "Library"), icon("ic_tab_library"), "navigation_library"),
            ClassicalNavTab(2, str("navigation_search", "Search"), icon("ic_tab_search"), "navigation_search"),
        )
    }

    private fun installHooks() {
        val chrome = profile.document.getJSONObject("chrome")
        val guideContract = profile.document.getJSONObject("listeningGuide")

        val homeClass = classLoader.loadClass(chrome.getString("homeRouteClass"))
        val libClass = classLoader.loadClass(chrome.getString("libraryRouteClass"))
        val searchClass = classLoader.loadClass(chrome.getString("searchRouteClass"))
        val homeCtor = homeClass.getDeclaredConstructor().apply { isAccessible = true }
        val libCtor = libClass.getDeclaredConstructor().apply { isAccessible = true }
        val searchCtor = searchClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        homeRouteFactory = { homeCtor.newInstance() }
        libraryRouteFactory = { libCtor.newInstance() }
        searchRouteFactory = { searchCtor.newInstance(false) }

        // Resolve Compose Modifier.alpha(float) so native bottom bar & mini player hide cleanly.
        val alphaClass = classLoader.loadClass(chrome.getString("alphaModifierClass"))
        val alphaMethod = alphaClass.declaredMethods.single { method ->
            method.name == chrome.getString("alphaModifierMethod") &&
                method.parameterCount == 2 &&
                method.parameterTypes[1] == Float::class.javaPrimitiveType
        }.apply { isAccessible = true }
        fun hideModifier(modifier: Any?): Any? {
            if (modifier == null) return null
            return runCatching { alphaMethod.invoke(null, modifier, 0f) }.getOrDefault(modifier)
        }

        val routeBaseClass = homeClass.interfaces.firstOrNull()?.interfaces?.firstOrNull()
            ?: homeClass.interfaces.first()
        val navHostClass = classLoader.loadClass(chrome.getString("bottomNavHostClass"))
        val navMethod = navHostClass.declaredMethods.single { method ->
            method.name == chrome.getString("bottomNavMethod") &&
                method.parameterCount == 5 &&
                method.parameterTypes[0].isAssignableFrom(homeClass)
        }.apply { isAccessible = true }
        val sc2Class = navMethod.parameterTypes[1]
        callbackInvokeMethod = sc2Class.getDeclaredMethod(
            chrome.getString("routeCallbackInvokeMethod"),
            Any::class.java,
        ).apply { isAccessible = true }

        ModernXposedRuntime.hookMethod(navMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive) return
                val route = param.args.getOrNull(0) ?: return
                latestTabCallback = param.args.getOrNull(1)
                val index = when {
                    homeClass.isInstance(route) -> 0
                    libClass.isInstance(route) -> 1
                    searchClass.isInstance(route) -> 2
                    else -> snapshot.selectedTabIndex
                }
                publish(snapshot.copy(selectedTabIndex = index, searchActive = index == 2))
                if (chromeGlassActive.get()) {
                    param.args[2] = hideModifier(param.args.getOrNull(2))
                }
            }
        }, scope)

        // Make APMC's bottom bar surface fill transparent while our glass capsule is active.
        val navSurfaceClass = classLoader.loadClass(chrome.getString("bottomNavSurfaceClass"))
        val navSurfaceMethod = navSurfaceClass.declaredMethods.single { method ->
            method.name == chrome.getString("bottomNavSurfaceMethod") &&
                method.parameterCount == 7 &&
                method.parameterTypes[1] == Long::class.javaPrimitiveType
        }.apply { isAccessible = true }

        ModernXposedRuntime.hookMethod(navSurfaceMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || !chromeGlassActive.get()) return
                // In Compose Color (inline ULong): 0L == Color.Transparent.
                param.args[1] = 0L
            }
        }, scope)

        // Track mini player metadata & playback callbacks (verified in c08.f -> al0 -> c08.e).
        val miniClass = classLoader.loadClass(chrome.getString("miniPlayerClass"))
        val miniMethod = miniClass.declaredMethods.single { method ->
            method.name == chrome.getString("miniPlayerMethod") &&
                method.parameterCount == 10 &&
                method.parameterTypes[0].name.endsWith("TrackMetaData") &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
        val miniShadowMethod = miniClass.declaredMethods.single { method ->
            method.name == chrome.getString("miniPlayerShadowMethod") &&
                method.parameterCount == 3 &&
                method.parameterTypes[2] == Int::class.javaPrimitiveType
        }.apply { isAccessible = true }
        val trackMetaClass = miniMethod.parameterTypes[0]
        val getTitle = trackMetaClass.getDeclaredMethod("getMovementOrTrackTitle")
        val getShortComposer = trackMetaClass.getDeclaredMethod("getComposerShortTitle")
        val getComposer = trackMetaClass.getDeclaredMethod("getComposerTitle")
        val getAlbumArt = trackMetaClass.getDeclaredMethod("getAlbumArt")
        val unitInvokeName = chrome.getString("unitCallbackInvokeMethod")
        unitInvokeNameValue = unitInvokeName

        runCatching {
            val controllerClass = classLoader.loadClass(chrome.getString("playerControllerClass"))
            controllerEventMethod = controllerClass.declaredMethods.single { method ->
                method.name == chrome.getString("playerControllerEventMethod") &&
                    method.parameterCount == 1
            }.apply { isAccessible = true }
            val clickEventClass = classLoader.loadClass(chrome.getString("miniPlayerClickEventClass"))
            miniPlayerClickEvent = clickEventClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical player controller contract skipped", error)
        }

        val expandPlayer: () -> Unit = expand@{
            val controller = latestPlayerController ?: return@expand
            val eventMethod = controllerEventMethod ?: return@expand
            val event = miniPlayerClickEvent ?: return@expand
            runCatching { eventMethod.invoke(controller, event) }
                .onFailure { ModernXposedRuntime.log("Classical mini player expand failed", it) }
        }

        ModernXposedRuntime.hookMethod(miniMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive) return
                val meta = param.args.getOrNull(0) ?: return
                val isPlaying = param.args.getOrNull(1) as? Boolean ?: false
                val nextCb = param.args.getOrNull(5)
                val playPauseCb = param.args.getOrNull(6)
                val title = runCatching { getTitle.invoke(meta) as? String }.getOrNull().orEmpty()
                val subtitle = runCatching {
                    (getShortComposer.invoke(meta) as? String)?.takeIf(String::isNotBlank)
                        ?: (getComposer.invoke(meta) as? String)
                }.getOrNull().orEmpty()
                val artworkUrl = runCatching {
                    val image = getAlbumArt.invoke(meta) ?: return@runCatching null
                    val rawUrl = image.javaClass.getDeclaredMethod("getUrl").invoke(image) as? String
                    resolveClassicalArtworkUrl(rawUrl, 600)
                }.getOrNull()
                val playPauseMethod = playPauseCb?.javaClass?.let { type ->
                    runCatching { type.getMethod(unitInvokeName) }.getOrNull()
                }
                val nextMethod = nextCb?.javaClass?.let { type ->
                    runCatching { type.getMethod(unitInvokeName) }.getOrNull()
                }
                val mini = ClassicalMiniPlayerSnapshot(
                    title = title.ifBlank { "Apple Classical" },
                    subtitle = subtitle,
                    isPlaying = isPlaying,
                    visible = title.isNotBlank() || subtitle.isNotBlank(),
                    onPlayPause = if (playPauseCb != null && playPauseMethod != null) {
                        { runCatching { playPauseMethod.invoke(playPauseCb) } }
                    } else null,
                    onNext = if (nextCb != null && nextMethod != null) {
                        { runCatching { nextMethod.invoke(nextCb) } }
                    } else null,
                    artworkUrl = artworkUrl,
                    onExpand = if (latestPlayerController != null) expandPlayer else null,
                )
                publish(snapshot.copy(miniPlayer = mini))
                if (chromeGlassActive.get()) {
                    param.args[7] = hideModifier(param.args.getOrNull(7))
                }
            }
        }, scope)

        ModernXposedRuntime.hookMethod(miniShadowMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || !chromeGlassActive.get()) return
                param.args[0] = hideModifier(param.args.getOrNull(0))
            }
        }, scope)

        // Drive native touch-target suppression, expanding background fade, and artwork crossfade
        // smoothly via host Compose MutableFloatState + Modifier.graphicsLayer draw-phase proxies.
        val floatStateClass = classLoader.loadClass(chrome.getString("floatStateClass"))
        val floatStateCtor = floatStateClass.getDeclaredConstructor(Float::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val floatGet = floatStateClass.getDeclaredMethod(chrome.getString("floatStateGetMethod")).apply {
            isAccessible = true
        }
        val floatSet = floatStateClass.getDeclaredMethod(
            chrome.getString("floatStateSetMethod"),
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        floatStateGetMethod = floatGet
        floatStateSetMethod = floatSet
        val expansionState = floatStateCtor.newInstance(0f)
        val glassActiveState = floatStateCtor.newInstance(if (chromeGlassActive.get()) 1f else 0f)
        hostExpansionState = expansionState
        hostGlassActiveState = glassActiveState

        val graphicsLayerClass = classLoader.loadClass(chrome.getString("graphicsLayerClass"))
        val graphicsLayerMethod = graphicsLayerClass.declaredMethods.single { method ->
            method.name == chrome.getString("graphicsLayerMethod") &&
                method.parameterCount == 2 &&
                method.parameterTypes[0].isAssignableFrom(alphaMethod.parameterTypes[0])
        }.apply { isAccessible = true }
        val graphicsLayerScopeClass = classLoader.loadClass(chrome.getString("graphicsLayerScopeClass"))
        val graphicsLayerAlphaMethod = graphicsLayerScopeClass.getDeclaredMethod(
            chrome.getString("graphicsLayerAlphaMethod"),
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val graphicsLayerClipMethod = graphicsLayerScopeClass.getDeclaredMethod(
            chrome.getString("graphicsLayerClipMethod"),
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val shapeClass = graphicsLayerScopeClass.declaredMethods.first { method ->
            method.name == chrome.getString("graphicsLayerShapeMethod") && method.parameterCount == 1
        }.parameterTypes[0]
        val graphicsLayerShapeMethod = graphicsLayerScopeClass.getDeclaredMethod(
            chrome.getString("graphicsLayerShapeMethod"),
            shapeClass,
        ).apply { isAccessible = true }
        val roundedCornerShapeClass = classLoader.loadClass(chrome.getString("roundedCornerShapeClass"))
        val roundedCornerShapeMethod = roundedCornerShapeClass.getDeclaredMethod(
            chrome.getString("roundedCornerShapeMethod"),
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val roundedSheetShape = roundedCornerShapeMethod.invoke(null, 24f)
        val rectangleShapeHolderClass = classLoader.loadClass(chrome.getString("rectangleShapeHolderClass"))
        val rectangleShape = rectangleShapeHolderClass.getDeclaredField(chrome.getString("rectangleShapeField"))
            .apply { isAccessible = true }
            .get(null)

        val sc2ClassForLayer = graphicsLayerMethod.parameterTypes[1]
        val unitClass = classLoader.loadClass("kotlin.Unit")
        val unitInstance = unitClass.declaredFields.first { it.type == unitClass }.apply { isAccessible = true }.get(null)

        val onGloballyPositionedClass = classLoader.loadClass(chrome.getString("onGloballyPositionedClass"))
        val onGloballyPositionedMethod = onGloballyPositionedClass.declaredMethods.single { method ->
            method.name == chrome.getString("onGloballyPositionedMethod") &&
                method.parameterCount == 2 &&
                method.parameterTypes[0].isAssignableFrom(alphaMethod.parameterTypes[0]) &&
                method.parameterTypes[1] == sc2ClassForLayer
        }.apply { isAccessible = true }
        val layoutCoordinatesClass = classLoader.loadClass(chrome.getString("layoutCoordinatesClass"))
        val coordsAttachedMethod = layoutCoordinatesClass.getMethod(chrome.getString("layoutCoordinatesAttachedMethod"))
        val coordsLocalToRootMethod = layoutCoordinatesClass.getMethod(
            chrome.getString("layoutCoordinatesLocalToRootMethod"),
            Long::class.javaPrimitiveType,
        )
        val coordsSizeMethod = layoutCoordinatesClass.getMethod(chrome.getString("layoutCoordinatesSizeMethod"))

        fun newLayerScopeProxy(tag: String, applyLayer: (Any) -> Unit): Any =
            Proxy.newProxyInstance(classLoader, arrayOf(sc2ClassForLayer)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> tag
                    "invoke" -> {
                        val layerScope = args?.getOrNull(0)
                        if (layerScope != null) {
                            runCatching { applyLayer(layerScope) }
                        }
                        unitInstance
                    }
                    else -> null
                }
            }

        fun newAlphaLayerProxy(tag: String, resolveAlpha: () -> Float): Any =
            newLayerScopeProxy(tag) { layerScope ->
                val alpha = resolveAlpha().coerceIn(0f, 1f)
                graphicsLayerAlphaMethod.invoke(layerScope, alpha)
            }

        fun wrapGraphicsLayer(modifier: Any?, layerProxy: Any): Any? {
            if (modifier == null) return null
            return runCatching { graphicsLayerMethod.invoke(null, modifier, layerProxy) }.getOrDefault(modifier)
        }

        fun wrapOnGloballyPositioned(modifier: Any?, positionedProxy: Any): Any? {
            if (modifier == null) return null
            return runCatching { onGloballyPositionedMethod.invoke(null, modifier, positionedProxy) }
                .getOrDefault(modifier)
        }

        val hiddenWhenGlassLayerProxy = newAlphaLayerProxy("AM++ClassicalMiniHide") {
            val active = (floatGet.invoke(glassActiveState) as? Float) ?: 0f
            if (active >= 0.5f) 0f else 1f
        }
        val backgroundLayerProxy = newLayerScopeProxy("AM++ClassicalPlayerBg") { layerScope ->
            val active = (floatGet.invoke(glassActiveState) as? Float) ?: 0f
            val expansion = (floatGet.invoke(expansionState) as? Float) ?: 0f
            val alpha = if (active >= 0.5f) computeClassicalBackgroundAlpha(expansion).coerceIn(0f, 1f) else 1f
            graphicsLayerAlphaMethod.invoke(layerScope, alpha)
            if (active >= 0.5f && expansion < 0.995f && roundedSheetShape != null) {
                graphicsLayerShapeMethod.invoke(layerScope, roundedSheetShape)
                graphicsLayerClipMethod.invoke(layerScope, true)
            } else {
                if (rectangleShape != null) {
                    graphicsLayerShapeMethod.invoke(layerScope, rectangleShape)
                }
                graphicsLayerClipMethod.invoke(layerScope, false)
            }
        }
        val artworkLayerProxy = newAlphaLayerProxy("AM++ClassicalPlayerArt") {
            val active = (floatGet.invoke(glassActiveState) as? Float) ?: 0f
            if (active >= 0.5f) {
                val expansion = (floatGet.invoke(expansionState) as? Float) ?: 0f
                computeClassicalArtworkAlpha(expansion)
            } else 1f
        }
        val sheetBoundsProxy = newLayerScopeProxy("AM++ClassicalSheetBounds") { coords ->
            if ((coordsAttachedMethod.invoke(coords) as? Boolean) != true) return@newLayerScopeProxy
            val rootOffset = coordsLocalToRootMethod.invoke(coords, 0L) as? Long ?: return@newLayerScopeProxy
            val sizeLong = coordsSizeMethod.invoke(coords) as? Long ?: return@newLayerScopeProxy
            val topPx = Float.fromBits((rootOffset and 0xFFFFFFFFL).toInt())
            val heightPx = (sizeLong and 0xFFFFFFFFL).toInt().toFloat()
            val bottomPx = topPx + heightPx
            if (!topPx.isFinite() || heightPx <= 0f) return@newLayerScopeProxy
            val current = snapshot
            val topChanged = !current.playerSheetTopPx.isFinite() || abs(topPx - current.playerSheetTopPx) >= 0.5f
            val bottomChanged = !current.playerSheetBottomPx.isFinite() ||
                abs(bottomPx - current.playerSheetBottomPx) >= 0.5f
            if (topChanged || bottomChanged) {
                publish(current.copy(playerSheetTopPx = topPx, playerSheetBottomPx = bottomPx))
            }
        }

        val artworkBoundsProxy = newLayerScopeProxy("AM++ClassicalArtworkBounds") { coords ->
            if ((coordsAttachedMethod.invoke(coords) as? Boolean) != true) return@newLayerScopeProxy
            val rootOffset = coordsLocalToRootMethod.invoke(coords, 0L) as? Long ?: return@newLayerScopeProxy
            val sizeLong = coordsSizeMethod.invoke(coords) as? Long ?: return@newLayerScopeProxy
            val leftPx = Float.fromBits((rootOffset ushr 32).toInt())
            val topPx = Float.fromBits((rootOffset and 0xFFFFFFFFL).toInt())
            val widthPx = (sizeLong ushr 32).toInt().toFloat()
            val heightPx = (sizeLong and 0xFFFFFFFFL).toInt().toFloat()
            if (!leftPx.isFinite() || !topPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return@newLayerScopeProxy
            val visSize = minOf(widthPx, heightPx) * artworkVisualScale
            val visLeft = leftPx + (widthPx - visSize) * 0.5f
            val visTop = topPx + (heightPx - visSize) * 0.5f
            val current = snapshot
            val changed = !current.playerArtworkLeftPx.isFinite() ||
                !current.playerArtworkTopPx.isFinite() ||
                !current.playerArtworkSizePx.isFinite() ||
                abs(visLeft - current.playerArtworkLeftPx) >= 0.5f ||
                abs(visTop - current.playerArtworkTopPx) >= 0.5f ||
                abs(visSize - current.playerArtworkSizePx) >= 0.5f
            if (changed) {
                publish(
                    current.copy(
                        playerArtworkLeftPx = visLeft,
                        playerArtworkTopPx = visTop,
                        playerArtworkSizePx = visSize,
                    ),
                )
            }
        }

        runCatching {
            val touchTargetClass = classLoader.loadClass(chrome.getString("playerTouchTargetClass"))
            val touchTargetMethod = touchTargetClass.declaredMethods.single { method ->
                method.name == chrome.getString("playerTouchTargetMethod") &&
                    method.parameterCount == 7 &&
                    method.parameterTypes[1].name.endsWith("TrackMetaData")
            }.apply { isAccessible = true }
            ModernXposedRuntime.hookMethod(touchTargetMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    latestPlayerController = param.args.getOrNull(0)
                    val changed = param.args.getOrNull(6) as? Int
                    if (changed != null) {
                        param.args[6] = (changed and 0x6000.inv()) or 0x4000
                    }
                    param.args[4] = wrapGraphicsLayer(param.args.getOrNull(4), hiddenWhenGlassLayerProxy)
                }
            }, scope)

            val backgroundClass = classLoader.loadClass(chrome.getString("playerBackgroundClass"))
            val backgroundMethod = backgroundClass.declaredMethods.single { method ->
                method.name == chrome.getString("playerBackgroundMethod") &&
                    method.parameterCount == 7 &&
                    method.parameterTypes[0].name.endsWith("Image") &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.apply { isAccessible = true }
            ModernXposedRuntime.hookMethod(backgroundMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val positioned = wrapOnGloballyPositioned(param.args.getOrNull(4), sheetBoundsProxy)
                    param.args[4] = wrapGraphicsLayer(positioned, backgroundLayerProxy)
                }
            }, scope)

            val artworkClass = classLoader.loadClass(chrome.getString("playerArtworkClass"))
            val artworkMethod = artworkClass.declaredMethods.single { method ->
                method.name == chrome.getString("playerArtworkMethod") &&
                    method.parameterCount == 6 &&
                    method.parameterTypes[0].name.endsWith("Image") &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }.apply { isAccessible = true }
            ModernXposedRuntime.hookMethod(artworkMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val isExpanded = (param.args.getOrNull(1) as? Boolean) == true
                    val isPlaying = (param.args.getOrNull(2) as? Boolean) == true
                    artworkVisualScale = if (isExpanded && !isPlaying) 0.73f else 1.0f
                    val changed = param.args.getOrNull(5) as? Int
                    if (changed != null) {
                        param.args[5] = (changed and 0x1C00.inv()) or 0x800
                    }
                    val positioned = wrapOnGloballyPositioned(param.args.getOrNull(3), artworkBoundsProxy)
                    param.args[3] = wrapGraphicsLayer(positioned, artworkLayerProxy)
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical player transition layer hooks skipped", error)
        }

        // Track continuous MotionLayout player sheet transitions (uk3 + m20) and drag-to-expand hooks (a80 + ya + dt6).
        val stateFlowClass = classLoader.loadClass(guideContract.getString("stateFlowClass"))
        val emitMethod = stateFlowClass.getDeclaredMethod(
            guideContract.getString("stateFlowEmitMethod"),
            Any::class.java,
            Any::class.java,
        ).apply { isAccessible = true }
        val playerStateBase = classLoader.loadClass(chrome.getString("playerStateBaseClass"))
        val minimizedState = classLoader.loadClass(chrome.getString("minimizedStateClass"))
        val hiddenState = classLoader.loadClass(chrome.getString("hiddenStateClass"))

        fun isExpandedPlayerState(state: Any?): Boolean =
            state != null &&
                playerStateBase.isInstance(state) &&
                !minimizedState.isInstance(state) &&
                !hiddenState.isInstance(state)

        runCatching {
            val motionContentClass = classLoader.loadClass(chrome.getString("playerMotionContentClass"))
            val motionCtor = motionContentClass.declaredConstructors.single { ctor ->
                ctor.parameterCount == 12 && ctor.parameterTypes[6] == Float::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val resolverClass = classLoader.loadClass(chrome.getString("playerStateResolverClass"))
            val resolverCaseField = resolverClass.getDeclaredField("i").apply { isAccessible = true }
            val resolverDraggableField = resolverClass.getDeclaredField("x").apply { isAccessible = true }
            val resolverStartStateField = resolverClass.getDeclaredField("y").apply { isAccessible = true }
            val resolverEndStateField = resolverClass.getDeclaredField("z").apply { isAccessible = true }
            val composeStateClass = classLoader.loadClass(chrome.getString("composeStateClass"))
            val stateHolderGetValue = composeStateClass.getMethod("getValue")

            ModernXposedRuntime.hookMethod(motionCtor, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || param.throwable != null) return
                    val motionProgress = param.args.getOrNull(6) as? Float ?: return
                    val resolver = param.args.getOrNull(7) ?: return
                    if (!resolverClass.isInstance(resolver)) return
                    if (runCatching { resolverCaseField.getInt(resolver) }.getOrNull() != 12) return
                    runCatching { resolverDraggableField.get(resolver) }.getOrNull()?.let { latestDraggable = it }
                    val startState = runCatching {
                        resolverStartStateField.get(resolver)?.let { stateHolderGetValue.invoke(it) }
                    }.getOrNull()
                    val endState = runCatching {
                        resolverEndStateField.get(resolver)?.let { stateHolderGetValue.invoke(it) }
                    }.getOrNull()
                    val expansion = computeClassicalPlayerExpansion(
                        startExpanded = isExpandedPlayerState(startState),
                        endExpanded = isExpandedPlayerState(endState),
                        motionProgress = motionProgress,
                    )
                    motionObserved = true
                    val prev = snapshot.playerExpansion
                    val endpoint = expansion == 0f || expansion == 1f
                    if (!endpoint && abs(expansion - prev) < 0.0005f) return
                    setHostExpansion(expansion)
                    publish(
                        snapshot.copy(
                            playerExpansion = expansion,
                            playerExpanded = expansion >= 0.98f,
                        ),
                    )
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical MotionLayout progress hook skipped", error)
        }

        runCatching {
            val dragHandlerClass = classLoader.loadClass(chrome.getString("playerDragHandlerClass"))
            val velocityTrackerClass = classLoader.loadClass(chrome.getString("velocityTrackerClass"))
            val draggableClass = classLoader.loadClass(chrome.getString("anchoredDraggableClass"))
            val velocityHolderClass = classLoader.loadClass(chrome.getString("velocityTrackerHolderClass"))
            val velocity1DClass = classLoader.loadClass(chrome.getString("velocityTracker1DClass"))

            yaDispatchRawDelta = draggableClass.getDeclaredMethod("d", Float::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            val scopeField = draggableClass.getDeclaredField("n").apply { isAccessible = true }
            yaDragScopeField = scopeField
            yaDragScopeToMethod = scopeField.type.getDeclaredMethod(
                "a",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            trackerHolderField = velocityTrackerClass.getDeclaredField("a").apply { isAccessible = true }
            holderTimeField = velocityHolderClass.getDeclaredField("x").apply { isAccessible = true }
            holderXTrackerField = velocityHolderClass.getDeclaredField("y").apply { isAccessible = true }
            holderYTrackerField = velocityHolderClass.getDeclaredField("z").apply { isAccessible = true }
            tracker1DPointsField = velocity1DClass.getDeclaredField("d").apply { isAccessible = true }
            tracker1DIndexField = velocity1DClass.getDeclaredField("e").apply { isAccessible = true }
            tracker1DAddMethod = velocity1DClass.getDeclaredMethod(
                "a",
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            val dragCtor = dragHandlerClass.declaredConstructors.single { ctor ->
                ctor.parameterCount == 4 &&
                    ctor.parameterTypes[1] == velocityTrackerClass &&
                    ctor.parameterTypes[2] == draggableClass
            }.apply { isAccessible = true }

            ModernXposedRuntime.hookMethod(dragCtor, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || param.throwable != null) return
                    latestSettleCallback = param.args.getOrNull(0)
                    latestVelocityTracker = param.args.getOrNull(1)
                    param.args.getOrNull(2)?.let { latestDraggable = it }
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical player drag bridge hook skipped", error)
        }

        ModernXposedRuntime.hookMethod(emitMethod, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val value = param.args.getOrNull(1) ?: return
                if (!playerStateBase.isInstance(value)) return
                val isHidden = hiddenState.isInstance(value)
                val isMinimized = minimizedState.isInstance(value)
                val mapped = mapClassicalPlayerState(
                    current = snapshot,
                    isHidden = isHidden,
                    isMinimized = isMinimized,
                )
                val next = if (motionObserved && !isHidden) {
                    mapped.copy(playerExpansion = snapshot.playerExpansion)
                } else {
                    setHostExpansion(mapped.playerExpansion)
                    mapped
                }
                publish(next)
            }
        }, scope)

        // Expand page content behind the floating bottom bar so scrolling passes underneath the glass.
        runCatching {
            val constraintClass = classLoader.loadClass(chrome.getString("contentConstraintClass"))
            val caseField = constraintClass.getDeclaredField("i").apply { isAccessible = true }
            val constraintInvoke = constraintClass.getDeclaredMethod("invoke", Any::class.java)
                .apply { isAccessible = true }
            val anchorHelperClass = classLoader.loadClass(chrome.getString("constraintAnchorHelperClass"))
            val anchorLinkMethod = anchorHelperClass.declaredMethods.single { method ->
                method.name == chrome.getString("constraintAnchorLinkMethod") &&
                    method.parameterCount == 4 &&
                    method.parameterTypes[2] == Float::class.javaPrimitiveType &&
                    method.parameterTypes[3] == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }
            var loggedFullBleedError = false
            ModernXposedRuntime.hookMethod(constraintInvoke, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || param.throwable != null || !chromeGlassActive.get()) return
                    if ((caseField.get(param.thisObject) as? Int) != 1) return
                    val scopeObj = param.args.getOrNull(0) ?: return
                    runCatching {
                        val cls = scopeObj.javaClass
                        val bottomAnchor = cls.getDeclaredField("g").apply { isAccessible = true }.get(scopeObj)
                        val parentRef = cls.getDeclaredField("c").apply { isAccessible = true }.get(scopeObj)
                        val parentBottom = parentRef.javaClass.getDeclaredField("g")
                            .apply { isAccessible = true }.get(parentRef)
                        anchorLinkMethod.invoke(null, bottomAnchor, parentBottom, 0f, 6)
                    }.onFailure { error ->
                        if (!loggedFullBleedError) {
                            loggedFullBleedError = true
                            ModernXposedRuntime.log("Classical full-bleed constraint adjust failed", error)
                        }
                    }
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical full-bleed hook skipped", error)
        }

        // Bridge Compose Dialogs (vj1) onto the shared backdrop + translucent Surface backing.
        val dialogWrapperClass = classLoader.loadClass(chrome.getString("dialogWrapperClass"))
        val anchorViewField: Field = dialogWrapperClass.getDeclaredField("C").apply { isAccessible = true }
        val surfaceClass = classLoader.loadClass(chrome.getString("surfaceClass"))
        val surfaceMethod = surfaceClass.declaredMethods.single { method ->
            method.name == chrome.getString("surfaceMethod") &&
                method.parameterCount == 10 &&
                method.parameterTypes[2] == Long::class.javaPrimitiveType
        }.apply { isAccessible = true }

        ModernXposedRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null) return
                val dialog = param.thisObject as? Dialog ?: return
                if (!dialogWrapperClass.isInstance(dialog)) return
                val newlyTracked = synchronized(trackedDialogs) {
                    if (trackedDialogs.containsKey(dialog)) false else {
                        trackedDialogs[dialog] = true
                        true
                    }
                }
                if (newlyTracked) activeDialogCount.incrementAndGet()
                val decor = dialog.window?.decorView as? ViewGroup ?: return
                val hostView = runCatching { anchorViewField.get(dialog) as? View }.getOrNull() ?: return
                val mainRoot = hostView.rootView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
                if (decor !== mainRoot) {
                    dialogGlassObserver?.onSurfaceAttached(
                        GlassSurfaceAnchor(
                            role = GlassSurfaceRole.ACTION_SHEET_DIALOG,
                            container = decor,
                            backdropSource = mainRoot,
                            cornerRadiusDp = 24f,
                            minCaptureIntervalMs = 16L,
                        ),
                    )
                }
            }
        }, scope)

        ModernXposedRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("dismiss"), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialog = param.thisObject as? Dialog ?: return
                val removed = synchronized(trackedDialogs) { trackedDialogs.remove(dialog) != null }
                if (removed) {
                    val remaining = activeDialogCount.decrementAndGet()
                    if (remaining < 0) activeDialogCount.set(0)
                }
            }
        }, scope)

        ModernXposedRuntime.hookMethod(surfaceMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || !chromeGlassActive.get() || activeDialogCount.get() <= 0) return
                val colorValue = param.args.getOrNull(2) as? Long ?: return
                // Only soften opaque sRGB Compose Colors (colorSpace id == 0 in lowest 6 bits).
                if (colorValue != 0L && (colorValue and 0x3fL) == 0L) {
                    val argb = (colorValue ushr 32).toInt()
                    val alpha = (argb ushr 24) and 0xFF
                    if (alpha > 190) {
                        val translucentArgb = (0x88 shl 24) or (argb and 0x00FFFFFF)
                        param.args[2] = translucentArgb.toLong() shl 32
                    }
                }
            }
        }, scope)

        // Bridge Compose DropdownMenu popups (uu4) onto the shared backdrop + unified CONTEXT_POPUP_MENU Liquid Glass.
        val dropdownMenuShape = roundedCornerShapeMethod.invoke(null, 24f)
        runCatching {
            val popupLayoutClass = classLoader.loadClass(chrome.getString("popupLayoutClass"))
            val popupAnchorViewField = popupLayoutClass.getDeclaredField(chrome.getString("popupAnchorViewField"))
                .apply { isAccessible = true }
            val popupPositionProviderField = popupLayoutClass.getDeclaredField(chrome.getString("popupPositionProviderField"))
                .apply { isAccessible = true }
            val dropdownPositionProviderClass = classLoader.loadClass(chrome.getString("dropdownPositionProviderClass"))
            val abstractComposeViewClass = classLoader.loadClass(chrome.getString("abstractComposeViewClass"))
            val checkAddMethod = abstractComposeViewClass.getDeclaredMethod(chrome.getString("abstractComposeViewCheckAddMethod"))
                .apply { isAccessible = true }
            val dropdownMenuContentClass = classLoader.loadClass(chrome.getString("dropdownMenuContentClass"))
            val dropdownMenuContentMethod = dropdownMenuContentClass.declaredMethods.single { method ->
                method.name == chrome.getString("dropdownMenuContentMethod") &&
                    method.parameterCount == 10 &&
                    method.parameterTypes[5] == Long::class.javaPrimitiveType &&
                    method.parameterTypes[6] == Float::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val surfaceModifierMethod = surfaceClass.declaredMethods.single { method ->
                method.name == chrome.getString("surfaceModifierMethod") &&
                    method.parameterCount == 4 &&
                    method.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Long::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val backgroundElementClass = classLoader.loadClass(chrome.getString("backgroundElementClass"))
            val modifierCompanion = classLoader.loadClass(chrome.getString("modifierCompanionClass"))
                .getDeclaredField(chrome.getString("modifierCompanionField"))
                .apply { isAccessible = true }
                .get(null)

            ModernXposedRuntime.hookMethod(checkAddMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive) return
                    val view = param.thisObject as? View ?: return
                    if (trackedDropdownPopups.containsKey(view)) {
                        param.result = null
                    }
                }
            }, scope)

            ModernXposedRuntime.hookMethod(
                popupLayoutClass.getDeclaredMethod("onAttachedToWindow"),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!scope.isActive || !chromeGlassActive.get() || param.throwable != null) return
                        val popup = param.thisObject as? ViewGroup ?: return
                        val provider = runCatching { popupPositionProviderField.get(popup) }.getOrNull() ?: return
                        if (!dropdownPositionProviderClass.isInstance(provider)) return
                        val hostView = runCatching { popupAnchorViewField.get(popup) as? View }.getOrNull() ?: return
                        val mainRoot = hostView.rootView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
                            ?: return
                        if (popup === mainRoot) return
                        fun attachPopupGlass() {
                            if (!scope.isActive || !chromeGlassActive.get() ||
                                !popup.isAttachedToWindow || popup.childCount == 0
                            ) {
                                return
                            }
                            popup.elevation = 0f
                            trackedDropdownPopups[popup] = true
                            dialogGlassObserver?.onSurfaceAttached(
                                GlassSurfaceAnchor(
                                    role = GlassSurfaceRole.CONTEXT_POPUP_MENU,
                                    container = popup,
                                    backdropSource = mainRoot,
                                    cornerRadiusDp = 24f,
                                    minCaptureIntervalMs = 16L,
                                    forceRefreshOnPreDraw = false,
                                    insertBehindViaZOrder = true,
                                ),
                            )
                        }
                        if (popup.childCount > 0) {
                            attachPopupGlass()
                        } else {
                            popup.post { attachPopupGlass() }
                        }
                    }
                },
                scope,
            )

            ModernXposedRuntime.hookMethod(
                popupLayoutClass.getDeclaredMethod("onDetachedFromWindow"),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val popup = param.thisObject as? View ?: return
                        trackedDropdownPopups.remove(popup)
                    }
                },
                scope,
            )

            ModernXposedRuntime.hookMethod(dropdownMenuContentMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || !chromeGlassActive.get()) return
                    val modifier = param.args.getOrNull(0)
                    if (modifier != null && backgroundElementClass.isInstance(modifier) && modifierCompanion != null) {
                        param.args[0] = modifierCompanion
                    }
                    param.args[4] = dropdownMenuShape
                    param.args[6] = 0f
                }
            }, scope)

            ModernXposedRuntime.hookMethod(surfaceModifierMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || !chromeGlassActive.get()) return
                    if (param.args.getOrNull(3) === dropdownMenuShape) {
                        param.args[0] = 0f
                        param.args[1] = 0L
                    }
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical dropdown popup glass hook skipped", error)
        }

        // Swap Motion Artwork PlayerView (ct4) from SurfaceView to TextureView so ViewBackdrop
        // captures live frames without punch-through holes, scaling glitches, or SurfaceSyncGroup stalls.
        runCatching {
            val motionPlayerViewClass = classLoader.loadClass(chrome.getString("motionPlayerViewClass"))
            val motionPlayerCtor = motionPlayerViewClass.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
            val contentFrameField = motionPlayerViewClass.getDeclaredField(chrome.getString("motionPlayerContentFrameField"))
                .apply { isAccessible = true }
            val surfaceViewField = motionPlayerViewClass.getDeclaredField(chrome.getString("motionPlayerSurfaceViewField"))
                .apply { isAccessible = true }

            ModernXposedRuntime.hookMethod(motionPlayerCtor, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || !chromeGlassActive.get() || param.throwable != null) return
                    val playerView = param.thisObject as? ViewGroup ?: return
                    val contentFrame = runCatching { contentFrameField.get(playerView) as? ViewGroup }.getOrNull()
                        ?: return
                    val surfaceView = runCatching { surfaceViewField.get(playerView) as? SurfaceView }.getOrNull()
                        ?: return
                    val textureView = TextureView(playerView.context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isClickable = false
                    }
                    runCatching {
                        val index = contentFrame.indexOfChild(surfaceView).coerceAtLeast(0)
                        contentFrame.removeView(surfaceView)
                        contentFrame.addView(textureView, index)
                        surfaceViewField.set(playerView, textureView)
                    }.onFailure { error ->
                        ModernXposedRuntime.log("Classical motion artwork TextureView swap failed", error)
                    }
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical motion artwork TextureView hook skipped", error)
        }

        // Flatten multi-group context menu buttons so ct7.d omits the abrupt 4dp solid group divider bar.
        runCatching {
            val groupListClass = classLoader.loadClass(chrome.getString("contextMenuGroupListClass"))
            val groupListMethod = groupListClass.declaredMethods.single { method ->
                method.name == chrome.getString("contextMenuGroupListMethod") &&
                    method.parameterCount == 4 &&
                    method.parameterTypes[0] == List::class.java &&
                    method.parameterTypes[3] == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }

            ModernXposedRuntime.hookMethod(groupListMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || !chromeGlassActive.get()) return
                    val groups = param.args.getOrNull(0) as? List<*> ?: return
                    flattenClassicalMenuGroups(groups)?.let { param.args[0] = it }
                }
            }, scope)
        }.onFailure { error ->
            ModernXposedRuntime.log("Classical context menu group divider suppression skipped", error)
        }

        scope.activate()
        @Suppress("UNUSED_VARIABLE") val unused = routeBaseClass
    }
}
