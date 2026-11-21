package dev.amenhancer.host.netease

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.hook.ModernMethodHook
import java.lang.reflect.Executable
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NetEaseShareSessionTest {
    class HostActivity : Activity() { override fun getPackageName() = Contract.INTERMEDIARY_PACKAGE }
    private val url = "https://music.apple.com/cn/album/name/123?i=456"
    private val profile = NetEaseShareProfiles.find("9.5.81", 9005081)!!
    private lateinit var activity: HostActivity
    private lateinit var session: NetEaseShareSession
    private var now = 0L
    private fun nativeContract(): NetEaseShareContract {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                NetEaseShareContract.PANEL -> NetEaseShareContractTest.Panel::class.java
                NetEaseShareContract.RESOURCE -> NetEaseShareContractTest.Resource::class.java
                else -> super.loadClass(name)
            }
        }
        return NetEaseShareContract.resolve(loader, profile)
    }
    private fun request(id: String = "request-1", link: String = url) = Intent(Contract.OPEN)
        .putExtra(Contract.REQUEST_ID, id).putExtra(Contract.URL, link).putExtra(Contract.TITLE, "Song")
        .putExtra(Contract.ARTWORK, "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg")
    @Before fun setup() {
        now = 0
        activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        session = NetEaseShareSession(activity.application, { profile }, { _, _ -> nativeContract() }, { now })
    }
    @Test fun `resume works with no Application callback and duplicate polls do not reopen the panel`() {
        assertEquals(Contract.RESULT_NOT_READY, session.receive(request()).code)
        session.resume(activity)
        assertEquals(Contract.RESULT_PENDING, session.receive(request()).code)
        val panelIntent = shadowOf(activity).nextStartedActivity!!
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg",
            (panelIntent.getSerializableExtra("res_obj") as NetEaseShareContractTest.Resource).cover)
        repeat(5) { assertEquals(Contract.RESULT_PENDING, session.receive(request()).code) }
        assertNull(shadowOf(activity).nextStartedActivity)
        val panel = Robolectric.buildActivity(NetEaseShareContractTest.Panel::class.java, panelIntent).setup().get()
        session.pause(activity); session.resume(panel)
        assertEquals(Contract.RESULT_OPENED, session.receive(request()).code)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun `ordinary page or another link panel cannot falsely confirm success`() {
        session.resume(activity); session.receive(request())
        val intent = shadowOf(activity).nextStartedActivity!!
        session.resume(activity)
        assertEquals(Contract.RESULT_PENDING, session.receive(request()).code)
        val wrong = (intent.getSerializableExtra("res_obj") as NetEaseShareContractTest.Resource)
        wrong.webUrl = "https://music.apple.com/us/song/different/999"
        val panel = Robolectric.buildActivity(NetEaseShareContractTest.Panel::class.java, intent).setup().get()
        session.resume(panel)
        assertEquals(Contract.RESULT_PENDING, session.receive(request()).code)
        wrong.webUrl = url
        panel.finish()
        session.resume(panel)
        assertEquals(Contract.RESULT_PENDING, session.receive(request()).code)
    }
    @Test fun `paused activity is not used and request expiry permits a fresh attempt`() {
        session.resume(activity); session.pause(activity)
        assertEquals(Contract.RESULT_NOT_READY, session.receive(request()).code)
        session.resume(activity)
        assertEquals(Contract.RESULT_PENDING, session.receive(request()).code)
        assertEquals(Contract.RESULT_BUSY, session.receive(request("request-2")).code)
        now = Contract.REQUEST_TIMEOUT_MS + 1
        assertEquals(Contract.RESULT_PENDING, session.receive(request("request-2")).code)
    }
    @Test fun `version signer and member failures have distinct diagnostics`() {
        fun outcome(verify: () -> NetEaseShareProfile?, resolve: (ClassLoader, NetEaseShareProfile) -> NetEaseShareContract): Int {
            val s = NetEaseShareSession(activity.application, verify, resolve)
            s.resume(activity)
            return s.receive(request()).code
        }
        assertEquals(Contract.RESULT_UNSUPPORTED, outcome({ null }, { _, _ -> nativeContract() }))
        assertEquals(Contract.RESULT_SIGNATURE, outcome({ throw SecurityException() }, { _, _ -> nativeContract() }))
        assertEquals(Contract.RESULT_CONTRACT, outcome({ profile }, { _, _ -> throw NoSuchMethodException() }))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun `invalid links and request identifier substitution never open a panel`() {
        session.resume(activity)
        assertEquals(Contract.RESULT_INVALID, session.receive(request(link = "https://evil.test/song")).code)
        assertEquals(Contract.RESULT_INVALID, session.receive(request().apply { removeExtra(Contract.REQUEST_ID) }).code)
        assertNull(shadowOf(activity).nextStartedActivity)
        session.receive(request())
        assertEquals(Contract.RESULT_INVALID, session.receive(request(link = "https://music.apple.com/cn/song/other/789")).code)
    }
    @Test fun `activity hooks bootstrap the receiver even when startup hook never ran`() {
        val hooks = mutableMapOf<String, Pair<Executable, ModernMethodHook>>()
        val bootstrap = NetEaseShareBootstrap({ method, callback -> hooks[method.name] = method to callback }, { session })
        bootstrap.install(); bootstrap.install()
        assertEquals(setOf("onResume", "onPause"), hooks.keys)
        fun call(name: String, before: Boolean = false) {
            val (method, callback) = hooks.getValue(name)
            val param = ModernMethodHook.MethodHookParam::class.java.declaredConstructors.single()
                .apply { isAccessible = true }.newInstance(method, activity, emptyArray<Any?>()) as ModernMethodHook.MethodHookParam
            if (before) callback.beforeHookedMethod(param) else callback.afterHookedMethod(param)
        }
        call("onResume")
        var code = 0
        fun send() {
            RuntimeEnvironment.getApplication().sendOrderedBroadcast(request(), null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) { code = resultCode }
            }, Handler(Looper.getMainLooper()), 0, null, null)
            shadowOf(Looper.getMainLooper()).idle()
        }
        send()
        assertEquals(0, code) // An app without the signature permission cannot submit links.
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Contract.PERMISSION)
        send()
        assertEquals(Contract.RESULT_PENDING, code)
        assertNotNull(shadowOf(activity).nextStartedActivity)
        call("onPause", true)
        now = Contract.REQUEST_TIMEOUT_MS + 1
        assertEquals(Contract.RESULT_NOT_READY, session.receive(request("new")).code)
    }
}
