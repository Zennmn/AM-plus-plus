package dev.amenhancer.module.share

import android.content.Intent
import android.content.pm.PackageManager
import dev.amenhancer.module.MomentsLinkContract as Contract

/** NetEase also exposes secondary-process launcher entries that cannot host the share bridge. */
internal object NetEaseShareLaunch {
    @Suppress("DEPRECATION")
    fun resolve(packageManager: PackageManager): Intent? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(Contract.INTERMEDIARY_PACKAGE)
        val activity = packageManager.queryIntentActivities(launcher, 0).firstOrNull { candidate ->
            val info = candidate.activityInfo ?: return@firstOrNull false
            val process = info.processName ?: info.applicationInfo.processName ?: info.packageName
            // The query already respects the current enabled state, including dynamic launcher aliases.
            info.packageName == Contract.INTERMEDIARY_PACKAGE && info.exported &&
                process == Contract.INTERMEDIARY_PACKAGE
        }?.activityInfo ?: return null
        return launcher.setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
