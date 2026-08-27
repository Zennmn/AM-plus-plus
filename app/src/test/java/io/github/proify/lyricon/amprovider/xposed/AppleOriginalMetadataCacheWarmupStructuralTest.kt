package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleOriginalMetadataCacheWarmupStructuralTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("Missing $relative")

    @Test
    fun startupWarmupNeverQueuesVisibleCacheHitsBehindTheFullCacheScan() {
        val source = source(
            "app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleOriginalMetadataCache.kt",
        )
        assertTrue(source.contains("private val interactiveExecutor"))
        assertTrue(source.contains("private val warmExecutor"))

        val warmStart = source.indexOf("fun warmRecentAsync")
        val warmEnd = source.indexOf("\n    fun put(", startIndex = warmStart)
        val warmFunction = source.substring(warmStart, warmEnd)
        assertTrue(warmFunction.contains("warmExecutor.execute"))
        assertFalse(warmFunction.contains("interactiveExecutor.execute"))

        val getStart = source.indexOf("fun get(")
        val getEnd = source.indexOf("\n    /**", startIndex = getStart)
        val getFunction = source.substring(getStart, getEnd)
        assertTrue(getFunction.contains("interactiveExecutor.execute"))
    }
}
