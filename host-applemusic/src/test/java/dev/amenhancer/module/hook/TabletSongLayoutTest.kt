package dev.amenhancer.module.hook

import android.app.Activity
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
class TabletSongLayoutTest {
    private lateinit var activity: HostActivity
    private lateinit var host: FrameLayout
    private lateinit var views: List<View>
    private lateinit var params: List<b>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        host = FrameLayout(activity)
        params = names.map { b(ViewGroup.LayoutParams(0, 0)).apply {
            t = 0; l = 0; M = 2; S = .345f; a = 16
            marginStart = 16; leftMargin = 16; marginEnd = 8
        } }
        views = names.mapIndexed { index, _ -> View(activity).apply {
            id = BASE_ID + index
            host.addView(this, FrameLayout.LayoutParams(400, 40))
            layoutParams = params[index]
        } }
    }

    @After fun tearDown() { activity.finish() }

    @Test fun usesObfuscatedFieldsWithoutDroppingNativeConstraints() {
        assertNotNull(ConstraintLayoutPane.configureTabletSongLayout(host))
        assertEquals(.25f, params[0].S, 0f)
        assertEquals(16, params[1].a)
        views.forEachIndexed { index, view ->
            assertSame(params[index], view.layoutParams)
            assertEquals(0, params[index].t)
            assertEquals(0, params[index].l)
            assertEquals(2, params[index].M)
        }
        for (index in 2..3) {
            assertEquals(16, params[index].marginStart)
            assertEquals(16, params[index].leftMargin)
            assertEquals(8, params[index].marginEnd)
        }
    }

    @Test fun restoresChangedValuesWhileRetainingLaterNativeUpdates() {
        val restore = checkNotNull(ConstraintLayoutPane.configureTabletSongLayout(host))
        params[2].bottomMargin = 35
        params[2].l = 77
        restore()
        assertEquals(.345f, params[0].S, 0f)
        assertEquals(16, params[1].a)
        for (index in 2..3) {
            assertEquals(16, params[index].marginStart)
            assertEquals(16, params[index].leftMargin)
        }
        assertSame(params[2], views[2].layoutParams)
        assertEquals(35, params[2].bottomMargin)
        assertEquals(77, params[2].l)
    }

    @Test fun missingControlsDoesNotChangeExistingConstraints() {
        host.removeView(views[0])
        assertNull(ConstraintLayoutPane.configureTabletSongLayout(host))
        assertEquals(.345f, params[0].S, 0f)
        assertEquals(16, params[2].marginStart)
    }

    class HostActivity : Activity() {
        private var lookup: Resources? = null
        override fun getResources(): Resources = lookup ?: LookupResources(super.getResources()).also { lookup = it }
    }

    private class LookupResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int =
            names.indexOf(name).takeIf { it >= 0 }?.let { BASE_ID + it }
                ?: super.getIdentifier(name, defType, defPackage)
    }

    companion object {
        private const val BASE_ID = 0x234000
        private val names = listOf("player_controls", "metadata_guideline_start", "title", "subtitle")
    }
}
