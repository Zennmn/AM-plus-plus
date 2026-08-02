package com.apple.android.music.model

/**
 * JVM stand-in for Apple Music's current lyrics item base class, matching the
 * real binary name so the current-item field contract (`getId()`) can be
 * exercised in unit tests.
 */
open class BaseContentItem(
    private val id: String = "0",
    private val title: String = "",
    private val artistName: String = "",
) {
    fun getId(): String = id
    fun getTitle(): String = title
    fun getArtistName(): String = artistName
}
