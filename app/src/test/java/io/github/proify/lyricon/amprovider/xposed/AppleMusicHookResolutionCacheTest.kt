package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppleMusicHookResolutionCacheTest {
    private val version = AppleMusicVersion("6.5.2", 1586L)
    private val point = AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS

    @Test
    fun `complete profile lookup is reused across repeated bindings`() {
        val calls = AtomicInteger()
        val resolver = AppleMusicHookResolver(version, classLookup = {
            calls.incrementAndGet()
            String::class.java
        })
        val first = resolver.resolveClasses(point)
        assertTrue(first.isNotEmpty())
        val initialCalls = calls.get()
        repeat(1000) { assertSame(first, resolver.resolveClasses(point)) }
        assertEquals(initialCalls, calls.get())
    }

    @Test
    fun `unavailable classes can resolve on a later attempt`() {
        var available = false
        val resolver = AppleMusicHookResolver(version, classLookup = { name ->
            if (!available) throw ClassNotFoundException(name)
            String::class.java
        })
        assertTrue(resolver.resolveClasses(point).isEmpty())
        available = true
        assertTrue(resolver.resolveClasses(point).isNotEmpty())
    }

    @Test
    fun `partial artist group does not freeze missing roles`() {
        val artistPoint = AppleMusicHookPoint.ARTIST_SURFACE_CLASSES
        val targets = AppleMusicHookProfiles.candidates(version, artistPoint)
        val fixtureClasses = listOf(
            String::class.java, Int::class.javaObjectType, Long::class.javaObjectType,
            Boolean::class.javaObjectType, Double::class.javaObjectType, Float::class.javaObjectType,
            Byte::class.javaObjectType, Short::class.javaObjectType, Char::class.javaObjectType,
            Any::class.java, List::class.java, Map::class.java,
        )
        assertTrue(targets.size > 1 && targets.size <= fixtureClasses.size)
        val classes = targets.mapIndexed { index, target -> target.className to fixtureClasses[index] }.toMap()
        var available = setOf(targets.first().className)
        val resolver = AppleMusicHookResolver(version, classLookup = { name ->
            classes[name]?.takeIf { name in available } ?: throw ClassNotFoundException(name)
        })
        assertEquals(1, resolver.resolveClasses(artistPoint).size)
        available = classes.keys
        val complete = resolver.resolveClasses(artistPoint)
        assertEquals(targets.size, complete.size)
        assertSame(complete, resolver.resolveClasses(artistPoint))
    }

    @Test
    fun `different host resolver does not reuse another class loader result`() {
        val first = AppleMusicHookResolver(version, classLookup = { String::class.java })
        val second = AppleMusicHookResolver(version, classLookup = { Int::class.javaObjectType })
        assertEquals(String::class.java, first.resolveClasses(point).single().clazz)
        assertEquals(Int::class.javaObjectType, second.resolveClasses(point).single().clazz)
    }

    @Test
    fun `concurrent callers share a completed resolution`() {
        val calls = AtomicInteger()
        val resolver = AppleMusicHookResolver(version, classLookup = {
            calls.incrementAndGet()
            String::class.java
        })
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                pool.submit(Callable { start.await(); resolver.resolveClasses(point) })
            }
            start.countDown()
            val first = results.first().get(5, TimeUnit.SECONDS)
            results.forEach { assertSame(first, it.get(5, TimeUnit.SECONDS)) }
            val afterResolution = calls.get()
            assertSame(first, resolver.resolveClasses(point))
            assertEquals(afterResolution, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
