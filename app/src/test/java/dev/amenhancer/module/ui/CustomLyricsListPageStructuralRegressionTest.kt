package dev.amenhancer.module.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsListPageStructuralRegressionTest {
    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `renders only the revealed window from the shared list state`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val state = projectFile("app/src/main/java/dev/amenhancer/module/ui/CustomLyricsListState.kt")

        assertTrue(host.contains("CustomLyricsListState"))
        assertTrue(host.contains("customLyricsListState.update(entries, customLyricsSearchQuery)"))
        assertTrue(host.contains("val visibleGroups = state.visibleGroups"))
        assertTrue(host.contains("embeddedCustomLyricsEntryRow(activity, group, song)"))
        assertTrue(state.contains("DEFAULT_PAGE_SIZE = 50"))
        assertTrue(state.contains("visibleEntries"))
        assertTrue(state.contains("hasMore"))
        assertFalse(state.contains("import android"))
    }

    @Test
    fun `paginates with search, load more and a shown total counter`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("搜索名称或 Apple Music ID"))
        assertTrue(host.contains("addTextChangedListener"))
        assertTrue(host.contains("afterTextChanged"))
        assertTrue(host.contains("customLyricsSearchQuery"))
        assertTrue(host.contains("加载更多"))
        assertTrue(host.contains("customLyricsListState.loadMore()"))
        assertTrue(
            host.contains(
                "已显示 \${state.visibleCount} / 共 \${state.totalCount} 首",
            ),
        )
    }

    @Test
    fun `places add and backup actions above the search input`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        val page = host.substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("private fun embeddedCustomLyricsEntryRow(")

        val actionBar = page.indexOf("embeddedCompactLyricsActionBar(")
        val search = page.indexOf("val search = EditText(activity)")
        assertTrue("action bar must exist", actionBar >= 0)
        assertTrue("search input must exist", search >= 0)
        assertTrue("actions must precede search", actionBar < search)
        listOf("onAdd", "onTtml", "onBackup").forEach { action ->
            assertTrue("$action must remain in the embedded action bar", page.contains(action))
        }
    }

    @Test
    fun `keeps embedded empty and no match states`() {
        val host = projectFile("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")

        assertTrue(host.contains("暂无自定义歌词"))
        assertTrue(host.contains("未找到匹配结果"))
        assertTrue(host.contains("添加歌词后会显示在这里"))
        assertTrue(host.contains("尝试更换关键词或检查 ID 是否正确"))
    }

    @Test
    fun `configures the embedded custom lyrics search field for text IME input`() {
        val embeddedHost = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt",
        )
        val embeddedSearch = embeddedHost
            .substringAfter("private fun renderEmbeddedCustomLyricsPage(")
            .substringBefore("private fun embeddedCustomLyricsEntryRow(")

        assertTrue(embeddedSearch.contains("inputType = InputType.TYPE_CLASS_TEXT"))
        assertTrue(embeddedSearch.contains("imeOptions = EditorInfo.IME_ACTION_SEARCH"))
        assertTrue(embeddedSearch.contains("showSoftInputOnFocus = true"))
        assertTrue(embeddedHost.contains("clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)"))
        assertTrue(embeddedHost.contains("setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)"))
    }

    @Test
    fun `embedded custom lyrics page exposes update instead of github bulk sync`() {
        val embeddedHost = projectFile(
            "app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt",
        )

        assertTrue(embeddedHost.contains("onUpdate = { updateEmbeddedLyrics(activity) }"))
        assertTrue(embeddedHost.contains("label = \"更新\""))
        assertTrue(embeddedHost.contains("description = \"歌词更新\""))
        assertTrue(embeddedHost.contains("private fun updateEmbeddedLyrics(activity: Activity)"))
        assertFalse(embeddedHost.contains("syncEmbeddedGitHub"))
        assertFalse(embeddedHost.contains("syncFromGitHub"))
    }
}
