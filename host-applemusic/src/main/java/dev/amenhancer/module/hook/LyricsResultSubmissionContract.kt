package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Method

/**
 * Exact, per-build descriptor set for the native lyrics result channel.
 *
 * The native flow a replacement has to join is:
 *
 *   PlaybackItem -> PlayerLyricsViewModel.loadLyrics(item)
 *                -> coroutine result written through the view model's
 *                   MutableLiveData lyrics result container
 *                -> PlayerLyricsViewModel.buildTimeRangeToLyricsMap(ptr)
 *                -> Fragment observer -> native lyric installer
 *
 * The view model, its result container and the container's write methods are
 * library surface, so every member is declared in the version profile and
 * resolved once, before any hook is registered. Nothing here guesses a shape:
 * the contract is unavailable whenever the profile that produced the view
 * model has no complete `lyricResultSubmission` inventory, and the caller
 * degrades with a diagnostic instead.
 *
 * The profile is selected by the identity the resolver already pinned for the
 * exact installed build ([TargetResolution.Found.profileId]); it is never
 * inferred from a runtime class name.
 */
internal class LyricsResultSubmissionContract private constructor(
    val vmClass: Class<*>,
    private val members: FragmentProfileMembers,
    val profileId: String,
    val loader: ClassLoader,
    val unavailableFallbackMethod: Method?,
) {
    val fragmentViewModelField get() = members.field("fragmentViewModel")
    val lyricsResultField get() = members.field("lyricsResult")

    val setValueMethod get() = members.method("resultSet")
    val postValueMethod get() = members.method("resultPost")
    val resultValueMethod get() = members.method("resultValue")
    val fragmentResumeMethod get() = members.method("fragmentResume")
    val buildIndexMethod get() = members.method("buildIndex")
    val loadLyricsMethod get() = members.method("loadLyrics")
    val systemLyricsLanguageMethod get() = members.method("systemLyricsLanguage")

    /** Native context and liveness seams for a chosen pointer. */
    val pointerGetMethod get() = members.method("pointerGet")
    val pointerAdamIdMethod get() = members.method("pointerAdamId")
    val pointerScriptMethod get() = members.method("pointerScript")

    /** View model teardown; a closed view model must never receive a publish. */
    val clearedMethod get() = members.method("clear")

    companion object {
        private const val SECTION = "lyricResultSubmission"

        private val requirements = FragmentMemberRequirements(
            methods = setOf(
                "resultSet",
                "resultPost",
                "resultValue",
                "fragmentResume",
                "buildIndex",
                "loadLyrics",
                "systemLyricsLanguage",
                "pointerGet",
                "pointerAdamId",
                "pointerScript",
                "clear",
            ),
            fields = setOf(
                "fragmentViewModel",
                "lyricsResult",
            ),
        )

        /**
         * Resolves the exact result-channel contract for the installed build.
         * Returns null with a logged reason when the version has no verified
         * inventory, so a stale profile degrades instead of hooking a guess.
         */
        fun create(
            symbols: TargetSymbolResolver,
            logger: (String) -> Unit,
        ): LyricsResultSubmissionContract? {
            val resolved = symbols.resolve(AppleMusicSymbols.LyricsViewModel)
            if (resolved !is TargetResolution.Found) {
                logger("lyric result submission unavailable: ${resolved.summary}")
                return null
            }
            if (resolved.match != SymbolMatch.VERSION_PROFILE) {
                logger(
                    "lyric result submission requires an exact version profile; " +
                        "${resolved.summary}",
                )
                return null
            }
            val profileId = resolved.profileId ?: return null
            val profile = AppleMusicHostProfiles.all.singleOrNull {
                it.id == profileId && it.productionEnabled
            } ?: run {
                logger("lyric result submission profile $profileId is not a production profile")
                return null
            }
            val section = profile.document.optJSONObject(SECTION) ?: return null
            if (section.optInt("contractVersion") != 1) {
                logger("$SECTION.contractVersion must be 1")
                return null
            }
            val vmClass = resolved.value
            val loader = vmClass.classLoader ?: ClassLoader.getSystemClassLoader()
            return runCatching {
                val members = FragmentProfileMembers(loader, section, SECTION, requirements)
                val fallback = if (section.optJSONObject("methods")?.has("unavailableFallback") == true) {
                    members.method("unavailableFallback").also {
                        check(it.declaringClass == members.method("fragmentResume").declaringClass &&
                            it.parameterCount == 0 && it.returnType == Void.TYPE)
                    }
                } else null
                LyricsResultSubmissionContract(
                    vmClass = vmClass,
                    members = members,
                    profileId = profileId,
                    loader = loader,
                    unavailableFallbackMethod = fallback,
                )
            }.onFailure { error ->
                logger("lyric result submission contract unavailable: ${error.message}")
            }.getOrNull()
        }
    }
}

