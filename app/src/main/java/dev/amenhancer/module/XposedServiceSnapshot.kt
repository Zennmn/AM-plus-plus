package dev.amenhancer.module

import android.content.SharedPreferences

/** Publishes remote preferences and their user-visible connection status as one value. */
internal class XposedServiceSnapshot private constructor(
    val preferences: SharedPreferences?,
    val status: String,
) {
    val isRemoteAvailable: Boolean get() = preferences != null

    init {
        require(status.isNotBlank()) { "libxposed connection status must not be blank" }
    }

    companion object {
        fun waiting(): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = null,
            status = "等待 libxposed API 102 服务",
        )

        fun connected(
            preferences: SharedPreferences,
            frameworkName: String,
            apiVersion: Int,
        ): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = preferences,
            status = "已连接 $frameworkName API $apiVersion",
        )

        fun unsupported(frameworkName: String, apiVersion: Int): XposedServiceSnapshot =
            XposedServiceSnapshot(
                preferences = null,
                status = "$frameworkName API $apiVersion 不支持 API 102 remote preferences",
            )

        fun disconnected(): XposedServiceSnapshot = XposedServiceSnapshot(
            preferences = null,
            status = "libxposed 服务连接已断开",
        )
    }
}
