package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object AppleCatalogQueryMethod {
    /**
     * Verified renames of the direct catalog query, keyed by owner class and preferred name.
     *
     * R8 reuses short names between builds, so the same name can describe a different method on
     * another host. Every entry below was established from bytecode rather than from the name,
     * and is consulted only after the preferred name fails the signature check:
     *
     *  - The alternate 6.5.2 (1586) build keeps the query body under [x] while its own [B] takes
     *    a different signature.
     *  - 6.5.3 (1599) renames s8.F to u8.E and moves the query, with its exact 353-instruction
     *    body, onto [v]; u8.E#B is now a method that takes the continuation implementation first.
     */
    private val VERIFIED_RENAMES: Map<String, Map<String, String>> = mapOf(
        "s8.F" to mapOf("B" to "x"),
        "u8.E" to mapOf("B" to "v"),
    )

    fun resolve(clazz: Class<*>, preferredName: String): Method {
        find(clazz, preferredName)?.let { method ->
            return method.also { it.isAccessible = true }
        }
        val method = VERIFIED_RENAMES[clazz.name]
            ?.get(preferredName)
            ?.let { name -> find(clazz, name) }
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
