package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import java.util.Locale

/** Request and match lyric pronunciations, with extra fallbacks for CJK app languages. */
internal object LyricsPronunciationMatching {
    private var installed = false

    @Synchronized
    fun install(profile: AppleMusicHostProfile, loader: ClassLoader) {
        val names = profile.document.optJSONObject("lyricPronunciation") ?: return
        if (installed) return
        val registration = HookRegistrationScope()
        runCatching {
            val members = FragmentProfileMembers(loader, names, "lyricPronunciation", FragmentMemberRequirements(
                methods = setOf("evaluate", "match", "songInfo", "address", "songLanguage", "setTranslation",
                    "pronunciationLanguages", "vectorSize", "vectorGet"),
            ))
            val evaluation = ThreadLocal<Evaluation>()
            val address = members.method("address")
            val songInfo = members.method("songInfo")
            val pronunciationLanguages = members.method("pronunciationLanguages")
            val vectorSize = members.method("vectorSize")
            val vectorGet = members.method("vectorGet")

            fun addressOf(song: Any?): Long? = song?.let {
                runCatching { address.invoke(it) as Long }.getOrNull()?.takeIf { value -> value != 0L }
            }

            fun fallbackTag(vector: Any?, allowOtherScripts: Boolean): String? {
                if (vector == null) return null
                val size = vectorSize.invoke(vector) as Long
                var first: String? = null
                for (index in 0L until size) {
                    val tag = vectorGet.invoke(vector, index) as? String ?: continue
                    if (tag.isBlank()) continue
                    if (tag.split('-').any { it.equals("Latn", ignoreCase = true) }) return tag
                    if (allowOtherScripts && first == null) first = tag
                }
                return first
            }

            fun hook(role: String, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(members.method(role), callback, registration)) { role }
            }

            if (names.optJSONObject("methods")?.has("requestLyrics") == true) {
                hook("requestLyrics", object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // This array feeds l[script]. Keep l[lyrics] and the native script order.
                        @Suppress("UNCHECKED_CAST")
                        val scripts = param.args.getOrNull(3) as? Array<String> ?: return
                        if (scripts.none { "en-Latn".equals(it, ignoreCase = true) }) {
                            param.args[3] = scripts + "en-Latn"
                        }
                    }
                })
            }

            // The same matcher is used when selecting the native track, building the menu,
            // and deciding whether the selected pronunciation is visible.
            hook("match", object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null || param.result != null) return
                    val allowOtherScripts = isCjk(Locale.getDefault().language)
                    runCatching { fallbackTag(param.args[0], allowOtherScripts) }
                        .getOrNull()?.let { param.result = it }
                }
            })

            hook("evaluate", object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.extras["previousEvaluation"] = evaluation.get()
                    evaluation.remove()
                    val language = Locale.getDefault().language
                    if (!isCjk(language)) return
                    val pointer = param.args[0] ?: return
                    val song = runCatching { songInfo.invoke(pointer) }.getOrNull()
                    val songAddress = addressOf(song) ?: return
                    evaluation.set(Evaluation(songAddress, language))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val previous = param.extras["previousEvaluation"] as? Evaluation
                    if (previous == null) evaluation.remove() else evaluation.set(previous)
                }
            })

            hook("songLanguage", object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val current = evaluation.get() ?: return
                    val song = param.thisObject ?: return
                    if (current.languageRead || param.throwable != null || addressOf(song) != current.songAddress) return
                    current.languageRead = true
                    if (!(param.result as? String).equals(current.uiLanguage, ignoreCase = true)) return
                    val hasPronunciation = runCatching {
                        fallbackTag(pronunciationLanguages.invoke(song), allowOtherScripts = true) != null
                    }.getOrDefault(false)
                    if (!hasPronunciation) return
                    // The host shares its same-language guard with translation. Enter its native
                    // selection branch for pronunciation, while keeping translation unavailable.
                    current.skipTranslation = true
                    param.result = ""
                }
            })

            hook("setTranslation", object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val current = evaluation.get() ?: return
                    if (!current.skipTranslation || addressOf(param.thisObject) != current.songAddress) return
                    current.skipTranslation = false
                    param.result = false
                }
            })

            registration.activate()
            installed = true
            ModernXposedRuntime.log("Native lyrics pronunciation hooks installed (${profile.id})")
        }.onFailure { error ->
            registration.close()
            ModernXposedRuntime.log("Native lyrics pronunciation hooks unavailable (${profile.id})", error)
        }
    }

    private fun isCjk(language: String): Boolean = when (language) {
        "zh", "yue", "ja", "ko" -> true
        else -> false
    }

    private class Evaluation(val songAddress: Long, val uiLanguage: String) {
        var languageRead = false
        var skipTranslation = false
    }
}
