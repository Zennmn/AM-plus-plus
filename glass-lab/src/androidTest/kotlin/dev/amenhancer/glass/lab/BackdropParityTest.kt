package dev.amenhancer.glass.lab

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a hardware accelerated API 33+ device. Never uses software View.draw screenshots. */
@RunWith(AndroidJUnit4::class)
class BackdropParityTest {
    @Test fun nativeBackdropMatchesOriginalAcrossThemesAndScrolledContent() {
        ActivityScenario.launch(GlassLabActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for (offset in listOf(0f, 37f)) {
                    scenario.onActivity { it.setScenario(false, dark, offset) }
                    val original = capture(scenario)
                    scenario.onActivity { it.setScenario(true, dark, offset) }
                    val bridge = capture(scenario)
                    val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "glass-parity").apply { mkdirs() }
                    File(output, "reference-$dark-$offset.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(output, "bridge-$dark-$offset.png").outputStream().use { bridge.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    var totalError = 0L
                    var compared = 0L
                    // Stage is identical; include both surfaces and their background context.
                    for (y in original.height / 2 until original.height) for (x in 0 until original.width) {
                        val a = original.getPixel(x, y)
                        val b = bridge.getPixel(x, y)
                        for (shift in listOf(0, 8, 16)) { totalError += abs((a shr shift and 255) - (b shr shift and 255)); compared++ }
                    }
                    val meanError = totalError.toDouble() / compared
                    original.recycle(); bridge.recycle()
                    assertTrue("Mean RGB error $meanError exceeds 1.5/255 (dark=$dark offset=$offset); see $output", meanError <= 1.5)
                }
            }
        }
    }

    private fun capture(scenario: ActivityScenario<GlassLabActivity>): Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Allow initial layer recording/composition to settle without advancing an animation.
        Thread.sleep(300)
        val latch = CountDownLatch(1)
        var status = -1
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val stage = activity.stageForCapture()
            val pos = IntArray(2).also(stage::getLocationInWindow)
            val result = Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888)
            bitmap = result
            PixelCopy.request(activity.window, Rect(pos[0], pos[1], pos[0] + stage.width, pos[1] + stage.height), result, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy did not complete successfully", latch.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS)
        return requireNotNull(bitmap)
    }
}
