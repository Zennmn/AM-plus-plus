package dev.amenhancer.module.lyrics

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseEapiTest {

    @Test
    fun `lyric v1 json keeps the upstream key order`() {
        assertEquals(
            "{\"id\":314159,\"cp\":false,\"tv\":0,\"lv\":0,\"rv\":0,\"kv\":0,\"yv\":0,\"ytv\":0,\"yrv\":0}",
            NeteaseEapi.lyricV1Json(314159),
        )
    }

    @Test
    fun `digest matches the published nobody-use-md5forencrypt vector`() {
        val json = NeteaseEapi.lyricV1Json(314159)
        val message = "nobody/api/song/lyric/v1use$json" + "md5forencrypt"

        assertEquals("db59d4b77c7ee845d41a3f102937f76f", NeteaseEapi.md5Hex(message))
    }

    @Test
    fun `params are uppercase hex of the AES ECB encrypted message`() {
        val params = NeteaseEapi.lyricV1Params(314159)

        assertTrue(params.isNotEmpty())
        assertTrue(params.length % 32 == 0) // 16-byte AES blocks as hex
        assertTrue(params.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `params decrypt back to the canonical message`() {
        val json = NeteaseEapi.lyricV1Json(314159)
        val message = "nobody/api/song/lyric/v1use$json" + "md5forencrypt"
        val digest = NeteaseEapi.md5Hex(message)
        val expected = "/api/song/lyric/v1-36cd479b6b5-$json-36cd479b6b5-$digest"

        val params = NeteaseEapi.lyricV1Params(314159)
        val decrypted = aesEcbDecrypt(params)

        assertEquals(expected, decrypted)
    }

    private fun aesEcbDecrypt(uppercaseHex: String): String {
        val bytes = ByteArray(uppercaseHex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = uppercaseHex.substring(index * 2, index * 2 + 2)
                .toInt(16)
                .toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.UTF_8), "AES"),
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }
}
