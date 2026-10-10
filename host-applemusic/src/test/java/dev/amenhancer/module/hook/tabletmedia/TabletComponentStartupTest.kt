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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletComponentStartupTest {
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
        val ids = names.associateWith { View.generateViewId() }
        @Suppress("UNCHECKED_CAST")
        (components.javaClass.getDeclaredField("ids").apply { isAccessible = true }.get(components) as MutableMap<String, Int>)
            .putAll(ids)
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
}
