package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings

/** MIUI MiPlay action plus the public AOSP output-picker API. */
internal object PlatformAudioOutputSwitcher {
    fun open(context: Context) {
        val xiaomi = setOf("xiaomi", "redmi", "poco")
        if (Build.MANUFACTURER.orEmpty().lowercase() in xiaomi || Build.BRAND.orEmpty().lowercase() in xiaomi) {
            // Apple Music has no package-visibility query for MiPlay. Attempting the actual
            // activity avoids falsely rejecting a supported ROM through PackageManager checks.
            if (runCatching {
                context.startActivity(Intent("miui.intent.action.ACTIVITY_MIPLAY_DETAIL")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ModernXposedRuntime.log("player_audio_output: opened MiPlay")
                true
            }.getOrDefault(false)) return
        }
        if (Build.VERSION.SDK_INT >= 34 && runCatching {
            MediaRouter2.getInstance(context).showSystemOutputSwitcher()
        }.getOrDefault(false)) {
            ModernXposedRuntime.log("player_audio_output: opened platform output switcher")
            return
        }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ModernXposedRuntime.log("player_audio_output: opened Bluetooth settings fallback")
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
