package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSongIdentityStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `caches the identity in memory without storage or network on the hook path`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCurrentSongIdentityTarget.kt",
        )
        val feature = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/CurrentSongIdentityFeature.kt",
        )

        assertTrue(target.contains("CurrentItemIdentitySeam(symbols)"))
        assertTrue(target.contains("private val cache: CurrentSongIdentityCache"))
        assertTrue(target.contains("PlayerMetadataPublishMethod"))
        assertTrue(target.contains("MetadataToPlaybackItemMethod"))
        assertTrue(target.contains("cache.publish(item, seam.detailsOfItem(item))"))
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
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("val seam = CurrentItemIdentitySeam(symbols)"))
        assertTrue(target.contains("seam.currentItemAdamIdOf(param.thisObject)"))
        assertTrue(target.contains("selectLyricsInjectionAdamId("))
        assertTrue(target.contains("seam.bindCurrentItemOf(fragment, publishedCurrent.item)"))
        assertTrue(target.contains("currentSong.canRebind(fragmentAdamId, publishedAdamId)"))
        assertTrue(target.contains("session.isMapped(adamId)"))
        assertFalse(target.contains("private fun currentItemAdamIdOf"))
        assertFalse(target.contains("private fun resolveGetIdMethod"))
    }

    @Test
    fun `opens unavailable lyrics only after an exact replacement is ready`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )

        assertTrue(target.contains("AppleMusicSymbols.LyricsAvailabilityPredicate"))
        assertTrue(target.contains("override fun afterHookedMethod(param: MethodHookParam)"))
        assertTrue(target.contains("seam.detailsOfItem(param.args.getOrNull(0))"))
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
        val adaptation = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt",
        )
        val installation = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt",
        )
        val constants = projectFile("app/src/main/java/dev/amenhancer/module/ModuleConstants.kt")

        assertTrue(adaptation.contains("currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache()"))
        assertTrue(adaptation.contains("currentSong = currentSong"))
        assertTrue(adaptation.contains("currentSongIdentity = AppleMusicCurrentSongIdentityTarget("))
        assertTrue(adaptation.contains("customLyrics = AppleMusicCustomLyricsTarget("))
        assertTrue(adaptation.contains("autoLyricsRuntime = autoLyricsRuntime"))
        assertTrue(adaptation.contains("settings.customLyricsEnabled && settings.automaticLyricsEnabled"))
        assertTrue(adaptation.contains("internal fun interface CurrentSongIdentityTarget"))
        assertTrue(installation.contains("FeatureInstallationPlan(feature = CurrentSongIdentityFeature())"))
        assertTrue(
            installation.indexOf("FeatureInstallationPlan(feature = CurrentSongIdentityFeature())") <
                installation.indexOf("FeatureInstallationPlan(feature = CustomLyricsFeature())"),
        )
        assertTrue(constants.contains("FEATURE_CURRENT_SONG_IDENTITY = \"current_song_identity\""))
    }

    @Test
    fun `embedded settings shares the in-process cache without a manifest permission`() {
        val target = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AppleMusicCurrentSongIdentityTarget.kt",
        )
        val protocol = projectFile(
            "app/src/main/java/dev/amenhancer/module/CurrentSongIdentityProtocol.kt",
        )
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val entry = projectFile("app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt")

        assertTrue(target.contains("CurrentSongIdentityRequestResponder"))
        assertTrue(target.contains("registerRequestResponder: Boolean = true"))
        assertTrue(entry.contains("currentSong = { currentSong.current()?.details }"))
        assertTrue(entry.contains("EmbeddedRuntimeSettingsController"))
        assertTrue(protocol.contains("EXTRA_SONG_TITLE"))
        assertTrue(protocol.contains("EXTRA_SONG_ARTIST"))
        assertTrue(protocol.contains("EXTRA_SONG_ALBUM"))
        assertTrue(protocol.contains("EXTRA_SONG_DURATION_MS"))
        assertFalse(manifest.contains("android:protectionLevel=\"signature\""))
        assertFalse(manifest.contains("permission.REQUEST_CURRENT_SONG_ID"))
    }
}
