package dev.amenhancer.module.share

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.MomentsSongMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class ShareDraftStoreTest {
    private val url = "https://music.apple.com/tr/album/%E6%AD%8C/111?i=123&ls=1"
    private val cover = "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg"
    private fun draft(now: Long) = ShareDraft(url, "My title", null, cover, true, now)
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); if (System.nanoTime() > end) fail("Draft UI timeout"); Thread.sleep(10) }
    }
    @Test fun `a recreated store restores private content and preserves a different song draft`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis()
        ShareDraftStore(app) { now }.save(draft(now))
        val recreated = ShareDraftStore(app) { now + 1000 }
        assertNull(recreated.load("https://music.apple.com/cn/song/456"))
        assertEquals(draft(now), recreated.load(url)); recreated.clear(); assertNull(recreated.load(url))
    }
    @Test fun `failed writes retain previous content and bad or expired storage is discarded`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis(); val store = ShareDraftStore(app) { now }
        store.save(draft(now))
        assertThrows(IllegalArgumentException::class.java) { store.save(draft(now).copy(url = "https://evil.example")) }
        File(app.filesDir, "amplusplus-share-draft-v1.json.new").writeText("partial write")
        assertEquals(draft(now), store.load(url))
        assertNull(ShareDraftStore(app) { now + ShareDraftCodec.RETENTION_MS }.load(url))
        val file = File(app.filesDir, "amplusplus-share-draft-v1.json")
        assertFalse(file.exists()); file.writeText("x".repeat(32769)); assertNull(store.load(url)); assertFalse(file.exists())
    }
    @Test fun `overlapping live stores cannot invalidate each others atomic writes`() {
        val app = RuntimeEnvironment.getApplication(); val now = System.currentTimeMillis()
        val stores = listOf(ShareDraftStore(app) { now }, ShareDraftStore(app) { now })
        stores[0].save(draft(now))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs = stores.mapIndexed { index, store -> pool.submit {
                repeat(25) { turn ->
                    store.save(draft(now).copy(title = "Writer$index-$turn"))
                    assertNotNull(store.load(url))
                }
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(stores[0].load(url)!!.title.startsWith("Writer"))
        } finally { pool.shutdownNow() }
    }
    @Test fun `restored fields go to the native NetEase share request only after user confirmation`() {
        val app = RuntimeEnvironment.getApplication()
        val store = ShareDraftStore(app)
        val original = draft(System.currentTimeMillis()).copy(title = "Restored title", artist = "Restored artist")
        store.save(original)
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 12345 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = Contract.INTERMEDIARY_PACKAGE; applicationInfo = info
        })
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(Contract.INTERMEDIARY_PACKAGE)
        shadowOf(app.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = Contract.INTERMEDIARY_PACKAGE; name = "MainActivity"; applicationInfo = info; exported = true
            }
        })
        var delivered: Intent? = null
        val netease = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        netease.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                delivered = intent
                resultCode = Contract.RESULT_OPENED
            }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url).putExtra(Contract.TITLE, "Initial title"))
        controller.get().metadataResolver = { MomentsSongMetadata("Other title", "Other artist", cover) }
        try {
            val activity = controller.setup().visible().get()
            fun button(text: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }
            await { button("Restore saved draft").visibility == View.VISIBLE }
            assertNull(delivered) // Restoring is never an automatic send.
            button("Restore saved draft").performClick()
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            assertEquals("Restored title", fields[0].text.toString())
            assertEquals("Restored artist", fields[1].text.toString())
            button("Open NetEase sharing").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(Contract.withChinaStorefront(url, true), delivered?.getStringExtra(Contract.URL))
            assertEquals("Restored title", delivered?.getStringExtra(Contract.TITLE))
            assertEquals("Restored artist", delivered?.getStringExtra(Contract.ARTIST))
            assertEquals(cover, delivered?.getStringExtra(Contract.ARTWORK))
        } finally {
            controller.pause().stop().destroy()
            store.clear()
        }
    }
    @Test fun `user restores saves and clears a draft without native sending or late metadata overwriting edits`() {
        val app = RuntimeEnvironment.getApplication(); val store = ShareDraftStore(app)
        store.save(draft(System.currentTimeMillis()))
        val gate = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, url).putExtra(Contract.TITLE, "Initial title"))
        controller.get().metadataResolver = { gate.await(4, TimeUnit.SECONDS); MomentsSongMetadata("Late title", "Late artist", cover) }
        val activity = controller.setup().visible().get()
        fun button(text: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }
        try {
            await { button("Restore saved draft").visibility == View.VISIBLE }
            button("Restore saved draft").performClick()
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            assertEquals("My title", fields[0].text.toString()); assertEquals("", fields[1].text.toString())
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isChecked)
            fields[1].setText("New artist"); gate.countDown()
            repeat(50) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertEquals("My title", fields[0].text.toString()); assertEquals("New artist", fields[1].text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            button("Save draft").performClick(); await { store.load(url)?.artist == "New artist" }
            button("Clear saved draft").performClick(); await { store.load(url) == null && button("Restore saved draft").visibility == View.GONE }
            assertEquals("My title", fields[0].text.toString()); assertNull(shadowOf(activity).nextStartedActivity)
        } finally { gate.countDown(); controller.pause().stop().destroy() }
    }
}
