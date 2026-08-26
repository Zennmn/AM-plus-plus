package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Persists embedded settings and immutable payloads inside Apple Music's data directory. */
internal class HostPrivateEmbeddedStorage(
    private val preferences: SharedPreferences,
    private val directory: File,
) : EmbeddedConfigurationStorage {
    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        directory = File(context.applicationContext.filesDir, FILES_DIRECTORY_NAME),
    )

    override fun values(): Map<String, *> = preferences.all

    override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean {
        val editor = preferences.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unsupported embedded configuration value for $key")
            }
        }
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    override fun removeValues(keys: Set<String>, synchronous: Boolean): Boolean {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return if (synchronous) editor.commit() else {
            editor.apply()
            true
        }
    }

    override fun openFile(name: String): InputStream? {
        val file = file(name) ?: return null
        return runCatching { file.takeIf(File::isFile)?.let(::FileInputStream) }.getOrNull()
    }

    override fun openFileDescriptor(name: String): ParcelFileDescriptor? {
        val file = file(name)?.takeIf(File::isFile) ?: return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    override fun writeFile(name: String, bytes: ByteArray): Boolean {
        val destination = file(name) ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val pending = runCatching {
            File.createTempFile("pending_", ".tmp", directory)
        }.getOrNull() ?: return false
        return try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveAtomically(pending, destination)
            syncDirectoryBestEffort()
            true
        } catch (_: Throwable) {
            false
        } finally {
            pending.delete()
        }
    }

    override fun copyFile(
        name: String,
        input: InputStream,
        expectedSizeBytes: Long,
        expectedSha256: String,
    ): Boolean {
        val destination = file(name) ?: return false
        if (expectedSizeBytes < 0L) return false
        if (!directory.exists() && !directory.mkdirs()) return false
        val pending = runCatching {
            File.createTempFile("pending_", ".tmp", directory)
        }.getOrNull() ?: return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var valid = true
            input.use { source ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (valid) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > expectedSizeBytes) {
                            valid = false
                        } else {
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!valid || size != expectedSizeBytes) return false
            if (!digestHex(digest).equals(expectedSha256, ignoreCase = true)) return false
            moveAtomically(pending, destination)
            syncDirectoryBestEffort()
            true
        } catch (_: Throwable) {
            false
        } finally {
            pending.delete()
        }
    }

    override fun fileMatches(
        name: String,
        expectedSizeBytes: Long,
        expectedSha256: String,
    ): Boolean {
        val source = openFile(name) ?: return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            source.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    digest.update(buffer, 0, count)
                }
            }
            size == expectedSizeBytes &&
                digestHex(digest).equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    override fun deleteFile(name: String): Boolean {
        val file = file(name) ?: return false
        return runCatching { file.isFile && file.delete() }.getOrDefault(false)
    }

    override fun hasAnyFiles(): Boolean = runCatching {
        directory.listFiles()?.any(File::isFile) == true
    }.getOrDefault(false)

    private fun file(name: String): File? = name
        .takeIf(FILE_NAME_PATTERN::matches)
        ?.let { File(directory, it) }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** ext4 supports directory fsync; unsupported providers still retain the flushed file. */
    private fun syncDirectoryBestEffort() {
        runCatching {
            java.nio.channels.FileChannel.open(directory.toPath(), StandardOpenOption.READ).use {
                it.force(true)
            }
        }
    }

    private fun digestHex(digest: MessageDigest): String = digest.digest()
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFERENCES_NAME = "ampp-embedded-settings"
        const val FILES_DIRECTORY_NAME = "ampp-embedded-files"
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
