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
        val build = context.target.build
        GlassDiagLog.i("phone glass feature install: ${build.versionName}/${build.versionCode}, " +
            "glass=${dev.amenhancer.host.applemusic.AppleMusicHostProfiles.supportsGlass(build.versionCode, build.versionName)}, " +
            "collapse=${dev.amenhancer.host.applemusic.AppleMusicHostProfiles.supportsGlassScrollCollapse(build.versionCode, build.versionName)}")
        if (!dev.amenhancer.host.applemusic.AppleMusicHostProfiles.supportsGlass(build.versionCode, build.versionName)) {
            return FeatureInstallResult.unsupported("当前宿主 profile 未提供已验收的玻璃能力")
        }
        return FeatureInstallResult.degraded("等待手机页面挂载；成功采样首帧后报告就绪")
    }
}

internal object PhoneLiquidGlassResourceHook {
    fun install(config: TargetConfigClient) {
        FragmentChromeFactory.registerResources { view ->
            if (Build.VERSION.SDK_INT >= 33 && config.settings().phoneLiquidGlassEnabled) FragmentGlassRuntime.discover(view, config)
        }
        AppleMusicHostFactory.registerChromeResources { view ->
            if (Build.VERSION.SDK_INT >= 33 && config.settings().phoneLiquidGlassEnabled &&
                !FragmentChromeFactory.supports(targetBuild(view.context))) PhoneGlassRuntime.discover(view, config)
        }
    }
}
