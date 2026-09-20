package dev.amenhancer.module.hook

import android.content.Context
import dev.amenhancer.module.ModuleConstants
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music 6.5.3 (1599) adaptation coverage: the new exact profile is selected for its own
 * tuple only, the re-derived owner names stay pinned, and the deliberately unresolved symbol is
 * still resolved through the reviewed fallback instead of a guessed class.
 */
class AppleMusic653ProfileTest {
    private val build653 = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.3", 1599L)
    private val version653 = AppleMusicVersion("6.5.3", 1599L)

    @Test
    fun `bootstrap accepts only the exact 6_5_3 tuple`() {
        val bootstrap = EmbeddedBootstrap()
        assertTrue(bootstrap.supports(build653))
        assertTrue(bootstrap.supports(TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L)))
        assertFalse(bootstrap.supports(TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.3", 1587L)))
        assertFalse(bootstrap.supports(TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.4", 1599L)))
    }

    @Test
    fun `hook profile selection is exact per version`() {
        assertEquals("am-6.5.3-1599", AppleMusicHookProfiles.profileFor(version653)?.id)
        assertEquals(
            "am-6.5.2-1586",
            AppleMusicHookProfiles.profileFor(AppleMusicVersion("6.5.2", 1586L))?.id,
        )
        assertEquals(
            "am-6.5.1-1583",
            AppleMusicHookProfiles.profileFor(AppleMusicVersion("6.5.1", 1583L))?.id,
        )
        // Profiles also match on the version name, so an unknown build must miss both fields;
        // the strict tuple gate lives in EmbeddedBootstrap and is asserted above.
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("6.5.4", 1600L)))
        assertEquals(
            "am-6.5.3-1599",
            AppleMusicHookProfiles.profileFor(AppleMusicVersion("6.5.3", 1600L))?.id,
        )
    }

    @Test
    fun `queue adapter seam follows the verified 6_5_3 rename`() {
        // Y8.a in 6.5.3 is an unrelated protobuf mode-mapping helper, so the fallback chain would
        // bind the wrong class on a name match alone. The adapter that replaces it keeps every
        // declared member and runtime member name of 1586's Y8.a.
        val submit = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT)
            .single()
        assertEquals("a9.a", submit.className)
        assertEquals("B", submit.methodName)
        assertEquals(1, submit.parameterCount)
        assertEquals(
            "l",
            submit.runtimeMemberNames[AppleMusicRuntimeMember.QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD],
        )
        assertEquals(
            "A",
            submit.runtimeMemberNames[AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD],
        )
        val bind = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND)
            .single()
        assertEquals("a9.a", bind.className)
        assertEquals("p", bind.methodName)
        assertEquals(2, bind.parameterCount)
        // 6.5.2 keeps its own adapter entry untouched.
        val submit652 = AppleMusicHookProfiles
            .exactTargets(AppleMusicVersion("6.5.2", 1586L), AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND)
        assertTrue(submit652.isEmpty() || submit652.all { it.className == "Y8.a" })
    }

    @Test
    fun `compose seams follow the verified 6_5_3 renames`() {
        val neverEqual = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY)
            .single()
        assertEquals("z0.p0", neverEqual.className)

        val observe = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE)
            .single()
        assertEquals("Dg.c", observe.className)
        assertEquals("l", observe.methodName)
        assertEquals(2, observe.parameterCount)
        assertEquals("z0.n0", observe.returnTypeName)
        assertEquals(true, observe.isStatic)
        assertEquals(
            "b",
            observe.runtimeMemberNames[AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_POLICY_FIELD],
        )
        assertEquals(
            "getValue",
            observe.runtimeMemberNames[AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD],
        )
        assertEquals(
            "setValue",
            observe.runtimeMemberNames[AppleMusicRuntimeMember.LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD],
        )
    }

    @Test
    fun `renamed 6_5_3 owners stay pinned in the profile`() {
        val artworkResolver = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.LISTEN_NOW_ARTWORK_RESOLVER)
            .single()
        assertEquals("com.apple.android.music.common.I", artworkResolver.className)
        assertEquals("t", artworkResolver.methodName)

        val libraryBuild = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.LIBRARY_EPOXY_BUILD)
            .single()
        assertEquals("buildModels", libraryBuild.methodName)
        assertEquals(
            listOf(
                "com.apple.android.music.library2.H",
                "java.util.List",
                "java.util.List",
                "com.apple.android.music.library2.a",
                "z6.b",
            ),
            libraryBuild.parameterTypeNames,
        )

        val listenNow = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.LISTEN_NOW_MODEL_BUILDER)
            .single()
        assertEquals(
            "com.apple.android.music.common.B0",
            listenNow.parameterTypeNames?.get(2),
        )
    }

    @Test
    fun `6_5_3 profile resolves the language array without scanning dex`() {
        val source = Profile653FakeClassSource(mapOf("K5.a" to LanguageArray653Fixture::class.java))
        val resolution = IndexedTargetSymbolResolver(build653, source)
            .resolve(AppleMusicSymbols.StoreFrontLanguageArrayMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals("b", resolution.value.name)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `unresolved 6_5_3 metadata hub falls back instead of being guessed`() {
        val source = Profile653FakeClassSource(emptyMap())
        val resolution = IndexedTargetSymbolResolver(build653, source)
            .resolve(AppleMusicSymbols.PlayerMetadataPublishMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `content http localization follows the u8 to w8 family move`() {
        // 6.5.3 moved the fourteen-class content-API family u8/a..u8/n to w8/a..w8/n, so the
        // request-localization interceptor is w8.a. Pinning it keeps the storefront/language
        // rewrite (and the removal of the module's own request token) on the request path; the
        // previous fallback could not resolve it and every scoped catalog lookup came back empty.
        val localization = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION)
            .single()
        assertEquals("w8.a", localization.className)
        assertEquals("a", localization.methodName)
        assertEquals(1, localization.parameterCount)
        assertEquals(listOf("Li.f"), localization.parameterTypeNames)
        assertEquals("Gi.D", localization.returnTypeName)
        listOf(
            AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD to "e",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD to "a",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD to "c",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD to "d",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD to "h",
            AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD to "d",
            AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD to "e",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_REQUEST_FIELD to "a",
            AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_HEADERS_FIELD to "f",
        ).forEach { (member, expected) ->
            assertEquals("runtime member $member", expected, localization.runtimeMemberNames[member])
        }
        // The MediaApi parameter seam stays on the class owning the storefront field and the
        // direct query, and 6.5.2 keeps its pre-move owners untouched.
        val mediaApi = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.MEDIA_API_LOCALIZATION)
            .single()
        assertEquals("u8.E", mediaApi.className)
        assertEquals("c0", mediaApi.methodName)
        // 6.5.0/6.5.1 declare u8.a directly and 6.5.2 inherits it through the candidate chain, so
        // the new entry must not replace those owners.
        val candidatesBefore653 = AppleMusicHookProfiles.candidates(
            AppleMusicVersion("6.5.2", 1586L),
            AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
        )
        assertTrue(
            candidatesBefore653.any { target ->
                target.className == "u8.a" && target.methodName == "a"
            },
        )
        assertTrue(
            AppleMusicHookProfiles
                .exactTargets(
                    AppleMusicVersion("6.5.2", 1586L),
                    AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
                )
                .none { it.className == "w8.a" },
        )
    }

    @Test
    fun `content http localization resolves the pinned 6_5_3 interceptor`() {
        // 1599's own u8.a is an unrelated MediaApi model class, so the 6.5.2 owner name cannot be
        // treated as a hit. The exact entry resolves from the profile without a dex scan.
        val lookup = mapOf("w8.a" to w8.a::class.java)
        val resolver = AppleMusicHookResolver(version653, classLookup = { name ->
            lookup[name] ?: throw ClassNotFoundException(name)
        })
        val resolved = resolver.resolveMethod(AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION)
        assertEquals("w8.a", resolved.target.className)
        assertEquals("a", resolved.method.name)
        assertEquals(1, resolved.method.parameterCount)
        assertEquals("Li.f", resolved.method.parameterTypes.single().name)
        assertEquals("Gi.D", resolved.method.returnType.name)
    }

    @Test
    fun `library compose view model getter follows the B0 to F0 rename`() {
        // 6.5.0 pinned B0 and 6.5.1 pinned A0 for the same getter; 1599 names it F0. The return
        // type is unchanged, and F0 is the only zero-argument getter of that type in the fragment.
        val getter = AppleMusicHookProfiles
            .exactTargets(version653, AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER)
            .single()
        assertEquals("com.apple.android.music.library3.LibraryComposeContentFragment", getter.className)
        assertEquals("F0", getter.methodName)
        assertEquals(0, getter.parameterCount)
        assertTrue(
            AppleMusicHookProfiles
                .candidates(version653, AppleMusicHookPoint.LIBRARY_COMPOSE_VIEW_MODEL_GETTER)
                .any { it.methodName == "B0" || it.methodName == "A0" },
        )
    }
}

private class Profile653FakeClassSource(
    private val classes: Map<String, Class<*>>,
) : TargetClassSource {
    var classNameReads: Int = 0
        private set

    override fun classNames(): List<String> {
        classNameReads++
        return emptyList()
    }

    override fun loadClass(name: String): Class<*>? = classes[name]
}

private class LanguageArray653Fixture {
    companion object {
        @JvmStatic
        fun b(context: Context): Array<String> = arrayOf("zh", "en")
    }
}
