package com.apple.android.music.player.fragment;

/**
 * Stand-in for the Apple Music 6.5.3 (1599) pane state enum
 * (com.apple.android.music.player.fragment.v0$n), verified from the host DEX: the fragment accessor
 * was renamed to e()Lcom/apple/android/music/common/fragment/a;, the tag accessor kept
 * g()Ljava/lang/String;, and the widget helper i(v0$o) takes an argument.
 */
public final class v0 {
    private v0() {
    }

    public enum n {
        SONG("song"),
        LYRICS("lyrics");

        private final String tag;

        n(String tag) {
            this.tag = tag;
        }

        public Object e() {
            return this;
        }

        public String g() {
            return tag;
        }

        public String i(Object widget) {
            return widget == null ? null : tag;
        }
    }
}
