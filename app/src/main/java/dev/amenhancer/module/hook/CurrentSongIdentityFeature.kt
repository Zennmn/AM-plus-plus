package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Publishes current-song identity for custom-lyrics coordination and embedded
 * settings. Not gated by a module setting — dependent features fail closed
 * when the capability is unavailable.
 */
internal class CurrentSongIdentityFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CURRENT_SONG_IDENTITY

    override fun install(context: HookContext): FeatureInstallResult =
        context.target.currentSongIdentity.install().toFeatureInstallResult()
}
