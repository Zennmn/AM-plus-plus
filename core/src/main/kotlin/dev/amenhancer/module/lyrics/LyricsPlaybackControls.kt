package dev.amenhancer.module.lyrics

/** Selection policy identifiers are independent of cached lyric bodies. */
object LyricsPlaybackSources {
    const val AUTOMATIC = "automatic"
}

/** Current presentation state only; never a persistent request log. */
enum class LlmLyricsPhase {
    OFF, WAITING, CONFIG_REQUIRED, REQUIRES_AUTO, NATIVE_ONLY, EXISTS, NOT_NEEDED, UNSUPPORTED,
    QUEUED, GENERATING, APPLYING, READY, CACHED,
    REQUEST_FAILED, AUTH_FAILED, RATE_LIMITED, TIMED_OUT, INVALID_RESPONSE,
}

/** Safe copied status shared by the lyric popup and settings; contains no credentials or lyrics. */
data class LlmLyricsStatusSnapshot(
    val appleMusicId: Long? = null,
    val songTitle: String? = null,
    val model: String = "",
    val configurationKey: Int? = null,
    val translation: LlmLyricsPhase = LlmLyricsPhase.WAITING,
    val transliteration: LlmLyricsPhase = LlmLyricsPhase.WAITING,
)

/** Copied state only: UI and host adapters never share a native lyric pointer. */
data class LyricsPlaybackSnapshot(
    val appleMusicId: Long,
    val preferredSource: String = LyricsPlaybackSources.AUTOMATIC,
    val activeSource: String? = null,
    val pendingSource: String? = null,
    val offsetMs: Int = 0,
    val hasManualLyrics: Boolean = false,
    val canAdjustTiming: Boolean = false,
    val message: String? = null,
    val isCurrentSong: Boolean = true,
)

/** Host-neutral commands for the module's lyric menu and settings cache row. */
interface LyricsPlaybackControls {
    fun snapshot(appleMusicId: Long): LyricsPlaybackSnapshot
    fun selectSource(appleMusicId: Long, source: String)
    fun setOffset(appleMusicId: Long, source: String, offsetMs: Int): Boolean
    /** Disk operations: callers must use their existing background worker. */
    fun cacheUsageBytes(): Long
    fun clearCache(): Boolean
    fun observe(listener: () -> Unit): AutoCloseable
}

/** Prepared candidates are private to the host's existing result submission channel. */
data class LyricsPlaybackChoice(val source: String, val payload: Any, val ttml: String?)

/** Storage/network orchestration is wired by app; only host adapters parse native documents. */
interface LyricsPlaybackRuntime : LyricsPlaybackControls {
    /** Readiness notifications are delivered on the host's main thread. */
    fun bind(
        prepare: (Long, String) -> Any?,
        isAlive: (Any?) -> Boolean,
        onReady: (Long) -> Unit,
    ): AutoCloseable
    fun onSongChanged(appleMusicId: Long?)
    /** Reselect the current song after its shared runtime settings are saved. */
    fun onSettingsChanged()
    fun hasReadyLyrics(appleMusicId: Long): Boolean
    /** Checks indexed sources before native preparation, including for an upcoming item. */
    fun hasCachedLyrics(appleMusicId: Long, automaticSources: List<String>): Boolean
    fun preferredSource(appleMusicId: Long): String?
    fun isSuppressed(appleMusicId: Long): Boolean
    fun manualEntry(appleMusicId: Long): dev.amenhancer.module.model.CustomLyricsEntry?
    fun cacheGeneration(): Long
    fun invalidateSource(appleMusicId: Long, source: String)
    fun select(
        appleMusicId: Long,
        native: Any?,
        nativeTtml: String?,
        manual: Any?,
        manualTtml: String?,
    ): LyricsPlaybackChoice?
    fun onSelected(appleMusicId: Long, source: String, payload: Any, ttml: String?)
    fun withOffset(appleMusicId: Long, source: String, payload: Any, ttml: String?): Any
    fun close()
}
