package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mid-playback lyric delivery contract. The target no longer re-enters the
 * Fragment install path through an o2 item-update coordinator or a ready-late
 * ledger: every source (configured mappings, automatic fallback, cache
 * refresh and LLM translation) submits its replacement through the exact-profile
 * LyricsResultSubmissionBridge, which owns the host result/index contract.
 * Selection and recovery publish upstream, and the Fragment install hook
 * observes the owner, guards empty fallback and acknowledges installation.
 * No hook writes installer arguments or performs IO or native parsing.
 */
class CustomLyricsItemUpdateStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `target submits replacements through the result bridge and drops the o2 coordinator path`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("AppleMusicSymbols.LyricsInstallMethod"))
        assertTrue(target.contains("LyricsResultSubmissionBridge.create("))
        assertTrue(
            target.indexOf("AppleMusicSymbols.LyricsInstallMethod") <
                target.indexOf("LyricsResultSubmissionBridge.create("),
        )
        assertTrue(target.contains("registration.onClose(resultBridge::close)"))
        assertFalse(target.contains("AppleMusicSymbols.LyricsItemUpdateMethod"))
        assertFalse(target.contains("LyricsItemUpdateCoordinator("))
        assertFalse(target.contains("CustomLyricsReadyReapply("))
        assertFalse(target.contains("itemUpdateContext"))
        assertFalse(target.contains("readyReapply"))
    }

    @Test
    fun `install hook observes the owner and completed delivery without mutating installer arguments`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val installHook = target.substringAfter("val hooked = hook(installMethod")
            .substringBefore("val availabilityResolution")

        assertTrue(installHook.contains("resultBridge.attach("))
        assertTrue(installHook.contains("param.thisObject"))
        assertTrue(installHook.contains("resultBridge.onInstalled(fragment, param.args.getOrNull(0))"))
        assertTrue(installHook.contains("param.throwable == null"))
        assertFalse(Regex("""param\.args\[0\]\s*=(?!=)""").containsMatchIn(installHook))
        assertFalse(installHook.contains("param.extras"))
        assertFalse(installHook.contains("mLyricsResult"))
        assertFalse(installHook.contains("loadLyrics"))
        assertFalse(installHook.contains("onResume"))
        assertFalse(installHook.contains("openRemoteFile"))
        assertFalse(installHook.contains("readTtml"))
        assertFalse(installHook.contains("parseTtml"))
        assertFalse(installHook.contains("HttpLyricTransport"))
        assertFalse(target.contains("installMethod.invoke"))
        assertFalse(Regex("""param\.args\[0\]\s*=(?!=)""").containsMatchIn(target))
        assertFalse(target.contains("ampp-installed-lyrics"))
    }

    @Test
    fun `every replacement source publishes through the bridge on the main thread`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("CustomLyricsReplacementSession("))
        assertTrue(target.contains("AutoLyricsReplacementSession("))
        assertTrue(target.contains("LlmLyricsReplacementSession("))
        assertTrue(target.contains("Handler(Looper.getMainLooper())"))
        assertTrue(target.contains("mainHandler.post {"))
        assertTrue(target.contains("resultBridge.onReplacementPublished(appleMusicId)"))
        assertEquals(
            // Configured lyrics, automatic fallback, cache refresh, LLM and
            // native-ID submission repair each publish.
            5,
            Regex("resultBridge\\.onReplacementPublished").findAll(target).count(),
        )
        assertTrue(
            target.indexOf("CustomLyricsReplacementSession(") <
                target.indexOf("LyricsResultSubmissionBridge.create("),
        )
    }

    @Test
    fun `selection resolves the current document before publication and unwraps the pointer`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("val selectReplacement: (Long, Long, Any?) -> Any?"))
        assertTrue(target.contains("selectReplacement = selectReplacement,"))
        assertTrue(target.contains("session.replacementFor(id)"))
        assertTrue(target.contains("autoSession?.takeoverReplacementFor("))
        assertTrue(target.contains("autoSession?.markTakeoverApplied(id)"))
        assertTrue(target.contains("parser.forSubmission(manual ?: automatic ?: translated, id, nativeId)"))
        assertTrue(
            target.indexOf("val selectReplacement:") <
                target.indexOf("LyricsResultSubmissionBridge.create("),
        )
    }

    @Test
    fun `identity changes and lifecycle close flow through the bridge`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("resultBridge.onSongChanged(appleMusicId)"))
        assertTrue(target.contains("currentSong.addListener"))
        assertTrue(target.contains("current?.details?.appleMusicId"))
        assertTrue(target.contains("registration.onClose(identitySubscription::close)"))
    }

    @Test
    fun `host hooks perform no io native parse or lyric state mutation`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        val parseHook = target.substringAfter("hook(parseMethod, object : ModernMethodHook()")
            .substringBefore("val hooked = hook(installMethod")
        val availabilityHook = target.substringAfter(
            "hook(availabilityMethod, object : ModernMethodHook()",
        )

        for (hookPath in listOf(parseHook, availabilityHook)) {
            assertFalse(hookPath.contains("openRemoteFile"))
            assertFalse(hookPath.contains("readTtml"))
            assertFalse(hookPath.contains("parseTtml"))
            assertFalse(hookPath.contains("HttpLyricTransport"))
            assertFalse(hookPath.contains("mLyricsResult"))
            assertFalse(hookPath.contains("loadLyrics"))
            assertFalse(hookPath.contains("onResume"))
        }
        assertTrue(availabilityHook.contains("shouldExposeCustomLyrics("))
    }

    @Test
    fun `automatic fallback is prewarmed from observed native metadata and manual ready wins`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("metadataOfAppleMusicId(id)"))
        assertTrue(target.contains("shouldTryAutoLyricsForMetadata(metadata)"))
        assertTrue(target.contains("session.readyReplacementFor(appleMusicId) == null"))
    }

    @Test
    fun `o2 symbol stays profiled for the standalone coordinator contract`() {
        val symbols = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt",
        )

        assertTrue(symbols.contains("LyricsItemUpdateMethod"))
        assertTrue(symbols.contains("TargetSymbolId.LYRICS_ITEM_UPDATE_METHOD"))
        assertEquals(
            // One pin per host profile that still routes o2: 6.5.0-1580,
            // 6.5.1-1583, 6.5.2-1586 and 6.5.3-1599. The 7.0.0-beta profiles
            // route b2 instead.
            4,
            dev.amenhancer.host.applemusic.AppleMusicHostProfiles.all.count {
                it.document.getJSONObject("indexed").getJSONObject("methods")
                    .optString("LYRICS_ITEM_UPDATE_METHOD") == "o2"
            },
        )
    }
}
