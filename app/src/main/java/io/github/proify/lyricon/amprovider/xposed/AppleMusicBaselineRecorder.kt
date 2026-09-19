package io.github.proify.lyricon.amprovider.xposed

import android.content.SharedPreferences

/** Keeps structural reflection/encoding off repeated bindings, not just disk I/O. */
internal class AppleMusicBaselineRecorder(
    private val preferences: () -> SharedPreferences,
) {
    private data class Identity(
        val hookPoint: AppleMusicHookPoint,
        val baselineClassName: String,
        val runtimeClass: Class<*>,
        val members: Map<AppleMusicRuntimeMember, String>,
    )

    private val recorded = HashSet<Identity>()

    fun record(
        hookPoint: AppleMusicHookPoint,
        target: AppleMusicHookTarget,
        runtimeClass: Class<*>,
        baselineClassName: String,
        entries: () -> Map<String, String>,
    ) {
        val identity = Identity(hookPoint, baselineClassName, runtimeClass, target.runtimeMemberNames.toMap())
        synchronized(recorded) {
            if (identity in recorded) return
            val values = entries()
            val store = preferences()
            val changed = values.filter { (key, value) -> store.getString(key, null) != value }
            if (changed.isNotEmpty()) {
                val editor = store.edit()
                changed.forEach { (key, value) -> editor.putString(key, value) }
                editor.apply()
            }
            // Failed encoding or submission must retry on the next call. The recorder is
            // instance-scoped, so a new process/host APK records and validates its own shape.
            recorded.add(identity)
        }
    }
}
