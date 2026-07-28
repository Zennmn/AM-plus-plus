package dev.amenhancer.module.hook

import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.FeatureState

/**
 * Mirrors the modified APK's c1.e(...) prefix, but only while Apple Music's
 * own tablet resource qualifier is active in landscape. Returning null here
 * suppresses the Editorial Video URL while preserving its static preview
 * frame and the separate Music Video playback path.
 */
internal class EditorialVideoFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_EDITORIAL_VIDEO

    override fun isEnabled(context: HookContext): Boolean =
        context.config.settings().disableEditorialVideoOnTablet

    override fun install(context: HookContext) {
        val resolution = context.symbols.resolve(AppleMusicSymbols.EditorialVideoUrlSelector)
        val selector = resolution.valueOrNull()
            ?: run {
                context.report(key, FeatureState.DEGRADED, resolution.summary)
                return
            }

        ModernXposedRuntime.hookMethod(selector, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!TabletModeQualifier.isOfficialTabletLandscape(context.application)) return
                param.result = null
            }
        })
        context.report(
            key,
            FeatureState.ACTIVE,
            "Installed tablet-landscape Editorial Video URL suppression on " +
                "${selector.declaringClass.name}.${selector.name}; ${resolution.summary}",
        )
    }
}
