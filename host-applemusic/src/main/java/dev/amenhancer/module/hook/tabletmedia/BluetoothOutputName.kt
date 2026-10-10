package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothClass
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Halcyon's output-name rules: remove vendor flags and prefer the matching paired name. */
internal object BluetoothOutputName {
    fun clean(name: String?): String? {
        var value = name?.filterNot(Char::isISOControl)?.trim().orEmpty()
        val prefixes = listOf("dontapplycevolume", "dontapplyvolume", "applycevolume", "applyvolume")
        while (true) {
            val prefix = prefixes.firstOrNull { value.startsWith(it, ignoreCase = true) } ?: break
            value = value.substring(prefix.length).trim()
        }
        return value.takeIf(String::isNotBlank)
    }
    fun resolve(raw: String?, paired: String?, localModels: List<String>, fallback: String = "蓝牙耳机"): String {
        val name = clean(paired) ?: clean(raw)
        return name?.takeUnless { candidate -> localModels.any { it.isNotBlank() && candidate.equals(it.trim(), ignoreCase = true) } }
            ?: fallback
    }
    fun icon(names: List<String?>, systemSpeaker: Boolean = false, deviceClass: Int? = null): OutputDeviceIcon {
        if (systemSpeaker || deviceClass in setOf(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO)) return OutputDeviceIcon.SPEAKER
        val values = names.mapNotNull(::clean).map { it.lowercase(java.util.Locale.ROOT) }
        if (values.any { "airpods" in it }) return OutputDeviceIcon.AIRPODS
        if (deviceClass in setOf(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
            return OutputDeviceIcon.HEADPHONES
        val keywords = listOf("音箱", "音响", "音響", "喇叭", "speaker", "soundbox", "soundbar", "subwoofer", "loudspeaker", "homepod")
        return if (values.any { name -> keywords.any { it in name } }) OutputDeviceIcon.SPEAKER else OutputDeviceIcon.HEADPHONES
    }
    fun matchesDevice(address: String?, name: String?, pairedAddress: String?, pairedName: String?, alias: String?): Boolean {
        if (!address.isNullOrBlank()) return address.equals(pairedAddress, ignoreCase = true)
        val selected = clean(name) ?: return false
        return selected.equals(clean(pairedName), ignoreCase = true) || selected.equals(clean(alias), ignoreCase = true)
    }
    // This ZIP has no installable manifest. Check permission on the host context before
    // reading paired devices; revocation races fall back to the selected output's name.
    @android.annotation.SuppressLint("MissingPermission")
    fun forOutput(context: Context, selectedName: String?, selectedAddress: String?, speaker: Boolean): BluetoothOutputState {
        val raw = clean(selectedName)
        var deviceClass: Int? = null
        var originalName: String? = null
        val paired = if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return@runCatching null
                val address = selectedAddress.orEmpty()
                val bonded = adapter.bondedDevices
                // A known selected address is authoritative, even if the name is generic or duplicated.
                val match = bonded.firstOrNull {
                    matchesDevice(address, raw, it.address, it.name, if (Build.VERSION.SDK_INT >= 30) it.alias else null)
                }
                deviceClass = match?.bluetoothClass?.deviceClass
                originalName = clean(match?.name)
                if (Build.VERSION.SDK_INT >= 30) clean(match?.alias) ?: originalName else originalName
            }.getOrNull()
        } else null
        val kind = icon(listOf(raw, paired, originalName), speaker, deviceClass)
        return BluetoothOutputState(resolve(raw, paired, listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.BOARD, Build.HARDWARE),
            if (kind == OutputDeviceIcon.SPEAKER) "蓝牙音响" else "蓝牙耳机"), kind)
    }
}
