package dev.amenhancer.glass

/** Android-free invariants used by both the host bridge and regression tests. */
object GlassPolicy {
    const val VERSION_CODE = 1586L
    const val VERSION_NAME = "6.5.2"
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

    fun supports(sdk: Int, versionCode: Long, versionName: String, tablet: Boolean) =
        sdk >= 33 && versionCode == VERSION_CODE && versionName == VERSION_NAME && !tablet

    fun selectedIndex(ids: List<Int>, selectedId: Int): Int? = ids.indexOf(selectedId).takeIf { it >= 0 }

    fun occupiedHeight(density: Float, bottomInset: Int, miniVisible: Boolean): Int =
        ((NAV_HEIGHT_DP + BOTTOM_DP + if (miniVisible) MINI_HEIGHT_DP + GAP_DP else 0) * density).toInt() + bottomInset
}
