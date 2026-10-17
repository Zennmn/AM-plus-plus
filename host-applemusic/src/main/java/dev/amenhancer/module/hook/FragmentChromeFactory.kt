package dev.amenhancer.module.hook

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import dev.amenhancer.module.host.FragmentViewSessions
import java.util.IdentityHashMap

/** Standalone install seam for the parent composition factory. Legacy chrome is independent. */
object FragmentChromeFactory {
    fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(
        build.packageName, build.versionName, build.versionCode,
    )?.let { it.productionEnabled && it.capability("glass") && it.family == "fragment-content" && it.document.has("fragmentChrome") } == true

    fun registerResources(observer: (View) -> Unit) {
        // mini inflation happens during nested Fragment creation, before first onViewCreated.
        listOf("fragment_music_content", "mini_player").forEach { LayoutInflationRegistry.register(it, observer) }
    }

    fun install(loader: ClassLoader, build: TargetBuild, observer: FragmentPlayerSurfaceObserver): HostSubscription {
        val profile = checkNotNull(AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        check(profile.family == "fragment-content") { "Fragment chrome cannot own ${profile.family}" }
        GlassDiagLog.i("resolving fragmentChrome contract for ${build.packageName} ${build.versionName}/${build.versionCode} (profile ${profile.id})")
        val contract = try {
            FragmentChromeContract(loader, profile.document.getJSONObject("fragmentChrome"))
        } catch (error: Throwable) {
            // The message lists every unresolved role/descriptor; glass stays off and the host stays native.
            GlassDiagLog.e("fragmentChrome contract did NOT resolve; glass disabled, native chrome kept", error)
            throw error
        }
        GlassDiagLog.i("fragmentChrome contract resolved; registering hooks")
        val scope = HookRegistrationScope()
        val bindings = FragmentViewSessions<Any, ViewGroup, FragmentSurfaceBinding> { binding ->
            observer.onDestroyed(binding.viewSessionIdentity)
        }
        val watched = IdentityHashMap<ViewGroup, View.OnAttachStateChangeListener>()
        val callbacks = IdentityHashMap<Any, IdentityHashMap<Any, Any>>()
        // Native slide events can precede the Fragment view's deferred glass mount.
        val playerProgress = java.util.WeakHashMap<Any, Float>()
        val activityOf = contract.activityOf
        fun destroy(owner: Any) {
            val root = bindings.root(owner)
            val mounted = bindings.hasBinding(owner)
            root?.let { watched.remove(it)?.let(it::removeOnAttachStateChangeListener) }
            callbacks.remove(owner)?.clear()
            try { bindings.destroy(owner) } finally {
                // A prepared view may disappear before its queued binding is ever constructed.
                if (!mounted && root != null) observer.onDestroyed(root)
            }
        }
        fun fail(owner: Any?, error: Throwable, expectedRoot: ViewGroup? = null) {
            if (expectedRoot != null && (owner == null || !bindings.current(owner, expectedRoot))) return
            GlassDiagLog.e("glass binding failed (owner=${owner?.javaClass?.name}); restoring native chrome", error)
            val identity = owner?.let(bindings::root)
            if (owner != null) destroy(owner)
            observer.onFailure(identity, error)
        }
        // Hot-path hooks run for every View, popup and bottom sheet in the host. Any failure here
        // restores native chrome for all sessions instead of letting an exception reach the host.
        fun <T> guarded(site: String, block: () -> T?): T? = try { block() } catch (error: Throwable) {
            GlassDiagLog.e("glass hook '$site' threw; restoring native chrome (fail-open)", error)
            runCatching { observer.onFailure(null, error) }.onFailure { GlassDiagLog.e("onFailure handler threw at '$site'", it) }
            null
        }
        fun bind(owner: Any, root: ViewGroup) {
            if (!scope.isActive || !root.isAttachedToWindow) return
            val binding = bindings.bind(owner, root) {
                FragmentSurfaceBinding(owner, activityOf.invoke(owner) as Activity, root, contract,
                    callbacks.getOrPut(owner) { IdentityHashMap() }, { fail(owner, it, root) })
            } ?: return
            contract.playerOf.invoke(owner)?.let { player -> playerProgress[player]?.let(binding::slide) }
            observer.onCreated(binding)
        }
        fun prepare(owner: Any, root: ViewGroup) {
            val previous = bindings.prepare(owner, root)
            if (previous != null) {
                watched.remove(previous)?.let(previous::removeOnAttachStateChangeListener)
                callbacks.remove(owner)?.clear()
            }
            if (watched.containsKey(root)) return
            observer.onPreparing(root)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!bindings.current(owner, root)) return
                    runCatching { observer.onPreparing(root) }.onFailure { fail(owner, it, root) }
                    root.post { runCatching { bind(owner, root) }.onFailure { fail(owner, it, root) } }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    // Revoke the renderer and native leases together; retain root/actions for reattachment.
                    if (!bindings.current(owner, root)) return
                    runCatching {
                        val mounted = bindings.hasBinding(owner)
                        bindings.detach(owner, root)
                        if (!mounted) observer.onDestroyed(root)
                    }.onFailure { fail(owner, it, root) }
                }
            }
            watched[root] = listener
            root.addOnAttachStateChangeListener(listener)
            if (root.isAttachedToWindow) root.post { runCatching { bind(owner, root) }.onFailure { fail(owner, it, root) } }
        }
        fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(method, callback, scope)) { "Fragment chrome hook failed: $method" }
        }
        try {
            hook(contract.createView, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    val root = param.result as? ViewGroup ?: return
                    runCatching { prepare(owner, root) }.onFailure { fail(owner, it, root) }
                }
            })
            hook(contract.viewCreated,
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val owner = param.thisObject ?: return
                        if (!contract.content.isInstance(owner)) return
                        val root = param.args[0] as? ViewGroup ?: return
                        runCatching { prepare(owner, root) }.onFailure { fail(owner, it, root) }
                    }
                })
            hook(contract.destroyView, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.takeIf(contract.content::isInstance)?.let(::destroy)
                }
            })
            hook(contract.renderTab, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("renderTab") {
                    val owner = param.thisObject ?: return@guarded null
                    val model = param.args[0] ?: return@guarded null
                    val action = param.args[2] ?: return@guarded null
                    val actions = callbacks.getOrPut(owner) { IdentityHashMap() }
                    val kind = contract.modelKind.get(model)
                    actions.keys.removeAll { it !== model && contract.modelKind.get(it) == kind }
                    actions[model] = action
                    null }
                }
            })
            hook(contract.slide, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val sheet = param.args[0] as View
                        val progress = param.args[1] as Float
                        if (!progress.isFinite()) return
                        contract.slidePlayer.get(param.thisObject)?.let { playerProgress[it] = progress.coerceIn(0f, 1f) }
                        bindings.values.forEach { if (it.ownsSheet(sheet)) it.slide(progress) }
                    }.onFailure { observer.onFailure(null, it) }
                }
            })
            hook(contract.progress, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val player = contract.slidePlayer.get(param.thisObject)
                        val progress = param.args[0] as Float
                        if (!progress.isFinite()) return
                        if (player != null) playerProgress[player] = progress.coerceIn(0f, 1f)
                        bindings.values.forEach { if (it.isPlayer(player)) it.slide(progress) }
                    }.onFailure { observer.onFailure(null, it) }
                }
            })
            hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java),
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        guarded("ViewGroup.dispatchTouchEvent") { dispatch(param) }
                    }
                    private fun dispatch(param: MethodHookParam): Unit? {
                        val view = param.thisObject as? View ?: return null
                        val event = param.args[0] as MotionEvent
                        // Root dispatch observes one event before native child controls consume it.
                        bindings.values.forEach { binding ->
                            if (binding.blocksNavigationTouch(view)) {
                                // Skip only the hidden native navigation subtree. Returning false
                                // leaves the parent free to route empty glass space to native input.
                                param.result = false
                                return null
                            }
                            if (binding.tabletChrome == null && binding.isMiniTouchPanel(view)) {
                                // The current phone session observes presses and returns null,
                                // preserving native mini controls and player drag. Only an
                                // explicit true result claims the event at this dispatch entry.
                                val claimed = runCatching {
                                    observer.onMiniTouch(binding.viewSessionIdentity, event)
                                }.onFailure { fail(null, it) }.getOrNull()
                                if (claimed == true) param.result = true
                            }
                            // Observe each page event once at the content root, including gestures
                            // handled internally by Compose. Current sessions return null so page
                            // scrolling and native child controls retain their own dispatch.
                            // Only an explicit true result claims the event at this seam.
                            if (binding.isContentRoot(view)) {
                                val claimed = runCatching {
                                    observer.onContentTouch(binding.viewSessionIdentity, event)
                                }.onFailure { fail(null, it) }.getOrNull()
                                if (claimed == true) param.result = true
                            }
                        }
                        return null
                    }
                })
            hook(View::class.java.getDeclaredMethod("setAlpha", java.lang.Float.TYPE), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("View.setAlpha") {
                    val view = param.thisObject as? View ?: return@guarded null
                    val original = param.args[0] as Float
                    bindings.values.firstNotNullOfOrNull { it.nativeAlphaWrite(view, original) }
                        ?.let { param.args[0] = it }
                    null }
                }
            })
            hook(contract.activityTouch, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args[0] as MotionEvent
                    bindings.values.toList().forEach { binding ->
                        if (binding.tabletChrome != null && binding.activity === param.thisObject)
                            runCatching { observer.onMiniTouch(binding.viewSessionIdentity, event) }.onFailure { fail(null, it) }
                    }
                }
            })
            hook(contract.blurDraw, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("BlurView.draw") {
                    val view = param.thisObject as? View ?: return@guarded null
                    if (bindings.values.any { it.replacesBlur(view) }) param.result = null
                    null }
                }
            })
            hook(View::class.java.getDeclaredMethod("setPadding", java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("View.setPadding") {
                    val view = param.thisObject as? View ?: return@guarded null
                    bindings.values.firstNotNullOfOrNull { it.phoneChrome?.padding(view) }?.let { param.args[3] = it }
                    null }
                }
            })
            hook(contract.tabletMiniMargins, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("miniMargins") {
                    val view = param.args[0] as View
                    val bottom = Math.round(param.args[2] as Float)
                    bindings.values.firstNotNullOfOrNull { it.tabletChrome?.miniBottomMargin(view, bottom) }
                        ?.let { param.args[2] = it.toFloat() }
                    null }
                }
            })
            hook(contract.settleSheet, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val sheet = param.args[0] as View
                    val target = param.args[1] as Int
                    runCatching {
                        bindings.values.forEach { it.phoneChrome?.prepareSheetSettle(owner, sheet, target) }
                    }.onFailure { observer.onFailure(null, it) }
                }
            })
            hook(contract.writePeek, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    guarded("writePeek") {
                    val owner = param.thisObject ?: return@guarded null
                    bindings.values.firstNotNullOfOrNull {
                        it.phoneChrome?.peek(owner, param.args[0] as Int)
                            ?: it.tabletChrome?.peek(owner, param.args[0] as Int)
                    }
                        ?.let { param.args[0] = it }
                    null }
                }
            })
            GlassDiagLog.i("fragmentChrome hooks registered (collapse hooks included: settleSheet/refreshCollapsedOffset resolved)")
            scope.onClose {
                bindings.owners.toList().forEach(::destroy)
                watched.clear(); callbacks.clear(); playerProgress.clear()
            }
            scope.activate()
            return HostSubscription { scope.close() }
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
