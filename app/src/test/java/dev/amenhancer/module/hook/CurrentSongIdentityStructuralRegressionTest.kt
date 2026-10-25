package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSongIdentityStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `caches the identity in memory without storage or network on the hook path`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCurrentSongIdentityTarget.kt",
        )
        val feature = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/CurrentSongIdentityFeature.kt",
        )

        assertTrue(target.contains("CurrentItemIdentitySeam(symbols)"))
        assertTrue(target.contains("private val cache: CurrentSongIdentityCache"))
        assertTrue(target.contains("PlayerMetadataPublishMethod"))
        assertTrue(target.contains("MetadataToPlaybackItemMethod"))
        assertTrue(target.contains("cache.publish(item, seam.detailsOfItem(item), seam.persistentIdOf(item))"))
        assertFalse(target.contains("SharedPreferences"))
        assertFalse(target.contains("openRemoteFile"))
        assertFalse(target.contains("HttpLyricTransport"))
        assertFalse(target.contains("AmLyricsClient"))
        assertFalse(target.contains("java.io.File"))
        assertFalse(target.contains("embedded-payload"))
        assertFalse(target.contains("com.apple.android.music.amplus"))
        assertFalse(feature.contains("config.settings()"))
    }

    @Test
    fun `reuses the verified current item seam instead of duplicating it`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("val seam = CurrentItemIdentitySeam(symbols)"))
        assertTrue(target.contains("seam.resolve(installMethod)"))
        assertTrue(target.contains("LyricsResultSubmissionBridge.create("))
        assertTrue(target.contains("seam.canonicalAdamIdOf(param.args.getOrNull(0), currentSong.current())"))
        assertTrue(target.contains("seam.fieldSummary"))
        assertTrue(target.contains("session.isMapped(appleMusicId)"))
        assertFalse(target.contains("seam.currentItemAdamIdOf("))
        assertFalse(target.contains("seam.bindCurrentItemOf("))
        assertFalse(target.contains("currentSong.canRebind("))
        assertFalse(target.contains("private fun currentItemAdamIdOf"))
        assertFalse(target.contains("private fun resolveGetIdMethod"))
    }

    @Test
    fun `opens unavailable lyrics only after an exact replacement is ready`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("AppleMusicSymbols.LyricsAvailabilityPredicate"))
        assertTrue(target.contains("override fun afterHookedMethod(param: MethodHookParam)"))
        assertTrue(target.contains("seam.canonicalAdamIdOf(param.args.getOrNull(0), currentSong.current())"))
        assertTrue(target.contains("appleMusicId?.let(session::ensureRequested)"))
        assertTrue(target.contains("session.replacementOrPrepareFor(appleMusicId)"))
        assertTrue(target.contains("shouldExposeCustomLyrics("))
        assertFalse(target.contains("LyricsResultField"))
        assertFalse(target.contains("LyricsLoadMethod"))
        assertFalse(target.contains("LyricsResumeMethod"))
        assertFalse(target.contains("installMethod.invoke"))
    }

    @Test
    fun `registers the capability in adaptation and the feature in installation`() {
        val adaptation = projectFile("host-api/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt")
        val factory = projectFile("host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt")
        val assembly = projectFile("app/src/main/java/dev/amenhancer/module/hook/AppleMusicAssembly.kt")
        val installation = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt",
        )
        val constants = projectFile("core/src/main/kotlin/dev/amenhancer/module/ModuleConstants.kt")

        assertTrue(adaptation.contains("currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache()"))
        assertTrue(factory.contains("currentSong = currentSong"))
        assertTrue(factory.contains("currentSongIdentity = AppleMusicCurrentSongIdentityTarget("))
        assertTrue(factory.contains("customLyrics = AppleMusicCustomLyricsTarget("))
        assertTrue(factory.contains("autoLyricsRuntime = autoLyricsRuntime"))
        assertTrue(assembly.contains("settings.customLyricsEnabled && settings.automaticLyricsEnabled"))
        assertTrue(adaptation.contains("fun interface CurrentSongIdentityTarget"))
        assertTrue(installation.contains("FeatureInstallationPlan(feature = CurrentSongIdentityFeature())"))
        assertTrue(
            installation.indexOf("FeatureInstallationPlan(feature = CurrentSongIdentityFeature())") <
                installation.indexOf("FeatureInstallationPlan(feature = CustomLyricsFeature())"),
        )
        assertTrue(constants.contains("FEATURE_CURRENT_SONG_IDENTITY = \"current_song_identity\""))
    }

    @Test
    fun `embedded settings shares the in-process cache without a broadcast bridge`() {
        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCurrentSongIdentityTarget.kt",
        )
        val details = projectFile(
            "core/src/main/kotlin/dev/amenhancer/module/CurrentSongDetails.kt",
        )
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")

        assertTrue(entry.contains("currentSong = { currentSong.current()?.details }"))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController"))
        assertTrue(details.contains("data class CurrentSongDetails"))
        assertFalse(target.contains("BroadcastReceiver"))
        assertFalse(target.contains("registerRequestResponder"))
        assertFalse(manifest.contains("android:protectionLevel=\"signature\""))
        assertFalse(manifest.contains("permission.REQUEST_CURRENT_SONG_ID"))
    }
}
