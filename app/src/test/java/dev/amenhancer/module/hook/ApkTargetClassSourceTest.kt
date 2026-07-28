package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkTargetClassSourceTest {
    @Test
    fun `merges base and split dex names with stable deduplication`() {
        val reads = mutableListOf<String>()
        val source = ApkTargetClassSource(
            apkPaths = listOf("base.apk", "split_config.apk"),
            classLoader = javaClass.classLoader!!,
            readEntries = { path ->
                reads += path
                when (path) {
                    "base.apk" -> listOf("com.apple.Base", "com.example.Ignored", "com.apple.Shared")
                    else -> listOf("com.apple.SplitOnly", "com.apple.Shared")
                }
            },
        )

        assertEquals(
            listOf("com.apple.Base", "com.apple.Shared", "com.apple.SplitOnly"),
            source.classNames(),
        )
        assertEquals(listOf("base.apk", "split_config.apk"), reads)
    }
}
