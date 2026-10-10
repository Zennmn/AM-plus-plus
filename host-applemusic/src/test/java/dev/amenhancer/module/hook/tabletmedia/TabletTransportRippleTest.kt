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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletTransportRippleTest {
    private fun color(drawable: RippleDrawable): ColorStateList =
        ReflectionHelpers.getField(ReflectionHelpers.getField<Any>(drawable, "mState"), "mColor")
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
            assertEquals(31, background.radius)
            assertEquals(51, Color.alpha(color(background).defaultColor))
            assertEquals(66, view.width); assertEquals(68, view.height)
            assertSame(icon, view.drawable); assertSame(params, view.layoutParams)
            assertEquals(11, view.paddingTop); assertEquals(11, view.paddingBottom)
            assertTrue(view.performClick()); assertEquals(1, clicks)
            // A native layout change scales the symbol; keep the same small outer allowance.
            layout(view, 88, 90); style.apply(view)
            assertEquals(42, background.radius)
            view.setImageDrawable(GradientDrawable().apply { setSize(36, 36) })
            layout(view, 66, 68); style.apply(view)
            assertEquals(30, background.radius)
        } finally { style.close(); activity.finish() }
    }

    @Test fun sharedNativeStateStaysUnchangedAndRetainedBackgroundRestoresOnClose() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val style = TabletTransportRipple(); val view = button(activity)
        val background = view.background as RippleDrawable
        val other = background.constantState!!.newDrawable() as RippleDrawable
        val originalRadius = background.radius
        val originalColor = color(background)
        try {
            style.apply(view)
            val applied = view.background as RippleDrawable
            assertNotSame(background, applied)
            assertNotEquals(originalRadius, applied.radius)
            assertEquals(51, Color.alpha(color(applied).defaultColor))
            assertEquals(originalColor.defaultColor, color(other).defaultColor)
            assertEquals(originalRadius, other.radius)
            assertSame(view, applied.callback)
            style.retain(emptyList())
            assertEquals(originalRadius, background.radius)
            assertSame(background, view.background)
            assertSame(originalColor, color(background))
            style.apply(view); style.close()
            assertEquals(originalRadius, background.radius)
            assertSame(background, view.background)
            assertSame(originalColor, color(background))
            val replacement = RippleDrawable(ColorStateList.valueOf(Color.RED), null, null).apply { radius = 40 }
            style.apply(view); view.background = replacement; style.close()
            assertSame(replacement, view.background); assertEquals(40, replacement.radius)
            assertEquals(Color.RED, color(replacement).defaultColor)
        } finally { style.close(); activity.finish() }
    }
    @Test fun softerRipplePreservesTheOriginalSelectorAndReusesItsAppliedDrawable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val style = TabletTransportRipple(); val view = button(activity)
        val pressed = intArrayOf(android.R.attr.state_pressed)
        val colors = ColorStateList(arrayOf(pressed, intArrayOf()), intArrayOf(Color.CYAN, Color.WHITE))
        val background = view.background as RippleDrawable
        background.setColor(colors)
        try {
            style.apply(view)
            assertSame(colors, color(background))
            val applied = view.background as RippleDrawable
            assertEquals(Color.argb(51, 255, 255, 255), color(applied).defaultColor)
            style.apply(view); assertSame(applied, view.background)
            style.close(); assertSame(background, view.background)
            assertSame(colors, color(background))
        } finally { style.close(); activity.finish() }
    }
}
