package com.apple.android.music.mediaapi.models

import android.os.Bundle
import com.apple.android.music.mediaapi.models.internals.Attributes
import com.apple.android.music.model.CollectionItemView

/**
 * JVM stand-ins for Apple Music's catalog model display seams, matching the
 * real binary class names so the title correction symbol contracts can be
 * exercised in unit tests.
 */
open class MediaEntityTitleFixture(
    private val id: String = "42",
    private val title: String = "English Title",
) {
    fun getId(): String = id
    fun getTitle(): String = title
    open fun getShortName(): String = title
    fun getAttributes(): Attributes = Attributes()
    fun toCollectionItemView(bundle: Bundle): CollectionItemView? = null
}

class SongTitleFixture : MediaEntityTitleFixture() {
    override fun getShortName(): String = super.getShortName()
}

class Song : MediaEntityTitleFixture() {
    override fun getShortName(): String = super.getShortName()

    fun toCollectionItemView(bundle: Bundle, includeEditors: Boolean): CollectionItemView? = null
}

class Album : MediaEntityTitleFixture() {
    fun toCollectionItemView(bundle: Bundle, includeEditors: Boolean): CollectionItemView? = null
}

open class LibrarySongTitleFixture {
    fun getId(): String = "7"
    fun toCollectionItemView(bundle: Bundle, includeEditors: Boolean): CollectionItemView? = null
}

open class LibraryAlbumTitleFixture {
    fun getId(): String = "8"
    fun toCollectionItemView(bundle: Bundle, includeEditors: Boolean): CollectionItemView? = null
}
