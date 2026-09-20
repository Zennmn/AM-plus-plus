/*
 * Derived from AndroidLiquidGlass / Backdrop 2.0.1
 * (https://github.com/Kyant0/AndroidLiquidGlass), commit
 * 65ab177e90e5c1d8c62e70cf7755841982da65f6, Apache License 2.0.
 * Changed by AM++: reduced from the upstream expect/actual pair to an Android-only helper.
 * See backdrop/UPSTREAM.md and THIRD_PARTY_NOTICES.md.
 */

package com.kyant.backdrop.catalog.utils

// Android-only adapter of the upstream expect/actual helper.
suspend fun awaitFrame() { kotlinx.coroutines.android.awaitFrame() }

