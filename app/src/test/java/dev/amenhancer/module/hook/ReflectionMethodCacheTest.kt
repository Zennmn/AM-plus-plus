package dev.amenhancer.module.hook

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReflectionMethodCacheTest {

    @Test
    fun `same class name and signature reuse the cached method`() {
        val first = ReflectionMethodCache.find(
            owner = ReflectionFixture::class.java,
            name = "getName",
            returnType = String::class.java,
        )
        val second = ReflectionMethodCache.find(
            owner = ReflectionFixture::class.java,
            name = "getName",
            returnType = String::class.java,
        )

        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `signature keeps a setter distinct from a getter`() {
        val setter = ReflectionMethodCache.find(
            owner = ReflectionFixture::class.java,
            name = "setName",
            parameterTypes = listOf(String::class.java),
        )
        val missing = ReflectionMethodCache.find(
            owner = ReflectionFixture::class.java,
            name = "setName",
        )

        assertNotNull(setter)
        assertNull(missing)
    }

    @Test
    fun `missing methods fail open`() {
        assertNull(
            ReflectionMethodCache.find(
                owner = ReflectionFixture::class.java,
                name = "doesNotExist",
            ),
        )
    }

    @Test
    fun `title constructor lookup is reused and fail open`() {
        val first = ReflectionConstructorCache.find(
            ReflectionFixture::class.java,
            listOf(String::class.java),
        )
        val second = ReflectionConstructorCache.find(
            ReflectionFixture::class.java,
            listOf(String::class.java),
        )
        assertSame(first, second)
        assertNull(
            ReflectionConstructorCache.find(
                ReflectionFixture::class.java,
                listOf(Int::class.java),
            ),
        )
    }
}

private class ReflectionFixture(private val value: String = "fixture") {
    @Suppress("unused")
    fun getName(): String = "fixture"

    @Suppress("unused")
    fun setName(value: String) = Unit
}
