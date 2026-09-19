package dev.amenhancer.module.hook

/** 1586 BottomSheetBehavior state constants and its positive slide-offset formula. */
internal object InitialGlassSlide {
    fun resolve(state: Int, top: Int, collapsedTop: Int, expandedTop: Int): Float = when (state) {
        3 -> 1f // expanded, even if layout offsets are not yet usable
        4, 5 -> 0f // collapsed / hidden
        else -> if (collapsedTop > expandedTop) {
            ((collapsedTop - top).toFloat() / (collapsedTop - expandedTop)).coerceIn(0f, 1f)
        } else 0f
    }
}
