package dev.amenhancer.module.hook

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHighlightEventRouterTest {
    @Test
    fun `view model drives the current line while callback hook is unavailable`() {
        val runtime = RecordingLyricBlurRuntime()
        val router = LyricHighlightEventRouter(runtime)

        router.onFourArgumentViewModelEvent(lineId = 41, isBackground = false)
        router.onFourArgumentViewModelEvent(lineId = 42, isBackground = true)
        router.onFourArgumentViewModelEvent(lineId = 0, isBackground = false)
        router.onSingleArgumentViewModelEvent(lineId = 43)
        router.onSingleArgumentViewModelEvent(lineId = -1)

        assertEquals(listOf(41, 43), runtime.fallbackHighlights)
    }

    @Test
    fun `installed callback suppresses both view model fallback shapes`() {
        val runtime = RecordingLyricBlurRuntime()
        val router = LyricHighlightEventRouter(runtime)

        router.onCallbackInstalled()
        router.onCallback(setOf(8, 9))
        router.onFourArgumentViewModelEvent(lineId = 10, isBackground = false)
        router.onSingleArgumentViewModelEvent(lineId = 11)

        assertEquals(listOf(emptySet(), setOf(8, 9)), runtime.highlightUpdates)
        assertEquals(emptyList<Int>(), runtime.fallbackHighlights)
    }

    @Test
    fun `probe distinguishes synthetic native and fallback while retaining native longs`() {
        val runtime = RecordingLyricBlurRuntime()
        val probeLines = mutableListOf<String>()
        val probe = LyricHighlightProbe(
            sink = LyricHighlightProbeSink { probeLines += it },
            enabled = { true },
            uptimeMillis = { 99L },
        )
        val router = LyricHighlightEventRouter(runtime, probe)

        router.onCallbackInstalled()
        router.onCallback(
            nativeFirst = 101L,
            lineIds = setOf(8),
            nativeLast = 202L,
            rawLineIds = setOf(8, 7),
        )
        val fallbackRuntime = RecordingLyricBlurRuntime()
        val fallbackProbeLines = mutableListOf<String>()
        val fallbackRouter = LyricHighlightEventRouter(
            fallbackRuntime,
            LyricHighlightProbe(
                sink = LyricHighlightProbeSink { fallbackProbeLines += it },
                enabled = { true },
                uptimeMillis = { 100L },
            ),
        )
        fallbackRouter.onFourArgumentViewModelEvent(lineId = 3, isBackground = false)
        fallbackRouter.onSingleArgumentViewModelEvent(lineId = 4)

        assertTrue(probeLines[0].contains("source=synthetic-install"))
        assertTrue(probeLines[1].contains("source=native"))
        assertTrue(probeLines[1].contains("native0=101"))
        assertTrue(probeLines[1].contains("native2=202"))
        assertTrue(probeLines[1].contains("rawLineIds=[7, 8]"))
        assertTrue(probeLines[1].contains("effectiveLineIds=[8]"))
        assertEquals(2, fallbackProbeLines.size)
        assertTrue(fallbackProbeLines[0].contains("source=vm4"))
        assertTrue(fallbackProbeLines[1].contains("source=vm1"))
    }

    private class RecordingLyricBlurRuntime : LyricBlurRuntime {
        val highlightUpdates = mutableListOf<Set<Int>>()
        val fallbackHighlights = mutableListOf<Int>()

        override fun onSessionChanged(songInfo: Any) = Unit

        override fun onHighlightsChanged(lineIds: Set<Int>) {
            highlightUpdates += lineIds
        }

        override fun onFallbackHighlightChanged(lineId: Int) {
            fallbackHighlights += lineId
        }

        override fun onLyricsViewCreated(owner: Any, root: View) = Unit

        override fun onLyricsViewDestroyed(owner: Any) = Unit
    }
}
