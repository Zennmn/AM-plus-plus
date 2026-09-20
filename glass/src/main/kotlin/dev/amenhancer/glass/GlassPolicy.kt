package dev.amenhancer.glass

/** Android-free invariants used by both the host bridge and regression tests. */
object GlassPolicy {
    /** Host builds whose phone layout carries the verified liquid-glass seams. */
    data class SupportedBuild(val versionName: String, val versionCode: Long)

    /**
     * Every Apple Music build whose phone navigation/mini-player seams were verified to still
     * expose `navigation_host_group`, the stacked tabs frame and the holder translate entry point.
     */
    val SUPPORTED_BUILDS = listOf(
        SupportedBuild("6.5.2", 1586L),
        SupportedBuild("6.5.3", 1599L),
    )
    const val NAV_HEIGHT_DP = 56
    const val MINI_HEIGHT_DP = 43
    const val HORIZONTAL_DP = 16
    const val GAP_DP = 8
    const val BOTTOM_DP = 16
    /** Bottom fade shared by the navigation strip: blur radius and wash-out strength. */
    const val BOTTOM_SCRIM_BLUR_DP = 12f
    const val BOTTOM_SCRIM_WASH_ALPHA = 0.75f
    /** Gradient samples per fade; more stops approximate the smooth curve more closely. */
    const val BOTTOM_SCRIM_STOPS = 9
    /** Backdrop blur shared by the navigation bar and the mini-player panel. */
    const val PANEL_BLUR_DP = 4f

    fun supports(sdk: Int, versionCode: Long, versionName: String, tablet: Boolean) =
        sdk >= 33 && !tablet && isSupportedBuild(versionCode, versionName)

    fun isSupportedBuild(versionCode: Long, versionName: String): Boolean =
        SUPPORTED_BUILDS.any { supported ->
            supported.versionName == versionName && supported.versionCode == versionCode
        }

    fun selectedIndex(ids: List<Int>, selectedId: Int): Int? = ids.indexOf(selectedId).takeIf { it >= 0 }

    fun occupiedHeight(density: Float, bottomInset: Int, miniVisible: Boolean): Int =
        ((NAV_HEIGHT_DP + BOTTOM_DP + if (miniVisible) MINI_HEIGHT_DP + GAP_DP else 0) * density).toInt() + bottomInset
}
