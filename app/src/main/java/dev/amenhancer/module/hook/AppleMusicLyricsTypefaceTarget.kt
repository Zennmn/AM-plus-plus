package dev.amenhancer.module.hook

/** Apple Music adapter for the semantic lyric-typeface capability. */
internal class AppleMusicLyricsTypefaceTarget(
    private val symbols: TargetSymbolResolver,
    private val session: LyricsTypefaceSession,
) : LyricsTypefaceTarget {
    override fun install(): TargetCapabilityInstall {
        val fragmentResolution = symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val fragmentClass = fragmentResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(fragmentResolution.summary)
        val recyclerResolution = symbols.resolve(AppleMusicSymbols.RecyclerView)
        val recyclerClass = recyclerResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(recyclerResolution.summary)

        val preparation = session.prepare()
        when (preparation) {
            LyricsTypefacePreparation.Disabled ->
                return TargetCapabilityInstall.Degraded("Lyrics font was disabled before target installation")
            is LyricsTypefacePreparation.Failed ->
                return TargetCapabilityInstall.Degraded(preparation.message)
            // Loading returns immediately; the font read and Typeface parse
            // happen on the session's background thread, and every observed
            // lyric root is re-applied once the load completes.
            LyricsTypefacePreparation.Ready, LyricsTypefacePreparation.Loading -> Unit
        }

        val resumeOwner = findLifecycleDeclaringClass(fragmentClass, "onResume")
            ?: return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.onResume was not found",
            )
        val hooks = ModernXposedRuntime.hookAllMethods(
            resumeOwner,
            "onResume",
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject?.takeIf(fragmentClass::isInstance) ?: return
                    session.attachToFragment(owner, recyclerClass)
                }
            },
        )
        if (hooks.isEmpty()) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.onResume had no hookable method",
            )
        }
        session.activate()
        val fontStatus = if (preparation is LyricsTypefacePreparation.Ready) {
            "font ready"
        } else {
            "font loading in background"
        }
        return TargetCapabilityInstall.Active(
            "Lyrics typeface installed for ${LyricsTypefaceLayoutContract.layoutNames.size} " +
                "verified player lyric layouts ($fontStatus); ${fragmentResolution.summary}",
        )
    }

    private fun findLifecycleDeclaringClass(start: Class<*>, methodName: String): Class<*>? =
        generateSequence(start) { type -> type.superclass }
            .firstOrNull { type ->
                type.declaredMethods.any { method ->
                    method.name == methodName && method.parameterCount == 0
                }
            }
}
