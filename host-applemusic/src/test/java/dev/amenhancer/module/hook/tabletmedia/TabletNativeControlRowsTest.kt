package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout.b
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletNativeControlRowsTest {
    private lateinit var activity: Activity
    private lateinit var rows: TabletNativeControlRows
    @Before fun setup() { activity = Robolectric.buildActivity(Activity::class.java).setup().get(); rows = TabletNativeControlRows() }
    @After fun cleanup() { rows.close(); activity.finish() }
    private fun view() = View(activity).apply {
        layoutParams = b(ViewGroup.LayoutParams(100, 66)).apply {
            i = -1; j = 11; k = 12; l = -1; t = 13; S = .25f; M = 2
            topMargin = 3; bottomMargin = 14; marginStart = 32
        }
        setPadding(11, 11, 11, 11)
    }
    @Test fun nativeTransportGetsNewBoundsWithoutLosingHorizontalConstraintsOrPercentage() {
        val view = view(); val original = view.layoutParams as b
        assertTrue(rows.transport(view, 51, 52, 7))
        assertSame(original, view.layoutParams)
        assertEquals(0, original.i); assertEquals(-1, original.j); assertEquals(-1, original.k); assertEquals(-1, original.l)
        assertEquals(13, original.t); assertEquals(32, original.marginStart)
        assertEquals(.25f, original.S, 0f); assertEquals(2, original.M)
        assertFalse(rows.transport(view, 51, 52, 7))
        rows.close()
        assertEquals(66, original.height); assertEquals(11, original.j); assertEquals(12, original.k)
        assertEquals(3, original.topMargin); assertEquals(14, original.bottomMargin)
        assertEquals(11, view.paddingTop)
    }
    @Test fun restoredRowsKeepLaterHostPercentageAndHorizontalChanges() {
        val view = view(); val p = view.layoutParams as b
        rows.transport(view, 51, 52, 7)
        p.S = .27f; p.marginStart = 35; p.t = 77
        rows.retain(emptyList())
        assertEquals(.27f, p.S, 0f); assertEquals(35, p.marginStart); assertEquals(77, p.t)
        assertEquals(66, p.height)
    }
    @Test fun replacedNativeParamsNeverReceiveOldSavedConstraints() {
        val view = view(); rows.transport(view, 51, 52, 7)
        val replacement = b(ViewGroup.LayoutParams(120, 80)).apply { j = 99; topMargin = 17 }
        view.layoutParams = replacement
        rows.close()
        assertSame(replacement, view.layoutParams); assertEquals(99, replacement.j); assertEquals(80, replacement.height)
    }
    @Test fun lyricViewportReservesTopAndBottomButtonsAndRestoresOriginalAnchors() {
        val view = view(); val p = view.layoutParams as b
        rows.lyricViewport(view, 92, 60)
        assertEquals(0, p.i); assertEquals(-1, p.j); assertEquals(-1, p.k); assertEquals(0, p.l)
        assertEquals(92, p.topMargin); assertEquals(60, p.bottomMargin)
        rows.close()
        assertEquals(11, p.j); assertEquals(12, p.k); assertEquals(-1, p.l)
        assertEquals(3, p.topMargin); assertEquals(14, p.bottomMargin)
    }
}
