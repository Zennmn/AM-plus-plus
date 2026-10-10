package dev.amenhancer.module.hook.tabletmedia

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

/** Lyrics toggles and native availability changes animate; sheet/page transitions keep their endpoints. */
internal class TabletLyricsPaneMotion(
    private val parent: ViewGroup, private val left: View, private val right: View,
    private val invalidate: () -> Unit,
) : AutoCloseable {
    private val leftX = left.translationX
    private val rightX = right.translationX
    private var animation: ValueAnimator? = null
    private var target = 0f
    private var fraction = 0f
    private var expansion = 0f
    private var available = true
    val horizontalOffset: Float get() = left.translationX - leftX

    fun apply(collapsed: Boolean, safe: Boolean, expansion: Float, animate: Boolean = false,
        available: Boolean = true) {
        val availabilityChanged = this.available != available
        this.available = available
        this.expansion = expansion
        if (!safe) {
            animation?.cancel(); animation = null; target = 0f; fraction = 0f
            render(); return
        }
        val desired = if (collapsed || !available) 1f else 0f
        if (target != desired) {
            animation?.cancel(); animation = null
            target = desired
            if ((animate || availabilityChanged) && parent.width > 0) {
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
        left.translationX = leftX + offset * fraction
        right.translationX = rightX + right.width * .12f * fraction
        right.alpha = expansion * (1f - fraction)
        right.visibility = if (fraction >= .999f) View.INVISIBLE else View.VISIBLE
        right.importantForAccessibility = if (fraction <= .001f && expansion >= .6f)
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    override fun close() {
        animation?.cancel(); animation = null; target = 0f; fraction = 0f; available = true
        left.translationX = leftX; right.translationX = rightX; right.visibility = View.VISIBLE
    }
}
