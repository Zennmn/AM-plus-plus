package dev.amenhancer.module.lyrics

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal NetEase EAPI encoding for the `/api/song/lyric/v1` endpoint.
 *
 * Algorithm verified against NeteaseCloudMusicApi (Binaryify JS original and
 * the wwh1004 C# port) and lx-music-desktop's `wy/lyric.js`:
 * - `json` is the exact request object in fixed key order;
 * - `digest = md5("nobody" + path + "use" + json + "md5forencrypt")` (lower hex);
 * - `message = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest`;
 * - `params = AES-128-ECB(message, key "e82ckenh8dichen8", PKCS5 padding)`
 *   as uppercase hex; the body is `params=<uppercase hex>`.
 *
 * Pure Kotlin (no Android state) so the encoding is deterministically
 * unit-testable without network access.
 */
object NeteaseEapi {

    const val LYRIC_V1_PATH = "/api/song/lyric/v1"
    const val LYRIC_V1_URL = "https://interface3.music.163.com/eapi/song/lyric/v1"

    private const val AES_KEY = "e82ckenh8dichen8"
    private const val MAGIC = "36cd479b6b5"
    private const val PREFIX = "nobody"
    private const val SUFFIX = "md5forencrypt"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"

    /** The `params` form value for a lyric request; body is `params=<value>`. */
    fun lyricV1Params(songId: Long): String {
        val json = lyricV1Json(songId)
        val message = buildString {
            append(PREFIX)
            append(LYRIC_V1_PATH)
            append("use")
            append(json)
            append(SUFFIX)
        }
        val digest = md5Hex(message)
        val data = buildString {
            append(LYRIC_V1_PATH)
            append('-').append(MAGIC).append('-')
            append(json)
            append('-').append(MAGIC).append('-')
            append(digest)
        }
        return aesEcbEncryptHex(data)
    }

    /** Exact request JSON, key order fixed by the upstream implementation. */
    internal fun lyricV1Json(songId: Long): String = buildString {
        append("{\"id\":").append(songId)
        append(",\"cp\":false")
        append(",\"tv\":0")
        append(",\"lv\":0")
        append(",\"rv\":0")
        append(",\"kv\":0")
        append(",\"yv\":0")
        append(",\"ytv\":0")
        append(",\"yrv\":0}")
    }

    internal fun md5Hex(message: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(message.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_LOWER[value ushr 4])
                append(HEX_LOWER[value and 0x0F])
            }
        }
    }

    internal fun aesEcbEncryptHex(data: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return buildString(encrypted.size * 2) {
            encrypted.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_UPPER[value ushr 4])
                append(HEX_UPPER[value and 0x0F])
            }
        }
    }

    private const val HEX_LOWER = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"
}
