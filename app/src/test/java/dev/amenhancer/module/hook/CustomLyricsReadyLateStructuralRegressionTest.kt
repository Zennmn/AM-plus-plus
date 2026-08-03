package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsReadyLateStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `i2 records a ready late miss without io or native parse on the hook path`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val hookBody = target.substringAfter(
            "override fun beforeHookedMethod(param: MethodHookParam)",
        ).substringBefore("override fun afterHookedMethod(param: MethodHookParam)")

        assertTrue(hookBody.contains("shouldRecordReadyLateMiss(original, replacement)"))
        assertTrue(hookBody.contains("readyReapply.recordMiss(it, adamId)"))
        assertTrue(hookBody.contains("readyReapply.dismiss(it)"))
        assertFalse(hookBody.contains("openRemoteFile"))
        assertFalse(hookBody.contains("readTtml"))
        assertFalse(hookBody.contains("parseTtml"))
    }

    @Test
    fun `ready late callbacks hop to the main thread before re-entering i2`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("Handler(Looper.getMainLooper())"))
        assertTrue(target.contains("readyReapply.onReplacementPublished(appleMusicId)"))
        assertTrue(target.contains("CustomLyricsReadyReapply("))
    }

    @Test
    fun `reapply ledger is weakly identity keyed, consumes before re-entry, and can be dismissed`() {
        val reapply = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/CustomLyricsReadyReapply.kt",
        )

        assertTrue(reapply.contains("mutableListOf<PendingMiss>()"))
        assertTrue(reapply.contains("WeakReference<Any>"))
        assertTrue(reapply.contains("synchronized(pending)"))
        assertTrue(reapply.contains("iterator.remove()"))
        assertTrue(reapply.contains("installMethod.invoke(fragment, replacement)"))
        assertTrue(reapply.contains("seam.currentItemAdamIdOf(fragment) != appleMusicId"))
        assertTrue(reapply.contains("fun dismiss(fragment: Any)"))
        assertTrue(reapply.contains("shouldRecordReadyLateMiss"))
        assertFalse(reapply.contains("IdentityHashMap<Any, Long>"))
    }

    @Test
    fun `session publishes the callback only after the cache write`() {
        val session = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/CustomLyricsReplacementSession.kt",
        )

        assertTrue(session.contains("onReplacementPublished"))
        assertTrue(
            session.indexOf("cache[key] = replacement") <
                session.indexOf("onReplacementPublished?.invoke(appleMusicId)"),
        )
    }
}
