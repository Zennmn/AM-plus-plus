package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsReadyLateStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `i2 no longer records a ready late miss and the hook path avoids io and native parse`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val installHook = target.substringAfter("val hooked = hook(installMethod")
            .substringBefore("val availabilityResolution")

        assertTrue(installHook.contains("resultBridge.attach("))
        assertTrue(installHook.contains("param.thisObject"))
        assertFalse(installHook.contains("shouldRecordReadyLateMiss"))
        assertFalse(installHook.contains("session.isTracking"))
        assertFalse(installHook.contains("readyReapply"))
        assertFalse(installHook.contains("openRemoteFile"))
        assertFalse(installHook.contains("readTtml"))
        assertFalse(installHook.contains("parseTtml"))
        assertFalse(target.contains("CustomLyricsReadyReapply("))
        assertFalse(target.contains("installMethod.invoke"))
    }

    @Test
    fun `ready late publication hops to the main thread before reaching the result bridge`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("Handler(Looper.getMainLooper())"))
        assertTrue(target.contains("mainHandler.post {"))
        assertTrue(target.contains("resultBridge.onReplacementPublished(appleMusicId)"))
        assertTrue(target.contains("LyricsResultSubmissionBridge.create("))
        assertTrue(target.contains("currentSong.addListener"))
        assertTrue(target.contains("current?.details?.appleMusicId"))
        assertTrue(target.contains("session::ensureRequested"))
    }

    @Test
    fun `reapply ledger is weakly identity keyed, consumes before re-entry, and can be dismissed`() {
        val reapply = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/CustomLyricsReadyReapply.kt",
        )

        assertTrue(reapply.contains("mutableListOf<PendingMiss>()"))
        assertTrue(reapply.contains("WeakReference<Any>"))
        assertTrue(reapply.contains("synchronized(pending)"))
        assertTrue(reapply.contains("iterator.remove()"))
        assertTrue(reapply.contains("installMethod.invoke(fragment, replacement)"))
        assertTrue(reapply.contains("val fragmentAdamId = seam.currentItemAdamIdOf(fragment)"))
        assertTrue(reapply.contains("fragmentAdamId != appleMusicId"))
        assertTrue(
            reapply.indexOf("val replacement = readyReplacementFor(appleMusicId)") <
                reapply.indexOf("val fragmentAdamId = seam.currentItemAdamIdOf(fragment)"),
        )
        assertTrue(reapply.contains("fun dismiss(fragment: Any)"))
        assertTrue(reapply.contains("shouldRecordReadyLateMiss"))
        assertFalse(reapply.contains("IdentityHashMap<Any, Long>"))
    }

    @Test
    fun `session publishes the callback only after the cache write`() {
        val session = projectFile(
            "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsReplacementSession.kt",
        )

        assertTrue(session.contains("onReplacementPublished"))
        assertTrue(
            session.indexOf("cache[key] = replacement") <
                session.indexOf("onReplacementPublished?.invoke(appleMusicId)"),
        )
    }
}
