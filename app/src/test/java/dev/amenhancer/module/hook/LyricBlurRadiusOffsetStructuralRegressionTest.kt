package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricBlurRadiusOffsetStructuralRegressionTest {
    private fun source(fileName: String): String = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/$fileName"),
        File("app/src/main/java/dev/amenhancer/module/hook/$fileName"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$fileName was not found from the unit-test working directory")

    @Test
    fun `target setting is applied to directional focus blur before tablet edge blur`() {
        val target = source("AppleMusicBidirectionalLyricBlurTarget.kt")
        val runtime = source("OpenSourceLyricBlurPort.kt").replace(Regex("\\s+"), " ")

        assertTrue(target.contains("TargetConfigClient.currentSettings().lyricBlurRadiusOffsetPx"))
        assertTrue(runtime.contains("BidirectionalBlurPolicy.applyRadiusOffset("))
        assertTrue(runtime.contains(
            "TabletLyricVisualPolicy.mergeBlurRadius(focusBlur, edgeBlur)",
        ))
    }
}
