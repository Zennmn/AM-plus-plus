package com.kyant.backdrop.catalog.utils

// Android-only adapter of the upstream expect/actual helper.
suspend fun awaitFrame() { kotlinx.coroutines.android.awaitFrame() }

