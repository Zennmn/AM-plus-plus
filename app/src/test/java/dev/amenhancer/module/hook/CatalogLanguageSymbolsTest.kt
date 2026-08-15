package dev.amenhancer.module.hook

import android.content.Context
import com.apple.android.music.commerce.jsinterface.ITunes
import aa.d
import ma.c
import com.apple.android.music.icloud.api.ICloudAcceptLanguageFixture
import com.apple.android.music.mediaapi.LanguageParamsFixture
import com.apple.android.music.storeapi.HeaderMapFixture
import com.apple.android.music.storeapi.modelprivate.Request
import com.apple.android.music.storeapi.stores.StorefrontLanguageFixture
import s8.F
import kotlin.coroutines.Continuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural resolution of the Catalog language symbols.  Each symbol must
 * resolve independently: a missing or ambiguous seam never blocks the others
 * (the target installs the hooks that did resolve and reports the rest).
 */
class CatalogLanguageSymbolsTest {
    private val storeFrontArrayName = "J5.a"
    private val storeFrontArray2Name = "T8.a"
    private val headersMapName = "com.apple.android.music.storeapi.HeaderMapFixture"
    private val setParamName = "com.apple.android.music.storeapi.modelprivate.Request\$Builder"
    private val storefrontLanguageName =
        "com.apple.android.music.storeapi.stores.StorefrontLanguageFixture"
    private val mediaApiMapName = "com.apple.android.music.mediaapi.LanguageParamsFixture"
    private val iCloudName = "com.apple.android.music.icloud.api.ICloudAcceptLanguageFixture"
    private val mediaApiRepositoryName =
        "com.apple.android.music.mediaapi.repository.MediaApiRepository"
    private val mediaApiRepositoryImplName =
        "com.apple.android.music.mediaapi.repository.MediaApiRepositoryImpl"

    @Test
    fun `catalog repository resolves concrete implementation instead of interface`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(mediaApiRepositoryName, mediaApiRepositoryImplName),
            classes = mapOf(
                mediaApiRepositoryName to MediaApiRepositoryInterfaceFixture::class.java,
                mediaApiRepositoryImplName to MediaApiRepositoryImplFixture::class.java,
            ),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.MediaApiRepositoryGetEntitiesWithIdsMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STABLE_NAME, (resolution as TargetResolution.Found).match)
        assertEquals(
            MediaApiRepositoryImplFixture::class.java,
            resolution.value.declaringClass,
        )
        assertEquals("getEntitiesWithIds", resolution.value.name)
        assertTrue(!java.lang.reflect.Modifier.isAbstract(resolution.value.modifiers))
    }

    @Test
    fun `catalog repository fails open when only abstract interface is present`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(mediaApiRepositoryName),
            classes = mapOf(
                mediaApiRepositoryName to MediaApiRepositoryInterfaceFixture::class.java,
            ),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.MediaApiRepositoryGetEntitiesWithIdsMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `catalog invocation resolves the stable repository interface method`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(mediaApiRepositoryName),
            classes = mapOf(mediaApiRepositoryName to MediaApiRepositoryInterfaceFixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.MediaApiRepositoryGetEntitiesWithIdsInvocationMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(
            MediaApiRepositoryInterfaceFixture::class.java,
            (resolution as TargetResolution.Found).value.declaringClass,
        )
        assertTrue(resolution.value.declaringClass.isInterface)
    }

    @Test
    fun `language array generator resolves by structural signature`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(storeFrontArrayName),
            classes = mapOf(storeFrontArrayName to LanguageArrayFixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("b", (resolution as TargetResolution.Found).value.name)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
    }

    @Test
    fun `651 profile resolves the verified language array without scanning dex`() {
        val source = CatalogLanguageFakeClassSource(
            classes = mapOf(storeFrontArrayName to LanguageArrayFixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild("com.apple.android.music", "6.5.1", 1583L),
            source,
        ).resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals("b", resolution.value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `650 profile resolves the verified language array without scanning dex`() {
        val source = CatalogLanguageFakeClassSource(
            classes = mapOf(storeFrontArrayName to LanguageArrayFixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild("com.apple.android.music", "6.5.0", 1580L),
            source,
        ).resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `language array generator reports ambiguity independently`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(storeFrontArrayName, storeFrontArray2Name, headersMapName),
            classes = mapOf(
                storeFrontArrayName to LanguageArrayFixture::class.java,
                storeFrontArray2Name to SecondLanguageArrayFixture::class.java,
                headersMapName to HeaderMapFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        assertTrue(
            resolver.resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)
                is TargetResolution.Ambiguous,
        )
        assertTrue(
            resolver.resolve(AppleMusicSymbols.StoreApiHeaderMapMethod)
                is TargetResolution.Found,
        )
    }

    @Test
    fun `store lookup setParam resolves from the Request Builder`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(setParamName),
            classes = mapOf(setParamName to Request.Builder::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.StoreLookupSetParamMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("setParam", (resolution as TargetResolution.Found).value.name)
    }

    @Test
    fun `store api header map and media api language map resolve in their packages`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(headersMapName, mediaApiMapName),
            classes = mapOf(
                headersMapName to HeaderMapFixture::class.java,
                mediaApiMapName to LanguageParamsFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val headerMap = resolver.resolve(AppleMusicSymbols.StoreApiHeaderMapMethod)
        val languageMap = resolver.resolve(AppleMusicSymbols.MediaApiLanguageParamMethod)

        assertTrue(headerMap is TargetResolution.Found)
        assertEquals("headerMap", (headerMap as TargetResolution.Found).value.name)
        assertTrue(languageMap is TargetResolution.Found)
        assertEquals("languageParams", (languageMap as TargetResolution.Found).value.name)
    }

    @Test
    fun `amtool media api language map resolves the pinned s8 F helper`() {
        val name = "s8.F"
        val source = CatalogLanguageFakeClassSource(
            names = listOf(name),
            classes = mapOf(name to F::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)
            .resolve(AppleMusicSymbols.MediaApiLanguageParamMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STABLE_NAME, (resolution as TargetResolution.Found).match)
        assertEquals("c0", resolution.value.name)
        assertTrue(java.lang.reflect.Modifier.isStatic(resolution.value.modifiers))
        assertEquals(Map::class.java, resolution.value.parameterTypes.single())
        assertTrue(java.util.LinkedHashMap::class.java.isAssignableFrom(resolution.value.returnType))
    }

    @Test
    fun `itunes private header map resolves on the exact commerce seam`() {
        val name = "com.apple.android.music.commerce.jsinterface.ITunes"
        val source = CatalogLanguageFakeClassSource(
            names = listOf(name),
            classes = mapOf(name to ITunes::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)
            .resolve(AppleMusicSymbols.ITunesGetHeadersMapMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("getHeadersMap", (resolution as TargetResolution.Found).value.name)
        assertEquals(0, resolution.value.parameterCount)
        assertTrue(Map::class.java.isAssignableFrom(resolution.value.returnType))
    }

    @Test
    fun `amtool locale header map resolves the pinned ma c helper`() {
        val name = "ma.c"
        val source = CatalogLanguageFakeClassSource(
            names = listOf(name),
            classes = mapOf(name to c::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)
            .resolve(AppleMusicSymbols.LocaleHeaderMapMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("a", (resolution as TargetResolution.Found).value.name)
        assertEquals(d::class.java, resolution.value.parameterTypes.single())
        assertTrue(Map::class.java.isAssignableFrom(resolution.value.returnType))
    }

    @Test
    fun `icloud helper resolves only on the icloud surface`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(iCloudName),
            classes = mapOf(iCloudName to ICloudAcceptLanguageFixture::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val iCloud = resolver.resolve(AppleMusicSymbols.ICloudAcceptLanguageHelperMethod)
        assertTrue(iCloud is TargetResolution.Found)
        assertEquals(
            "acceptLanguage",
            (iCloud as TargetResolution.Found).value.name,
        )
    }

    @Test
    fun `storefront language method resolves by package scan without a fixed class name`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(storefrontLanguageName),
            classes = mapOf(storefrontLanguageName to StorefrontLanguageFixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.ConfigurationStoreStoreFrontLanguageMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(
            "storeFrontLanguageOrDefault",
            (resolution as TargetResolution.Found).value.name,
        )
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
        assertEquals(0, resolution.value.parameterTypes.size)
        assertEquals(String::class.java, resolution.value.returnType)
    }

    @Test
    fun `store api headers set resolves with the relaxed AMTool contract`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf("com.apple.android.music.storeapi.modelprivate.Headers"),
            classes = mapOf(
                "com.apple.android.music.storeapi.modelprivate.Headers" to
                    HeadersSetFixture::class.java,
            ),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.StoreApiHeadersSetMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("set", (resolution as TargetResolution.Found).value.name)
        assertEquals(2, (resolution as TargetResolution.Found).value.parameterTypes.size)
        assertEquals(String::class.java, resolution.value.parameterTypes[0])
    }

    @Test
    fun `storefront language method is missing outside the obfuscated owner`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(headersMapName),
            classes = mapOf(
                headersMapName to HeaderMapFixture::class.java,
            ),
        )
        val resolution = IndexedTargetSymbolResolver(
            TargetBuild.UNKNOWN,
            source,
        ).resolve(AppleMusicSymbols.ConfigurationStoreStoreFrontLanguageMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `missing symbols degrade independently without affecting the rest`() {
        val source = CatalogLanguageFakeClassSource(
            names = listOf(storeFrontArrayName, headersMapName),
            classes = mapOf(
                storeFrontArrayName to LanguageArrayFixture::class.java,
                headersMapName to HeaderMapFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        assertTrue(
            resolver.resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)
                is TargetResolution.Found,
        )
        assertTrue(
            resolver.resolve(AppleMusicSymbols.ICloudAcceptLanguageHelperMethod)
                is TargetResolution.Missing,
        )
        assertTrue(
            resolver.resolve(AppleMusicSymbols.MediaApiLanguageParamMethod)
                is TargetResolution.Missing,
        )
        assertTrue(
            resolver.resolve(AppleMusicSymbols.StoreLookupSetParamMethod)
                is TargetResolution.Missing,
        )
    }
}
private class LanguageArrayFixture {
    companion object {
        @JvmStatic
        fun b(context: Context): Array<String> = arrayOf("zh", "en")
    }
}

/**
 * The real Headers.set method accepts Object; the resolver still requires a
 * String-compatible slot before the hook is allowed to replace its value.
 */
private class HeadersSetFixture {
    fun set(key: String, value: Any): Any = value
}

private class SecondLanguageArrayFixture {
    companion object {
        @JvmStatic
        fun b(context: Context): Array<String> = arrayOf("zh", "en")
    }
}

private interface MediaApiRepositoryInterfaceFixture {
    fun getEntitiesWithIds(
        type: String,
        ids: List<String>,
        queryParams: Map<String, String>,
        continuation: Continuation<Any?>,
    ): Any?
}

private class MediaApiRepositoryImplFixture : MediaApiRepositoryInterfaceFixture {
    override fun getEntitiesWithIds(
        type: String,
        ids: List<String>,
        queryParams: Map<String, String>,
        continuation: Continuation<Any?>,
    ): Any? = null
}

private class CatalogLanguageFakeClassSource(
    private val names: List<String> = emptyList(),
    private val classes: Map<String, Class<*>> = emptyMap(),
) : TargetClassSource {
    var classNameReads: Int = 0
        private set

    override fun classNames(): List<String> {
        classNameReads++
        return names
    }

    override fun loadClass(name: String): Class<*>? = classes[name]
}
