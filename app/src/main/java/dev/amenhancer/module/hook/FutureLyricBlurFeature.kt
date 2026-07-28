package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.FeatureState

/** Module setting and health adapter around the upstream AMLyricBlur core. */
internal class FutureLyricBlurFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_FUTURE_BLUR

    override fun isEnabled(context: HookContext): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.config.settings().futureBlurEnabled

    override fun install(context: HookContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            context.report(key, FeatureState.DISABLED, "Requires Android 12 or newer")
            return
        }

        val recyclerResolution = context.symbols.resolve(AppleMusicSymbols.RecyclerView)
        val fragmentResolution = context.symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val recyclerClass = recyclerResolution.valueOrNull()
        val fragmentClass = fragmentResolution.valueOrNull()
        if (recyclerClass == null || fragmentClass == null) {
            context.report(
                key,
                FeatureState.DEGRADED,
                listOf(recyclerResolution, fragmentResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
            return
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
            context.report(
                key,
                FeatureState.DEGRADED,
                listOf(callbackResolution, viewModelResolution).joinToString { it.summary },
            )
            return
        }

        OpenSourceLyricBlurPort().install(targets)
        val optionalFailures = listOf(
            vectorResolution,
            sessionResolution,
            callbackResolution,
            viewModelResolution,
        )
            .filterNot { it is TargetResolution.Found<*> }
        context.report(
            key,
            if (optionalFailures.isEmpty()) FeatureState.ACTIVE else FeatureState.DEGRADED,
            if (optionalFailures.isEmpty()) {
                "a23bc/amlyricblur core installed; ${fragmentResolution.summary}; ${callbackResolution.summary}"
            } else {
                "Lyric blur installed with fallback hooks; " + optionalFailures.joinToString { it.summary }
            },
        )
    }
}
