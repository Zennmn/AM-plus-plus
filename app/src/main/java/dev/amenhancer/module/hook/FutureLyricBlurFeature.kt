package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.module.ModuleConstants

/** Module setting and health adapter around the upstream AMLyricBlur core. */
internal class FutureLyricBlurFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_FUTURE_BLUR

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().futureBlurEnabled) return FeatureInstallResult.disabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return FeatureInstallResult.unsupported("Requires Android 12 or newer")
        }

        return context.target.bidirectionalLyricBlur.install().toFeatureInstallResult()
    }
}
