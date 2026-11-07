package dev.amenhancer.module.ui

/** Pure lifecycle decisions used by the embedded host and its JVM tests. */
internal enum class EmbeddedSettingsLifecycleAction {
    Ignore,
    Inject,
    AlreadyInjected,
}

/**
 * Tracks only opaque activity identities. It deliberately does not retain
 * Activity instances, views, or dialogs.
 */
internal class EmbeddedSettingsLifecycleState(
    private val targetActivityName: String = EmbeddedSettingsHost.PLAYER_ACTIVITY_NAME,
) {
    private val injectedActivityIds = mutableSetOf<String>()

    fun onActivityResumed(
        activityId: String,
        className: String,
        role: EmbeddedHostActivityRole? = null,
    ): EmbeddedSettingsLifecycleAction {
        val resolvedRole = role ?: when {
            className == targetActivityName -> EmbeddedHostActivityRole.Player
            EmbeddedSettingsTextPolicy.isSettingsClassName(className) -> EmbeddedHostActivityRole.Settings
            else -> null
        }
        if (resolvedRole == null) return EmbeddedSettingsLifecycleAction.Ignore
        return if (injectedActivityIds.add(activityId)) {
            EmbeddedSettingsLifecycleAction.Inject
        } else {
            EmbeddedSettingsLifecycleAction.AlreadyInjected
        }
    }

    fun onActivityDestroyed(activityId: String): Boolean = injectedActivityIds.remove(activityId)

    fun clear() {
        injectedActivityIds.clear()
    }
}

