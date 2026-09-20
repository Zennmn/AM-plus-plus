package dev.amenhancer.module.hook

internal class NativeLayerAlpha(var native: Float) {
    var factor = 1f
    val effective: Float get() = native * factor
    fun hostWrite(value: Float): Float {
        native = value
        return effective
    }
}
