package dev.amenhancer.module.hook

import android.util.Log
import android.view.View
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val LYRICS_PROCESS_EVENTS_MARKER = "\$processEvents"

/**
 * Returns the stable owner prefix shared by Apple's line/word callback lambdas.
 * The local callback name is compiler-generated and may change between builds.
 */
internal fun deriveLyricWordCallbackPrefix(ownerName: String): String? {
    val markerStart = ownerName.indexOf(LYRICS_PROCESS_EVENTS_MARKER)
    if (markerStart < 0) return null
    return ownerName.substring(0, markerStart + LYRICS_PROCESS_EVENTS_MARKER.length)
}

/** Apple Music symbol and hook adapter for the target-independent lyric blur runtime. */
internal class AppleMusicBidirectionalLyricBlurTarget(
    private val symbols: TargetSymbolResolver,
) : BidirectionalLyricBlurTarget {
    override fun install(): TargetCapabilityInstall {
        val recyclerResolution = symbols.resolve(AppleMusicSymbols.RecyclerView)
        val fragmentResolution = symbols.resolve(AppleMusicSymbols.LyricsFragment)
        val recyclerClass = recyclerResolution.valueOrNull()
        val fragmentClass = fragmentResolution.valueOrNull()
        if (recyclerClass == null || fragmentClass == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(recyclerResolution, fragmentResolution)
                    .filterNot { it is TargetResolution.Found<*> }
                    .joinToString { it.summary },
            )
        }

        val vectorResolution = symbols.resolve(AppleMusicSymbols.LyricsLineVector)
        val sessionResolution = symbols.resolve(AppleMusicSymbols.LyricsSessionProcessor)
        val callbackResolution = symbols.resolve(AppleMusicSymbols.LyricsHighlightCallback)
        val notifyWordHighlightResolution = symbols.resolve(
            AppleMusicSymbols.LyricsViewModelNotifyWordHighlight,
        )
        val setCurrentHighlightedLineResolution = symbols.resolve(
            AppleMusicSymbols.LyricsViewModelSetCurrentHighlightedLine,
        )
        val callback = callbackResolution.valueOrNull()
        val notifyWordHighlight = notifyWordHighlightResolution.valueOrNull()
        val setCurrentHighlightedLine = setCurrentHighlightedLineResolution.valueOrNull()
        if (callback == null && notifyWordHighlight == null && setCurrentHighlightedLine == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(
                    callbackResolution,
                    notifyWordHighlightResolution,
                    setCurrentHighlightedLineResolution,
                ).joinToString { it.summary },
            )
        }

        val targetAccess = AppleMusicLyricBlurTargetAccess(recyclerClass)
        val probe = LyricHighlightProbe()
        val runtime = OpenSourceLyricBlurPort(
            targetAccess = targetAccess,
            blurRadiusOffsetPx = TargetConfigClient.currentSettings().lyricBlurRadiusOffsetPx,
            probe = probe,
        )
        val highlights = LyricHighlightEventRouter(runtime, probe)

        // Preserve the upstream installation order: recycler, session, callback, lifecycle, VM.
        targetAccess.initializeAdapterPositionAccessor()
        hookSessionProcessor(
            method = sessionResolution.valueOrNull(),
            runtime = runtime,
            probe = probe,
        )
        hookHighlightCallback(
            method = callback,
            vectorClass = vectorResolution.valueOrNull(),
            highlights = highlights,
            probe = probe,
            runtime = runtime,
        )
        hookLyricsFragment(fragmentClass, runtime)
        hookViewModel(notifyWordHighlight, setCurrentHighlightedLine, highlights)

        val optionalFailures = listOf(
            vectorResolution,
            sessionResolution,
            callbackResolution,
            notifyWordHighlightResolution,
            setCurrentHighlightedLineResolution,
        ).filterNot { it is TargetResolution.Found<*> }
        return if (optionalFailures.isEmpty()) {
            TargetCapabilityInstall.Active(
                "a23bc/amlyricblur core installed; ${fragmentResolution.summary}; ${callbackResolution.summary}",
            )
        } else {
            TargetCapabilityInstall.Degraded(
                "Lyric blur installed with fallback hooks; " +
                    optionalFailures.joinToString { it.summary },
            )
        }
    }

    private fun hookSessionProcessor(
        method: Method?,
        runtime: LyricBlurRuntime,
        probe: LyricHighlightProbe,
    ) {
        if (method == null) {
            Log.w(TAG, "Lyric session processor symbol was unavailable")
            return
        }
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    probe.recordSession(
                        token = param.args.firstOrNull(),
                        processPosition = (param.args.getOrNull(1) as? Number)?.toLong(),
                    )
                    param.args.firstOrNull()?.let(runtime::onSessionChanged)
                }
            })
            Log.i(TAG, "Lyric session hook installed on ${method.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "Lyric session hook failed", t)
        }
    }

    private fun hookHighlightCallback(
        method: Method?,
        vectorClass: Class<*>?,
        highlights: LyricHighlightEventRouter,
        probe: LyricHighlightProbe,
        runtime: LyricBlurRuntime,
    ) {
        if (method == null || vectorClass == null) {
            Log.w(TAG, "Highlight callback symbols were unavailable")
            return
        }
        Log.i(TAG, "FOUND: ${method.declaringClass.name}.${method.name}")
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vector = param.args.firstOrNull { argument ->
                            argument != null && vectorClass.isInstance(argument)
                        } ?: return
                        val rawLineIds = readLineIds(vectorClass, vector)
                        val nativeFirst = (param.args.getOrNull(0) as? Number)?.toLong()
                        highlights.onCallback(
                            nativeFirst = nativeFirst,
                            lineIds = rawLineIds,
                            nativeLast = (param.args.getOrNull(2) as? Number)?.toLong(),
                            rawLineIds = rawLineIds,
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Highlight hook error", t)
                    }
                }
            })
            hookWordHighlightCallbacks(method, probe, runtime)
            highlights.onCallbackInstalled()
            Log.i(TAG, "Highlight hook installed on ${method.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "installHighlightHook failed", t)
        }
    }

    /**
     * Apple keeps line and word transition callbacks as four sibling
     * Kotlin/JNI owner classes.  The line callback alone cannot tell whether
     * the previous row is still receiving word progress, so the four word
     * vectors are kept in a separate blur-state stream instead of replacing
     * the existing line session.
     */
    private fun hookWordHighlightCallbacks(
        lineMethod: Method,
        probe: LyricHighlightProbe,
        runtime: LyricBlurRuntime,
    ) {
        val owner = lineMethod.declaringClass
        val prefix = deriveLyricWordCallbackPrefix(owner.name) ?: run {
            Log.w(TAG, "Word callback owner prefix unavailable: ${owner.name}")
            return
        }
        val candidates = listOf(
            "\$wordEventCallback\$1" to LyricHighlightProbe.Source.WORD,
            "\$bgWordEventCallback\$1" to LyricHighlightProbe.Source.BG_WORD,
            "\$prWordEventCallback\$1" to LyricHighlightProbe.Source.PR_WORD,
            "\$prBgWordEventCallback\$1" to LyricHighlightProbe.Source.PR_BG_WORD,
        )
        candidates.forEach { (suffix, source) ->
            val callbackOwner = runCatching {
                Class.forName(prefix + suffix, false, owner.classLoader)
            }.getOrNull() ?: return@forEach
            callbackOwner.declaredMethods
                .filter { method -> isWordHighlightCallback(method) }
                .forEach { wordMethod ->
                    runCatching {
                        ModernXposedRuntime.hookMethod(wordMethod, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val vector = param.args.getOrNull(1) ?: return@runCatching
                                    val wordKeys = readWordKeys(wordMethod.parameterTypes[1], vector)
                                        ?: return@runCatching
                                    probe.recordWord(
                                        source = source,
                                        firstNative = (param.args.getOrNull(0) as? Number)?.toLong(),
                                        wordKeys = wordKeys,
                                        lastNative = (param.args.getOrNull(2) as? Number)?.toLong(),
                                    )
                                    runtime.onWordHighlightsChanged(
                                        source = source,
                                        lineIds = wordKeys.mapNotNull { key ->
                                            key.substringBefore(':').toIntOrNull()
                                        }.toSet(),
                                    )
                                }
                            }
                        })
                    }.onFailure { error ->
                        Log.w(TAG, "Word callback hook failed on ${wordMethod.declaringClass.name}", error)
                    }
                }
        }
    }

    private fun isWordHighlightCallback(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "call" &&
            method.returnType == Void.TYPE &&
            types.size == 3 &&
            types[0] == Long::class.javaPrimitiveType &&
            types[1].name.endsWith(".javanative.model.LyricsWordVector") &&
            types[2] == Long::class.javaPrimitiveType
    }

    private fun readWordKeys(vectorClass: Class<*>, vector: Any): Set<String>? = runCatching {
        val size = (vectorClass.getMethod("size").invoke(vector) as Number).toInt()
        val get = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
        buildSet {
            for (index in 0 until size) {
                runCatching {
                    val pointer = get.invoke(vector, index.toLong()) ?: return@runCatching
                    val word = pointer.javaClass.getMethod("get").invoke(pointer) ?: return@runCatching
                    val wordId = (word.javaClass.getMethod("getWordId").invoke(word) as Number).toInt()
                    val linePointer = word.javaClass.getMethod("getLyricsLine").invoke(word)
                        ?: return@runCatching
                    val line = linePointer.javaClass.getMethod("get").invoke(linePointer)
                        ?: return@runCatching
                    val lineId = (line.javaClass.getMethod("getLineId").invoke(line) as Number).toInt()
                    add("$lineId:$wordId")
                }
            }
        }
    }.getOrNull()

    private fun readLineIds(vectorClass: Class<*>, vector: Any): Set<Int> {
        val size = (vectorClass.getMethod("size").invoke(vector) as Long).toInt()
        val get = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
        return buildSet {
            for (index in 0 until size) {
                try {
                    val pointer = get.invoke(vector, index.toLong()) ?: continue
                    val nativeObject = pointer.javaClass.getMethod("get").invoke(pointer) ?: continue
                    val lineId = (
                        nativeObject.javaClass.getMethod("getLineId").invoke(nativeObject) as Number
                    ).toInt()
                    add(lineId)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun hookLyricsFragment(fragmentClass: Class<*>, runtime: LyricBlurRuntime) {
        try {
            ModernXposedRuntime.hookAllMethods(fragmentClass, "onCreateView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.result as? View ?: return
                    val owner = param.thisObject ?: return
                    runtime.onLyricsViewCreated(owner, root)
                    Log.i(TAG, "onCreateView hooked")
                }
            })
            val destroyDeclaringClass = findLifecycleDeclaringClass(fragmentClass, "onDestroyView")
                ?: error("onDestroyView declaration was unavailable")
            ModernXposedRuntime.hookAllMethods(
                destroyDeclaringClass,
                "onDestroyView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val owner = param.thisObject?.takeIf(fragmentClass::isInstance) ?: return
                        runtime.onLyricsViewDestroyed(owner)
                    }
                },
            )
            Log.i(TAG, "Fragment lifecycle hooks installed")
        } catch (t: Throwable) {
            Log.w(TAG, "Fragment hook failed: ${t.message}")
        }
    }

    private fun findLifecycleDeclaringClass(start: Class<*>, methodName: String): Class<*>? =
        generateSequence(start) { type -> type.superclass }
            .firstOrNull { type ->
                type.declaredMethods.any { method ->
                    method.name == methodName && method.parameterCount == 0
                }
            }

    private fun hookViewModel(
        notifyWordHighlight: Method?,
        setCurrentHighlightedLine: Method?,
        highlights: LyricHighlightEventRouter,
    ) {
        if (notifyWordHighlight == null && setCurrentHighlightedLine == null) {
            Log.w(TAG, "VM method symbols were unavailable")
            return
        }
        try {
            if (notifyWordHighlight != null) {
                ModernXposedRuntime.hookMethod(notifyWordHighlight, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onFourArgumentViewModelEvent(
                                lineId = param.args[0] as Int,
                                isBackground = param.args[3] as Boolean,
                            )
                        }
                    })
            }
            if (setCurrentHighlightedLine != null) {
                ModernXposedRuntime.hookMethod(setCurrentHighlightedLine, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onSingleArgumentViewModelEvent(param.args[0] as Int)
                        }
                    })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VM hook failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "AMLyricBlur"
    }
}

internal class AppleMusicLyricBlurTargetAccess(
    private val recyclerViewClass: Class<*>,
) : LyricBlurTargetAccess {
    private var adapterPositionAccessor: Method? = null

    fun initializeAdapterPositionAccessor() {
        try {
            adapterPositionAccessor = recyclerViewClass.declaredMethods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
                    method.returnType == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
            Log.i("AMLyricBlur", "Reflection OK")
        } catch (t: Throwable) {
            Log.e("AMLyricBlur", "Reflection failed", t)
        }
    }

    override fun isRecyclerView(view: View): Boolean = view.javaClass == recyclerViewClass

    override fun isInstrumentalRow(view: View): Boolean = InstrumentalRowIdentity.matches(view)

    override fun isCreditsRow(view: View): Boolean = CreditsRowIdentity.matches(view)

    override fun adapterPosition(view: View): Int = try {
        (adapterPositionAccessor?.invoke(null, view) as? Int) ?: -1
    } catch (_: Throwable) {
        -1
    }
}

/** Callback wins once installed; otherwise ViewModel events replace the active lyric line. */
internal class LyricHighlightEventRouter(
    private val runtime: LyricBlurRuntime,
    private val probe: LyricHighlightProbe = LyricHighlightProbe(),
) {
    private var callbackInstalled = false

    fun onCallbackInstalled() {
        callbackInstalled = true
        probe.recordSyntheticInstall()
        runtime.onHighlightsChanged(emptySet())
    }

    fun onCallback(lineIds: Set<Int>) {
        onCallback(nativeFirst = null, lineIds = lineIds, nativeLast = null)
    }

    /**
     * Native callback bridge.  The two Long values are intentionally kept as
     * nullable wrappers: reflection may expose a null argument on a degraded
     * callback shape, and a diagnostic failure must never affect the set path.
     */
    fun onCallback(
        nativeFirst: Long?,
        lineIds: Set<Int>,
        nativeLast: Long?,
        rawLineIds: Set<Int>? = null,
    ) {
        probe.recordNative(nativeFirst, lineIds, nativeLast, rawLineIds ?: lineIds)
        runtime.onNativeHighlightsChanged(lineIds, nativeFirst)
    }

    fun onFourArgumentViewModelEvent(lineId: Int, isBackground: Boolean) {
        if (!callbackInstalled && !isBackground && lineId > 0) {
            probe.recordVm4(lineId)
            runtime.onFallbackHighlightChanged(lineId)
        }
    }

    fun onSingleArgumentViewModelEvent(lineId: Int) {
        if (!callbackInstalled && lineId >= 0) {
            probe.recordVm1(lineId)
            runtime.onFallbackHighlightChanged(lineId)
        }
    }
}
