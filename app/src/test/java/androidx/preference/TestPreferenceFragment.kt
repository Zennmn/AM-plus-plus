package androidx.preference

open class TestPreferenceFragment {
    fun setPreferences(resourceId: Int) = Unit
}

open class AmbiguousPreferenceFragment {
    fun first(resourceId: Int) = Unit
    fun second(resourceId: Int) = Unit
}
