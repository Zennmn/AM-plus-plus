package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLyricsRuntimeStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `automatic runtime keeps only the newest queued task`() {
        val source = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )

        assertTrue(source.contains("ArrayBlockingQueue(1)"))
        assertTrue(source.contains("ThreadPoolExecutor.DiscardOldestPolicy()"))
    }
}
