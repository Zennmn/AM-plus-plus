package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricWordCallbackPrefixTest {
    @Test
    fun `prefix ignores the generated line callback lambda name`() {
        val ownerName =
            "com.apple.android.music.ttml.SongInfoTimeProcessor" +
                "\$processEvents\$renamedLineCallback\$1"

        assertEquals(
            "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents",
            deriveLyricWordCallbackPrefix(ownerName),
        )
    }

    @Test
    fun `prefix uses stable processor owner when callback name is renamed`() {
        assertEquals(
            "com.apple.android.music.ttml.SongInfoTimeProcessor\$processEvents",
            deriveLyricWordCallbackPrefix(
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$renamedCallback",
            ),
        )
    }

    @Test
    fun `prefix is unavailable when owner is not a nested callback`() {
        assertNull(
            deriveLyricWordCallbackPrefix(
                "com.apple.android.music.ttml.SongInfoTimeProcessor",
            ),
        )
    }
}
