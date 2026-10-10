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
    private class GuideParams(begin: Int) : FrameLayout.LayoutParams(0, 0) {
        @JvmField var guideBegin = begin
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
            actions.forEach { it.alpha = .8f }
            components.update(.5f, true)
            actions.forEach {
                assertEquals(0f, it.alpha, 0f); assertFalse(it.isClickable)
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
    @Test fun outputAlignsBelowVolumeWhileTheRightButtonsKeepTheirPositions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = kotlin.math.round(value * density).toInt()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(dp(504), dp(800)).apply { leftMargin = dp(48) })
        root.addView(right, FrameLayout.LayoutParams(dp(568), dp(800)).apply { leftMargin = dp(616) })
        var offset = 0f
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {}, { offset })
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
            val output = overlay.getChildAt(0); val lyrics = overlay.getChildAt(1)
            val queue = overlay.getChildAt(2); val volume = overlay.getChildAt(3)
            assertEquals(View.VISIBLE, volume.visibility)
            val play = group.findViewById<View>(ids.getValue("play_pause"))
            assertTrue(play.y < dp(50))
            assertTrue(play.y >= progress.bottom)
            assertEquals((root.height * .061f).toInt().coerceAtLeast(dp(55)), volume.top + volume.height / 2 - (song.top + group.top + play.y.toInt() + play.height / 2))
            assertTrue(volume.top < output.top)
            assertEquals(volume.left, output.left)
            assertTrue(output.top >= volume.bottom + dp(8))
            assertTrue(output.right <= volume.right)
            assertEquals(song.left + track.left + track.paddingLeft, volume.left)
            assertEquals(song.left + track.right - track.paddingRight, volume.right)
            assertEquals(dp(17), root.height - output.bottom)
            assertTrue(root.height - volume.bottom > dp(28))
            offset = 0f; song.translationX = 0f
            listOf(180, 160, 220).forEach { height ->
                group.layoutParams = (group.layoutParams as FrameLayout.LayoutParams).apply { this.height = dp(height) }
                root.measure(View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, dp(1200), dp(800))
                components.update(1f, false)
                assertEquals(View.VISIBLE, volume.visibility)
                assertEquals(song.left + track.left + track.paddingLeft, volume.left)
                assertEquals(song.left + track.right - track.paddingRight, volume.right)
                assertTrue(volume.top >= group.top + play.y.toInt() + play.height)
                assertTrue(volume.bottom <= root.height)
                assertTrue(volume.height >= dp(24))
                if (height < 220) assertEquals(View.INVISIBLE, output.visibility)
                else {
                    assertEquals(View.VISIBLE, output.visibility)
                    assertEquals(volume.left, output.left)
                    assertTrue(output.top >= volume.bottom + dp(8))
                }
                assertEquals(dp(44), volume.height)
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
            offset = dp(300).toFloat(); song.translationX = offset
            components.update(1f, false)
            assertEquals(volume.left, output.left)
            assertEquals(song.left + dp(300) + track.left + track.paddingLeft, volume.left)
            assertEquals(song.left + dp(300) + track.right - track.paddingRight, volume.right)
            assertEquals(rightTop, lyrics.top); assertEquals(rightTop, queue.top)
            assertEquals(dp(17), root.height - output.bottom)
            assertTrue(root.height - volume.bottom > dp(28))
            components.close()
            names.subList(1, 4).forEach { name ->
                val button = group.findViewById<View>(ids.getValue(name))
                assertEquals(0f, button.translationX, 0f); assertEquals(0f, button.translationY, 0f)
            }
        } finally { components.close(); activity.finish() }
    }
    @Test fun referenceRowsApplyBeforeQueueCrossfadeAndFitShorterWindowsWithoutResizingNativeSymbols() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = kotlin.math.round(value * density).toInt()
        val root = FrameLayout(activity); val song = FrameLayout(activity); val right = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(song, FrameLayout.LayoutParams(dp(504), -1).apply { leftMargin = dp(48) })
        root.addView(right, FrameLayout.LayoutParams(dp(568), -1).apply { leftMargin = dp(616) })
        val components = TabletPlayerComponents(root, song, right, { true }, { true }, {})
        val names = listOf("player_controls", "play_pause", "previous_rewind", "next_fast_forward",
            "player_lyrics", "player_queue", "media_route_button", "seek_bar_controls", "progress", "metadata_guideline_start")
        val ids = resourceIds(components, names)
        val guide = View(activity).apply { id = ids.getValue("metadata_guideline_start") }
        song.addView(guide)
        val guideParams = GuideParams(dp(16))
        guide.layoutParams = guideParams
        fun pane(): FrameLayout = FrameLayout(activity).apply {
            id = ids.getValue("player_controls"); setPadding(dp(32), 0, dp(32), 0)
            song.addView(this, FrameLayout.LayoutParams(-1, dp(250), Gravity.BOTTOM))
            names.subList(1, 4).forEachIndexed { index, name ->
                addView(ImageView(activity).apply {
                    id = ids.getValue(name); setPadding(dp(11), dp(11), dp(11), dp(11))
                }, FrameLayout.LayoutParams(dp(66), dp(66)).apply {
                    topMargin = dp(110); leftMargin = dp(110 + index * 99)
                })
            }
            names.subList(4, 7).forEach { name -> addView(ImageView(activity).apply { id = ids.getValue(name) },
                FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM).apply { bottomMargin = dp(14) }) }
            addView(FrameLayout(activity).apply {
                id = ids.getValue("seek_bar_controls")
                addView(View(activity).apply { id = ids.getValue("progress") }, FrameLayout.LayoutParams(-1, dp(12)))
            }, FrameLayout.LayoutParams(-1, dp(32)))
        }
        fun layout(height: Int) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dp(1200), dp(height))
        }
        fun verify(group: FrameLayout, expectedGap: Int? = null) {
            val play = group.findViewById<View>(ids.getValue("play_pause"))
            val progress = group.findViewById<View>(ids.getValue("seek_bar_controls"))
            val track = group.findViewById<View>(ids.getValue("progress"))
            assertEquals(dp(16), group.paddingLeft); assertEquals(dp(32), group.paddingRight)
            assertEquals(progress.left + track.width / 2, play.x.toInt() + play.width / 2)
            assertTrue(play.y >= progress.bottom)
            expectedGap?.let { assertEquals(it, play.y.toInt() + play.height / 2 - (progress.top + track.height / 2)) }
            names.subList(1, 4).forEach { name ->
                val button = group.findViewById<View>(ids.getValue(name))
                assertEquals(dp(66), button.width); assertEquals(dp(66), button.height)
                assertEquals(dp(11), button.paddingLeft); assertEquals(dp(11), button.paddingTop)
                assertEquals(play.y.toInt() + play.height / 2, button.y.toInt() + button.height / 2)
            }
        }
        val first = pane()
        try {
            assertTrue(components.prepare(first))
            layout(1000); components.update(1f, false)
            verify(first, dp(62)); assertEquals(0, guideParams.guideBegin)
            val overlay = root.getChildAt(2) as FrameLayout
            val output = overlay.getChildAt(0); val lyrics = overlay.getChildAt(1)
            val queue = overlay.getChildAt(2); val volume = overlay.getChildAt(3)
            val play = first.findViewById<View>(ids.getValue("play_pause"))
            assertEquals(dp(61), volume.top + volume.height / 2 - (first.top + play.y.toInt() + play.height / 2))
            assertEquals(dp(26), root.height - output.bottom)
            assertEquals(volume.left, output.left)
            assertTrue(volume.bottom <= output.top)
            assertEquals(dp(12), root.height - lyrics.bottom); assertEquals(lyrics.top, queue.top)
            components.update(1f, false); verify(first, dp(62))
            val incoming = pane()
            components.prepare(incoming); layout(1000)
            components.update(.5f, true)
            verify(first, dp(62)); verify(incoming, dp(62))
            listOf(first, incoming).forEach {
                assertEquals(0f, it.findViewById<View>(ids.getValue("player_lyrics")).alpha, 0f)
                it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).apply { height = dp(150) }
            }
            first.alpha = .4f; incoming.alpha = .8f
            layout(600); components.update(1f, false)
            verify(first); verify(incoming)
            val smallPlay = incoming.findViewById<View>(ids.getValue("play_pause"))
            assertEquals(View.VISIBLE, volume.visibility)
            assertTrue(volume.top >= incoming.top + smallPlay.y.toInt() + smallPlay.height)
            assertTrue(volume.bottom <= root.height)
            assertEquals(dp(44), volume.height); assertEquals(View.INVISIBLE, output.visibility)
            components.close()
            assertEquals(dp(16), guideParams.guideBegin)
            listOf(first, incoming).forEach { group ->
                assertEquals(dp(32), group.paddingLeft)
                names.subList(1, 4).forEach { name ->
                    val button = group.findViewById<View>(ids.getValue(name))
                    assertEquals(0f, button.translationX, 0f); assertEquals(0f, button.translationY, 0f)
                }
            }
        } finally { components.close(); activity.finish() }
    }
}
