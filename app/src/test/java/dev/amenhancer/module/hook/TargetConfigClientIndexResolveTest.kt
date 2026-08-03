package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.CustomLyricsEntry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetConfigClientIndexResolveTest {

    @Test
    fun `settings without a pointer falls back to the legacy v1 manifest`() {
        val v1 = """{"version":1,"entries":[{"appleMusicId":42,"displayName":"Old","fileId":"lyrics_old","sizeBytes":42,"sha256":"0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53","source":"manual","enabled":true}]}"""
        val client = TargetConfigClient(
            preferences(
                mapOf(
                    "custom_lyrics_enabled" to true,
                    "custom_lyrics_manifest" to v1,
                ),
            ),
        )

        val manifest = client.settings().customLyricsManifest

        assertEquals(listOf(42L), manifest.entries.map(CustomLyricsEntry::appleMusicId))
    }

    @Test
    fun `settings with neither pointer nor legacy yields an empty manifest`() {
        val client = TargetConfigClient(
            preferences(
                mapOf("custom_lyrics_enabled" to true),
            ),
        )

        assertTrue(client.settings().customLyricsManifest.entries.isEmpty())
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> values
                "toString" -> "target-config-index-resolve-test-preferences"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SharedPreferences
}
