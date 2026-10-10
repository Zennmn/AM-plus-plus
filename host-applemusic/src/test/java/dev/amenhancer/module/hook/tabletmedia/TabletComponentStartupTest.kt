package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletComponentStartupTest {
    private fun resourceIds(components: TabletPlayerComponents, names: List<String>): Map<String, Int> {
        val ids = names.associateWith { View.generateViewId() }
        @Suppress("UNCHECKED_CAST")
        (components.javaClass.getDeclaredField("ids").apply { isAccessible = true }.get(components) as MutableMap<String, Int>)
            .putAll(ids)
        return ids
    }
    private class PercentParams(width: Int, height: Int) : FrameLayout.LayoutParams(width, height) {
        @JvmField var matchConstraintPercentHeight = .345f
    }
    @Test fun packagedIconsInitializeAndCollapsedPlayerReleasesItsOverlay() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val song = FrameLayout(activity); val right = FrameLayout(activity)
        root.addView(song); root.addView(right)
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        try {
            assertEquals(3, root.childCount)
            components.update(0f, false)
            assertEquals(android.view.View.INVISIBLE, root.getChildAt(2).visibility)
        } finally { components.close(); activity.finish() }
        assertEquals(2, root.childCount)
        assertSame(song, root.getChildAt(0)); assertSame(right, root.getChildAt(1))
    }
    @Test fun queueCrossfadeNeverRestoresTheOutgoingOrIncomingNativeFooter() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val song = FrameLayout(activity); val right = FrameLayout(activity)
        root.addView(song); root.addView(right)
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val names = listOf("player_controls", "play_pause", "player_lyrics", "player_queue", "media_route_button")
        val ids = resourceIds(components, names)
        fun pane(): Pair<FrameLayout, List<View>> {
            val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
            group.addView(View(activity).apply { id = ids.getValue("play_pause") })
            val actions = names.drop(2).map { name -> View(activity).apply {
                id = ids.getValue(name); alpha = .8f; isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                group.addView(this, FrameLayout.LayoutParams(44, 44))
            } }
            song.addView(group)
            return group to actions
        }
        try {
            val outgoing = pane()
            components.prepare(outgoing.first)
            val incoming = pane()
            // The native onViewCreated hook prepares the queue before its first draw.
            components.prepare(incoming.first)
            val actions = outgoing.second + incoming.second
            actions.forEach { assertEquals(0f, it.alpha, 0f); assertFalse(it.isClickable) }
            components.javaClass.getDeclaredField("placed").apply { isAccessible = true }.setBoolean(components, true)
            // A late native media update can write alpha again during the crossfade.
            actions.forEach { it.alpha = .8f }
            components.update(.5f, true)
            actions.forEach {
                assertEquals(0f, it.alpha, 0f); assertFalse(it.isClickable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, it.importantForAccessibility)
            }
            components.close()
            actions.forEach {
                assertEquals(.8f, it.alpha, 0f); assertTrue(it.isClickable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, it.importantForAccessibility)
            }
        } finally { components.close(); activity.finish() }
    }
    @Test fun preparationKeepsNativeFontsAndControlDimensionsAndOnlyChangesQueuePercentage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        root.addView(song); root.addView(right)
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val names = listOf("controls", "player_controls", "play_pause", "previous_rewind", "next_fast_forward",
            "player_lyrics", "player_queue", "media_route_button", "current_time_progress", "total_time_progress",
            "audio_badge_text", "title", "subtitle")
        val ids = resourceIds(components, names)
        val wrapper = FrameLayout(activity).apply { id = ids.getValue("controls") }
        val percent = PercentParams(504, 0)
        song.addView(wrapper, percent)
        val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
        wrapper.addView(group)
        val nativeImages = names.subList(2, 8).map { name -> ImageView(activity).apply {
            id = ids.getValue(name); setPadding(11, 11, 11, 11); scaleType = ImageView.ScaleType.FIT_CENTER
            group.addView(this, FrameLayout.LayoutParams(66, 66).apply { topMargin = 3; bottomMargin = 14 })
        } }
        val texts = names.drop(8).map { name -> TextView(activity).apply {
            id = ids.getValue(name); textSize = 22f; minimumHeight = 0
            group.addView(this, FrameLayout.LayoutParams(200, 66).apply { topMargin = 12; bottomMargin = 12 })
        } }
        val sizes = texts.map { it.textSize }; val params = (nativeImages + texts).map { it.layoutParams }
        try {
            assertTrue(components.prepare(wrapper))
            assertEquals(.32f, percent.matchConstraintPercentHeight, 0f)
            assertFalse(components.prepare(wrapper))
            (nativeImages + texts).forEachIndexed { i, view ->
                assertSame(params[i], view.layoutParams); assertEquals(66, view.layoutParams.height)
                val p = view.layoutParams as FrameLayout.LayoutParams
                assertEquals(if (view is TextView) 12 else 3, p.topMargin)
                assertEquals(if (view is TextView) 12 else 14, p.bottomMargin)
            }
            nativeImages.forEach {
                assertEquals(11, it.paddingTop); assertEquals(11, it.paddingBottom)
                assertEquals(ImageView.ScaleType.FIT_CENTER, it.scaleType)
            }
            texts.forEachIndexed { i, view -> assertEquals(sizes[i], view.textSize, 0f); assertEquals(0, view.minimumHeight) }
            components.close()
            assertEquals(.345f, percent.matchConstraintPercentHeight, 0f)
        } finally { components.close(); activity.finish() }
    }
    @Test fun volumeAndCornerOutputHaveSeparateRowsWhileTheRightButtonsKeepTheirPositions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = kotlin.math.round(value * density).toInt()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(dp(504), dp(800)).apply { leftMargin = dp(48) })
        root.addView(right, FrameLayout.LayoutParams(dp(568), dp(800)).apply { leftMargin = dp(616) })
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val names = listOf("player_controls", "play_pause", "previous_rewind", "next_fast_forward",
            "player_lyrics", "player_queue", "media_route_button", "seek_bar_controls")
        val ids = resourceIds(components, names)
        val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
        song.addView(group, FrameLayout.LayoutParams(dp(504), dp(256), Gravity.BOTTOM))
        names.subList(1, 4).forEach { name -> group.addView(ImageView(activity).apply { id = ids.getValue(name) },
            FrameLayout.LayoutParams(dp(66), dp(66)).apply { topMargin = dp(50) }) }
        val nativeFooter = ImageView(activity).apply { id = ids.getValue("player_lyrics") }
        listOf(nativeFooter, ImageView(activity).apply { id = ids.getValue("player_queue") },
            ImageView(activity).apply { id = ids.getValue("media_route_button") }).forEach { view ->
            group.addView(view, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM).apply { bottomMargin = dp(14) })
        }
        group.addView(View(activity).apply { id = ids.getValue("seek_bar_controls") }, FrameLayout.LayoutParams(dp(504), dp(41)))
        try {
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dp(1200), dp(800))
            components.update(1f, false)
            val overlay = root.getChildAt(2) as FrameLayout
            val output = overlay.getChildAt(0); val lyrics = overlay.getChildAt(1)
            val queue = overlay.getChildAt(2); val volume = overlay.getChildAt(3)
            assertEquals(View.VISIBLE, volume.visibility)
            assertEquals(dp(44), volume.height)
            assertEquals(volume.bottom + dp(8), output.top)
            assertEquals(song.left, volume.left)
            assertEquals(song.left + dp(10), output.left)
            assertEquals(song.left + group.width, volume.right)
            val rightTop = group.top + group.height - dp(12) - dp(44)
            assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
            assertEquals(rightTop, output.top)
            assertEquals(dp(44), lyrics.height); assertEquals(dp(44), queue.height)
            assertEquals(root.width - dp(44) - dp(44), queue.left)
            assertEquals(queue.left - dp(8) - dp(44), lyrics.left)
            assertEquals(dp(60), nativeFooter.height)
        } finally { components.close(); activity.finish() }
    }
}
