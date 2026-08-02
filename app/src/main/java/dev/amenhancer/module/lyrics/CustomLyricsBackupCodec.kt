package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsManifestCodec
import dev.amenhancer.module.config.CustomLyricsManifestPolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
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

/** Decoded backup payload: the manifest plus one validated TTML file per entry. */
internal data class CustomLyricsBackup(
    val manifest: CustomLyricsManifest,
    val files: Map<String, ByteArray>,
)

/**
 * Bounded ZIP backup format: exactly one [MANIFEST_JSON_NAME] plus one file
 * per manifest entry named by its fileId. Every TTML body passes the same
 * size/hash/TTML-policy validation as [CustomLyricsFileReader]; decode never
 * touches disk and rejects directories, duplicates, missing or extra files,
 * invalid names, unsupported versions, and anything exceeding the size caps.
 */
internal object CustomLyricsBackupCodec {

    private const val MANIFEST_JSON_NAME = "manifest.json"
    private const val MAX_MANIFEST_JSON_BYTES = 64 * 1024
    private const val MAX_ZIP_ENTRIES = CustomLyricsManifestPolicy.MAX_ENTRIES + 1
    private val MAX_TOTAL_TTML_BYTES =
        TtmlInputPolicy.MAX_TTML_BYTES.toLong() * CustomLyricsManifestPolicy.MAX_ENTRIES

    /** Writes and closes [out]; fails the whole backup if any entry read fails. */
    fun encode(
        manifest: CustomLyricsManifest,
        readRemoteFile: (String) -> ByteArray?,
        out: OutputStream,
    ): CustomLyricsBackupEncodeResult {
        val safe = CustomLyricsManifestPolicy.sanitize(manifest)
        if (safe.entries.size != manifest.entries.size) {
            return CustomLyricsBackupEncodeResult.Failed("歌词映射无效，无法备份")
        }
        val reader = CustomLyricsFileReader(readRemoteFile)
        val payload = buildList {
            safe.entries.forEach { entry ->
                val ttml = reader.read(entry)
                    ?: return CustomLyricsBackupEncodeResult.Failed("读取歌词文件失败：${entry.displayName}")
                add(entry to ttml.toByteArray(Charsets.UTF_8))
            }
        }
        return try {
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_JSON_NAME))
                zip.write(CustomLyricsManifestCodec.encode(safe).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                payload.forEach { (entry, bytes) ->
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

    /** Reads and closes [input]; returns only fully validated payloads. */
    fun decode(input: InputStream): CustomLyricsBackupDecodeResult {
        var manifestJson: ByteArray? = null
        val files = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L
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
                        manifestJson = CustomLyricsFilePolicy.readBounded(zip)
                        if (manifestJson.size > MAX_MANIFEST_JSON_BYTES) {
                            return CustomLyricsBackupDecodeResult.Rejected("manifest.json 超出大小上限")
                        }
                    } else {
                        if (!CustomLyricsManifestPolicy.isValidFileId(name)) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份包含非法文件名：$name")
                        }
                        if (files.containsKey(name)) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份包含重复文件：$name")
                        }
                        val bytes = CustomLyricsFilePolicy.readBounded(zip)
                        files[name] = bytes
                        totalBytes += bytes.size
                        if (totalBytes > MAX_TOTAL_TTML_BYTES) {
                            return CustomLyricsBackupDecodeResult.Rejected("备份解压总量超过上限")
                        }
                    }
                    zip.closeEntry()
                }
                val raw = manifestJson
                    ?: return CustomLyricsBackupDecodeResult.Rejected("备份缺少 manifest.json")
                val manifest = CustomLyricsManifestCodec.decodeStrict(raw.toString(Charsets.UTF_8))
                    ?: return CustomLyricsBackupDecodeResult.Rejected("manifest.json 无效或不支持的版本")
                val expectedFileIds = manifest.entries.map(CustomLyricsEntry::fileId)
                if (
                    expectedFileIds.size != expectedFileIds.toSet().size ||
                    expectedFileIds.toSet() != files.keys
                ) {
                    return CustomLyricsBackupDecodeResult.Rejected("备份文件与映射不一致")
                }
                val reader = CustomLyricsFileReader { fileId -> files[fileId] }
                manifest.entries.forEach { entry ->
                    if (reader.read(entry) == null) {
                        return CustomLyricsBackupDecodeResult.Rejected("歌词文件校验失败：${entry.displayName}")
                    }
                }
                CustomLyricsBackupDecodeResult.Decoded(CustomLyricsBackup(manifest, files))
            }
        } catch (e: CustomLyricsFilePolicy.SizeLimitExceeded) {
            CustomLyricsBackupDecodeResult.Rejected("备份解压超过大小上限")
        } catch (e: IOException) {
            CustomLyricsBackupDecodeResult.Rejected("读取备份失败")
        }
    }
}
