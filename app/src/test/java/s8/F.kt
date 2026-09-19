package s8

import kotlin.coroutines.Continuation

@Suppress("UNUSED_PARAMETER")
class F {
    // Signatures from the alternate Apple Music 6.5.2 (1586) R8 variant.
    fun B(parameters: HashMap<*, *>, continuation: Continuation<*>?): Any? = null

    fun x(path: String, parameters: Map<*, *>, continuation: Continuation<*>?): Any = "catalog"

    companion object {
        @JvmStatic
        fun c0(params: Map<*, *>): LinkedHashMap<Any?, Any?> = LinkedHashMap(params)
    }
}
