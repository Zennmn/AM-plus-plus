package dev.amenhancer.module.hook

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.WeakHashMap

/** Drives every visible lyric row from one frame callback and one shared effect cache. */
internal class LyricBlurRenderer {
    private data class Transition(
        var startRadius: Float,
        var targetRadius: Float,
        var startedAtMs: Long,
        var appliedRadius: Int = UNSET_RADIUS,
    )

    private val transitions = WeakHashMap<View, Transition>()
    private val effects = mutableMapOf<Int, RenderEffect>()
    private var frameScheduled = false

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        renderFrame(frameTimeNanos / NANOS_PER_MILLISECOND)
    }

    fun animateTo(targets: Map<View, Float>) {
        val now = SystemClock.uptimeMillis()
        transitions.keys
            .filterNot(targets::containsKey)
            .toList()
            .forEach(::clear)
        targets.forEach { (view, target) ->
            if (target <= 0f) {
                clear(view)
                return@forEach
            }
            val state = transitions[view]
            val current = state?.radiusAt(now) ?: 0f
            if (state != null && state.targetRadius == target) return@forEach
            transitions[view] = Transition(
                startRadius = current,
                targetRadius = target,
                startedAtMs = now,
                appliedRadius = state?.appliedRadius ?: UNSET_RADIUS,
            )
        }
        scheduleFrame()
    }

    /** Manual scrolling needs position-driven edge blur without temporal lag. */
    fun applyImmediately(targets: Map<View, Float>) {
        val now = SystemClock.uptimeMillis()
        transitions.keys
            .filterNot(targets::containsKey)
            .toList()
            .forEach(::clear)
        targets.forEach { (view, target) ->
            val quantized = BidirectionalBlurPolicy.quantize(target)
            applyRadius(view, quantized)
            transitions[view] = Transition(
                startRadius = target,
                targetRadius = target,
                startedAtMs = now,
                appliedRadius = quantized,
            )
        }
    }

    fun clear(view: View) {
        transitions.remove(view)
        view.setRenderEffect(null)
        view.alpha = 1f
    }

    fun clearAll() {
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
        }
        transitions.keys.toList().forEach { view ->
            view.setRenderEffect(null)
            view.alpha = 1f
        }
        transitions.clear()
    }

    private fun renderFrame(nowMs: Long) {
        var needsAnotherFrame = false
        transitions.forEach { (view, state) ->
            val radius = state.radiusAt(nowMs)
            val quantized = BidirectionalBlurPolicy.quantize(radius)
            if (quantized != state.appliedRadius) {
                applyRadius(view, quantized)
                state.appliedRadius = quantized
            }
            if (nowMs - state.startedAtMs < BidirectionalBlurPolicy.TRANSITION_DURATION_MS) {
                needsAnotherFrame = true
            } else {
                state.startRadius = state.targetRadius
                state.startedAtMs = nowMs
            }
        }
        if (needsAnotherFrame) scheduleFrame()
    }

    private fun applyRadius(view: View, quantized: Int) {
        val effect = if (quantized == 0) {
            null
        } else {
            effects.getOrPut(quantized) {
                RenderEffect.createBlurEffect(
                    quantized.toFloat(),
                    quantized.toFloat(),
                    Shader.TileMode.DECAL,
                )
            }
        }
        view.setRenderEffect(effect)
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun Transition.radiusAt(nowMs: Long): Float = BidirectionalBlurPolicy.interpolate(
        start = startRadius,
        target = targetRadius,
        elapsedMs = nowMs - startedAtMs,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNSET_RADIUS = -1
    }
}
