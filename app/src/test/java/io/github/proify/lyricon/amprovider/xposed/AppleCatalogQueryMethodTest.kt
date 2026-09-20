package io.github.proify.lyricon.amprovider.xposed

import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("UNUSED_PARAMETER")
class AppleCatalogQueryMethodTest {
    open class Author {
        fun B(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): Any = "B"
        fun x(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): Any = "x"
    }

    class Inherited : Author()

    class Unknown {
        fun x(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): Any = "x"
    }

    class WrongParameters {
        fun B(path: String, parameters: Map<String, String>, continuation: Any): Any = "B"
    }

    class WrongReturn {
        fun B(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): String = "B"
    }

    class WrongStatic {
        companion object {
            @JvmStatic
            fun B(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): Any = "B"
        }
    }

    class Ambiguous {
        fun B(path: String, parameters: Map<String, String>, continuation: Continuation<Any>): Any = "map"
        fun B(path: String, parameters: HashMap<String, String>, continuation: Continuation<Any>): Any = "hash"
    }

    @Test
    fun `preferred mapping wins and superclass lookup is retained`() {
        assertEquals("B", AppleCatalogQueryMethod.resolve(Author::class.java, "B").name)
        assertEquals(
            Author::class.java,
            AppleCatalogQueryMethod.resolve(Inherited::class.java, "B").declaringClass,
        )
    }

    @Test
    fun `verified 652 variant uses x when B has the wrong signature`() {
        val method = AppleCatalogQueryMethod.resolve(s8.F::class.java, "B")
        assertEquals("x", method.name)
        assertEquals("catalog", method.invoke(s8.F(), "path", emptyMap<String, String>(), null))
    }

    @Test
    fun `verified 653 rename uses v when the name B moved to another method`() {
        val method = AppleCatalogQueryMethod.resolve(u8.E::class.java, "B")
        assertEquals("v", method.name)
        assertEquals(
            "catalog-653",
            method.invoke(u8.E(), "path", emptyMap<String, String>(), null),
        )
    }

    @Test
    fun `unknown classes names invalid signatures and ambiguity fail closed`() {
        listOf(Unknown::class.java, WrongParameters::class.java, WrongReturn::class.java,
            WrongStatic::class.java, Ambiguous::class.java).forEach { clazz ->
            assertThrows(NoSuchMethodException::class.java) {
                AppleCatalogQueryMethod.resolve(clazz, "B")
            }
        }
        assertThrows(NoSuchMethodException::class.java) {
            AppleCatalogQueryMethod.resolve(s8.F::class.java, "unknown")
        }
        assertThrows(NoSuchMethodException::class.java) {
            AppleCatalogQueryMethod.resolve(u8.E::class.java, "unknown")
        }
    }
}
