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
    fun `routes add, import, update and backup actions above the search input`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")

        val add = page.indexOf("title = localizedText(ModuleText.ADD_LYRICS)")
        val importTtml = page.indexOf("title = localizedText(ModuleText.IMPORT_TTML)")
        val update = page.indexOf("title = localizedText(ModuleText.LYRICS_UPDATE)")
        val backup = page.indexOf("title = localizedText(ModuleText.BACKUP_AND_RESTORE)")
        val search = page.indexOf("label = localizedText(ModuleText.SEARCH_LYRICS)")
        assertTrue("add action must exist", add >= 0)
        assertTrue("TTML import action must exist", importTtml >= 0)
        assertTrue("update action must exist", update >= 0)
        assertTrue("backup action must exist", backup >= 0)
        assertTrue("search input must exist", search >= 0)
        assertTrue(
            "actions must precede search",
            add < search && importTtml < search && update < search && backup < search,
        )
        val actionSource = page.substring(add, search)
        listOf(
            "onClick = { showLyricsEditor(",
            "launchSafPicker(",
            "EmbeddedSafOperation.Ttml",
            "onClick = { updateEmbeddedLyrics(activity) }",
            "onClick = { showBackupRestoreDialog(activity) }",
        ).forEach { action ->
            assertTrue("$action must remain in the embedded action group", actionSource.contains(action))
        }
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
        val clearedFlags = window.substringAfter("clearFlags(").substringBefore(")")
        assertTrue(clearedFlags.contains("WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM"))
        assertTrue(window.contains("setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)"))
    }

    @Test
    fun `embedded custom lyrics page exposes update instead of github bulk sync`() {
        val page = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedLyricsSettingsPages.kt")
        val operations = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsOperations.kt")

        assertTrue(page.contains("title = localizedText(ModuleText.LYRICS_UPDATE)"))
        assertTrue(page.contains("onClick = { updateEmbeddedLyrics(activity) }"))
        assertTrue(operations.contains("internal fun EmbeddedSettingsHost.updateEmbeddedLyrics(activity: Activity)"))
        assertFalse(page.contains("syncEmbeddedGitHub"))
        assertFalse(page.contains("syncFromGitHub"))
        assertFalse(operations.contains("syncEmbeddedGitHub"))
        assertFalse(operations.contains("syncFromGitHub"))
    }
}
