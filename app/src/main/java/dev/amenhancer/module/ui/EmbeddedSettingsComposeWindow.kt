package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.ref.WeakReference
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** The dialog owns the AndroidX view-tree owners; the host supplies its window token and configuration. */
internal fun createEmbeddedSettingsDialog(
    activity: Activity,
    onBack: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable (dismiss: () -> Unit) -> Unit,
): Dialog {
    val dark = embeddedPalette(activity).isDark
    val theme = if (dark) android.R.style.Theme_Material_Dialog else android.R.style.Theme_Material_Light_Dialog
    val dialog = ComponentDialog(embeddedComposeContext(activity), theme)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setOwnerActivity(activity)
    dialog.setCanceledOnTouchOutside(false)
    val backCallback = onBack?.let { action ->
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = action()
        }.also { dialog.onBackPressedDispatcher.addCallback(it) }
    }
    val configuration = mutableStateOf(Configuration(activity.resources.configuration))
    val composeView = ComposeView(dialog.context).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            val effective = configuration.value
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            CompositionLocalProvider(
                LocalConfiguration provides effective,
                LocalDensity provides Density(effective.densityDpi / 160f, effective.fontScale),
                LocalLayoutDirection provides if (effective.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                },
            ) {
                MiuixTheme(controller = controller) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        content(dialog::dismiss)
                    }
                }
            }
        }
    }
    val root = object : FrameLayout(dialog.context) {
        @Suppress("DEPRECATION")
        override fun onConfigurationChanged(newConfig: Configuration) {
            val effective = Configuration(activity.resources.configuration)
            resources.updateConfiguration(effective, activity.resources.displayMetrics)
            configuration.value = effective
            super.onConfigurationChanged(newConfig)
        }
    }.apply {
        addView(composeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }
    dialog.setContentView(
        root,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    configureEmbeddedComposeWindow(dialog)
    dialog.setOnDismissListener {
        backCallback?.remove()
        composeView.disposeComposition()
        onDismiss?.invoke()
    }
    return dialog
}

@Suppress("DEPRECATION")
private fun configureEmbeddedComposeWindow(dialog: Dialog) {
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setWindowAnimations(0)
        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setDimAmount(EMBEDDED_DIALOG_DIM_AMOUNT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        statusBarColor = android.graphics.Color.TRANSPARENT
        navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
        WindowCompat.setDecorFitsSystemWindows(this, false)
    }
}

@Suppress("DEPRECATION")
private fun embeddedComposeContext(activity: Activity): Context {
    val module = checkNotNull(ModernXposedRuntime.activeModule()).moduleApplicationInfo
    val assets = activity.packageManager.getResourcesForApplication(module).assets
    val resources = Resources(assets, activity.resources.displayMetrics, Configuration(activity.resources.configuration))
    return object : ContextWrapper(activity) {
        override fun getResources(): Resources = resources
        override fun getAssets() = resources.assets
        override fun getClassLoader(): ClassLoader = ComposeView::class.java.classLoader!!
    }
}

internal fun EmbeddedSettingsHost.showEmbeddedMiuixDialog(
    activity: Activity,
    title: String,
    maxWidth: Dp = 520.dp,
    scrollContent: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    dismissOnOutside: Boolean = true,
    footer: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
): Dialog {
    val owner = dialogReference?.get()
    lateinit var dialog: Dialog
    dialog = createEmbeddedSettingsDialog(
        activity = activity,
        onDismiss = {
            auxiliaryDialogs.removeAll { it.get() == null || it.get() === dialog }
            onDismiss?.invoke()
        },
    ) { dismiss ->
        EmbeddedSettingsPanel(maxWidth, dismiss.takeIf { dismissOnOutside }) {
            EmbeddedSettingsHeader(title)
            val scroll = if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .then(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(dismiss)
            }
            if (footer != null) Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) { footer(dismiss) }
        }
    }
    if (owner?.isShowing != true || owner.ownerActivity !== activity || activity.isFinishing || activity.isDestroyed) {
        onDismiss?.invoke()
        return dialog
    }
    auxiliaryDialogs.removeAll { it.get() == null }
    auxiliaryDialogs += WeakReference(dialog)
    try {
        dialog.show()
    } catch (error: WindowManager.BadTokenException) {
        auxiliaryDialogs.removeAll { it.get() == null || it.get() === dialog }
        onDismiss?.invoke()
    }
    return dialog
}

@Composable
internal fun EmbeddedSettingsPanel(
    width: Dp,
    onDismiss: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    var stage by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var panel by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { stage = it }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val root = stage
                val child = panel
                if (dismiss != null && root != null && child != null && root.isAttached && child.isAttached &&
                    !down.isConsumed && !root.localBoundingBoxOf(child).contains(down.position)
                ) {
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null && root.isAttached && child.isAttached &&
                        !root.localBoundingBoxOf(child).contains(up.position)
                    ) {
                        up.consume()
                        dismiss?.invoke()
                    }
                }
            }
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(26.dp)
            Column(
                modifier = Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = maxHeight)
                    .animateContentSize().onGloballyPositioned { panel = it }
                    .shadow(12.dp, shape).clip(shape).background(MiuixTheme.colorScheme.background),
                content = content,
            )
        }
    }
}
