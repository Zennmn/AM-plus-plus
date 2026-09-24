package com.kyant.backdrop.effects

import androidx.compose.ui.graphics.RenderEffect
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.rememberRenderEffect
import com.kyant.backdrop.internal.RuntimeShaderEffect
import com.kyant.backdrop.internal.chain
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.isRuntimeShaderSupported
import org.intellij.lang.annotations.Language
import kotlin.contracts.ExperimentalContracts

fun BackdropEffectScope.effect(effect: RenderEffect) {
    if (!isRenderEffectSupported()) return

    val parent = renderEffect
    renderEffect = rememberRenderEffect(
        kind = "chain",
        parent = parent,
        input = effect,
    ) {
        parent.chain(effect)
    }
}

@OptIn(ExperimentalContracts::class)
fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    @Language("AGSL") shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit
) {
    if (!isRuntimeShaderSupported()) return

    val shader = obtainRuntimeShader(key, shaderString).apply(block)
    effect(
        rememberRenderEffect(
            kind = "runtime-shader",
            parent = null,
            input = shader,
            parameters = uniformShaderName,
        ) {
            RuntimeShaderEffect(shader, uniformShaderName)
        },
    )
}
