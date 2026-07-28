package dev.amenhancer.module.hook

/*
 * Ported from a23bc/amlyricblur, commit 3417e217d7692ae742bbae80d2bd51aadffcd59e.
 * Copyright (c) 2026 a23bc. Licensed under the MIT License.
 */

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import java.lang.reflect.Method

internal class OpenSourceLyricBlurPort {
    companion object {
        private const val TAG = "AMLyricBlur"
        private const val SCROLL_RESTORE_DELAY_MS = 1_000L
        private const val MAX_RECYCLER_DISCOVERY_ATTEMPTS = 10
    }

    private val highlightedLineIds = mutableSetOf<Int>()
    private val blurRenderer = LyricBlurRenderer()

    private var getAdapterPositionFromView: Method? = null

    private var recyclerView: Any? = null
    private var lyricsRootView: View? = null
    private var lyricsFragmentOwner: Any? = null
    private var recyclerDiscoveryRunnable: Runnable? = null
    private var recyclerDiscoveryAttempts = 0
    private var observedScrollView: View? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var isUserScrolling = false
    private var highlightHookInstalled = false
    private val scrollHandler by lazy { Handler(Looper.getMainLooper()) }
    private var blurFrameScheduled = false
    private val blurFrameCallback = Choreographer.FrameCallback {
        blurFrameScheduled = false
        runCatching(::applyBlur)
            .onFailure { error -> Log.e(TAG, "Blur failed", error) }
    }
    private val restoreBlurRunnable = Runnable {
        isUserScrolling = false
        scheduleBlurUpdate()
    }
    fun install(targets: LyricBlurTargets) {
        Log.i(TAG, "install with shared target symbols")
        initReflectionCache(targets.recyclerViewClass)
        hookHighlightCallback(targets.highlightCallback, targets.lyricsLineVectorClass)
        hookLyricsFragment(targets.lyricsFragmentClass)
        hookViewModel(targets.lyricsViewModelClass)
    }

    private fun initReflectionCache(rvClass: Class<*>) {
        try {
            for (m in rvClass.declaredMethods) {
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)
                    && m.parameterTypes.size == 1
                    && m.parameterTypes[0] == View::class.java
                    && m.returnType == Int::class.javaPrimitiveType
                ) {
                    getAdapterPositionFromView = m.apply { isAccessible = true }
                    break
                }
            }
            Log.i(TAG, "Reflection OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection failed", t)
        }
    }

    private fun hookHighlightCallback(method: Method?, vectorClass: Class<*>?) {
        if (highlightHookInstalled) return
        if (method == null || vectorClass == null) {
            Log.w(TAG, "Highlight callback symbols were unavailable")
            return
        }
        Log.i(TAG, "FOUND: ${method.declaringClass.name}.${method.name}")
        installHighlightHook(method, vectorClass)
    }

    private fun installHighlightHook(method: Method, vectorClass: Class<*>) {
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vector = param.args.firstOrNull { arg ->
                            arg != null && vectorClass.isInstance(arg)
                        } ?: return
                        val sizeMethod = vectorClass.getMethod("size")
                        val size = (sizeMethod.invoke(vector) as Long).toInt()
                        val getMethod = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
                        val newIds = mutableSetOf<Int>()
                        for (i in 0 until size) {
                            try {
                                val ptr = getMethod.invoke(vector, i.toLong()) ?: continue
                                val nativeObj = ptr.javaClass.getMethod("get").invoke(ptr) ?: continue
                                val lineId = (
                                    nativeObj.javaClass.getMethod("getLineId").invoke(nativeObj) as Number
                                ).toInt()
                                newIds.add(lineId)
                            } catch (_: Exception) {
                            }
                        }
                        synchronized(highlightedLineIds) {
                            val resolved = BidirectionalBlurPolicy.resolveHighlights(
                                current = highlightedLineIds,
                                incoming = newIds,
                            )
                            highlightedLineIds.clear()
                            highlightedLineIds.addAll(resolved)
                        }
                        scheduleBlurUpdate()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Highlight hook error", t)
                    }
                }
            })
            highlightHookInstalled = true
            Log.i(TAG, "Highlight hook installed on ${method.name}")
            scheduleBlurUpdate()
        } catch (t: Throwable) {
            Log.e(TAG, "installHighlightHook failed", t)
        }
    }

    private fun hookLyricsFragment(cls: Class<*>) {
        try {
            ModernXposedRuntime.hookAllMethods(cls, "onCreateView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? View ?: return
                    val owner = param.thisObject ?: return
                    bindLyricsView(owner, result)
                    Log.i(TAG, "onCreateView hooked")
                }
            })
            val destroyDeclaringClass = findLifecycleDeclaringClass(cls, "onDestroyView")
                ?: error("onDestroyView declaration was unavailable")
            ModernXposedRuntime.hookAllMethods(destroyDeclaringClass, "onDestroyView", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject?.takeIf(cls::isInstance) ?: return
                    releaseLyricsView(owner)
                }
            })
            Log.i(TAG, "Fragment lifecycle hooks installed")
        } catch (t: Throwable) {
            Log.w(TAG, "Fragment hook failed: ${t.message}")
        }
    }

    private fun findLifecycleDeclaringClass(start: Class<*>, methodName: String): Class<*>? =
        generateSequence<Class<*>>(start) { type -> type.superclass }
            .firstOrNull { type ->
                type.declaredMethods.any { method ->
                    method.name == methodName && method.parameterCount == 0
                }
            }

    private fun bindLyricsView(owner: Any, root: View) {
        lyricsFragmentOwner?.let(::releaseLyricsView)
        lyricsFragmentOwner = owner
        lyricsRootView = root
        recyclerDiscoveryAttempts = 0
        scheduleRecyclerViewDiscovery(root, delayMs = 500L)
    }

    private fun releaseLyricsView(owner: Any) {
        if (owner !== lyricsFragmentOwner) return
        recyclerDiscoveryRunnable?.let { discovery ->
            scrollHandler.removeCallbacks(discovery)
        }
        recyclerDiscoveryRunnable = null
        recyclerDiscoveryAttempts = 0
        scrollHandler.removeCallbacks(restoreBlurRunnable)
        if (blurFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(blurFrameCallback)
            blurFrameScheduled = false
        }
        detachScrollListener()
        blurRenderer.clearAll()
        recyclerView = null
        lyricsRootView = null
        lyricsFragmentOwner = null
        isUserScrolling = false
    }

    private fun scheduleRecyclerViewDiscovery(root: View, delayMs: Long) {
        if (root !== lyricsRootView) return
        if (recyclerDiscoveryAttempts >= MAX_RECYCLER_DISCOVERY_ATTEMPTS) {
            recyclerDiscoveryRunnable = null
            Log.w(TAG, "RV discovery stopped after $recyclerDiscoveryAttempts attempts")
            return
        }
        recyclerDiscoveryAttempts += 1
        recyclerDiscoveryRunnable?.let(scrollHandler::removeCallbacks)
        val discovery = Runnable {
            recyclerDiscoveryRunnable = null
            if (root === lyricsRootView) findRecyclerView(root)
        }
        recyclerDiscoveryRunnable = discovery
        scrollHandler.postDelayed(discovery, delayMs)
    }

    private fun hookViewModel(vmClass: Class<*>?) {
        if (vmClass == null) {
            Log.w(TAG, "VM symbol was unavailable")
            return
        }
        try {
            Log.i(TAG, "Found VM")

            for (m in vmClass.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 4 && p[0] == Int::class.javaPrimitiveType && p[3] == Boolean::class.javaPrimitiveType) {
                    ModernXposedRuntime.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val lineId = param.args[0] as Int
                            val isBg = param.args[3] as Boolean
                            if (!isBg && lineId > 0) {
                                synchronized(highlightedLineIds) {
                                    highlightedLineIds.add(lineId)
                                }
                                scheduleBlurUpdate()
                            }
                        }
                    })
                }
            }

            for (m in vmClass.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 1 && p[0] == Int::class.javaPrimitiveType && m.returnType == Void.TYPE) {
                    ModernXposedRuntime.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val lineId = param.args[0] as Int
                            if (lineId < 0) return
                            if (!highlightHookInstalled) {
                                synchronized(highlightedLineIds) {
                                    highlightedLineIds.clear()
                                    highlightedLineIds.add(lineId)
                                }
                                scheduleBlurUpdate()
                            }
                        }
                    })
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VM hook failed: ${t.message}")
        }
    }

    private fun findRecyclerView(view: View) {
        if (recyclerView != null) return
        try {
            val rv = findRVInHierarchy(view)
            if (rv != null) {
                recyclerView = rv
                recyclerDiscoveryAttempts = 0
                Log.i(TAG, "RV FOUND")
                attachScrollListener(rv)
            } else {
                scheduleRecyclerViewDiscovery(view, delayMs = 1_000L)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findRV error", t)
        }
    }

    private fun findRVInHierarchy(view: View): Any? {
        if (view.javaClass.name == "androidx.recyclerview.widget.RecyclerView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findRVInHierarchy(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun scheduleBlurUpdate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scrollHandler.post(::scheduleBlurUpdate)
            return
        }
        if (blurFrameScheduled) return
        blurFrameScheduled = true
        Choreographer.getInstance().postFrameCallback(blurFrameCallback)
    }

    private fun attachScrollListener(rv: Any) {
        try {
            val view = rv as View
            detachScrollListener()
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        isUserScrolling = true
                        scrollHandler.removeCallbacks(restoreBlurRunnable)
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> scheduleScrollRestore()
                }
                false
            }
            val listener = ViewTreeObserver.OnScrollChangedListener(::onScrollDetected)
            view.viewTreeObserver.addOnScrollChangedListener(listener)
            observedScrollView = view
            scrollChangedListener = listener
            Log.i(TAG, "Scroll listener attached")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach scroll listener", t)
        }
    }

    private fun detachScrollListener() {
        val view = observedScrollView
        val listener = scrollChangedListener
        if (view != null && listener != null) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnScrollChangedListener(listener)
            view.setOnTouchListener(null)
        }
        observedScrollView = null
        scrollChangedListener = null
    }

    private fun onScrollDetected() {
        if (!isUserScrolling) {
            scheduleBlurUpdate()
            return
        }
        clearAllBlur()
        scheduleScrollRestore()
    }

    private fun scheduleScrollRestore() {
        scrollHandler.removeCallbacks(restoreBlurRunnable)
        scrollHandler.postDelayed(restoreBlurRunnable, SCROLL_RESTORE_DELAY_MS)
    }

    private fun clearAllBlur() {
        val rv = getRv() as? ViewGroup ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (!isLyricsLine(child)) continue
            blurRenderer.clear(child)
        }
    }

    private fun getRv(): Any? {
        val rv = recyclerView ?: return null
        if ((rv as? ViewGroup)?.childCount?.let { it > 0 } == true) return rv
        val root = lyricsRootView ?: return null
        val fresh = findRVInHierarchy(root)
        if (fresh != null) {
            recyclerView = fresh
            return fresh
        }
        return null
    }

    private fun applyBlur() {
        val rv = getRv() as? ViewGroup ?: return
        val visibleRows = ArrayList<Pair<View, Int>>(rv.childCount)

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (!isLyricsLine(child)) continue
            val adapterPos = getAdapterPosition(child)
            visibleRows += child to adapterPos
        }
        val activeIds = synchronized(highlightedLineIds) { highlightedLineIds.toSet() }
        val effectiveIds = BidirectionalBlurPolicy.resolveDisplayHighlights(
            active = activeIds,
            visiblePositions = visibleRows.map { (_, position) -> position },
        )
        val targets = LinkedHashMap<View, Float>(visibleRows.size)
        visibleRows.forEach { (child, adapterPos) ->
            val target = BidirectionalBlurPolicy.targetRadius(adapterPos, effectiveIds)
            targets[child] = target
        }
        blurRenderer.animateTo(targets)
    }

    private fun isLyricsLine(view: View): Boolean {
        if (view !is ViewGroup) return false
        if (hasDescendantOfType(view, ImageView::class.java)) return false
        return true
    }

    private fun hasDescendantOfType(view: View, cls: Class<*>): Boolean {
        if (cls.isInstance(view)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasDescendantOfType(view.getChildAt(i), cls)) return true
            }
        }
        return false
    }

    private fun getAdapterPosition(child: View): Int {
        val method = getAdapterPositionFromView ?: return -1
        return try {
            method.invoke(null, child) as Int
        } catch (_: Throwable) {
            -1
        }
    }

}

internal data class LyricBlurTargets(
    val recyclerViewClass: Class<*>,
    val lyricsFragmentClass: Class<*>,
    val lyricsLineVectorClass: Class<*>?,
    val highlightCallback: Method?,
    val lyricsViewModelClass: Class<*>?,
)
