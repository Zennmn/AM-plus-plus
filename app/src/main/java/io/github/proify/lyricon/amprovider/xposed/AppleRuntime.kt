package io.github.proify.lyricon.amprovider.xposed

import dev.amenhancer.module.hook.ModernXposedRuntime
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object ProviderLogger {
    fun debug(message: String) = ModernXposedRuntime.log("[HLE-metadata] $message")
    fun diagnostic(message: String) = ModernXposedRuntime.log("[HLE-metadata] [debug] $message")
    fun info(message: String) = ModernXposedRuntime.log("[HLE-metadata] $message")
    fun error(message: String, throwable: Throwable? = null) =
        ModernXposedRuntime.log("[HLE-metadata] $message", throwable)
}

internal object AppleReflection {
    private val methodsByClass =
        ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()
    private val fieldsByClass = ConcurrentHashMap<Class<*>, Map<String, Field>>()

    fun findMethod(
        clazz: Class<*>,
        name: String,
        parameterCount: Int? = null,
        parameterTypes: List<Class<*>>? = null,
    ): Method {
        val method = methodsNamed(clazz, name).firstOrNull { candidate ->
            (parameterCount == null || candidate.parameterCount == parameterCount) &&
                (parameterTypes == null || candidate.parameterTypes.contentEquals(parameterTypes.toTypedArray()))
        } ?: throw NoSuchMethodException("${clazz.name}#$name/${parameterCount ?: parameterTypes?.size ?: "*"}")
        method.isAccessible = true
        return method
    }

    fun call(instance: Any?, name: String, vararg args: Any?): Any? {
        if (instance == null) return null
        val method = methodsNamed(instance.javaClass, name).firstOrNull {
            parametersMatch(it.parameterTypes, args)
        } ?: throw NoSuchMethodException("${instance.javaClass.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(instance, *args)
    }

    fun callStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val method = methodsNamed(clazz, name).firstOrNull {
            Modifier.isStatic(it.modifiers) && parametersMatch(it.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = clazz.declaredConstructors.firstOrNull {
            parametersMatch(it.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}<init>/${args.size}")
        @Suppress("UNCHECKED_CAST")
        return (constructor as Constructor<Any>).apply { isAccessible = true }.newInstance(*args)
    }

    fun field(instance: Any, name: String): Any? = findField(instance.javaClass, name).let {
        it.isAccessible = true
        it.get(instance)
    }

    fun setField(instance: Any, name: String, value: Any?) {
        findField(instance.javaClass, name).let {
            it.isAccessible = true
            it.set(instance, value)
        }
    }

    fun intField(instance: Any, name: String): Int = findField(instance.javaClass, name).let {
        it.isAccessible = true
        it.getInt(instance)
    }

    private fun findField(clazz: Class<*>, name: String): Field =
        fieldsByClass.computeIfAbsent(clazz, ::indexFields)[name]
            ?: throw NoSuchFieldException("${clazz.name}#$name")

    private fun methodsNamed(clazz: Class<*>, name: String): List<Method> =
        methodsByClass.computeIfAbsent(clazz, ::indexMethods)[name].orEmpty()

    private fun indexMethods(clazz: Class<*>): Map<String, List<Method>> {
        val result = LinkedHashMap<String, MutableList<Method>>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.forEach { result.getOrPut(it.name) { mutableListOf() }.add(it) }
            current = current.superclass
        }
        return result
    }

    private fun indexFields(clazz: Class<*>): Map<String, Field> {
        val result = LinkedHashMap<String, Field>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredFields.forEach { result.putIfAbsent(it.name, it) }
            current = current.superclass
        }
        return result
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val argument = args[index] ?: return@all !types[index].isPrimitive
            boxed(types[index]).isAssignableFrom(argument.javaClass)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        else -> type
    }
}
