package dev.amenhancer.module.hook

import android.app.Application
import dev.amenhancer.module.ModuleConstants

/** Multi-surface Liquid Glass feature installer dedicated to Apple Music Classical. */
internal class ClassicalLiquidGlassFeature(
    private val application: Application,
    private val classLoader: ClassLoader,
) : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_PHONE_LIQUID_GLASS

    override fun install(context: HookContext): FeatureInstallResult {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return FeatureInstallResult.unsupported("完整玻璃折射需要 Android 13+")
        }
        val runtimeResult = ClassicalGlassRuntime.install(application, classLoader, context.config)
            .toFeatureInstallResult()
        return if (context.config.settings().phoneLiquidGlassEnabled) {
            runtimeResult
        } else {
            FeatureInstallResult.disabled()
        }
    }
}
