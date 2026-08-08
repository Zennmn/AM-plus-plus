package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 152 item-update (o2) hook contract tests: the o2 seam resolves only
 * through the profile-backed symbol, re-enters the exact I2 path with a ready
 * replacement after Apple has updated the current item, records ready-late
 * misses otherwise, and never performs IO, native parsing, or lyric-state
 * mutation on the hook path.
 */
class CustomLyricsItemUpdateStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `o2 hook resolves the profile backed symbol and registers after i2`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val symbols = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt",
        )

        assertTrue(target.contains("AppleMusicSymbols.LyricsItemUpdateMethod"))
        assertTrue(target.contains("LyricsItemUpdateCoordinator("))
        assertTrue(
            target.indexOf("AppleMusicSymbols.LyricsInstallMethod") <
                target.indexOf("AppleMusicSymbols.LyricsItemUpdateMethod"),
        )
        assertTrue(symbols.contains("LyricsItemUpdateMethod"))
        assertTrue(symbols.contains("TargetSymbolId.LYRICS_ITEM_UPDATE_METHOD"))
        assertEquals(
            2,
            Regex("TargetSymbolId\\.LYRICS_ITEM_UPDATE_METHOD to \"o2\"").findAll(symbols).count(),
        )
    }

    @Test
    fun `o2 hook enters a thread local context before apple and exits after`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("itemUpdateContext.enterO2()"))
        assertTrue(target.contains("itemUpdateContext.exitO2()"))
        assertTrue(target.contains("itemUpdateContext.appleInvokedI2DuringO2()"))
        assertTrue(target.contains("itemUpdateContext.markAppleInvokedI2()"))
    }

    @Test
    fun `o2 hook coordinates the exact current item and flags holder after apple ran`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val o2Hook = target.substringAfter(
            "override fun afterHookedMethod(param: MethodHookParam)",
        )

        assertTrue(o2Hook.contains("coordinator.onItemUpdate("))
        assertTrue(o2Hook.contains("param.thisObject"))
        assertTrue(o2Hook.contains("param.args.getOrNull(2)"))
    }

    @Test
    fun `o2 hook path never loads lyrics writes results or hooks resume`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val o2Hook = target.substringAfter(
            "AppleMusicSymbols.LyricsItemUpdateMethod",
        )

        assertFalse(o2Hook.contains("loadLyrics"))
        assertFalse(o2Hook.contains("onResume"))
        assertFalse(o2Hook.contains("mLyricsResult"))
        assertFalse(o2Hook.contains("openRemoteFile"))
        assertFalse(o2Hook.contains("readTtml"))
        assertFalse(o2Hook.contains("parseTtml"))
        assertFalse(o2Hook.contains("HttpLyricTransport"))
    }

    @Test
    fun `coordinator uses ready only lookup, the i2 identity seam, and the ready late ledger`() {
        val coordinator = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/LyricsItemUpdateCoordinator.kt",
        )

        assertTrue(coordinator.contains("fun decideLyricsItemUpdate("))
        assertTrue(coordinator.contains("enum class LyricsItemUpdateAction"))
        assertTrue(coordinator.contains("readyReplacementFor(appleMusicId)"))
        assertTrue(coordinator.contains("isTracking(appleMusicId)"))
        assertTrue(coordinator.contains("seam.currentItemAdamIdOf(fragment)"))
        assertFalse(coordinator.contains("seam.detailsOfItem(item)"))
        assertTrue(coordinator.contains("readyReapply.recordMiss(fragment, appleMusicId)"))
        assertTrue(coordinator.contains("readyReapply.dismiss(fragment)"))
        assertTrue(coordinator.contains("installMethod.invoke(fragment, replacement)"))
        assertTrue(coordinator.contains("WeakReference<Any>"))
        assertTrue(coordinator.contains("ThreadLocal"))
        assertFalse(coordinator.contains("ensureRequested"))
        assertFalse(coordinator.contains("openRemoteFile"))
        assertFalse(coordinator.contains("readTtml"))
        assertFalse(coordinator.contains("parseTtml"))
        assertFalse(coordinator.contains("HttpLyricTransport"))
        assertFalse(coordinator.contains("loadLyrics"))
        assertFalse(coordinator.contains("onResume"))
        assertFalse(coordinator.contains("mLyricsResult"))
    }
}
