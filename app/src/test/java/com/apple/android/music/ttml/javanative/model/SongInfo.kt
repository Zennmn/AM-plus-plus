package com.apple.android.music.ttml.javanative.model

import org.bytedeco.javacpp.Pointer

/**
 * JVM stand-ins for the native model classes, matching the real binary names
 * (`...SongInfo$SongInfoPtr`, `...SongInfo$SongInfoNative`,
 * `...LyricsSectionVector`) so target-symbol contracts can be exercised in
 * unit tests.
 */
class SongInfo {
    class SongInfoPtr : Pointer() {
        fun get(): SongInfoNative = SongInfoNative()
    }

    class SongInfoNative {
        fun getSections(): LyricsSectionVector = LyricsSectionVector()
        fun getAdamId(): Long = 0L
        fun setAdamId(adamId: Long) = Unit
    }
}

class LyricsSectionVector {
    fun size(): Long = 0L
}
