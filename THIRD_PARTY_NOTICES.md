# Third-Party Notices

## AM++ media plugin tablet components

Selected output-routing, output icons, volume controls, and native playback-mode
contracts are adapted from Kifranei/AM-plus-plus-media-plugin, commit `9e3f72b`
(GPL-3.0). The host retains its own tablet proportions and artwork layout.
The Halcyon Bluetooth name rules and Kifranei output vectors are retained.
Flamingo volume vectors and their source record are packaged under `tablet-media/`.
Apple's speaker/AirPods images retain their source and copyright notice in
`tablet-media/AppleMusicOutputIcons.txt`; they are not covered by GPL.

## AMLyricBlur

This project includes code adapted from `a23bc/amlyricblur`, commit
`3417e217d7692ae742bbae80d2bd51aadffcd59e`.

MIT License

Copyright (c) 2026 a23bc

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## AndroidLiquidGlass / Backdrop

Includes Backdrop 2.0.1 and catalog components from Kyant0/AndroidLiquidGlass,
commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`.

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
Full license: `backdrop/LICENSE`; also packaged as `assets/licenses/AndroidLiquidGlass.txt`.
Source provenance and AM++ changes: `backdrop/UPSTREAM.md`.

Compose Multiplatform 1.12.0 and io.github.kyant0:shapes:1.2.1 are Apache-2.0 dependencies.

## HyperLyrics-Enhanced cellular data settings

The cellular data settings scope and hook behavior in
`host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCellularDataEntryTarget.kt`
are adapted from HyperLyrics-Enhanced's `AppleCellularDataSettingsHooks.kt`,
source snapshot `38cf511`.

Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
AM++ adds its own configuration, exact target profiles, capability installation,
and a gate that keeps partially registered hooks dormant.

License: https://www.apache.org/licenses/LICENSE-2.0

## libxposed API and service

This project uses `io.github.libxposed:api:102.0.0` and
`io.github.libxposed:service:102.0.0`.

Licensed under the Apache License, Version 2.0. The license is available at:

    https://www.apache.org/licenses/LICENSE-2.0

## Kotlin standard library

This project uses `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`.

Licensed under the Apache License, Version 2.0. The license is available at:

    https://www.apache.org/licenses/LICENSE-2.0
