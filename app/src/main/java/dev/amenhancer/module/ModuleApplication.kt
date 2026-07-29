package dev.amenhancer.module

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val supportsRemote = service.apiVersion >= 102 &&
            service.frameworkProperties.and(XposedService.PROP_CAP_REMOTE) != 0L
        if (!supportsRemote) {
            publish(XposedServiceSnapshot.unsupported(service.frameworkName, service.apiVersion))
            return
        }
        val preferences = service.getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)
        dev.amenhancer.module.config.ConfigStore.migrateLegacyPreferences(this, preferences)
        publish(XposedServiceSnapshot.connected(
            preferences = preferences,
            frameworkName = service.frameworkName,
            apiVersion = service.apiVersion,
        ))
    }

    override fun onServiceDied(service: XposedService) {
        publish(XposedServiceSnapshot.disconnected())
    }

    companion object {
        private val serviceSnapshotReference = AtomicReference(XposedServiceSnapshot.waiting())
        internal val serviceSnapshot: XposedServiceSnapshot get() = serviceSnapshotReference.get()
        private val listeners = CopyOnWriteArraySet<(XposedServiceSnapshot) -> Unit>()

        internal fun addServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners += listener
        }

        internal fun removeServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners -= listener
        }

        private fun publish(snapshot: XposedServiceSnapshot) {
            serviceSnapshotReference.set(snapshot)
            listeners.forEach { it(snapshot) }
        }
    }
}
