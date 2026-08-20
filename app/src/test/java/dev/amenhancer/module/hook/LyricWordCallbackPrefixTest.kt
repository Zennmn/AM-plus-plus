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
    fun `prefix is unavailable when owner is not a processEvents callback`() {
        assertNull(
            deriveLyricWordCallbackPrefix(
                "com.apple.android.music.ttml.SongInfoTimeProcessor\$renamedCallback",
            ),
        )
    }
}
