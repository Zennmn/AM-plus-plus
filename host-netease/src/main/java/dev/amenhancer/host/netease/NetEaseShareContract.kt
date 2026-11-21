package dev.amenhancer.host.netease

import android.app.Activity
import android.content.Context
import dev.amenhancer.module.MomentsSongMetadata
import dev.amenhancer.module.MomentsLinkContract
import java.io.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class NetEaseShareProfile(val versionName: String, val versionCode: Long, val helper: String)

/** Exact inspected official builds, not a guessed neighbor-version mapping. */
internal object NetEaseShareProfiles {
    const val SIGNER_SHA256 = "54254d2be09daef48dedc2b4a4f497d153e14ed9d70814fc9c360ee9240827f7"
    fun find(name: String, code: Long): NetEaseShareProfile? = when {
        name == "9.5.81" && code == 9005081L -> NetEaseShareProfile(name, code, "Lb")
        name == "9.6.22" && code == 9006022L -> NetEaseShareProfile(name, code, "Rb")
        else -> null
    }
}

/** Uses the host's real GeneralResource and normal native sharing helper, including its own SDK. */
internal class NetEaseShareContract private constructor(
    private val constructor: Constructor<*>, private val setters: Map<String, Method>,
    private val readUrl: Method, private val openPanel: Method,
    private val panelClass: Class<*>,
) {
    fun open(activity: Activity, url: String, title: String?, artist: String? = null, artworkUrl: String? = null) {
        require(MomentsLinkContract.validate(url) != null)
        val resource = constructor.newInstance()
        fun set(name: String, value: String) { setters.getValue(name).invoke(resource, value) }
        val songTitle = requireNotNull(MomentsSongMetadata.title(title))
        set("setTitle", songTitle)
        set("setSubTitle", MomentsSongMetadata.clean(artist).orEmpty())
        set("setContent", url)
        set("setHeaderTitle", songTitle)
        set("setWebUrl", url)
        MomentsSongMetadata.artwork(artworkUrl)?.let { set("setCover", it) }
        check(readUrl.invoke(resource) == url)
        // 19 is the inspected native web-resource type. It produces WXWebpageObject, not song/image data.
        // The host opens its own visible panel; the module never chooses a target or posts content.
        openPanel.invoke(null, activity, 19, resource, null)
    }

    @Suppress("DEPRECATION")
    fun isPanel(activity: Activity, url: String): Boolean = runCatching {
        panelClass.isInstance(activity) && activity.intent.getIntExtra("res_type", -1) == 19 &&
            activity.intent.getSerializableExtra("res_obj")?.let { readUrl.invoke(it) } == url
    }.getOrDefault(false)

    companion object {
        const val PANEL = "com.netease.cloudmusic.music.biz.share.SharePanelActivity"
        const val RESOURCE = "com.netease.cloudmusic.meta.GeneralResource"
        fun resolve(loader: ClassLoader, profile: NetEaseShareProfile): NetEaseShareContract {
            val panel = loader.loadClass(PANEL)
            check(Activity::class.java.isAssignableFrom(panel))
            val resource = loader.loadClass(RESOURCE)
            check(Serializable::class.java.isAssignableFrom(resource))
            val constructor = resource.getConstructor()
            val setters = listOf("setTitle", "setSubTitle", "setContent", "setHeaderTitle", "setWebUrl", "setCover")
                .associateWith { resource.getMethod(it, String::class.java).also { method ->
                    check(method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers))
                } }
            val getter = resource.getMethod("getWebUrl").also { check(it.returnType == String::class.java) }
            val helper = panel.getDeclaredMethod(profile.helper, Context::class.java, Int::class.javaPrimitiveType,
                Serializable::class.java, Serializable::class.java).also {
                check(Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.returnType == Void.TYPE)
            }
            return NetEaseShareContract(constructor, setters, getter, helper, panel)
        }
    }
}
