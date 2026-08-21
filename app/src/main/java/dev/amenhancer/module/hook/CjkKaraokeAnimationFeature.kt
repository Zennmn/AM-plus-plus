package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Installs the narrow Apple Music 6.5.2 per-word ValueAnimator adaptation.
 *
 * The target adapter owns all version and symbol checks; unsupported hosts
 * therefore report a degraded health result without touching existing lyric
 * layout or blur paths. Apple’s native Unicode/rush-gradient classifier is
 * intentionally not rewritten by this feature.
 */
internal class CjkKaraokeAnimationFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CJK_KARAOKE_ANIMATION

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().cjkKaraokeAnimationEnabled) {
            return FeatureInstallResult.disabled("CJK 长尾歌词动画已关闭")
        }
        return context.target.cjkKaraokeAnimation.install().toFeatureInstallResult()
    }
}
