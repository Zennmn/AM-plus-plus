package dev.amenhancer.module.hook

import android.animation.Animator
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Map as JavaMap
import java.util.WeakHashMap

/**
 * Apple Music 6.5.2 adapter for AM++'s own per-word karaoke animation.
 *
 * The old experiment changed Apple's Unicode-block predicate while `z.a0`
 * was running. That made the host's private animation state machine decide
 * whether CJK should be animated and was consequently hard to cancel when a
 * lyric row was recycled. This target still uses the verified `z.a0` entry
 * point, but only as a data seam: its arguments identify the current
 * `wordId`, duration and background flag. The holder's exact word map then
 * leads to the generated binding's `CustomTextView`, where AM++ starts and
 * owns a `ValueAnimator`. For CJK foreground words the host's `e.o` animator
 * is cancelled before/after the call so it cannot fight the AM++ properties.
 */
internal class AppleMusicCjkKaraokeAnimationTarget(
    private val symbols: TargetSymbolResolver,
) : CjkKaraokeAnimationTarget {
    private val valueAnimator = CjkLyricValueAnimator()
    private val fieldCache = Collections.synchronizedMap(
        WeakHashMap<Class<*>, Map<String, Field>>(),
    )
    private val rootMethodCache = Collections.synchronizedMap(
        WeakHashMap<Class<*>, Method?>(),
    )

    override fun install(): TargetCapabilityInstall {
        val a0Resolution = symbols.resolve(AppleMusicSymbols.CjkKaraokeAnimationMethod)
        val a0 = a0Resolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(a0Resolution.summary)

        val failures = mutableListOf<String>()
        val a0Installed = try {
            ModernXposedRuntime.hookMethod(a0, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { suppressNativeWordAnimator(param) }
                        .onFailure { error ->
                            Log.w(TAG, "CJK native animator suppression failed open", error)
                        }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { animateCurrentWord(param) }
                        .onFailure { error ->
                            // A host binding change must never break Apple's
                            // original callback or lyric layout.
                            Log.w(TAG, "CJK word View animation failed open", error)
                        }
                }
            }).also { installed ->
                if (!installed) failures += "z.a0 hook was rejected"
            }
        } catch (error: Throwable) {
            failures += "z.a0 hook failed: ${error.cjkShortMessage()}"
            Log.w(TAG, "CJK word View animation hook failed", error)
            false
        }

        // g0 removes and rebuilds the flexbox children. Clearing here avoids
        // a detached/recycled CustomTextView retaining an in-flight animator
        // if the platform keeps the object in its pool for a moment.
        installWordLayoutRecycleHook(a0.declaringClass, failures)
        installHolderRecycleHook(a0.declaringClass, a0.parameterTypes.firstOrNull(), failures)
        installFragmentDestroyHook(failures)

        if (!a0Installed) {
            return TargetCapabilityInstall.Degraded(
                failures.joinToString("; ").ifBlank { "CJK ValueAnimator hook was not installed" },
            )
        }

        return TargetCapabilityInstall.Active(
            "Installed AM++ per-word ValueAnimator on exact 6.5.2/1586 z.a0 binding View",
        )
    }

    private fun suppressNativeWordAnimator(param: ModernMethodHook.MethodHookParam) {
        val background = param.args.getOrNull(4) as? Boolean ?: return
        if (background) return
        val holder = param.args.getOrNull(0) ?: return
        val wordId = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
        val entry = findWordEntry(holder, wordId, background = false) ?: return
        val wordText = findWordText(holder, wordId, background = false)
        if (wordText?.let(::containsCjkKaraokeScript) != true) return
        cancelNativeWordAnimator(entry)
    }

    private fun animateCurrentWord(param: ModernMethodHook.MethodHookParam) {
        // z.a0(z$a holder, int lineId, int wordId, int duration, boolean bg)
        val background = param.args.getOrNull(4) as? Boolean ?: return
        val holder = param.args.getOrNull(0) ?: return
        val wordId = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
        val duration = (param.args.getOrNull(3) as? Number)?.toLong() ?: return

        // Background vocals have a separate binding map and are intentionally
        // left to the host. The foreground map is z$a.G in this exact build.
        if (background) return
        val entry = findWordEntry(holder, wordId, background = false) ?: return
        val seenTextViews = IdentityHashMap<TextView, Boolean>()
        val textViews = findWordBindings(holder, wordId, background = false)
            .flatMap(::findBindingTextViews)
            .filter { view -> seenTextViews.put(view, true) == null }
        if (textViews.isEmpty()) return

        // The generated binding may apply its TextView text asynchronously.
        // e.c is the native wordText already consumed by z.a0, so use it as
        // the primary script signal and only fall back to the visible View.
        val hostWordText = findWordText(holder, wordId, background = false)
        val cjkViews = textViews.filter { textView ->
            runCatching { textView.text }.getOrNull()?.let(::containsCjkKaraokeScript) == true
        }
        if (hostWordText?.let(::containsCjkKaraokeScript) == true || cjkViews.isNotEmpty()) {
            // The host may have created e.o during this invocation. Cancel it
            // again after the original method and leave View properties to
            // the AM++ animator below.
            cancelNativeWordAnimator(entry)
        }
        if (hostWordText?.let(::containsCjkKaraokeScript) != true && cjkViews.isEmpty()) {
            // A recycled word View can be rebound to Latin text before the
            // next CJK callback; restore it immediately in that case.
            textViews.forEach(valueAnimator::clear)
            return
        }

        // a0 is already called once per native word event. The duration is
        // the word's own LyricsTiming duration, so long CJK tail words get a
        // correspondingly longer AM++ animation without guessing line timing.
        val targets = cjkViews.ifEmpty { textViews }
        targets.forEach { view ->
            // Native z$f/z$h may leave a cancelled lift at a non-zero
            // translation/scale. Normalize only those properties before AM++
            // captures its baseline; alpha is left untouched for host row
            // dimming semantics.
            runCatching {
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            }
        }
        valueAnimator.animateViews(
            // The generated binding's root is the first word View. The
            // explicit target list also covers z.m0's e.k fallback when
            // Apple split one native word into several character bindings.
            root = targets.first(),
            targets = targets,
            durationMs = duration,
            wordIndex = 0,
            wordCount = targets.size,
        )
    }

    private fun cancelNativeWordAnimator(entry: Any) {
        val field = cachedFields(entry.javaClass)["o"] ?: return
        val animator = readField(field, entry)
        if (animator is Animator) runCatching { animator.cancel() }
        runCatching {
            field.isAccessible = true
            field.set(entry, null)
        }
        // The host also keeps child word-lift animators in e.p. Its verified
        // e.a() cleanup only cancels entries tagged KARAOKE_WORD_LIFT_TAG,
        // so it cannot touch the AM++ ValueAnimator (which is not in e.p).
        runCatching {
            entry.javaClass.methods.firstOrNull { method ->
                method.name == "a" && method.parameterCount == 0
            }?.invoke(entry)
        }
    }

    private fun installWordLayoutRecycleHook(owner: Class<*>, failures: MutableList<String>) {
        val g0 = findWordLayoutMethod(owner) ?: return
        try {
            ModernXposedRuntime.hookMethod(g0, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // g0 rebuilds one FullWidthAlphaGradientFlexboxLayout at
                    // a time. Clear only that layout's current children so a
                    // neighboring visible line keeps its own animation.
                    (param.args.getOrNull(2) as? View)?.let(::clearViewTree)
                }
            })
        } catch (error: Throwable) {
            // This is an optional safety hook. z.a0 remains useful even if
            // an OEM build changes the layout method's return type.
            failures += "z.g0 recycle hook failed: ${error.cjkShortMessage()}"
            Log.w(TAG, "CJK z.g0 recycle hook failed open", error)
        }
    }

    private fun clearViewTree(view: View) {
        valueAnimator.clear(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { view.getChildAt(index) }.getOrNull()?.let(::clearViewTree)
            }
        }
    }

    private fun installFragmentDestroyHook(failures: MutableList<String>) {
        val fragment = symbols.resolve(AppleMusicSymbols.LyricsFragment).valueOrNull() ?: return
        try {
            val declaringClass = generateSequence(fragment) { it.superclass }
                .firstOrNull { type ->
                    type.declaredMethods.any { method ->
                        method.name == "onDestroyView" && method.parameterCount == 0
                    }
                } ?: return
            ModernXposedRuntime.hookAllMethods(
                declaringClass,
                "onDestroyView",
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        valueAnimator.clearAll()
                    }
                },
            )
        } catch (error: Throwable) {
            failures += "lyrics onDestroyView cleanup failed: ${error.cjkShortMessage()}"
            Log.w(TAG, "CJK lyrics lifecycle cleanup failed open", error)
        }
    }

    /**
     * z.p(RecyclerView$D,int) is the RecyclerView rebind seam in the verified
     * build; its runtime holder is cast to z$a. It can recycle an attached
     * word View without calling g0 first, so only that holder is cleared here.
     */
    private fun installHolderRecycleHook(
        owner: Class<*>,
        holderClass: Class<*>?,
        failures: MutableList<String>,
    ) {
        if (holderClass == null) return
        val recycle = owner.declaredMethods.firstOrNull { method ->
            val holderParameter = method.parameterTypes.firstOrNull()
            method.name == "p" &&
                method.parameterTypes.size == 2 &&
                holderParameter != null &&
                (
                    holderParameter == holderClass ||
                        holderParameter.name.contains("RecyclerView\$D") ||
                        holderParameter.isAssignableFrom(holderClass)
                    ) &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return
        try {
            ModernXposedRuntime.hookMethod(recycle, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.getOrNull(0)?.let(::clearHolderAnimations)
                }
            })
        } catch (error: Throwable) {
            failures += "z.p recycle hook failed: ${error.cjkShortMessage()}"
            Log.w(TAG, "CJK z.p recycle hook failed open", error)
        }
    }

    private fun clearHolderAnimations(holder: Any) {
        cachedFields(holder.javaClass).values
            .mapNotNull { field -> readField(field, holder) }
            .filterIsInstance<JavaMap<*, *>>()
            .flatMap { map -> map.values().asSequence() }
            .filterNotNull()
            .flatMap { entry ->
                listOf(false, true).asSequence()
                    .flatMap { background -> findEntryBindings(entry, background).asSequence() }
            }
            .flatMap(::findBindingTextViews)
            .forEach(valueAnimator::clear)
    }

    private fun findWordLayoutMethod(owner: Class<*>): Method? = owner.declaredMethods
        .firstOrNull { method ->
            method.name == "g0" &&
                method.parameterTypes.size == 7 &&
                method.parameterTypes[0].name.endsWith("LyricsWordVector") &&
                method.parameterTypes[2].name.contains("FullWidthAlphaGradientFlexboxLayout")
        }
        ?.apply { isAccessible = true }

    private fun findWordBindings(holder: Any, wordId: Int, background: Boolean): List<Any> {
        val entry = findWordEntry(holder, wordId, background) ?: return emptyList()
        return findEntryBindings(entry, background)
    }

    private fun findWordText(holder: Any, wordId: Int, background: Boolean): CharSequence? =
        findWordEntry(holder, wordId, background)
            ?.let { entry ->
                cachedFields(entry.javaClass)["c"]
                    ?.let { field -> readField(field, entry) as? CharSequence }
            }

    private fun findWordEntry(holder: Any, wordId: Int, background: Boolean): Any? {
        val preferredMapName = if (background) "H" else "G"
        // z$a also owns I/J pronunciation maps. Falling back to an arbitrary
        // Map can therefore animate the wrong TextView when the same wordId
        // exists in more than one lyric track; G/H are the exact foreground
        // and background maps selected by z.a0.
        val map = cachedFields(holder.javaClass)[preferredMapName]
            ?.let { field -> readField(field, holder) as? JavaMap<*, *> }
            ?: return null
        val entry = runCatching { map[Integer.valueOf(wordId)] }.getOrNull()
            ?: runCatching { map[wordId] }.getOrNull()
        return entry
    }

    private fun findEntryBindings(entry: Any, background: Boolean): List<Any> {
        val preferredBindingName = if (background) "j" else "i"
        val directBinding = cachedFields(entry.javaClass)[preferredBindingName]
            ?.let { field -> readField(field, entry) }
            ?.takeIf(::looksLikeViewBinding)
        if (directBinding != null) return listOf(directBinding)

        // z.m0 falls back to e.k when the host split one native word into a
        // character/whitespace binding list. This is the common CJK path.
        val splitBindings = (
            cachedFields(entry.javaClass)["k"]
                ?.let { field -> readField(field, entry) as? Iterable<*> }
                ?: emptyList<Any?>()
            )
            .filterNotNull()
            .filter(::looksLikeViewBinding)
        return splitBindings
    }

    private fun findBindingTextViews(binding: Any): List<TextView> {
        if (binding is TextView) return listOf(binding)

        // Generated lyric bindings in 6.5.2 expose their word CustomTextView
        // as field U. We intentionally use the type, not only the obfuscated
        // name, so a generated binding rename remains fail-open.
        cachedFields(binding.javaClass).values.forEach { field ->
            val value = readField(field, binding)
            if (value is TextView) return listOf(value)
        }

        // A future generated binding may only expose ViewDataBinding#getRoot.
        val root = runCatching {
            val rootMethod = synchronized(rootMethodCache) {
                if (rootMethodCache.containsKey(binding.javaClass)) {
                    rootMethodCache[binding.javaClass]
                } else {
                    binding.javaClass.methods.firstOrNull { method ->
                        method.name == "getRoot" && method.parameterCount == 0
                    }?.apply { isAccessible = true }
                        .also { method -> rootMethodCache[binding.javaClass] = method }
                }
            }
            rootMethod?.invoke(binding)
        }.getOrNull()
        return (root as? TextView)?.let(::listOf).orEmpty()
    }

    private fun looksLikeViewBinding(value: Any): Boolean =
        value.javaClass.name.contains("ViewDataBinding") ||
            cachedFields(value.javaClass).values.any { field ->
                TextView::class.java.isAssignableFrom(field.type)
            }

    private fun cachedFields(type: Class<*>): Map<String, Field> = synchronized(fieldCache) {
        fieldCache[type] ?: buildMap {
            generateSequence(type) { it.superclass }
                .flatMap { current -> current.declaredFields.asSequence() }
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .forEach { field ->
                    if (!containsKey(field.name)) {
                        runCatching { field.isAccessible = true }
                        put(field.name, field)
                    }
                }
        }.also { fields -> fieldCache[type] = fields }
    }

    private fun readField(field: Field, receiver: Any): Any? = runCatching {
        field.get(receiver)
    }.getOrNull()

    private companion object {
        const val TAG = "AMPP-CJK-KARAOKE"
    }
}

/** Returns true for the CJK blocks used by the host's karaoke classifier. */
internal fun containsCjkKaraokeScript(text: CharSequence): Boolean =
    defaultCjkLyricScriptPredicate(text)

private fun Throwable.cjkShortMessage(): String = buildString {
    append(javaClass.simpleName.ifBlank { javaClass.name })
    message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(180)) }
}
