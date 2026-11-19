package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.FeatureState
import java.util.IdentityHashMap

/** Registry keys are Fragment view identities; two recreated roots can never share a glass session. */
@RequiresApi(33)
internal object FragmentGlassRuntime {
    private val sessions = IdentityHashMap<Any, FragmentGlassSessionLifecycle>()
    private val firstDraws = IdentityHashMap<Any, FragmentGlassFirstDraw>()
    private var installation: HostSubscription? = null
    private var registered = false
    private var failedInstall = false

    fun discover(view: View, config: TargetConfigClient) {
        if (Build.VERSION.SDK_INT < 33 || !config.settings().phoneLiquidGlassEnabled) return
        AmMultiSurfaceGlassRuntime.configure(config)
        val activity = activity(view.context) ?: return
        val build = targetBuild(activity)
        AppleMusicHostFactory.installMultiSurfaceGlassHooks(activity.applicationContext, activity.classLoader, build, AmMultiSurfaceGlassRuntime)
        if (!FragmentChromeFactory.supports(build) || installation != null || failedInstall) return
        try {
            installation = FragmentChromeFactory.install(activity.classLoader, build, object : FragmentPlayerSurfaceObserver {
                override fun onPreparing(root: ViewGroup) {
                    if (config.settings().phoneLiquidGlassEnabled) firstDraws.getOrPut(root) {
                        FragmentGlassFirstDraw(root) { config.settings().phoneLiquidGlassEnabled }
                    }
                }
                override fun onCreated(surface: FragmentPlayerSurfacePort) {
                    val identity = surface.viewSessionIdentity
                    sessions.remove(identity)?.close()
                    if (!config.settings().phoneLiquidGlassEnabled) return
                    try {
                        val pendingDraw = firstDraws[identity]
                        val ready = {
                            if (firstDraws[identity] === pendingDraw) firstDraws.remove(identity)?.close()
                            config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS,
                                FeatureState.ACTIVE, "Fragment 玻璃已渲染：原生布局、导航透镜及完整迷你播放器", build.displayName))
                        }
                        val failure: (Throwable) -> Unit = { error -> onFailure(identity, error) }
                        sessions[identity] = if (surface.tabletChrome != null)
                            FragmentTabletGlassSession(surface, config, ready, failure)
                        else FragmentPhoneGlassSession(surface, config, ready, failure)
                    } catch (error: Throwable) { onFailure(identity, error) }
                }
                override fun onDestroyed(identity: Any) {
                    firstDraws.remove(identity)?.close()
                    sessions.remove(identity)?.close()
                }
                override fun onMiniTouch(identity: Any, event: MotionEvent): Boolean? =
                    sessions[identity]?.observeMiniTouch(event)
                override fun onContentTouch(identity: Any, event: MotionEvent): Boolean? =
                    sessions[identity]?.observeContentTouch(event)
                override fun onFailure(identity: Any?, error: Throwable) {
                    if (identity != null) {
                        firstDraws.remove(identity)?.close()
                        sessions.remove(identity)?.close()
                    } else {
                        firstDraws.values.forEach { it.close() }; firstDraws.clear()
                        sessions.values.forEach { it.close() }; sessions.clear()
                    }
                    ModernXposedRuntime.log("Fragment glass restored native chrome", error)
                    config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS,
                        FeatureState.FAILED, "Fragment 玻璃失败，已恢复原生界面：${error.javaClass.simpleName}", build.displayName))
                }
            })
            if (!registered) {
                registered = true
                activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) { sessions.values.filter { it.activity === activity }.forEach { it.foreground(true) } }
                    override fun onActivityPaused(activity: Activity) { sessions.values.filter { it.activity === activity }.forEach { it.foreground(false) } }
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) {
                        firstDraws.keys.toList().filter { identity ->
                            (identity as? View)?.let { this@FragmentGlassRuntime.activity(it.context) } === activity
                        }.forEach { firstDraws.remove(it)?.close() }
                        sessions.entries.filter { (_, session) -> session.activity === activity }
                            .map { it.key }.forEach { sessions.remove(it)?.close() }
                    }
                })
            }
        } catch (error: Throwable) {
            failedInstall = true
            ModernXposedRuntime.log("Fragment glass installation failed", error)
            config.reportHealth(FeatureHealth(ModuleConstants.FEATURE_PHONE_LIQUID_GLASS, FeatureState.FAILED,
                "Fragment 玻璃安装失败：${error.javaClass.simpleName}: ${error.message}", build.displayName))
        }
    }

    private fun activity(context: Context): Activity? {
        var current = context
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}
