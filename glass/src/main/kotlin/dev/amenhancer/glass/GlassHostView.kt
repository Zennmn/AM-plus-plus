package dev.amenhancer.glass

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns only module AndroidX objects. Never reads the host application's AndroidX owners. */
class GlassHostView(context: Context) : FrameLayout(context) {
    // Keep layout/hit bounds unchanged while giving Compose's RenderNode room for
    // the reference lens expansion and its shadow on every side.
    private val bleed get() = (32 * resources.displayMetrics.density).roundToInt()
    private var owner = Owner()
    private var compositionScope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    val compose = ComposeView(context).apply {
        clipChildren = false
        clipToPadding = false
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    init {
        clipChildren = false
        clipToPadding = false
        bindOwners()
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun content(block: @Composable () -> Unit) {
        compose.setContent {
            val padding = with(LocalDensity.current) { bleed.toDp() }
            Box(Modifier.fillMaxSize().padding(padding)) { block() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        compose.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth + bleed * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight + bleed * 2, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        compose.layout(-bleed, -bleed, width + bleed, height + bleed)
    }

    private fun bindOwners() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    override fun onAttachedToWindow() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            owner = Owner()
            bindOwners()
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        // WindowRecomposer searches the host's content root, whose obfuscated AndroidX
        // owners cannot satisfy our module's types. Keep recomposition inside this island.
        val context = AndroidUiDispatcher.CurrentThread
        val ownRecomposer = Recomposer(context).also { recomposer = it }
        compositionScope = CoroutineScope(context + SupervisorJob()).also { scope ->
            scope.launch { ownRecomposer.runRecomposeAndApplyChanges() }
        }
        compose.setParentCompositionContext(ownRecomposer)
        super.onAttachedToWindow()
    }

    fun foreground(active: Boolean) {
        if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
            owner.registry.currentState = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        }
    }

    override fun onDetachedFromWindow() {
        compose.disposeComposition()
        recomposer?.cancel()
        compositionScope?.cancel()
        recomposer = null
        compositionScope = null
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}
