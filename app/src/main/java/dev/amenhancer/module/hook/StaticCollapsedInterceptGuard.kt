package dev.amenhancer.module.hook

import android.view.View
import java.lang.reflect.Modifier

/**
 * Counteracts a landscape regression introduced by keeping the native flat
 * bottom-navigation holder: that holder feeds the static-collapsed behavior's
 * slide offset once the player sheet expands, and a non-zero offset makes the
 * behavior's onInterceptTouchEvent swallow every touch over the tabs frame.
 *
 * In the transformed landscape layout the tabs frame sits directly under the
 * left pane's lyrics/queue buttons, so the intercept collapses the player
 * instead of letting the buttons receive their taps. On the dual-pane tablet
 * the sheet already overlays the tabs frame, so the intercept is pure
 * misrouting: force it off there. The touch then flows naturally — the pane
 * buttons consume their taps, while untouched areas still bubble back to the
 * behavior's onTouchEvent to preserve the native collapse behaviour.
 */
internal object StaticCollapsedInterceptGuard {
    fun install(classLoader: ClassLoader?): Boolean {
        classLoader ?: return false
        return runCatching {
            val behaviorClass = Class.forName(
                "com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior",
                false,
                classLoader,
            )
            val intercept = behaviorClass.declaredMethods.firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.name == "h" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.map { it.name } == listOf(
                        "androidx.coordinatorlayout.widget.CoordinatorLayout",
                        "android.view.View",
                        "android.view.MotionEvent",
                    )
            } ?: return@runCatching false
            ModernXposedRuntime.hookMethod(intercept, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val coordinator = param.args[0] as? View ?: return
                    if (!TabletModeQualifier.isEligible(coordinator.context)) return
                    param.result = false
                }
            })
            true
        }.onFailure {
            ModernXposedRuntime.log("static-collapsed intercept guard failed", it)
        }.getOrDefault(false)
    }
}
