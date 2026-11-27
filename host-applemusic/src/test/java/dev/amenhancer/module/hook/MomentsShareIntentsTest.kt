package dev.amenhancer.module.hook

import android.content.Intent
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.MomentsLinkContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MomentsShareIntentsTest {
    @Suppress("DEPRECATION")
    @Test fun `native chooser keeps original share and adds one link destination`() {
        val url = "https://music.apple.com/cn/song/song/1447401620"
        val original = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        val existing = Intent(Intent.ACTION_VIEW)
        val chooser = Intent.createChooser(original, "Share").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(existing))
        val changed = MomentsShareIntents.addDestination(chooser)!!
        assertEquals(original, changed.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val targets = changed.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!
        val relay = targets.first() as Intent
        assertEquals(ModuleConstants.MODULE_PACKAGE, relay.component?.packageName)
        assertEquals(url, relay.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(2, targets.size)
        assertNull(MomentsShareIntents.addDestination(changed))
        assertFalse(chooser.getBooleanExtra("dev.amenhancer.module.moments_added", false))
    }
    @Suppress("DEPRECATION")
    @Test fun `URL-only native sharing carries song metadata only for the matching song`() {
        val url = "https://music.apple.com/cn/album/album-name/123?i=456"
        val source = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url)
        fun relay(id: Long): Intent {
            val changed = MomentsShareIntents.addDestination(Intent.createChooser(source, "Share"),
                CurrentSongDetails(id, "Despacito", "Luis Fonsi"))!!
            return changed.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.first() as Intent
        }
        assertEquals("Despacito", relay(456).getStringExtra(MomentsLinkContract.TITLE))
        assertEquals("Luis Fonsi", relay(456).getStringExtra(MomentsLinkContract.ARTIST))
        assertNull(relay(789).getStringExtra(MomentsLinkContract.TITLE))
        assertEquals(url, relay(456).getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(source.hasExtra(MomentsLinkContract.TITLE))
    }
    @Test fun `image-only shares never masquerade as clickable link sharing`() {
        val image = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("content://image/1"))
        assertNull(MomentsShareIntents.addDestination(Intent.createChooser(image, "Share")))
        val other = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/music.apple.com/song")
        assertNull(MomentsShareIntents.addDestination(Intent.createChooser(other, "Share")))
    }
}
