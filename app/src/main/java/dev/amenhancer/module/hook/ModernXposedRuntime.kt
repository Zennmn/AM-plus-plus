package dev.amenhancer.module.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method

internal abstract class ModernMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
    ) {
        internal val extras = mutableMapOf<String, Any?>()
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

internal object ModernXposedRuntime {
    private const val TAG = "AppleMusicEnhancer"

    @Volatile
    private var module: XposedModule? = null

    fun attach(module: XposedModule) {
        this.module = module
    }

    fun activeModule(): XposedModule? = module

    fun hookMethod(executable: Executable, callback: ModernMethodHook): Boolean {
        val activeModule = module ?: error("Modern Xposed runtime is not attached")
        activeModule.hook(executable).intercept { chain ->
            val param = ModernMethodHook.MethodHookParam(
                method = executable,
                thisObject = chain.thisObject,
                args = chain.args.toTypedArray(),
            )
            callback.beforeHookedMethod(param)
            if (!param.shouldReturnEarly()) {
                try {
                    param.setInvocationResult(chain.proceed(param.args))
                } catch (error: Throwable) {
                    param.throwable = error
                }
            }
            callback.afterHookedMethod(param)
            param.throwable?.let { throw it }
            param.result
        }
        return true
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: ModernMethodHook): Set<Executable> =
        type.declaredMethods
            .filter { it.name == name }
            .onEach { hookMethod(it, callback) }
            .toSet()

    fun callMethod(receiver: Any, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(receiver.javaClass, name, args)
            ?: throw NoSuchMethodException("${receiver.javaClass.name}#$name(${args.size})")
        method.isAccessible = true
        return method.invoke(receiver, *args)
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

    private fun findCompatibleMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { index -> compatible(method.parameterTypes[index], args[index]) }
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun compatible(parameter: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameter.isPrimitive
        val boxed = when (parameter) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> parameter
        }
        return boxed.isInstance(value)
    }
}
