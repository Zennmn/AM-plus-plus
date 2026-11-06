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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
    val activityReference = WeakReference(activity)
    val dark = embeddedPalette(activity).isDark
    val theme = if (dark) {
        android.R.style.Theme_Material_NoActionBar
    } else {
        android.R.style.Theme_Material_Light_NoActionBar
    }
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
                    // Keep the default MiuixPopupHost so OverlayDialog uses this Scaffold as its root.
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MiuixTheme.colorScheme.surface,
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
            val currentActivity = activityReference.get()
            val effective = currentActivity?.resources?.configuration?.let(::Configuration)
                ?: Configuration(newConfig)
            currentActivity?.let { source ->
                resources.updateConfiguration(effective, source.resources.displayMetrics)
            }
            configuration.value = effective
            super.onConfigurationChanged(newConfig)
        }
    }.apply {
        addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    dialog.setContentView(
        root,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    configureEmbeddedComposeWindow(dialog, dark)
    dialog.setOnDismissListener {
        backCallback?.remove()
        composeView.disposeComposition()
        onDismiss?.invoke()
    }
    return dialog
}

@Suppress("DEPRECATION")
private fun configureEmbeddedComposeWindow(dialog: Dialog, dark: Boolean) {
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        clearFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
        )
        setDimAmount(0f)
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        statusBarColor = android.graphics.Color.TRANSPARENT
        navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            isStatusBarContrastEnforced = false
            isNavigationBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(this, false)
        WindowCompat.getInsetsController(this, decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
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

internal class EmbeddedMiuixDialogEntry(
    internal val id: Long,
    internal val title: String,
    internal val maxWidth: Dp,
    internal val scrollContent: Boolean,
    internal val dismissOnOutside: Boolean,
    internal val onDismiss: (() -> Unit)?,
    internal val footer: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)?,
    internal val content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    internal val visible = mutableStateOf(true)
    internal var contentShown = false
    internal var closeRequested = false
    internal var released = false
    internal var onDismissDelivered = false

    internal val isShowing: Boolean
        get() = visible.value && !closeRequested && !released
}

internal class EmbeddedMiuixDialogHandle internal constructor(
    private val host: EmbeddedSettingsHost?,
    private val entry: EmbeddedMiuixDialogEntry?,
) {
    val isShowing: Boolean
        get() = entry?.isShowing == true

    fun dismiss() {
        val host = host ?: return
        val entry = entry ?: return
        host.requestEmbeddedMiuixDialogDismiss(entry)
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
): EmbeddedMiuixDialogHandle {
    if (activeSettingsDialog(activity) == null) {
        onDismiss?.invoke()
        return EmbeddedMiuixDialogHandle(null, null)
    }
    val entry = EmbeddedMiuixDialogEntry(
        id = ++auxiliaryDialogSequence,
        title = title,
        maxWidth = maxWidth,
        scrollContent = scrollContent,
        dismissOnOutside = dismissOnOutside,
        onDismiss = onDismiss,
        footer = footer,
        content = content,
    )
    auxiliaryDialogs += entry
    return EmbeddedMiuixDialogHandle(this, entry)
}

/** Render once in the root Scaffold, as a sibling after the page NavDisplay. */
@Composable
internal fun EmbeddedSettingsHost.EmbeddedSettingsOverlays() {
    auxiliaryDialogs.toList().forEach { entry ->
        key(entry.id) {
            EmbeddedMiuixOverlay(entry)
        }
    }
}

@Composable
private fun EmbeddedSettingsHost.EmbeddedMiuixOverlay(entry: EmbeddedMiuixDialogEntry) {
    val dismiss = remember(entry) {
        { requestEmbeddedMiuixDialogDismiss(entry) }
    }
    DisposableEffect(entry) {
        onDispose { releaseEmbeddedMiuixDialog(entry) }
    }
    OverlayDialog(
        show = entry.visible.value,
        title = entry.title,
        enableWindowDim = true,
        onDismissRequest = if (entry.dismissOnOutside) dismiss else null,
        onDismissFinished = { releaseEmbeddedMiuixDialog(entry) },
        renderInRootScaffold = true,
        maxWidth = entry.maxWidth,
    ) {
        SideEffect { markEmbeddedMiuixDialogContentShown(entry) }
        val scrollState = rememberScrollState()
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .then(if (entry.scrollContent) Modifier.verticalScroll(scrollState) else Modifier),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                entry.content(this, dismiss)
            }
            entry.footer?.let { footer ->
                Column(Modifier.fillMaxWidth()) {
                    footer(dismiss)
                }
            }
        }
    }
}
