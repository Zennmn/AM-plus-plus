package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Mirrors the modified APK's c1.e(...) prefix, but only while Apple Music's
 * own tablet resource qualifier is active in landscape and the tablet
 * dual-pane player is enabled. Returning null here
 * suppresses the Editorial Video URL while preserving its static preview
 * frame and the separate Music Video playback path.
 */
internal class EditorialVideoFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_EDITORIAL_VIDEO

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().dualPaneEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.editorialVideo.install().toFeatureInstallResult()
    }
}
