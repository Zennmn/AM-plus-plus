package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

private const val RENDER_EFFECT_CACHE_LIMIT = 32

private class RenderEffectCacheKey(
    private val kind: String,
    private val parent: RenderEffect?,
    private val input: Any?,
    private val parameters: Any?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RenderEffectCacheKey &&
            kind == other.kind &&
            parent === other.parent &&
            input === other.input &&
            parameters == other.parameters

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + (parent?.hashCode() ?: 0)
        result = 31 * result + (input?.hashCode() ?: 0)
        result = 31 * result + (parameters?.hashCode() ?: 0)
        return result
    }
}

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()
    private val renderEffectCache = LinkedHashMap<RenderEffectCacheKey, RenderEffect>()

    fun rememberRenderEffect(
        kind: String,
        parent: RenderEffect?,
        input: Any? = null,
        parameters: Any? = null,
        create: () -> RenderEffect,
    ): RenderEffect {
        val key = RenderEffectCacheKey(kind, parent, input, parameters)
        renderEffectCache[key]?.let { return it }
        val effect = create()
        if (renderEffectCache.size >= RENDER_EFFECT_CACHE_LIMIT) {
            val iterator = renderEffectCache.keys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        renderEffectCache[key] = effect
        return effect
    }

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    fun update(scope: DrawScope): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
        renderEffectCache.clear()
    }
}

internal fun BackdropEffectScope.rememberRenderEffect(
    kind: String,
    parent: RenderEffect?,
    input: Any? = null,
    parameters: Any? = null,
    create: () -> RenderEffect,
): RenderEffect =
    (this as? BackdropEffectScopeImpl)?.rememberRenderEffect(kind, parent, input, parameters, create)
        ?: create()
