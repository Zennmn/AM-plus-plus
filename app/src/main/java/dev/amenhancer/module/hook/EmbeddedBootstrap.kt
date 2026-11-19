package dev.amenhancer.module.hook

import dev.amenhancer.module.config.ConfigurationReader
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact-version bootstrap for the embedded-only artifact. */
internal class EmbeddedBootstrap {
    private val prepared = AtomicBoolean(false)
    private val deferredReader = DeferredConfigurationReader()

    val reader: ConfigurationReader get() = deferredReader

    fun prepare(
        packageName: String,
        processName: String,
        isFirstPackage: Boolean,
        mainProcessName: String = packageName,
    ): Boolean {
        if (packageName.isBlank()) return false
        if (processName.isNotBlank() && processName != mainProcessName) return false
        if (!isFirstPackage) return false
        return prepared.compareAndSet(false, true)
    }

    fun bind(build: TargetBuild, reader: ConfigurationReader): Boolean {
        if (!prepared.get()) return false
        if (!supports(build)) return false
        return deferredReader.bind(reader)
    }

    fun supports(build: TargetBuild): Boolean =
        dev.amenhancer.host.applemusic.AppleMusicHostProfiles.isProductionBuild(
            build.packageName, build.versionName, build.versionCode,
        ) || dev.amenhancer.host.classical.ClassicalHostProfiles.isProductionBuild(
            build.packageName, build.versionName, build.versionCode,
        )

    private class DeferredConfigurationReader : ConfigurationReader {
        private val delegate = AtomicReference<ConfigurationReader?>(null)

        fun bind(reader: ConfigurationReader): Boolean = delegate.compareAndSet(null, reader)

        override fun values(): Map<String, *> = delegate.get()?.values().orEmpty()

        override fun openFile(name: String): InputStream? = delegate.get()?.openFile(name)

        override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
            delegate.get()?.openFileDescriptor(name)
    }

}
