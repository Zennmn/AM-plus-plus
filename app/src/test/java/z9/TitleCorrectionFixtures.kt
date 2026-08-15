package z9

import android.os.Bundle
import com.apple.android.music.mediaapi.models.Song
import com.apple.android.music.model.Song as ModelSong

/** Second same-shaped converter used to pin ambiguity reporting. */
class C {
    companion object {
        @JvmStatic
        fun convert(song: Song, bundle: Bundle): ModelSong = ModelSong()
    }
}
