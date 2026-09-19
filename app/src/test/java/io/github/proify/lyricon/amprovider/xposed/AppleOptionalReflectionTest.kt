package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class AppleOptionalReflectionTest {
    private open class Base {
        private fun inherited(): String = "base"
    }

    private class Entity : Base() {
        var value = "first"
        var attempts = 0
        fun title(): String = value
        fun overloaded(value: String): String = "string:$value"
        fun overloaded(value: Int): String = "int:$value"
        fun nullable(value: String?): String = value ?: "null"
        fun retry(): String {
            if (attempts++ == 0) error("transient")
            return "recovered"
        }
    }

    @Test fun missingOptionalMembersDoNotThrowButStrictCallsStillDo() {
        val entity = Entity()
        repeat(1000) {
            assertNull(AppleReflection.callIfPresent(entity, "missing"))
            assertNull(AppleReflection.findMethodOrNull(Entity::class.java, "missing"))
        }
        assertThrows(NoSuchMethodException::class.java) { AppleReflection.call(entity, "missing") }
        assertThrows(NoSuchMethodException::class.java) {
            AppleReflection.findMethod(Entity::class.java, "missing")
        }
    }

    @Test fun indexedGettersReadCurrentValuesAndInheritedMembers() {
        val entity = Entity()
        assertEquals("first", AppleReflection.callIfPresent(entity, "title"))
        entity.value = "updated"
        assertEquals("updated", AppleReflection.callIfPresent(entity, "title"))
        assertEquals("base", AppleReflection.callIfPresent(entity, "inherited"))
        assertEquals("first", AppleReflection.callIfPresent(Entity(), "title"))
    }

    @Test fun optionalCallsPreserveOverloadAndNullMatching() {
        val entity = Entity()
        assertEquals("string:x", AppleReflection.callIfPresent(entity, "overloaded", "x"))
        assertEquals("int:7", AppleReflection.callIfPresent(entity, "overloaded", 7))
        assertNull(AppleReflection.callIfPresent(entity, "overloaded", true))
        assertEquals("null", AppleReflection.callIfPresent(entity, "nullable", null))
        assertNull(AppleReflection.callIfPresent(null, "title"))
        assertNull(AppleReflection.findMethodOrNull(Entity::class.java, "title", parameterCount = 1))
        assertNotNull(AppleReflection.findMethodOrNull(
            Entity::class.java, "overloaded", parameterTypes = listOf(Int::class.javaPrimitiveType!!),
        ))
    }

    @Test fun invocationFailuresRemainVisibleAndCanRecover() {
        val entity = Entity()
        val error = assertThrows(InvocationTargetException::class.java) {
            AppleReflection.callIfPresent(entity, "retry")
        }
        assertEquals("transient", error.cause?.message)
        assertEquals("recovered", AppleReflection.callIfPresent(entity, "retry"))
    }

    @Test fun optionalArtistGettersKeepNormalizationAndDeduplication() {
        val attributes = object {
            fun first() = " 123 "
            fun duplicate() = "123"
            fun zero() = "0"
            fun invalid() = "not-an-id"
        }
        assertEquals(listOf("123"), mediaApiAttributeArtistIds(
            attributes, listOf("missing", "first", "duplicate", "zero", "invalid"),
        ))
    }
}
