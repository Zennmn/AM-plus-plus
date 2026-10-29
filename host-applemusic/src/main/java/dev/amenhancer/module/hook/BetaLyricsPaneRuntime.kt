package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.abs

/** Scoped to the independent right fragment. Phone/fullscreen lyrics keep their native controls. */
internal class BetaLyricsPaneRuntime(private val lyricsClass: Class<*>,
    private val karaoke: FragmentKaraokeWidthContract, private val native: FragmentLyricsRuntimeContract) {
    private val sessions = WeakHashMap<Any, Session>()
    private val create = native.createView
    private val resume = native.resume
    private val destroy = native.destroyView
    private val metrics = native.metrics
    private val chrome = native.chrome

    fun validate() {
        check(lyricsClass.name == "com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        check(metrics.returnType == Boolean::class.javaPrimitiveType)
        check(native.bindingField.declaringClass == lyricsClass && create.declaringClass == lyricsClass)
    }

    fun install(registration: HookRegistrationScope) {
        fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(method, callback, registration))
        }
        hook(create, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val installedPointer = native.installedPointer ?: return
                val fragment = param.thisObject ?: return
                if (!dedicated(fragment)) return
                // Native view creation replaces the adapters but retains the old installation
                // pointer. Invalidate that guard before the new observer replays the same lyrics.
                guarded { installedPointer.set(fragment, null) }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val fragment = param.thisObject ?: return
                val root = param.result as? View ?: return
                if (!dedicated(fragment)) return
                guarded { sessions.remove(fragment)?.close(); sessions[fragment] = Session(fragment, root).also { it.start() } }
            }
        })
        hook(resume, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { fragment -> guarded { sessions[fragment]?.schedule() } }
            }
        })
        hook(karaoke.bind, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val adapter = param.thisObject ?: return
                if (karaoke.initialized(adapter)) return
                val row = param.args.firstOrNull() ?: return
                sessions.values.firstOrNull { it.owns(adapter) }?.let { session -> guarded { session.prepareRowWidth(adapter, row) } }
            }
        })
        hook(metrics, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || param.result != true) return
                param.thisObject?.let { fragment -> guarded { sessions[fragment]?.correctMetrics() } }
            }
        })
        hook(chrome, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                if (!lyricsClass.isInstance(fragment) || !dedicated(fragment)) return
                val root = runCatching { native.getView.invoke(fragment) as? View }.getOrNull() ?: return
                if (!TabletModeQualifier.isEligible(root.context)) return
                hideChrome(root)
                param.result = null
            }
        })
        hook(destroy, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { fragment -> sessions.remove(fragment)?.close() }
            }
        })
        registration.onClose { sessions.values.toList().forEach(Session::close); sessions.clear() }
    }

    private fun dedicated(fragment: Any): Boolean = runCatching {
        native.fragmentId.invoke(fragment) == FragmentDualPanePolicy.RIGHT_HOST_ID
    }.getOrDefault(false)

    private fun hideChrome(root: View) {
        listOf("current_player_item", "controls", "controls_tap_target").forEach { name ->
            val id = root.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
            if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private inner class Session(fragment: Any, root: View) : AutoCloseable {
        private val fragmentRef = WeakReference(fragment)
        private val rootRef = WeakReference(root)
        private val binding = checkNotNull(native.bindingField.get(fragment))
        private val container = checkNotNull(native.container.get(binding) as? View)
        private val recycler = checkNotNull(native.recycler.get(binding) as? ViewGroup)
        private val gradients = checkNotNull(native.gradients.get(binding) as? View)
        private var closed = false
        private var posted = false
        private var listener: Any? = null
        private val layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) schedule()
        }
        private val rowLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRows() }
        private val update = Runnable { posted = false; guarded { refresh() } }

        fun start() {
            val root = rootRef.get() ?: return
            if (TabletModeQualifier.isEligible(root.context)) RightLyricsPaneLayout.apply(root)
            container.addOnLayoutChangeListener(layout)
            recycler.addOnLayoutChangeListener(rowLayout)
            val listenerClass = native.listener
            val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    native.attachMethod -> { (args?.firstOrNull() as? View)?.let(::applyRow); null }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AM++FragmentLyricRows"
                    else -> null
                }
            }
            native.registerListener.invoke(recycler, proxy)
            listener = proxy
            schedule()
        }

        fun schedule() {
            if (closed || posted) return
            posted = true
            if (!container.post(update)) posted = false
        }

        fun refresh() {
            if (closed || !TabletModeQualifier.isEligible(container.context)) return
            rootRef.get()?.let(::hideChrome)
            applyRows()
            fragmentRef.get()?.let { metrics.isAccessible = true; metrics.invoke(it) }
            RightLyricsPaneLayout.reapplyVerticalGradientEdges(gradients)
        }

        fun owns(adapter: Any): Boolean = !closed && fragmentRef.get()?.let { karaoke.owns(it, adapter) } == true
        fun prepareRowWidth(adapter: Any, row: Any) {
            // A saved right fragment can bind before portrait restoration removes it.
            // Its native width contract must hold until onDestroyView, in either orientation.
            if (closed) return
            karaoke.initialize(adapter, row, recycler.width)?.let { width ->
                ModernXposedRuntime.log("Fragment right lyrics: initialized cached-row karaoke width=$width viewport=${recycler.width}")
            }
        }

        fun correctMetrics() {
            if (closed || !TabletModeQualifier.isEligible(container.context) || container.height <= 0) return
            val fragment = fragmentRef.get() ?: return
            val controls = runCatching { native.controls.invoke(fragment) as? View }.getOrNull()
            val sync = checkNotNull(native.synchronizedMetrics.first().get(fragment))
            val offset = native.metricOffset
            offset.setInt(sync, TabletLyricAnchorPolicy.highlightOffset(offset.getInt(sync), container.height))
            native.synchronizedMetrics.forEach { field ->
                val bounds = checkNotNull(field.get(fragment))
                val end = native.metricEnd
                end.setInt(bounds, (end.getInt(bounds) - (controls?.height ?: 0)).coerceAtLeast(0))
            }
            native.refresh.invoke(recycler)
            RightLyricsPaneLayout.reapplyVerticalGradientEdges(gradients)
        }

        private fun applyRow(row: View) {
            if (closed || !TabletModeQualifier.isEligible(row.context)) return
            val primaryIds = listOf("song_lyrics_line", "song_lyrics_word").map {
                row.resources.getIdentifier(it, "id", ModuleConstants.TARGET_PACKAGE)
            }.filter { it != 0 }.toSet()
            val display = row.resources.displayMetrics
            val sizeSp = TabletLyricVisualPolicy.textSizeSp(display.widthPixels.toFloat(), display.heightPixels.toFloat(),
                display.density * row.resources.configuration.fontScale)
            val sizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, display)
            fun visit(view: View) {
                if (view is TextView && view.id in primaryIds && abs(view.textSize - sizePx) > 0.5f) {
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
                }
                (view as? ViewGroup)?.let { group -> repeat(group.childCount) { visit(group.getChildAt(it)) } }
            }
            visit(row)
            if (row.getTag(dev.amenhancer.host.applemusic.R.id.am_enhancer_lyric_spacing_applied) != true) {
                (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.bottomMargin += (TabletLyricVisualPolicy.ITEM_SPACING_EXTRA_DP * row.resources.displayMetrics.density + 0.5f).toInt()
                    row.layoutParams = it
                    row.setTag(dev.amenhancer.host.applemusic.R.id.am_enhancer_lyric_spacing_applied, true)
                }
            }
        }

        private fun applyRows() {
            if (closed) return
            repeat(recycler.childCount) { applyRow(recycler.getChildAt(it)) }
        }

        override fun close() {
            closed = true
            container.removeCallbacks(update)
            container.removeOnLayoutChangeListener(layout)
            recycler.removeOnLayoutChangeListener(rowLayout)
            // Remove only our identity from the exact profile's native listener list.
            (native.listenerList.get(recycler) as? MutableList<*>)?.remove(listener)
            listener = null
        }
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { ModernXposedRuntime.log("Fragment right lyrics layout failed", it) }
    }
}
