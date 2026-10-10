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
    @Test fun compactTransportUsesNativeIntrinsicSymbolsAndRestoresTheHostScaleType() {
        val image = android.widget.ImageView(activity).apply {
            layoutParams = view().layoutParams
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
        }
        val nativeDrawable = image.drawable
        rows.transport(image, 51, 52, 7)
        assertSame(nativeDrawable, image.drawable)
        assertEquals(android.widget.ImageView.ScaleType.CENTER, image.scaleType)
        rows.close()
        assertSame(nativeDrawable, image.drawable)
        assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, image.scaleType)
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
    @Test fun smallerMetadataGlyphsKeepTheCoverBarrierHeightAndNativeStartInset() {
        val text = android.widget.TextView(activity).apply {
            textSize = 22f; minimumHeight = 0
            layoutParams = b(ViewGroup.LayoutParams(200, -2)).apply { marginStart = 16 }
            layout(0, 0, 200, 66)
        }
        val originalSize = text.textSize
        assertTrue(rows.text(text, 18f, true))
        assertEquals(66, text.minimumHeight); assertEquals(16, (text.layoutParams as b).marginStart)
        assertTrue(text.textSize < originalSize)
        assertFalse(rows.text(text, 18f, true))
        rows.close()
        assertEquals(originalSize, text.textSize, 0f); assertEquals(0, text.minimumHeight)
    }
}
