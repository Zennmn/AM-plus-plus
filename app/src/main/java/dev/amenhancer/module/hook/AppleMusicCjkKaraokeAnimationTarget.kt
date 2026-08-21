package dev.amenhancer.module.hook

import java.util.concurrent.atomic.AtomicInteger

/**
 * Narrow Apple Music 6.5.2/1586 adapter for the native karaoke rush-gradient
 * path.  The host's `z.a0` call is the only scope in which the UnicodeBlock
 * helper result is adjusted; normal `z.g0` layout calls therefore retain their
 * original CJK behavior.
 */
internal class AppleMusicCjkKaraokeAnimationTarget(
    private val symbols: TargetSymbolResolver,
) : CjkKaraokeAnimationTarget {
    private val a0Depth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }
    private val rewriteLogCount = AtomicInteger()

    override fun install(): TargetCapabilityInstall {
        val a0Resolution = symbols.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod)
        val helperResolution = symbols.resolve(AppleMusicSymbols.CjkUnicodeBlockPredicateMethod)
        val a0 = a0Resolution.valueOrNull()
        val helper = helperResolution.valueOrNull()
        if (a0 == null || helper == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(a0Resolution, helperResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
        }

        val failures = mutableListOf<String>()
        val a0Installed = try {
            ModernXposedRuntime.hookMethod(a0, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    enterA0Scope()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // The host callback runs even when z.a0 throws; always
                    // release this thread's scope so a later g0 call cannot
                    // inherit a stale override.
                    leaveA0Scope()
                }
            }).also { installed ->
                if (!installed) failures += "z.a0 hook was rejected"
            }
        } catch (error: Throwable) {
            failures += "z.a0 hook failed: ${error.cjkShortMessage()}"
            ModernXposedRuntime.log("CJK karaoke z.a0 hook failed", error)
            false
        }

        val helperInstalled = try {
            ModernXposedRuntime.hookMethod(helper, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        rewriteCjkK0Result(param)
                    } catch (error: Throwable) {
                        // A malformed host call must never break its original
                        // helper result.  This is deliberately fail-open.
                        ModernXposedRuntime.log("CJK karaoke UnicodeBlock helper failed open", error)
                    }
                }
            }).also { installed ->
                if (!installed) failures += "I0\$a.a hook was rejected"
            }
        } catch (error: Throwable) {
            failures += "I0\$a.a hook failed: ${error.cjkShortMessage()}"
            ModernXposedRuntime.log("CJK karaoke I0\$a.a hook failed", error)
            false
        }

        if (!a0Installed || !helperInstalled) {
            return TargetCapabilityInstall.Degraded(
                failures.joinToString("; ").ifBlank { "CJK karaoke animation hooks were not installed" },
            )
        }

        return TargetCapabilityInstall.Active(
            "Installed exact Apple Music 6.5.2/1586 z.a0 + I0\$a.a CJK j0/k0 rush-gradient scope",
        )
    }

    private fun rewriteCjkK0Result(param: ModernMethodHook.MethodHookParam) {
        if (!isInA0Scope()) return

        val text = param.args.getOrNull(0) as? CharSequence ?: return
        if (!containsCjkKaraokeScript(text)) return

        val languageSet = param.args.getOrNull(1) as? Set<*> ?: return
        when {
            isK0Set(languageSet) && param.result == true -> {
                // CJK is normally classified into k0, which blocks the
                // rush branch.  Make that one result look like a default
                // script only while a0 is running.
                param.result = false
                logRewrite(text, "k0 true -> false")
            }
            isJ0Set(languageSet) && containsHangul(text) && param.result != true -> {
                // i0 receives the j0 hit as its split/rush eligibility bit.
                // Hangul belongs to k0 but not j0, so opt it into the same
                // Apple animation only inside a0; g0 remains untouched.
                param.result = true
                logRewrite(text, "j0 false -> true")
            }
            else -> return
        }
    }

    private fun logRewrite(text: CharSequence, change: String) {
        if (rewriteLogCount.getAndIncrement() < MAX_REWRITE_LOGS) {
            ModernXposedRuntime.log(
                "CJK karaoke a0 scope: I0\$a.a(${text.length} chars, $change); g0 unchanged",
            )
        }
    }

    private fun isK0Set(languageSet: Set<*>): Boolean = runCatching {
        // z.k0 always contains HANGUL_SYLLABLES.  This marker distinguishes
        // it from the host's j0/l0 sets without touching either set globally.
        languageSet.contains(Character.UnicodeBlock.HANGUL_SYLLABLES)
    }.getOrElse { error ->
        ModernXposedRuntime.log("CJK karaoke k0 marker check failed open", error)
        false
    }

    private fun isJ0Set(languageSet: Set<*>): Boolean = runCatching {
        languageSet.contains(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) &&
            languageSet.contains(Character.UnicodeBlock.HIRAGANA) &&
            languageSet.contains(Character.UnicodeBlock.KATAKANA) &&
            !languageSet.contains(Character.UnicodeBlock.HANGUL_SYLLABLES) &&
            !languageSet.contains(Character.UnicodeBlock.THAI)
    }.getOrElse { error ->
        ModernXposedRuntime.log("CJK karaoke j0 marker check failed open", error)
        false
    }

    private fun enterA0Scope() {
        runCatching { a0Depth.set((a0Depth.get() ?: 0) + 1) }
            .onFailure { error -> ModernXposedRuntime.log("CJK karaoke a0 depth enter failed open", error) }
    }

    private fun leaveA0Scope() {
        runCatching {
            val depth = a0Depth.get() ?: 0
            if (depth <= 1) {
                a0Depth.remove()
            } else {
                a0Depth.set(depth - 1)
            }
        }.onFailure { error -> ModernXposedRuntime.log("CJK karaoke a0 depth cleanup failed open", error) }
    }

    private fun isInA0Scope(): Boolean = runCatching { (a0Depth.get() ?: 0) > 0 }
        .getOrElse { error ->
            ModernXposedRuntime.log("CJK karaoke a0 depth read failed open", error)
            false
        }

    private companion object {
        const val MAX_REWRITE_LOGS = 3
    }
}

/** Returns true for the CJK blocks used by the host's karaoke classifier. */
internal fun containsCjkKaraokeScript(text: CharSequence): Boolean {
    for (index in text.indices) {
        when (Character.UnicodeBlock.of(text[index])) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> return true
        }
    }
    return false
}

private fun containsHangul(text: CharSequence): Boolean {
    for (index in text.indices) {
        when (Character.UnicodeBlock.of(text[index])) {
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> return true
            else -> Unit
        }
    }
    return false
}

private fun Throwable.cjkShortMessage(): String = buildString {
    append(javaClass.simpleName.ifBlank { javaClass.name })
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}
