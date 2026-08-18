package dev.amenhancer.module.config

import android.content.SharedPreferences
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostPrivateEmbeddedStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `settings and files survive a new session over the same host storage`() {
        val values = mutableMapOf<String, Any>()
        val preferences = preferences(values)
        val directory = temporaryFolder.newFolder("embedded-files")
        val first = EmbeddedConfigurationSession(
            HostPrivateEmbeddedStorage(preferences, directory),
        )
        val bytes = "embedded".toByteArray()

        assertTrue(first.saveSettings(ModuleSettings(dualPaneEnabled = false)))
        assertTrue(first.writeFile("lyrics_42", bytes))

        val restored = EmbeddedConfigurationSession(
            HostPrivateEmbeddedStorage(preferences, directory),
        )
        assertFalse(restored.settings().dualPaneEnabled)
        assertArrayEquals(bytes, restored.openFile("lyrics_42")?.readBytes())
    }

    @Test
    fun `file names cannot escape the embedded storage directory`() {
        val storage = HostPrivateEmbeddedStorage(
            preferences(mutableMapOf()),
            temporaryFolder.newFolder("embedded-files"),
        )

        assertFalse(storage.writeFile("../outside", byteArrayOf(1)))
        assertTrue(storage.openFile("../outside") == null)
        assertFalse(storage.deleteFile("../outside"))
    }

    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putLong", "putString" -> {
                    values[args[0] as String] = args[1] as Any
                    editor
                }
                "commit" -> true
                "apply" -> null
                "toString" -> "host-private-editor"
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor
                "contains" -> values.containsKey(args[0] as String)
                "toString" -> "host-private-preferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> null
            }
        } as SharedPreferences
    }
}
