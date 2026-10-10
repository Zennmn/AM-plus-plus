package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.MotionEvent
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
        val names = listOf("player_controls", "play_pause", "player_lyrics", "player_queue", "media_route_button",
            "badge_platter", "shuffle_repeat_badge", "shareplay_badge", "router_name_textview")
        val ids = resourceIds(components, names)
        fun pane(): Pair<FrameLayout, List<View>> {
            val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
            group.addView(View(activity).apply { id = ids.getValue("play_pause") })
            val actions = names.drop(2).map { name ->
                (if (name == "shareplay_badge") FrameLayout(activity) else View(activity)).apply {
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
            actions.forEach { it.alpha = .8f; it.visibility = View.VISIBLE; it.isPressed = true }
            components.update(.5f, true)
            actions.forEach {
                assertEquals(0f, it.alpha, 0f); assertFalse(it.isClickable)
                assertEquals(View.INVISIBLE, it.visibility); assertFalse(it.isPressed)
                assertEquals(if (it is android.view.ViewGroup) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    else View.IMPORTANT_FOR_ACCESSIBILITY_NO, it.importantForAccessibility)
            }
            components.close()
            actions.forEach {
                assertEquals(.8f, it.alpha, 0f); assertTrue(it.isClickable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, it.importantForAccessibility)
            }
        } finally { components.close(); activity.finish() }
    }
    @Test fun hiddenFooterStopsTouchFeedbackAndRestoresNativeVisibility() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(400, 400)); root.addView(right)
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val ids = resourceIds(components, listOf("player_controls", "play_pause", "player_lyrics",
            "player_queue", "media_route_button", "badge_platter"))
        val group = FrameLayout(activity).apply {
            id = ids.getValue("player_controls")
            setOnClickListener { } // Native controls consume presses in otherwise empty space.
        }
        song.addView(group, FrameLayout.LayoutParams(400, 240).apply { topMargin = 120 })
        group.addView(ImageView(activity).apply { id = ids.getValue("play_pause") }, FrameLayout.LayoutParams(44, 44))
        val nativeLyrics = ImageView(activity).apply {
            id = ids.getValue("player_lyrics"); visibility = View.INVISIBLE
        }
        val nativeQueue = ImageView(activity).apply { id = ids.getValue("player_queue") }
        val badge = View(activity).apply { id = ids.getValue("badge_platter"); visibility = View.GONE }
        listOf(nativeLyrics, nativeQueue, badge).forEach { group.addView(it, FrameLayout.LayoutParams(44, 44)) }
        var nativeTouches = 0
        var nativeClicks = 0
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.WHITE), null, null)
        val output = ImageView(activity).apply {
            id = ids.getValue("media_route_button"); alpha = .8f; background = ripple
            setOnClickListener { nativeClicks++ }
            setOnTouchListener { _, _ -> nativeTouches++; false }
        }
        val outputParams = FrameLayout.LayoutParams(44, 44).apply { leftMargin = 178; topMargin = 180 }
        group.addView(output, outputParams)
        fun measure() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 400)
        }
        try {
            measure()
            val originalBounds = listOf(output.left, output.top, output.right, output.bottom)
            components.prepare()
            measure()
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, downTime + 10, action,
                    (song.left + group.left + output.left + 22).toFloat(),
                    (song.top + group.top + output.top + 22).toFloat(), 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(0, nativeTouches); assertEquals(0, nativeClicks)
            assertSame(outputParams, output.layoutParams)
            assertEquals(originalBounds, listOf(output.left, output.top, output.right, output.bottom))
            assertEquals(View.GONE, badge.visibility)
            assertTrue(nativeLyrics.isEnabled); assertEquals(true, components.lyricsAvailable())

            group.isPressed = true // A non-clickable child also inherits its parent's press state.
            assertTrue(output.isPressed)
            components.prepare()
            assertFalse(output.isPressed)
            assertFalse(ripple.state.contains(android.R.attr.state_pressed))
            group.isPressed = false

            // A native update can re-show the route while hiding a different action.
            output.visibility = View.VISIBLE; output.alpha = .6f; output.isPressed = true
            nativeQueue.visibility = View.GONE
            components.prepare()
            assertEquals(View.INVISIBLE, output.visibility); assertFalse(output.isPressed)
            assertEquals(View.GONE, nativeQueue.visibility)
            nativeLyrics.isEnabled = false
            components.prepare()
            assertFalse(nativeLyrics.isEnabled); assertEquals(false, components.lyricsAvailable())

            components.close()
            assertEquals(View.VISIBLE, output.visibility); assertEquals(.6f, output.alpha, 0f)
            assertEquals(View.INVISIBLE, nativeLyrics.visibility)
            assertEquals(View.GONE, nativeQueue.visibility); assertEquals(View.GONE, badge.visibility)
            assertSame(ripple, output.background); assertTrue(output.isClickable)
            assertTrue(output.performClick()); assertEquals(1, nativeClicks)
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
            assertEquals(.25f, percent.matchConstraintPercentHeight, 0f)
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
    @Test fun volumeFollowsProgressBoundsWithoutOutputWhileTheRightButtonsKeepTheirPositions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = kotlin.math.round(value * density).toInt()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(dp(504), dp(800)).apply { leftMargin = dp(48) })
        root.addView(right, FrameLayout.LayoutParams(dp(568), dp(800)).apply { leftMargin = dp(616) })
        var lyricClicks = 0
        var songVisible = true
        var lyricsExpanded = true
        val components = TabletPlayerComponents(root, song, right, { songVisible }, { lyricsExpanded }, { lyricClicks++ })
        val names = listOf("player_controls", "play_pause", "previous_rewind", "next_fast_forward",
            "player_lyrics", "player_queue", "media_route_button", "seek_bar_controls", "progress")
        val ids = resourceIds(components, names)
        val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
        song.addView(group, FrameLayout.LayoutParams(dp(504), dp(220), Gravity.BOTTOM))
        names.subList(1, 4).forEach { name -> group.addView(ImageView(activity).apply { id = ids.getValue(name) },
            FrameLayout.LayoutParams(dp(66), dp(66)).apply { topMargin = dp(50) }) }
        val nativeFooter = ImageView(activity).apply { id = ids.getValue("player_lyrics") }
        listOf(nativeFooter, ImageView(activity).apply { id = ids.getValue("player_queue") },
            ImageView(activity).apply { id = ids.getValue("media_route_button") }).forEach { view ->
            group.addView(view, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM).apply { bottomMargin = dp(14) })
        }
        val progress = FrameLayout(activity).apply { id = ids.getValue("seek_bar_controls") }
        group.addView(progress, FrameLayout.LayoutParams(dp(504), dp(41)))
        val track = View(activity).apply { id = ids.getValue("progress"); setPadding(dp(8), 0, dp(10), 0) }
        progress.addView(track, FrameLayout.LayoutParams(dp(472), dp(12)).apply { leftMargin = dp(16) })
        try {
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dp(1200), dp(800))
            components.update(1f, false)
            val overlay = root.getChildAt(2) as FrameLayout
            assertEquals(3, overlay.childCount)
            val lyrics = overlay.getChildAt(0); val queue = overlay.getChildAt(1)
            val volume = overlay.getChildAt(2)
            val nativeOutput = group.findViewById<View>(ids.getValue("media_route_button"))
            assertEquals(0f, nativeOutput.alpha, 0f); assertFalse(nativeOutput.isClickable)
            assertEquals(View.VISIBLE, volume.visibility)
            assertEquals(group.top + nativeFooter.top + nativeFooter.height / 2 - dp(4) - dp(2), volume.top + volume.height / 2)
            assertEquals(song.left + track.left + track.paddingLeft, volume.left)
            assertEquals(song.left + track.right - track.paddingRight, volume.right)
            assertEquals(dp(28), root.height - volume.bottom)
            assertEquals(true, components.lyricsAvailable())
            lyrics.performClick(); assertEquals(1, lyricClicks)
            nativeFooter.isEnabled = false
            // Reject a late native disable even before the next frame updates the proxy.
            lyrics.performClick(); assertEquals(1, lyricClicks)
            components.update(1f, false)
            assertEquals(false, components.lyricsAvailable()); assertFalse(lyrics.isEnabled)
            assertTrue(queue.isEnabled); assertFalse(nativeFooter.isEnabled)
            // Collapsing the right pane must not prevent detection of the next native enable.
            right.visibility = View.INVISIBLE
            nativeFooter.isEnabled = true
            components.update(1f, false)
            assertEquals(true, components.lyricsAvailable()); assertTrue(lyrics.isEnabled)
            lyrics.performClick(); assertEquals(2, lyricClicks)
            right.visibility = View.VISIBLE
            song.translationX = 0f
            listOf(180, 160, 220).forEach { height ->
                group.layoutParams = (group.layoutParams as FrameLayout.LayoutParams).apply { this.height = dp(height) }
                root.measure(View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, dp(1200), dp(800))
                components.update(1f, false)
                assertEquals(View.VISIBLE, volume.visibility)
                assertEquals(song.left + track.left + track.paddingLeft, volume.left)
                assertEquals(song.left + track.right - track.paddingRight, volume.right)
                assertTrue(volume.top >= group.top + dp(50) + dp(66) + dp(4))
                assertTrue(volume.bottom <= root.height)
                assertTrue(volume.height >= dp(24))
                assertEquals(dp(if (height == 160) 40 else 44), volume.height)
                val shortRightTop = group.top + group.height - dp(12) - dp(44)
                assertEquals(shortRightTop, lyrics.top); assertEquals(shortRightTop, queue.top)
                names.subList(1, 4).forEach { name -> assertEquals(dp(66), group.findViewById<View>(ids.getValue(name)).height) }
            }
            val rightTop = group.top + group.height - dp(12) - dp(44)
            assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
            assertEquals(dp(44), lyrics.height); assertEquals(dp(44), queue.height)
            assertEquals(root.width - dp(44) - dp(44), queue.left)
            assertEquals(queue.left - dp(8) - dp(44), lyrics.left)
            assertEquals(dp(60), nativeFooter.height)
            song.translationX = dp(300).toFloat()
            components.update(1f, false)
            assertEquals(song.left + dp(300) + track.left + track.paddingLeft, volume.left)
            assertEquals(song.left + dp(300) + track.right - track.paddingRight, volume.right)
            assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
            assertEquals(dp(28), root.height - volume.bottom)
            songVisible = false; lyricsExpanded = false; right.visibility = View.INVISIBLE
            // Native queue crossfade keeps the centered host and its already placed footer.
            components.update(1f, true)
            assertEquals(0f, nativeOutput.alpha, 0f); assertFalse(nativeOutput.isClickable)
            assertEquals(song.left + dp(300) + track.left + track.paddingLeft, volume.left)
            assertEquals(View.INVISIBLE, right.visibility)
            components.update(1f, false)
            assertTrue(queue.isSelected); assertFalse(lyrics.isSelected)
            assertTrue(queue.isEnabled); assertTrue(lyrics.isEnabled)
            assertEquals(song.left + dp(300) + track.right - track.paddingRight, volume.right)
            assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
            assertEquals(View.INVISIBLE, right.visibility)
            song.translationX = 0f
            val motion = TabletLyricsPaneMotion(root, song, right, {})
            try {
                for (slide in listOf(.25f, .5f, .94f, .95f, 1f, .95f, .5f, .25f)) {
                    motion.apply(true, "QUEUE", slide, slide)
                    components.update(slide, false)
                    assertEquals(song.left + dp(300) + track.left + track.paddingLeft, volume.left)
                    assertEquals(song.left + dp(300) + track.right - track.paddingRight, volume.right)
                    assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
                    assertEquals(View.INVISIBLE, right.visibility)
                }
            } finally { motion.close() }
        } finally { components.close(); activity.finish() }
    }
    @Test fun lyricsAvailabilityFollowsTheVisibleRetainedNativeControlGroup() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song); root.addView(right)
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val ids = resourceIds(components, listOf("player_controls", "play_pause", "player_lyrics"))
        fun pane(enabled: Boolean, alpha: Float): Pair<FrameLayout, ImageView> {
            val group = FrameLayout(activity).apply { id = ids.getValue("player_controls"); this.alpha = alpha }
            val lyrics = ImageView(activity).apply { id = ids.getValue("player_lyrics"); isEnabled = enabled }
            group.addView(ImageView(activity).apply { id = ids.getValue("play_pause") })
            group.addView(lyrics); song.addView(group, FrameLayout.LayoutParams(500, 220))
            return group to lyrics
        }
        try {
            assertNull(components.lyricsAvailable())
            val outgoing = pane(true, .8f); val incoming = pane(false, .2f)
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1200, 800)
            components.prepare()
            assertEquals(0f, outgoing.second.alpha, 0f); assertFalse(outgoing.second.isClickable)
            assertTrue(outgoing.second.isEnabled); assertFalse(incoming.second.isEnabled)
            assertEquals(true, components.lyricsAvailable())
            outgoing.first.alpha = .2f; incoming.first.alpha = .8f
            assertEquals(false, components.lyricsAvailable())
            incoming.second.isEnabled = true
            assertEquals(true, components.lyricsAvailable())
        } finally { components.close(); activity.finish() }
    }
    @Test fun currentItemAvailabilityOverridesOldLyricsAndAnEnabledRetainedButton() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(504, 800).apply { leftMargin = 48 })
        root.addView(right, FrameLayout.LayoutParams(568, 800).apply { leftMargin = 616 })
        val oldLyrics = TextView(activity).apply { text = "Lyrics from the previous song" }
        right.addView(oldLyrics)
        var currentAvailable: Boolean? = true
        var clicks = 0
        val components = TabletPlayerComponents(root, song, right, { true }, { currentAvailable == true },
            { clicks++ }, currentLyricsAvailability = { currentAvailable })
        val names = listOf("player_controls", "play_pause", "previous_rewind", "next_fast_forward",
            "player_lyrics", "player_queue", "media_route_button", "seek_bar_controls")
        val ids = resourceIds(components, names)
        val group = FrameLayout(activity).apply { id = ids.getValue("player_controls") }
        song.addView(group, FrameLayout.LayoutParams(504, 220, Gravity.BOTTOM))
        names.subList(1, 4).forEach { name -> group.addView(ImageView(activity).apply { id = ids.getValue(name) },
            FrameLayout.LayoutParams(66, 66).apply { topMargin = 50 }) }
        names.subList(4, 7).forEach { name -> group.addView(ImageView(activity).apply { id = ids.getValue(name) },
            FrameLayout.LayoutParams(60, 60, Gravity.BOTTOM).apply { bottomMargin = 14 }) }
        group.addView(FrameLayout(activity).apply { id = ids.getValue("seek_bar_controls") },
            FrameLayout.LayoutParams(504, 41))
        try {
            root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1200, 800)
            components.update(1f, false)
            val proxy = (root.getChildAt(2) as FrameLayout).getChildAt(0)
            val native = group.findViewById<View>(ids.getValue("player_lyrics"))
            assertTrue(proxy.isEnabled)
            proxy.performClick(); assertEquals(1, clicks)
            currentAvailable = false
            assertTrue(native.isEnabled)
            assertEquals("Lyrics from the previous song", oldLyrics.text.toString())
            assertEquals(false, components.lyricsAvailable())
            proxy.performClick(); assertEquals(1, clicks)
            components.update(1f, false)
            assertFalse(proxy.isEnabled); assertFalse(proxy.isSelected)
            right.visibility = View.INVISIBLE
            currentAvailable = true
            components.update(1f, false)
            assertTrue(proxy.isEnabled); assertTrue(proxy.isSelected)
            proxy.performClick(); assertEquals(2, clicks)
            currentAvailable = null
            components.update(1f, false)
            assertEquals(false, components.lyricsAvailable()); assertFalse(proxy.isEnabled)
            proxy.performClick(); assertEquals(2, clicks)
        } finally { components.close(); activity.finish() }
    }
}
