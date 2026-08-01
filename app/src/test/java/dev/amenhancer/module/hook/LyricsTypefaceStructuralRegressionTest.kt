package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTypefaceStructuralRegressionTest {
    @Test
    fun `uses the remote file and not a provider or base64 preferences`() {
        val importer = source("../font/SafFontImporter.kt")
        val entry = source("HookEntry.kt")
        val runtime = source("LyricsTypefaceSession.kt")

        assertTrue(importer.contains("openRemoteFile"))
        assertTrue(entry.contains("openRemoteFile(name)"))
        assertFalse(importer.contains("takePersistableUriPermission"))
        assertFalse(importer.contains("Base64"))
        assertFalse(importer.contains("uri.toString()"))
        assertFalse(runtime.contains("TextView::class.java.getDeclaredMethod(\"setTypeface\""))
    }

    @Test
    fun `registers every verified player lyric surface from the full contract`() {
        val source = source("LyricsTypefaceSession.kt") + source("AppleMusicLyricsTypefaceTarget.kt")
        val contract = LyricsTypefaceLayoutContract.layoutNames

        // Lock the complete production whitelist, not a subset of it.
        assertEquals(12, contract.size)
        contract.forEach { layout -> assertTrue(source.contains("\"$layout\"")) }
        assertTrue(source.contains("LyricsTypefaceLayoutContract.layoutNames.forEach"))
        assertTrue(source.contains("LayoutInflationRegistry.register"))
        assertTrue(source.contains("onResume"))
        assertTrue(source.contains("addOnChildAttachStateChangeListener"))
    }

    @Test
    fun `only changes the typeface family while retaining each view style`() {
        val source = source("LyricsTypefaceSession.kt")

        assertTrue(source.contains("view.typeface?.style ?: Typeface.NORMAL"))
        assertTrue(source.contains("Typeface.create(importedBase, style)"))
        assertTrue(source.contains("styleTypefaceCache"))
        assertTrue(source.contains("view.typeface = replacement"))
        assertTrue(source.contains("view.typeface === replacement"))
        assertTrue(source.contains("view is TextView"))
        assertTrue(source.contains("view as? ViewGroup"))
        assertFalse(source.contains("TextView.setTypeface"))
    }

    @Test
    fun `a failed replacement skips only that view instead of the whole tree`() {
        val source = source("LyricsTypefaceSession.kt")

        assertTrue(source.contains("styleTypeface(importedBase, originalStyle) ?: return@forEach"))
        assertTrue(source.contains("failedTypefaceStyles"))
    }

    @Test
    fun `the sixteen mib read hash and typeface parse stay off the main thread`() {
        val session = source("LyricsTypefaceSession.kt")
        val target = source("AppleMusicLyricsTypefaceTarget.kt")

        assertTrue(target.contains("LyricsTypefacePreparation.Loading"))
        assertTrue(session.contains("LyricsTypefaceLoadController"))
        assertTrue(session.contains("backgroundExecutor.execute"))
        assertTrue(session.contains("isDaemon = true"))
        assertTrue(session.contains("Typeface.Builder(descriptor.fileDescriptor).build()"))
        assertTrue(session.contains("scheduleReapplyPendingRoots"))
        assertTrue(session.contains("mainHandler.post"))
        assertTrue(session.contains("FontLoadRetryPolicy.shouldRetry"))
    }

    @Test
    fun `child attach delayed applies merge and rows observe in place rebinds`() {
        val source = source("LyricsTypefaceSession.kt")

        assertTrue(source.contains("DelayedApplyGate"))
        assertTrue(source.contains("delayedApplyGate.tryAcquire"))
        assertTrue(source.contains("postDelayed"))
        assertTrue(source.contains("addOnLayoutChangeListener"))
        assertTrue(source.contains("installRowLayoutChangeObserver"))
    }

    @Test
    fun `feature stays independent from blur and dual pane capabilities`() {
        val feature = source("LyricsTypefaceFeature.kt")

        assertTrue(feature.contains("context.target.lyricsTypeface.install()"))
        assertFalse(feature.contains("DualPane"))
        assertFalse(feature.contains("FutureLyricBlur"))
        assertFalse(feature.contains("setTypeface"))
    }

    @Test
    fun `font feature is appended after the existing four features`() {
        val source = source("FeatureInstallation.kt")

        assertTrue(source.contains("feature = FutureLyricBlurFeature()"))
        assertTrue(source.contains("feature = LyricsTypefaceFeature()"))
        assertTrue(source.indexOf("FutureLyricBlurFeature()") < source.indexOf("LyricsTypefaceFeature"))
    }

    private fun source(relative: String): String = sequenceOf(
        File("app/src/main/java/dev/amenhancer/module/hook/$relative"),
        File("src/main/java/dev/amenhancer/module/hook/$relative"),
        File("app/src/main/java/dev/amenhancer/module/$relative"),
        File("src/main/java/dev/amenhancer/module/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$relative was not found")
}
