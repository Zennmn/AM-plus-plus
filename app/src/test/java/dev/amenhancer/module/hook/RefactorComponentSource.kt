package dev.amenhancer.module.hook
import java.io.File

/** Source contracts protect a responsibility, including all files extracted from its old owner. */
internal fun File.readRefactorComponent(): String {
    val root = generateSequence(absoluteFile.parentFile) { it.parentFile }
        .firstOrNull { File(it,"settings.gradle.kts").isFile } ?: return readText()
    val hook = "host-applemusic/src/main/java/dev/amenhancer/module/hook/"
    val catalog = "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/"
    val ui = "app/src/main/java/dev/amenhancer/module/ui/"
    val components = when (name) {
        "AppleMusicDualPaneTarget.kt" -> listOf("DualPaneResourceBindings.kt","LegacyLyricsLayoutProfiles.kt",
            "AppleMusicDualPaneTarget.kt","LegacyTabletQualification.kt","DualPaneViewMount.kt",
            "DualPaneFragmentStateBridge.kt","DualPaneReflectionAccess.kt","TabletArtworkLayoutLease.kt").map { hook+it }
        "HleMetadataSurfaceBridge.kt" -> listOf(hook+"HleMetadataSurfaceBridge.kt",hook+"MetadataHostAdapters.kt")
        "AppleInternalCatalogResolver.kt" -> listOf("AppleInternalCatalogResolver.kt","CatalogPersistentAccess.kt",
            "CatalogRequestScheduling.kt","CatalogOriginalResolution.kt","NativeCatalogQueryAccess.kt",
            "CatalogResponseSnapshotAccess.kt").map { catalog+it }
        "EmbeddedSettingsHost.kt" -> listOf("EmbeddedSettingsDesign.kt","EmbeddedSettingsState.kt","EmbeddedSafRouter.kt",
            "EmbeddedSettingsController.kt","EmbeddedSettingsHost.kt","EmbeddedSettingsPages.kt","EmbeddedSettingsWidgets.kt",
            "EmbeddedSettingsComposeWindow.kt","EmbeddedLyricsSettingsPages.kt","EmbeddedLlmSettingsPage.kt",
            "EmbeddedPluginPages.kt","EmbeddedAnimationSettingsPage.kt","EmbeddedLyricsEditor.kt",
            "EmbeddedSettingsOperations.kt").map { ui+it }
        else -> return readText()
    }
    return components.joinToString("\n") { File(root,it).readText() }
}
