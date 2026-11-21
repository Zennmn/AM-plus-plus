package dev.amenhancer.host.netease

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.Serializable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetEaseShareContractTest {
    class Resource : Serializable {
        var cover = ""; var title = ""; var subTitle = ""; var content = ""; var headerTitle = ""; var webUrl = ""
    }
    class Panel : Activity() {
        companion object {
            @JvmStatic fun Lb(context: Context, type: Int, resource: Serializable, sub: Serializable?) {
                context.startActivity(Intent(context, Panel::class.java).putExtra("res_type", type).putExtra("res_obj", resource))
            }
            @JvmStatic fun Rb(context: Context, type: Int, resource: Serializable, sub: Serializable?) = Lb(context, type, resource, sub)
        }
    }
    private fun loader() = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when(name) {
            NetEaseShareContract.PANEL -> Panel::class.java
            NetEaseShareContract.RESOURCE -> Resource::class.java
            else -> super.loadClass(name)
        }
    }
    @Suppress("DEPRECATION")
    @Test fun `9581 opens native web resource panel with the exact Apple link`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val profile = NetEaseShareProfiles.find("9.5.81", 9005081)!!
        val url = "https://music.apple.com/cn/album/song/123?i=456"
        NetEaseShareContract.resolve(loader(), profile).open(activity, url, "Despacito", "Luis Fonsi", "https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Panel::class.java.name, intent.component?.className)
        assertEquals(19, intent.getIntExtra("res_type", -1))
        val nativeResource = intent.getSerializableExtra("res_obj") as Resource
        assertEquals(url, nativeResource.webUrl); assertEquals("Despacito", nativeResource.title)
        assertEquals("Luis Fonsi", nativeResource.subTitle)
        assertEquals("Despacito", nativeResource.headerTitle)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/cover/100x100bb.jpg", nativeResource.cover)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertNotEquals("com.tencent.mm", intent.component?.packageName)
    }
    @Test fun `neighbor builds and non Apple links are rejected`() {
        assertNull(NetEaseShareProfiles.find("9.5.80", 9005081))
        assertNull(NetEaseShareProfiles.find("9.5.81", 9005082))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertThrows(IllegalArgumentException::class.java) {
            NetEaseShareContract.resolve(loader(), NetEaseShareProfiles.find("9.5.81", 9005081)!!).open(activity, "https://music.163.com/song/1", "Wrong song")
        }
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
