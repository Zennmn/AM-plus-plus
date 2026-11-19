package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.classical.ClassicalHostProfile
import dev.amenhancer.host.classical.ClassicalHostProfiles
import dev.amenhancer.module.ui.EmbeddedHostActivityRole
import java.lang.reflect.Modifier

internal class ClassicalSettingsActivityMatcher(
    private val settingsActivityName: String,
    private val hostPackageName: String,
) : SettingsActivityMatcher {
    override fun roleFor(activity: Activity): EmbeddedHostActivityRole? =
        if (activity.packageName == hostPackageName && activity.javaClass.name == settingsActivityName) {
            EmbeddedHostActivityRole.Settings
        } else null

    override fun isPlayerActivity(activity: Activity): Boolean = false

    override fun isMainContentActivity(activity: Activity): Boolean = false
}

internal class ClassicalSettingsViewBridge : SettingsViewBridge {
    override val supportsViewFallback: Boolean = true
    override fun supportsGlassScrollCollapse(activity: Activity): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 &&
            resolveClassicalProfile(activity)?.let { it.productionEnabled && it.capability("glass") } == true
    override fun supportsStartupPagePrewarm(activity: Activity): Boolean = false
    override fun supportsLyricsPronunciation(activity: Activity): Boolean = false
    override fun fragmentView(fragment: Any): ViewGroup? = null
    override fun findSettingsListOverlayContainer(root: View): ViewGroup? = null
    override fun injectNativeSettingsPreference(fragment: Any, activity: Activity): Boolean = false
    override fun findSettingsInsertionContainer(root: ViewGroup): ViewGroup? = null
    override fun refreshNativePreferenceAdapter(root: ViewGroup?) = Unit
}

internal class ClassicalSettingsEntryInstaller(
    private val context: Context,
) {
    private val scope = HookRegistrationScope()

    fun install(loader: ClassLoader, observer: SettingsEntryObserver): TargetCapabilityInstall {
        val profile = resolveClassicalProfile(context)
            ?: return TargetCapabilityInstall.Unsupported("Classical host profile not found")
        val settingsClassName = profile.document.getJSONObject("activities").getString("settings")
        return runCatching {
            val settingsClass = loader.loadClass(settingsClassName)
            val activityResult = inheritedMethod(
                settingsClass,
                "onActivityResult",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Intent::class.java,
            )
            require(!Modifier.isStatic(activityResult.modifiers)) { "Unexpected static onActivityResult" }
            check(ModernXposedRuntime.hookMethod(activityResult, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!scope.isActive || param.throwable != null) return
                    val activity = param.thisObject as? Activity ?: return
                    if (!settingsClass.isInstance(activity) || activity.packageName != context.packageName) return
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    runCatching { observer.onActivityResult(activity, requestCode, resultCode, data) }
                }
            }, scope)) { "Classical SettingsActivity.onActivityResult hook failed" }
            scope.activate()
            TargetCapabilityInstall.Active("Installed Classical SettingsActivity entry and SAF bridge")
        }.getOrElse { error ->
            scope.close()
            TargetCapabilityInstall.Degraded("Classical settings entry failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun inheritedMethod(type: Class<*>, name: String, vararg parameters: Class<*>): java.lang.reflect.Method {
        for (owner in generateSequence(type) { it.superclass }) {
            val method = runCatching { owner.getDeclaredMethod(name, *parameters) }.getOrNull()
            if (method != null) return method.apply { isAccessible = true }
        }
        throw NoSuchMethodException("${type.name}#$name")
    }
}

private fun resolveClassicalProfile(ctx: Context): ClassicalHostProfile? {
    val build = classicalTargetBuild(ctx)
    return ClassicalHostProfiles.find(build.packageName, build.versionName, build.versionCode)
}
