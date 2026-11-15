package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.i18n.ModuleText
import kotlin.math.roundToInt

/**
 * Adds the module's lyrics entry after Apple Music's native pronunciation and
 * translation rows. The native rows and their callbacks remain untouched.
 */
internal class AppleMusicLyricsMenuTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val onOpen: (Activity) -> Unit,
) {
    private val rowTag = Any()
    private val rowResources = profile.document.optJSONObject("lyricsMenu")
        ?.optJSONObject("resources")
    private val layoutName: String = rowResources?.optString("rowLayout").orEmpty()
    private val endIconName: String = rowResources?.optString("endIcon").orEmpty()
    private val separatorName: String = rowResources?.optString("separator").orEmpty()

    @Volatile
    private var popup: PopupWindow? = null

    @Volatile
    private var installedResult: TargetCapabilityInstall? = null

    @Volatile
    private var enabled = false

    @Synchronized
    fun install(registration: HookRegistrationScope): TargetCapabilityInstall {
        installedResult?.let { return it }

        val names = profile.document.optJSONObject("lyricsMenu")
        if (names == null) {
            return TargetCapabilityInstall.Unsupported(
                "The native lyrics menu contract is not available for ${profile.id}",
            ).also { installedResult = it }
        }
        if (layoutName.isBlank()) {
            return TargetCapabilityInstall.Degraded(
                "The native lyrics menu row layout is missing for ${profile.id}",
            ).also { installedResult = it }
        }
        val members = try {
            FragmentProfileMembers(
                loader = loader,
                names = names,
                section = "lyricsMenu",
                required = FragmentMemberRequirements(
                    methods = setOf("evaluate", "chromeVisibility", "resume", "getActivity"),
                    fields = setOf(
                        "listenerFragment",
                        "fragmentBinding",
                        "fragmentPopup",
                        "menuAnchor",
                    ),
                    constructors = setOf("menuReadyCallback"),
                ),
            )
        } catch (error: Throwable) {
            return TargetCapabilityInstall.Degraded(
                "Lyrics menu contract failed for ${profile.id}: ${error.message ?: error.javaClass.simpleName}",
            ).also { installedResult = it }
        }

        val result = runCatching {
            check(
                ModernXposedRuntime.hookMethod(
                    members.constructor("menuReadyCallback"),
                    object : ModernMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!enabled || param.throwable != null) return
                            val listener = param.args.firstOrNull() ?: return
                            if (!members.field("listenerFragment").declaringClass.isInstance(listener)) return
                            runCatching { appendRow(listener, members) }.onFailure { error ->
                                ModernXposedRuntime.log("Lyrics menu row append failed", error)
                            }
                        }
                    },
                    registration,
                ),
            ) { "Native lyrics menu preparation hook was rejected" }

            check(
                ModernXposedRuntime.hookMethod(
                    members.method("evaluate"),
                    object : ModernMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!enabled || param.throwable != null) return
                            val fragment = param.thisObject ?: return
                            runCatching { ensureVisible(fragment, members) }.onFailure { error ->
                                ModernXposedRuntime.log("Lyrics menu anchor evaluation failed", error)
                            }
                        }
                    },
                    registration,
                ),
            ) { "Native lyrics menu visibility hook was rejected" }

            check(
                ModernXposedRuntime.hookMethod(
                    members.method("chromeVisibility"),
                    object : ModernMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!enabled || param.throwable != null) return
                            val fragment = param.thisObject ?: return
                            val visibility = param.args.firstOrNull() as? Int ?: return
                            if (visibility != View.VISIBLE) return
                            runCatching { ensureVisible(fragment, members) }.onFailure { error ->
                                ModernXposedRuntime.log("Lyrics menu chrome visibility failed", error)
                            }
                        }
                    },
                    registration,
                ),
            ) { "Native lyrics menu chrome visibility hook was rejected" }

            check(
                ModernXposedRuntime.hookMethod(
                    members.method("resume"),
                    object : ModernMethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!enabled || param.throwable != null) return
                            val fragment = param.thisObject ?: return
                            runCatching { ensureVisible(fragment, members) }.onFailure { error ->
                                ModernXposedRuntime.log("Lyrics menu resume visibility failed", error)
                            }
                        }
                    },
                    registration,
                ),
            ) { "Native lyrics menu resume hook was rejected" }

            enabled = true
            TargetCapabilityInstall.Active(
                "Native lyrics menu entry installed; ${profile.id}",
            )
        }.getOrElse { error ->
            enabled = false
            TargetCapabilityInstall.Degraded(
                "Lyrics menu registration failed for ${profile.id}: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }

        registration.onClose {
            popup?.let { active ->
                if (active.isShowing) runCatching { active.dismiss() }
            }
            popup = null
            enabled = false
        }
        installedResult = result
        return result
    }

    private fun appendRow(listener: Any, members: FragmentProfileMembers) {
        val fragment = members.field("listenerFragment").get(listener) ?: return
        val nativePopup = members.field("fragmentPopup").get(fragment) as? PopupWindow ?: return
        // Native rows are complete here, before either the first show or the
        // tablet offset hook measures them. Never grow an already visible popup.
        if (nativePopup.isShowing) return
        if (nativePopup.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
            nativePopup.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) return
        popup = nativePopup

        val container = nativePopup.contentView as? LinearLayout ?: return
        if (container.orientation != LinearLayout.VERTICAL) return
        if (container.findViewWithTag<View>(rowTag) != null) return

        val row = inflateRow(container.context) ?: return
        row.text = ModuleText.LYRICS_MENU.text()
        applyEndIcon(row)
        row.minimumHeight = maxOf(
            row.minimumHeight,
            (MIN_TOUCH_DP * row.resources.displayMetrics.density).roundToInt(),
        )
        row.isFocusable = true
        row.tag = rowTag
        row.setOnClickListener {
            val activity = runCatching {
                members.method("getActivity").invoke(fragment) as? Activity
            }.onFailure { error ->
                ModernXposedRuntime.log("Lyrics menu activity lookup failed", error)
            }.getOrNull()
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
            runCatching { nativePopup.dismiss() }.onFailure { error ->
                ModernXposedRuntime.log("Lyrics menu popup dismissal failed", error)
            }
            if (popup === nativePopup) popup = null
            activity.window.decorView.post {
                if (enabled && !activity.isFinishing && !activity.isDestroyed) {
                    runCatching { onOpen(activity) }.onFailure { error ->
                        ModernXposedRuntime.log("Lyrics menu open callback failed", error)
                    }
                }
            }
        }

        // Like the native rows, take the vertical container's default MATCH_PARENT width.
        // Giving only this row a WRAP_CONTENT width makes LinearLayout size the popup to it.
        if (container.childCount > 0) applySeparator(row)
        container.addView(row)
    }

    private fun ensureVisible(fragment: Any, members: FragmentProfileMembers) {
        val binding = members.field("fragmentBinding").get(fragment) ?: return
        val anchor = members.field("menuAnchor").get(binding) as? View ?: return
        anchor.visibility = View.VISIBLE
    }

    private fun inflateRow(context: Context): TextView? {
        val layoutId = context.resources.getIdentifier(layoutName, "layout", profile.packageName)
        if (layoutId == 0) {
            ModernXposedRuntime.log("Lyrics menu row layout is missing: $layoutName")
            return null
        }
        return runCatching {
            LayoutInflater.from(context).inflate(layoutId, null, false) as? TextView
        }.onFailure { error ->
            ModernXposedRuntime.log("Lyrics menu row inflation failed", error)
        }.getOrNull()
    }

    private fun applyEndIcon(row: TextView) {
        if (endIconName.isBlank()) return
        val iconId = row.resources.getIdentifier(endIconName, "drawable", profile.packageName)
        if (iconId == 0) {
            ModernXposedRuntime.log("Lyrics menu end icon is missing: $endIconName")
            return
        }
        val icon = runCatching { row.context.getDrawable(iconId) }
            .onFailure { error -> ModernXposedRuntime.log("Lyrics menu end icon failed", error) }
            .getOrNull() ?: return
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
    }

    private fun applySeparator(row: TextView) {
        if (separatorName.isBlank()) return
        val id = row.resources.getIdentifier(separatorName, "drawable", profile.packageName)
        if (id == 0) {
            ModernXposedRuntime.log("Lyrics menu separator is missing: $separatorName")
            return
        }
        runCatching {
            val divider = row.context.getDrawable(id) ?: return
            val layers = row.foreground?.let { arrayOf(it, divider) } ?: arrayOf(divider)
            // A plain MATCH_PARENT child takes the full AT_MOST width during
            // native popup measurement. Decoration must not be a layout child.
            row.foreground = LayerDrawable(layers).apply {
                setLayerGravity(layers.lastIndex, Gravity.TOP or Gravity.FILL_HORIZONTAL)
                setLayerHeight(layers.lastIndex, divider.intrinsicHeight.coerceAtLeast(1))
            }
        }.onFailure { error ->
            ModernXposedRuntime.log("Lyrics menu separator drawing failed", error)
        }
    }

    private companion object {
        const val MIN_TOUCH_DP = 48
    }
}
