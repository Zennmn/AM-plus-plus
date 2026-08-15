package dev.amenhancer.module.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local cache for the small reflective method calls used by display
 * hooks.  A missing or inaccessible method is cached as a negative result so
 * a degraded host shape does not repeatedly scan [Class.methods] on a hot
 * callback path.
 *
 * The key includes the receiver class, method name, parameter signature and
 * optional return type.  The cache deliberately fails open: lookup failures
 * and accessibility failures resolve to null and callers can leave the host
 * value untouched.
 */
internal object ReflectionMethodCache {
    private data class Entry(val method: Method?)

    private class Key(
        private val owner: Class<*>,
        private val name: String,
        private val parameterTypes: List<Class<*>>,
        private val returnType: Class<*>?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return owner == other.owner &&
                name == other.name &&
                parameterTypes == other.parameterTypes &&
                returnType == other.returnType
        }

        override fun hashCode(): Int {
            var result = owner.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + parameterTypes.hashCode()
            result = 31 * result + (returnType?.hashCode() ?: 0)
            return result
        }
    }

    private val methods = ConcurrentHashMap<Key, Entry>()

    /**
     * Finds a public method using the same shape that the old hot-path helper
     * used.  [parameterTypes] is a method signature, not an invocation list.
     */
    fun find(
        owner: Class<*>,
        name: String,
        parameterTypes: List<Class<*>> = emptyList(),
        returnType: Class<*>? = null,
    ): Method? {
        val key = Key(owner, name, parameterTypes.toList(), returnType)
        return methods.computeIfAbsent(key) {
            Entry(resolve(owner, name, parameterTypes, returnType))
        }.method
    }

    private fun resolve(
        owner: Class<*>,
        name: String,
        parameterTypes: List<Class<*>>,
        returnType: Class<*>?,
    ): Method? = runCatching {
        owner.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterTypes.size == parameterTypes.size &&
                candidate.parameterTypes.indices.all { index ->
                    candidate.parameterTypes[index] == parameterTypes[index]
                } &&
                (returnType == null || candidate.returnType == returnType)
        }?.apply {
            isAccessible = true
        }
    }.getOrNull()
}

/** Process-local cache for immutable Title(String) constructor lookups. */
internal object ReflectionConstructorCache {
    private data class Entry(val constructor: Constructor<*>?)

    private class Key(
        private val owner: Class<*>,
        private val parameterTypes: List<Class<*>>,
    ) {
        override fun equals(other: Any?): Boolean = other is Key &&
            owner == other.owner && parameterTypes == other.parameterTypes

        override fun hashCode(): Int = 31 * owner.hashCode() + parameterTypes.hashCode()
    }

    private val constructors = ConcurrentHashMap<Key, Entry>()

    fun find(owner: Class<*>, parameterTypes: List<Class<*>>): Constructor<*>? {
        val key = Key(owner, parameterTypes.toList())
        return constructors.computeIfAbsent(key) {
            Entry(runCatching {
                owner.declaredConstructors.firstOrNull { constructor ->
                    constructor.parameterTypes.contentEquals(parameterTypes.toTypedArray())
                }?.apply { isAccessible = true }
            }.getOrNull())
        }.constructor
    }
}
