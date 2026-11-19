package dev.amenhancer.host.classical

import dev.amenhancer.module.ModuleConstants
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Exact Apple Music Classical build knowledge shared by bootstrap and adapters. */
object ClassicalHostProfiles {
    @Volatile
    private var apkPaths: List<String> = emptyList()

    fun initializeModulePaths(paths: List<String>) {
        apkPaths = paths.distinct()
    }

    private val filenames: List<String> by lazy {
        val index = read("index.json")
        check(index.getInt("schemaVersion") == 1)
        val array = index.getJSONArray("profiles")
        List(array.length()) { array.getString(it) }.also {
            require(it.distinct().size == it.size)
        }
    }

    private val loaded = ConcurrentHashMap<String, ClassicalHostProfile>()

    private fun load(name: String): ClassicalHostProfile = loaded.computeIfAbsent(name) {
        ClassicalHostProfile(read(name)).also { profile ->
            require(name == "${profile.versionName}-${profile.versionCode}.json")
        }
    }

    val all: List<ClassicalHostProfile> by lazy { filenames.map(::load) }

    fun find(packageName: String, versionName: String, versionCode: Long): ClassicalHostProfile? {
        if (packageName != ModuleConstants.CLASSICAL_PACKAGE) return null
        val name = "$versionName-$versionCode.json"
        if (name !in filenames) return null
        return load(name)
    }

    fun isProductionBuild(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.productionEnabled == true

    fun supportsGlass(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.let { it.productionEnabled && it.capability("glass") } == true

    private fun read(name: String): JSONObject {
        require(Regex("[A-Za-z0-9._-]+\\.json").matches(name))
        val path = "classical-profiles/$name"
        val resource = ClassicalHostProfiles::class.java.classLoader?.getResourceAsStream(path)
        if (resource != null) return resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        apkPaths.forEach { apk ->
            val bytes = runCatching {
                ZipFile(apk).use { zip ->
                    zip.getEntry(path)?.let { entry ->
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
            }.getOrNull()
            if (bytes != null) return JSONObject(bytes.toString(Charsets.UTF_8))
        }
        error("Packaged Classical profile is unavailable: $path")
    }
}

class ClassicalHostProfile internal constructor(val document: JSONObject) {
    init {
        require(document.getInt("schemaVersion") == 1)
    }

    val id: String = document.getString("id")
    val packageName: String = document.getString("packageName")
    val versionName: String = document.getString("versionName")
    val versionCode: Long = document.getLong("versionCode")
    val productionEnabled: Boolean = document.getBoolean("productionEnabled")
    val family: String = document.getString("family")

    fun capability(key: String): Boolean = document.getJSONObject("capabilities").optBoolean(key, false)
}
