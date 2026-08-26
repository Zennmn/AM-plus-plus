package io.github.proify.lyricon.amprovider.xposed.hooks

/** Minimal active-player seam required by HLE playback metadata hooks. */
internal class ApplePlaybackHooks {
    @Volatile
    private var player: Any? = null

    fun activePlayer(): Any? = player
    fun attachActivePlayer(value: Any?) { player = value }
}
