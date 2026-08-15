package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    "base.apk" -> listOf(
                        "com.apple.Base",
                        "com.example.Ignored",
                        "com.apple.Shared",
                        "J5.a",
                    )
                    else -> listOf("com.apple.SplitOnly", "com.apple.Shared", "T8.a")
                }
            },
        )

        assertEquals(
            listOf("J5.a", "T8.a", "com.apple.Base", "com.apple.Shared", "com.apple.SplitOnly"),
            source.classNames(),
        )
        assertEquals(listOf("base.apk", "split_config.apk"), reads)
    }

    @Test
    fun `only reviewed shape obfuscated top level names enter the index`() {
        assertTrue(isObfuscatedTopLevelClass("J5.a"))
        assertTrue(isObfuscatedTopLevelClass("G5.g"))
        assertFalse(isObfuscatedTopLevelClass("a.b"))
        assertFalse(isObfuscatedTopLevelClass("a.b.c"))
        assertFalse(isObfuscatedTopLevelClass("J5.a\$b"))
    }
}
