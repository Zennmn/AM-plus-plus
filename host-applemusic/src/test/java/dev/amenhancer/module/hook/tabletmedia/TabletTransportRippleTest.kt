package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletTransportRippleTest {
    private fun button(activity: Activity) = ImageView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(66, 68)
        setPadding(11, 11, 11, 11)
        setImageDrawable(GradientDrawable().apply { setSize(44, 46) })
        background = RippleDrawable(ColorStateList.valueOf(Color.WHITE), null, null)
    }
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun radiusTracksTheRenderedIconAndKeepsNativeImageAndClickGeometry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val style = TabletTransportRipple()
        val view = button(activity); val icon = view.drawable; val params = view.layoutParams
        var clicks = 0; view.setOnClickListener { clicks++ }
        try {
            layout(view, 66, 68); style.apply(view)
            val background = view.background as RippleDrawable
            assertEquals(27, background.radius)
            assertEquals(66, view.width); assertEquals(68, view.height)
            assertSame(icon, view.drawable); assertSame(params, view.layoutParams)
            assertEquals(11, view.paddingTop); assertEquals(11, view.paddingBottom)
            assertTrue(view.performClick()); assertEquals(1, clicks)
            // A native layout change scales the symbol; keep the same small outer allowance.
            layout(view, 88, 90); style.apply(view)
            assertEquals(38, background.radius)
            view.setImageDrawable(GradientDrawable().apply { setSize(36, 36) })
            layout(view, 66, 68); style.apply(view)
            assertEquals(26, background.radius)
        } finally { style.close(); activity.finish() }
    }

    @Test fun sharedNativeStateStaysUnchangedAndRetainedBackgroundRestoresOnClose() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val style = TabletTransportRipple(); val view = button(activity)
        val background = view.background as RippleDrawable
        val other = background.constantState!!.newDrawable() as RippleDrawable
        val originalRadius = background.radius
        try {
            style.apply(view)
            assertNotEquals(originalRadius, background.radius)
            assertEquals(originalRadius, other.radius)
            assertSame(view, background.callback)
            style.retain(emptyList())
            assertEquals(originalRadius, background.radius)
            style.apply(view); style.close()
            assertEquals(originalRadius, background.radius)
            val replacement = RippleDrawable(ColorStateList.valueOf(Color.RED), null, null).apply { radius = 40 }
            style.apply(view); view.background = replacement; style.close()
            assertSame(replacement, view.background); assertEquals(40, replacement.radius)
        } finally { style.close(); activity.finish() }
    }
}
