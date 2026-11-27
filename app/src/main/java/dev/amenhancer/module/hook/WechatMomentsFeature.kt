package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class WechatMomentsFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_WECHAT_MOMENTS
    override fun install(context: HookContext): FeatureInstallResult =
        if (context.config.settings().wechatMomentsShareEnabled) context.target.wechatMoments.install().toFeatureInstallResult()
        else FeatureInstallResult.disabled()
}
