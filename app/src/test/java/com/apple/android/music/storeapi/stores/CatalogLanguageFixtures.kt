package com.apple.android.music.storeapi.stores

/**
 * Mirrors the storefront language accessor that AMTool rewrites with
 * `ic.r()`.  The class deliberately does NOT carry the verified
 * `ConfigurationStore` name: the symbol resolves by package scan plus the
 * `storeFrontLanguageOrDefault` contract, never by a pinned class name.
 */
class StorefrontLanguageFixture {
    fun storeFrontLanguageOrDefault(): String = "tr-TR"
}
