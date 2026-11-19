package dev.amenhancer.module.hook

import android.app.Application
import android.content.Context
import android.content.res.Resources
import dev.amenhancer.host.classical.ClassicalHostProfile
import java.lang.reflect.Field

/** Unlocks landscape + tablet dual-pane layout on Apple Music Classical. */
internal class ClassicalDualPaneTarget(
    private val application: Application,
    private val classLoader: ClassLoader,
    private val profile: ClassicalHostProfile,
    private val enabled: () -> Boolean,
) : DualPaneTarget {
    private val scope = HookRegistrationScope()
    private var installedResult: TargetCapabilityInstall? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return runCatching { installOnce() }
            .getOrElse { error ->
                scope.close()
                TargetCapabilityInstall.Degraded(
                    "Classical dual-pane install failed: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
            .also { installedResult = it }
    }

    private fun installOnce(): TargetCapabilityInstall {
        if (!profile.productionEnabled || !profile.capability("dualPane")) {
            return TargetCapabilityInstall.Unsupported("Classical build has no verified dual-pane contract")
        }
        val tablet = profile.document.getJSONObject("tablet")
        val resources = application.resources
        val portraitOnlyId = resources.getIdentifier(tablet.getString("portraitOnlyBool"), "bool", profile.packageName)
        val isTabletId = resources.getIdentifier(tablet.getString("isTabletBool"), "bool", profile.packageName)
        require(portraitOnlyId != 0 && isTabletId != 0) {
            "Missing Classical dual-pane boolean resources ($portraitOnlyId/$isTabletId)"
        }

        val detectorClass = classLoader.loadClass(tablet.getString("detectorClass"))
        val initMethod = detectorClass.getDeclaredMethod(tablet.getString("detectorInitMethod"), Context::class.java)
            .apply { isAccessible = true }
        val getterMethod = detectorClass.getDeclaredMethod(tablet.getString("detectorGetterMethod"))
            .apply { isAccessible = true }
        val flagField: Field = detectorClass.getDeclaredField(tablet.getString("detectorField"))
            .apply { isAccessible = true }

        val getBoolean = Resources::class.java.getDeclaredMethod("getBoolean", Int::class.javaPrimitiveType)
        check(ModernXposedRuntime.hookMethod(getBoolean, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!scope.isActive || param.throwable != null || !enabled()) return
                val id = param.args.getOrNull(0) as? Int ?: return
                if (id != portraitOnlyId && id != isTabletId) return
                val receiver = param.thisObject as? Resources ?: return
                val pkg = runCatching { receiver.getResourcePackageName(id) }.getOrNull()
                if (pkg != profile.packageName) return
                param.result = (id == isTabletId)
            }
        }, scope)) { "Resources.getBoolean hook failed" }

        check(ModernXposedRuntime.hookMethod(initMethod, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (scope.isActive && enabled()) {
                    runCatching { flagField.set(null, java.lang.Boolean.TRUE) }
                }
            }
        }, scope)) { "Classical tablet detector init hook failed" }

        check(ModernXposedRuntime.hookMethod(getterMethod, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (scope.isActive && enabled()) {
                    runCatching { flagField.set(null, java.lang.Boolean.TRUE) }
                    param.result = java.lang.Boolean.TRUE
                }
            }
        }, scope)) { "Classical tablet detector getter hook failed" }

        scope.activate()
        if (enabled()) {
            runCatching { flagField.set(null, java.lang.Boolean.TRUE) }
        }
        return TargetCapabilityInstall.Active("Installed Classical dual-pane landscape and tablet layout override")
    }
}
