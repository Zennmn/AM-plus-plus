package v5

import com.apple.android.medialibrary.javanative.medialibrary.svmodel.SVEntityNative
import com.apple.android.music.model.Album
import com.apple.android.music.model.BasePlaybackItem

/** JVM stand-in for the 6.5.1 native entity conversion helpers in v5.a. */
class a {
    companion object {
        @JvmStatic
        fun n(
            item: BasePlaybackItem,
            entity: SVEntityNative.SVEntitySRef,
        ) {
            // Signature-only fixture; the host populates the playback model.
        }

        @JvmStatic
        fun b(
            entity: SVEntityNative.SVEntitySRef,
            includeChildren: Boolean,
        ): Album = Album()
    }
}
