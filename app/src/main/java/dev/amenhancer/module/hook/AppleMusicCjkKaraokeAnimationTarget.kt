package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Map as JavaMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val a0MetadataStack: ThreadLocal<MutableList<WordMetadata?>> =
        ThreadLocal.withInitial { mutableListOf() }
    private val fieldCache = Collections.synchronizedMap(
        WeakHashMap<Class<*>, kotlin.collections.Map<String, Field>>(),
    )
    private val rewriteLogCount = AtomicInteger()
    private val cleanupAnimators = Collections.synchronizedMap(
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
                    // Attach cleanup only to the exact CJK words admitted by
                    // the gate. English and merged CJK retain Apple's path.
                    try {
                        runCatching {
                            if (isSingleWordScope()) {
                                a0MetadataStack().lastOrNull()
                                    ?.let { metadata -> installCjkCleanup(readLiveAnimators(metadata)) }
                            }
                        }.onFailure { error ->
                            ModernXposedRuntime.log("CJK karaoke cleanup install failed open", error)
                        }
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
                        // helper result. This is deliberately fail-open.
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
        when {
            isK0Set(languageSet) && param.result == true -> {
                // CJK is normally classified into k0, which blocks the
                // rush branch. Make that one result look like a default
                // script only while a0 is running.
                param.result = false
                logRewrite(text, "k0 true -> false")
            }
            isJ0Set(languageSet) && containsHangul(text) && param.result != true -> {
                // Hangul belongs to k0 but not j0, so opt it into the same
                // Apple animation only inside a0; g0 remains untouched.
                param.result = true
                logRewrite(text, "j0 false -> true")
            }
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
        // z.k0 always contains HANGUL_SYLLABLES. This marker distinguishes
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
            val metadata = readWordMetadata(param)
            val gate = metadata != null && isSingleUnmergedWord(metadata)
            a0Stack().add(gate)
            a0MetadataStack().add(
                if (gate) metadata?.captureViewBaselines() else null,
            )
        }.onFailure { error ->
            // A reflection mismatch must never break the host callback. Keep
            // this nested scope fail-closed and balanced for the helper hook.
            a0Stack().add(false)
            a0MetadataStack().add(null)
            ModernXposedRuntime.log("CJK karaoke a0 scope enter failed closed", error)
        }
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
            val metadataStack = a0MetadataStack()
            if (metadataStack.isNotEmpty()) metadataStack.removeAt(metadataStack.lastIndex)
            if (stack.isEmpty()) {
                a0SingleWordStack.remove()
                a0MetadataStack.remove()
            }
        }.onFailure { error ->
            ModernXposedRuntime.log("CJK karaoke a0 depth cleanup failed open", error)
        }
    }

    private fun isSingleWordScope(): Boolean = runCatching {
        (a0Depth.get() ?: 0) > 0 && a0Stack().lastOrNull() == true
    }.getOrElse { error ->
        ModernXposedRuntime.log("CJK karaoke single-word gate read failed open", error)
        false
    }

    private fun a0Stack(): MutableList<Boolean> =
        a0SingleWordStack.get() ?: mutableListOf<Boolean>().also(a0SingleWordStack::set)

    private fun a0MetadataStack(): MutableList<WordMetadata?> =
        a0MetadataStack.get() ?: mutableListOf<WordMetadata?>().also(a0MetadataStack::set)

    /** Reads only the host's grouping metadata; Apple retains all trigger gates. */
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
        return WordMetadata(
            wordId = wordId,
            nativeDurationMs = nativeDuration,
            background = background,
            holder = holder,
            entry = entry,
            text = text,
            cumulativeDurationMs = cumulativeDuration,
            cumulativeTextLength = cumulativeLength,
            splitBindingCount = splitCount(splitValue),
            nativeAnimator = fields["o"]?.let { field -> readField(field, entry) },
            animatorList = fields["p"]?.let { field -> readField(field, entry) },
            foregroundBinding = fields["i"]?.let { field -> readField(field, entry) },
            backgroundBinding = fields["j"]?.let { field -> readField(field, entry) },
            splitBindings = splitValue,
        )
    }

    private fun readLiveAnimators(metadata: WordMetadata): WordMetadata {
        val fields = cachedFields(metadata.entry.javaClass)
        return metadata.copy(
            nativeAnimator = readNamedField(metadata.entry, "o"),
            animatorList = readNamedField(metadata.entry, "p"),
            foregroundBinding = fields["i"]?.let { field -> readField(field, metadata.entry) },
            backgroundBinding = fields["j"]?.let { field -> readField(field, metadata.entry) },
            splitBindings = fields["k"]?.let { field -> readField(field, metadata.entry) },
        )
    }

    private fun isSingleUnmergedWord(metadata: WordMetadata): Boolean =
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

    private fun installCjkCleanup(metadata: WordMetadata) {
        val candidates = mutableListOf<Animator>()
        (metadata.nativeAnimator as? Animator)?.let { candidates += it }
        animatorItems(metadata.animatorList).forEach { item ->
            val animator = item as? Animator ?: return@forEach
            if (candidates.none { it === animator }) candidates += animator
        }
        candidates.forEach { animator ->
            val shouldInstall = synchronized(cleanupAnimators) {
                cleanupAnimators.put(animator, true) == null
            }
            if (!shouldInstall) return@forEach
            runCatching {
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (cleanupAfterCjkAnimator(metadata, animation)) {
                            animation.removeListener(this)
                            synchronized(cleanupAnimators) { cleanupAnimators.remove(animation) }
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (cleanupAfterCjkAnimator(metadata, animation)) {
                            animation.removeListener(this)
                            synchronized(cleanupAnimators) { cleanupAnimators.remove(animation) }
                        }
                    }
                })
            }.onFailure { error ->
                ModernXposedRuntime.log("CJK karaoke cleanup listener failed", error)
            }
        }
    }

    private fun cleanupAfterCjkAnimator(metadata: WordMetadata, trigger: Animator): Boolean =
        runCatching {
            val liveList = readNamedField(metadata.entry, "p")
            val liveNative = readNamedField(metadata.entry, "o") as? Animator
            val active = mutableListOf<Animator>()
            liveNative?.let { active += it }
            animatorItems(liveList).forEach { item ->
                val animator = item as? Animator ?: return@forEach
                if (active.none { it === animator }) active += animator
            }
            // A child callback can arrive before Apple's outer AnimatorSet;
            // wait for that outer set while it is actually running.  A stale
            // custom spring may report isStarted=true forever after cancel,
            // so it must not block the final cleanup pass.
            // An AnimatorSet is Apple's outer word animation. Its end is the
            // authoritative boundary even if a custom spring remains in e.p
            // without dispatching onAnimationEnd.
            if (trigger !is AnimatorSet && active.any { it !== trigger && isAnimatorRunning(it) }) {
                return@runCatching false
            }

            active.filter { it !== trigger && isAnimatorStarted(it) }.forEach { animator ->
                runCatching { animator.cancel() }
            }
            removeAnimators(liveList, active)
            restoreViewBaselines(metadata, readLiveAnimators(metadata))
            true
        }.onFailure { error ->
            ModernXposedRuntime.log("CJK karaoke view cleanup failed", error)
        }.getOrDefault(false)

    private fun isAnimatorRunning(animator: Animator): Boolean = runCatching {
        animator.isRunning
    }.getOrDefault(false)

    private fun isAnimatorStarted(animator: Animator): Boolean = runCatching {
        animator.isRunning || animator.isStarted
    }.getOrDefault(false)

    private fun removeAnimators(container: Any?, animators: List<Animator>) {
        if (container == null) return
        if (container is MutableCollection<*>) {
            @Suppress("UNCHECKED_CAST")
            val mutable = container as MutableCollection<Any?>
            animators.forEach { animator -> runCatching { mutable.remove(animator) } }
        } else {
            // Keep the cleanup compatible with Apple's obfuscated F/b
            // collection even if a future runtime exposes it as read-only.
            animators.forEach { animator -> invokeMethod(container, "remove", animator) }
        }
    }

    private fun restoreViewBaselines(metadata: WordMetadata, liveMetadata: WordMetadata) {
        if (!isCurrentWordEntry(metadata)) return
        val liveViews = bindingViews(liveMetadata)
        metadata.viewBaselines.forEach { baseline ->
            if (liveViews.none { it === baseline.view }) return@forEach
            val currentText = invokeNoArg(baseline.view, "getText") as? CharSequence
            if (currentText == null || !containsCjkKaraokeScript(currentText)) return@forEach
            invokeMethod(baseline.view, "setTranslationX", baseline.translationX)
            invokeMethod(baseline.view, "setTranslationY", baseline.translationY)
            invokeMethod(baseline.view, "setScaleX", baseline.scaleX)
            invokeMethod(baseline.view, "setScaleY", baseline.scaleY)
            invokeNoArg(baseline.view, "resetPivot")
            restoreShadow(baseline.view, baseline.shadow)
            invokeNoArg(baseline.view, "invalidate")
        }
    }

    private fun isCurrentWordEntry(metadata: WordMetadata): Boolean = runCatching {
        val mapName = if (metadata.background) "H" else "G"
        val map = cachedFields(metadata.holder.javaClass)[mapName]
            ?.let { field -> readField(field, metadata.holder) as? JavaMap<*, *> }
            ?: return@runCatching false
        val current = map.get(Integer.valueOf(metadata.wordId)) ?: map.get(metadata.wordId)
        current === metadata.entry
    }.getOrDefault(false)

    private fun restoreShadow(view: Any, shadow: ShadowState) {
        if (shadow.radius == null || shadow.dx == null || shadow.dy == null || shadow.color == null) return
        invokeMethod(
            view,
            "setShadowLayer",
            shadow.radius,
            shadow.dx,
            shadow.dy,
            shadow.color,
        )
    }

    private fun WordMetadata.captureViewBaselines(): WordMetadata = copy(
        viewBaselines = bindingViews(this).mapNotNull { view -> captureViewBaseline(view) },
    )

    private fun bindingViews(metadata: WordMetadata): List<Any> {
        val bindings = mutableListOf<Any?>().apply {
            val primaryBinding = if (metadata.background) {
                metadata.backgroundBinding
            } else {
                metadata.foregroundBinding
            }
            primaryBinding?.let(::add)
            when (val split = metadata.splitBindings) {
                is Collection<*> -> split.forEach { add(it) }
                else -> Unit
            }
        }
        val views = mutableListOf<Any>()
        bindings.forEach { binding ->
            val view = binding?.let { readNamedField(it, "U") } ?: return@forEach
            if (views.none { it === view }) views += view
        }
        return views
    }

    private fun captureViewBaseline(view: Any): ViewBaseline? = runCatching {
        ViewBaseline(
            view = view,
            translationX = readFloat(invokeNoArg(view, "getTranslationX")) ?: 0f,
            translationY = readFloat(invokeNoArg(view, "getTranslationY")) ?: 0f,
            scaleX = readFloat(invokeNoArg(view, "getScaleX")) ?: 1f,
            scaleY = readFloat(invokeNoArg(view, "getScaleY")) ?: 1f,
            shadow = readShadow(view),
        )
    }.getOrNull()

    private fun readShadow(view: Any): ShadowState {
        val paint = invokeNoArg(view, "getPaint")
        return ShadowState(
            radius = readFloat(invokeNoArg(view, "getShadowRadius"))
                ?: readFloat(paint?.let { invokeNoArg(it, "getShadowLayerRadius") }),
            dx = readFloat(invokeNoArg(view, "getShadowDx"))
                ?: readFloat(paint?.let { invokeNoArg(it, "getShadowLayerDx") }),
            dy = readFloat(invokeNoArg(view, "getShadowDy"))
                ?: readFloat(paint?.let { invokeNoArg(it, "getShadowLayerDy") }),
            color = readInt(invokeNoArg(view, "getShadowColor"))
                ?: readInt(paint?.let { invokeNoArg(it, "getShadowLayerColor") }),
        )
    }

    private fun animatorItems(value: Any?): List<Any?> = runCatching {
        when (value) {
            is Collection<*> -> value.toList()
            null -> emptyList()
            else -> {
                val backing = readNamedField(value, "b")
                when {
                    backing is Collection<*> -> backing.toList()
                    backing?.javaClass?.isArray == true ->
                        (0 until ReflectArray.getLength(backing)).map { index ->
                            ReflectArray.get(backing, index)
                        }
                    else -> emptyList()
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun splitCount(value: Any?): Int = when (value) {
        null -> 0
        is Collection<*> -> value.size
        else -> -1
    }

    private fun readFloat(value: Any?): Float? = (value as? Number)?.toFloat()

    private fun readInt(value: Any?): Int? = (value as? Number)?.toInt()

    private fun invokeNoArg(receiver: Any, methodName: String): Any? =
        invokeMethod(receiver, methodName)

    private fun invokeMethod(receiver: Any, methodName: String, vararg args: Any?): Any? = runCatching {
        receiver.javaClass.methods
            .firstOrNull { method ->
                method.name == methodName && method.parameterTypes.size == args.size
            }
            ?.invoke(receiver, *args)
    }.getOrNull()

    private fun readNamedField(receiver: Any, name: String): Any? =
        cachedFields(receiver.javaClass)[name]?.let { field -> readField(field, receiver) }

    private fun cachedFields(type: Class<*>): kotlin.collections.Map<String, Field> =
        synchronized(fieldCache) {
            fieldCache[type] ?: HashMap<String, Field>().also { fields ->
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

    private data class ShadowState(
        val radius: Float?,
        val dx: Float?,
        val dy: Float?,
        val color: Int?,
    )

    private data class ViewBaseline(
        val view: Any,
        val translationX: Float,
        val translationY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val shadow: ShadowState,
    )

    private data class WordMetadata(
        val wordId: Int,
        val nativeDurationMs: Int,
        val background: Boolean,
        val holder: Any,
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
        val viewBaselines: List<ViewBaseline> = emptyList(),
    )

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
