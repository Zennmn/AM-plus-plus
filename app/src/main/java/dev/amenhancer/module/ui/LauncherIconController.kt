package dev.amenhancer.module.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal class LauncherIconController(context: Context) {
    private val appContext = context.applicationContext
    private val launcherComponent = ComponentName(
        appContext,
        "${appContext.packageName}.LauncherAlias",
    )

    fun isHidden(): Boolean =
        appContext.packageManager.getComponentEnabledSetting(launcherComponent) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(hidden: Boolean) {
        appContext.packageManager.setComponentEnabledSetting(
            launcherComponent,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}
