# Apple Music 新版本适配手册

本文面向 AM++ 维护者，说明拿到新版 Apple Music APK 后，如何在不改变既有功能行为的前提下完成目标适配、测试和真机验收。

## 1. 适配目标

版本适配的目标是让现有功能在新的 Apple Music 内部实现上继续工作，而不是借机调整产品行为。

适配完成应满足：

- 新版的包名、`versionName` 和 `versionCode` 被精确识别。
- 每个目标符号都经过结构契约验证，不能只凭混淆类名猜测。
- 双栏播放器、Editorial Video 和双向歌词模糊独立安装、独立降级。
- Feature 层不接触 Apple Music 私有类、方法、字段或反射句柄。
- 旧版 profile 和旧版行为测试继续通过。
- 真机日志能明确显示每项能力是 `ACTIVE`、`DEGRADED`、`DISABLED`、`UNSUPPORTED` 或 `FAILED`。

适配不会自动发生。Apple Music 更新后仍需分析新版 APK；目标适配模块的作用是把分析结果集中到明确位置，并限制改动范围。

## 2. 当前架构

```text
FeatureInstallation
    │
    ├── 注册资源期 Hook，并在 Application 创建后构造 TargetAdaptation
    │
    ├── DualPaneFeature ───────────────> DualPaneTarget
    ├── EditorialVideoFeature ─────────> EditorialVideoTarget
    └── FutureLyricBlurFeature ────────> BidirectionalLyricBlurTarget
                                               │
                                               ▼
                              Apple Music 专用 adapter
                                               │
                                               ▼
                           TargetSymbolResolver / version profile
                                               │
                                               ▼
                             base APK + split APK 的 DEX 类索引
```

主要文件：

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt` | 对 Feature 暴露稳定的语义能力，并组装 Apple Music adapter |
| `app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt` | 完整拥有资源期注册、应用期安装、异常隔离、安装快照与功能健康状态上报 |
| `app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt` | 版本 profile、目标符号契约、解析顺序和 missing/ambiguous 诊断 |
| `app/src/main/java/dev/amenhancer/module/hook/ApkTargetClassSource.kt` | 合并 base/split APK 的 DEX 类名并通过目标 ClassLoader 加载类 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt` | 双栏播放器所需的全部 Apple Music 私有知识和 Hook 安装 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt` | 歌词生命周期、高亮事件和 RecyclerView 目标接入 |
| `app/src/main/java/dev/amenhancer/module/hook/OpenSourceLyricBlurPort.kt` | 与目标符号无关的歌词模糊运行策略 |

解析优先级固定为：

1. 精确版本 profile。
2. 已验证的稳定名称。
3. 结构契约回退。

结构回退只有一个候选时才能使用。零候选返回 `Missing`，多个候选返回 `Ambiguous`；禁止静默选择第一个候选。

## 3. 当前支持基线

| Apple Music | versionCode | profile ID | 状态 |
| --- | ---: | --- | --- |
| 6.5.0 | 1580 | `apple-music-6.5.0-1580` | 已通过 JVM、构建和真机验证 |

新增版本时必须保留已有 profile，除非明确停止支持旧版本并单独记录该决策。

## 4. 不得改变的行为边界

只做版本适配时，不得顺手改变：

- 功能设置的默认值和启用条件。
- Android API 版本门槛。
- Feature 安装和健康上报顺序。
- 官方平板、横屏和手机路径的判定方式。
- Hook 的 before/after 时机、返回值覆盖方式和调用顺序。
- 双栏播放器的布局、Fragment transaction 和折叠/展开语义。
- 歌词高亮 session、空 callback 保留、跨歌曲清理、模糊半径和动画时间。
- RecyclerView 的精确类型约束。
- Editorial Video 仅在官方平板横屏抑制 URL 的范围。

如果新版 Apple Music 迫使以上行为发生变化，应把它作为独立功能变更处理：先建立失败的行为测试，说明差异和风险，获得确认后再实现。不要把行为变化伪装成 profile 更新。

## 5. 新版本适配流程

### 5.1 建立基线

开始前记录当前分支和工作树，并确保旧版测试通过：

```powershell
git status --short
git branch --show-current
git diff --check
.\gradlew.bat test --no-daemon
```

若本机默认 JVM 低于 17，临时将 `JAVA_HOME` 指向 JDK 17；不要为了单次适配修改项目的 JVM 约束。

### 5.2 确认目标版本和 APK 集合

连接安装了目标版本的设备后：

```powershell
adb shell dumpsys package com.apple.android.music | Select-String 'versionName=|versionCode='
adb shell pm path com.apple.android.music
```

必须同时记录：

- 包名，正常应为 `com.apple.android.music`。
- `versionName`。
- `versionCode`。
- base APK 及全部 split APK 路径。
- 设备型号、Android 版本、分辨率和 density。

不要只分析 base APK。目标类可能位于 split APK，`ApkTargetClassSource` 也按 base/split 合并索引。

### 5.3 建立符号变化清单

先对新版 APK 做只读分析，为每个 `TargetSymbolId` 记录候选类及契约证据。当前符号包括：

| 符号 | 主要用途 | 必须核对的证据 |
| --- | --- | --- |
| `PLAYER_CONTROLLER` | 双栏播放器控制器 | 目标方法签名、根布局获取和播放器状态调用链 |
| `PLAYER_ACTIVITY` | 播放器 Activity | Activity 继承关系及真实内容根节点 |
| `EDITORIAL_VIDEO_OWNER` | Editorial Video URL | 静态方法返回 `String`，参数为 Song、float、Flavor 数组 |
| `LYRICS_FRAGMENT` | 歌词 View 生命周期 | `onCreateView`/`onDestroyView` 所属对象和布局根 |
| `LYRICS_CHROME` | 歌词工具栏及 metrics | `a2(int, int[])`、`f2()` 等现有结构契约 |
| `LYRICS_LINE_VECTOR` | 原生高亮行集合 | callback 第二参数的精确类型关系 |
| `LYRICS_EVENT_PROCESSOR` | 歌曲 session 边界 | `processEvents` 参数、返回值及 SongInfoPtr 身份 |
| `LYRICS_HIGHLIGHT_CALLBACK_OWNER` | 当前歌词高亮 | `call(long, LyricsLineVector, long): void` |
| `LYRICS_VIEW_MODEL` | callback 不可用时的 fallback | 两种现有高亮入口签名及优先级 |
| `STACKED_NAVIGATION_MENU` | 平板底部导航布局 | 真实类、测量方法和 Material 菜单结构 |

核对原则：

- 类名相同但方法契约变化，视为不兼容，不能直接复用。
- 混淆名变化但契约唯一，可以加入新 profile。
- 结构回退出现多个候选，必须收紧契约；不能按 DEX 顺序选择。
- 记录调用路径，不只记录反编译后的单个方法。
- 目标方法的参数、返回值、静态性和 owner 都应进入测试证据。

### 5.4 添加精确 profile

在 `TargetSymbols.kt` 中新增独立 `AppleMusicProfile`：

```kotlin
private val appleMusicNewVersion = AppleMusicProfile(
    id = "apple-music-X.Y.Z-CODE",
    exactClasses = mapOf(
        TargetSymbolId.PLAYER_CONTROLLER to "新版完整类名",
        // 只填写已验证的符号
    ),
)
```

随后扩展 `AppleMusicProfiles.match(build)`，必须同时匹配：

- 目标包名。
- 精确 `versionName`。
- 精确 `versionCode`。

不要只匹配 versionCode，也不要使用宽泛版本范围。profile 中不能填写未经验证、只是“看起来相似”的类名。

### 5.5 判断是否需要修改 adapter

仅类名变化、结构契约不变时，通常只需新增 profile 和测试。

以下情况才修改对应 Apple Music adapter：

- 方法签名、字段归属或构造方式变化。
- 生命周期入口变化。
- 原有调用顺序在新版上不再成立。
- 目标布局、资源 ID 或 Fragment transaction 类型变化。

修改范围应局限在对应能力：

- 双栏问题只修改 `AppleMusicDualPaneTarget.kt`。
- 歌词目标接入问题只修改 `AppleMusicBidirectionalLyricBlurTarget.kt`。
- Editorial Video 目标问题只修改 `AppleMusicEditorialVideoTarget`。

不要让 `DualPaneFeature.kt`、`EditorialVideoFeature.kt` 或 `FutureLyricBlurFeature.kt` 重新接触 `Class`、`Method`、`Field`、`TargetResolution` 或 `AppleMusicSymbols`。

### 5.6 测试要求

至少补充以下测试：

1. `TargetSymbolsTest`
   - 新 profile 能直接命中验证过的符号。
   - 命中精确 profile 时不需要枚举全部 DEX 类名。
   - 版本名或 versionCode 不匹配时不能误用 profile。
   - 缺失和歧义继续显式报告。
2. `TargetAdaptationBehaviorTest`
   - 各能力结果仍映射为既有 Feature 状态。
   - 一个能力失败不会调用或阻断其他能力。
3. 对应能力测试
   - 双栏：`DualPaneTargetBehaviorTest` 和 `DualPaneStructuralRegressionTest`。
   - 歌词：`LyricHighlightEventRouterTest`、`LyricHighlightSessionTest`、`FutureLyricBlurStructuralRegressionTest`。
   - Editorial：`EditorialVideoFeatureStructuralRegressionTest`。
4. 若 adapter 调用契约变化，先建立能够在旧实现上失败的行为或接线测试。

禁止只用“源码包含某个新混淆类名”的字符串测试代替行为测试。结构测试只保留无法在 JVM 中直接执行、但对接线边界必要的约束。

### 5.7 自动验证

Windows：

```powershell
.\gradlew.bat test assembleDebug assembleRelease lintVitalRelease --no-daemon
git diff --check
```

提交前还应扫描 Feature 层是否重新泄漏目标私有知识。检查范围至少包括所有 `*Feature.kt` 和 `OpenSourceLyricBlurPort.kt`，关注：

```text
TargetResolution
TargetSymbolResolver
AppleMusicSymbols
java.lang.reflect
Class / Method / Field
callMethod / findField
```

扫描命中后应人工判断；目标私有知识原则上只能存在于目标符号模块和 Apple Music adapter 中。

### 5.8 同签名真机 QA

安装前先核对设备现有模块 APK 和 QA APK 的证书指纹。只有包名及证书一致时才使用覆盖安装：

```powershell
adb shell pm path dev.amenhancer.module
apksigner verify --print-certs <设备现有模块APK>
apksigner verify --print-certs <QA APK>
adb install -r <QA APK>
```

模块更新后只需强停并重开 Apple Music，不需要重启设备：

```powershell
adb logcat -c
adb shell am force-stop com.apple.android.music
adb shell monkey -p com.apple.android.music -c android.intent.category.LAUNCHER 1
adb logcat -d -b all -v threadtime
```

不要修改用户功能设置来制造通过结果。使用升级前已经记录的设置组合验证行为保持。

## 6. 真机验收矩阵

| 能力 | 日志要求 | 最低视觉/交互要求 |
| --- | --- | --- |
| 双栏播放器 | `dual_pane: ACTIVE` 且带新版 profile ID | 平板横屏播放器展开/收起正常，右侧歌词 pane 正常，底部导航无闪烁或错位 |
| Editorial Video | `editorial_video: ACTIVE` | 官方平板横屏抑制 Editorial Video URL；静态封面和 Music Video 路径不受影响 |
| 双向歌词模糊 | `future_blur: ACTIVE` 且 callback/session 符号命中 | 当前句、相邻句模糊、跨歌曲清理、手动滚动恢复和生命周期清理正常 |
| 独立降级 | 故障能力为 `DEGRADED`，其他能力仍按自身结果上报 | 单项缺失不得导致 Apple Music 启动失败或其他功能失效 |

每次验收至少记录：

- Apple Music 版本和设备信息。
- 模块版本、APK SHA-256 和签名证书 SHA-256。
- 三项能力的完整健康日志。
- `FATAL EXCEPTION`、Apple Music ANR 和 AM++ `FAILED` 的检索结果。
- 涉及界面功能时的截图或录屏。

## 7. 常见失败及处理

### `resolved via structural_fallback`

未知版本上回退成功只能作为探测结果，不能直接宣称正式支持。应验证候选契约并添加精确 profile。

### `was not found`

检查新版类是否移至 split APK、owner 是否变化、方法是否改名或参数类型是否变化。不要立即放宽到全包模糊匹配。

### `was ambiguous`

说明结构契约不够深。加入能表达真实职责的参数、返回值、静态性、继承关系或调用路径条件，直到候选唯一。

### 能力显示 `ACTIVE` 但界面不生效

`ACTIVE` 只证明 Hook 安装完成。继续检查目标生命周期是否实际触发、布局资源是否变化、对象是否属于当前 Activity/Fragment session，以及 Hook 时机是否仍正确。

### Apple Music 启动崩溃

立即检查本次 adapter 的反射、强制类型转换、参数数组和返回值覆盖。不要用捕获所有异常后继续运行来掩盖错误；能力应明确 `DEGRADED` 或 `FAILED`。

## 8. 新版本适配记录模板

```markdown
## Apple Music X.Y.Z / versionCode

- Profile ID：`apple-music-X.Y.Z-CODE`
- APK：base + split 清单
- 设备：型号 / Android / 分辨率 / density
- 与上一版本相比的符号变化：
  - PLAYER_CONTROLLER：
  - EDITORIAL_VIDEO_OWNER：
  - LYRICS_FRAGMENT：
  - LYRICS_HIGHLIGHT_CALLBACK_OWNER：
  - 其他：
- Adapter 行为变化：无 / 详细说明
- JVM：通过/失败
- Debug/Release/lint：通过/失败
- 真机健康状态：
  - dual_pane：
  - editorial_video：
  - future_blur：
- FATAL/ANR：
- 截图或录屏：
- 已知限制：
```

## 9. 完成定义

只有以下条件全部满足，才能把新版标记为支持：

- 精确版本 profile 已加入并有正反匹配测试。
- 所有目标符号有可复核的类名和结构契约证据。
- Feature 层没有新增 Apple Music 私有知识。
- 旧版及新版相关行为测试全部通过。
- Debug、Release、lint 和 `git diff --check` 通过。
- 同签名真机覆盖 QA 通过。
- 三项能力独立上报，且没有 Apple Music FATAL/ANR。
- 适配记录包含版本、签名、日志和视觉证据。
