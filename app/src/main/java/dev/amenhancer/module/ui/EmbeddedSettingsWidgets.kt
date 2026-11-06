package dev.amenhancer.module.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.i18n.ModuleText
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EmbeddedPreferencePadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

@Composable
internal fun EmbeddedSettingsHeader(
    title: String,
    start: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp)) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                .padding(horizontal = if (start != null || end != null) 48.dp else 4.dp, vertical = 12.dp),
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (start != null) Box(Modifier.align(Alignment.CenterStart).size(48.dp), contentAlignment = Alignment.Center) {
            start()
        }
        if (end != null) Box(Modifier.align(Alignment.CenterEnd).size(48.dp), contentAlignment = Alignment.Center) {
            end()
        }
    }
}

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
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = if (endAction == null) 16.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.onBackground)
            if (summary != null) Text(
                summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        endAction?.invoke()
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
        insideMargin = EmbeddedPreferencePadding,
        startAction = if (icon == null) null else {
            { EmbeddedSettingsIcon(icon, modifier = Modifier.size(22.dp)) }
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
        { EmbeddedSettingsIcon(icon, modifier = Modifier.size(22.dp)) }
    }
    if (summary != null && summaryMaxLines != Int.MAX_VALUE) {
        BasicComponent(
            startAction = start,
            endActions = {
                EmbeddedSettingsIcon(
                    EmbeddedSvgIcon.ChevronRight,
                    modifier = Modifier.size(12.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            insideMargin = EmbeddedPreferencePadding,
            onClick = onClick,
            enabled = enabled,
            role = Role.Button,
        ) {
            Text(
                title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface,
            )
            Text(
                summary,
                style = MiuixTheme.textStyles.body2,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            )
        }
        return
    }
    ArrowPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        insideMargin = EmbeddedPreferencePadding,
        startAction = start,
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
internal fun EmbeddedSettingsBrand() {
    val radius = with(LocalDensity.current) { 10.dp.toPx() }
    val drawable = remember(radius) { EmbeddedAmppBrandDrawable(cornerRadiusPx = radius) }
    EmbeddedSettingsDrawable(drawable, Modifier.size(34.dp))
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
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        EmbeddedSettingsIcon(
            icon = icon,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
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
    val hasConfirm = confirmText != null && onConfirm != null
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 284.dp || LocalDensity.current.fontScale > 1.3f
        if (hasConfirm && !compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(dismissText, onDismiss, Modifier.weight(1f), minHeight = 48.dp)
                TextButton(
                    checkNotNull(confirmText), checkNotNull(onConfirm), Modifier.weight(1f),
                    enabled = confirmEnabled, minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(dismissText, onDismiss, Modifier.fillMaxWidth(), minHeight = 48.dp)
                if (hasConfirm) TextButton(
                    checkNotNull(confirmText), checkNotNull(onConfirm), Modifier.fillMaxWidth(),
                    enabled = confirmEnabled, minHeight = 48.dp, colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.main)
            Text(
                "$preview$unit", modifier = Modifier.padding(start = 8.dp),
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (defaultValue != null) EmbeddedSettingsIconAction(
                EmbeddedSvgIcon.RestoreDefault, "$title, ${localizedText(ModuleText.RESET_DEFAULT)}",
            ) {
                preview = defaultValue
                onChanged(defaultValue)
            }
        }
        if (summary != null) Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Slider(
            value = preview.toFloat(),
            onValueChange = { preview = it.roundToInt().coerceIn(range) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            showKeyPoints = false,
            onValueChangeFinished = { onChanged(preview) },
        )
    }
}

// The legacy host entry is still a framework View.
internal fun EmbeddedSettingsHost.dp(activity: Activity, value: Int): Int =
    (value * activity.resources.displayMetrics.density).toInt()

internal fun EmbeddedSettingsHost.matchWidthWrapContent(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
