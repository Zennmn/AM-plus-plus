package dev.amenhancer.module.hook.tabletmedia

import android.app.Activity
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TabletAudioOutputViewTest {
    private lateinit var activity: Activity
    private lateinit var icon: IosDeviceOutputDrawable
    private lateinit var view: TabletAudioOutputView
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        icon = IosDeviceOutputDrawable.Factory(TabletMediaAssets, activity.resources).create()
        view = TabletAudioOutputView(activity, icon)
    }
    @After fun close() { activity.finish() }

    @Test fun visibleDeviceNameAndGlyphUpdateTogetherAndTheWholeRowOpensOutput() {
        view.bind(AudioOutputState("MOONDROP PILL", OutputDeviceIcon.HEADPHONES))
        assertEquals("MOONDROP PILL", view.text.toString())
        assertEquals("音频输出 · MOONDROP PILL", view.contentDescription)
        assertEquals(OutputDeviceIcon.HEADPHONES, icon.kind)
        assertSame(icon, view.compoundDrawables[0])
        assertEquals(dp(24), icon.intrinsicWidth); assertEquals(dp(12), view.compoundDrawablePadding)
        assertEquals(0, view.paddingLeft)
        var clicked = false
        view.setOnClickListener { clicked = true }
        assertTrue(view.performClick()); assertTrue(clicked)
        assertTrue(view.isFocusable)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longNamesStayOnOneLineAndEllipsizeInsideTheAvailableColumn() {
        view.bind(AudioOutputState("Very long Bluetooth device name ".repeat(8), OutputDeviceIcon.SPEAKER))
        assertTrue(view.preferredWidth() > dp(160))
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, dp(160), dp(44))
        assertEquals(dp(160), view.width); assertEquals(dp(44), view.height)
        assertEquals(1, view.layout.lineCount)
        assertTrue(view.layout.getEllipsisCount(0) > 0)
        assertEquals(OutputDeviceIcon.SPEAKER, icon.kind)
    }
    @Test fun disconnectAndLaterRouteChangesReplaceTheOldNameAndRecalculateWidth() {
        view.bind(AudioOutputState("A long named Bluetooth output", OutputDeviceIcon.HEADPHONES))
        val wide = view.preferredWidth()
        view.bind(AudioOutputRouting.presentation(AudioOutputRoute(AudioOutputKind.HEADPHONES, "USB DAC")))
        assertEquals("USB DAC", view.text.toString()); assertEquals(OutputDeviceIcon.HEADPHONES, icon.kind)
        view.bind(AudioOutputState(null, OutputDeviceIcon.SYSTEM))
        assertEquals("本机", view.text.toString()); assertEquals(OutputDeviceIcon.SYSTEM, icon.kind)
        assertTrue(view.preferredWidth() < wide)
        view.bind(AudioOutputState("AirPods Pro", OutputDeviceIcon.AIRPODS))
        assertEquals("AirPods Pro", view.text.toString()); assertEquals(OutputDeviceIcon.AIRPODS, icon.kind)
        assertEquals("音频输出 · AirPods Pro", view.contentDescription)
    }
}
