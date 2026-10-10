package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.ImageButton
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletNativeActionStyleTest {
    @Test fun nativeSelectorAndTintAreReusedWithoutSharingStateOrStealingItsCallback() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val native = ImageView(activity)
            val selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Color.RED))
                addState(intArrayOf(), ColorDrawable(Color.BLUE))
            }
            native.setImageDrawable(selector)
            native.imageTintList = ColorStateList.valueOf(Color.WHITE)
            val target = ImageButton(activity)
            val style = TabletNativeActionStyle()
            style.bind(target, native)
            val copied = target.drawable as StateListDrawable
            assertNotSame(selector, copied)
            assertSame(native, selector.callback)
            assertSame(native.imageTintList, target.imageTintList)
            target.isSelected = true
            assertEquals(Color.RED, (copied.current as ColorDrawable).color)
            assertEquals(Color.BLUE, (selector.current as ColorDrawable).color)
            style.bind(target, native)
            assertSame(copied, target.drawable)
            val replacement = ColorDrawable(Color.GREEN)
            native.setImageDrawable(replacement)
            style.bind(target, native)
            assertNotSame(copied, target.drawable)
            assertEquals(Color.GREEN, (target.drawable as ColorDrawable).color)
            assertSame(native, replacement.callback)
            style.clear()
        } finally { activity.finish() }
    }
}
