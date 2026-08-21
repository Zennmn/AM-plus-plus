package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Array as ReflectArray
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
    private val traceEventCount = AtomicInteger()
    private val observedAnimators = Collections.synchronizedMap(
        WeakHashMap<Animator, Boolean>(),
    )

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
                    try {
                        traceA0Boundary("after", param, isSingleWordScope())
                    } finally {
                        leaveA0Scope()
                    }
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
            "Installed exact 6.5.2/1586 single-unmerged-CJK guard around z.a0 + I0\$a.a",
        )
    }

    private fun rewriteCjkK0Result(param: ModernMethodHook.MethodHookParam) {
        if (!isSingleWordScope()) return

        val text = param.args.getOrNull(0) as? CharSequence ?: return
        if (!containsCjkKaraokeScript(text)) return

        val languageSet = param.args.getOrNull(1) as? Set<*> ?: return
        val originalResult = param.result
        val setName = when {
            isK0Set(languageSet) -> "k0"
            isJ0Set(languageSet) -> "j0"
            else -> null
        }
        when {
            setName == "k0" && param.result == true -> {
                // CJK is normally classified into k0, which blocks the
                // rush branch.  Make that one result look like a default
                // script only while a0 is running.
                param.result = false
                logRewrite(text, "k0 true -> false")
            }
            setName == "j0" && containsHangul(text) && param.result != true -> {
                // i0 receives the j0 hit as its split/rush eligibility bit.
                // Hangul belongs to k0 but not j0, so opt it into the same
                // Apple animation only inside a0; g0 remains untouched.
                param.result = true
                logRewrite(text, "j0 false -> true")
            }
            else -> Unit
        }
        traceHelperResult(text, setName, originalResult, param.result)
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
            val gate = readSingleWordGate(param)
            a0Stack().add(gate)
            traceA0Boundary("before", param, gate)
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
            val metadata = readWordMetadata(param) ?: return@runCatching false
            isSingleUnmergedCjkWord(
                CjkKaraokeWordTiming(
                    text = metadata.text,
                    nativeDurationMs = metadata.nativeDurationMs,
                    cumulativeDurationMs = metadata.cumulativeDurationMs,
                    cumulativeTextLength = metadata.cumulativeTextLength,
                    splitBindingCount = metadata.splitBindingCount,
                    isBackground = metadata.background,
                ),
            )
        }.getOrElse { error ->
            ModernXposedRuntime.log("CJK single-word gate failed closed: ${error.cjkShortMessage()}")
            false
        }

    private fun readWordMetadata(param: ModernMethodHook.MethodHookParam): WordMetadata? {
        val holder = param.args.getOrNull(0) ?: return null
        val wordId = (param.args.getOrNull(2) as? Number)?.toInt() ?: return null
        val nativeDuration = (param.args.getOrNull(3) as? Number)?.toInt() ?: return null
        val background = param.args.getOrNull(4) as? Boolean ?: return null
        val mapName = if (background) "H" else "G"
        val map = cachedFields(holder.javaClass)[mapName]
            ?.let { field -> readField(field, holder) as? JavaMap<*, *> }
            ?: return null
        val entry = map.get(Integer.valueOf(wordId)) ?: map.get(wordId) ?: return null
        val fields = cachedFields(entry.javaClass)
        val text = fields["c"]?.let { field -> readField(field, entry) as? CharSequence }
            ?: return null
        val cumulativeDuration = fields["f"]
            ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
            ?: return null
        val cumulativeLength = fields["g"]
            ?.let { field -> (readField(field, entry) as? Number)?.toInt() }
            ?: return null
        val splitValue = fields["k"]?.let { field -> readField(field, entry) }
        val splitCount = when (splitValue) {
            null -> 0
            is Collection<*> -> splitValue.size
            else -> -1
        }
        return WordMetadata(
            wordId = wordId,
            nativeDurationMs = nativeDuration,
            background = background,
            entry = entry,
            text = text,
            cumulativeDurationMs = cumulativeDuration,
            cumulativeTextLength = cumulativeLength,
            splitBindingCount = splitCount,
            nativeAnimator = fields["o"]?.let { field -> readField(field, entry) },
            animatorList = fields["p"]?.let { field -> readField(field, entry) },
            foregroundBinding = fields["i"]?.let { field -> readField(field, entry) },
            backgroundBinding = fields["j"]?.let { field -> readField(field, entry) },
            splitBindings = splitValue,
        )
    }

    private fun traceA0Boundary(
        phase: String,
        param: ModernMethodHook.MethodHookParam,
        gate: Boolean,
    ) {
        runCatching {
            val metadata = readWordMetadata(param)
            if (metadata == null) {
                val wordId = (param.args.getOrNull(2) as? Number)?.toInt()
                if (wordId != null) {
                    trace(
                        "a0-$phase metadata-unavailable word=$wordId " +
                            "holder=${param.args.getOrNull(0)?.javaClass?.simpleName}",
                    )
                }
                return@runCatching
            }
            val isCjk = containsCjkKaraokeScript(metadata.text)
            val isNativeCandidate = !metadata.background &&
                metadata.cumulativeDurationMs >= NATIVE_CANDIDATE_DURATION_MS &&
                metadata.cumulativeTextLength <= NATIVE_CANDIDATE_LENGTH_MAX
            if (!isCjk && !isNativeCandidate) return@runCatching
            trace(
                "a0-$phase word=${metadata.wordId} bg=${metadata.background} " +
                    "gate=$gate text=${quoteTraceText(metadata.text)} " +
                    "native=${metadata.nativeDurationMs} f=${metadata.cumulativeDurationMs} " +
                    "g=${metadata.cumulativeTextLength} k=${metadata.splitBindingCount} " +
                    "entry=${metadata.entry.javaClass.simpleName} " +
                    "o=${describeAnimator(metadata.nativeAnimator)} " +
                    "p=${describeAnimatorContainer(metadata.animatorList)} " +
                    "bindings=${describeBindings(metadata)}",
            )
            if (phase == "after") observeAnimatorLifecycle(metadata)
        }.onFailure { error ->
            trace("a0-$phase trace failed: ${error.cjkShortMessage()}")
        }
    }

    private fun traceHelperResult(
        text: CharSequence,
        setName: String?,
        originalResult: Any?,
        finalResult: Any?,
    ) {
        if (!isSingleWordScope() || setName == null) return
        trace(
            "helper set=$setName text=${quoteTraceText(text)} " +
                "result=$originalResult->$finalResult depth=${a0Depth.get() ?: 0} " +
                "gate=${a0Stack().lastOrNull() == true}",
        )
    }

    private fun trace(message: String) {
        if (traceEventCount.getAndIncrement() < MAX_TRACE_EVENTS) {
            ModernXposedRuntime.log("[DEBUG-cjk-r2] $message")
        }
    }

    private fun describeAnimator(value: Any?): String {
        if (value == null) return "null"
        val type = value.javaClass.simpleName.ifBlank { value.javaClass.name }
        val started = invokeNoArg(value, "isStarted")
        val running = invokeNoArg(value, "isRunning")
        val duration = invokeNoArg(value, "getDuration")
        val playTime = invokeNoArg(value, "getCurrentPlayTime")
        val tag = readAnimatorTag(value)
        val listeners = describeAnimatorListeners(value)
        return "$type{started=$started,running=$running,duration=$duration,play=$playTime," +
            "tag=$tag,listeners=$listeners}"
    }

    private fun describeAnimatorListeners(value: Any): String = runCatching {
        val listeners = invokeNoArg(value, "getListeners") as? Collection<*> ?: return@runCatching "?"
        listeners.take(MAX_TRACE_ITEMS).joinToString(
            prefix = "[",
            postfix = "]",
        ) { listener -> listener?.javaClass?.simpleName ?: "null" }
    }.getOrDefault("?")

    private fun describeAnimatorContainer(value: Any?): String {
        if (value == null) return "null"
        return runCatching {
            val items: List<Any?> = when (value) {
                is Collection<*> -> value.take(MAX_TRACE_ITEMS)
                else -> {
                    val backing = readNamedField(value, "b")
                    when {
                        backing is Collection<*> -> backing.take(MAX_TRACE_ITEMS)
                        backing?.javaClass?.isArray == true -> (0 until minOf(
                            ReflectArray.getLength(backing),
                            MAX_TRACE_ITEMS,
                        )).map { index -> ReflectArray.get(backing, index) }
                        else -> emptyList()
                    }
                }
            }
            val count = readNamedField(value, "c") ?: items.size
            val details = items.joinToString(",") { item -> describeAnimator(item) }
            "${value.javaClass.simpleName}{count=$count,items=[$details]}"
        }.getOrElse { error -> "${value.javaClass.simpleName}{error=${error.cjkShortMessage()}}" }
    }

    private fun observeAnimatorLifecycle(metadata: WordMetadata) {
        val candidates = buildList {
            (metadata.nativeAnimator as? Animator)?.let(::add)
            animatorItems(metadata.animatorList).forEach { item ->
                (item as? Animator)?.let(::add)
            }
        }
        candidates.forEach { animator ->
            val shouldObserve = synchronized(observedAnimators) {
                observedAnimators.put(animator, true) == null
            }
            if (!shouldObserve) return@forEach
            runCatching {
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        traceAnimatorEnd(metadata, animation, "end")
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        traceAnimatorEnd(metadata, animation, "cancel")
                    }
                })
            }.onFailure { error ->
                trace(
                    "anim-observe-failed word=${metadata.wordId} " +
                        "anim=${animationType(animator)} error=${error.cjkShortMessage()}",
                )
            }
        }
    }

    private fun animatorItems(value: Any?): List<Any?> = runCatching {
        when (value) {
            is Collection<*> -> value.take(MAX_TRACE_ITEMS)
            null -> emptyList()
            else -> {
                val backing = readNamedField(value, "b")
                when {
                    backing is Collection<*> -> backing.take(MAX_TRACE_ITEMS)
                    backing?.javaClass?.isArray == true -> (0 until minOf(
                        ReflectArray.getLength(backing),
                        MAX_TRACE_ITEMS,
                    )).map { index -> ReflectArray.get(backing, index) }
                    else -> emptyList()
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun traceAnimatorEnd(metadata: WordMetadata, animation: Animator, phase: String) {
        runCatching {
            val live = readLiveMetadata(metadata)
            trace(
                "anim-$phase word=${metadata.wordId} text=${quoteTraceText(live.text)} " +
                    "anim=${describeAnimator(animation)} " +
                    "o=${describeAnimator(live.nativeAnimator)} " +
                    "p=${describeAnimatorContainer(live.animatorList)} " +
                    "bindings=${describeBindings(live)}",
            )
        }.onFailure { error ->
            trace(
                "anim-$phase word=${metadata.wordId} " +
                    "anim=${animationType(animation)} live-state-failed=${error.cjkShortMessage()}",
            )
        }
    }

    private fun readLiveMetadata(original: WordMetadata): WordMetadata {
        val fields = cachedFields(original.entry.javaClass)
        return original.copy(
            text = fields["c"]?.let { field -> readField(field, original.entry) as? CharSequence }
                ?: original.text,
            cumulativeDurationMs = fields["f"]
                ?.let { field -> (readField(field, original.entry) as? Number)?.toInt() }
                ?: original.cumulativeDurationMs,
            cumulativeTextLength = fields["g"]
                ?.let { field -> (readField(field, original.entry) as? Number)?.toInt() }
                ?: original.cumulativeTextLength,
            splitBindingCount = fields["k"]?.let { field -> splitCount(readField(field, original.entry)) }
                ?: original.splitBindingCount,
            nativeAnimator = fields["o"]?.let { field -> readField(field, original.entry) },
            animatorList = fields["p"]?.let { field -> readField(field, original.entry) },
            foregroundBinding = fields["i"]?.let { field -> readField(field, original.entry) },
            backgroundBinding = fields["j"]?.let { field -> readField(field, original.entry) },
            splitBindings = fields["k"]?.let { field -> readField(field, original.entry) },
        )
    }

    private fun splitCount(value: Any?): Int = when (value) {
        null -> 0
        is Collection<*> -> value.size
        else -> -1
    }

    private fun animationType(value: Any): String =
        value.javaClass.simpleName.ifBlank { value.javaClass.name }

    private fun describeBindings(metadata: WordMetadata): String {
        val bindings = mutableListOf<Any?>().apply {
            metadata.foregroundBinding?.let(::add)
            metadata.backgroundBinding?.let(::add)
            when (val split = metadata.splitBindings) {
                is Collection<*> -> split.take(MAX_TRACE_ITEMS).forEach { add(it) }
                else -> Unit
            }
        }
        if (bindings.isEmpty()) return "[]"
        return bindings.joinToString(prefix = "[", postfix = "]") { binding ->
            val view = binding?.let { readNamedField(it, "U") }
            "${binding?.javaClass?.simpleName ?: "null"}/${describeView(view)}"
        }
    }

    private fun describeView(value: Any?): String {
        if (value == null) return "view=null"
        val type = value.javaClass.simpleName.ifBlank { value.javaClass.name }
        val background = invokeNoArg(value, "getBackground")
        return "$type{alpha=${invokeNoArg(value, "getAlpha")},tx=${invokeNoArg(value, "getTranslationX")}," +
            "ty=${invokeNoArg(value, "getTranslationY")},sx=${invokeNoArg(value, "getScaleX")}," +
            "sy=${invokeNoArg(value, "getScaleY")},shadow=${describeShadow(value)}," +
            "paintShadow=${describePaintShadow(value)},bg=${describeDrawable(background)}}"
    }

    private fun describeShadow(value: Any): String =
        listOf(
            "r" to invokeNoArg(value, "getShadowRadius"),
            "dx" to invokeNoArg(value, "getShadowDx"),
            "dy" to invokeNoArg(value, "getShadowDy"),
            "c" to invokeNoArg(value, "getShadowColor"),
        ).joinToString(prefix = "{", postfix = "}") { (name, result) -> "$name=$result" }

    private fun describePaintShadow(value: Any): String {
        val paint = invokeNoArg(value, "getPaint") ?: return "null"
        return listOf(
            "r" to invokeNoArg(paint, "getShadowLayerRadius"),
            "dx" to invokeNoArg(paint, "getShadowLayerDx"),
            "dy" to invokeNoArg(paint, "getShadowLayerDy"),
            "c" to invokeNoArg(paint, "getShadowLayerColor"),
        ).joinToString(prefix = "{", postfix = "}") { (name, result) -> "$name=$result" }
    }

    private fun describeDrawable(value: Any?): String {
        if (value == null) return "null"
        return "${value.javaClass.simpleName}{alpha=${invokeNoArg(value, "getAlpha")}}"
    }

    private fun readAnimatorTag(value: Any?): Any? = value?.let { invokeNoArg(it, "getTag") }

    private fun invokeNoArg(receiver: Any, methodName: String): Any? = runCatching {
        receiver.javaClass.methods
            .firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
            ?.invoke(receiver)
    }.getOrNull()

    private fun readNamedField(receiver: Any, name: String): Any? =
        cachedFields(receiver.javaClass)[name]?.let { field -> readField(field, receiver) }

    private fun quoteTraceText(text: CharSequence): String =
        "\"${text.toString()
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
            .take(MAX_TRACE_TEXT)}\""

    private data class WordMetadata(
        val wordId: Int,
        val nativeDurationMs: Int,
        val background: Boolean,
        val entry: Any,
        val text: CharSequence,
        val cumulativeDurationMs: Int,
        val cumulativeTextLength: Int,
        val splitBindingCount: Int,
        val nativeAnimator: Any?,
        val animatorList: Any?,
        val foregroundBinding: Any?,
        val backgroundBinding: Any?,
        val splitBindings: Any?,
    )

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

    private companion object {
        const val MAX_REWRITE_LOGS = 3
        const val MAX_TRACE_EVENTS = 180
        const val MAX_TRACE_ITEMS = 6
        const val MAX_TRACE_TEXT = 32
        const val NATIVE_CANDIDATE_DURATION_MS = 1_000
        const val NATIVE_CANDIDATE_LENGTH_MAX = 7
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
