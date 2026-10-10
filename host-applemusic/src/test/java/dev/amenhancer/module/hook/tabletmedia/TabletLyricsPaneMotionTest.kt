package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TabletLyricsPaneMotionTest {
    @Test fun lyricToggleMovesTheExistingColumnAndCanReverseOrCancelWithoutChangingSize() {
        // Use an explicit duration scale and deterministic animator fractions.
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity); val left = View(activity); val right = View(activity)
        parent.addView(left); parent.addView(right)
        parent.layout(0, 0, 1200, 800); left.layout(48, 0, 552, 800); right.layout(616, 0, 1184, 800)
        val motion = TabletLyricsPaneMotion(parent, left, right, {})
        try {
            motion.apply(false, true, 1f)
            motion.apply(true, true, 1f, animate = true)
            fun frame(fraction: Float) {
                val animation = motion.javaClass.getDeclaredField("animation").apply { isAccessible = true }
                    .get(motion) as android.animation.ValueAnimator
                animation.setCurrentFraction(fraction)
            }
            frame(.4f)
            assertTrue("mid-animation translation=${left.translationX}", left.translationX > 0f && left.translationX < 300f)
            assertEquals(View.VISIBLE, right.visibility); assertTrue(right.alpha > 0f && right.alpha < 1f)
            motion.apply(true, true, 1f)
            frame(1f)
            assertEquals(300f, left.translationX, .1f); assertEquals(504, left.width)
            assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(false, true, 1f, animate = true)
            frame(.35f)
            assertTrue(left.translationX > 0f && left.translationX < 300f)
            motion.apply(true, false, .5f)
            assertEquals(0f, left.translationX, 0f); assertEquals(0f, right.translationX, 0f)
            assertEquals(View.VISIBLE, right.visibility)
            motion.apply(false, true, 1f)
            assertEquals(1f, right.alpha, 0f)
        } finally { motion.close(); activity.finish() }
    }
}
