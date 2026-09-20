package com.apple.android.music.player.fragment;

/**
 * A future host shape with two no-argument fragment accessors. The dual-pane attachment must
 * report the shape as unusable instead of picking one of the two by declaration order.
 */
public final class x0 {
    private x0() {
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

        public Object e() {
            return this;
        }

        public String g() {
            return tag;
        }
    }
}
