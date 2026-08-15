package dev.amenhancer.module.hook

import com.apple.android.music.mediaapi.models.LibraryAlbumTitleFixture
import com.apple.android.music.mediaapi.models.LibrarySongTitleFixture
import com.apple.android.music.mediaapi.models.MediaEntityTitleFixture
import com.apple.android.music.mediaapi.models.internals.Attributes
import com.apple.android.music.mediaapi.models.internals.SearchResultsResponse
import com.apple.android.medialibrary.javanative.medialibrary.svmodel.SVEntityNative
import com.apple.android.music.model.Album
import com.apple.android.music.model.BasePlaybackItem
import com.apple.android.music.model.BaseStorePlatformResponse
import com.apple.android.music.model.CollectionItemView
import com.apple.android.music.model.PlaybackItem
import com.apple.android.music.model.Song as ModelSong
import android.content.Context
import android.view.View
import dev.amenhancer.module.ModuleConstants
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import y8.B
import v5.a
import z9.C

/**
 * Structural resolution of the AMTool-equivalent title correction seams.
 * Every seam resolves independently: a missing or ambiguous symbol degrades
 * only that seam, and the pinned `y8.B` converter is verified per profile
 * without scanning the dex inventory.
 */
class TitleCorrectionSymbolsTest {
    private val mediaEntityName = "com.apple.android.music.mediaapi.models.MediaEntity"
    private val songName = "com.apple.android.music.mediaapi.models.Song"
    private val attributesName = "com.apple.android.music.mediaapi.models.internals.Attributes"
    private val titleName = "com.apple.android.music.mediaapi.models.internals.Title"
    private val searchSectionName =
        "com.apple.android.music.mediaapi.models.internals.SearchResultsResponse\$SearchSectionResultResponse"
    private val librarySongName = "com.apple.android.music.mediaapi.models.LibrarySong"
    private val libraryAlbumName = "com.apple.android.music.mediaapi.models.LibraryAlbum"
    private val storePlatformName = "com.apple.android.music.model.BaseStorePlatformResponse"
    private val converterName = "y8.B"
    private val playerDisplayName = "com.apple.android.music.player.d1"
    private val playerActionResponseName = "com.apple.android.music.collection.mediaapi.fragment.G"
    private val nativeConverterName = "v5.a"

    @Test
    fun `stable title seams resolve from their model classes`() {
        val source = TitleCorrectionFakeClassSource(
            names = listOf(
                mediaEntityName,
                songName,
                "com.apple.android.music.mediaapi.models.Album",
                attributesName,
                titleName,
                searchSectionName,
                librarySongName,
                libraryAlbumName,
                storePlatformName,
                playerDisplayName,
                playerActionResponseName,
                nativeConverterName,
            ),
            classes = mapOf(
                mediaEntityName to MediaEntityTitleFixture::class.java,
                songName to com.apple.android.music.mediaapi.models.Song::class.java,
                "com.apple.android.music.mediaapi.models.Album" to
                    com.apple.android.music.mediaapi.models.Album::class.java,
                attributesName to Attributes::class.java,
                titleName to com.apple.android.music.mediaapi.models.internals.Title::class.java,
                searchSectionName to
                    SearchResultsResponse.SearchSectionResultResponse::class.java,
                librarySongName to LibrarySongTitleFixture::class.java,
                libraryAlbumName to LibraryAlbumTitleFixture::class.java,
                storePlatformName to BaseStorePlatformResponse::class.java,
                playerDisplayName to
                    com.apple.android.music.player.PlayerTitleCorrectionFixture::class.java,
                playerActionResponseName to
                    com.apple.android.music.collection.mediaapi.fragment.G::class.java,
                nativeConverterName to a::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val shortNameBase = resolver.resolve(AppleMusicSymbols.MediaEntityGetShortNameMethod)
        val shortNameOverride = resolver.resolve(AppleMusicSymbols.SongGetShortNameMethod)
        val attributes = listOf(
            resolver.resolve(AppleMusicSymbols.AttributesGetShortNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesGetNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesGetTitleMethod),
            resolver.resolve(AppleMusicSymbols.AttributesGetTitleWithoutNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesGetArtistNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesSetArtistNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesGetAlbumNameMethod),
            resolver.resolve(AppleMusicSymbols.AttributesSetAlbumNameMethod),
        )
        val titleDisplay = resolver.resolve(AppleMusicSymbols.TitleGetStringForDisplayMethod)
        val searchData = resolver.resolve(AppleMusicSymbols.SearchSectionResultResponseSetDataMethod)
        val songConversion = resolver.resolve(AppleMusicSymbols.SongToCollectionItemViewMethod)
        val albumConversion = resolver.resolve(AppleMusicSymbols.AlbumToCollectionItemViewMethod)
        val librarySong = resolver.resolve(AppleMusicSymbols.LibrarySongToCollectionItemViewMethod)
        val libraryAlbum = resolver.resolve(AppleMusicSymbols.LibraryAlbumToCollectionItemViewMethod)
        val storePlatform = resolver.resolve(
            AppleMusicSymbols.BaseStorePlatformResponseGetStorePlatformDataMethod,
        )
        val playerDisplay = resolver.resolve(AppleMusicSymbols.PlayerActionSheetMethod)
        val playerActionResponse = resolver.resolve(
            AppleMusicSymbols.PlayerActionSheetResponseApplyMethod,
        )
        val playbackPopulate = resolver.resolve(
            AppleMusicSymbols.NativeLibrarySongConverterMethod,
        )
        val albumConverter = resolver.resolve(AppleMusicSymbols.NativeLibraryAlbumConverterMethod)

        listOf(
            shortNameBase,
            shortNameOverride,
            searchData,
            songConversion,
            albumConversion,
            librarySong,
            libraryAlbum,
            storePlatform,
            titleDisplay,
            playerDisplay,
            playerActionResponse,
            playbackPopulate,
            albumConverter,
        )
            .forEach { resolution ->
                assertTrue(resolution is TargetResolution.Found)
                assertEquals(SymbolMatch.STABLE_NAME, (resolution as TargetResolution.Found).match)
            }
        assertEquals("getShortName", (shortNameBase as TargetResolution.Found).value.name)
        assertEquals("getShortName", (shortNameOverride as TargetResolution.Found).value.name)
        assertEquals("setData", (searchData as TargetResolution.Found).value.name)
        assertEquals("toCollectionItemView", (songConversion as TargetResolution.Found).value.name)
        assertEquals("toCollectionItemView", (albumConversion as TargetResolution.Found).value.name)
        assertEquals("toCollectionItemView", (librarySong as TargetResolution.Found).value.name)
        assertEquals("toCollectionItemView", (libraryAlbum as TargetResolution.Found).value.name)
        assertEquals("getStorePlatformData", (storePlatform as TargetResolution.Found).value.name)
        assertEquals("getStringForDisplay", (titleDisplay as TargetResolution.Found).value.name)
        assertEquals("y0", (playerDisplay as TargetResolution.Found).value.name)
        assertEquals("accept", (playerActionResponse as TargetResolution.Found).value.name)
        assertEquals("n", (playbackPopulate as TargetResolution.Found).value.name)
        assertEquals("b", (albumConverter as TargetResolution.Found).value.name)
        assertEquals(2, (librarySong as TargetResolution.Found).value.parameterTypes.size)
        assertEquals(
            listOf(
                PlaybackItem::class.java,
                CollectionItemView::class.java,
                String::class.java,
                Context::class.java,
                View::class.java,
            ),
            (playerDisplay as TargetResolution.Found).value.parameterTypes.toList(),
        )
        assertEquals(
            listOf(BasePlaybackItem::class.java, SVEntityNative.SVEntitySRef::class.java),
            (playbackPopulate as TargetResolution.Found).value.parameterTypes.toList(),
        )
        assertEquals(
            listOf(SVEntityNative.SVEntitySRef::class.java, Boolean::class.javaPrimitiveType),
            (albumConverter as TargetResolution.Found).value.parameterTypes.toList(),
        )
        assertEquals(Void.TYPE, (playbackPopulate as TargetResolution.Found).value.returnType)
        assertEquals(Album::class.java, (albumConverter as TargetResolution.Found).value.returnType)
        assertTrue(Modifier.isStatic((playbackPopulate as TargetResolution.Found).value.modifiers))
        assertTrue(Modifier.isStatic((albumConverter as TargetResolution.Found).value.modifiers))
        attributes.forEach { resolution ->
            assertTrue(resolution is TargetResolution.Found)
        }
    }

    @Test
    fun `650 profile resolves the pinned song converter without scanning dex`() {
        val source = TitleCorrectionFakeClassSource(
            classes = mapOf(converterName to B::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.0", 1580L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)

        assertTrue(resolution is TargetResolution.Found)
        val found = resolution as TargetResolution.Found
        assertEquals(SymbolMatch.VERSION_PROFILE, found.match)
        assertEquals("convert", found.value.name)
        assertEquals("apple-music-6.5.0-1580", found.profileId)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `651 profile resolves the migrated song converter without scanning dex`() {
        val source = TitleCorrectionFakeClassSource(
            classes = mapOf(converterName to B::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = source,
        )

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertEquals("apple-music-6.5.1-1583", resolution.profileId)
        assertEquals(0, source.classNameReads)
    }

    @Test
    fun `unknown build resolves the unique converter signature structurally`() {
        val source = TitleCorrectionFakeClassSource(
            names = listOf(converterName),
            classes = mapOf(converterName to B::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, (resolution as TargetResolution.Found).match)
        assertEquals("convert", (resolution as TargetResolution.Found).value.name)
    }

    @Test
    fun `a stale converter pin degrades instead of scanning blindly`() {
        val resolver = IndexedTargetSymbolResolver(
            build = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            source = TitleCorrectionFakeClassSource(
                names = listOf("y8.B"),
                classes = mapOf("y8.B" to ModelSong::class.java),
            ),
        )

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)

        assertTrue(resolution is TargetResolution.Missing)
    }

    @Test
    fun `two same shaped converters resolve ambiguous instead of silently choosing`() {
        val second = "z9.C"
        val source = TitleCorrectionFakeClassSource(
            names = listOf(converterName, second),
            classes = mapOf(
                converterName to B::class.java,
                second to C::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityToSongConverterMethod)

        assertTrue(resolution is TargetResolution.Ambiguous)
        assertEquals(2, (resolution as TargetResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `get attributes resolves by contract without pinning the return class`() {
        val source = TitleCorrectionFakeClassSource(
            names = listOf(mediaEntityName),
            classes = mapOf(
                mediaEntityName to MediaEntityRenamedAttributesFixture::class.java,
            ),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        val resolution = resolver.resolve(AppleMusicSymbols.MediaEntityGetAttributesMethod)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals("getAttributes", (resolution as TargetResolution.Found).value.name)
        assertEquals(0, resolution.value.parameterTypes.size)
        assertEquals(SymbolMatch.STABLE_NAME, resolution.match)
    }

    @Test
    fun `a missing attributes accessor degrades only that symbol`() {
        val source = TitleCorrectionFakeClassSource(
            names = listOf(attributesName),
            classes = mapOf(attributesName to Attributes::class.java),
        )
        val resolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, source)

        assertTrue(
            resolver.resolve(AppleMusicSymbols.AttributesSetAlbumNameMethod)
                is TargetResolution.Found,
        )
        val broken = TitleCorrectionFakeClassSource(
            names = listOf(attributesName),
            classes = mapOf(attributesName to BrokenAttributesFixture::class.java),
        )
        val brokenResolver = IndexedTargetSymbolResolver(TargetBuild.UNKNOWN, broken)

        assertTrue(
            brokenResolver.resolve(AppleMusicSymbols.AttributesSetAlbumNameMethod)
                is TargetResolution.Missing,
        )
        assertTrue(
            brokenResolver.resolve(AppleMusicSymbols.AttributesGetArtistNameMethod)
                is TargetResolution.Found,
        )
    }

    @Test
    fun `profile backed converter declares a reviewed fallback policy`() {
        assertEquals(
            ProfilePolicy.EXACT_PREFERRED,
            AppleMusicSymbols.MediaEntityToSongConverterMethod.profilePolicy,
        )
    }

    @Test
    fun `unpinned title seams stay profile independent`() {
        listOf(
            AppleMusicSymbols.MediaEntityGetShortNameMethod,
            AppleMusicSymbols.SongGetShortNameMethod,
            AppleMusicSymbols.AttributesGetShortNameMethod,
            AppleMusicSymbols.AttributesGetNameMethod,
            AppleMusicSymbols.AttributesGetTitleMethod,
            AppleMusicSymbols.AttributesGetTitleWithoutNameMethod,
            AppleMusicSymbols.SearchSectionResultResponseSetDataMethod,
            AppleMusicSymbols.SongToCollectionItemViewMethod,
            AppleMusicSymbols.AlbumToCollectionItemViewMethod,
            AppleMusicSymbols.LibrarySongToCollectionItemViewMethod,
            AppleMusicSymbols.LibraryAlbumToCollectionItemViewMethod,
            AppleMusicSymbols.BaseStorePlatformResponseGetStorePlatformDataMethod,
            AppleMusicSymbols.PlayerActionSheetMethod,
            AppleMusicSymbols.NativeLibrarySongConverterMethod,
            AppleMusicSymbols.NativeLibraryAlbumConverterMethod,
        ).forEach { symbol ->
            assertEquals(ProfilePolicy.NO_PROFILE, symbol.profilePolicy)
        }
    }

    private class BrokenAttributesFixture {
        fun getName(): String = ""
        fun setName(name: String) {}
        fun getArtistName(): String = ""
        fun setArtistName(name: String) {}
        fun getAlbumName(): String = ""
    }

    /** getAttributes returns a non-Attributes non-void type to pin the relaxed contract. */
    private class MediaEntityRenamedAttributesFixture {
        fun getAttributes(): Map<String, Any> = emptyMap()
    }
}
private class TitleCorrectionFakeClassSource(
    private val names: List<String> = emptyList(),
    private val classes: Map<String, Class<*>>,
) : TargetClassSource {
    private val loaded = mutableMapOf<String, Class<*>?>()

    var classNameReads: Int = 0
        private set

    override fun classNames(): List<String> {
        classNameReads++
        return names
    }

    override fun loadClass(name: String): Class<*>? {
        if (loaded.containsKey(name)) return loaded[name]
        return classes[name].also { loaded[name] = it }
    }
}
