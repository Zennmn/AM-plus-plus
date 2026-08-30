package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StablePlaybackMetadataCoordinatorTest {
    @Test
    fun `correction waits for terminal resolution and reports changed metadata`() {
        val scheduled = mutableListOf<() -> Unit>()
        val coordinator = StablePlaybackMetadataCoordinator(true, schedule = { _, task -> scheduled += task })
        val events = mutableListOf<StablePlaybackMetadata?>()
        coordinator.addListener { events += it }

        coordinator.onCurrentSong(CurrentSongDetails(42L, "Romanized", "Artist", "Album", 180_123L))
        assertNull(coordinator.current())

        coordinator.onResolutionFinished(42L, "原名", "歌手", "专辑", 180_123L)

        assertEquals(1, events.size)
        assertEquals(StableMetadataOutcome.CORRECTED, events.single()?.outcome)
        assertEquals("原名", events.single()?.title)
        assertEquals(180_123L, events.single()?.durationMs)
        scheduled.single().invoke()
        assertEquals(1, events.size)
    }

    @Test
    fun `disabled correction publishes raw metadata immediately`() {
        val coordinator = StablePlaybackMetadataCoordinator(false)

        coordinator.onCurrentSong(CurrentSongDetails(42L, "Song", "Artist", durationMs = 90_000L))
        val replayed = mutableListOf<StablePlaybackMetadata?>()
        coordinator.addListener { replayed += it }

        assertEquals(StableMetadataOutcome.NOT_REQUIRED, coordinator.current()?.outcome)
        assertEquals(90_000L, coordinator.current()?.durationMs)
        assertEquals(42L, replayed.single()?.appleMusicId)
    }

    @Test
    fun `timeout publishes raw metadata and stale timeout cannot cross a song change`() {
        val scheduled = mutableListOf<() -> Unit>()
        val coordinator = StablePlaybackMetadataCoordinator(true, schedule = { _, task -> scheduled += task })

        coordinator.onCurrentSong(CurrentSongDetails(42L, "First", "Artist", durationMs = 90_000L))
        coordinator.onCurrentSong(CurrentSongDetails(43L, "Second", "Artist", durationMs = 120_000L))
        scheduled.first().invoke()
        assertNull(coordinator.current())

        scheduled.last().invoke()
        assertEquals(43L, coordinator.current()?.appleMusicId)
        assertEquals(StableMetadataOutcome.TIMED_OUT, coordinator.current()?.outcome)
    }

    @Test
    fun `terminal resolution arriving before raw identity is replayed for the same id`() {
        val coordinator = StablePlaybackMetadataCoordinator(true, schedule = { _, _ -> })

        coordinator.onResolutionFinished(42L, "原名", "歌手", "专辑", 180_000L)
        assertNull(coordinator.current())
        coordinator.onCurrentSong(CurrentSongDetails(42L, "Romanized", "Artist", "Album"))

        assertEquals("原名", coordinator.current()?.title)
        assertEquals(StableMetadataOutcome.CORRECTED, coordinator.current()?.outcome)
    }

    @Test
    fun `stale resolution event cannot notify after a newer song`() {
        val coordinator = StablePlaybackMetadataCoordinator(true, schedule = { _, _ -> })
        val events = mutableListOf<Long>()
        var interlocked = false
        coordinator.addListener { event ->
            if (event?.appleMusicId == 42L && !interlocked) {
                interlocked = true
                coordinator.onCurrentSong(CurrentSongDetails(43L, "B", "Artist"))
                coordinator.onResolutionFinished(43L, "B", "Artist", null, 90_000L)
            }
        }
        coordinator.addListener { event -> event?.let { events += it.appleMusicId } }

        coordinator.onCurrentSong(CurrentSongDetails(42L, "A", "Artist"))
        coordinator.onResolutionFinished(42L, "A", "Artist", null, 90_000L)

        assertEquals(listOf(43L), events)
        assertEquals(43L, coordinator.current()?.appleMusicId)
    }
}
