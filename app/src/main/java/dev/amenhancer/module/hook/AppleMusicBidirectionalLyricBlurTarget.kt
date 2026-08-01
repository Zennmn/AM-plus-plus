package dev.amenhancer.module.hook

import android.util.Log
import android.view.View
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
        val viewModelResolution = symbols.resolve(AppleMusicSymbols.LyricsViewModel)
        val callback = callbackResolution.valueOrNull()
        val viewModel = viewModelResolution.valueOrNull()
        if (callback == null && viewModel == null) {
            return TargetCapabilityInstall.Degraded(
                listOf(callbackResolution, viewModelResolution).joinToString { it.summary },
            )
        }

        val targetAccess = AppleMusicLyricBlurTargetAccess(recyclerClass)
        val runtime = OpenSourceLyricBlurPort(
            targetAccess = targetAccess,
            blurRadiusOffsetPx = TargetConfigClient.currentSettings().lyricBlurRadiusOffsetPx,
        )
        val highlights = LyricHighlightEventRouter(runtime)

        // Preserve the upstream installation order: recycler, session, callback, lifecycle, VM.
        targetAccess.initializeAdapterPositionAccessor()
        hookSessionProcessor(sessionResolution.valueOrNull(), runtime)
        hookHighlightCallback(callback, vectorResolution.valueOrNull(), highlights)
        hookLyricsFragment(fragmentClass, runtime)
        hookViewModel(viewModel, highlights)

        val optionalFailures = listOf(
            vectorResolution,
            sessionResolution,
            callbackResolution,
            viewModelResolution,
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

    private fun hookSessionProcessor(method: Method?, runtime: LyricBlurRuntime) {
        if (method == null) {
            Log.w(TAG, "Lyric session processor symbol was unavailable")
            return
        }
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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
                        highlights.onCallback(readLineIds(vectorClass, vector))
                    } catch (t: Throwable) {
                        Log.e(TAG, "Highlight hook error", t)
                    }
                }
            })
            highlights.onCallbackInstalled()
            Log.i(TAG, "Highlight hook installed on ${method.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "installHighlightHook failed", t)
        }
    }

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

    private fun hookViewModel(vmClass: Class<*>?, highlights: LyricHighlightEventRouter) {
        if (vmClass == null) {
            Log.w(TAG, "VM symbol was unavailable")
            return
        }
        try {
            Log.i(TAG, "Found VM")
            vmClass.declaredMethods.forEach { method ->
                val parameters = method.parameterTypes
                if (
                    parameters.size == 4 &&
                    parameters[0] == Int::class.javaPrimitiveType &&
                    parameters[3] == Boolean::class.javaPrimitiveType
                ) {
                    ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onFourArgumentViewModelEvent(
                                lineId = param.args[0] as Int,
                                isBackground = param.args[3] as Boolean,
                            )
                        }
                    })
                }
            }
            vmClass.declaredMethods.forEach { method ->
                val parameters = method.parameterTypes
                if (
                    parameters.size == 1 &&
                    parameters[0] == Int::class.javaPrimitiveType &&
                    method.returnType == Void.TYPE
                ) {
                    ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            highlights.onSingleArgumentViewModelEvent(param.args[0] as Int)
                        }
                    })
                }
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

    override fun adapterPosition(view: View): Int = try {
        (adapterPositionAccessor?.invoke(null, view) as? Int) ?: -1
    } catch (_: Throwable) {
        -1
    }
}

/** Callback wins once installed; otherwise ViewModel events replace the active lyric line. */
internal class LyricHighlightEventRouter(
    private val runtime: LyricBlurRuntime,
) {
    private var callbackInstalled = false

    fun onCallbackInstalled() {
        callbackInstalled = true
        runtime.onHighlightsChanged(emptySet())
    }

    fun onCallback(lineIds: Set<Int>) {
        runtime.onHighlightsChanged(lineIds)
    }

    fun onFourArgumentViewModelEvent(lineId: Int, isBackground: Boolean) {
        if (!callbackInstalled && !isBackground && lineId > 0) {
            runtime.onFallbackHighlightChanged(lineId)
        }
    }

    fun onSingleArgumentViewModelEvent(lineId: Int) {
        if (!callbackInstalled && lineId >= 0) {
            runtime.onFallbackHighlightChanged(lineId)
        }
    }
}
