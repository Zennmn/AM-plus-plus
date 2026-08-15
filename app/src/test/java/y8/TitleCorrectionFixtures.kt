package y8

import android.os.Bundle
import com.apple.android.music.mediaapi.models.Song
import com.apple.android.music.model.Song as ModelSong

/**
 * JVM stand-in for the verified AMTool "MediaEntity -> model.Song" converter
 * (`y8.B.b(Song, Bundle)` in both 6.5.0 and 6.5.1), matching the obfuscated
 * binary name so the structural signature contract can be exercised.
 */
class B {
    companion object {
        @JvmStatic
        fun convert(song: Song, bundle: Bundle): ModelSong = ModelSong()
    }
}
