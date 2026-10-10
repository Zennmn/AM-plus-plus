package dev.amenhancer.module.hook.tabletmedia

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles

internal object TabletMediaAssets {
    fun openAsset(name: String) = AppleMusicHostProfiles.openTabletMediaAsset(name)
}
