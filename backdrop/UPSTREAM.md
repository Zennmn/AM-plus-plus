# Source provenance

AndroidLiquidGlass / Backdrop 2.0.1, by Kyant.

- Upstream: https://github.com/Kyant0/AndroidLiquidGlass
- Pinned commit: `65ab177e90e5c1d8c62e70cf7755841982da65f6`
- License: Apache-2.0; see LICENSE in this directory.
- `src/commonMain` and the original `src/androidMain` files are copied without code edits. `upstream-sha256.json` records SHA256 with CRLF normalized to LF, matching the repository's cross-platform Git checkout rules.
- `src/androidMain/.../backdrops/ViewBackdrop.kt` is an AM++ addition for a hardware-recorded Android View source.
- Build configuration targets Android only and uses the application's Java 17 baseline. Desktop, browser, Apple targets and publication tasks are not included.

Run `python scripts/verify-glass-reference.py` from the repository root to verify original rendering sources.

Every file AM++ modified, and every file AM++ added inside the upstream
packages, carries a header naming the upstream commit, the Apache-2.0 license
and the change itself, as Apache-2.0 section 4(b) requires. The pristine files
listed in `upstream-sha256.json` are left byte-identical apart from line
endings.

The seven catalog component/helper files in `glass/src/main/kotlin/com/kyant/backdrop/catalog` have the same source commit and license. AM++ changes: optional host accent and tap-only native reselection in LiquidBottomTabs/DampedDragAnimation, Android-only awaitFrame helper, and native touch entry points on InteractiveHighlight. Original shaders, springs and effect ordering are retained. NativeLiquidButton adapts LiquidButton's material and transform to content whose View/actions remain owned by Apple Music.
