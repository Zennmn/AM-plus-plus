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
            motion.apply(false, "SONG", true, 1f)
            motion.apply(true, "SONG", true, 1f, animate = true)
            fun frame(fraction: Float) {
                val animation = motion.javaClass.getDeclaredField("animation").apply { isAccessible = true }
                    .get(motion) as android.animation.ValueAnimator
                animation.setCurrentFraction(fraction)
            }
            frame(.4f)
            assertTrue("mid-animation translation=${left.translationX}", left.translationX > 0f && left.translationX < 300f)
            assertEquals(View.VISIBLE, right.visibility); assertTrue(right.alpha > 0f && right.alpha < 1f)
            val movingPosition = left.translationX
            motion.apply(true, "QUEUE", true, 1f)
            assertEquals(movingPosition, left.translationX, 0f)
            frame(1f)
            assertEquals(300f, left.translationX, .1f); assertEquals(504, left.width)
            assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(false, "SONG", true, 1f, animate = true)
            frame(.35f)
            assertTrue(left.translationX > 0f && left.translationX < 300f)
            motion.apply(true, "SONG", false, .5f)
            assertEquals(0f, left.translationX, 0f); assertEquals(0f, right.translationX, 0f)
            assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(false, "SONG", true, 1f)
            assertEquals(1f, right.alpha, 0f)
        } finally { motion.close(); activity.finish() }
    }
    @Test fun nativeDisableClosesLyricsAndNativeEnableRestoresOnlyAnAutomaticallyClosedPane() {
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity); val left = View(activity); val right = View(activity)
        parent.addView(left); parent.addView(right)
        parent.layout(0, 0, 1200, 800); left.layout(48, 0, 552, 800); right.layout(616, 0, 1184, 800)
        val motion = TabletLyricsPaneMotion(parent, left, right, {})
        fun finishAnimation() {
            val animation = motion.javaClass.getDeclaredField("animation").apply { isAccessible = true }
                .get(motion) as android.animation.ValueAnimator
            animation.setCurrentFraction(1f)
        }
        try {
            motion.apply(false, "SONG", true, 1f, available = true)
            assertEquals(View.VISIBLE, right.visibility); assertEquals(0f, left.translationX, 0f)
            motion.apply(false, "SONG", true, 1f, available = false)
            finishAnimation()
            assertEquals(View.INVISIBLE, right.visibility); assertEquals(300f, left.translationX, .1f)
            assertEquals(504, left.width)
            // An expansion request cannot override the host's disabled lyric state.
            motion.apply(false, "SONG", true, 1f, animate = true, available = false)
            assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(false, "SONG", true, 1f, available = true)
            finishAnimation()
            assertEquals(View.VISIBLE, right.visibility); assertEquals(0f, left.translationX, .1f)
            motion.apply(true, "SONG", true, 1f)
            motion.apply(true, "SONG", true, 1f, available = false)
            motion.apply(true, "SONG", true, 1f, available = true)
            assertEquals(View.INVISIBLE, right.visibility)
            // Sheet dragging retains the original native coordinates without showing old lyrics.
            motion.apply(false, "QUEUE", false, .5f, available = false)
            assertEquals(0f, left.translationX, 0f); assertEquals(View.INVISIBLE, right.visibility)
            assertEquals(0f, right.alpha, 0f)
            motion.apply(false, "QUEUE", true, 1f, available = false)
            assertEquals(View.INVISIBLE, right.visibility)
            assertEquals(300f, left.translationX, .1f)
            motion.apply(false, "QUEUE", true, 1f, available = true)
            finishAnimation()
            assertEquals(View.VISIBLE, right.visibility); assertEquals(0f, left.translationX, .1f)
            motion.apply(false, "QUEUE", false, .5f, available = true)
            assertEquals(0f, left.translationX, 0f); assertEquals(View.VISIBLE, right.visibility)
            assertEquals(.5f, right.alpha, 0f)
        } finally { motion.close(); activity.finish() }
    }

    @Test fun closedLyricsKeepTheSharedSongQueueHostCenteredAcrossPageSwitches() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity); val left = FrameLayout(activity); val right = View(activity)
        parent.addView(left); parent.addView(right)
        activity.setContentView(parent)
        parent.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1200, 800); left.layout(48, 0, 552, 800); right.layout(616, 0, 1184, 800)
        val song = View(activity); val queue = View(activity)
        left.addView(song); left.addView(queue)
        song.layout(0, 0, 504, 800); queue.layout(0, 0, 504, 800)
        val motion = TabletLyricsPaneMotion(parent, left, right, {})
        try {
            motion.apply(true, "SONG", true, 1f)
            val sharedPosition = IntArray(2).also(song::getLocationInWindow)
            assertEquals(348, sharedPosition[0] - IntArray(2).also(parent::getLocationInWindow)[0])
            // Both outgoing and incoming native fragments draw in the same translated host.
            for (pane in listOf("QUEUE", "SONG", "QUEUE")) {
                motion.apply(true, pane, true, 1f)
                assertEquals(300f, left.translationX, .1f); assertEquals(504, left.width)
                assertArrayEquals(sharedPosition, IntArray(2).also(queue::getLocationInWindow))
                assertEquals(View.INVISIBLE, right.visibility); assertEquals(0f, right.alpha, 0f)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, right.importantForAccessibility)
            }
            motion.apply(true, "QUEUE", true, 1f, available = false)
            motion.apply(true, "QUEUE", true, 1f, available = true)
            assertEquals(300f, left.translationX, .1f); assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(true, "QUEUE", false, .5f)
            assertEquals(0f, left.translationX, 0f); assertEquals(View.INVISIBLE, right.visibility)
            motion.apply(true, "QUEUE", true, 1f)
            assertEquals(300f, left.translationX, .1f); assertEquals(View.INVISIBLE, right.visibility)
            // An explicit lyric reopen can restore dual-pane presentation on a queue page.
            motion.apply(false, "QUEUE", true, 1f)
            assertEquals(0f, left.translationX, 0f); assertEquals(View.VISIBLE, right.visibility)
            assertEquals(1f, right.alpha, 0f)
        } finally { motion.close(); activity.finish() }
    }
}
