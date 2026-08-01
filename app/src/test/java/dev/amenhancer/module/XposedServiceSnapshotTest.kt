package dev.amenhancer.module

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedServiceSnapshotTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `connected snapshot publishes preferences and matching status together`() {
        val preferences = fakePreferences()
        val snapshot = XposedServiceSnapshot.connected(
            preferences = preferences,
            frameworkName = "LSPosed",
            apiVersion = 102,
        )

        assertSame(preferences, snapshot.preferences)
        assertTrue(snapshot.isRemoteAvailable)
        assertFalse(snapshot.isRemoteFileAvailable)
        assertEquals("已连接 LSPosed API 102", snapshot.status)
    }

    @Test
    fun `non-connected snapshots never expose writable preferences`() {
        val waiting = XposedServiceSnapshot.waiting()
        val unsupported = XposedServiceSnapshot.unsupported("Legacy", 100)
        val disconnected = XposedServiceSnapshot.disconnected()

        listOf(waiting, unsupported, disconnected).forEach { snapshot ->
            assertNull(snapshot.preferences)
            assertNull(snapshot.service)
            assertFalse(snapshot.isRemoteAvailable)
            assertFalse(snapshot.isRemoteFileAvailable)
        }
        assertEquals("等待 libxposed API 102 服务", waiting.status)
        assertEquals(
            "Legacy API 100 不支持 API 102 remote preferences",
            unsupported.status,
        )
        assertEquals("libxposed 服务连接已断开", disconnected.status)
    }

    @Test
    fun `application store and settings render consume one snapshot interface`() {
        val application = source("dev/amenhancer/module/ModuleApplication.kt")
        val store = source("dev/amenhancer/module/config/ConfigStore.kt")
        val settings = source("dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(application.contains("AtomicReference(XposedServiceSnapshot.waiting())"))
        assertTrue(application.contains("publish(XposedServiceSnapshot.connected("))
        assertTrue(application.contains("service = service"))
        assertTrue(application.contains("listeners.forEach { it(snapshot) }"))
        assertFalse(application.contains("var remotePreferences"))
        assertFalse(application.contains("var serviceStatus"))
        assertTrue(store.contains("fun settings(snapshot: XposedServiceSnapshot)"))
        assertTrue(store.contains("snapshot.preferences ?: legacyPreferences"))
        assertTrue(store.contains("snapshot.isRemoteFileAvailable"))
        assertTrue(settings.contains(
            "private fun render(snapshot: XposedServiceSnapshot = ModuleApplication.serviceSnapshot)",
        ))
        assertTrue(settings.contains("statusCard(snapshot)"))
        assertFalse(settings.contains("ModuleApplication.serviceStatus"))
    }

    private fun fakePreferences(): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "fake-preferences"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    } as SharedPreferences
}
