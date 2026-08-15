package com.apple.android.music.model

/**
 * JVM stand-ins for Apple Music's model display items used by the title
 * correction seams: the MediaEntity -> model.Song converter return type, the
 * library conversion return type and the StorePlatform response getter.
 */
open class Song : BaseContentItem() {
    fun setTitle(title: String) {}
    fun setArtistName(name: String) {}
    fun setCollectionName(name: String) {}
    fun getCollectionName(): String = ""
}
/** JVM stand-ins for the native playback-item conversion contract. */
open class BasePlaybackItem : BaseContentItem()

/** JVM stand-in for the native entity album conversion return type. */
open class Album : BaseContentItem()

open class CollectionItemView {
    fun getId(): String = "0"
    fun getTitle(): String = ""
    fun setTitle(title: String) {}
    fun getArtistName(): String = ""
    fun setArtistName(name: String) {}
    fun getCollectionName(): String = ""
    fun setCollectionName(name: String) {}
}

open class BaseStorePlatformResponse {
    fun getStorePlatformData(): Map<String, Any> = emptyMap()
}
