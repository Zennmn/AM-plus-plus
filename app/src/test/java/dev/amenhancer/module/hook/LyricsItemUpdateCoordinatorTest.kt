package dev.amenhancer.module.hook

import com.apple.android.music.model.BaseContentItem
import dev.amenhancer.module.CurrentSongDetails
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 152 item-update decision/coordinator tests: the o2 hook must only
 * re-enter I2 for a real tracked item change after Apple's own o2 tail has
 * run, and must never touch same-item metadata refreshes, Apple's own I2
 * invocation, untracked songs, dead fragments or invalid ids.
 */
class LyricsItemUpdateCoordinatorTest {

    @Test
    fun `a real item change with a ready replacement re-enters i2 exactly once`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, reapply, _) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "77")

        assertSame(pointer, fragment.installed)
        assertEquals(1, fragment.installs)
    }

    @Test
    fun `the same item update never re-enters i2 twice`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, _, _) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "77")
        update(coordinator, fragment, "77")

        assertEquals(1, fragment.installs)
    }

    @Test
    fun `a new generation of a handled id re-enters after an intervening item`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        var ready: Any? = pointer
        val (coordinator, _, _) = coordinator(fragment, ready = { ready })

        update(coordinator, fragment, "77")
        ready = null
        update(coordinator, fragment, "99")
        ready = pointer
        update(coordinator, fragment, "77")

        assertEquals(3, fragment.installs)
    }

    @Test
    fun `an item change without a ready replacement clears and records a ready late miss`() {
        val fragment = ItemUpdateFragment()
        var ready: Any? = null
        val pointer = Any()
        val cache = CurrentSongIdentityCache().apply {
            publish(item("77"), CurrentSongDetails(77L))
        }
        val (coordinator, reapply, _) = coordinator(fragment, ready = { ready }, cache = cache)

        update(coordinator, fragment, "77")

        assertNull(fragment.installed)
        assertEquals(1, fragment.installs)
        assertEquals(1, pending(reapply).size)

        ready = pointer
        reapply.onReplacementPublished(77L)

        assertSame(pointer, fragment.installed)
        assertEquals(2, fragment.installs)
    }

    @Test
    fun `apple o2 tail invocation of i2 suppresses the module re-entry`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, reapply, _) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "77", appleInvokedI2 = true)

        assertEquals(0, fragment.installs)
        assertNull(fragment.installed)
        assertEquals(0, pending(reapply).size)
    }

    @Test
    fun `apple handled intermediate item resets a later return to the same song`() {
        val fragment = ItemUpdateFragment()
        val (coordinator, reapply, _) = coordinator(
            fragment,
            ready = { null },
            tracking = { false },
        )

        update(coordinator, fragment, "77", appleInvokedI2 = false)
        assertEquals(1, pending(reapply).size)

        reapply.dismiss(fragment)
        update(coordinator, fragment, "42", appleInvokedI2 = true)
        update(coordinator, fragment, "77", appleInvokedI2 = false)

        assertEquals(1, pending(reapply).size)
    }

    @Test
    fun `return within identity debounce clears the intermediate lyric session`() {
        val fragment = ItemUpdateFragment()
        val intermediateLyrics = Any()
        val cache = CurrentSongIdentityCache().apply {
            publish(item("77"), CurrentSongDetails(77L))
        }
        val (coordinator, reapply, _) = coordinator(
            fragment,
            ready = { null },
            tracking = { id -> cache.current()?.details?.appleMusicId == id },
            cache = cache,
        )
        cache.addListener { current ->
            current?.let(reapply::onCurrentSongChanged)
        }

        update(coordinator, fragment, "42", appleInvokedI2 = true)
        fragment.installed = intermediateLyrics
        update(coordinator, fragment, "77", appleInvokedI2 = false)

        assertEquals(1, fragment.installs)
        assertNull(fragment.installed)
        assertEquals(1, pending(reapply).size)
    }

    @Test
    fun `a same item metadata refresh with the changed flag off is ignored`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, reapply, _) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "77", changed = false)

        assertEquals(0, fragment.installs)
        assertNull(fragment.installed)
        assertEquals(0, pending(reapply).size)
    }

    @Test
    fun `an untracked item defers the fragment until identity confirmation`() {
        val fragment = ItemUpdateFragment()
        val previousPointer = Any()
        fragment.installed = previousPointer
        val (coordinator, reapply, _) = coordinator(
            fragment,
            ready = { null },
            tracking = { false },
        )

        update(coordinator, fragment, "77")

        assertEquals(0, fragment.installs)
        assertSame(previousPointer, fragment.installed)
        assertEquals(1, pending(reapply).size)
    }

    @Test
    fun `an unusable fragment never records a miss or re-enters`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, reapply, _) = coordinator(
            fragment,
            ready = { pointer },
            usable = { false },
        )

        update(coordinator, fragment, "77")

        assertEquals(0, fragment.installs)
        assertNull(fragment.installed)
        assertEquals(0, pending(reapply).size)
    }

    @Test
    fun `an invalid item id is ignored`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, reapply, _) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "0")

        assertEquals(0, fragment.installs)
        assertNull(fragment.installed)
        assertEquals(0, pending(reapply).size)
    }

    @Test
    fun `the handled ledger is identity based like the ready late ledger`() {
        val first = ItemUpdateFragment()
        val second = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, _, _) = coordinator(first, ready = { pointer })

        update(coordinator, first, "77")
        update(coordinator, second, "77")

        assertEquals(1, first.installs)
        assertEquals(1, second.installs)

        update(coordinator, first, "77")
        assertEquals(1, first.installs)
    }

    @Test
    fun `a failing re-entry is logged once and fails open`() {
        val fragment = ItemUpdateFragment(failOnInstall = true)
        val pointer = Any()
        val (coordinator, _, logs) = coordinator(fragment, ready = { pointer })

        update(coordinator, fragment, "77")
        update(coordinator, fragment, "77")

        assertEquals(1, fragment.attempts)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("item update re-entry failed"))
    }

    @Test
    fun `re-entry dismisses pending entries so a late publish cannot double install`() {
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        var ready: Any? = null
        val (coordinator, reapply, _) = coordinator(fragment, ready = { ready })

        update(coordinator, fragment, "77")
        assertEquals(1, pending(reapply).size)

        ready = pointer
        update(coordinator, fragment, "99")
        assertSame(pointer, fragment.installed)
        assertEquals(2, fragment.installs)
        assertEquals(0, pending(reapply).size)

        reapply.onReplacementPublished(77L)
        assertEquals(2, fragment.installs)
    }

    @Test
    fun `an i2 mark outside an o2 call is never seen by the next o2 call`() {
        val context = LyricsItemUpdateContext()
        val fragment = ItemUpdateFragment()
        val pointer = Any()
        val (coordinator, _, _) = coordinator(fragment, ready = { pointer })

        context.markAppleInvokedI2()
        assertFalse(context.appleInvokedI2DuringO2())

        context.enterO2()
        fragment.c = item("77")
        coordinator.onItemUpdate(
            fragment,
            flagsHolder = flags(changed = true),
            appleInvokedI2 = context.appleInvokedI2DuringO2(),
        )
        context.exitO2()

        assertEquals(1, fragment.installs)
    }

    @Test
    fun `the apple i2 marker is consumed once and suppressed while re-entering`() {
        val context = LyricsItemUpdateContext()

        context.enterO2()
        context.markAppleInvokedI2()
        assertTrue(context.appleInvokedI2DuringO2())
        assertFalse(context.appleInvokedI2DuringO2())
        context.exitO2()

        context.enterO2()
        context.reentering { context.markAppleInvokedI2() }
        assertFalse(context.appleInvokedI2DuringO2())
        context.exitO2()
    }

    @Test
    fun `decision ignores same item metadata refreshes`() {
        assertEquals(
            LyricsItemUpdateAction.IGNORE,
            decideLyricsItemUpdate(
                itemChanged = false,
                appleInvokedI2 = false,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = true,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision observes apple i2 without requesting module re-entry`() {
        assertEquals(
            LyricsItemUpdateAction.OBSERVE_APPLE_HANDLED,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = true,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = true,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision ignores unusable fragments`() {
        assertEquals(
            LyricsItemUpdateAction.IGNORE,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = false,
                fragmentUsable = false,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = true,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision ignores null and non positive item ids`() {
        listOf(null, 0L).forEach { id ->
            assertEquals(
                LyricsItemUpdateAction.IGNORE,
                decideLyricsItemUpdate(
                    itemChanged = true,
                    appleInvokedI2 = false,
                    fragmentUsable = true,
                    itemAdamId = id,
                    previouslyHandledAdamId = null,
                    tracked = true,
                    replacementReady = true,
                ),
            )
        }
    }

    @Test
    fun `decision waits for identity when the item is not tracked yet`() {
        assertEquals(
            LyricsItemUpdateAction.WAIT_FOR_IDENTITY,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = false,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = false,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision ignores an item already handled for the same fragment`() {
        assertEquals(
            LyricsItemUpdateAction.IGNORE,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = false,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = 77L,
                tracked = true,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision re-enters when the replacement is ready`() {
        assertEquals(
            LyricsItemUpdateAction.REENTER,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = false,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = true,
                replacementReady = true,
            ),
        )
    }

    @Test
    fun `decision records a miss when the replacement is still preparing`() {
        assertEquals(
            LyricsItemUpdateAction.RECORD_MISS,
            decideLyricsItemUpdate(
                itemChanged = true,
                appleInvokedI2 = false,
                fragmentUsable = true,
                itemAdamId = 77L,
                previouslyHandledAdamId = null,
                tracked = true,
                replacementReady = false,
            ),
        )
    }

    private fun item(id: String): BaseContentItem = BaseContentItem(id)

    private fun flags(changed: Boolean): ItemUpdateFragmentBase.c =
        ItemUpdateFragmentBase.c().apply { a = changed }

    private fun update(
        coordinator: LyricsItemUpdateCoordinator,
        fragment: ItemUpdateFragment,
        id: String,
        changed: Boolean = true,
        appleInvokedI2: Boolean = false,
    ) {
        fragment.c = item(id)
        coordinator.onItemUpdate(
            fragment,
            flagsHolder = flags(changed),
            appleInvokedI2 = appleInvokedI2,
        )
    }

    private fun coordinator(
        fragment: Any,
        ready: (Long) -> Any? = { null },
        tracking: (Long) -> Boolean = { true },
        usable: (Any) -> Boolean = { true },
        cache: CurrentSongIdentityCache = CurrentSongIdentityCache(),
    ): Triple<LyricsItemUpdateCoordinator, CustomLyricsReadyReapply, MutableList<String>> {
        val logs = mutableListOf<String>()
        val fragmentClass = fragment.javaClass
        val installMethod = fragmentClass.getDeclaredMethod("I2", Any::class.java)
        val itemUpdateMethod = fragmentClass.getDeclaredMethod(
            "o2",
            v3.v::class.java,
            BaseContentItem::class.java,
            ItemUpdateFragmentBase.c::class.java,
        )
        val seam = CurrentItemIdentitySeam(
            ItemUpdateResolver(
                field = fragmentClass.getDeclaredField("c"),
                installMethod = installMethod,
            ),
        )
        seam.resolve(installMethod)
        val reapply = CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = ready,
            isFragmentUsable = usable,
            currentSong = cache,
            logger = { logs += it },
        )
        val coordinator = LyricsItemUpdateCoordinator(
            installMethod = installMethod,
            flags = ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]),
            seam = seam,
            readyReplacementFor = ready,
            isTracking = tracking,
            isFragmentUsable = usable,
            readyReapply = reapply,
            logger = { logs += it },
        )
        return Triple(coordinator, reapply, logs)
    }

    private fun pending(reapply: CustomLyricsReadyReapply): MutableList<*> {
        val field = CustomLyricsReadyReapply::class.java.getDeclaredField("pending")
        field.isAccessible = true
        return field.get(reapply) as MutableList<*>
    }

    private class ItemUpdateResolver(
        private val field: Field,
        private val installMethod: Method,
    ) : TargetSymbolResolver {
        override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> {
            @Suppress("UNCHECKED_CAST")
            return when (symbol.id) {
                AppleMusicSymbols.LyricsCurrentItemField.id ->
                    TargetResolution.Found(symbol.id, field, SymbolMatch.VERSION_PROFILE, "test")
                AppleMusicSymbols.LyricsInstallMethod.id ->
                    TargetResolution.Found(
                        symbol.id,
                        installMethod,
                        SymbolMatch.VERSION_PROFILE,
                        "test",
                    )
                else -> TargetResolution.Missing(symbol.id, "test")
            } as TargetResolution<T>
        }
    }

    private open class ItemUpdateFragmentBase {
        class c {
            @JvmField
            var a: Boolean = false

            @JvmField
            var b: Boolean = false

            @JvmField
            var c: Boolean = false
        }
    }

    private class ItemUpdateFragment(private val failOnInstall: Boolean = false) :
        ItemUpdateFragmentBase() {
        @JvmField
        var c: BaseContentItem? = null

        var installed: Any? = null
        var installs: Int = 0
        var attempts: Int = 0

        @Suppress("UNUSED_PARAMETER")
        fun I2(ptr: Any?) {
            attempts += 1
            if (failOnInstall) throw IllegalStateException("I2 exploded")
            installed = ptr
            installs += 1
        }

        @Suppress("UNUSED_PARAMETER")
        fun o2(metadata: v3.v, item: BaseContentItem, flags: ItemUpdateFragmentBase.c) = Unit
    }
}
