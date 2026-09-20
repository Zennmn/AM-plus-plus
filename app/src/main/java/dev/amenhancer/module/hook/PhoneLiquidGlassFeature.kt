package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient

internal class PhoneLiquidGlassFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_PHONE_LIQUID_GLASS
    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().phoneLiquidGlassEnabled) return FeatureInstallResult.disabled()
        if (Build.VERSION.SDK_INT < 33) return FeatureInstallResult.unsupported("完整玻璃折射需要 Android 13+")
        val expected = TargetBuild(ModuleConstants.TARGET_PACKAGE, GlassPolicy.VERSION_NAME, GlassPolicy.VERSION_CODE)
        if (context.target.identity != expected.displayName) return FeatureInstallResult.unsupported("玻璃适配仅支持 Apple Music 6.5.2 (1586)")
        return FeatureInstallResult.degraded("等待手机页面挂载；成功采样首帧后报告就绪")
    }
}

internal object PhoneLiquidGlassResourceHook {
    fun install(config: TargetConfigClient) {
        listOf("bottom_navigation", "mini_player").forEach { name ->
            LayoutInflationRegistry.register(name) { view ->
                if (Build.VERSION.SDK_INT >= 33 && config.settings().phoneLiquidGlassEnabled) PhoneGlassRuntime.discover(view, config)
            }
        }
    }
}
