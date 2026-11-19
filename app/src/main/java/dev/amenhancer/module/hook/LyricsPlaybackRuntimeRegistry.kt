package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LlmLyricsStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One runtime belongs to the active host process; settings and the lyric menu share it. */
internal object LyricsPlaybackRuntimeRegistry {
    @Volatile private var active: EmbeddedLyricsPlaybackRuntime? = null
    private var llmOwner: Any? = null
    private val mutableLlmStatus = MutableStateFlow<LlmLyricsStatusSnapshot?>(null)
    val llmStatus: StateFlow<LlmLyricsStatusSnapshot?> = mutableLlmStatus.asStateFlow()
    fun controls(): EmbeddedLyricsPlaybackRuntime? = active
    fun attach(runtime: EmbeddedLyricsPlaybackRuntime) { active = runtime }
    fun detach(runtime: EmbeddedLyricsPlaybackRuntime) { if (active === runtime) active = null }
    fun onSettingsChanged() { active?.onSettingsChanged() }

    @Synchronized fun attachLlmStatus(owner: Any) {
        llmOwner = owner
        mutableLlmStatus.value = null
    }

    @Synchronized fun publishLlmStatus(owner: Any, status: LlmLyricsStatusSnapshot) {
        if (llmOwner === owner) mutableLlmStatus.value = status
    }

    @Synchronized fun detachLlmStatus(owner: Any) {
        if (llmOwner !== owner) return
        llmOwner = null
        mutableLlmStatus.value = null
    }
}
