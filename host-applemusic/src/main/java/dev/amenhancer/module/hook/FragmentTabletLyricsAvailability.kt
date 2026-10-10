package dev.amenhancer.module.hook

import android.content.Context
import java.lang.reflect.Modifier

/** The 1606 lyric button's native item rule, without its forced-open preference override. */
internal class FragmentTabletLyricsAvailability(
    private val currentItem: () -> Any?,
    private val supportsLyrics: (Any) -> Boolean,
    private val online: () -> Boolean,
    private val offlineLyrics: (Any) -> Boolean,
) {
    fun available(): Boolean? = runCatching {
        val item = currentItem() ?: return@runCatching null
        supportsLyrics(item) && (online() || offlineLyrics(item))
    }.getOrNull()

    companion object {
        fun native(controller: Any, context: Context): FragmentTabletLyricsAvailability {
            val loader = controller.javaClass.classLoader!!
            val itemType = loader.loadClass("com.apple.android.music.model.PlaybackItem")
            val itemField = controller.javaClass.getDeclaredField("M").apply {
                check(type.name == "com.apple.android.music.model.BaseContentItem")
                isAccessible = true
            }
            val supported = loader.loadClass("com.apple.android.music.player.i1")
                .getDeclaredMethod("i", itemType).apply {
                    check(returnType == java.lang.Boolean.TYPE && Modifier.isStatic(modifiers))
                    isAccessible = true
                }
            val connectivity = loader.loadClass("fc.d").getDeclaredMethod("c", Context::class.java).apply {
                check(returnType == java.lang.Boolean.TYPE && Modifier.isStatic(modifiers))
                isAccessible = true
            }
            val offline = itemType.getMethod("hasOfflineLyrics").apply {
                check(returnType == java.lang.Boolean.TYPE && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
            return FragmentTabletLyricsAvailability(
                { itemField.get(controller)?.takeIf(itemType::isInstance) },
                { supported.invoke(null, it) == true },
                { connectivity.invoke(null, context) == true },
                { offline.invoke(it) == true },
            )
        }
    }
}
