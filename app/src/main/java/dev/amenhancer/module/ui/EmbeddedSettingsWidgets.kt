package dev.amenhancer.module.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.i18n.ModuleText
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EmbeddedPreferencePadding = BasicComponentDefaults.InsideMargin

@Composable
internal fun EmbeddedSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Card(modifier = modifier.fillMaxWidth(), content = content)

@Composable
internal fun EmbeddedSettingsSection(
    title: String,
    summary: String? = null,
    endAction: (@Composable () -> Unit)? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val titlePadding = SmallTitleDefaults.InsideMargin
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SmallTitle(text = title)
            if (summary != null) Text(
                text = summary,
                modifier = Modifier.padding(
                    start = titlePadding.calculateStartPadding(layoutDirection),
                    end = titlePadding.calculateEndPadding(layoutDirection),
                    bottom = titlePadding.calculateBottomPadding(),
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (endAction != null) {
            Box(Modifier.padding(end = 8.dp)) { endAction() }
        }
    }
}

@Composable
internal fun EmbeddedSettingSwitch(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: EmbeddedSvgIcon? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        enabled = enabled,
        startAction = if (icon == null) null else {
            {
                EmbeddedSettingsIcon(
                    icon,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    },
                )
            }
        },
    )
}

@Composable
internal fun EmbeddedSettingLink(
    title: String,
    summary: String? = null,
    icon: EmbeddedSvgIcon? = null,
    enabled: Boolean = true,
    summaryMaxLines: Int = Int.MAX_VALUE,
    onClick: () -> Unit,
) {
    val start: (@Composable () -> Unit)? = if (icon == null) null else {
        {
            EmbeddedSettingsIcon(
                icon,
                modifier = Modifier.size(24.dp),
                tint = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                },
            )
        }
    }
    val limitedSummary = summary?.takeIf { summaryMaxLines != Int.MAX_VALUE }
    val bottomAction: (@Composable () -> Unit)? = if (limitedSummary != null) {
        {
            Text(
                text = limitedSummary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
            )
        }
    } else {
        null
    }
    ArrowPreference(
        title = title,
        summary = if (limitedSummary == null) summary else null,
        enabled = enabled,
        startAction = start,
        bottomAction = bottomAction,
        onClick = onClick,
    )
}

@Composable
internal fun EmbeddedSettingsNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
internal fun EmbeddedSettingsIcon(
    icon: EmbeddedSvgIcon,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    val drawable = remember(icon, tint) { EmbeddedSettingsSvgAssets.drawable(icon, fillColor = tint.toArgb()) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mirror = rtl && (icon == EmbeddedSvgIcon.Back || icon == EmbeddedSvgIcon.ChevronRight)
    EmbeddedSettingsDrawable(
        drawable,
        modifier
            .then(if (mirror) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
            .then(if (contentDescription == null) Modifier else Modifier.semantics {
                this.contentDescription = contentDescription
            }),
    )
}

@Composable
private fun EmbeddedSettingsDrawable(drawable: Drawable?, modifier: Modifier) {
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            drawable?.apply {
                setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
                draw(canvas.nativeCanvas)
            }
        }
    }
}

@Composable
internal fun EmbeddedSettingsIconAction(
    icon: EmbeddedSvgIcon,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = label
            role = Role.Button
            if (!enabled) disabled()
        },
    ) {
        EmbeddedSettingsIcon(
            icon = icon,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
        )
    }
}

@Composable
internal fun EmbeddedDialogActions(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
) {
    val confirm = onConfirm
    if (confirmText != null && confirm != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(dismissText, onDismiss, Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton(
                confirmText,
                confirm,
                Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    } else {
        TextButton(dismissText, onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun EmbeddedSettingsHost.EmbeddedSettingRange(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    summary: String? = null,
    defaultValue: Int? = null,
    onChanged: (Int) -> Unit,
) {
    var preview by remember(value, range) { mutableIntStateOf(value.coerceIn(range)) }
    SliderPreference(
        value = preview.toFloat(),
        onValueChange = { preview = it.roundToInt().coerceIn(range) },
        modifier = Modifier.fillMaxWidth(),
        title = title,
        summary = summary,
        valueText = "$preview$unit",
        endActions = if (defaultValue == null) null else {
            {
                EmbeddedSettingsIconAction(
                    EmbeddedSvgIcon.RestoreDefault,
                    "$title, ${localizedText(ModuleText.RESET_DEFAULT)}",
                ) {
                    preview = defaultValue
                    onChanged(defaultValue)
                }
            }
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        onValueChangeFinished = { onChanged(preview) },
        showKeyPoints = false,
    )
}

// The legacy host entry is still a framework View.
internal fun EmbeddedSettingsHost.dp(activity: Activity, value: Int): Int =
    (value * activity.resources.displayMetrics.density).toInt()

internal fun EmbeddedSettingsHost.matchWidthWrapContent(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
