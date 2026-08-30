package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSongIdentityTargetTest {

    @Test
    fun `seam resolves and reads the exact current item adam id`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        assertNull(seam.resolve(SongFragment.installMethod()))
        assertNotNull(seam.fieldSummary)

        assertEquals(12345L, seam.currentItemAdamIdOf(SongFragment(SongItem("12345"))))
    }

    @Test
    fun `seam reads item update argument without depending on I2`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        assertNull(seam.resolve(SongFragment.installMethod()))

        assertEquals(
            CurrentSongDetails(67890L, "No lyrics", "Artist"),
            seam.detailsOfItem(SongItem("67890", "No lyrics", "Artist")),
        )
    }

    @Test
    fun `seam exposes the optional collection name for metadata lookup`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        assertNull(seam.resolve(SongFragment.installMethod()))

        assertEquals(
            CurrentSongDetails(67890L, "No lyrics", "Artist", "Album"),
            seam.detailsOfItem(SongItem("67890", "No lyrics", "Artist", "Album")),
        )
    }

    @Test
    fun `seam exposes the optional millisecond duration for strict metadata matching`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        assertNull(seam.resolve(SongFragment.installMethod()))

        assertEquals(
            180_123L,
            seam.detailsOfItem(
                SongItem("67890", "Song", "Artist", "Album", 180_123L),
            )?.durationMs,
        )
    }

    @Test
    fun `seam can rebind a stale lyrics fragment to the verified current item`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        assertNull(seam.resolve(SongFragment.installMethod()))
        val fragment = SongFragment(SongItem("182861090"))

        assertTrue(seam.bindCurrentItemOf(fragment, SongItem("7335408332109193189")))
        assertEquals(7335408332109193189L, seam.currentItemAdamIdOf(fragment))
        assertTrue(!seam.bindCurrentItemOf(fragment, Any()))
    }

    @Test
    fun `seam reads fail closed when the current item is unavailable`() {
        val seam = CurrentItemIdentitySeam(resolver(SongFragment::class.java))
        seam.resolve(SongFragment.installMethod())

        assertNull(seam.currentItemAdamIdOf(null))
        assertNull(seam.currentItemAdamIdOf(SongFragment(null)))
        assertNull(seam.currentItemAdamIdOf(SongFragment(SongItem("0"))))
        assertNull(seam.currentItemAdamIdOf(SongFragment(SongItem("not-a-number"))))
    }

    @Test
    fun `seam reports a missing current item field`() {
        val seam = CurrentItemIdentitySeam(FakeIdentityResolver(field = null))
        val diagnostic = seam.resolve(SongFragment.installMethod())

        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("lyrics-current-item-field"))
        assertTrue(diagnostic.contains("was not found"))
    }

    @Test
    fun `seam reports an item type without the getId contract`() {
        val seam = CurrentItemIdentitySeam(resolver(WrongTypeFragment::class.java))
        val diagnostic = seam.resolve(WrongTypeFragment.installMethod())

        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("getId() was unavailable"))
    }

    @Test
    fun `seam rejects a field outside the I2 fragment hierarchy`() {
        val seam = CurrentItemIdentitySeam(
            resolver(owner = ForeignFieldOwner::class.java, installMethod = ForeignFragment.installMethod()),
        )
        val diagnostic = seam.resolve(ForeignFragment.installMethod())

        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("not in the I2 fragment hierarchy"))
    }

    @Test
    fun `cache stores the exact adam id`() {
        val cache = CurrentSongIdentityCache(invalidIdentityDebounceMs = 0L)
        val item = Any()

        cache.publish(item, CurrentSongDetails(12345L, "Song title", "Artist name"))

        assertEquals(
            CurrentSongDetails(12345L, "Song title", "Artist name"),
            cache.current()?.details,
        )
        assertTrue(cache.current()?.item === item)
    }

    @Test
    fun `cache fails closed for unavailable identities`() {
        val cache = CurrentSongIdentityCache(invalidIdentityDebounceMs = 0L)
        cache.publish(Any(), CurrentSongDetails(12345L))

        cache.publish(null, null)
        assertNull(cache.current())
        cache.publish(Any(), CurrentSongDetails(0L))
        assertNull(cache.current())
        cache.publish(Any(), CurrentSongDetails(-7L))
        assertNull(cache.current())
    }

    @Test
    fun `transient unavailable identity does not clear a live song`() {
        val cache = CurrentSongIdentityCache(invalidIdentityDebounceMs = 100L)
        val events = mutableListOf<TargetCurrentSong?>()
        cache.addListener { events += it }
        val item = Any()

        cache.publish(item, CurrentSongDetails(12345L))
        cache.publish(null, null)
        Thread.sleep(20L)

        assertEquals(12345L, cache.current()?.details?.appleMusicId)
        assertEquals(0, events.count { it == null })
        cache.publish(item, CurrentSongDetails(12345L))
        Thread.sleep(150L)
        assertEquals(12345L, cache.current()?.details?.appleMusicId)
        assertEquals(0, events.count { it == null })
    }

    @Test
    fun `transient different valid identity does not replace the current song`() {
        val cache = CurrentSongIdentityCache(
            invalidIdentityDebounceMs = 0L,
            validIdentityDebounceMs = 100L,
        )
        val events = mutableListOf<TargetCurrentSong?>()
        cache.addListener { events += it }

        cache.publish(Any(), CurrentSongDetails(42L))
        cache.publish(Any(), CurrentSongDetails(43L))
        Thread.sleep(20L)
        cache.publish(Any(), CurrentSongDetails(42L))
        Thread.sleep(150L)

        assertEquals(42L, cache.current()?.details?.appleMusicId)
        assertTrue(events.none { it?.details?.appleMusicId == 43L })
    }

    @Test
    fun `persistent different valid identity replaces the current song after its grace window`() {
        val cache = CurrentSongIdentityCache(
            invalidIdentityDebounceMs = 0L,
            validIdentityDebounceMs = 100L,
        )
        cache.publish(Any(), CurrentSongDetails(42L))
        cache.publish(Any(), CurrentSongDetails(43L))

        Thread.sleep(150L)

        assertEquals(43L, cache.current()?.details?.appleMusicId)
    }

    @Test
    fun `scheduled valid identity cannot commit after a newer publish`() {
        val staleId = 42L
        val candidateId = 43L
        var cache: CurrentSongIdentityCache? = null
        val events = CopyOnWriteArrayList<Long>()
        val scheduled = CountDownLatch(1)
        cache = CurrentSongIdentityCache(
            invalidIdentityDebounceMs = 0L,
            validIdentityDebounceMs = 100L,
            beforeScheduledCommit = {
                scheduled.countDown()
                cache!!.publish(Any(), CurrentSongDetails(staleId))
            },
        )
        cache!!.addListener { song -> song?.details?.appleMusicId?.let(events::add) }
        cache!!.publish(Any(), CurrentSongDetails(staleId))
        cache!!.publish(Any(), CurrentSongDetails(candidateId))
        assertTrue(scheduled.await(1L, TimeUnit.SECONDS))

        assertTrue(
            "a cancelled callback must not publish the stale candidate after a newer identity",
            events.none { it == candidateId } && cache.current()?.details?.appleMusicId == staleId,
        )
    }

    @Test
    fun `scheduled invalidation cannot clear after a newer publish`() {
        val currentId = 42L
        var cache: CurrentSongIdentityCache? = null
        val events = CopyOnWriteArrayList<Long?>()
        val scheduled = CountDownLatch(1)
        cache = CurrentSongIdentityCache(
            invalidIdentityDebounceMs = 100L,
            validIdentityDebounceMs = 100L,
            beforeScheduledCommit = {
                scheduled.countDown()
                cache!!.publish(Any(), CurrentSongDetails(currentId))
            },
        )
        cache!!.addListener { song -> events += song?.details?.appleMusicId }
        cache!!.publish(Any(), CurrentSongDetails(currentId))
        cache!!.publish(null, null)
        assertTrue(scheduled.await(1L, TimeUnit.SECONDS))

        assertEquals(currentId, cache!!.current()?.details?.appleMusicId)
        assertTrue(events.none { it == null })
    }

    @Test
    fun `identity listener notification stays ordered with state commits`() {
        val cache = CurrentSongIdentityCache(invalidIdentityDebounceMs = 0L)
        val events = CopyOnWriteArrayList<Long?>()
        val invalidationListenerEntered = CountDownLatch(1)
        val releaseInvalidationListener = CountDownLatch(1)
        val nextIdentityFinished = CountDownLatch(1)
        cache.publish(Any(), CurrentSongDetails(42L))
        cache.addListener { song ->
            if (song == null) {
                invalidationListenerEntered.countDown()
                releaseInvalidationListener.await(1L, TimeUnit.SECONDS)
            }
            events += song?.details?.appleMusicId
        }
        events.clear()

        val invalidation = Thread { cache.publish(null, null) }
        invalidation.start()
        assertTrue(invalidationListenerEntered.await(1L, TimeUnit.SECONDS))

        val nextIdentity = Thread {
            cache.publish(Any(), CurrentSongDetails(43L))
            nextIdentityFinished.countDown()
        }
        nextIdentity.start()
        try {
            assertFalse(
                "a new identity must not notify before the old event is delivered",
                nextIdentityFinished.await(100L, TimeUnit.MILLISECONDS),
            )
        } finally {
            releaseInvalidationListener.countDown()
        }

        assertTrue(nextIdentityFinished.await(1L, TimeUnit.SECONDS))
        invalidation.join(1_000L)
        nextIdentity.join(1_000L)
        assertEquals(listOf(null, 43L), events)
        assertEquals(43L, cache.current()?.details?.appleMusicId)
    }

    @Test
    fun `cache replays and publishes identity changes to listeners`() {
        val cache = CurrentSongIdentityCache(invalidIdentityDebounceMs = 0L)
        val first = Any()
        val second = Any()
        val events = mutableListOf<TargetCurrentSong?>()

        cache.publish(first, CurrentSongDetails(12345L))
        cache.addListener { events += it }
        cache.publish(second, CurrentSongDetails(67890L))
        cache.publish(null, null)

        assertEquals(3, events.size)
        assertTrue(events[0]?.item === first)
        assertTrue(events[1]?.item === second)
        assertNull(events[2])
    }

    @Test
    fun `cache only permits rebinding from a recently published identity`() {
        val cache = CurrentSongIdentityCache(
            invalidIdentityDebounceMs = 0L,
            validIdentityDebounceMs = 0L,
        )

        cache.publish(Any(), CurrentSongDetails(182861090L))
        cache.publish(Any(), CurrentSongDetails(7335408332109193189L))

        assertTrue(cache.canRebind(182861090L, 7335408332109193189L))
        assertTrue(cache.canRebind(null, 7335408332109193189L))
        assertTrue(!cache.canRebind(999L, 7335408332109193189L))
    }

    @Test
    fun `feature installs unconditionally and maps the capability result`() {
        val target = TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            currentSongIdentity = CurrentSongIdentityTarget {
                TargetCapabilityInstall.Active("identity published")
            },
        )

        val result = CurrentSongIdentityFeature().install(
            HookContext(config(), target),
        )

        assertEquals(FeatureState.ACTIVE, result.state)
        assertTrue(result.message.contains("identity published"))
    }

    @Test
    fun `feature maps a degraded capability as degraded without a settings gate`() {
        val target = TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            currentSongIdentity = CurrentSongIdentityTarget {
                TargetCapabilityInstall.Degraded("holder missing")
            },
        )

        val result = CurrentSongIdentityFeature().install(
            HookContext(config(), target),
        )

        assertEquals(FeatureState.DEGRADED, result.state)
        assertTrue(result.message.contains("holder missing"))
    }

    private fun resolver(
        owner: Class<*>,
        installMethod: Method = owner.getDeclaredMethod("I2", Any::class.java),
    ): TargetSymbolResolver = FakeIdentityResolver(
        field = owner.getDeclaredField("c"),
        installMethod = installMethod,
    )

    private fun config(): TargetConfigClient = TargetConfigClient(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> emptyMap<String, Any>()
                "toString" -> "current-song-identity-test-preferences"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SharedPreferences,
    )
}

private class FakeIdentityResolver(
    private val field: Field?,
    private val installMethod: Method? = null,
) : TargetSymbolResolver {
    override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> {
        @Suppress("UNCHECKED_CAST")
        return when (symbol.id) {
            AppleMusicSymbols.LyricsCurrentItemField.id ->
                if (field != null) {
                    TargetResolution.Found(symbol.id, field, SymbolMatch.VERSION_PROFILE, "test")
                } else {
                    TargetResolution.Missing(symbol.id, "test")
                }
            AppleMusicSymbols.LyricsInstallMethod.id ->
                if (installMethod != null) {
                    TargetResolution.Found(
                        symbol.id,
                        installMethod,
                        SymbolMatch.VERSION_PROFILE,
                        "test",
                    )
                } else {
                    TargetResolution.Missing(symbol.id, "test")
                }
            else -> TargetResolution.Missing(symbol.id, "test")
        } as TargetResolution<T>
    }
}

private class SongItem(
    private val id: String,
    private val title: String = "Song title",
    private val artistName: String = "Artist name",
    private val collectionName: String = "",
    private val duration: Long = 0L,
) {
    fun getId(): String = id
    fun getTitle(): String = title
    fun getArtistName(): String = artistName
    fun getCollectionName(): String = collectionName
    fun getDuration(): Long = duration
}

private class SongFragment(item: SongItem?) {
    @JvmField
    var c: SongItem? = item

    @Suppress("UNUSED_PARAMETER")
    fun I2(ptr: Any) = Unit

    companion object {
        fun installMethod(): Method =
            SongFragment::class.java.getDeclaredMethod("I2", Any::class.java)
    }
}

private class WrongTypeFragment {
    @JvmField
    var c: String = ""

    @Suppress("UNUSED_PARAMETER")
    fun I2(ptr: Any) = Unit

    companion object {
        fun installMethod(): Method =
            WrongTypeFragment::class.java.getDeclaredMethod("I2", Any::class.java)
    }
}

private class ForeignFieldOwner {
    @JvmField
    var c: SongItem? = null
}

private class ForeignFragment {
    @Suppress("UNUSED_PARAMETER")
    fun I2(ptr: Any) = Unit

    companion object {
        fun installMethod(): Method =
            ForeignFragment::class.java.getDeclaredMethod("I2", Any::class.java)
    }
}
