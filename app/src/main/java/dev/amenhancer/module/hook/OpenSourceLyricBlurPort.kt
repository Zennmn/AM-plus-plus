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
import kotlin.math.abs

/**
 * Target-independent AMLyricBlur runtime.
 *
 * Apple Music discovery and hooks live in [AppleMusicBidirectionalLyricBlurTarget]. This class
 * only consumes semantic lyric events and the two view accessors required by the renderer.
 */
internal class OpenSourceLyricBlurPort(
    private val targetAccess: LyricBlurTargetAccess,
    private val blurRadiusOffsetPx: Int = 0,
) : LyricBlurRuntime {
    companion object {
        private const val TAG = "AMLyricBlur"
        private const val SCROLL_RESTORE_DELAY_MS = 1_000L
        private const val MAX_RECYCLER_DISCOVERY_ATTEMPTS = 10
    }

    private val highlightSession = LyricHighlightSession()
    private val blurRenderer = LyricBlurRenderer()

    private var recyclerView: Any? = null
    private var lyricsRootView: View? = null
    private var lyricsFragmentOwner: Any? = null
    private var recyclerDiscoveryRunnable: Runnable? = null
    private var recyclerDiscoveryAttempts = 0
    private var observedScrollView: View? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var isUserScrolling = false
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
    override fun onSessionChanged(songInfo: Any) {
        if (highlightSession.enter(songInfo)) {
            Log.i(TAG, "Lyric session changed")
            scheduleBlurUpdate()
        }
    }

    override fun onHighlightsChanged(lineIds: Set<Int>) {
        highlightSession.update(lineIds)
        scheduleBlurUpdate()
    }

    override fun onFallbackHighlightChanged(lineId: Int) {
        highlightSession.replace(lineId)
        scheduleBlurUpdate()
    }

    override fun onLyricsViewCreated(owner: Any, root: View) {
        lyricsFragmentOwner?.let(::releaseLyricsView)
        lyricsFragmentOwner = owner
        lyricsRootView = root
        recyclerDiscoveryAttempts = 0
        scheduleRecyclerViewDiscovery(root, delayMs = 500L)
    }

    override fun onLyricsViewDestroyed(owner: Any) {
        releaseLyricsView(owner)
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
        if (targetAccess.isRecyclerView(view)) return view
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
        applyBlur(includeFocus = false, immediate = true)
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

    private fun applyBlur(
        includeFocus: Boolean = true,
        immediate: Boolean = false,
    ) {
        val rv = getRv() as? ViewGroup ?: return
        val visibleRows = ArrayList<Pair<View, Int>>(rv.childCount)
        val instrumentalRows = ArrayList<Pair<View, Int>>(1)

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val adapterPos = targetAccess.adapterPosition(child)
            if (targetAccess.isInstrumentalRow(child)) {
                instrumentalRows += child to adapterPos
                continue
            }
            if (!isLyricsLine(child)) continue
            visibleRows += child to adapterPos
        }
        val activeIds = highlightSession.snapshot()
        val gapAnchorPosition = if (highlightSession.isGap()) {
            val referencePosition = activeIds.maxOrNull()
            instrumentalRows
                .asSequence()
                .map { (_, position) -> position }
                .filter { position -> position >= 0 }
                .minByOrNull { position ->
                    referencePosition?.let { reference -> abs(position - reference) } ?: 0
                } ?: -1
        } else {
            -1
        }
        val effectiveIds = BidirectionalBlurPolicy.resolveDisplayHighlights(
            active = activeIds,
            visiblePositions = visibleRows.map { (_, position) -> position },
            gapAnchorPosition = gapAnchorPosition,
        )
        val useTabletEdges = TabletModeQualifier.isEligible(rv.context)
        val targets = LinkedHashMap<View, Float>(visibleRows.size)
        visibleRows.forEach { (child, adapterPos) ->
            val focusBlur = if (includeFocus) {
                BidirectionalBlurPolicy.applyRadiusOffset(
                    radius = BidirectionalBlurPolicy.targetRadius(adapterPos, effectiveIds),
                    offsetPx = blurRadiusOffsetPx,
                )
            } else {
                0f
            }
            val edgeBlur = if (useTabletEdges) {
                TabletLyricVisualPolicy.edgeBlurRadius(
                    rowCenterPx = (child.top + child.bottom) / 2f,
                    viewportHeightPx = rv.height.toFloat(),
                )
            } else {
                0f
            }
            targets[child] = TabletLyricVisualPolicy.mergeBlurRadius(
                focusBlurRadius = focusBlur,
                edgeBlurRadius = edgeBlur,
                isHighlighted = includeFocus && adapterPos in effectiveIds,
            )
        }
        instrumentalRows.forEach { (view, _) -> blurRenderer.clear(view) }
        if (immediate) {
            blurRenderer.applyImmediately(targets)
        } else {
            blurRenderer.animateTo(targets)
        }
    }

    private fun isLyricsLine(view: View): Boolean {
        if (view !is ViewGroup) return false
        if (hasImageDescendant(view)) return false
        return true
    }

    private fun hasImageDescendant(view: View): Boolean {
        if (view is ImageView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasImageDescendant(view.getChildAt(i))) return true
            }
        }
        return false
    }

}

internal interface LyricBlurRuntime {
    fun onSessionChanged(songInfo: Any)
    fun onHighlightsChanged(lineIds: Set<Int>)
    fun onFallbackHighlightChanged(lineId: Int)
    fun onLyricsViewCreated(owner: Any, root: View)
    fun onLyricsViewDestroyed(owner: Any)
}

internal interface LyricBlurTargetAccess {
    fun isRecyclerView(view: View): Boolean
    fun isInstrumentalRow(view: View): Boolean
    fun adapterPosition(view: View): Int
}
