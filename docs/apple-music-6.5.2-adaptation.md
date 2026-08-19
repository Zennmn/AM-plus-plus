# Apple Music 6.5.2/1586 适配实战记录

本文记录 AM++ 对 Apple Music 6.5.2 的一次完整适配，目标是把“拿到 APK 后如何确认版本、建立符号档案、接入设置页、修复平板横屏双栏播放器，以及如何验收”固化成可复用流程。

这不是对 Apple Music 私有实现的永久承诺。私有类、混淆方法和资源 ID 只对本文记录的构建有效；适配其他版本时必须重新取证、重新解析并重新验证。

## 1. 适配对象和结论

本次输入文件为 `Apple+Music_6.5.2_APKPure.xapk`。解包并安装到测试环境后确认：

| 项目 | 值 |
| --- | --- |
| 包名 | `com.apple.android.music` |
| `versionName` | `6.5.2` |
| `versionCode` | `1586` |
| 包类型 | XAPK，包含 base、arm64 和 mdpi split |
| XAPK SHA-256 | `96A70F0B724F6196C9F2B356986D9C93A1CF6BC9FDD555C1C96AABA5B4783C1C` |
| base APK SHA-256 | `1E7151D02CAC39A9F70D017BCC26E3B0FD4C2AFB3AA30C363206D8616A4FEC59` |

PR 标题或文件名不能作为版本依据。本次最初把 6.5.2 误写成 1590，后续通过 `dumpsys package` 和 APK 元数据纠正为 1586；版本档案必须以实际安装包为准。

适配后的关键结论如下：

1. 设置页仍然嵌入 Apple Music 原生 Settings 页面，不能假设模块有可启动的 launcher Activity。
2. 平板横屏双栏播放器继续让 Apple Music 的原生 holder、peek 和过渡动画拥有生命周期；AM++ 只修正目标 View tree 和边界几何。
3. 6.5.2 的 flat root 不能直接保留原生 flat holder。对已经转换成双栏布局的 root，必须通过 `k1()` before-hook 返回官方 `StackedBottomNavigationHolder`，否则 mini-player 生命周期和底部栏状态会丢失。
4. bottom navigation 的重叠判断使用完整 tabs frame 高度，但播放器的 settled translation 只使用 navigation inset，不能把整个 tabs frame 高度直接当作平移量。
5. `StaticCollapsedBottomSheetBehavior.h()` 是独立、可降级的 6.5.2 适配点；不能对该 Behavior 的所有实例无条件返回 `false`，也不能吞掉安装失败。

## 2. 从 APK 建立版本档案

### 2.1 先锁定构建身份

建议把以下证据保存到适配记录或 PR：原始文件名、XAPK/base/split 的 SHA-256、包名、versionName、versionCode、签名证书摘要，以及测试设备的 Android 版本和 ABI。

安装后可用 ADB 复核：

```powershell
adb shell dumpsys package com.apple.android.music |
  Select-String 'versionName=|versionCode='
```

不要只根据 PR 标题、商店页面或 APK 文件名写 `TargetBuild`。版本 code 错一个数字，就可能把错误的私有符号档案应用到真实进程。

### 2.2 记录可复用的符号表

6.5.2/1586 的主要符号如下。完整解析仍以 `TargetSymbols.kt` 中的 resolver 为准；下表用于审查和重新取证时快速定位。

| 功能 | owner / 类 | 方法或字段 | 适配说明 |
| --- | --- | --- | --- |
| 双栏播放器控制器 | `com.apple.android.music.player.fragment.t0` | `w1`、`onCreateView`、`F1` | 在控制器生命周期内接入 SONG 和 LYRICS fragment |
| editorial owner | `...player.f1` | profile 中记录的方法 | 只在结构证据匹配时使用 |
| lyrics chrome | `...fragment.e` | `a2(int,int[])` | 用于抑制重复 chrome |
| current item | `...fragment.m` | profile 中记录的方法 | 保留原始对象语义 |
| metadata converter | `...player.O` | profile 中记录的方法 | 只做目标字段修正 |
| availability | `...player.e1` | profile 中记录的方法 | 失败时 fail-open |
| PlayerActivity holder | `PlayerActivity` | `k1()` | 双栏 flat root 必须返回官方 `StackedBottomNavigationHolder` |
| PlayerActivity root | `PlayerActivity` | `n0()`（可继承） | 用于确认转换后的 root |
| PlayerActivity behavior | `PlayerActivity` | `c1` | 用于建立原生 bottom-sheet 关系 |
| 设置页 | `...settings.fragment.SettingsFragment` | 继承 `...settings.fragment.o` 的 `r1(int):void` | AndroidX Preference 的 6.5.2 接线点 |
| 设置页 fallback | 同上 | `t0(String)` | 用于按 key 获取 preference |
| Static collapsed behavior | `...common.behavior.StaticCollapsedBottomSheetBehavior` | `h(CoordinatorLayout, View, MotionEvent):Boolean` | 仅 6.5.2/1586，独立 resolver |

本次确认的资源 ID 也要写入档案，而不是散落在业务代码注释中：

| 资源名 | 6.5.2/1586 ID |
| --- | --- |
| `bottom_navigation_root_flat` | `0x7f0a0161` |
| `bottom_navigation_tabs_frame` | `0x7f0a0163` |
| `player_container` | `0x7f0a08a2` |
| `player_sheet_container` | `0x7f0a08aa` |
| `player_lyrics` | `0x7f0a08a6` |
| `player_queue` | `0x7f0a08a8` |

### 2.3 Resolver 规则

- 每个私有类、方法和字段都要独立解析，返回 `Found`、`Missing` 或 `Ambiguous`；不要使用 `declaredMethods.firstOrNull { name == "h" }` 之类的静默首候选。
- owner、参数类型、返回类型和结构特征必须同时记录。混淆方法名本身不是证据。
- 已验证的版本应该有 exact profile；结构 fallback 只能用于明确记录过的兼容范围。
- 6.5.2 的 `StaticCollapsedBottomSheetBehavior.h` 不要伪装成 6.5.0/6.5.1 的通用 profile 符号。它是条件性的独立能力，缺失时必须能单独降级。

## 3. 设置页适配

### 3.1 为什么“模块没有设置入口”不一定是作用域问题

当前独立模块的主 manifest 没有 launcher Activity。设置页由 `HookEntry` 在 Apple Music 原生 SettingsFragment 中注入，因此验证入口是：

> Apple Music → 更多 → 设置 → 页面顶部的“AM++ 模块设置”

本次 6.5.2 的症状是 Hook 已加载但设置入口不显示。根因不是作用域，而是 `EmbeddedBootstrap` 只允许 6.5.1/1583，遇到 6.5.2/1586 直接走 unsupported 分支。修复时必须同时更新：

- `TargetBuild` 的 exact package/versionCode 映射；
- `EmbeddedBootstrap` 的允许构建集合；
- 启动日志中的 expected versions；
- SettingsFragment 的 owner/superclass/method profile。

只更新 `TargetSymbols` 而忘记 bootstrap，会产生“符号能解析、设置仍然消失”的假成功。

### 3.2 设置页接线检查

6.5.2 的 SettingsFragment 继承链为：

```text
SettingsFragment → com.apple.android.music.settings.fragment.o → androidx.preference.b
```

优先使用继承的 `r1(int):void` 完成 preference 初始化，`t0(String)` 作为按 key 获取的 fallback。6.5.2 的 AndroidX Preference 混淆成员包括 `K`（title）、`J`（summary）、`P`（add）、`S`（remove）和 `x`（key）。这些成员也必须通过结构或 profile 证据确认。

验收时不要只看日志：重启 Apple Music 后从原生设置页进入，确认“AM++ 模块设置”可见、可打开、修改后能保存，并确认没有为模块额外创建错误的 launcher 入口。

## 4. 双栏播放器适配契约

### 4.1 Hook 时序

资源和布局注册必须在目标 Application 的 `onCreate` before-hook 完成，确保 Apple Music 后续 inflate 时能看到 `bottom_navigation_*` 等资源。功能安装、fragment 接线和设置桥接放在 after-hook；所有阶段要幂等，失败不能发布半初始化状态。

### 4.2 holder 的语义边界

6.5.2 原生 `PlayerActivity.k1()` 在发现 `bottom_navigation_root_flat` 时会构造 `FlatBottomNavigationHolder`。这个行为对普通页面可能正确，但对 AM++ 已转换的 flat root 会让 mini-player 不再走原生 stacked 生命周期，表现为底部栏异常、mini-player 消失或播放器状态不再同步。

因此 holder hook 的判断顺序应是：

1. 确认当前 root 是 AM++ 转换过的目标 root；
2. 确认当前版本是已取证的 6.5.2/1586；
3. 返回 Apple Music 官方 `StackedBottomNavigationHolder`；
4. 其他 root 和其他版本继续调用原始实现。

不要把“任何地方存在 `bottom_navigation_root_flat`”当成通用条件，也不要为了保留 flat holder 而提前 return。holder 的选择属于行为契约，必须有运行时回归，而不只是 `source.contains` 测试。

### 4.3 布局变换和原生所有权

AM++ 负责把目标 bottom navigation 变成完整宽度的 tabs frame，并设置：

- menu height：设备密度对应的 56dp；
- stacked tabs container：`menuHeight + 2 × 8dp`；
- player container：铺满可用区域，`bottomMargin = 0`；
- 不在模块侧设置 mini-player 的永久 visibility、alpha 或动画。

播放器的 peek、展开/收回动画和最终 holder 状态继续由原生 `StackedBottomNavigationHolder` 管理。模块侧只提供 root、fragment 和边界所需的几何条件。

### 4.4 边界计算：检测高度和位移不是同一个量

设：

```text
tabsHeight      = tabs frame 的完整高度
menuHeight      = 原生底栏菜单高度
navigationInset = tabsHeight - menuHeight
```

边界策略必须使用完整 `tabsHeight` 判断播放器是否与 tabs frame 重叠；settled collapsed 状态的平移只使用 `-navigationInset`。本次错误版本把 `-tabsHeight` 直接作为 translation，导致 mini-player 被底栏整体推入错误位置；修复后重新分离了 overlap geometry 和 navigation inset。

这条规则要由单元测试锁定：

- overlap 使用完整 tabs frame 高度；
- settled translation 使用 `-navigationInset`；
- 不额外修改 layout margin 或 bottom-sheet native boundary。

## 5. StaticCollapsedInterceptGuard 的最小作用域

### 5.1 为什么全局返回 false 会出问题

`CoordinatorLayout.Behavior.onInterceptTouchEvent()` 决定 Behavior 是否接管一串触摸事件。对 `StaticCollapsedBottomSheetBehavior` 的所有实例都强制 `param.result = false`，会影响与播放器无关的 CoordinatorLayout，也可能使原本应该接管 `ACTION_DOWN` 的拖拽永远进不了 `onTouchEvent()`。

因此“其他区域仍会回到 onTouchEvent”不能作为全局保证。正确做法是只在已确认的歌词/队列按钮误拦截区域绕过 interception，其余事件调用 Apple 原始 `h()`。

### 5.2 推荐的运行时策略

Guard 至少同时满足以下条件才 bypass：

- exact build 为 6.5.2/1586；
- 官方平板、横屏、`dualPaneEnabled` 和 `navigationCompensationEnabled` 均开启；
- `param.args[0]` 是目标 CoordinatorLayout；
- `param.args[1]` 是 AM++ 转换后的 player sheet child，并且位于 `player_sheet_container` / `bottom_navigation_root_flat` 目标树下；
- MotionEvent 命中可见的 `player_lyrics` 或 `player_queue` 区域；
- 对同一个 behavior 建立 DOWN→UP/CANCEL 的短暂 gesture latch，避免只放行 DOWN 后又被原生逻辑抢回。

建议把判断拆成纯函数，例如：

```kotlin
internal object StaticCollapsedInterceptPolicy {
    fun shouldBypass(
        tabletEligible: Boolean,
        targetCoordinator: Boolean,
        targetChild: Boolean,
        inPlayerButtonRegion: Boolean,
    ): Boolean = tabletEligible && targetCoordinator && targetChild && inPlayerButtonRegion
}
```

反射安装失败、方法缺失或签名歧义必须进入 `DEGRADED`/`FAILED`，不能忽略 `install()` 返回的 Boolean 后仍报告 `ACTIVE`。Guard 是独立能力，不应让整个目标 feature 崩溃，但也不能把能力缺失伪装成成功。

## 6. 这次踩过的坑

| 错误做法 | 现象 | 修复原则 |
| --- | --- | --- |
| 把版本 code 写成 1590 | profile/日志与真实 APK 不一致 | 以安装后的 `dumpsys package` 和 APK 元数据为准 |
| 只更新 `TargetSymbols`，不更新 bootstrap | Hook 看似加载，设置入口消失 | exact build 必须贯穿 bootstrap、profile 和日志 |
| 发现 flat root 就保留 native flat holder | mini-player 消失、底部栏状态异常 | 转换后的 root 返回官方 stacked holder |
| collapsed translation 使用 `-tabsHeight` | 播放器被底栏整体推错 | overlap 用 tabsHeight，位移用 navigationInset |
| 对整个 Behavior 类永久返回 false | 无关区域和拖拽手势可能被破坏 | 目标树 + 目标区域 + gesture latch 的最小 bypass |
| `firstOrNull` 猜混淆方法 | 版本变化后可能误 hook | owner/signature/结构证据 + Found/Missing/Ambiguous |
| 忽略 guard 安装结果 | 日志仍是 ACTIVE，实际修复没安装 | 独立能力健康状态必须可见 |
| 只写 source-string 测试 | 编译通过但 holder/触摸行为仍可能反 | 加入 policy、runtime seam 和设备验收 |

## 7. 测试和验收清单

### 7.1 JVM/静态回归

本次修复先用 RED 测试固定行为，再实现 GREEN：

- `DualPaneStructuralRegressionTest`：转换后的 flat root 使用 `StackedBottomNavigationHolder`，不能有无条件 flat-holder early return；
- `FlatPlayerBoundaryPolicyTest`：完整 tabs frame 参与 overlap 判断，但 settled translation 等于 `-navigationInset`；
- `StaticCollapsedInterceptPolicyTest`（后续维护应补齐）：portrait、dual-pane 关闭、补偿开关关闭、无关 coordinator、无关 child 和非按钮区域都必须放行原始 `h()`；
- 设置 bootstrap/profile 测试：6.5.2/1586 进入 supported，其他版本仍 fail-open。

建议至少运行：

```powershell
./gradlew.bat testDebugUnitTest --no-daemon
./gradlew.bat lintDebug lintVitalRelease assembleDebug assembleRelease --no-daemon
git diff --check
```

### 7.2 设备验收

设备测试必须在准确的宿主版本上进行：

```powershell
adb shell dumpsys package com.apple.android.music |
  Select-String 'versionName=|versionCode='
adb shell uiautomator dump /sdcard/ampp-652.xml
adb pull /sdcard/ampp-652.xml .work/ampp-652.xml
```

至少检查：

1. Apple Music 原生设置页能打开“AM++ 模块设置”，修改后重启仍保留；
2. 平板横屏进入播放器后，底部 tabs 完整可见，mini-player 存在；
3. 展开、收回、切换 SONG/LYRICS、点击歌词/队列按钮和拖拽播放器均正常；
4. portrait、dual-pane 关闭、补偿开关关闭时，Guard 不改变原生 Behavior；
5. logcat 中能区分 profile resolution、holder hook、boundary hook 和 guard 的 `ACTIVE/DEGRADED/FAILED`；
6. 保存同一设备、同一版本的截图或录屏，作为 PR 证据。

本次代码修复后的 JVM、Lint、Debug/Release 构建已经通过；由于最终设备在修复后未保持连接，mini-player 和触摸 Guard 的最终运行时验收仍应由适配者在目标平板上完成，不能用编译结果替代设备证据。

## 8. 以后适配新版本的最短流程

1. 保存原始 APK/XAPK 和所有 SHA-256，安装后确认 package/versionName/versionCode。
2. 复制上一版 profile，逐个重新确认 owner、参数、返回值、继承链和资源 ID；不要批量沿用混淆方法名。
3. 先恢复设置入口，再做播放器 UI；设置 bootstrap 不通过时，后续 UI 观察没有意义。
4. 为 holder、boundary、touch guard 分别写行为测试；涉及私有类时给出 Found/Missing/Ambiguous 结果和健康状态。
5. 用 RED→GREEN 顺序修复，先验证原生 holder/动画所有权，再调整几何量。
6. 完成全量 JVM、lint、Debug/Release 和 `git diff --check`，再在准确宿主版本上做设备回归。
7. 在 PR 中附上版本证据、设备信息、日志和截图/录屏，并明确哪些结论仍未在设备上验证。

## 9. 相关代码入口

- [`TargetSymbols.kt`](../app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt)：版本 profile 和符号解析。
- [`AppleMusicDualPaneTarget.kt`](../app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt)：双栏播放器、holder、布局和边界 hook。
- [`StaticCollapsedInterceptGuard.kt`](../app/src/main/java/dev/amenhancer/module/hook/StaticCollapsedInterceptGuard.kt)：6.5.2 触摸 interception 适配点。
- [`EmbeddedBootstrap.kt`](../app/src/main/java/dev/amenhancer/module/hook/EmbeddedBootstrap.kt)：嵌入设置的 exact build gate。
- [`HookEntry.kt`](../app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt)：宿主生命周期和资源注册时序。
- [`DualPaneStructuralRegressionTest.kt`](../app/src/test/java/dev/amenhancer/module/hook/DualPaneStructuralRegressionTest.kt)：holder/布局结构回归。
- [`FlatPlayerBoundaryPolicyTest.kt`](../app/src/test/java/dev/amenhancer/module/hook/FlatPlayerBoundaryPolicyTest.kt)：边界几何回归。
