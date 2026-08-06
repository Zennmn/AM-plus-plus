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
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val state = projectFile("app/src/main/java/dev/amenhancer/module/ui/CustomLyricsListState.kt")

        assertTrue(activity.contains("CustomLyricsListState"))
        assertTrue(
            activity.contains(
                "customLyricsListState.update(\n" +
                    "            settings.customLyricsManifest.entries,\n" +
                    "            customLyricsSearchQuery,\n" +
                    "        )",
            ),
        )
        assertTrue(activity.contains("state.visibleEntries.forEach"))
        assertTrue(activity.contains("customLyricsEntryRow(entry, writable)"))
        assertFalse(activity.contains("manifest.entries.forEach"))
        assertTrue(state.contains("DEFAULT_PAGE_SIZE = 50"))
        assertTrue(state.contains("visibleEntries"))
        assertTrue(state.contains("hasMore"))
        assertFalse(state.contains("import android"))
    }

    @Test
    fun `paginates with search, load more and a shown total counter`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("搜索名称或 Apple Music ID"))
        assertTrue(activity.contains("addTextChangedListener"))
        assertTrue(activity.contains("afterTextChanged"))
        assertTrue(activity.contains("customLyricsSearchQuery"))
        assertTrue(activity.contains("加载更多"))
        assertTrue(activity.contains("customLyricsListState.loadMore()"))
        assertTrue(
            activity.contains(
                "已显示 \${state.visibleCount} / 共 \${state.totalCount} 首",
            ),
        )
        assertFalse(activity.contains("RecyclerView"))
    }

    @Test
    fun `places add and backup actions above the search input`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")
        val card = activity.substringAfter("private fun customLyricsCard(")
            .substringBefore("private fun customLyricsSearchInput(")

        val searchIndex = card.indexOf("customLyricsSearchInput(writable)")
        assertTrue("search input call must exist", searchIndex >= 0)
        val entriesGate = card.indexOf("if (manifest.entries.isNotEmpty())")
        assertTrue("entries gate must exist", entriesGate >= 0)
        for (label in listOf("添加歌词", "备份歌词", "恢复备份")) {
            val actionIndex = card.indexOf(label)
            assertTrue("$label action must exist", actionIndex >= 0)
            assertTrue("$label action must precede the search input", actionIndex < searchIndex)
            assertTrue("$label action must remain outside the entries gate", actionIndex < entriesGate)
        }
        assertTrue("search input must remain inside the entries gate", entriesGate < searchIndex)
    }

    @Test
    fun `keeps empty and remote unavailable semantics with a no match state`() {
        val activity = projectFile("app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt")

        assertTrue(activity.contains("需要 libxposed API 102 remote file 服务"))
        assertTrue(activity.contains("按 Apple Music ID 手动添加 TTML；不会在播放时联网识歌"))
        assertTrue(activity.contains("没有匹配的歌词"))
        assertTrue(activity.contains("已配置 \${manifest.entries.size} 首；更改后重开 Apple Music 生效"))
    }
}
