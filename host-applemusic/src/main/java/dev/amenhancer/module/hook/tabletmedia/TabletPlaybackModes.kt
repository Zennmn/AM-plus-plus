package dev.amenhancer.module.hook.tabletmedia

import android.os.Looper
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.lang.reflect.Modifier

/** Native queue commands/getters from the media plugin's verified 1606 descriptors. */
internal class TabletPlaybackModes(private val owner: Any) {
    data class State(val shuffle: Boolean? = null, val repeat: Int? = null,
        val canShuffle: Boolean = false, val canRepeat: Boolean = false)
    private val loader = owner.javaClass.classLoader!!
    private val indexed = checkNotNull(AppleMusicHostProfiles.find("com.apple.android.music", "7.0.0-beta", 1606))
        .document.getJSONObject("indexed")
    private fun type(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE; "boolean" -> java.lang.Boolean.TYPE; "int" -> Integer.TYPE
        else -> loader.loadClass(name)
    }
    private fun method(key: String) = indexed.getJSONObject("methodContracts").getJSONObject("tablet-playback-$key").let { c ->
        val p = c.getJSONArray("parameters")
        type(c.getString("owner")).getDeclaredMethod(c.getString("name"),
            *Array(p.length()) { type(p.getString(it)) }).apply {
            check(returnType == type(c.getString("returns")) && Modifier.isStatic(modifiers) == c.getBoolean("static"))
            isAccessible = true
        }
    }
    private fun field(key: String) = indexed.getJSONObject("fieldContracts").getJSONObject("tablet-playback-$key").let { c ->
        type(c.getString("owner")).getDeclaredField(c.getString("name")).apply {
            check(type == type(c.getString("type"))); isAccessible = true
        }
    }
    private val browser = method("media-browser")
    private val shuffle = method("get-shuffle")
    private val repeat = method("get-repeat")
    private val setShuffle = method("set-shuffle")
    private val setRepeat = method("set-repeat")
    private val available = method("command-available")
    private val looper = method("application-looper")
    private val connected = method("backend-connected")
    private val backend = field("controller-backend")
    private val model = field("main-state")
    private val blocked = field("mode-blocked")

    fun state(): State = runCatching {
        val target = browser.invoke(owner) ?: return State()
        if (looper.invoke(target) !== Looper.myLooper()) return State()
        val connection = backend.get(target) ?: return State()
        if (connected.invoke(connection) != true) return State()
        val state = model.get(owner)
        val canChange = state != null && !blocked.getBoolean(state)
        val repeatValue = (repeat.invoke(target) as Int).takeIf { it in 0..2 }
        State(shuffle.invoke(target) as Boolean, repeatValue,
            canChange && available.invoke(target, 14) == true,
            canChange && repeatValue != null && available.invoke(target, 15) == true)
    }.getOrDefault(State())

    fun toggleShuffle() {
        val state = state()
        if (state.canShuffle) setShuffle.invoke(browser.invoke(owner), !checkNotNull(state.shuffle))
    }
    fun cycleRepeat() {
        val state = state()
        if (state.canRepeat) setRepeat.invoke(browser.invoke(owner), when (state.repeat) { 0 -> 2; 2 -> 1; else -> 0 })
    }
}
