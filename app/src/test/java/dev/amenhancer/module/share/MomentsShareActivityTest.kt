package dev.amenhancer.module.share

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Button
import dev.amenhancer.module.MomentsSongMetadata
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.widget.TextView
import dev.amenhancer.module.MomentsLinkContract as Contract
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class MomentsShareActivityTest {
    private val artwork = "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg"
    private val netEaseLauncher = "com.netease.cloudmusic.activity.IconChangeDefaultAlias"
    private lateinit var app: Application
    private lateinit var activity: MomentsShareActivity
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts() = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun openButton() = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Open NetEase sharing" }
    private fun awaitMetadata() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (openButton().isEnabled && texts().none { it == "Fetching artwork; you can share the existing details now." }) return
            Thread.sleep(10)
        }
        fail("Metadata lookup did not finish")
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val info = ApplicationInfo().apply { packageName = Contract.INTERMEDIARY_PACKAGE; uid = 12345 }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = Contract.INTERMEDIARY_PACKAGE; applicationInfo = info
        })
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(Contract.INTERMEDIARY_PACKAGE)
        fun registerLauncher(component: String, process: String, order: Int) {
            shadowOf(app.packageManager).addResolveInfoForIntent(launcher, ResolveInfo().apply {
                priority = order
                activityInfo = ActivityInfo().apply {
                    packageName = Contract.INTERMEDIARY_PACKAGE
                    name = component
                    processName = process
                    applicationInfo = info
                    exported = true
                }
            })
        }
        registerLauncher("com.squareup.leakcanary.internal.DisplayLeakActivity", "${info.packageName}:leakcanary", 100)
        registerLauncher(netEaseLauncher, info.packageName, 0)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123")
                .putExtra(Contract.TITLE, "Despacito").putExtra(Contract.ARTIST, "Luis Fonsi")
        )
        controller.get().metadataResolver = { MomentsSongMetadata("Fetched title", "Fetched artist", artwork) }
        activity = controller.setup().visible().get()
        awaitMetadata()

    }
    @Test fun `China switch updates preview clipboard and the exact link sent to NetEase`() {
        val original = "https://music.apple.com/tr/album/%E6%AD%8C/111?i=123&ls=1"
        val expected = original.replace("/tr/", "/cn/")
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, original)
                .putExtra(Contract.TITLE, "Despacito").putExtra(Contract.ARTIST, "Luis Fonsi"))
        controller.get().metadataResolver = { MomentsSongMetadata("Despacito", "Luis Fonsi", artwork) }
        activity = controller.setup().visible().get(); awaitMetadata()
        val toggle = views(activity.window.decorView).filterIsInstance<android.widget.Switch>().single()
        assertFalse(toggle.isChecked); toggle.performClick(); assertTrue(texts().contains(expected))
        views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Copy song link" }.performClick()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        var delivered: String? = null
        val host = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        host.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered = intent.getStringExtra(Contract.URL); resultCode = Contract.RESULT_OPENED }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        openButton().performClick(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(expected, delivered)
        controller.pause().stop().destroy()
    }
    @Test fun `launching NetEase without its module receiver never reports success and shows scope diagnosis`() {
        openButton().performClick()
        assertEquals(android.content.ComponentName(Contract.INTERMEDIARY_PACKAGE, netEaseLauncher),
            shadowOf(activity).nextStartedActivity?.component)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(texts().any { "[N0]" in it && "LSPosed" in it })
        assertFalse(texts().any { it.startsWith("NetEase share panel opened") })
        assertTrue(openButton().isEnabled)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("[N0]"))
    }
    @Suppress("DEPRECATION")
    @Test fun `pending native launch is polled with one request id until real panel acknowledgement`() {
        var response = Contract.RESULT_PENDING
        val ids = mutableListOf<String?>()
        val host = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        host.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ids += intent.getStringExtra(Contract.REQUEST_ID)
                assertEquals("Despacito", intent.getStringExtra(Contract.TITLE))
                assertEquals("Luis Fonsi", intent.getStringExtra(Contract.ARTIST))
                assertEquals(artwork, intent.getStringExtra(Contract.ARTWORK))
                resultCode = response
            }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        openButton().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(ids.size >= 2)
        assertEquals(1, ids.distinct().size)
        assertFalse(texts().any { it.startsWith("NetEase share panel opened") })
        response = Contract.RESULT_OPENED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(texts().any { it.startsWith("NetEase share panel opened") })
        assertTrue(openButton().isEnabled)
        val count = ids.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(count, ids.size)
    }
    @Test fun `URL-only share looks up the exact track and displays editable song title and artist`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lookupUrl = ""
        val responseGate = CountDownLatch(1)
        server.createContext("/lookup") { exchange ->
            responseGate.await()
            val bytes = """{"results":[{"kind":"song","trackId":999,"trackName":"Wrong song","artistName":"Wrong artist"},{"kind":"song","trackId":123,"trackName":"Despacito","artistName":"Luis Fonsi","artworkUrl100":"$artwork"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/album/album-name/111?i=123"))
        val client = AppleSongMetadataLookup { requested ->
            lookupUrl = requested.toString()
            URL("http://127.0.0.1:${server.address.port}/lookup").openConnection() as HttpURLConnection
        }
        controller.get().metadataResolver = client::lookup
        try {
            activity = controller.setup().visible().get()
            assertFalse(openButton().isEnabled)
            responseGate.countDown()
            var loaded = false
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (openButton().isEnabled) loaded = true else Thread.sleep(10)
            }
            assertTrue(loaded)
            assertEquals(artwork, client.lookup("https://music.apple.com/cn/song/123")?.artworkUrl)
            assertTrue("id=123&country=cn" in lookupUrl)
            assertTrue(texts().contains("Despacito")); assertTrue(texts().contains("Luis Fonsi"))
            val fields = views(activity.window.decorView).filterIsInstance<EditText>()
            fields.first().setText("My title")
            assertTrue(texts().contains("My title"))
        } finally { responseGate.countDown(); controller.pause().stop().destroy(); server.stop(0) }
    }
    @Test fun `missing metadata permits manual entry and a late lookup cannot overwrite edits`() {
        val gate = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123"))
        controller.get().metadataResolver = {
            gate.await(2, TimeUnit.SECONDS)
            MomentsSongMetadata("Fetched title", "Fetched artist")
        }
        activity = controller.setup().visible().get()
        val fields = views(activity.window.decorView).filterIsInstance<EditText>()
        fields[0].setText("User title"); fields[1].setText("User artist")
        gate.countDown()
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (texts().none { it.startsWith("Loading song title") }) break
            Thread.sleep(10)
        }
        assertFalse(texts().any { it.startsWith("Loading song title") })
        assertTrue(texts().contains("User title")); assertTrue(texts().contains("User artist"))
        assertTrue(openButton().isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun `known title remains shareable while artwork loads and failure preserves edited details`() {
        val gate = CountDownLatch(1)
        val controller = Robolectric.buildActivity(MomentsShareActivity::class.java,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://music.apple.com/cn/song/123")
                .putExtra(Contract.TITLE, "Manual title").putExtra(Contract.ARTIST, "Manual artist"))
        controller.get().metadataResolver = { gate.await(2, TimeUnit.SECONDS); null }
        activity = controller.setup().visible().get()
        try {
            assertTrue(openButton().isEnabled)
            assertTrue(texts().contains("Fetching artwork; you can share the existing details now."))
            gate.countDown()
            awaitMetadata()
            assertTrue(texts().contains("Manual title")); assertTrue(texts().contains("Manual artist"))
            assertTrue(texts().any { "without a cover" in it })
        } finally { gate.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun `blocked receiver still reaches the independent deadline and restores region controls`() {
        var blocked: BroadcastReceiver.PendingResult? = null
        val host = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        host.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { blocked = goAsync() }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        openButton().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNotNull(blocked)
        assertTrue(texts().any { it.contains("Waiting for NetEase") && it.contains("28s") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertTrue(openButton().isEnabled)
        assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isEnabled)
        assertTrue(texts().any { "[N0]" in it })
        blocked?.setResultCode(Contract.RESULT_OPENED); blocked?.finish()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(texts().any { it.startsWith("NetEase share panel opened") })
    }

    @Test fun `stop waiting ignores a late success and allows editing again`() {
        var blocked: BroadcastReceiver.PendingResult? = null
        val host = app.createPackageContext(Contract.INTERMEDIARY_PACKAGE, 0)
        host.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { blocked = goAsync() }
        }, IntentFilter(Contract.OPEN), Context.RECEIVER_EXPORTED)
        openButton().performClick(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val stop = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Stop waiting" }
        assertEquals(View.VISIBLE, stop.visibility); stop.performClick()
        assertEquals(View.GONE, stop.visibility)
        assertTrue(openButton().isEnabled)
        assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.Switch>().single().isEnabled)
        blocked?.setResultCode(Contract.RESULT_OPENED); blocked?.finish()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertTrue(texts().any { it.startsWith("Stopped waiting") })
        assertFalse(texts().any { "[N0]" in it || it.startsWith("NetEase share panel opened") })
    }
}
