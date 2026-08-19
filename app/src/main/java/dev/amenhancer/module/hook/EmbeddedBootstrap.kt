package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
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
    ): Boolean {
        if (packageName != ModuleConstants.TARGET_PACKAGE) return false
        if (processName.isNotBlank() && processName != ModuleConstants.TARGET_PACKAGE) return false
        if (!isFirstPackage) return false
        return prepared.compareAndSet(false, true)
    }

    fun bind(build: TargetBuild, reader: ConfigurationReader): Boolean {
        if (!prepared.get()) return false
        if (!supports(build)) return false
        return deferredReader.bind(reader)
    }

    fun supports(build: TargetBuild): Boolean =
        build.packageName == ModuleConstants.TARGET_PACKAGE &&
            SUPPORTED_BUILDS.any { supported ->
                build.versionName == supported.versionName &&
                    build.versionCode == supported.versionCode
            }

    private class DeferredConfigurationReader : ConfigurationReader {
        private val delegate = AtomicReference<ConfigurationReader?>(null)

        fun bind(reader: ConfigurationReader): Boolean = delegate.compareAndSet(null, reader)

        override fun values(): Map<String, *> = delegate.get()?.values().orEmpty()

        override fun openFile(name: String): InputStream? = delegate.get()?.openFile(name)

        override fun openFileDescriptor(name: String): ParcelFileDescriptor? =
            delegate.get()?.openFileDescriptor(name)
    }

    private companion object {
        private val SUPPORTED_BUILDS = listOf(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.1", 1583L),
            TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L),
        )
    }
}
