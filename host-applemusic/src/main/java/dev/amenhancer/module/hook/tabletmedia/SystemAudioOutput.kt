package dev.amenhancer.module.hook.tabletmedia

// Adapted from Kifranei/AM-plus-plus-media-plugin, 9e3f72b (GPL-3.0).
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Read music routing; observing route changes never transfers or modifies playback. */
internal class SystemAudioOutput(private val context: Context, private val audio: AudioManager,
    private val onChanged: () -> Unit) : AutoCloseable {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val legacy = context.getSystemService(MediaRouter::class.java)
    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { onChanged() }
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) { onChanged() }
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) { onChanged() }
    }
    private var legacyRegistered = false
    private var modern: ModernRoutes? = null
    init {
        runCatching {
            legacy?.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS)
            legacyRegistered = legacy != null
        }
        if (Build.VERSION.SDK_INT >= 30) modern = runCatching { ModernRoutes(context, onChanged) }.getOrNull()
    }

    fun current(): AudioOutputState {
        // API 33+ queries the outputs anticipated for a music AudioTrack, including the selected address.
        // Unlike getDevices(GET_DEVICES_OUTPUTS), this is not the inventory of connected devices.
        val routed = if (Build.VERSION.SDK_INT >= 33) runCatching {
            audio.getAudioDevicesForAttributes(attributes).map(::deviceRoute)
        }.getOrDefault(emptyList()) else emptyList()
        val selected = if (Build.VERSION.SDK_INT >= 30) modern?.selected().orEmpty() else emptyList()
        val route = AudioOutputRouting.select(routed, selected, legacyRoute())
        val bluetooth = route?.takeIf(AudioOutputRoute::isBluetooth)?.let {
            BluetoothOutputName.forOutput(context, it.name, it.address, it.kind == AudioOutputKind.BLUETOOTH_SPEAKER)
        }
        return AudioOutputRouting.presentation(route, bluetooth)
    }

    private fun legacyRoute(): AudioOutputRoute? = runCatching {
        val route = legacy?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) ?: return@runCatching null
        val name = route.name?.toString()
        val isDefault = route === legacy.defaultRoute
        val kind = when (route.deviceType) {
            MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> AudioOutputKind.BLUETOOTH
            MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> if (isDefault) AudioOutputKind.PHONE_SPEAKER else AudioOutputKind.EXTERNAL_SPEAKER
            else -> if (isDefault) AudioOutputKind.PHONE_SPEAKER else AudioOutputKind.UNKNOWN
        }
        AudioOutputRoute(kind, name)
    }.getOrNull()

    override fun close() {
        if (legacyRegistered) runCatching { legacy?.removeCallback(callback) }
        legacyRegistered = false
        if (Build.VERSION.SDK_INT >= 30) modern?.close()
        modern = null
    }

    @TargetApi(33)
    private fun deviceRoute(device: AudioDeviceInfo): AudioOutputRoute {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioOutputKind.PHONE_SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> AudioOutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioOutputKind.BLUETOOTH_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputKind.HEADPHONES
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_DOCK -> AudioOutputKind.EXTERNAL_SPEAKER
            else -> AudioOutputKind.UNKNOWN
        }
        return AudioOutputRoute(kind, device.productName?.toString(), device.address)
    }

    @TargetApi(30)
    private class ModernRoutes(context: Context, private val onChanged: () -> Unit) : AutoCloseable {
        private val router = MediaRouter2.getInstance(context)
        private val main = Handler(Looper.getMainLooper())
        private val executor = Executor { main.post(it) }
        private var routesRegistered = false
        private var controllerRegistered = false
        private val routes = object : MediaRouter2.RouteCallback() {
            override fun onRoutesChanged(routes: List<MediaRoute2Info>) { onChanged() }
            override fun onRoutesAdded(routes: List<MediaRoute2Info>) { onChanged() }
            override fun onRoutesRemoved(routes: List<MediaRoute2Info>) { onChanged() }
        }
        private val controller = object : MediaRouter2.ControllerCallback() {
            override fun onControllerUpdated(controller: MediaRouter2.RoutingController) { onChanged() }
        }
        init {
            try {
                // A passive callback also keeps the router's selected system session synchronized.
                router.registerRouteCallback(executor, routes,
                    RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build())
                routesRegistered = true
                router.registerControllerCallback(executor, controller)
                controllerRegistered = true
            } catch (error: Throwable) { close(); throw error }
        }
        fun selected(): List<AudioOutputRoute> = if (Build.VERSION.SDK_INT >= 34) selectedApi34() else emptyList()

        @TargetApi(34)
        private fun selectedApi34(): List<AudioOutputRoute> = runCatching {
            router.systemController.selectedRoutes.map { route ->
                val kind = when (route.type) {
                    MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.PHONE_SPEAKER
                    MediaRoute2Info.TYPE_BLUETOOTH_A2DP, MediaRoute2Info.TYPE_HEARING_AID -> AudioOutputKind.BLUETOOTH
                    MediaRoute2Info.TYPE_WIRED_HEADPHONES, MediaRoute2Info.TYPE_WIRED_HEADSET,
                    MediaRoute2Info.TYPE_USB_HEADSET -> AudioOutputKind.HEADPHONES
                    MediaRoute2Info.TYPE_REMOTE_SPEAKER, MediaRoute2Info.TYPE_REMOTE_TV,
                    MediaRoute2Info.TYPE_HDMI, MediaRoute2Info.TYPE_HDMI_ARC,
                    MediaRoute2Info.TYPE_LINE_ANALOG, MediaRoute2Info.TYPE_LINE_DIGITAL,
                    MediaRoute2Info.TYPE_DOCK -> AudioOutputKind.EXTERNAL_SPEAKER
                    else -> if (Build.VERSION.SDK_INT >= 31 && route.type == MediaRoute2Info.TYPE_BLE_HEADSET)
                        AudioOutputKind.BLUETOOTH else AudioOutputKind.UNKNOWN
                }
                AudioOutputRoute(kind, route.name.toString())
            }
        }.getOrDefault(emptyList())
        override fun close() {
            if (controllerRegistered) runCatching { router.unregisterControllerCallback(controller) }
            if (routesRegistered) runCatching { router.unregisterRouteCallback(routes) }
            controllerRegistered = false; routesRegistered = false
        }
    }
}
