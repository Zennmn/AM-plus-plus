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
    @Test fun restoredRowsKeepLaterHostPercentageAndHorizontalChanges() {
        val view = view(); val p = view.layoutParams as b
        rows.lyricViewport(view, 51, 7)
        p.S = .27f; p.marginStart = 35; p.t = 77
        rows.retain(emptyList())
        assertEquals(.27f, p.S, 0f); assertEquals(35, p.marginStart); assertEquals(77, p.t)
        assertEquals(66, p.height)
    }
    @Test fun replacedNativeParamsNeverReceiveOldSavedConstraints() {
        val view = view(); rows.lyricViewport(view, 51, 7)
        val replacement = b(ViewGroup.LayoutParams(120, 80)).apply { j = 99; topMargin = 17 }
        view.layoutParams = replacement
        rows.close()
        assertSame(replacement, view.layoutParams); assertEquals(99, replacement.j); assertEquals(80, replacement.height)
    }
    @Test fun lyricViewportKeepsBottomFadeAtTheFullPaneEdgeAndRestoresOriginalAnchors() {
        val view = view(); val p = view.layoutParams as b
        rows.lyricViewport(view, 92, 0)
        assertEquals(0, p.i); assertEquals(-1, p.j); assertEquals(-1, p.k); assertEquals(0, p.l)
        assertEquals(92, p.topMargin); assertEquals(0, p.bottomMargin)
        rows.close()
        assertEquals(11, p.j); assertEquals(12, p.k); assertEquals(-1, p.l)
        assertEquals(3, p.topMargin); assertEquals(14, p.bottomMargin)
    }
    @Test fun queuePercentUsesTheSameHeightAsSongAndRestoresOnlyItsOwnField() {
        val view = view(); val p = view.layoutParams as b
        p.height = 0; p.S = .345f
        assertTrue(rows.percentage(view, .25f))
        assertEquals(.25f, p.S, 0f)
        p.marginStart = 41; p.k = 77
        assertFalse(rows.percentage(view, .25f))
        rows.close()
        assertEquals(.345f, p.S, 0f); assertEquals(41, p.marginStart); assertEquals(77, p.k)
    }
    @Test fun smallerStartInsetKeepsNativeMarginsAndEndPaddingAndRestoresOnlyOwnedValues() {
        val view = view(); val p = view.layoutParams as b
        val density = activity.resources.displayMetrics.density
        val padding = (32 * density).toInt()
        view.setPadding(padding, 5, padding, 6)
        assertTrue(rows.startInset(view))
        assertEquals(padding - (16 * density).toInt(), view.paddingLeft)
        assertEquals(padding, view.paddingRight); assertEquals(32, p.marginStart)
        assertFalse(rows.startInset(view))
        val guide = view(); val gp = guide.layoutParams as b
        assertTrue(rows.startInset(guide, guideline = true)); assertEquals(0, gp.a)
        p.topMargin = 40; view.setPadding(view.paddingLeft, 7, 50, 8)
        rows.close()
        assertEquals(padding, view.paddingLeft); assertEquals(7, view.paddingTop)
        assertEquals(50, view.paddingRight); assertEquals(8, view.paddingBottom)
        assertEquals(40, p.topMargin); assertEquals(32, p.marginStart); assertEquals(-1, gp.a)
        assertSame(p, view.layoutParams); assertSame(gp, guide.layoutParams)
    }
}
