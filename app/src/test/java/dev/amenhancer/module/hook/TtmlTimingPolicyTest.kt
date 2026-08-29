package dev.amenhancer.module.hook

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlTimingPolicyTest {
    @Test
    fun `accepts Word root timing with single or double quotes and whitespace`() {
        assertEquals(
            TtmlTimingMode.WORD,
            TtmlTimingPolicy.modeOf(
                "<tt xmlns:itunes=\"urn\" itunes:timing = ' Word '><body/></tt>",
            ),
        )
    }

    @Test
    fun `missing and Line timing are non-word`() {
        assertEquals(TtmlTimingMode.NON_WORD, TtmlTimingPolicy.modeOf("<tt><body/></tt>"))
        assertEquals(
            TtmlTimingMode.NON_WORD,
            TtmlTimingPolicy.modeOf("<tt itunes:timing=\"Line\"><body/></tt>"),
        )
    }

    @Test
    fun `foreign Word metadata without translation needs fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="en-US" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">hello</p></div></body>
            </tt>
            """.trimIndent(),
        )

        assertEquals(TtmlTimingMode.WORD, metadata.timingMode)
        assertEquals("en-US", metadata.language)
        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `translation track suppresses foreign Word fallback`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="ja" itunes:timing="Word">
              <body><div><p begin="0s" end="1s">歌</p></div></body>
              <translations><translation xml:lang="zh">歌</translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertTrue(metadata.hasTranslation)
        assertTrue(metadata.isForeign)
        assertFalse(metadata.needsTranslationFallback)
    }

    @Test
    fun `empty translation and transliteration do not count as translation`() {
        val metadata = TtmlTimingPolicy.metadataOf(
            """
            <tt xml:lang="fr" itunes:timing="Word">
              <transliterations><transliteration>bonjour</transliteration></transliterations>
              <translations><translation>  <span> </span> </translation></translations>
            </tt>
            """.trimIndent(),
        )

        assertFalse(metadata.hasTranslation)
        assertTrue(metadata.needsTranslationFallback)
    }

    @Test
    fun `Chinese or missing language is not classified as foreign`() {
        val chinese = TtmlTimingPolicy.metadataOf(
            "<tt xml:lang=\"zh-Hans\" itunes:timing=\"Word\"><body/></tt>",
        )
        val missing = TtmlTimingPolicy.metadataOf(
            "<tt itunes:timing=\"Word\"><body/></tt>",
        )

        assertFalse(chinese.isForeign)
        assertFalse(chinese.needsTranslationFallback)
        assertNull(missing.language)
        assertFalse(missing.isForeign)
        assertFalse(missing.needsTranslationFallback)
    }

    @Test
    fun `identity registry does not confuse equal pointers`() {
        val first = String(charArrayOf('p'))
        val equal = String(charArrayOf('p'))
        val registry = TtmlTimingObservationRegistry(maxEntries = 2)

        registry.record(first, TtmlTimingMode.WORD)

        assertEquals(TtmlTimingMode.WORD, registry.modeOf(first))
        assertNull(registry.modeOf(equal))
    }

    @Test
    fun `identity registry associates observed metadata with the Apple Music ID`() {
        val pointer = Any()
        val metadata = TtmlDocumentMetadata(
            timingMode = TtmlTimingMode.NON_WORD,
            language = "en",
            hasTranslation = false,
        )
        val registry = TtmlTimingObservationRegistry()

        registry.record(pointer, metadata, appleMusicId = 42L)

        assertEquals(metadata, registry.metadataOfAppleMusicId(42L))
    }

    @Test
    fun `registry associates distinct wrappers of the same native pointer`() {
        val parsedWrapper = NativePointerWrapper(0x1234L)
        val installWrapper = NativePointerWrapper(0x1234L)
        val registry = TtmlTimingObservationRegistry()

        registry.record(parsedWrapper, TtmlTimingMode.NON_WORD)

        assertEquals(TtmlTimingMode.NON_WORD, registry.modeOf(installWrapper))
    }

    @Test
    fun `registry retains native address metadata after parsed wrapper is collected`() {
        val registry = TtmlTimingObservationRegistry()
        val parsedWrapper = recordCollectibleNativePointer(registry)

        assertTrue("parsed wrapper was not collected", awaitCollected(parsedWrapper))

        assertEquals(
            TtmlTimingMode.NON_WORD,
            registry.modeOf(NativePointerWrapper(0x5678L)),
        )
    }

    @Test
    fun `registry evicts oldest observation`() {
        val registry = TtmlTimingObservationRegistry(maxEntries = 1)
        val first = Any()
        val second = Any()

        registry.record(first, TtmlTimingMode.WORD)
        registry.record(second, TtmlTimingMode.NON_WORD)

        assertNull(registry.modeOf(first))
        assertEquals(TtmlTimingMode.NON_WORD, registry.modeOf(second))
    }
}

private fun recordCollectibleNativePointer(
    registry: TtmlTimingObservationRegistry,
): WeakReference<NativePointerWrapper> {
    val pointer = NativePointerWrapper(0x5678L)
    registry.record(pointer, TtmlTimingMode.NON_WORD)
    return WeakReference(pointer)
}

private fun awaitCollected(reference: WeakReference<*>): Boolean {
    repeat(40) {
        System.gc()
        System.runFinalization()
        if (reference.get() == null) return true
        Thread.sleep(10L)
    }
    return false
}

private class NativePointerWrapper(
    @JvmField val address: Long,
)
