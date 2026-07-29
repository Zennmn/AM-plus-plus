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

        val recyclerResolution = context.symbols.resolve(AppleMusicSymbols.RecyclerView)
        val fragmentResolution = context.symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val recyclerClass = recyclerResolution.valueOrNull()
        val fragmentClass = fragmentResolution.valueOrNull()
        if (recyclerClass == null || fragmentClass == null) {
            return FeatureInstallResult.degraded(
                listOf(recyclerResolution, fragmentResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
        }

        val vectorResolution = context.symbols.resolve(AppleMusicSymbols.LyricsLineVector)
        val sessionResolution = context.symbols.resolve(AppleMusicSymbols.LyricsSessionProcessor)
        val callbackResolution = context.symbols.resolve(AppleMusicSymbols.LyricsHighlightCallback)
        val viewModelResolution = context.symbols.resolve(AppleMusicSymbols.LyricsViewModel)
        val targets = LyricBlurTargets(
            recyclerViewClass = recyclerClass,
            lyricsFragmentClass = fragmentClass,
            lyricsLineVectorClass = vectorResolution.valueOrNull(),
            sessionProcessor = sessionResolution.valueOrNull(),
            highlightCallback = callbackResolution.valueOrNull(),
            lyricsViewModelClass = viewModelResolution.valueOrNull(),
        )
        if (targets.highlightCallback == null && targets.lyricsViewModelClass == null) {
            return FeatureInstallResult.degraded(
                listOf(callbackResolution, viewModelResolution).joinToString { it.summary },
            )
        }

        OpenSourceLyricBlurPort().install(targets)
        val optionalFailures = listOf(
            vectorResolution,
            sessionResolution,
            callbackResolution,
            viewModelResolution,
        )
            .filterNot { it is TargetResolution.Found<*> }
        return if (optionalFailures.isEmpty()) {
            FeatureInstallResult.active(
                "a23bc/amlyricblur core installed; ${fragmentResolution.summary}; ${callbackResolution.summary}"
            )
        } else {
            FeatureInstallResult.degraded(
                "Lyric blur installed with fallback hooks; " + optionalFailures.joinToString { it.summary }
            )
        }
    }
}
