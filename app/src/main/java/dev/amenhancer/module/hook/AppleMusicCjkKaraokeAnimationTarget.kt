package dev.amenhancer.module.hook

import android.animation.Animator
import java.lang.reflect.Executable
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Map as JavaMap
import java.util.WeakHashMap

/**
 * Narrow Apple Music 6.5.2/1586 adapter for the native karaoke rush-gradient
 * path. The host owns all duration/length trigger conditions; AM++ only
 * allows its CJK classifier override for one unmerged native CJK word.
 */
internal class AppleMusicCjkKaraokeAnimationTarget(
    private val symbols: TargetSymbolResolver,
) : CjkKaraokeAnimationTarget {
    private val a0Depth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }
    private val a0SingleWordStack: ThreadLocal<MutableList<Boolean>> =
        ThreadLocal.withInitial { mutableListOf() }
    private val fieldCache = Collections.synchronizedMap(
        WeakHashMap<Class<*>, kotlin.collections.Map<String, Field>>(),
    )
    private val rewriteLogCount = AtomicInteger()
    private var specialEndHookInstalled = false

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
                    enterA0Scope(param)
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

        // The special path owns its own z$c end callback.  Observe that
        // callback instead of adding listeners to every Animator in e.p; the
        // latter changes Apple's ordering and was shown to disturb all lyric
        // animations on device.
        specialEndHookInstalled = installSpecialEndHook(a0)

        if (!a0Installed || !helperInstalled) {
            return TargetCapabilityInstall.Degraded(
                failures.joinToString("; ").ifBlank { "CJK karaoke animation hooks were not installed" },
            )
        }

        return TargetCapabilityInstall.Active(
            "Installed exact 6.5.2/1586 single-unmerged-CJK guard with z\$c end cleanup",
        )
    }

    private fun installSpecialEndHook(a0: Executable): Boolean = runCatching {
        val owner = a0.declaringClass
        val cleanupType = Class.forName(
            "${owner.name}\$c",
            false,
            owner.classLoader,
        )
        val method = cleanupType.declaredMethods
            .filter { candidate ->
                candidate.name == "onAnimationEnd" &&
                    candidate.parameterTypes.size == 1 &&
                    Animator::class.java.isAssignableFrom(candidate.parameterTypes[0])
            }
            .singleOrNull()
            ?: return@runCatching false
        specialEndHookInstalled = ModernXposedRuntime.hookMethod(
            method,
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cleanupSpecialCjkEnd(param.thisObject)
                }
            },
        )
        specialEndHookInstalled
    }.onFailure { error ->
        ModernXposedRuntime.log("CJK karaoke z\$c end hook unavailable", error)
    }.getOrDefault(false)

    private fun cleanupSpecialCjkEnd(listener: Any?) {
        if (!specialEndHookInstalled || listener == null) return
        runCatching {
            val entry = readNamedField(listener, "a") ?: return@runCatching
            val text = readNamedField(entry, "c") as? CharSequence ?: return@runCatching
            val background = readNamedField(entry, "h") as? Boolean ?: return@runCatching
            val nativeDuration = (readNamedField(entry, "e") as? Number)?.toInt()
                ?: return@runCatching
            val timing = CjkKaraokeWordTiming(
                text = text,
                nativeDurationMs = nativeDuration,
                cumulativeDurationMs = (readNamedField(entry, "f") as? Number)?.toInt()
                    ?: return@runCatching,
                cumulativeTextLength = (readNamedField(entry, "g") as? Number)?.toInt()
                    ?: return@runCatching,
                splitBindingCount = splitCount(readNamedField(entry, "k")),
                isBackground = background,
            )
            if (!isSingleUnmergedCjkWord(timing)) return@runCatching

            val expectedText = text.toString().trim()
            foregroundEntryViews(entry).forEach { view ->
                val currentText = invokeNoArg(view, "getText") as? CharSequence
                if (currentText == null || currentText.toString().trim() != expectedText) {
                    return@forEach
                }
                // z$c has already removed its outer AnimatorSet from e.p;
                // only clear the properties written by z$f/z$g.
                invokeMethod(view, "setScaleX", 1f)
                invokeMethod(view, "setScaleY", 1f)
                invokeNoArg(view, "resetPivot")
                invokeMethod(view, "setShadowLayer", 0f, 0f, 0f, 0)
                invokeNoArg(view, "invalidate")
            }
        }.onFailure { error ->
            ModernXposedRuntime.log("CJK karaoke z\$c end cleanup failed", error)
        }
    }

    private fun foregroundEntryViews(entry: Any): List<Any> {
        val bindings = mutableListOf<Any?>()
        readNamedField(entry, "i")?.let(bindings::add)
        when (val split = readNamedField(entry, "k")) {
            is Collection<*> -> split.forEach(bindings::add)
            else -> Unit
        }
        val views = mutableListOf<Any>()
        bindings.forEach { binding ->
            val view = binding?.let { readNamedField(it, "U") } ?: return@forEach
            if (views.none { it === view }) views += view
        }
        return views
    }

    private fun splitCount(value: Any?): Int = when (value) {
        null -> 0
        is Collection<*> -> value.size
        else -> -1
    }

    private fun rewriteCjkK0Result(param: ModernMethodHook.MethodHookParam) {
        if (!isSingleWordScope()) return

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

    private fun enterA0Scope(param: ModernMethodHook.MethodHookParam) {
        runCatching {
            a0Depth.set((a0Depth.get() ?: 0) + 1)
            a0Stack().add(readSingleWordGate(param))
        }
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
            val stack = a0Stack()
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) a0SingleWordStack.remove()
        }.onFailure { error -> ModernXposedRuntime.log("CJK karaoke a0 depth cleanup failed open", error) }
    }

    private fun isSingleWordScope(): Boolean = runCatching {
        (a0Depth.get() ?: 0) > 0 && a0Stack().lastOrNull() == true
    }
        .getOrElse { error ->
            ModernXposedRuntime.log("CJK karaoke single-word gate read failed open", error)
            false
        }

    private fun a0Stack(): MutableList<Boolean> =
        a0SingleWordStack.get() ?: mutableListOf<Boolean>().also(a0SingleWordStack::set)

    /** Reads only the host's grouping metadata; Apple retains all trigger gates. */
    private fun readSingleWordGate(param: ModernMethodHook.MethodHookParam): Boolean =
        runCatching {
            val holder = param.args.getOrNull(0) ?: return@runCatching false
            val wordId = (param.args.getOrNull(2) as? Number)?.toInt()
                ?: return@runCatching false
            val nativeDuration = (param.args.getOrNull(3) as? Number)?.toInt()
                ?: return@runCatching false
            val background = param.args.getOrNull(4) as? Boolean
                ?: return@runCatching false
            val mapName = if (background) "H" else "G"
            val map = cachedFields(holder.javaClass)[mapName]
                ?.let { field -> readField(field, holder) as? JavaMap<*, *> }
                ?: return@runCatching false
            val entry = map.get(Integer.valueOf(wordId)) ?: map.get(wordId)
                ?: return@runCatching false
            val text = cachedFields(entry.javaClass)["c"]
                ?.let { field -> readField(field, entry) as? CharSequence }
                ?: return@runCatching false
            val cumulativeDuration = cachedFields(entry.javaClass)["f"]
                ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
                ?: return@runCatching false
            val cumulativeLength = cachedFields(entry.javaClass)["g"]
                ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
                ?: return@runCatching false
            val splitValue = cachedFields(entry.javaClass)["k"]
                ?.let { field -> readField(field, entry) }
            val splitCount = when (splitValue) {
                null -> 0
                is Collection<*> -> splitValue.size
                else -> -1
            }
            isSingleUnmergedCjkWord(
                CjkKaraokeWordTiming(
                    text = text,
                    nativeDurationMs = nativeDuration,
                    cumulativeDurationMs = cumulativeDuration,
                    cumulativeTextLength = cumulativeLength,
                    splitBindingCount = splitCount,
                    isBackground = background,
                ),
            )
        }.getOrElse { error ->
            ModernXposedRuntime.log("CJK single-word gate failed closed: ${error.cjkShortMessage()}")
            false
        }

    private fun cachedFields(type: Class<*>): kotlin.collections.Map<String, Field> =
        synchronized(fieldCache) {
            fieldCache.get(type) ?: HashMap<String, Field>().also { fields ->
                generateSequence(type) { it.superclass }
                    .flatMap { current -> current.declaredFields.asSequence() }
                    .filterNot { field -> Modifier.isStatic(field.modifiers) }
                    .forEach { field ->
                        if (!fields.containsKey(field.name)) {
                            runCatching { field.isAccessible = true }
                            fields[field.name] = field
                        }
                    }
                fieldCache[type] = fields
            }
        }

    private fun readField(field: Field, receiver: Any): Any? = runCatching {
        field.get(receiver)
    }.getOrNull()

    private fun readNamedField(receiver: Any, name: String): Any? =
        cachedFields(receiver.javaClass)[name]?.let { field -> readField(field, receiver) }

    private fun invokeNoArg(receiver: Any, methodName: String): Any? =
        invokeMethod(receiver, methodName)

    private fun invokeMethod(receiver: Any, methodName: String, vararg args: Any?): Any? = runCatching {
        receiver.javaClass.methods
            .firstOrNull { method ->
                method.name == methodName && method.parameterTypes.size == args.size
            }
            ?.invoke(receiver, *args)
    }.getOrNull()

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
