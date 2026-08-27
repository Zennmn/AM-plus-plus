package io.github.proify.lyricon.amprovider.xposed

/** Small HLE-only compatibility types not needed by the lyrics provider. */
internal enum class AppleNativeSupplementTrack { TRANSLATION, PRONUNCIATION }

internal data class AppleLyricsLanguageParts(
    val normalized: String,
    val language: String,
    val script: String?,
    val region: String?,
)
