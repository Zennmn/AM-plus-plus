package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.config.TitleCorrectionMode
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.R
import dev.amenhancer.module.model.ModuleSettings

internal fun EmbeddedSettingsHost.embeddedStatusCard(activity: Activity, song: CurrentSongDetails?): View =
        embeddedCard(activity, null) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 84 else 64)
            addView(embeddedMusicIcon(activity), LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
            ))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = song?.let {
                        localizedText(ModuleText.CURRENT_SONG_TITLE, it.title.orEmpty().ifBlank { localizedText(ModuleText.UNKNOWN_TITLE) })
                    } ?: localizedText(ModuleText.CURRENT_SONG_NOT_CAPTURED)
                    textSize = embeddedTextSize(activity, 16f, 16f)
                    setEmbeddedTextColor(activity) { colors -> colors.text }
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = song?.let { "Apple Music ID：${it.appleMusicId}" } ?: localizedText(ModuleText.MUSIC_ID_PLAY_HINT)
                    textSize = embeddedTextSize(activity, 12f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    setSingleLine(false)
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 14 else 12)
            })
            addView(ImageView(activity).apply {
                setEmbeddedImageDrawable(activity,
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.ChevronRight,
                        EmbeddedSettingsPalette.Light.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = null
                setPadding(dp(activity, 5), dp(activity, 8), dp(activity, 5), dp(activity, 8))
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 32 else 28), dp(activity, 44)))
        }


internal fun EmbeddedSettingsHost.embeddedMusicIcon(activity: Activity): View =
        ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, EmbeddedMusicStatusDrawable())
            contentDescription = localizedText(ModuleText.CURRENT_SONG)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }


internal fun EmbeddedSettingsHost.embeddedFontCard(
        activity: Activity,
        manifest: dev.amenhancer.module.model.LyricsFontManifest,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ): View = embeddedCard(activity, localizedText(ModuleText.LYRICS_FONT)) {
        setPaddingRelative(dp(activity, 20), paddingTop, dp(activity, 16), paddingBottom)
        addView(TextView(activity).apply {
            text = if (manifest.enabled) manifest.displayName else localizedText(ModuleText.ORIGINAL_FONT)
            textSize = embeddedTextSize(activity, 16f, 16f)
            setEmbeddedTextColor(activity) { colors -> colors.text }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            setPadding(0, 0, 0, dp(activity, 4))
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = if (manifest.enabled) {
                localizedText(ModuleText.LYRICS_FONT_ENABLED_SUMMARY)
            } else {
                localizedText(ModuleText.LYRICS_FONT_IMPORT_SUMMARY)
            }
            textSize = embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setSingleLine(false)
            setPadding(0, 0, 0, dp(activity, 12))
        }, matchWidthWrapContent())
        val actions = LinearLayout(activity).apply {
            orientation = embeddedActionOrientation(activity)
            setPadding(0, 0, 0, 0)
            addView(embeddedActionButton(activity, localizedText(ModuleText.CHOOSE_FONT), onClick = onChooseFont),
                embeddedActionButtonParams(activity))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, localizedText(ModuleText.RESTORE_FONT), manifest.enabled, onClearFont),
                embeddedActionButtonParams(activity))
        }
        addView(actions, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.embeddedCard(
        activity: Activity,
        title: String?,
        outlined: Boolean = true,
        content: LinearLayout.() -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18))
        bindEmbeddedTheme(activity) { colors ->
            background = EmbeddedG3Drawable().apply {
                setColor(colors.surface)
                if (outlined) setStroke(dp(activity, 1), colors.outline)
                cornerRadius = embeddedCardCornerRadius(activity)
            }
        }
        elevation = 0f
        title?.let { section -> addView(embeddedSectionLabel(activity, section)) }
        content()
    }


internal fun EmbeddedSettingsHost.embeddedSectionLabel(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = embeddedTextSize(activity, 14f, 14f)
            setEmbeddedTextColor(activity) { colors -> colors.text }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(false)
            setPadding(0, 0, 0, dp(activity, 8))
        }


internal fun EmbeddedSettingsHost.embeddedInfoCard(
        activity: Activity,
        text: String,
        onClick: (() -> Unit)? = null,
    ): View = embeddedCard(activity, null) {
            addView(TextView(activity).apply {
                this.text = text
                textSize = embeddedTextSize(activity, 12f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.secondary }
                setSingleLine(false)
                setPadding(0, 0, 0, 0)
            }, matchWidthWrapContent())
            onClick?.let { click ->
                isClickable = true
                isFocusable = true
                bindEmbeddedClickFeedback(activity, cornerRadiusDp = 18)
                setOnClickListener { fromEmbeddedSettingsView(this) { click() } }
            }
        }


internal fun EmbeddedSettingsHost.showEmbeddedHelp(activity: Activity) {
        embeddedDialogBuilder(activity)
            .setTitle(localizedText(ModuleText.LSPOSED_HELP))
            .setMessage(
                localizedText(ModuleText.LSPOSED_HELP_SCOPE) +
                    localizedText(ModuleText.LSPOSED_HELP_RESTART),
            )
            .setPositiveButton(localizedText(ModuleText.GOT_IT), null)
            .show()
    }


internal fun EmbeddedSettingsHost.embeddedSettingRow(
        activity: Activity,
        title: String,
        summary: String,
        checked: Boolean,
        badge: String? = null,
        badgeAtToggle: Boolean = false,
        compactWidePadding: Boolean = false,
        enabled: Boolean = true,
        onEnableConfirmation: ((onConfirmed: () -> Unit) -> Unit)? = null,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedSettingRowHeight(activity)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.58f
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        setPaddingRelative(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    textSize = embeddedTextSize(activity, 15f, 15f)
                    setEmbeddedTextColor(activity) { colors -> colors.text }
                    setTypeface(typeface, Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!badgeAtToggle) badge?.let { badgeText ->
                    addView(embeddedBadge(activity, badgeText))
                }
            }, matchWidthWrapContent())
            if (summary.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = summary
                    textSize = embeddedTextSize(activity, 12f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    setSingleLine(false)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            isEnabled = enabled
            textSize = 15f
            minimumWidth = dp(activity, 44)
            minimumHeight = dp(activity, 44)
            bindEmbeddedSwitchTheme(activity)
        }
        var suppressToggleCallback = false
        var committedToggleValue = checked
        toggle.setOnCheckedChangeListener { _, value ->
            if (suppressToggleCallback) return@setOnCheckedChangeListener
            if (value && !committedToggleValue && onEnableConfirmation != null) {
                suppressToggleCallback = true
                toggle.isChecked = false
                suppressToggleCallback = false
                onEnableConfirmation.invoke {
                    suppressToggleCallback = true
                    toggle.isChecked = true
                    suppressToggleCallback = false
                    committedToggleValue = true
                    onChanged(true)
                }
            } else {
                committedToggleValue = value
                onChanged(value)
            }
        }
        val toggleControls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (badgeAtToggle && badge != null) {
                addView(embeddedBadge(activity, badge), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(activity, 4) })
            }
            addView(toggle, LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 46),
                dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
            ))
        }
        addView(toggleControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
        ))
        bindEmbeddedClickFeedback(activity, cornerRadiusDp = 12)
        setOnClickListener {
            fromEmbeddedSettingsView(this) { toggle.isChecked = !toggle.isChecked }
        }
    }


internal fun EmbeddedSettingsHost.embeddedBadge(activity: Activity, text: String): View = TextView(activity).apply {
        this.text = text
        textSize = embeddedTextSize(activity, 12f, 11f)
        gravity = Gravity.CENTER
        setEmbeddedTextColor(activity) { colors -> colors.primary }
        setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2))
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(colors.selected)
                cornerRadius = dp(activity, 99).toFloat()
            }
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 6) }
    }


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, iconRes: Int): View =
        embeddedFeatureIcon(activity, iconRes, EmbeddedSettingsPalette.Light.accent)


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, iconRes: Int, iconTint: Int): View =
        embeddedFeatureIcon(activity, activity.getDrawable(iconRes), iconTint)


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, icon: Drawable?, iconTint: Int): View =
        FrameLayout(activity).apply {
            bindEmbeddedTheme(activity) { colors ->
                background = EmbeddedG3Drawable().apply {
                    setColor(colors.raised)
                    cornerRadius = dp(activity, 12).toFloat()
                }
            }
            addView(ImageView(activity).apply {
                setEmbeddedImageDrawable(activity, icon)
                bindEmbeddedIconTint(activity, iconTint)
                contentDescription = null
                val iconPadding = dp(activity, if (isEmbeddedPhone(activity)) 10 else 8)
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }


private fun EmbeddedSettingsHost.embeddedInlineSvgIcon(
        activity: Activity,
        icon: EmbeddedSvgIcon,
): ImageView = ImageView(activity).apply {
        setEmbeddedImageDrawable(activity, embeddedSvgDrawable(icon, activity))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = null
        setPadding(0, 0, 0, 0)
    }


internal fun EmbeddedSettingsHost.embeddedBlurRadiusRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPaddingRelative(
            dp(activity, 12),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 10),
            dp(activity, 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 8),
        )
        val title = localizedText(ModuleText.LYRIC_BLUR_OFFSET)
        val label = TextView(activity).apply {
            text = localizedText(ModuleText.SETTING_VALUE, title, value, "px")
            textSize = embeddedTextSize(activity, 15f, 15f)
            setEmbeddedTextColor(activity) { colors -> colors.text }
        }
        addView(label, matchWidthWrapContent())
        val seekBar = SeekBar(activity).apply {
            bindEmbeddedSeekBarTheme(activity)
            minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 32 else 36)
            contentDescription = title
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = value - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val next = (progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX).coerceIn(
                        ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                        ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    )
                    label.text = localizedText(ModuleText.SETTING_VALUE, title, next, "px")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged(
                            (seekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX)
                                .coerceIn(
                                    ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                    ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                ),
                        )
                    }
                }
            })
        }
        addView(seekBar, matchWidthWrapContent().apply {
            marginStart = -seekBar.paddingStart
            marginEnd = -seekBar.paddingEnd
        })
    }

    /** SeekBar row for the gated liquid-glass extras, with a per-row one-tap default restore. */

    internal fun EmbeddedSettingsHost.embeddedGlassRangeRow(
            activity: Activity,
            title: String,
            value: Int,
            defaultValue: Int,
            rangeMin: Int,
            rangeMax: Int,
            suffix: String,
            unit: String = "dp",
            icon: EmbeddedSvgIcon? = null,
            onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPaddingRelative(dp(activity, 12), 0, dp(activity, 8), 0)
        val titleLabel = TextView(activity).apply {
            text = title
            textSize = embeddedTextSize(activity, 15f, 15f)
            setEmbeddedTextColor(activity) { colors -> colors.text }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
        }
        val valueLabel = TextView(activity).apply {
            text = "$value$unit"
            textSize = embeddedTextSize(activity, 13f, 13f)
            setEmbeddedTextColor(activity) { colors -> colors.primary }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        // Keep the 44dp touch target transparent while centering a light 18dp glyph.
        val reset = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, embeddedSvgDrawable(EmbeddedSvgIcon.RestoreDefault, activity))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "$title，${localizedText(ModuleText.RESET_DEFAULT)}"
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 13), dp(activity, 13), dp(activity, 13), dp(activity, 13))
            bindEmbeddedClickFeedback(activity, cornerRadiusDp = 22)
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            icon?.let {
                addView(embeddedInlineSvgIcon(activity, it), LinearLayout.LayoutParams(
                    dp(activity, 20),
                    dp(activity, 20),
                ).apply { marginEnd = dp(activity, 8) })
            }
            addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(valueLabel, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                addView(reset, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 8) })
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = suffix
            textSize = embeddedTextSize(activity, 12f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.secondary }
            setSingleLine(false)
            maxLines = 3
            setPadding(0, dp(activity, 4), 0, 0)
        }, matchWidthWrapContent())
        val seekBar = SeekBar(activity).apply {
            bindEmbeddedSeekBarTheme(activity)
            minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 32 else 36)
            contentDescription = title
            max = rangeMax - rangeMin
            progress = value - rangeMin
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = "${(progress + rangeMin).coerceIn(rangeMin, rangeMax)}$unit"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged((seekBar.progress + rangeMin).coerceIn(rangeMin, rangeMax))
                    }
                }
            })
        }
        addView(seekBar, matchWidthWrapContent().apply {
            topMargin = dp(activity, 8)
            marginStart = -seekBar.paddingStart
            marginEnd = -seekBar.paddingEnd
        })
        reset.setOnClickListener {
            valueLabel.text = "$defaultValue$unit"
            seekBar.progress = defaultValue - rangeMin
            onChanged(defaultValue)
        }
    }


internal fun EmbeddedSettingsHost.embeddedDpiOverrideRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = embeddedNavigationRow(
        activity = activity,
        title = localizedText(ModuleText.MUSIC_DPI),
        summary = if (value == ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI) {
            localizedText(ModuleText.MUSIC_DPI_DEFAULT_SUMMARY).substringBefore(" ·")
        } else {
            localizedText(ModuleText.MUSIC_DPI_VALUE_SUMMARY, value).substringBefore(" ·")
        },
        inlineSummary = true,
        onClick = { showEmbeddedDpiOverrideDialog(activity, value, onChanged) },
    )


internal fun EmbeddedSettingsHost.showEmbeddedDpiOverrideDialog(
        activity: Activity,
        currentValue: Int,
        onSelected: (Int) -> Unit,
    ) {
        val input = embeddedLyricsEditorInput(
            activity = activity,
            hint = localizedText(ModuleText.MUSIC_DPI_INPUT_HINT),
            initial = currentValue.toString(),
            numeric = true,
        )
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 24),
                dp(activity, 8),
                dp(activity, 24),
                dp(activity, 8),
            )
            addView(input, matchWidthWrapContent())
        }
        val dialog = embeddedDialogBuilder(activity)
            .setTitle(localizedText(ModuleText.MUSIC_DPI))
            .setMessage(localizedText(ModuleText.MUSIC_DPI_NOTICE))
            .setView(content)
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setPositiveButton(localizedText(ModuleText.SAVE), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = input.text?.toString()?.trim()?.toIntOrNull()
                if (parsed == null || !ModuleSettings.isValidAppleMusicDpi(parsed)) {
                    input.error = localizedText(ModuleText.MUSIC_DPI_INVALID)
                    return@setOnClickListener
                }
                onSelected(parsed)
                dialog.dismiss()
            }
        }
        dialog.show()
    }


internal fun EmbeddedSettingsHost.showEmbeddedTitleCorrectionModePicker(
        activity: Activity,
        onSelected: (TitleCorrectionMode) -> Unit,
    ) {
        val modes = TitleCorrectionMode.values()
        val current = controller.currentSettings().titleCorrectionMode
        val labels = modes.map { localizedText(it.displayText) }.toTypedArray()
        embeddedDialogBuilder(activity)
            .setTitle(localizedText(ModuleText.TITLE_CORRECTION_MODE))
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                modes.getOrNull(which)?.let(onSelected)
                dialog.dismiss()
            }
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .show()
    }


internal fun EmbeddedSettingsHost.embeddedNavigationRow(
        activity: Activity,
        title: String,
        summary: String,
        icon: EmbeddedSvgIcon? = null,
        clickable: Boolean = true,
        inlineSummary: Boolean = false,
        compactWidePadding: Boolean = false,
        chevronTint: Int = EmbeddedSettingsPalette.Light.muted,
        summaryMaxLines: Int = 2,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        val showIcon = icon != null
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedNavigationRowHeight(activity, showIcon)
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        isClickable = clickable
        isFocusable = clickable
        contentDescription = listOf(title, summary)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        setPaddingRelative(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        if (clickable) {
            stateListAnimator = null
            bindEmbeddedTheme(activity) { colors ->
                foreground = RippleDrawable(
                    ColorStateList.valueOf(colors.selected),
                    null,
                    EmbeddedG3Drawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(activity, 8).toFloat()
                    },
                )
            }
            setOnClickListener { fromEmbeddedSettingsView(this) { onClick() } }
        }
        if (icon != null) {
            addView(embeddedFeatureIcon(activity, embeddedSvgDrawable(icon, activity), EmbeddedSettingsPalette.Light.accent), LinearLayout.LayoutParams(embeddedFeatureIconSize(activity), embeddedFeatureIconSize(activity)).apply {
                marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
            })
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = embeddedTextSize(activity, if (showIcon) 16f else 15f, if (showIcon) 16f else 15f)
                setEmbeddedTextColor(activity) { colors -> colors.text }
                typeface = Typeface.create(
                    if (showIcon) "sans-serif-medium" else "sans-serif",
                    Typeface.NORMAL,
                )
                setSingleLine(false)
                maxLines = 2
            }, matchWidthWrapContent())
            if (!inlineSummary && summary.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = summary
                    textSize = embeddedTextSize(activity, 12f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.secondary }
                    setSingleLine(false)
                    maxLines = summaryMaxLines
                    if (isEmbeddedPhone(activity) || summaryMaxLines == 1) {
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    setPadding(0, dp(activity, 3), 0, 0)
                }, matchWidthWrapContent())
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (inlineSummary && summary.isNotBlank()) {
            addView(TextView(activity).apply {
                text = summary
                textSize = embeddedTextSize(activity, 12f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.secondary }
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 44),
            ).apply { marginStart = dp(activity, 8) })
        }
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity,
                EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.ChevronRight,
                    chevronTint,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
            minimumHeight = dp(activity, 44)
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
        }, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 44)))
    }


internal fun EmbeddedSettingsHost.embeddedActionButton(
        activity: Activity,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        bindEmbeddedButtonTheme(activity, tintBackground = false)
        text = label
        isAllCaps = false
        textSize = 13f
        letterSpacing = 0f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(activity, 48)
        minimumHeight = dp(activity, 48)
        elevation = 0f
        setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
        setEmbeddedTextColor(activity) { colors -> if (enabled) colors.text else colors.disabledText }
        bindEmbeddedTheme(activity) { colors ->
            background = embeddedActionRipple(
                activity = activity,
                colors = colors,
                content = EmbeddedG3Drawable().apply {
                    setColor(if (enabled) colors.raised else colors.disabledSurface)
                    setStroke(dp(activity, 1), if (enabled) colors.outline else colors.disabled)
                    cornerRadius = dp(activity, 12).toFloat()
                },
            )
            backgroundTintList = null
        }
        stateListAnimator = null
        setOnClickListener {
            if (enabled) fromEmbeddedSettingsView(this) { onClick() }
        }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorButton(
        activity: Activity,
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = embeddedTextSize(activity, 13f, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setEmbeddedTextColor(activity) { colors -> colors.primary }
        val horizontalPadding = dp(activity, 6)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        minWidth = 0
        minimumWidth = 0
        minimumHeight = dp(activity, 32)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setSingleLine(false)
        setHorizontallyScrolling(false)
        ellipsize = null
        bindEmbeddedClickFeedback(activity, cornerRadiusDp = 8)
        setOnClickListener { fromEmbeddedSettingsView(this) { onClick() } }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorActionRows(
        activity: Activity,
        actions: List<EmbeddedLyricsEditorAction>,
    ): View = object : LinearLayout(activity) {
        private val buttons = actions.map { action ->
            embeddedLyricsEditorButton(activity, action.compactLabel, action.onClick).apply {
                contentDescription = action.label
            }
        }
        private var lastRowWidth = -1
        private var lastLabelWidths = emptyList<Int>()

        init {
            orientation = VERTICAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val gap = dp(activity, 8)
            val labelWidths = buttons.map { button ->
                kotlin.math.ceil(Layout.getDesiredWidth(button.text, button.paint).toDouble()).toInt() +
                    button.compoundPaddingLeft + button.compoundPaddingRight
            }
            val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                labelWidths.sum() + gap * (buttons.size - 1).coerceAtLeast(0)
            } else {
                MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            }.coerceAtLeast(1)
            if (availableWidth != lastRowWidth || labelWidths != lastLabelWidths) {
                // Reuse the themed buttons and callbacks when a viewport change reflows them.
                buttons.forEach { button -> (button.parent as? ViewGroup)?.removeView(button) }
                removeAllViews()
                var row: LinearLayout? = null
                var usedWidth = 0
                buttons.forEachIndexed { index, button ->
                    val buttonWidth = labelWidths[index].coerceAtMost(availableWidth)
                    if (row == null || usedWidth + gap + buttonWidth > availableWidth) {
                        row = LinearLayout(activity).apply {
                            orientation = HORIZONTAL
                            gravity = Gravity.CENTER
                            setPadding(0, dp(activity, 2), 0, dp(activity, 2))
                        }
                        addView(row, matchWidthWrapContent())
                        usedWidth = 0
                    }
                    row?.addView(button, LinearLayout.LayoutParams(
                        buttonWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (usedWidth > 0) marginStart = gap })
                    usedWidth += buttonWidth + if (usedWidth > 0) gap else 0
                }
                lastRowWidth = availableWidth
                lastLabelWidths = labelWidths
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorInput(
        activity: Activity,
        hint: String,
        initial: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
        password: Boolean = false,
        maxLength: Int = 0,
    ): EditText = object : EditText(activity) {
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private var touchStartY = 0f
        private var draggingText = false
        private var textOwnsDrag = false

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!multiline) return super.onTouchEvent(event)

            val deltaY = touchStartY - event.y
            // Read the range before native scrolling reaches an edge on this move.
            val canScrollInDirection = when {
                deltaY > 0f -> canScrollVertically(1)
                deltaY < 0f -> canScrollVertically(-1)
                else -> canScrollVertically(-1) || canScrollVertically(1)
            }
            val handled = super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    draggingText = false
                    textOwnsDrag = false
                    parent?.requestDisallowInterceptTouchEvent(
                        canScrollVertically(-1) || canScrollVertically(1),
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!draggingText && kotlin.math.abs(deltaY) > touchSlop) {
                        draggingText = true
                        textOwnsDrag = canScrollInDirection
                    }
                    if (draggingText) {
                        // Hand off a new outward drag at an edge. Keep an active text drag
                        // stable so the outer ScrollView cannot jump by its accumulated delta.
                        parent?.requestDisallowInterceptTouchEvent(textOwnsDrag || hasSelection())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingText = false
                    textOwnsDrag = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return handled
        }
    }.apply {
        bindEmbeddedInputTheme(activity)
        this.hint = hint
        setText(initial)
        textSize = 14f
        includeFontPadding = false
        setEmbeddedTextColor(activity) { colors -> colors.text }
        setEmbeddedHintColor(activity) { colors -> colors.muted }
        bindEmbeddedTheme(activity) { colors ->
            background = EmbeddedG3Drawable().apply {
                setColor(colors.raised)
                setStroke(dp(activity, 1), colors.outline)
                cornerRadius = dp(activity, 12).toFloat()
            }
            backgroundTintList = null
        }

        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            multiline -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            setLines(if (isEmbeddedPhone(activity)) 14 else 18)
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            setHorizontallyScrolling(false)
        } else {
            isSingleLine = true
        }
        setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9))
        if (maxLength > 0) {
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }
    }


/**
 * One editable text setting whose value opens a themed input dialog. Used by the
 * OpenAI-compatible LLM lyrics fallback rows on the lyrics settings page.
 */
internal fun EmbeddedSettingsHost.embeddedTextValueRow(
        activity: Activity,
        title: String,
        value: String,
        emptySummary: String,
        hint: String,
        notice: String? = null,
        password: Boolean = false,
        masked: Boolean = false,
        maxLength: Int = 0,
        onChanged: (String) -> Unit,
    ): View = embeddedNavigationRow(
        activity = activity,
        title = title,
        summary = when {
            value.isBlank() -> emptySummary
            masked -> "••••••"
            else -> value
        },
        // Long endpoint/model values must not consume the title's weighted width.
        inlineSummary = false,
        summaryMaxLines = 1,
        onClick = {
            showEmbeddedTextValueDialog(
                activity = activity,
                title = title,
                notice = notice,
                hint = hint,
                initial = value,
                password = password,
                maxLength = maxLength,
                onSelected = onChanged,
            )
        },
    )


/**
 * Text input dialog shared by the OpenAI-compatible configuration rows.
 *
 * A password field renders without echoing the value and stays out of the IME
 * suggestion pipeline; the value is never written to a log.
 */
internal fun EmbeddedSettingsHost.showEmbeddedTextValueDialog(
        activity: Activity,
        title: String,
        notice: String?,
        hint: String,
        initial: String,
        password: Boolean,
        maxLength: Int = 0,
        onSelected: (String) -> Unit,
    ) {
        val input = embeddedLyricsEditorInput(
            activity = activity,
            hint = hint,
            initial = initial,
            password = password,
            maxLength = maxLength,
        )
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 24),
                dp(activity, 8),
                dp(activity, 24),
                dp(activity, 8),
            )
            addView(input, matchWidthWrapContent())
        }
        val builder = embeddedDialogBuilder(activity).setTitle(title)
        if (notice != null) builder.setMessage(notice)
        val dialog = builder
            .setView(content)
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setPositiveButton(localizedText(ModuleText.SAVE), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onSelected(input.text?.toString()?.trim().orEmpty())
                dialog.dismiss()
            }
        }
        dialog.show()
    }




internal fun EmbeddedSettingsHost.embeddedIconActionButton(
        activity: Activity,
        label: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): View = embeddedIconActionButton(activity, label, activity.getDrawable(iconRes), onClick)


internal fun EmbeddedSettingsHost.embeddedCompactLyricsActionBar(
        activity: Activity,
        onAdd: () -> Unit,
        onTtml: () -> Unit,
        onUpdate: () -> Unit,
        onBackup: (View) -> Unit,
    ): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 3), dp(activity, 4), dp(activity, 3), dp(activity, 4))
            bindEmbeddedTheme(activity) { colors ->
                background = EmbeddedG3Drawable().apply {
                    setColor(colors.raised)
                    cornerRadius = dp(activity, 12).toFloat()
                    setStroke(dp(activity, 1), colors.outline)
                }
            }
            contentDescription = localizedText(ModuleText.LYRICS_ACTIONS)
        }

        fun addAction(
            label: String,
            description: String,
            icon: Drawable?,
            onClick: (View) -> Unit,
        ) {
            val item = embeddedCompactLyricsActionItem(
                activity = activity,
                label = label,
                description = description,
                icon = icon,
                onClick = onClick,
            )
            container.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (container.childCount > 0) marginStart = dp(activity, 1)
            })
        }

        addAction(
            label = localizedText(ModuleText.ADD),
            description = localizedText(ModuleText.ADD_LYRICS),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.AddLyrics, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.AddCircle,
                EmbeddedSettingsPalette.Light.accent,
            ),
            onClick = { onAdd() },
        )
        addAction(
            label = "TTML",
            description = localizedText(ModuleText.IMPORT_TTML),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.ImportTtml, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.TtmlDocument,
                EmbeddedSettingsPalette.Light.accent,
            ),
            onClick = { onTtml() },
        )
        addAction(
            label = localizedText(ModuleText.UPDATE),
            description = localizedText(ModuleText.LYRICS_UPDATE),
            icon = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Refresh,
                EmbeddedSettingsPalette.Light.accent,
            ),
            onClick = { onUpdate() },
        )
        addAction(
            label = localizedText(ModuleText.BACKUP),
            description = localizedText(ModuleText.BACKUP_AND_RESTORE),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.BackupRestore, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.CloudBackup,
                EmbeddedSettingsPalette.Light.accent,
            ),
            onClick = { anchor -> onBackup(anchor) },
        )
        return container
    }


internal fun EmbeddedSettingsHost.embeddedCompactLyricsActionItem(
        activity: Activity,
        label: String,
        description: String,
        icon: Drawable?,
        onClick: (View) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = description
        val glyphSize = dp(activity, 22)
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            bindEmbeddedIconTint(activity, EmbeddedSettingsPalette.Light.primary)
            contentDescription = null
        }, LinearLayout.LayoutParams(glyphSize, glyphSize))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 11.5f, 11f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.primary }
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 20)))
        stateListAnimator = null
        bindEmbeddedTheme(activity) { colors ->
            foreground = embeddedActionRipple(activity, colors)
        }
        setOnClickListener { fromEmbeddedSettingsView(this) { onClick(this) } }
    }


internal fun EmbeddedSettingsHost.embeddedIconActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 64 else 56)
        contentDescription = label
        bindEmbeddedTheme(activity) { colors ->
            background = embeddedActionRipple(
                activity = activity,
                colors = colors,
                content = EmbeddedG3Drawable().apply {
                    setColor(colors.raised)
                    setStroke(dp(activity, 1), colors.outline)
                    cornerRadius = dp(activity, 12).toFloat()
                },
            )
            backgroundTintList = null
        }
        val glyphSize = embeddedActionGlyphSize(activity)
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            bindEmbeddedIconTint(activity, EmbeddedSettingsPalette.Light.primary)
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(
            glyphSize,
            glyphSize,
        ))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 13f, 13f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.text }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            setPadding(0, dp(activity, if (isEmbeddedPhone(activity)) 2 else 3), 0, 0)
            translationY = if (isEmbeddedPhone(activity)) 0f else -dp(activity, 3).toFloat()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stateListAnimator = null
        setOnClickListener { fromEmbeddedSettingsView(this) { onClick() } }
    }


internal fun EmbeddedSettingsHost.embeddedEntryActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        tint: Int,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        bindEmbeddedTheme(activity) { colors ->
            background = embeddedActionRipple(
                activity = activity,
                colors = colors,
                content = EmbeddedG3Drawable().apply {
                    setColor(colors.raised)
                    setStroke(dp(activity, 1), colors.outline)
                    cornerRadius = dp(activity, 12).toFloat()
                },
            )
            backgroundTintList = null
        }
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            bindEmbeddedIconTint(activity, tint)
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply {
            marginEnd = dp(activity, 8)
        })
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 13f, 13f)
            setEmbeddedTextColor(activity) { it.iconColor(tint) }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        stateListAnimator = null
        setOnClickListener { fromEmbeddedSettingsView(this) { onClick() } }
    }


internal fun EmbeddedSettingsHost.embeddedIconActionButtonParams(
        activity: Activity,
        wideWeight: Float = 1f,
    ): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 80))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 75), wideWeight)
        }


internal fun EmbeddedSettingsHost.embeddedActionGlyphSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 28 else 36)


internal fun EmbeddedSettingsHost.showEmbeddedActionMenu(
        activity: Activity,
        anchor: View,
        actions: List<Pair<String, () -> Unit>>,
        destructiveIndex: Int = -1,
    ) {
        if (!anchor.isAttachedToWindow || activity.isFinishing || activity.isDestroyed || actions.isEmpty()) return
        val root = anchor.rootView
        (root.getTag(R.id.ampp_embedded_popup_menu) as? ListPopupWindow)?.dismiss()
        val shadow = EmbeddedPanelShadow(activity.resources.displayMetrics.density)
        val shadowInset = shadow.outsetPx
        val inset = dp(activity, 8) + shadowInset
        val radius = dp(activity, 18).toFloat()
        shadow.prepare(radius)
        val surface = EmbeddedG3Drawable().apply { cornerRadius = radius }
        val selection = EmbeddedG3Drawable().apply { cornerRadius = dp(activity, 12).toFloat() }
        val menuBackground = object : InsetDrawable(surface, shadowInset) {
            private val surfaceBounds = RectF()

            override fun draw(canvas: Canvas) {
                surfaceBounds.set(
                    (bounds.left + shadowInset).toFloat(), (bounds.top + shadowInset).toFloat(),
                    (bounds.right - shadowInset).toFloat(), (bounds.bottom - shadowInset).toFloat(),
                )
                shadow.draw(canvas, surfaceBounds, radius)
                super.draw(canvas)
            }

            override fun getOutline(outline: Outline) {
                // The cached contact shadow replaces the platform's broad elevation shadow.
                outline.setEmpty()
            }

            override fun getPadding(padding: android.graphics.Rect): Boolean {
                padding.set(inset, inset, inset, inset)
                return true
            }
        }
        fun applySurface(colors: EmbeddedSettingsColors) {
            surface.setColor(colors.surface)
            surface.setStroke(dp(activity, 1), colors.outline)
            selection.setColor(colors.selected)
        }
        applySurface(embeddedPalette(activity))
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = actions.size
            override fun getItem(position: Int): String = actions[position].first
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (convertView as? TextView ?: TextView(embeddedThemedContext(activity)).apply {
                    textSize = 14f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    minHeight = dp(activity, 48)
                    setPaddingRelative(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                    layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    bindEmbeddedClickFeedback(activity, cornerRadiusDp = 12)
                    bindEmbeddedTheme(activity) { colors ->
                        setTextColor(if (getTag(R.id.ampp_embedded_popup_menu) == true) colors.primary else colors.text)
                    }
                }).apply {
                    text = getItem(position)
                    val destructive = position == destructiveIndex
                    setTag(R.id.ampp_embedded_popup_menu, destructive)
                    val colors = embeddedPalette(activity)
                    setTextColor(if (destructive) colors.primary else colors.text)
                }
        }
        val visibleFrame = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        val availableWidth = (if (visibleFrame.isEmpty) activity.resources.displayMetrics.widthPixels else visibleFrame.width())
            .minus(dp(activity, 16)).coerceAtLeast(1)
        val maxContentWidth = (availableWidth - inset * 2).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxContentWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var contentWidth = minOf(dp(activity, 176), maxContentWidth)
        val measuringParent = FrameLayout(activity)
        for (index in actions.indices) {
            val row = adapter.getView(index, null, measuringParent)
            row.measure(widthSpec, heightSpec)
            contentWidth = maxOf(contentWidth, row.measuredWidth)
        }
        // Use framework styling: module style resources cannot be resolved by the host context.
        val popup = ListPopupWindow(embeddedThemedContext(activity)).apply {
            anchorView = anchor
            isModal = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setDropDownGravity(Gravity.END)
            verticalOffset = dp(activity, 4)
            setBackgroundDrawable(menuBackground)
            // Content width excludes the surface padding; set it after background.
            setContentWidth(contentWidth)
            setListSelector(selection)
            animationStyle = android.R.style.Animation_Toast
            setAdapter(adapter)
        }
        val anchorListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = popup.dismiss()
        }
        popup.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(anchorListener)
            if (root.getTag(R.id.ampp_embedded_popup_menu) === popup) {
                root.setTag(R.id.ampp_embedded_popup_menu, null)
            }
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            fromEmbeddedSettingsView(anchor) { actions[position].second() }
        }
        root.setTag(R.id.ampp_embedded_popup_menu, popup)
        anchor.addOnAttachStateChangeListener(anchorListener)
        try {
            popup.show()
            popup.listView?.apply {
                divider = null
                bindEmbeddedTheme(activity) { colors ->
                    applySurface(colors)
                }
            }
        } catch (error: RuntimeException) {
            if (popup.isShowing) popup.dismiss()
            anchor.removeOnAttachStateChangeListener(anchorListener)
            if (root.getTag(R.id.ampp_embedded_popup_menu) === popup) root.setTag(R.id.ampp_embedded_popup_menu, null)
            throw error
        }
    }


internal fun EmbeddedSettingsHost.showEmbeddedBackupRestoreMenu(activity: Activity, anchor: View) {
        showEmbeddedActionMenu(activity, anchor, listOf(
            localizedText(ModuleText.BACKUP_LYRICS) to {
                launchSafPicker(activity, EmbeddedSafOperation.Backup, "application/zip")
            },
            localizedText(ModuleText.RESTORE_BACKUP) to {
                launchSafPicker(
                    activity,
                    EmbeddedSafOperation.RestoreOverwrite,
                    "*/*",
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                )
            },
        ))
    }


internal fun EmbeddedSettingsHost.embeddedActionSpacer(activity: Activity): View = View(activity).apply {
        layoutParams = if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 8))
        } else {
            LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 1))
        }
    }


internal fun EmbeddedSettingsHost.embeddedSpacer(activity: Activity, height: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(activity, height))
    }


internal fun EmbeddedSettingsHost.addSwitch(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        checked: Boolean,
    ): Switch = Switch(activity).apply {
        text = label
        textSize = 15f
        isChecked = checked
        minimumHeight = dp(activity, 48)
        bindEmbeddedSwitchTheme(activity)
        setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        parent.addView(this, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.addFileButton(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        operation: EmbeddedSafOperation,
        mimeType: String,
    ) {
        Button(activity).apply {
            bindEmbeddedButtonTheme(activity, tintBackground = false)
            text = label
            isAllCaps = false
            textSize = 13f
            letterSpacing = 0f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            elevation = 0f
            setEmbeddedTextColor(activity) { colors -> colors.text }
            bindEmbeddedTheme(activity) { colors ->
                background = embeddedActionRipple(
                    activity = activity,
                    colors = colors,
                    content = EmbeddedG3Drawable().apply {
                        setColor(colors.raised)
                        setStroke(dp(activity, 1), colors.outline)
                        cornerRadius = dp(activity, 12).toFloat()
                    },
                )
                backgroundTintList = null
            }
            stateListAnimator = null
            setOnClickListener {
                fromEmbeddedSettingsView(this) { launchSafPicker(activity, operation, mimeType) }
            }
            parent.addView(this, matchWidthWrapContent())
        }
    }


internal fun EmbeddedSettingsHost.addLyricsManagement(
        parent: LinearLayout,
        activity: Activity,
        song: CurrentSongDetails?,
    ) {
        parent.addView(TextView(activity).apply {
            text = localizedText(ModuleText.CUSTOM_LYRICS_MANAGEMENT)
            textSize = embeddedTextSize(activity, 18f, 17f)
            setEmbeddedTextColor(activity) { colors -> colors.text }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(false)
            setPadding(0, dp(activity, 18), 0, dp(activity, 6))
        }, matchWidthWrapContent())

        parent.addView(
            embeddedActionButton(activity, localizedText(ModuleText.ADD_LYRICS_MANUALLY)) {
                showLyricsEditor(activity, null as CustomLyricsUiGroup?, song)
            },
            matchWidthWrapContent(),
        )
        if (song != null) {
            val onlineRow = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            listOf(
                "AMLL" to EmbeddedOnlineSource.AMLL,
                "AM-Lyrics" to EmbeddedOnlineSource.AM_LYRICS,
                "Lunabeat" to EmbeddedOnlineSource.LUNABEAT,
            ).forEach { (label, source) ->
                onlineRow.addView(
                    embeddedActionButton(activity, label) {
                        runAsync(activity) {
                            controller.importOnlineLyrics(
                                source,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                            )
                        }
                    },
                    embeddedActionButtonParams(activity),
                )
            }
            parent.addView(onlineRow, matchWidthWrapContent())
        }

        val entries = runCatching(controller::lyricsEntries).getOrDefault(emptyList())
        if (entries.isEmpty()) {
            parent.addView(TextView(activity).apply {
                text = localizedText(ModuleText.NO_CUSTOM_LYRICS)
                setEmbeddedTextColor(activity) { colors -> colors.secondary }
            }, matchWidthWrapContent())
        }
        entries.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            }
            row.addView(TextView(activity).apply {
                text = "${entry.displayName.ifBlank { entry.appleMusicId.toString() }}  ·  ${entry.appleMusicId}"
                setEmbeddedTextColor(activity) { colors -> colors.text }
                setSingleLine(false)
            }, matchWidthWrapContent())
            val actions = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            actions.addView(
                embeddedActionButton(activity, localizedText(ModuleText.EDIT)) {
                    showLyricsEditor(activity, entry, song)
                },
                embeddedActionButtonParams(activity),
            )
            actions.addView(
                embeddedActionButton(
                    activity,
                    if (entry.enabled) localizedText(ModuleText.DISABLE) else localizedText(ModuleText.ENABLE),
                ) {
                    runAsync(activity) { controller.setLyricsEnabled(entry.appleMusicId, !entry.enabled) }
                },
                embeddedActionButtonParams(activity),
            )
            actions.addView(
                embeddedActionButton(activity, localizedText(ModuleText.DELETE)) {
                    embeddedDialogBuilder(activity)
                        .setMessage(localizedText(ModuleText.DELETE_NAMED_ITEM, entry.displayName.ifBlank { entry.appleMusicId.toString() }))
                        .setNegativeButton(localizedText(ModuleText.CANCEL), null)
                        .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                            runAsync(activity) { controller.deleteLyrics(entry.appleMusicId) }
                        }
                        .show()
                },
                embeddedActionButtonParams(activity),
            )
            row.addView(actions, matchWidthWrapContent())
            parent.addView(row, matchWidthWrapContent())
        }
    }


internal fun EmbeddedSettingsHost.embeddedSvgDrawable(icon: EmbeddedSvgIcon, activity: Activity): Drawable? {
    return EmbeddedSettingsSvgAssets.drawable(icon, followsPalette = true)
}


internal fun EmbeddedSettingsHost.loadEmbeddedArrowIcon(context: Context): Drawable =
        EmbeddedSettingsSvgAssets.drawable(EmbeddedSvgIcon.Back) ?: GradientDrawable()


internal fun EmbeddedSettingsHost.embeddedWidthDp(activity: Activity): Float {
        val widthDp = activity.resources.configuration.screenWidthDp
        if (widthDp > 0) return widthDp.toFloat()
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        return activity.resources.displayMetrics.widthPixels / density
    }


internal fun EmbeddedSettingsHost.isEmbeddedPhone(activity: Activity): Boolean = embeddedWidthDp(activity) < 600f


internal fun EmbeddedSettingsHost.embeddedTextSize(activity: Activity, phone: Float, wide: Float): Float =
        if (isEmbeddedPhone(activity)) phone else wide

    /**
     * The supplied reference is a 1280dp-wide tablet composition: 626dp for
     * the main panel and 503dp for the lyrics page.  Phones keep Android's
     * normal dialog sizing so text and touch targets remain usable.
     */

internal fun EmbeddedSettingsHost.embeddedDialogWidth(activity: Activity, page: EmbeddedSettingsPage): Int? {
        if (isEmbeddedPhone(activity)) return null
        // AlertDialog applies a 16dp inset on each side. These fractions target
        // the visible white panel after that inset, not the outer window.
        val fraction = when (page) {
            EmbeddedSettingsPage.CUSTOM_LYRICS,
            EmbeddedSettingsPage.AUTOMATIC_LYRICS,
            EmbeddedSettingsPage.LLM_LYRICS -> 0.418f
            else -> 0.514f
        }
        return (activity.resources.displayMetrics.widthPixels * fraction).toInt()
    }


/**
 * Resolves the visible panel width inside the stable full-screen dialog stage.
 * Tablet pages keep the reference composition widths; phones use the platform
 * dialog minimum width. The available window always keeps an 8dp side inset.
 */
internal fun EmbeddedSettingsHost.embeddedPanelWidth(
        activity: Activity,
        page: EmbeddedSettingsPage,
        availableWidth: Int,
): Int {
        val safeAvailableWidth = availableWidth.coerceAtLeast(0)
        val maxPanelWidth = (safeAvailableWidth - dp(activity, 8) * 2).coerceAtLeast(0)
        val preferredWidth = embeddedDialogWidth(activity, page) ?: run {
            val widthAttribute = if (
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            ) {
                android.R.attr.windowMinWidthMajor
            } else {
                android.R.attr.windowMinWidthMinor
            }
            val value = TypedValue()
            val resolved = embeddedThemedContext(activity, dialog = true).theme
                .resolveAttribute(widthAttribute, value, true)
            when {
                !resolved -> (safeAvailableWidth * 0.95f).toInt()
                value.type == TypedValue.TYPE_FRACTION ->
                    (safeAvailableWidth * TypedValue.complexToFloat(value.data)).toInt()
                value.type == TypedValue.TYPE_DIMENSION ->
                    TypedValue.complexToDimensionPixelSize(
                        value.data,
                        activity.resources.displayMetrics,
                    )
                else -> (safeAvailableWidth * 0.95f).toInt()
            }
        }
        return preferredWidth.coerceIn(0, maxPanelWidth)
    }


internal fun EmbeddedSettingsHost.embeddedDialogContentHeight(activity: Activity, page: EmbeddedSettingsPage): Int {
        val height = activity.resources.displayMetrics.heightPixels
        if (isEmbeddedPhone(activity)) return (height * 0.70f).toInt()
        val fraction = when (page) {
            EmbeddedSettingsPage.CUSTOM_LYRICS,
            EmbeddedSettingsPage.AUTOMATIC_LYRICS,
            EmbeddedSettingsPage.LLM_LYRICS -> 0.888f
            else -> 0.837f
        }
        return (height * fraction).toInt()
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorDialogWidth(activity: Activity): Int {
        val targetWidthDp = embeddedWidthDp(activity) *
            if (isEmbeddedPhone(activity)) 0.88f else 0.62f
        return dp(activity, targetWidthDp.toInt())
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorDialogHeight(activity: Activity): Int =
        embeddedDialogContentHeight(activity, EmbeddedSettingsPage.CUSTOM_LYRICS)


internal fun EmbeddedSettingsHost.embeddedTopBarHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 60 else 52)


internal fun EmbeddedSettingsHost.embeddedHeaderIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 34)


internal fun EmbeddedSettingsHost.embeddedFeatureIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 36)


internal fun EmbeddedSettingsHost.embeddedSettingRowHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 64 else 56)


internal fun EmbeddedSettingsHost.embeddedNavigationRowHeight(
        activity: Activity,
        showIcon: Boolean = false,
): Int = dp(
        activity,
        if (showIcon) {
            68
        } else {
            if (isEmbeddedPhone(activity)) 56 else 52
        },
)


internal fun EmbeddedSettingsHost.embeddedSearchFieldHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 48 else 44)


internal fun EmbeddedSettingsHost.embeddedActionOrientation(activity: Activity): Int =
        if (isEmbeddedPhone(activity)) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL


internal fun EmbeddedSettingsHost.embeddedActionButtonParams(activity: Activity): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
        }






internal fun EmbeddedSettingsHost.dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()


internal fun EmbeddedSettingsHost.embeddedCardCornerRadius(activity: Activity): Float =
        dp(activity, 18).toFloat()


private fun EmbeddedSettingsHost.embeddedActionRipple(
        activity: Activity,
        colors: EmbeddedSettingsColors,
        content: Drawable? = null,
): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf((colors.primary and 0x00FFFFFF) or 0x24000000),
        content,
        EmbeddedG3Drawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(activity, 12).toFloat()
        },
    )


internal fun EmbeddedSettingsHost.matchWidthWrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

