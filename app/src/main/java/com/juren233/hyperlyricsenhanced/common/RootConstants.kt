package com.juren233.hyperlyricsenhanced.common

/** Minimal HLE constants surface used by the migrated Apple metadata subsystem. */
internal object RootConstants {
    const val KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE =
        "key_hook_apple_music_content_ui_language"

    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE = 0
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_CN = 1
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANS_US = 2
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_HK = 3
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_ZH_HANT_TW = 4
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_KO_KR = 5
    const val APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP = 6
    const val DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE =
        APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE

    const val KEY_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER =
        "key_hook_apple_music_notification_open_full_player"
    const val DEFAULT_HOOK_APPLE_MUSIC_NOTIFICATION_OPEN_FULL_PLAYER = false
}
