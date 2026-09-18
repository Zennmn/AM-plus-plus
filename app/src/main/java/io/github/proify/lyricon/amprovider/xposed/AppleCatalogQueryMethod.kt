package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object AppleCatalogQueryMethod {
    fun resolve(clazz: Class<*>, preferredName: String): Method {
        val preferred = find(clazz, preferredName)
        // Another 6.5.2 (1586) R8 variant maps the same catalog query to x.
        val method = preferred ?: if (clazz.name == "s8.F" && preferredName == "B") {
            find(clazz, "x")
        } else {
            null
        }
        return method?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException(
                "${clazz.name}#$preferredName(String,Map,Continuation)",
            )
    }

    private fun find(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val candidates = current.declaredMethods.filter { method ->
                method.name == name &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Any::class.java &&
                    method.parameterTypes.let { types ->
                        types.size == 3 &&
                            types[0] == String::class.java &&
                            Map::class.java.isAssignableFrom(types[1]) &&
                            types[2].isInterface &&
                            types[2].name == "kotlin.coroutines.Continuation"
                    }
            }
            if (candidates.size > 1) {
                throw NoSuchMethodException("Ambiguous catalog query: ${current.name}#$name")
            }
            candidates.singleOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }
}
