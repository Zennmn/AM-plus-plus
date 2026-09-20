package com.apple.android.music.player.fragment;

/**
 * Stand-in for the Apple Music 6.5.2 (1586) player controller's pane state enum
 * (com.apple.android.music.player.fragment.t0$n), verified from the host DEX: the fragment comes
 * from f()Lcom/apple/android/music/common/fragment/a; and the fragment tag from
 * g()Ljava/lang/String;.
 */
public final class t0 {
    private t0() {
    }

    public enum n {
        SONG("song"),
        LYRICS("lyrics");

        private final String tag;

        n(String tag) {
            this.tag = tag;
        }

        public Object f() {
            return this;
        }

        public String g() {
            return tag;
        }
    }
}
