package com.apple.android.music.mediaapi.repository

class MediaApiResponse(private val data: Array<Any>) {
    fun getData(): Array<Any> = data
}
