package io.github.proify.lyricon.amprovider.xposed

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the resolver's host/CPU seam from an accidental main-thread regression. */
class AppleInternalCatalogResolverStructuralTest {
    private fun source(): String = sequenceOf(
        File("app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt"),
        File("../app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Missing resolver source")

    @Test
    fun `direct host query stays in main adapter while response work uses CPU seam`() {
        val resolver = source()
        assertTrue(resolver.contains("mainHandler.post {"))
        assertTrue(resolver.contains("access.directQueryMethod.invoke("))
        assertTrue(resolver.contains("catalogResponseDispatcher.newTask"))
        assertTrue(resolver.contains("snapshotCatalogResponse("))
        assertTrue(resolver.contains("transformOffMain = transformOffMain"))
        assertTrue(resolver.contains("CatalogResponseSnapshot"))
        assertTrue(resolver.contains("catalogBackgroundExecutor.execute"))
        assertTrue(
            resolver.contains(
                "isMainThread = { Looper.myLooper() == Looper.getMainLooper() }",
            ),
        )
    }

    @Test
    fun `continuation does not reflect arbitrary host result objects off main`() {
        val resolver = source()
        assertTrue(
            resolver.contains(
                "value.javaClass.name != \"" +
                    "kotlin.Result" + '\\' + "${'$'}Failure\"",
            ),
        )
        assertTrue(resolver.contains("responseTask.submit(response)"))
    }
}
