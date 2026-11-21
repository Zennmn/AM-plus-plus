package dev.amenhancer.module.share

import org.junit.Assert.*
import org.junit.Test

class ShareDraftCodecTest {
    private val now = 1_799_000_000_000L
    private val draft = ShareDraft("https://music.apple.com/tr/album/%E6%AD%8C/111?i=123&ls=1", "My edited title", "My artist",
        "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg", true, now)
    @Test fun `restored draft preserves the raw link and manual edits`() {
        assertEquals(draft, ShareDraftCodec.decode(ShareDraftCodec.encode(draft), now + 1000))
    }
    @Test fun `stale and future drafts are discarded`() {
        val raw = ShareDraftCodec.encode(draft)
        assertNull(ShareDraftCodec.decode(raw, now + ShareDraftCodec.RETENTION_MS))
        assertNull(ShareDraftCodec.decode(raw, now - 300_001))
    }
    @Test fun `invalid content and oversized storage do not restore`() {
        val raw = ShareDraftCodec.encode(draft)
        assertNull(ShareDraftCodec.decode(raw.replace("music.apple.com", "evil.example"), now))
        assertNull(ShareDraftCodec.decode(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), now))
        assertNull(ShareDraftCodec.decode("x".repeat(32769), now))
        assertNull(ShareDraftCodec.decode("corrupt storage", now))
    }
    @Test fun `foreign links are rejected and nonApple artwork is discarded`() {
        assertThrows(IllegalArgumentException::class.java) { ShareDraftCodec.encode(draft.copy(url = "http://evil.example")) }
        val decoded = ShareDraftCodec.decode(ShareDraftCodec.encode(draft.copy(artwork = "https://evil.example/cover.jpg")), now)
        assertNotNull(decoded); assertNull(decoded?.artwork)
    }
}
