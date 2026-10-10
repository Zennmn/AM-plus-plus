package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

internal enum class AudioOutputKind { PHONE_SPEAKER, BLUETOOTH, BLUETOOTH_SPEAKER, HEADPHONES, EXTERNAL_SPEAKER, UNKNOWN }
internal data class AudioOutputRoute(val kind: AudioOutputKind, val name: String? = null, val address: String? = null) {
    val isBluetooth get() = kind == AudioOutputKind.BLUETOOTH || kind == AudioOutputKind.BLUETOOTH_SPEAKER
}
internal data class AudioOutputState(val name: String?, val icon: OutputDeviceIcon)

/** Connected outputs are not evidence of which device carries music. */
internal object AudioOutputRouting {
    fun select(routed: List<AudioOutputRoute>, selected: List<AudioOutputRoute>, legacy: AudioOutputRoute?): AudioOutputRoute? {
        if (routed.isNotEmpty()) {
            // With duplicated playback paths, prefer the selected route only if it is actually routed.
            return selected.firstNotNullOfOrNull { route -> routed.firstOrNull { matches(it, route) } } ?: routed.first()
        }
        return selected.firstOrNull() ?: legacy
    }

    private fun matches(first: AudioOutputRoute, second: AudioOutputRoute): Boolean {
        if (first.kind != second.kind && !(first.isBluetooth && second.isBluetooth)) return false
        if (!first.address.isNullOrBlank() && !second.address.isNullOrBlank())
            return first.address.equals(second.address, ignoreCase = true)
        val name = BluetoothOutputName.clean(first.name) ?: return false
        return name.equals(BluetoothOutputName.clean(second.name), ignoreCase = true)
    }

    fun presentation(route: AudioOutputRoute?, bluetooth: BluetoothOutputState? = null): AudioOutputState = when (route?.kind) {
        AudioOutputKind.PHONE_SPEAKER -> AudioOutputState(null, OutputDeviceIcon.SYSTEM)
        AudioOutputKind.HEADPHONES -> AudioOutputState(null, OutputDeviceIcon.HEADPHONES)
        AudioOutputKind.EXTERNAL_SPEAKER -> AudioOutputState(BluetoothOutputName.clean(route.name), OutputDeviceIcon.SPEAKER)
        AudioOutputKind.BLUETOOTH, AudioOutputKind.BLUETOOTH_SPEAKER -> AudioOutputState(
            bluetooth?.name ?: BluetoothOutputName.clean(route.name),
            if (route.kind == AudioOutputKind.BLUETOOTH_SPEAKER) OutputDeviceIcon.SPEAKER
            else bluetooth?.icon ?: BluetoothOutputName.icon(listOf(route.name)))
        else -> AudioOutputState(null, OutputDeviceIcon.SYSTEM)
    }
}
