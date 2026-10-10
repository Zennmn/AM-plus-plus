package dev.amenhancer.module.hook

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.util.WeakHashMap

/** Uses only verified 1606 contracts; the legacy dual-pane installer remains closed to beta. */
internal object FragmentTabletDualPaneCoordinator {
    private val sessions = WeakHashMap<Any, FragmentTabletDualPaneSession>()
    private val roots = WeakHashMap<Any, ViewGroup>()
    private val failed = WeakHashMap<Any, Boolean>()
    private class WindowGate(val width: Int, val height: Int, val density: Int, val drawer: Boolean)
    private val windows = WeakHashMap<Resources, WindowGate>()

    private val progressValues = WeakHashMap<Any, Float>()
    fun progress(owner: Any): Float? = progressValues[owner]
    fun eligible(context: Context): Boolean {
        val c = context.resources.configuration
        if (!dev.amenhancer.module.config.TargetConfigClient.currentSettings().dualPaneEnabled) return false
        val drawer = context.resources.getIdentifier("useNavigationDrawer", "bool", context.packageName)
        return c.screenWidthDp >= 600 && c.screenWidthDp > c.screenHeightDp && drawer != 0 && context.resources.getBoolean(drawer)
    }
    fun install(loader: ClassLoader, registration: HookRegistrationScope) {
        this.registration = registration
        registration.onClose { sessions.values.toList().forEach { it.destroy() }; sessions.clear(); roots.clear(); failed.clear(); progressValues.clear() }
        val lyricsClass = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        val build = TargetBuild("com.apple.android.music", "7.0.0-beta", 1606L)
        val profile = checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        FragmentTabletVideoHandoff(loader, profile.document.getJSONObject("fragmentTabletVideoHandoff")).install(registration)
        val karaoke = FragmentKaraokeWidthContract(lyricsClass, profile.document.getJSONObject("fragmentKaraokeWidth"))
        val visual = BetaLyricsPaneRuntime(lyricsClass, checkNotNull(LyricsLayoutFieldProfiles.resolve(lyricsClass, build)), karaoke)
        visual.validate(); visual.install(registration)
        installProgressObserver(loader)
        val controller = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment")
        val state = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment\$l")
        val bag = loader.loadClass("com.apple.android.music.storeapi.model.BagConfig")
        val pane = loader.loadClass("com.apple.android.music.player.fragment.l")
        hook(pane.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val view = param.args.firstOrNull() as? View ?: return
                val owner = param.thisObject?.let { ModernXposedRuntime.callMethod(it, "getParentFragment") } ?: return
                sessions[owner]?.takeIf { eligible(view.context) }?.prepareComponents(view)
            }
        })
        hook(controller.getDeclaredMethod("j1", bag), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val context = ModernXposedRuntime.callMethod(owner, "getContext") as? Context ?: return
                if (eligible(context)) controller.getDeclaredField("a").apply { isAccessible = true }
                    .set(owner, checkNotNull(state.enumConstants).first { (it as Enum<*>).name == "SONG" })
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                roots[owner]?.post { reconcile(owner) }
            }
        })
        hook(controller.getDeclaredMethod("onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val root = param.result as? ViewGroup ?: return
                roots[owner] = root
                // Saved right-pane fragments must find the same container before view restoration.
                if (eligible(root.context) || param.args.getOrNull(2) != null) guarded(owner) {
                    sessions.getOrPut(owner) { FragmentTabletDualPaneSession(owner, root) }.prepare()
                }
                root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                    if (r - l != oldR - oldL || b - t != oldB - oldT) root.post { reconcile(owner) }
                }
                root.post { reconcile(owner) }
            }
        })
        hook(controller.getDeclaredMethod("s1", state, Bundle::class.java), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val session = sessions[owner]?.takeIf { it.installed && eligible(it.root.context) } ?: return
                val requested = (param.args[0] as? Enum<*>)?.name ?: return
                if (requested == "SONG" || requested == "QUEUE") session.prepareNativeTransition()
                // Match the established dual-pane player: only lyrics are fixed on the right.
                // SONG/QUEUE retain Apple's state, shared-element transitions and left controls.
                if (!FragmentTabletArtworkPolicy.keepsDedicatedLyrics(requested)) return
                param.result = null
            }
        })
        hook(controller.getDeclaredMethod("onResume"), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { param.thisObject?.let { owner -> roots[owner]?.post { reconcile(owner) } } }
        })
        hook(controller.getDeclaredMethod("onDestroyView"), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                sessions.remove(owner)?.destroy()
                roots.remove(owner)
                failed.remove(owner)
                progressValues.remove(owner)
            }
        })
    }

    private lateinit var registration: HookRegistrationScope
    private fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
        check(ModernXposedRuntime.hookMethod(method, callback, registration)) { "Reference dual-pane hook failed: $method" }
    }

    /** Observe the completed native frame for lyrics/materials; never replace its artwork writes. */
    private fun installProgressObserver(loader: ClassLoader) {
        val callback = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment\$i")
        val artwork = callback.getDeclaredMethod("d", Float::class.javaPrimitiveType)
        val owner = callback.getDeclaredField("h").apply { isAccessible = true }
        hook(artwork, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val controller = owner.get(param.thisObject) ?: return
                val progress = (param.args[0] as? Number)?.toFloat() ?: return
                if (!progress.isFinite()) return
                progressValues[controller] = progress.coerceIn(0f, 1f)
                sessions[controller]?.takeIf { failed[controller] != true }?.let { session ->
                    guarded(controller) { session.onSlide(progress) }
                }
            }
        })
    }

    private fun guarded(owner: Any, block: () -> Unit) {
        runCatching(block).onFailure { error -> fail(owner, error) }
    }

    fun fail(owner: Any, error: Throwable) {
        if (failed.put(owner, true) == true) return
        ModernXposedRuntime.log("7.0 tablet dual pane failed; restoring native player", error)
        roots[owner]?.post { runCatching { sessions[owner]?.restore() } }
    }

    private fun reconcile(owner: Any) {
        val root = roots[owner] ?: return
        if (!root.isAttachedToWindow) return
        guarded(owner) {
            if (!eligible(root.context) || failed[owner] == true) {
                sessions[owner]?.restore()
            } else {
                val session = sessions.getOrPut(owner) { FragmentTabletDualPaneSession(owner, root) }
                session.install()
            }
        }
    }

    fun refreshSettings() {
        failed.clear()
        roots.keys.toList().forEach { owner -> roots[owner]?.post { reconcile(owner) } }
    }

    fun onSlide(sheet: View, progress: Float) {
        sessions.values.toList().filter { it.root.parent === sheet && failed[it.controller] != true }.forEach { session -> guarded(session.controller) { session.onSlide(progress) } }
    }

    fun onRestored(owner: Any) { roots[owner]?.post { reconcile(owner) } }
    fun active(player: View?): Boolean = sessions.values.any { it.installed && it.player === player && failed[it.controller] != true && eligible(it.root.context) }
    fun activeController(owner: Any): Boolean = sessions[owner]?.let {
        it.installed && failed[owner] != true && eligible(it.root.context)
    } == true
    fun coverReady(player: View?): Boolean = sessions.values.any { it.installed && it.player === player && failed[it.controller] != true && eligible(it.root.context) && it.nativeCoverReady() }
}
