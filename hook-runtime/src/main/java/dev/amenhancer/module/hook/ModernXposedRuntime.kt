package dev.amenhancer.module.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

abstract class ModernMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
    ) {
        val extras = mutableMapOf<String, Any?>()
        private var returnEarly = false
        private var resultValue: Any? = null

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwable = null
                returnEarly = true
            }

        var throwable: Throwable? = null
            set(value) {
                field = value
                if (value != null) returnEarly = true
            }

        internal fun shouldReturnEarly(): Boolean = returnEarly
        internal fun setInvocationResult(value: Any?) {
            resultValue = value
        }
    }
}

object ModernXposedRuntime {
    private const val TAG = "AppleMusicEnhancer"

    @Volatile
    private var module: XposedModule? = null

    fun attach(module: XposedModule) {
        CachedMethods.clear()
        this.module = module
    }

    fun activeModule(): XposedModule? = module

    fun hookMethod(executable: Executable, callback: ModernMethodHook, scope: HookRegistrationScope? = null, recordBuiltin: Boolean = true): Boolean {
        val activeModule = module ?: error("Modern Xposed runtime is not attached")
        val guard = HookCallbackGuard.of(callback)
        activeModule.hook(executable).intercept { chain ->
            if (scope != null && !scope.isActive) return@intercept chain.proceed()
            val param = ModernMethodHook.MethodHookParam(
                method = executable,
                thisObject = chain.thisObject,
                args = chain.args.toTypedArray(),
            )
            // Fail open: a module callback must never throw into the host. The host call proceeds untouched.
            if (guard.enabled) {
                try { callback.beforeHookedMethod(param) } catch (error: Throwable) {
                    guard.failed("before", executable, error)
                }
            }
            if (!param.shouldReturnEarly()) {
                try {
                    param.setInvocationResult(chain.proceed(param.args))
                } catch (error: Throwable) {
                    param.throwable = error
                }
            }
            if (guard.enabled) {
                try { callback.afterHookedMethod(param) } catch (error: Throwable) {
                    guard.failed("after", executable, error)
                }
            }
            param.throwable?.let { throw it }
            param.result
        }
        if (recordBuiltin) {
            val record = HookRegistrations.register("AM++: ${callback.javaClass.name}", true, target = executable,
                retained = { scope == null || !scope.isClosed })
            scope?.onClose { record.close() }
        }
        return true
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: ModernMethodHook, scope: HookRegistrationScope? = null): Set<Executable> =
        type.declaredMethods
            .filter { it.name == name }
            .onEach { hookMethod(it, callback, scope) }
            .toSet()

    /** Compatibility entry point; new native adapters resolve explicit member contracts. */
    fun callMethod(receiver: Any, name: String, vararg args: Any?): Any? {
        return CachedMethods.callLegacy(receiver, name, *args)
    }

    fun log(message: String, error: Throwable? = null) {
        val activeModule = module
        if (activeModule != null) {
            if (error == null) {
                activeModule.log(Log.INFO, TAG, message)
            } else {
                activeModule.log(Log.ERROR, TAG, message, error)
            }
        } else if (error == null) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
    }

}

/** Counts callback failures; a callback that keeps throwing is switched off so the host runs native. */
internal class HookCallbackGuard private constructor() {
    @Volatile var enabled = true
        private set
    private var failures = 0

    fun failed(phase: String, executable: Executable, error: Throwable) {
        val count = synchronized(this) { ++failures }
        val disable = count >= MAX_FAILURES
        if (disable) enabled = false
        if (count <= MAX_LOGGED) {
            GlassDiagLog.e("hook callback threw in $phase of ${executable.declaringClass.name}#${executable.name}; " +
                "failing open (failure $count/$MAX_FAILURES${if (disable) ", callback disabled" else ""})", error)
        }
    }

    companion object {
        private const val MAX_FAILURES = 5
        private const val MAX_LOGGED = 8
        private val guards = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ModernMethodHook, HookCallbackGuard>())
        fun of(callback: ModernMethodHook): HookCallbackGuard = guards.getOrPut(callback) { HookCallbackGuard() }
    }
}
