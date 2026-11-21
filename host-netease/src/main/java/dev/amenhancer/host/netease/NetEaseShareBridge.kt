package dev.amenhancer.host.netease

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.amenhancer.module.MomentsLinkContract as Contract
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.security.MessageDigest

/** Only the NetEase main process installs this bridge. No WeChat code is hooked. */
object NetEaseShareBridge {
    private val bootstrap = NetEaseShareBootstrap()
    fun install() = bootstrap.install()
}

/** Observe actual activities: a wrapper Application must not hide foreground lifecycle events. */
internal class NetEaseShareBootstrap(
    private val hook: (Executable, ModernMethodHook) -> Unit = { method, callback ->
        ModernXposedRuntime.hookMethod(method, callback)
    },
    private val create: (Application) -> NetEaseShareSession = { NetEaseShareSession(it) },
) {
    private var installed = false
    private var session: NetEaseShareSession? = null
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            hook(Activity::class.java.getDeclaredMethod("onResume"), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != Contract.INTERMEDIARY_PACKAGE) return
                    runCatching {
                        val active = session ?: create(activity.application).also { session = it }
                        active.register()
                        active.resume(activity)
                    }.onFailure { ModernXposedRuntime.log("NetEase share resume unavailable: ${it.javaClass.simpleName}") }
                }
            })
            hook(Activity::class.java.getDeclaredMethod("onPause"), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.let { session?.pause(it) }
                }
            })
        }.onFailure { ModernXposedRuntime.log("NetEase share bootstrap unavailable: ${it.javaClass.simpleName}") }
    }
}

internal data class NetEaseShareReply(val code: Int, val detail: String? = null)

/** Main-thread session. A request is acknowledged only after its native panel actually resumes. */
internal class NetEaseShareSession(
    private val app: Application,
    private val verify: () -> NetEaseShareProfile? = { verifiedProfile(app) },
    private val resolve: (ClassLoader, NetEaseShareProfile) -> NetEaseShareContract = NetEaseShareContract::resolve,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var registered = false
    private var foreground = WeakReference<Activity>(null)
    private var contract: NetEaseShareContract? = null
    private data class Pending(val id: String, val url: String, val started: Long, var opened: Boolean = false)
    private var pending: Pending? = null

    fun register() {
        if (registered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, request: Intent) {
                if (request.action != Contract.OPEN) return
                val reply = receive(request)
                resultCode = reply.code
                resultData = reply.detail
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Contract.OPEN),
            Contract.PERMISSION, Handler(Looper.getMainLooper()), ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun resume(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        foreground = WeakReference(activity)
        val active = pending ?: return
        if (clock() - active.started > Contract.REQUEST_TIMEOUT_MS) return
        if (contract?.isPanel(activity, active.url) == true) active.opened = true
    }
    fun pause(activity: Activity) { if (foreground.get() === activity) foreground.clear() }

    fun receive(request: Intent): NetEaseShareReply {
        val url = Contract.validate(request.getStringExtra(Contract.URL)) ?: return NetEaseShareReply(Contract.RESULT_INVALID)
        val id = request.getStringExtra(Contract.REQUEST_ID)?.takeIf { it.length in 1..100 }
            ?: return NetEaseShareReply(Contract.RESULT_INVALID)
        val previous = pending
        if (previous != null && clock() - previous.started <= Contract.REQUEST_TIMEOUT_MS) {
            if (previous.id == id) {
                if (previous.url != url) return NetEaseShareReply(Contract.RESULT_INVALID)
                return NetEaseShareReply(if (previous.opened) Contract.RESULT_OPENED else Contract.RESULT_PENDING)
            }
            if (!previous.opened) return NetEaseShareReply(Contract.RESULT_BUSY)
        }
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return NetEaseShareReply(Contract.RESULT_NOT_READY)
        val profile = try { verify() } catch (_: SecurityException) {
            return NetEaseShareReply(Contract.RESULT_SIGNATURE)
        } catch (_: Exception) { return NetEaseShareReply(Contract.RESULT_UNSUPPORTED) }
            ?: return NetEaseShareReply(Contract.RESULT_UNSUPPORTED, installedVersion())
        val resolved = try {
            // The real foreground Activity may have a different loader from a wrapper Application.
            contract ?: resolve(activity.classLoader, profile).also { contract = it }
        } catch (error: Throwable) {
            ModernXposedRuntime.log("NetEase native share contract unavailable: ${error.javaClass.simpleName}")
            return NetEaseShareReply(Contract.RESULT_CONTRACT, profile.versionName)
        }
        pending = Pending(id, url, clock())
        return try {
            resolved.open(activity, url, request.getStringExtra(Contract.TITLE), request.getStringExtra(Contract.ARTIST), request.getStringExtra(Contract.ARTWORK))
            NetEaseShareReply(Contract.RESULT_PENDING)
        } catch (error: Throwable) {
            pending = null
            ModernXposedRuntime.log("NetEase native share launch failed: ${error.javaClass.simpleName}")
            NetEaseShareReply(Contract.RESULT_LAUNCH, profile.versionName)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): String? = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName}/${it.versionCode}" }
    }.getOrNull()

    companion object {
        @Suppress("DEPRECATION")
        private fun verifiedProfile(app: Application): NetEaseShareProfile? {
            val info = app.packageManager.getPackageInfo(app.packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val profile = NetEaseShareProfiles.find(info.versionName.orEmpty(), code) ?: return null
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            if (signatures.isNullOrEmpty() || signatures.any { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } != NetEaseShareProfiles.SIGNER_SHA256
            }) throw SecurityException("Unexpected NetEase signer")
            return profile
        }
    }
}
