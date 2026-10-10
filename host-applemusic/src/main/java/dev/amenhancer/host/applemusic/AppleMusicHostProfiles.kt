package dev.amenhancer.host.applemusic

import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap

/** Exact build knowledge shared by bootstrap, adapters and the offline verifier. */
object AppleMusicHostProfiles {
    @Volatile private var apkPaths: List<String> = emptyList()
    fun initializeModulePaths(paths: List<String>) { apkPaths = paths.distinct() }

    private val filenames: List<String> by lazy {
        val index = read("index.json")
        check(index.getInt("schemaVersion") == 1)
        index.getJSONArray("profiles").strings().also { require(it.distinct().size == it.size) }
    }
    private val loaded = ConcurrentHashMap<String, AppleMusicHostProfile>()
    private fun load(name: String): AppleMusicHostProfile = loaded.computeIfAbsent(name) {
        AppleMusicHostProfile(read(name)).also { profile ->
            require(name == "${profile.versionName}-${profile.versionCode}.json")
        }
    }
    val all: List<AppleMusicHostProfile> by lazy {
        filenames.map(::load).also { profiles ->
            check(profiles.map { Triple(it.packageName, it.versionName, it.versionCode) }.distinct().size == profiles.size)
        }
    }

    fun find(packageName: String, versionName: String, versionCode: Long): AppleMusicHostProfile? {
        val name = "$versionName-$versionCode.json"
        if (name !in filenames) return null
        return load(name).takeIf { it.packageName == packageName }
    }

    fun isProductionBuild(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.productionEnabled == true

    fun supportsGlass(versionCode: Long, versionName: String): Boolean =
        find("com.apple.android.music", versionName, versionCode)?.let { it.productionEnabled && it.capability("glass") } == true

    fun supportsCellular(packageName: String, versionName: String, versionCode: Long): Boolean =
        find(packageName, versionName, versionCode)?.let { it.productionEnabled && it.capability("cellular") } == true

    fun catalogQueryRename(owner: String, preferredName: String): String? =
        all.firstNotNullOfOrNull { it.document.optJSONObject("catalogQueryRenames")?.optJSONObject(owner)
            ?.optString(preferredName)?.takeIf(String::isNotBlank) }

    internal fun openTabletMediaAsset(name: String): java.io.InputStream {
        val path = "tablet-media/$name"
        AppleMusicHostProfiles::class.java.classLoader?.getResourceAsStream(path)?.let { return it }
        apkPaths.forEach { apk ->
            val bytes = ZipFile(apk).use { zip -> zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            } }
            if (bytes != null) return bytes.inputStream()
        }
        error("Packaged tablet media asset is unavailable: $name")
    }

    private fun read(name: String): JSONObject {
        require(Regex("[A-Za-z0-9._-]+\\.json").matches(name))
        val path = "host-profiles/$name"
        val resource = AppleMusicHostProfiles::class.java.classLoader?.getResourceAsStream(path)
        if (resource != null) return resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        apkPaths.forEach { apk ->
            val bytes = runCatching { ZipFile(apk).use { zip -> zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            } } }.getOrNull()
            if (bytes != null) return JSONObject(bytes.toString(Charsets.UTF_8))
        }
        error("Packaged Apple Music profile is unavailable: $path")
    }
}

class AppleMusicHostProfile internal constructor(val document: JSONObject) {
    init { require(document.getInt("schemaVersion") == 1) }
    val id: String = document.getString("id")
    val packageName: String = document.getString("packageName")
    val versionName: String = document.getString("versionName")
    val versionCode: Long = document.getLong("versionCode")
    val productionEnabled: Boolean = document.getBoolean("productionEnabled")
    val family: String = document.getString("family")
    fun capability(key: String): Boolean = document.getJSONObject("capabilities").optBoolean(key, false)
}

fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith(::getString)
fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
