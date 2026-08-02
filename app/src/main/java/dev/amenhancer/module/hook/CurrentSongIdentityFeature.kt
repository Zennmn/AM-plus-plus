package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Diagnostics capability: serves current-song identity requests from the
 * standalone settings page. Not gated by a module setting — requests fail
 * closed when the capability is unavailable.
 */
internal class CurrentSongIdentityFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CURRENT_SONG_IDENTITY

    override fun install(context: HookContext): FeatureInstallResult =
        context.target.currentSongIdentity.install().toFeatureInstallResult()
}
