package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.amenhancer.glass.GlassHostForm
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Method
import java.util.WeakHashMap

@RequiresApi(33)
internal object PhoneGlassRuntime {
    private val sessions = WeakHashMap<Activity, GlassSession>()
    private val failed = java.util.Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var applicationRegistered = false
    private var hooksInstalled = false
    private var hooksAttempted = false

    fun discover(view: View, config: TargetConfigClient) {
        if (!view.isAttachedToWindow) {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.removeOnAttachStateChangeListener(this); discover(v, config) }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            return
        }
        val activity = activity(view.context) ?: return
        if (activity in failed || activity.isFinishing || activity.isDestroyed) return
        val build = targetBuild(activity)
        // Both host forms share one seam whitelist; the form itself is routed below.
        if (GlassHostForm.values().none { GlassPolicy.supports(android.os.Build.VERSION.SDK_INT, build.versionCode, build.versionName, it) }) return
        if (!config.settings().phoneLiquidGlassEnabled) return
        registerLifecycle(activity.application)
        view.post {
            if (activity in failed || activity.isDestroyed) return@post
            try {
                installHooks(activity.classLoader)
                sessions[activity]?.takeUnless { it.ownsCurrentHierarchy() }?.let { it.close(); sessions.remove(activity) }
                val desired = createSession(activity, config) { error -> fail(activity, config, error) }
                if (desired == null) { sessions.remove(activity)?.close(); return@post }
                val session = sessions[activity]?.takeIf { it.javaClass == desired.javaClass }
                    ?: desired.also { sessions.remove(activity)?.close(); sessions[activity] = it }
                session.attachAvailableViews()
            } catch (error: Throwable) { fail(activity, config, error) }
        }
    }

    /** Routes the host form; null means no session may exist for this activity right now. */
    private fun createSession(activity: Activity, config: TargetConfigClient, onFail: (Throwable) -> Unit): GlassSession? {
        val build = targetBuild(activity)
        if (config.settings().phoneLiquidGlassEnabled &&
            !TabletModeQualifier.isOfficialTablet(activity) &&
            GlassPolicy.supports(android.os.Build.VERSION.SDK_INT, build.versionCode, build.versionName, GlassHostForm.PhoneStacked)
        ) {
            return PhoneGlassSession(activity, config, onFail)
        }
        if (config.settings().phoneLiquidGlassEnabled &&
            TabletModeQualifier.isEligible(activity) &&
            GlassPolicy.supports(android.os.Build.VERSION.SDK_INT, build.versionCode, build.versionName, GlassHostForm.TabletDualPane)
        ) {
            return TabletDualPaneGlassSession(activity, config, onFail)
        }
        return null
    }

    private fun fail(activity: Activity, config: TargetConfigClient, error: Throwable) {
        failed += activity
        sessions.remove(activity)?.close()
        ModernXposedRuntime.log("liquid glass 1586 restored native UI", error)
        config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS, FeatureState.FAILED,
            "玻璃接入失败，已恢复原生界面：${error.javaClass.simpleName}: ${error.message}", targetBuild(activity).displayName))
    }

    private fun installHooks(loader: ClassLoader) {
        if (hooksInstalled) return
        check(!hooksAttempted) { "Glass hook installation previously failed; restart the host to retry" }
        hooksAttempted = true
        // The stacked and flat holders each drive their own slide contract; the outer
        // activity reflection resolves both holder shapes.
        for (holderName in listOf("StackedBottomNavigationHolder", "FlatBottomNavigationHolder")) {
            val holder = loader.loadClass("com.apple.android.music.common.activity.PlayerActivity\$$holderName")
            ModernXposedRuntime.hookMethod(holder.getDeclaredMethod("c", Float::class.javaPrimitiveType), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject?.let(::outerActivity) ?: return
                    sessions[owner]?.onSlide((param.args[0] as Number).toFloat())
                }
            })
        }
        ModernXposedRuntime.hookMethod(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = param.thisObject as? View ?: return
                sessions.values.firstOrNull { it.miniRoot === root }?.observeTouch(param.args[0] as MotionEvent)
            }
        })
        val behavior = loader.loadClass("com.apple.android.music.player.PlayerBottomSheetBehavior")
        // The tablet row keeps the sheet drag inside the capsule handles. The flat host captures
        // a band gesture inside this Behavior, so the gate hooks the same touch entries the host
        // uses; the static-collapsed wrapper delegates to it and is gated as well.
        runCatching { TabletRowGestureGate.install(behavior) }
        runCatching {
            TabletRowGestureGate.install(
                loader.loadClass("com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior"),
            )
        }
        val peek = method(behavior, "F", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        ModernXposedRuntime.hookMethod(peek, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                sessions.values.firstOrNull { it.playerBehavior === param.thisObject }?.let {
                    it.observeNativePeek((param.args[0] as Number).toInt())
                    if (it.activated) param.args[0] = it.peekHeight()
                }
            }
        })
        // Apple's scrolling behavior reserves bottom padding on the content host.
        // Redirect it before setPadding rather than fighting it with another layout every frame.
        ModernXposedRuntime.hookMethod(View::class.java.getDeclaredMethod("setPadding", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                sessions.values.firstNotNullOfOrNull { it.redirectedPadding(param.thisObject) }?.let { param.args[3] = it }
            }
        })
        // Only the explicitly managed player layers are affected. Preserve the
        // host's changing target alpha (track changes, motion artwork, lyrics).
        ModernXposedRuntime.hookMethod(View::class.java.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val alpha = (param.args[0] as Number).toFloat()
                sessions.values.firstNotNullOfOrNull { it.redirectedLayerAlpha(param.thisObject, alpha) }
                    ?.let { param.args[0] = it }
            }
        })
        hooksInstalled = true
    }

    private fun registerLifecycle(application: Application) {
        if (applicationRegistered) return
        applicationRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) { sessions[activity]?.foreground(true) }
            override fun onActivityPaused(activity: Activity) { sessions[activity]?.foreground(false) }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { sessions.remove(activity)?.close(); failed.remove(activity) }
        })
    }

    private fun outerActivity(instance: Any): Activity? = instance.javaClass.declaredFields.firstNotNullOfOrNull { field ->
        if (!Activity::class.java.isAssignableFrom(field.type)) null else runCatching { field.isAccessible = true; field.get(instance) as? Activity }.getOrNull()
    }

    fun activity(context: Context): Activity? {
        var current = context
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current!!.getDeclaredMethod(name, *parameters) }.getOrNull()?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$name")
    }
}
