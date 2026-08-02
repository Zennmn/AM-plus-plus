package com.apple.android.music.player.fragment

import com.apple.android.music.model.BaseContentItem

class e {
    class c
}

/**
 * JVM stand-ins for the player fragment hierarchy that owns the current
 * lyrics item field (`c`). `m` mirrors the verified 6.5.0/1580 owner with the
 * exact public non-static field of type `BaseContentItem`; `n` is a
 * same-contract neighbor used for ambiguity fixtures; the `m*` variants must
 * never satisfy the field contract.
 */
class m {
    @JvmField
    var c: BaseContentItem = BaseContentItem()
}

class n {
    @JvmField
    var c: BaseContentItem = BaseContentItem()
}

/** Same field name but static — must never satisfy the field contract. */
class mStatic {
    companion object {
        @JvmField
        var c: BaseContentItem? = null
    }
}

/** Same field name but wrong type — must never satisfy the field contract. */
class mWrongType {
    @JvmField
    var c: String = ""
}
