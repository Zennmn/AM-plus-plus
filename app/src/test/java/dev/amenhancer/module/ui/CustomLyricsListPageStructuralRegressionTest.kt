package dev.amenhancer.module.ui
import dev.amenhancer.module.hook.readRefactorComponent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsListPageStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `renders only the revealed window from the shared list state`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")
        val state = projectFile("app/src/main/java/dev/amenhancer/module/ui/CustomLyricsListState.kt")

        assertTrue(page.contains("val lyricsState = customLyricsListState"))
        assertTrue(page.contains("lyricsState.update(entries, searchText)"))
        assertTrue(page.contains("lyricsState.visibleGroups"))
        assertTrue(page.contains("visibleGroups.forEachIndexed"))
        assertTrue(page.contains("CustomLyricsEntryRow("))
        assertTrue(state.contains("DEFAULT_PAGE_SIZE = 50"))
        assertTrue(state.contains("visibleEntries"))
        assertTrue(state.contains("hasMore"))
        assertFalse(state.contains("import android"))
    }

    @Test
    fun `paginates with search, load more and a shown total counter`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")

        assertTrue(page.contains("label = localizedText(ModuleText.SEARCH_LYRICS)"))
        assertTrue(page.contains("mutableStateOf(customLyricsSearchQuery)"))
        assertTrue(page.contains("customLyricsSearchQuery = value"))
        assertTrue(page.contains("lyricsState.setQuery(value)"))
        assertTrue(page.contains("ModuleText.LOAD_MORE"))
        assertTrue(page.contains("lyricsState.loadMore()"))
        assertTrue(page.contains("ModuleText.LYRICS_VISIBLE_COUNT"))
        assertTrue(page.contains("lyricsState.visibleCount"))
        assertTrue(page.contains("lyricsState.totalCount"))
    }

    @Test
    fun `places add and backup actions above the search input`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")

        val actionBar = page.indexOf("LyricsActionBar(")
        val search = page.indexOf("TextField(")
        assertTrue("action bar must exist", actionBar >= 0)
        assertTrue("search input must exist", search >= 0)
        assertTrue("actions must precede search", actionBar < search)
        val actionBarSource = page.substring(actionBar, search)
        listOf(
            "onAdd = { showLyricsEditor(",
            "onTtml = {",
            "onUpdate = { updateEmbeddedLyrics(activity) }",
            "onBackup = { showBackupRestoreDialog(activity) }",
        ).forEach { action ->
            assertTrue("$action must remain in the embedded action bar", actionBarSource.contains(action))
        }
        assertTrue(actionBarSource.contains("launchSafPicker("))
        assertTrue(actionBarSource.contains("EmbeddedSafOperation.Ttml"))
    }

    @Test
    fun `keeps embedded empty and no match states`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")

        assertTrue(page.contains("ModuleText.NO_CUSTOM_LYRICS"))
        assertTrue(page.contains("ModuleText.NO_SEARCH_RESULTS"))
        assertTrue(page.contains("ModuleText.ADD_LYRICS_EMPTY_HINT"))
        assertTrue(page.contains("ModuleText.SEARCH_LYRICS_EMPTY_HINT"))
    }

    @Test
    fun `configures the embedded custom lyrics search field for text IME input`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")
        val window = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsComposeWindow.kt")
        val embeddedSearch = page.substringAfter("TextField(")
            .substringBefore("EmbeddedSettingsSection(")

        assertTrue(embeddedSearch.contains("ImeAction.Search"))
        assertTrue(embeddedSearch.contains("singleLine = true"))
        assertTrue(window.contains("clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM"))
        assertTrue(window.contains("setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)"))
    }

    @Test
    fun `embedded custom lyrics page exposes update instead of github bulk sync`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")
        val operations = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsOperations.kt")

        assertTrue(page.contains("onUpdate = { updateEmbeddedLyrics(activity) }"))
        assertTrue(page.contains("label = localizedText(ModuleText.UPDATE)"))
        assertTrue(page.contains("description = ModuleText.LYRICS_UPDATE"))
        assertTrue(operations.contains("internal fun EmbeddedSettingsHost.updateEmbeddedLyrics(activity: Activity)"))
        assertFalse(page.contains("syncEmbeddedGitHub"))
        assertFalse(page.contains("syncFromGitHub"))
        assertFalse(operations.contains("syncEmbeddedGitHub"))
        assertFalse(operations.contains("syncFromGitHub"))
    }
}
