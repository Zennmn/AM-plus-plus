package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.LyricsPlaybackControls
import dev.amenhancer.module.lyrics.LlmLyricsStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One runtime belongs to the active host process; settings and the lyric menu share it. */
internal object LyricsPlaybackRuntimeRegistry {
    @Volatile private var active: LyricsPlaybackControls? = null
    private var llmOwner: Any? = null
    private val mutableLlmStatus = MutableStateFlow<LlmLyricsStatusSnapshot?>(null)
    val llmStatus: StateFlow<LlmLyricsStatusSnapshot?> = mutableLlmStatus.asStateFlow()
    fun controls(): LyricsPlaybackControls? = active
    fun attach(controls: LyricsPlaybackControls) { active = controls }
    fun detach(controls: LyricsPlaybackControls) { if (active === controls) active = null }

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
