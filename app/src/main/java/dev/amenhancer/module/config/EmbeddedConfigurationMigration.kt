package dev.amenhancer.module.config

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Copies the last libxposed remote configuration into the embedded host
 * storage.  This is deliberately a one-way boundary: after the host storage
 * is initialized, embedded code only reads and writes [EmbeddedConfigurationStorage].
 */
internal object EmbeddedConfigurationMigration {
    /** Marker keys live outside [ModuleSettingsSchema] and are ignored by readers. */
    const val MIGRATION_MARKER_KEY = "embedded_storage_migration_v1"
    const val MIGRATION_IN_PROGRESS = "in_progress"
    const val MIGRATION_COMPLETE = "complete"

    /** Bounds synchronous pre-Application migration for large lyric libraries. */
    const val MAX_TOTAL_TTML_BYTES = 64L * 1024 * 1024

    /** Existing host state must not depend on the legacy remote service. */
    fun destinationAlreadyInitialized(destination: EmbeddedConfigurationStorage): Boolean =
        synchronized(destination) {
            when (destinationState(destination)) {
                DestinationState.COMPLETE,
                DestinationState.OCCUPIED,
                -> true
                DestinationState.EMPTY,
                DestinationState.IN_PROGRESS,
                -> false
            }
        }

    /**
     * Adapter used by the host-process call site, where libxposed exposes
     * remote preferences and file descriptors.
     */
    fun migrate(
        remotePreferences: SharedPreferences,
        remoteFileOpener: (String) -> ParcelFileDescriptor?,
        destination: EmbeddedConfigurationStorage,
    ): EmbeddedConfigurationMigrationResult {
        val remoteValues = runCatching { remotePreferences.all }.getOrNull()
            ?: return EmbeddedConfigurationMigrationResult.Failed("无法读取远程配置")
        return migrate(
            remoteValues = remoteValues,
            openRemoteFile = { name ->
                runCatching { remoteFileOpener(name) }
                    .getOrNull()
                    ?.let { descriptor -> ParcelFileDescriptor.AutoCloseInputStream(descriptor) }
            },
            destination = destination,
        )
    }

    /**
     * Pure storage seam used by tests and by callers that already adapted a
     * remote file descriptor to an [InputStream].
     */
    fun migrate(
        remoteValues: Map<String, *>,
        openRemoteFile: (String) -> InputStream?,
        destination: EmbeddedConfigurationStorage,
    ): EmbeddedConfigurationMigrationResult = synchronized(destination) {
        when (destinationState(destination)) {
            DestinationState.COMPLETE -> {
                return@synchronized EmbeddedConfigurationMigrationResult.SkippedAlreadyComplete
            }
            DestinationState.OCCUPIED -> {
                return@synchronized EmbeddedConfigurationMigrationResult.SkippedDestinationOccupied
            }
            DestinationState.EMPTY,
            DestinationState.IN_PROGRESS,
            -> Unit
        }

        if (remoteValues.isEmpty() || !ModuleSettingsSchema.hasMigratableValues(remoteValues)) {
            return@synchronized EmbeddedConfigurationMigrationResult.SkippedNoRemoteConfiguration
        }

        val plan = buildPlan(remoteValues, openRemoteFile)
            ?: return@synchronized EmbeddedConfigurationMigrationResult.Failed(
                message = "远程配置或文件不可读",
            )

        if (!writeValues(
                destination,
                mapOf(MIGRATION_MARKER_KEY to MIGRATION_IN_PROGRESS),
            )
        ) {
            return@synchronized EmbeddedConfigurationMigrationResult.Failed(
                message = "无法标记宿主存储迁移状态",
            )
        }

        val copied = mutableListOf<String>()
        plan.files.forEach { payload ->
            val existing = runCatching { destination.openFile(payload.name) }.getOrNull()
            if (existing != null) {
                runCatching { existing.close() }
                if (!destination.fileMatches(
                        name = payload.name,
                        expectedSizeBytes = payload.sizeBytes,
                        expectedSha256 = payload.sha256,
                    )
                ) {
                    return@synchronized EmbeddedConfigurationMigrationResult.Failed(
                        message = "宿主文件与远程文件冲突: ${payload.name}",
                        copiedFileIds = copied,
                    )
                }
                copied += payload.name
                return@forEach
            }
            val source = runCatching { payload.openStream() }.getOrNull()
            if (source == null || !runCatching {
                    destination.copyFile(
                        name = payload.name,
                        input = source,
                        expectedSizeBytes = payload.sizeBytes,
                        expectedSha256 = payload.sha256,
                    )
                }.getOrDefault(false)
            ) {
                return@synchronized EmbeddedConfigurationMigrationResult.Failed(
                    message = "无法复制远程文件: ${payload.name}",
                    copiedFileIds = copied,
                )
            }
            copied += payload.name
        }

        val publishedValues = LinkedHashMap<String, Any>(plan.values.size + 1).apply {
            putAll(plan.values)
            put(MIGRATION_MARKER_KEY, MIGRATION_COMPLETE)
        }
        if (!writeValues(destination, publishedValues)) {
            return@synchronized EmbeddedConfigurationMigrationResult.Failed(
                message = "无法发布宿主配置",
                copiedFileIds = copied,
            )
        }
        EmbeddedConfigurationMigrationResult.Migrated(copiedFileIds = copied)
    }

    private fun buildPlan(
        remoteValues: Map<String, *>,
        openRemoteFile: (String) -> InputStream?,
    ): MigrationPlan? = runCatching {
        buildPlanUnsafe(remoteValues, openRemoteFile)
    }.getOrNull()

    private fun buildPlanUnsafe(
        remoteValues: Map<String, *>,
        openRemoteFile: (String) -> InputStream?,
    ): MigrationPlan? {
        val settings = ModuleSettingsSchema.decode(remoteValues)
        val pointer = ModuleSettingsSchema.decodeIndexPointer(remoteValues)
        val indexBytes = mutableMapOf<String, ByteArray?>()
        fun readIndex(fileId: String): ByteArray? {
            if (fileId in indexBytes) return indexBytes[fileId]
            val bytes = readBounded(openRemoteFile, fileId, CustomLyricsManifestPolicy.MAX_INDEX_BYTES)
            indexBytes[fileId] = bytes
            return bytes
        }

        val indexState = CustomLyricsIndexRepository.state(remoteValues) { fileId ->
            readIndex(fileId)?.let(::ByteArrayInputStream)
        }
        if (!indexState.canCommit) return null

        val payloads = linkedMapOf<String, Payload>()
        fun addPayload(payload: Payload): Boolean {
            val previous = payloads[payload.name]
            if (previous != null && (
                    previous.sizeBytes != payload.sizeBytes ||
                        !previous.sha256.equals(payload.sha256, ignoreCase = true)
                    )
            ) return false
            if (previous == null) {
                payloads[payload.name] = payload
            } else {
                // Prefer an already materialized payload when two schema
                // entries reference the same file, but preserve the TTML
                // accounting bit if a schema aliases file roles.
                payloads[payload.name] = when {
                    previous.bytes == null && payload.bytes != null -> payload.copy(
                        countsTowardTtmlBudget =
                            previous.countsTowardTtmlBudget || payload.countsTowardTtmlBudget,
                    )
                    else -> previous.copy(
                        countsTowardTtmlBudget =
                            previous.countsTowardTtmlBudget || payload.countsTowardTtmlBudget,
                    )
                }
            }
            return true
        }

        pointer?.let { published ->
            val bytes = readIndex(published.fileId) ?: return null
            if (!addPayload(Payload.bytes(published.fileId, bytes))) return null
        }

        val font = settings.fontManifest
        if (font.enabled) {
            if (!addPayload(
                    Payload.stream(
                        name = font.fileId,
                        sizeBytes = font.sizeBytes,
                        sha256 = font.sha256,
                        openStream = { openRemoteFile(font.fileId) },
                    )
                )
            ) return null
        }

        indexState.manifest.entries.forEach { entry ->
            if (!addPayload(
                    Payload.stream(
                        name = entry.fileId,
                        sizeBytes = entry.sizeBytes,
                        sha256 = entry.sha256,
                        openStream = { openRemoteFile(entry.fileId) },
                        countsTowardTtmlBudget = true,
                    )
                )
            ) return null
        }
        if (exceedsTtmlBudget(payloads.values)) return null

        val values = LinkedHashMap<String, Any>()
            .apply { putAll(ModuleSettingsSchema.encodeOrdinarySettings(settings)) }
        if (hasFontValues(remoteValues)) {
            values.putAll(ModuleSettingsSchema.encodeFontManifest(font))
        }
        if (pointer != null) {
            values.putAll(ModuleSettingsSchema.encodeIndexPointer(pointer))
        } else if (hasLegacyManifest(remoteValues)) {
            // Preserve v1 data in the schema slot.  The embedded repository
            // can resolve it and will promote it to a v2 index on first edit.
            values.putAll(ModuleSettingsSchema.encodeCustomLyricsManifest(indexState.manifest))
        }
        return MigrationPlan(values = values, files = payloads.values.toList())
    }

    private fun destinationState(destination: EmbeddedConfigurationStorage): DestinationState {
        val values = runCatching { destination.values() }
            .getOrElse { return DestinationState.OCCUPIED }
        return when (values[MIGRATION_MARKER_KEY]) {
            MIGRATION_COMPLETE -> DestinationState.COMPLETE
            MIGRATION_IN_PROGRESS -> if (
                values.keys.all { it == MIGRATION_MARKER_KEY }
            ) {
                DestinationState.IN_PROGRESS
            } else {
                DestinationState.OCCUPIED
            }
            else -> if (values.isEmpty() && !runCatching { destination.hasAnyFiles() }
                    .getOrDefault(true)
            ) {
                DestinationState.EMPTY
            } else {
                DestinationState.OCCUPIED
            }
        }
    }

    private fun writeValues(
        destination: EmbeddedConfigurationStorage,
        values: Map<String, Any>,
    ): Boolean = runCatching {
        destination.writeValues(values, synchronous = true)
    }.getOrDefault(false)

    private fun readBounded(
        openRemoteFile: (String) -> InputStream?,
        name: String,
        maxBytes: Int,
    ): ByteArray? = runCatching {
        openRemoteFile(name)?.use { input ->
            CustomLyricsFilePolicy.readBounded(input, maxBytes)
        }
    }.getOrNull()

    private fun exceedsTtmlBudget(payloads: Collection<Payload>): Boolean {
        var total = 0L
        payloads.filter(Payload::countsTowardTtmlBudget).forEach { payload ->
            if (payload.sizeBytes < 0L ||
                total > MAX_TOTAL_TTML_BYTES - payload.sizeBytes
            ) return true
            total += payload.sizeBytes
        }
        return false
    }

    private fun hasFontValues(values: Map<String, *>): Boolean =
        values.keys.any { it.startsWith("lyrics_font_") }

    private fun hasLegacyManifest(values: Map<String, *>): Boolean =
        values.containsKey("custom_lyrics_manifest")

    private enum class DestinationState {
        EMPTY,
        IN_PROGRESS,
        COMPLETE,
        OCCUPIED,
    }

    private data class MigrationPlan(
        val values: Map<String, Any>,
        val files: List<Payload>,
    )

    private data class Payload(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        val bytes: ByteArray? = null,
        val openStream: (() -> InputStream?)? = null,
        val countsTowardTtmlBudget: Boolean = false,
    ) {
        fun openStream(): InputStream? = bytes?.let(::ByteArrayInputStream) ?: openStream?.invoke()

        companion object {
            fun bytes(name: String, bytes: ByteArray): Payload = Payload(
                name = name,
                sizeBytes = bytes.size.toLong(),
                sha256 = CustomLyricsFilePolicy.sha256(bytes),
                bytes = bytes,
            )

            fun stream(
                name: String,
                sizeBytes: Long,
                sha256: String,
                openStream: () -> InputStream?,
                countsTowardTtmlBudget: Boolean = false,
            ): Payload = Payload(
                name = name,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                openStream = openStream,
                countsTowardTtmlBudget = countsTowardTtmlBudget,
            )
        }
    }
}

internal sealed interface EmbeddedConfigurationMigrationResult {
    data object SkippedAlreadyComplete : EmbeddedConfigurationMigrationResult

    data object SkippedDestinationOccupied : EmbeddedConfigurationMigrationResult

    data object SkippedNoRemoteConfiguration : EmbeddedConfigurationMigrationResult

    data class Migrated(
        val copiedFileIds: List<String>,
    ) : EmbeddedConfigurationMigrationResult

    data class Failed(
        val message: String,
        val copiedFileIds: List<String> = emptyList(),
    ) : EmbeddedConfigurationMigrationResult
}
