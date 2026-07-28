package dev.amenhancer.module.hook

import android.content.Context
import dalvik.system.DexFile
import java.util.Collections

internal class ApkTargetClassSource internal constructor(
    private val apkPaths: List<String>,
    private val classLoader: ClassLoader,
    private val readEntries: (String) -> List<String>,
) : TargetClassSource {
    constructor(context: Context, classLoader: ClassLoader) : this(
        apkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let(::addAll)
        }.distinct(),
        classLoader = classLoader,
        readEntries = ::readDexEntries,
    )

    override fun classNames(): List<String> = apkPaths.asSequence()
        .flatMap { path -> readEntries(path).asSequence() }
        .filter { it.startsWith("com.apple.") }
        .distinct()
        .sorted()
        .toList()

    override fun loadClass(name: String): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()
}

@Suppress("DEPRECATION")
private fun readDexEntries(path: String): List<String> = runCatching {
    val dex = DexFile(path)
    try {
        Collections.list(dex.entries())
    } finally {
        dex.close()
    }
}.getOrDefault(emptyList())
