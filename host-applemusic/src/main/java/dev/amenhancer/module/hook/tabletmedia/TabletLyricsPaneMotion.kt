package dev.amenhancer.module.hook.tabletmedia

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

/** Song/queue controls keep their resting X; only the cover follows native sheet progress. */
internal class TabletLyricsPaneMotion(
    private val parent: ViewGroup, private val left: View, private val right: View,
    private val invalidate: () -> Unit,
    private val artwork: (() -> View?)? = null,
) : AutoCloseable {
    private val leftX = left.translationX
    private val rightX = right.translationX
    private var animation: ValueAnimator? = null
    private var target = 0f
    private var fraction = 0f
    private var progress = 0f
    private var expansion = 0f
    private var available = true
    private var artworkView: View? = null
    private var nativeArtworkX = 0f
    private var lastArtworkX: Float? = null
    val horizontalOffset: Float get() = left.translationX - leftX

    fun apply(collapsed: Boolean, pane: String?, progress: Float, expansion: Float, animate: Boolean = false,
        available: Boolean = true) {
        val availabilityChanged = this.available != available
        this.available = available
        this.progress = progress.coerceIn(0f, 1f)
        this.expansion = expansion
        if (pane != "SONG" && pane != "QUEUE") {
            animation?.cancel(); animation = null; target = 0f; fraction = 0f
            render()
            if (collapsed || !available) {
                right.alpha = 0f; right.visibility = View.INVISIBLE
                right.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            return
        }
        val desired = if (collapsed || !available) 1f else 0f
        if (target != desired) {
            animation?.cancel(); animation = null
            target = desired
            if ((animate || availabilityChanged) && this.progress > 0f && parent.width > 0) {
                animation = ValueAnimator.ofFloat(fraction, target).apply {
                    duration = 280; interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { fraction = it.animatedValue as Float; render(); invalidate() }
                    start()
                }
            } else fraction = target
        }
        render()
    }
    private fun render() {
        val offset = TabletComponentGeometry.centeredOffset(parent.width, left.left, left.width) ?: 0f
        val centered = offset * fraction
        left.translationX = leftX + centered
        right.translationX = rightX + right.width * .12f * fraction
        compensateCover(centered)
        right.alpha = expansion * (1f - fraction)
        right.visibility = if (fraction >= .999f) View.INVISIBLE else View.VISIBLE
        right.importantForAccessibility = if (fraction <= .001f && expansion >= .6f)
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Restore the native X before its next callback reads/writes a fresh cover frame. */
    fun prepareNativeCoverFrame() {
        artworkView?.let { if (it.translationX == lastArtworkX) it.translationX = nativeArtworkX }
        lastArtworkX = null
    }

    private fun compensateCover(centered: Float) {
        val view = artwork?.invoke()
        if (view !== artworkView) {
            prepareNativeCoverFrame()
            artworkView = view
        }
        view ?: return
        if (lastArtworkX == null || view.translationX != lastArtworkX) nativeArtworkX = view.translationX
        // Parent stays centered for controls. Cancel the unused portion on the cover only,
        // giving it the same continuous mini-to-center path without touching scale or Y.
        val desired = nativeArtworkX - centered * (1f - progress)
        if (view.translationX != desired) view.translationX = desired
        lastArtworkX = desired
    }

    override fun close() {
        prepareNativeCoverFrame(); artworkView = null
        animation?.cancel(); animation = null; target = 0f; fraction = 0f; available = true
        left.translationX = leftX; right.translationX = rightX; right.visibility = View.VISIBLE
    }
}
