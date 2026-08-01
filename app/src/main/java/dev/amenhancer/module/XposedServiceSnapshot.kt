package dev.amenhancer.module

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService

/** Publishes remote preferences and their user-visible connection status as one value. */
internal class XposedServiceSnapshot private constructor(
    val preferences: SharedPreferences?,
    val service: XposedService?,
    val status: String,
) {
    val isRemoteAvailable: Boolean get() = preferences != null
    val isRemoteFileAvailable: Boolean get() = preferences != null && service != null

    init {
        require(status.isNotBlank()) { "libxposed connection status must not be blank" }
    }

    internal fun openRemoteFile(name: String): ParcelFileDescriptor? =
        if (!isRemoteFileAvailable) null else runCatching { service?.openRemoteFile(name) }.getOrNull()

    internal fun deleteRemoteFile(name: String): Boolean =
        if (!isRemoteFileAvailable) false else runCatching { service?.deleteRemoteFile(name) == true }.getOrDefault(false)

    companion object {
        fun waiting(): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = null,
            service = null,
            status = "等待 libxposed API 102 服务",
        )

        fun connected(
            preferences: SharedPreferences,
            frameworkName: String,
            apiVersion: Int,
            service: XposedService? = null,
        ): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = preferences,
            service = service,
            status = "已连接 $frameworkName API $apiVersion",
        )

        fun unsupported(frameworkName: String, apiVersion: Int): XposedServiceSnapshot =
            XposedServiceSnapshot(
                preferences = null,
                service = null,
                status = "$frameworkName API $apiVersion 不支持 API 102 remote preferences",
            )

        fun disconnected(): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = null,
            service = null,
            status = "libxposed 服务连接已断开",
        )
    }
}
