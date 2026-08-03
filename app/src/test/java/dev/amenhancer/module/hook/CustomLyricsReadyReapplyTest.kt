package dev.amenhancer.module.hook

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsReadyReapplyTest {

    @Test
    fun `first i2 miss then ready late publish re-enters i2 with the replacement`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        assertNull(fragment.installed)

        reapply.onReplacementPublished(42L)

        assertSame(pointer, fragment.installed)
        assertEquals(1, fragment.installs)
    }

    @Test
    fun `ready late applies only to the exact recorded apple music id`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(77L)

        assertNull(fragment.installed)
        assertEquals(0, fragment.installs)

        reapply.onReplacementPublished(42L)
        assertSame(pointer, fragment.installed)
        assertEquals(1, fragment.installs)
    }

    @Test
    fun `ledger is identity based and never matches a distinct fragment with equal state`() {
        val first = LyricsFragment(LyricsItem("42"))
        val second = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(first, ready = { pointer })

        reapply.recordMiss(first, 42L)
        reapply.onReplacementPublished(42L)

        assertSame(pointer, first.installed)
        assertNull(second.installed)
    }

    @Test
    fun `a changed song on the same fragment skips the stale re-entry`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        fragment.c = LyricsItem("67890")
        reapply.onReplacementPublished(42L)

        assertNull(fragment.installed)
        assertEquals(0, fragment.installs)
    }

    @Test
    fun `a detached fragment skips re-entry`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer }, usable = { false })

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(42L)

        assertNull(fragment.installed)
        assertEquals(0, fragment.installs)
    }

    @Test
    fun `a replacement that is no longer ready skips re-entry`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        var ready: Any? = null
        val (reapply, _) = reapply(fragment, ready = { ready })

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(42L)

        assertNull(fragment.installed)
        assertEquals(0, fragment.installs)
    }

    @Test
    fun `duplicate publish callbacks re-enter exactly once`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(42L)
        reapply.onReplacementPublished(42L)

        assertEquals(1, fragment.installs)
        assertSame(pointer, fragment.installed)

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(42L)
        assertEquals(2, fragment.installs)
    }

    @Test
    fun `a newer miss on the same fragment supersedes the older pending id`() {
        val fragment = LyricsFragment(LyricsItem("77"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        reapply.recordMiss(fragment, 77L)
        reapply.onReplacementPublished(42L)

        assertNull(fragment.installed)

        reapply.onReplacementPublished(77L)
        assertSame(pointer, fragment.installed)
        assertEquals(1, fragment.installs)
    }

    @Test
    fun `a publish for an id without a waiting fragment is ignored`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val (reapply, _) = reapply(fragment, ready = { Any() })

        reapply.onReplacementPublished(42L)

        assertEquals(0, fragment.installs)
    }

    @Test
    fun `re-entry failure fails open and is logged once`() {
        val fragment = ThrowingLyricsFragment(LyricsItem("42"))
        val (reapply, logs) = reapply(fragment, ready = { Any() })

        reapply.recordMiss(fragment, 42L)
        reapply.onReplacementPublished(42L)
        reapply.onReplacementPublished(42L)

        assertEquals(1, fragment.attempts)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("ready-late re-entry failed"))
    }

    @Test
    fun `a ready late miss is recorded for a null or valid original without a replacement`() {
        val original = Any()
        val replacement = Any()

        assertTrue(shouldRecordReadyLateMiss(original, null))
        assertTrue(shouldRecordReadyLateMiss(null, null))
        assertFalse(shouldRecordReadyLateMiss(original, replacement))
        assertFalse(shouldRecordReadyLateMiss(null, replacement))
    }

    @Test
    fun `is added predicate fails open when the fragment exposes no platform lifecycle method`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        assertTrue(fragmentIsAddedPredicate(fragment.javaClass)(fragment))
    }

    @Test
    fun `is added predicate reflects a platform isAdded method when present`() {
        val fragment = AddedLyricsFragment(LyricsItem("42"))
        val predicate = fragmentIsAddedPredicate(fragment.javaClass)

        assertTrue(predicate(fragment))
        fragment.added = false
        assertFalse(predicate(fragment))
    }

    @Test
    fun `one publish reapplies every still-live waiting fragment for the same id`() {
        val first = LyricsFragment(LyricsItem("42"))
        val second = LyricsFragment(LyricsItem("42"))
        val other = LyricsFragment(LyricsItem("77"))
        val pointer = Any()
        val (reapply, _) = reapply(first, ready = { pointer })

        reapply.recordMiss(first, 42L)
        reapply.recordMiss(second, 42L)
        reapply.recordMiss(other, 77L)
        reapply.onReplacementPublished(42L)

        assertSame(pointer, first.installed)
        assertEquals(1, first.installs)
        assertSame(pointer, second.installed)
        assertEquals(1, second.installs)
        assertNull(other.installed)
        assertEquals(0, other.installs)

        reapply.onReplacementPublished(42L)
        reapply.onReplacementPublished(77L)

        assertEquals(1, first.installs)
        assertEquals(1, second.installs)
        assertSame(pointer, other.installed)
        assertEquals(1, other.installs)
    }

    @Test
    fun `a failing re-entry is consumed once without blocking other waiting fragments`() {
        val failing = LyricsFragment(LyricsItem("42"), failOnInstall = true)
        val healthy = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, logs) = reapply(healthy, ready = { pointer })

        reapply.recordMiss(failing, 42L)
        reapply.recordMiss(healthy, 42L)
        reapply.onReplacementPublished(42L)
        reapply.onReplacementPublished(42L)

        assertEquals(1, failing.attempts)
        assertEquals(1, healthy.installs)
        assertSame(pointer, healthy.installed)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("ready-late re-entry failed"))
    }

    @Test
    fun `dismiss drops a pending entry so a ready late publish cannot double install`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(fragment, ready = { pointer })

        reapply.recordMiss(fragment, 42L)
        reapply.dismiss(fragment)
        reapply.onReplacementPublished(42L)

        assertEquals(0, fragment.installs)
        assertNull(fragment.installed)
    }

    @Test
    fun `cleared fragment entries are never re-applied and do not accumulate`() {
        val fragment = LyricsFragment(LyricsItem("42"))
        val live = LyricsFragment(LyricsItem("42"))
        val pointer = Any()
        val (reapply, _) = reapply(live, ready = { pointer })
        val pending = pendingLedger(reapply)

        reapply.recordMiss(fragment, 42L)
        reapply.recordMiss(live, 42L)
        assertEquals(2, pending.size)

        pending.forEach { entry ->
            val pendingEntry = requireNotNull(entry)
            val key = pendingEntry.javaClass.getDeclaredField("key")
                .apply { isAccessible = true }
                .get(pendingEntry) as WeakReference<*>
            key.clear()
        }
        reapply.onReplacementPublished(42L)

        assertEquals(0, fragment.installs)
        assertEquals(0, live.installs)
        assertEquals(0, pending.size)

        reapply.recordMiss(live, 42L)
        reapply.onReplacementPublished(42L)
        assertSame(pointer, live.installed)
        assertEquals(0, pending.size)
    }

    private fun reapply(
        fragment: Any,
        ready: (Long) -> Any? = { null },
        usable: (Any) -> Boolean = { true },
    ): Pair<CustomLyricsReadyReapply, MutableList<String>> {
        val logs = mutableListOf<String>()
        val fragmentClass = fragment.javaClass
        val installMethod = fragmentClass.getDeclaredMethod("I2", Any::class.java)
        val seam = CurrentItemIdentitySeam(
            ReadyLateResolver(
                field = fragmentClass.getDeclaredField("c"),
                installMethod = installMethod,
            ),
        )
        seam.resolve(installMethod)
        return CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = ready,
            isFragmentUsable = usable,
            logger = { logs += it },
        ) to logs
    }

    private fun pendingLedger(reapply: CustomLyricsReadyReapply): MutableList<*> {
        val field = CustomLyricsReadyReapply::class.java.getDeclaredField("pending")
        field.isAccessible = true
        return field.get(reapply) as MutableList<*>
    }

    private class ReadyLateResolver(
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

    private class LyricsItem(private val id: String) {
        fun getId(): String = id
    }

    private class LyricsFragment(item: LyricsItem?, private val failOnInstall: Boolean = false) {
        @JvmField
        var c: LyricsItem? = item
        var installed: Any? = null
        var installs: Int = 0
        var attempts: Int = 0

        @Suppress("UNUSED_PARAMETER")
        fun I2(ptr: Any) {
            attempts += 1
            if (failOnInstall) throw IllegalStateException("I2 exploded")
            installed = ptr
            installs += 1
        }
    }

    private class AddedLyricsFragment(item: LyricsItem?) {
        @JvmField
        var c: LyricsItem? = item
        var added: Boolean = true

        @Suppress("UNUSED_PARAMETER")
        fun isAdded(): Boolean = added

        @Suppress("UNUSED_PARAMETER")
        fun I2(ptr: Any) = Unit
    }

    private class ThrowingLyricsFragment(item: LyricsItem?) {
        @JvmField
        var c: LyricsItem? = item
        var attempts: Int = 0

        @Suppress("UNUSED_PARAMETER")
        fun I2(ptr: Any) {
            attempts += 1
            throw IllegalStateException("I2 exploded")
        }
    }
}
