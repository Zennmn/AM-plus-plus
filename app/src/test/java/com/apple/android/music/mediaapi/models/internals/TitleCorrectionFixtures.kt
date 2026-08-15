package com.apple.android.music.mediaapi.models.internals

class Attributes {
    fun getName(): String = ""
    fun setName(name: String) {}
    fun getShortName(): String = ""
    fun getTitle(): Title = Title("")
    fun getTitleWithoutName(): Title = Title("")
    fun getArtistName(): String = ""
    fun setArtistName(name: String) {}
    fun getAlbumName(): String = ""
    fun setAlbumName(name: String) {}
}

class Title(private val value: String) {
    fun getStringForDisplay(): String = value
}

class SearchResultsResponse {
    class SearchSectionResultResponse {
        fun setData(data: List<*>) {}
    }
}
