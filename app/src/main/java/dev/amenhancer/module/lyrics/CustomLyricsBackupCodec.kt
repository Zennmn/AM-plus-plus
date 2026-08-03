package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestCodec
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal sealed interface CustomLyricsBackupEncodeResult {
    data class Encoded(val entryCount: Int) : CustomLyricsBackupEncodeResult
    data class Failed(val message: String) : CustomLyricsBackupEncodeResult
}

internal sealed interface CustomLyricsBackupDecodeResult {
    data class Decoded(val backup: CustomLyricsBackup) : CustomLyricsBackupDecodeResult
    data class Rejected(val message: String) : CustomLyricsBackupDecodeResult
}

/**
 * Decoded backup payload: the validated manifest; TTML bodies are delivered
 * one at a time by [CustomLyricsBackupCodec.decode] and never held together.
 */
internal data class CustomLyricsBackup(
    val manifest: CustomLyricsManifest,
)

/**
 * Streaming bounded ZIP backup format: exactly one [MANIFEST_JSON_NAME] as the
 * first entry plus one file per manifest entry named by its fileId. At most
 * one TTML body is in memory at a time: encode reads and validates each
 * remote file just before writing its ZIP entry; decode delivers each fully
 * validated file through its [onFile] as the scan proceeds and retains only
 * the manifest. Every TTML body passes the same size/hash/TTML-policy
 * validation as [CustomLyricsFileReader]; decode never touches disk and
 * rejects directories, duplicates, files before the manifest, invalid names,
 * unsupported versions, missing or extra files, and anything exceeding the
 * resource guards below.
 */
internal object CustomLyricsBackupCodec {

    private const val MANIFEST_JSON_NAME = "manifest.json"
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_APPLE_MUSIC_ID = "appleMusicId"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_FILE_ID = "fileId"
    private const val KEY_SIZE_BYTES = "sizeBytes"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_SOURCE = "source"
    private const val KEY_ENABLED = "enabled"

    /**
     * Independent resource guards for untrusted backups, not user-facing
     * feature limits. Any manifest restorable into the 8 MiB index budget
     * stays far below them: a sanitized entry encodes to at least ~166 JSON
     * bytes (the SHA-256 alone is 64), so the entry-count guard derived from
     * that budget with a 128-byte floor can never be hit by a valid backup.
     */
    private const val MAX_MANIFEST_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_TTML_BYTES = 256L * 1024 * 1024
    private const val MIN_ENTRY_JSON_BYTES = 128
    private const val MAX_ZIP_ENTRIES = MAX_MANIFEST_JSON_BYTES / MIN_ENTRY_JSON_BYTES + 1

    /**
     * Writes and closes [out]; fails the whole backup as soon as any entry
     * cannot be read or validated. Each TTML body is read, validated, and
     * written before the next one is read. A failed backup leaves at most a
     * partial ZIP that decode always rejects, because decode requires every
     * manifest file to be present.
     */
    fun encode(
        manifest: CustomLyricsManifest,
        readRemoteFile: (String) -> ByteArray?,
        out: OutputStream,
    ): CustomLyricsBackupEncodeResult {
        val safe = CustomLyricsManifestPolicy.sanitize(manifest)
        if (safe.entries.size != manifest.entries.size) {
            return CustomLyricsBackupEncodeResult.Failed("歌词映射无效，无法备份")
        }
        val manifestBytes = CustomLyricsManifestCodec.encode(safe).toByteArray(Charsets.UTF_8)
        if (manifestBytes.size > MAX_MANIFEST_JSON_BYTES) {
            return CustomLyricsBackupEncodeResult.Failed("歌词索引超出备份大小上限")
        }
        if (safe.entries.sumOf(CustomLyricsEntry::sizeBytes) > MAX_TOTAL_TTML_BYTES) {
            return CustomLyricsBackupEncodeResult.Failed("歌词备份总量超过上限")
        }
        return try {
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_JSON_NAME))
                zip.write(manifestBytes)
                zip.closeEntry()
                safe.entries.forEach { entry ->
                    val bytes = runCatching { readRemoteFile(entry.fileId) }.getOrNull()
                        ?: return CustomLyricsBackupEncodeResult.Failed("读取歌词文件失败：${entry.displayName}")
                    if (
                        bytes.size.toLong() != entry.sizeBytes ||
                        !CustomLyricsFilePolicy.sha256(bytes).equals(entry.sha256, ignoreCase = true) ||
                        CustomLyricsFilePolicy.inspect(bytes.toString(Charsets.UTF_8))
                            !is CustomLyricsInspection.Accepted
                    ) {
                        return CustomLyricsBackupEncodeResult.Failed("读取歌词文件失败：${entry.displayName}")
                    }
                    zip.putNextEntry(ZipEntry(entry.fileId))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            CustomLyricsBackupEncodeResult.Encoded(safe.entries.size)
        } catch (e: IOException) {
            CustomLyricsBackupEncodeResult.Failed("写入备份失败")
        }
    }

    /**
     * Single-pass streaming decode: reads and closes [input], calling [onFile]
     * once per fully validated TTML file before returning. `manifest.json`
     * must be the first entry; every file entry is validated against the
     * manifest (size, SHA-256, TTML policy) before delivery, so [onFile] never
     * sees unvalidated bytes. Returns only fully validated payloads.
     */
    fun decode(
        input: InputStream,
        onFile: (fileId: String, bytes: ByteArray) -> Unit,
    ): CustomLyricsBackupDecodeResult {
        var manifestJson: ByteArray? = null
        var manifest: CustomLyricsManifest? = null
        var expectedByFileId: Map<String, CustomLyricsEntry>? = null
        val seenFileIds = mutableSetOf<String>()
        var entryCount = 0
        var totalTtmlBytes = 0L
        return try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        return CustomLyricsBackupDecodeResult.Rejected("备份条目过多")
                    }
                    if (entry.isDirectory) {
                        return CustomLyricsBackupDecodeResult.Rejected("备份包含目录条目")
                    }
                    val name = entry.name
                    if (name == MANIFEST_JSON_NAME) {
                        if (manifestJson != null) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份包含重复的 manifest.json")
                        }
                        manifestJson = CustomLyricsFilePolicy.readBounded(zip, MAX_MANIFEST_JSON_BYTES)
                        val parsed = parseManifestStrict(manifestJson.toString(Charsets.UTF_8))
                            ?: return CustomLyricsBackupDecodeResult.Rejected("manifest.json 无效或不支持的版本")
                        if (parsed.entries.map(CustomLyricsEntry::fileId).distinct().size != parsed.entries.size) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份文件与映射不一致")
                        }
                        manifest = parsed
                        expectedByFileId = parsed.entries.associateBy(CustomLyricsEntry::fileId)
                    } else {
                        if (!CustomLyricsManifestPolicy.isValidFileId(name)) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份包含非法文件名：$name")
                        }
                        if (!seenFileIds.add(name)) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份包含重复文件：$name")
                        }
                        val bytes = CustomLyricsFilePolicy.readBounded(zip)
                        totalTtmlBytes += bytes.size
                        if (totalTtmlBytes > MAX_TOTAL_TTML_BYTES) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份解压总量超过上限")
                        }
                        val expected = expectedByFileId?.get(name)
                            ?: return CustomLyricsBackupDecodeResult.Rejected(
                                if (manifestJson == null) "备份缺少 manifest.json" else "备份文件与映射不一致",
                            )
                        if (
                            bytes.size.toLong() != expected.sizeBytes ||
                            !CustomLyricsFilePolicy.sha256(bytes).equals(expected.sha256, ignoreCase = true) ||
                            CustomLyricsFilePolicy.inspect(bytes.toString(Charsets.UTF_8))
                                !is CustomLyricsInspection.Accepted
                        ) {
                            return CustomLyricsBackupDecodeResult.Rejected("歌词文件校验失败：${expected.displayName}")
                        }
                        onFile(name, bytes)
                    }
                    zip.closeEntry()
                }
                val parsed = manifest
                    ?: return CustomLyricsBackupDecodeResult.Rejected("备份缺少 manifest.json")
                if (parsed.entries.any { it.fileId !in seenFileIds }) {
                    return CustomLyricsBackupDecodeResult.Rejected("备份文件与映射不一致")
                }
                CustomLyricsBackupDecodeResult.Decoded(CustomLyricsBackup(parsed))
            }
        } catch (e: CustomLyricsFilePolicy.SizeLimitExceeded) {
            CustomLyricsBackupDecodeResult.Rejected("备份解压超过大小上限")
        } catch (e: IOException) {
            CustomLyricsBackupDecodeResult.Rejected("读取备份失败")
        }
    }

    /**
     * Strict manifest decode for backups: mirrors the config codec's strict
     * decode minus the fixed entry cap, so backups beyond 32 entries restore.
     * Rejects foreign versions, malformed payloads, and any entry that policy
     * would alter or deduplicate.
     */
    private fun parseManifestStrict(raw: String): CustomLyricsManifest? {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return null
        }
        val version = root.optInt(KEY_VERSION, 0)
        if (version != VERSION && version != LEGACY_VERSION) return null
        val entries = root.optJSONArray(KEY_ENTRIES) ?: return null
        if (entries.length() > MAX_ZIP_ENTRIES) return null
        val parsed = mutableListOf<CustomLyricsEntry>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: return null
            parsed += parseEntry(entry)
        }
        val sanitized = CustomLyricsManifestPolicy.sanitize(CustomLyricsManifest(parsed))
        return sanitized.takeIf { it.entries.size == parsed.size }
    }

    private fun parseEntry(entry: JSONObject): CustomLyricsEntry = CustomLyricsEntry(
        appleMusicId = entry.optLong(KEY_APPLE_MUSIC_ID, 0L),
        displayName = entry.optString(KEY_DISPLAY_NAME),
        fileId = entry.optString(KEY_FILE_ID),
        sizeBytes = entry.optLong(KEY_SIZE_BYTES, 0L),
        sha256 = entry.optString(KEY_SHA256),
        source = entry.optString(KEY_SOURCE),
        enabled = entry.optBoolean(KEY_ENABLED, false),
    )
}
