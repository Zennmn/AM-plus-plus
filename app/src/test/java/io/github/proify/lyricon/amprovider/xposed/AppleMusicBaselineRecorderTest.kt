package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppleMusicBaselineRecorderTest {
    private val point = AppleMusicHookPoint.ARTIST_SURFACE_CLASSES
    private val target = AppleMusicHookTarget("host.ArtistModel")

    @Test
    fun `repeated binding neither rebuilds descriptors nor submits preferences`() {
        val store = Store()
        val recorder = AppleMusicBaselineRecorder { store.preferences }
        var builds = 0
        repeat(1000) {
            recorder.record(point, target, String::class.java, target.className) {
                builds++
                mapOf("class" to "shape", "member" to "descriptor")
            }
        }
        assertEquals(1, builds)
        assertEquals(1, store.applies)
        assertEquals(mapOf("class" to "shape", "member" to "descriptor"), store.values)
    }

    @Test
    fun `new recorder validates again but unchanged values need no write`() {
        val store = Store()
        var builds = 0
        repeat(2) {
            AppleMusicBaselineRecorder { store.preferences }
                .record(point, target, String::class.java, target.className) {
                    builds++
                    mapOf("class" to "same-shape")
                }
        }
        assertEquals(2, builds)
        assertEquals(1, store.applies)
    }

    @Test
    fun `descriptor failure and preference submission failure remain retryable`() {
        val store = Store()
        val recorder = AppleMusicBaselineRecorder { store.preferences }
        assertThrows(IllegalStateException::class.java) {
            recorder.record(point, target, String::class.java, target.className) { error("encoding failed") }
        }
        store.failApply = true
        assertThrows(IllegalStateException::class.java) {
            recorder.record(point, target, String::class.java, target.className) { mapOf("class" to "shape") }
        }
        store.failApply = false
        recorder.record(point, target, String::class.java, target.className) { mapOf("class" to "shape") }
        assertEquals("shape", store.values["class"])
        assertEquals(1, store.applies)
    }

    @Test
    fun `changed runtime class and member mapping are recorded separately`() {
        val store = Store()
        val recorder = AppleMusicBaselineRecorder { store.preferences }
        val members = mutableMapOf(AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD to "a")
        val mutableTarget = target.copy(runtimeMemberNames = members)
        var builds = 0
        fun record(clazz: Class<*>) = recorder.record(point, mutableTarget, clazz, target.className) {
            mapOf("class" to "shape-${++builds}")
        }
        record(String::class.java)
        record(Int::class.javaObjectType)
        members[AppleMusicRuntimeMember.ARTIST_HEADER_TITLE_FIELD] = "b"
        record(Int::class.javaObjectType)
        record(Int::class.javaObjectType)
        assertEquals(3, builds)
        assertEquals(3, store.applies)
    }

    @Test
    fun `concurrent callers generate and submit one baseline`() {
        val store = Store()
        val recorder = AppleMusicBaselineRecorder { store.preferences }
        val builds = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map {
                pool.submit(Callable {
                    start.await()
                    recorder.record(point, target, String::class.java, target.className) {
                        builds.incrementAndGet()
                        mapOf("class" to "shape")
                    }
                })
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, builds.get())
            assertEquals(1, store.applies)
        } finally {
            pool.shutdownNow()
        }
    }

    private class Store {
        val values = mutableMapOf<String, String>()
        var applies = 0
        var failApply = false
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        pending[args!![0] as String] = args[1] as String
                        proxy
                    }
                    "apply" -> {
                        check(!failApply) { "submission failed" }
                        values.putAll(pending)
                        applies++
                        null
                    }
                    else -> error("Unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
