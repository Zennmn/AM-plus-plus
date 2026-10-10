package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
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
        val components = TabletPlayerComponents(Any(), root, song, right, { true }, { true }, {})
        try {
            assertEquals(3, root.childCount)
            components.update(0f, false)
            assertEquals(android.view.View.INVISIBLE, root.getChildAt(2).visibility)
        } finally { components.close(); activity.finish() }
        assertEquals(2, root.childCount)
        assertSame(song, root.getChildAt(0)); assertSame(right, root.getChildAt(1))
    }
}
