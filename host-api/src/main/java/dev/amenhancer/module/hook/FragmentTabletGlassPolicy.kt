package dev.amenhancer.module.hook

/** Reference595581b layout/visibility contract. */
object FragmentTabletGlassPolicy {
    /**
     * A complete menu can precede the native material expanding beyond its drawer-only square.
     * Keep native navigation visible until the expanded material and glass content are laid out.
     */
    fun navigationLayoutReady(width: Int, height: Int, contentWidth: Int, contentHeight: Int): Boolean =
        height > 1 && width > height && contentWidth == width && contentHeight in 2..height

    /** The overlay shares the root's alpha; only the native layers below that root are multiplied. */
    fun opacity(shown: Boolean, nativeAlphas: Iterable<Float>): Float {
        if (!shown) return 0f
        return nativeAlphas.fold(1f) { alpha, layer ->
            if (!layer.isFinite()) 0f else alpha * layer.coerceIn(0f, 1f)
        }.coerceIn(0f, 1f)
    }

    /** A suppressed blur material is a paint layer, not the navigation visibility source. */
    fun navigationOpacity(shown: Boolean, contentAlpha: Float, ancestorAlphas: Iterable<Float>, slide: Float): Float =
        opacity(shown, sequenceOf(contentAlpha).plus(ancestorAlphas).asIterable()) * FragmentPlayerSurfaceMotion.tabletFrame(slide).alpha

    fun bounds(rootX: Int, rootY: Int, x: Int, y: Int, width: Int, height: Int): Bounds? =
        if (width <= 0 || height <= 0) null else Bounds(x - rootX, y - rootY, width, height)

    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        fun contains(x: Float, y: Float): Boolean =
            x >= left && y >= top && x < left.toFloat() + width && y < top.toFloat() + height
    }
}
