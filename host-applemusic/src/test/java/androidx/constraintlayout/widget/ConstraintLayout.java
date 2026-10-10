package androidx.constraintlayout.widget;

import android.view.ViewGroup;

/** The 1606 host's obfuscated params; its generic constructor does not copy constraints. */
public final class ConstraintLayout {
    public static class b extends ViewGroup.MarginLayoutParams {
        public int a = -1; // guideBegin
        public int t = -1; // startToStart
        public int i = -1; // topToTop
        public int j = -1; // topToBottom
        public int k = -1; // bottomToTop
        public int l = -1; // bottomToBottom
        public int M = 0; // matchConstraintDefaultHeight
        public float S = 1f; // matchConstraintPercentHeight

        public b(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}
