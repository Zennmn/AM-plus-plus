# Apple Music 新版本适配手册

本文面向 AM++ 维护者，描述 Apple Music 目标适配层的当前代码契约和新版本分析方法。当前版本软件完全可用；适配的目标是让既有能力在新版 Apple Music 内部实现上继续工作，不是借版本更新改变产品行为。

本文以仓库当前源码为准。源码中的类名、方法名、字段名和资源名是适配事实；本手册不能替代对新版 APK 的重新分析。

## 1. 适配契约

### 1.1 术语

- **目标符号**：Apple Music 内部被 AM++ 定位并使用的一个类、方法或字段。每个符号必须能独立得到 `Found`、`Missing` 或 `Ambiguous` 结果，禁止静默使用第一个候选。
- **版本 profile**：针对一个精确的 Apple Music `packageName + versionName + versionCode` 三元组记录的适配知识。profile 不是跨版本猜测规则。
- **目标适配**：把私有符号解析和 Hook 安装转换成某一项稳定的语义能力。Feature 只依赖这个能力，不接触 Apple Music 私有反射对象。
- **配置 schema**：设置进程和 Apple Music 目标进程共同使用的键名、默认值、编码、迁移和远程文件指针规则。规则只能由 `ModuleSettingsSchema` 提供。
- **双向歌词模糊**：当前高亮歌词保持清晰，历史歌词和后续歌词按距离逐渐模糊。
- **滚动暂停**：用户手动浏览歌词时暂时移除焦点相关模糊，滚动稳定后恢复当前高亮位置对应的双向视觉状态。它是 `future_blur` 的运行时行为，不是独立 feature。

### 1.2 不变行为

只做版本适配时，不得顺手改变：

- 功能开关的默认值、启用条件和 `FeatureState` 语义。
- Android API 门槛以及官方平板、横屏、手机资格判定。
- 资源期注册、`Application.onCreate` 安装、异常隔离和健康上报顺序。
- Hook 的 before/after 时机、参数替换、返回值覆盖和调用顺序。
- 双栏播放器的布局、Fragment transaction、折叠/展开和边界补偿语义。
- 双向歌词模糊的 session、焦点路由、跨歌曲清理、滚动暂停和恢复时序。
- 歌词目标的精确 `RecyclerView` 类型约束。
- Editorial Video 只在官方平板横屏抑制 URL 的范围。
- 配置 schema 的键名、默认值、编码和升级规则。

如果新版迫使上述行为发生变化，应先建立独立的行为变更说明，不要把行为变化伪装成 profile 更新。

## 2. 当前运行架构

### 2.1 两阶段安装

```text
HookEntry.onPackageReady
    │  包名、首次 package、libxposed API 102 和 remote capability 门控
    ▼
FeatureInstallation.install
    ├─ 注册各 feature 的资源期回调
    ├─ LayoutInflationRegistry hook LayoutInflater.inflate 两个重载
    └─ hook Application.onCreate
             │
             ▼
        TargetAdaptation.appleMusic
             ├─ targetBuild(application)
             ├─ ApkTargetClassSource(base APK + split APK)
             ├─ 一个共享 IndexedTargetSymbolResolver
             └─ 组装 6 个目标能力
             │
             ▼
        FeatureInstallationSession
             ├─ 按固定顺序逐个 installSafely
             ├─ 每项独立生成 FeatureHealth
             └─ 更新 RESOURCES_REGISTERED → FEATURES_INSTALLING → COMPLETE
```

`HookEntry.kt:20-35` 在目标包 `com.apple.android.music` 的首次 package 回调中工作。框架低于 API 102 或不提供 `PROP_CAP_REMOTE` 时，目标 Hook 全部保持禁用状态。

资源阶段在 `FeatureInstallationModule.install` 中先执行，目标进程的 `Application.onCreate` 之后才创建 `TargetAdaptation` 并安装目标符号 Hook。不能把需要目标类实例或版本信息的操作提前到资源阶段。

### 2.2 Feature 与目标能力的边界

`TargetAdaptation.kt:12-56` 暴露 6 个 Apple Music 目标能力：

| 目标能力 | 语义接口 | 目标 adapter |
| --- | --- | --- |
| 双栏播放器 | `DualPaneTarget` | `AppleMusicDualPaneTarget` |
| Editorial Video | `EditorialVideoTarget` | `AppleMusicEditorialVideoTarget`，内联在 `TargetAdaptation.kt` |
| 双向歌词模糊 | `BidirectionalLyricBlurTarget` | `AppleMusicBidirectionalLyricBlurTarget` |
| 歌词字体 | `LyricsTypefaceTarget` | `AppleMusicLyricsTypefaceTarget` |
| 自定义歌词 | `CustomLyricsTarget` | `AppleMusicCustomLyricsTarget` |
| 当前歌曲身份 | `CurrentSongIdentityTarget` | `AppleMusicCurrentSongIdentityTarget` |

`PhoneLiquidGlassFeature` 没有目标符号能力接口。它是资源期 feature，不能把它写成已有的 `TargetAdaptation` 能力。

### 2.3 Feature 安装顺序和健康状态

`FeatureInstallation.kt:199-219` 固定注册 7 个 feature，顺序为：

1. `dual_pane`
2. `editorial_video`
3. `phone_liquid_glass`
4. `future_blur`
5. `lyrics_typeface`
6. `current_song_identity`
7. `custom_lyrics`

每个 feature 都经过 `FeatureHook.installSafely`。返回状态为 `ACTIVE`、`DISABLED`、`UNSUPPORTED`、`DEGRADED` 或 `FAILED`；一个 feature 的异常会被记录并转换为 `FAILED`，不会阻断后续 feature。目标 adapter 自身只返回 `TargetCapabilityInstall.Active` 或 `Degraded`，再由 `toFeatureInstallResult` 转成 feature 状态。

健康日志格式由 `TargetConfigClient.reportHealth` 固定为：

```text
<feature>: <state> - <message> [<targetVersion>]
```

`targetVersion` 来自 `TargetBuild.displayName`，通常是 `versionName (versionCode)`，不是 profile ID。profile 或符号解析摘要只有在对应 resolution message 中出现。

### 2.4 关键文件

| 文件 | 代码职责 |
| --- | --- |
| `app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt` | 目标包和 API 102 门控 |
| `app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt` | 资源阶段、应用阶段、安装顺序、异常隔离、快照和健康上报 |
| `app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt` | 6 个目标能力的语义 seam 和 Editorial Video adapter |
| `app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt` | profile、目标符号、契约、解析策略和诊断 |
| `app/src/main/java/dev/amenhancer/module/hook/ApkTargetClassSource.kt` | base/split DEX 类名合并和 ClassLoader 加载 |
| `app/src/main/java/dev/amenhancer/module/hook/LayoutInflationRegistry.kt` | 两个 `LayoutInflater.inflate` 重载的资源回调分发 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt` | 双栏资源镜像、Fragment 接线、歌词 pane 和平板边界策略 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt` | Apple Music 高亮/生命周期接入 |
| `app/src/main/java/dev/amenhancer/module/hook/OpenSourceLyricBlurPort.kt` | 与 Apple Music 私有符号无关的模糊运行时 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricsTypefaceTarget.kt`、`LyricsTypefaceSession.kt` | 歌词字体目标接入、异步加载和布局回调 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt` 及 `CustomLyrics*` | TTML 替换、ready-late 重入和身份校验 |
| `app/src/main/java/dev/amenhancer/module/hook/AppleMusicCurrentSongIdentityTarget.kt` | 元数据发布、当前项身份缓存和受权限保护的请求回复 |
| `app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt` | 设置键、默认值、编码和迁移的唯一来源 |

## 3. 版本 profile 基线

### 3.1 已登记的精确 profile

`TargetSymbols.kt` 当前对三个精确版本返回 profile：

| package | versionName | versionCode | profile ID | 代码含义 |
| --- | --- | ---: | --- | --- |
| `com.apple.android.music` | `6.5.0` | `1580` | `apple-music-6.5.0-1580` | 已登记的 profile |
| `com.apple.android.music` | `6.5.1` | `1583` | `apple-music-6.5.1-1583` | 已登记的 profile |
| `com.apple.android.music` | `6.5.2` | `1586` | `apple-music-6.5.2-1586` | 基于 APKPure base + arm64 + mdpi 清单登记 |

匹配是包名、版本名和版本码的严格同时匹配。未知版本、版本名与版本码错配、版本读取失败都不会复用旧 profile。

### 3.2 三个 profile 的关键迁移

`AppleMusicProfile` 同时支持 `exactClasses`、`exactMethods` 和 `exactFields`。当前三个 profile 的关键差异如下：

| 目标契约 | 6.5.0 / 1580 | 6.5.1 / 1583 | 6.5.2 / 1586 |
| --- | --- | --- | --- |
| `PLAYER_CONTROLLER` | `com.apple.android.music.player.fragment.w0` | `com.apple.android.music.player.fragment.q0` | `com.apple.android.music.player.fragment.t0` |
| `EDITORIAL_VIDEO_OWNER` | `com.apple.android.music.player.c1` | `com.apple.android.music.player.f1` | `com.apple.android.music.player.f1` |
| `LYRICS_CHROME` | `com.apple.android.music.player.fragment.e` | `com.apple.android.music.player.fragment.d` | `com.apple.android.music.player.fragment.e` |
| `LYRICS_CURRENT_ITEM_FIELD` owner | `com.apple.android.music.player.fragment.m` | `com.apple.android.music.player.fragment.l` | `com.apple.android.music.player.fragment.m` |
| `METADATA_TO_ITEM_CONVERTER` owner | `com.apple.android.music.player.P` | `com.apple.android.music.player.O` | `com.apple.android.music.player.O` |
| `LYRICS_AVAILABILITY_OWNER` | `com.apple.android.music.player.d1` | `com.apple.android.music.player.e1` | `com.apple.android.music.player.e1` |
| Activity holder method | `k1` | `j1` | `k1` |
| Activity root method | `n0` | `l1` | `n0` (inherited) |
| Activity behavior field | `c1` | `c1` | `c1` |

以下身份在三个 profile 中保持不变：`PlayerActivity`、`PlayerLyricsViewFragment`、`LyricsLineVector`、`SongInfoTimeProcessor`、高亮 callback owner、`PlayerLyricsViewModel`、`Hd.b`、`SongInfoPtr`、`SongInfoNative`、`TTMLParserNative` 和 metadata hub `com.apple.android.music.player.f`。

6.5.2 的 APK 证据：XAPK SHA-256 为 `96A70F0B724F6196C9F2B356986D9C93A1CF6BC9FDD555C1C96AABA5B4783C1C`；base APK SHA-256 为 `1E7151D02CAC39A9F70D017BCC26E3B0FD4C2AFB3AA30C363206D8616A4FEC59`；API 33+ 使用的 v3.1 证书 SHA-256 为 `771d8674d3d9837c9edf11b11873443998f19105abcecab425ed9b8e6fefff9b`。其 `k1()` 在存在 `bottom_navigation_root_flat` 时返回官方 `FlatBottomNavigationHolder`，歌词 chrome 使用 `fragment.e.a2(int, int[])`，静态折叠拦截使用 `h(CoordinatorLayout, View, MotionEvent)`。AM++ 变换该 flat root 后仍改为返回官方 `StackedBottomNavigationHolder`，以保留已验收的 mini-player/peek 生命周期。

新增版本时应显式比较三类身份，不能只比较类名：

- `exactClasses`：类 owner 变化。
- `exactMethods`：同一 owner 中的 root 或构造方法名变化。
- `exactFields`：同一 owner 或父类中的行为字段名变化。

### 3.3 profile 的添加规则

新增 profile 时：

- 保留旧 profile，除非有明确的停版决策。
- `id` 使用 `apple-music-X.Y.Z-CODE`，并与实际版本二元组一致。
- 只写入经过新版 APK 分析和契约确认的身份。
- 不使用版本范围、前缀匹配或“最接近版本”。
- 如果只确认了部分符号，其他符号不要用看起来相似的混淆名填充。
- 对新增的 `exactMethods` 和 `exactFields` 同时记录 profile 正匹配、版本错配和缺失时的解析行为。

## 4. 目标符号解析模型

### 4.1 解析源的限制

`ApkTargetClassSource` 会合并 `ApplicationInfo.sourceDir` 和 `splitSourceDirs`，去重后读取每个 APK 的 DEX 类名，并只把 `com.apple.` 前缀类名放入结构索引。之后通过目标进程的 `ClassLoader` 惰性加载类。

这带来两个适配规则：

- 新版分析必须包含 base APK 和所有 split APK。
- `Hd.b`、`androidx.recyclerview.widget.RecyclerView` 等非 `com.apple.` 的已知稳定类名不能依赖 DEX 枚举；它们通过稳定名称直接 `loadClass`。不要因为结构索引没有列出它们就重复添加模糊扫描。

### 4.2 三级解析和三种策略

`IndexedTargetSymbolResolver` 的解析顺序固定为：

1. 匹配 profile 的精确候选。
2. 已审查的稳定名称候选。
3. 结构契约候选。

`ProfilePolicy` 决定 profile 阶段失败后的行为。注意，只有版本命中 profile 时才存在 profile 阶段；未知版本仍会根据 key 的 stable/structural 定义继续解析：

| 策略 | 语义 | 适用场景 |
| --- | --- | --- |
| `NO_PROFILE` | 不使用版本 profile，直接走稳定名和结构候选 | `RecyclerView` |
| `EXACT_REQUIRED` | 版本命中 profile 且 profile 候选为空时，身份是权威契约，直接 `Missing`，不向下猜测；未知版本没有 profile 阶段，仍可能走 stable/structural | 类锚点和 `LyricsInstallMethod` 等名称本身具有身份意义的符号 |
| `EXACT_PREFERRED` | 先使用精确身份，失败后再使用已经审查过的稳定名或结构契约 | 大多数可由完整签名和 owner 约束的方法/字段 |

profile 阶段有多个候选时即返回 `Ambiguous`，即使策略是 `EXACT_PREFERRED` 也不能继续向下回退。结构候选同样遵守唯一性。对未登记版本，`EXACT_REQUIRED` key 的结构回退结果必须单独记录，不能当作 profile 身份。

解析结果的摘要格式为：

```text
<symbol> resolved via version_profile [<profile-id>]
<symbol> resolved via stable_name [<profile-id>]
<symbol> resolved via structural_fallback [<profile-id>]
<symbol> was not found [<profile-id>]
<symbol> was ambiguous (N candidates): <first-three-identities> [<profile-id>]
```

未知版本没有 profile ID；已匹配 profile 但发生 stable 或 structural 回退时，摘要仍会带该 profile ID。`TargetClassIndex`、解析器和每个符号结果都在进程内缓存，适配时不能假设每次 `resolve` 都会重新扫描 DEX。

### 4.3 20 个 profile symbol ID

`TargetSymbolId` 当前有 20 个条目。它们是通用 Apple Music profile 的身份键，不等同于 `AppleMusicSymbols` 的全部解析 key；静态折叠私有方法由独立结构 resolver 解析，不参与版本 profile。

| 能力族 | `TargetSymbolId` | 适配用途 |
| --- | --- | --- |
| 播放器和双栏 | `PLAYER_CONTROLLER` | 播放器控制器 owner |
| 播放器和双栏 | `PLAYER_ACTIVITY` | 播放器 Activity owner |
| 播放器和双栏 | `PLAYER_ACTIVITY_CREATE_STACKED_NAVIGATION_HOLDER` | Activity 中创建 stacked navigation holder 的方法名 |
| 播放器和双栏 | `PLAYER_ACTIVITY_ROOT` | Activity 内容根方法名 |
| 播放器和双栏 | `PLAYER_ACTIVITY_BEHAVIOR_FIELD` | Activity BottomSheetBehavior 字段名 |
| 播放器和歌词 | `EDITORIAL_VIDEO_OWNER` | Editorial Video URL owner |
| 播放器和歌词 | `LYRICS_FRAGMENT` | 歌词 Fragment owner |
| 播放器和歌词 | `LYRICS_CHROME` | 歌词 chrome/metrics owner |
| 歌词事件 | `LYRICS_LINE_VECTOR` | 原生歌词行向量类型 |
| 歌词事件 | `LYRICS_EVENT_PROCESSOR` | session/processEvents owner |
| 歌词事件 | `LYRICS_HIGHLIGHT_CALLBACK_OWNER` | 高亮 callback owner |
| 歌词事件 | `LYRICS_VIEW_MODEL` | ViewModel fallback owner |
| 播放器和双栏 | `STACKED_NAVIGATION_MENU` | 平板底部导航菜单 owner |
| 自定义歌词 | `SONG_INFO_PTR` | `SongInfoPtr` 类型 |
| 自定义歌词 | `SONG_INFO_NATIVE` | `SongInfoNative` 类型 |
| 自定义歌词 | `TTML_PARSER_NATIVE` | TTML native parser 类型 |
| 身份 seam | `LYRICS_CURRENT_ITEM_FIELD` | 歌词 Fragment 当前 `BaseContentItem` 字段 owner |
| 当前歌曲身份 | `PLAYER_METADATA_HUB` | metadata 发布 owner |
| 当前歌曲身份 | `METADATA_TO_ITEM_CONVERTER` | metadata 到 `PlaybackItem` 转换 owner |
| 自定义歌词 | `LYRICS_AVAILABILITY_OWNER` | 原生歌词可用性谓词 owner |

### 4.4 `AppleMusicSymbols` key 清单和关键契约

新增版本分析应以 `TargetSymbols.kt` 中的完整定义为准。当前 key 按调用能力分组如下：

| 能力族 | 解析 key | 必须保持的契约重点 |
| --- | --- | --- |
| 双栏播放器 | `PlayerController`, `PlayerControllerInitialize`, `PlayerControllerCreateView`, `PlayerControllerSelectPane` | `w1(BagConfig): void`；`onCreateView(LayoutInflater, ViewGroup, Bundle): View`；`F1(enum, Bundle): void`；三个方法必须属于同一 controller 契约 |
| 双栏 Activity | `PlayerActivity`, `PlayerActivityCreateStackedNavigationHolder`, `PlayerActivityRoot`, `PlayerActivityBehaviorField` | holder 无参返回 `PlayerActivity$m`；root 无参返回 `View`；behavior 非 static 且沿继承层级为 BottomSheetBehavior 或目标专用子类 |
| 双栏菜单 | `StackedNavigationMenu`, `StackedNavigationMenuOnMeasure` | owner 为 `Hd.b`；`onMeasure(int, int): void` 且非 static |
| 双栏歌词 chrome | `LyricsFragment`, `LyricsFragmentOnResume`, `LyricsFragmentUpdateMetrics`, `LyricsChromeFragment`, `LyricsChromeAnimate` | `onResume()`、`j2(): boolean`、`a2(int, int[]): void`；chrome 还要求 `f2(): View` 兄弟契约 |
| Editorial Video | `EditorialVideoUrlSelector` | static，返回 `String`，参数为 `Song`、primitive `float`、`EditorialVideo$Flavor[]` |
| 模糊核心 | `RecyclerView`, `LyricsLineVector`, `LyricsSessionProcessor`, `LyricsHighlightCallback` | RecyclerView 使用精确类型；`processEvents` 为 7 参数并返回 `long`；callback 为 `call(long, LyricsLineVector, long): void` |
| 模糊 fallback | `LyricsViewModel`, `LyricsViewModelNotifyWordHighlight`, `LyricsViewModelSetCurrentHighlightedLine` | `notifyWordHighlight(int, int, int, boolean): void` 和 `setCurrentHighlightedLine(int): void` |
| 自定义歌词 | `LyricsInstallMethod`, `SongInfoPtr`, `SongInfoNative`, `TtmlParserNative`, `TtmlSongInfoFromTtml` | 安装入口必须是精确名称 `I2(SongInfoPtr): void`；不能把同形 `R2` 当作安装入口；native 类型和 `songInfoFromTTML(String)` 必须互相匹配 |
| 自定义歌词入口 | `LyricsAvailabilityPredicate`, `LyricsCurrentItemField` | static `i(PlaybackItem): boolean`；当前项字段必须是非 static、名称 `c`、类型 `BaseContentItem`，结构回退只能接受歌词 Fragment 层级中的唯一字段 |
| 当前歌曲身份 | `PlayerMetadataPublishMethod`, `MetadataToPlaybackItemMethod` | metadata 发布方法为非 static `g(v3.v): void`；转换方法为 static `b(v3.v): PlaybackItem`；结构回退还需要同类型的 `BaseContentItem` 兄弟转换方法 |

上表列出的 11 个类 key 同时承担 owner 锚定职责：`PlayerController`、`PlayerActivity`、`LyricsFragment`、`LyricsChromeFragment`、`LyricsViewModel`、`StackedNavigationMenu`、`RecyclerView`、`LyricsLineVector`、`SongInfoPtr`、`SongInfoNative` 和 `TtmlParserNative`。它们已经计入 32 个 key，不应重复登记。profile ID、解析 key 和 feature 能力之间不是一对一关系；多个方法可能共享同一个 owner ID。

## 5. 六项目标能力和七个 feature

### 5.1 双栏播放器 `dual_pane`

- 设置门控：`dual_pane_enabled`，默认 `true`。
- 资源阶段：注册 `bottom_navigation`、`fragment_player_main`、`fragment_player_lyrics_sheet`、`lyrics_line` 和 `lyrics_word_karaoke` 回调。
- 应用阶段：安装 11 个核心目标方法/字段契约，包括 controller 三钩子、Activity holder/root/behavior、菜单 `onMeasure`、歌词 Fragment、chrome、metrics 和 `onResume`。对 6.5.2/1586 另外按独立私有契约解析并安装 `StaticCollapsedInterceptGuard`。
- 资格判定：`TabletModeQualifier` 读取目标包的 `is_tablet` bool，并要求横屏；资源和目标运行时都还受双栏开关约束。
- 运行机制：资源回调镜像 layout-land 约束；目标 Hook 负责原生 holder、Fragment transaction、歌词 pane 和生命周期接线；不能替换目标 player root 或接管目标 bottom-sheet 生命周期。
- 私有适配知识：`AlphaGradientEdgeFieldProfiles`、`LyricsLayoutFieldProfiles` 和 `ConstraintLayout$b` 的 `TARGET_650_FIELD_NAMES` 与 `AppleMusicProfile` 独立。新版适配时必须单独确认这些字段变体。
- 边界补偿：`navigation_compensation_enabled` 只在官方平板横屏生效。`FlatPlayerBoundaryPolicy` 通过 `player_sheet_container` 的几何、`translationY` 和 pre-draw 同步处理折叠态；只做视觉平移，不修改布局 margin。传入的补偿量保持为 `tabsHeight - menuHeight`，不能把整块 tabs frame 平移到 mini-player 后面；变换后的 flat root 仍强制返回官方 `StackedBottomNavigationHolder`，不能让 native flat holder 接管播放器生命周期。
- `ACTIVE` 条件：11 个核心 resolution 均为 `Found`，controller 三个 Hook、菜单测量、chrome、metrics 和 typography Hook 数量都满足安装要求；在 6.5.2/1586 上静态折叠 guard 也必须成功安装。
- `DEGRADED` 条件：任一核心 resolution 缺失/歧义，或实际 Hook 数量不足。

### 5.2 Editorial Video `editorial_video`

- 设置门控：`disable_editorial_video_on_tablet`，默认 `true`。
- 应用阶段：解析并 Hook `EditorialVideoUrlSelector`。
- 运行条件：仅 `TabletModeQualifier.isOfficialTabletLandscape(application)` 为真时把 URL 返回值改为 `null`。
- 行为边界：保留静态预览帧和独立 Music Video 路径，不做全设备或全视频类型抑制。
- `ACTIVE` 只表示目标方法 Hook 安装成功；目标资格不是官方平板横屏时，不会发生 UI 变化。

### 5.3 手机液态玻璃 `phone_liquid_glass`

- 设置门控：`phone_liquid_glass_enabled`，默认 `false`。
- 资源阶段：处理 `bottom_navigation` 和 `mini_player`，并排除官方平板。
- 适配要求：保持资源期处理和官方平板排除规则，不要把它添加到 `TargetAdaptation` 作为 Apple Music 符号能力。

### 5.4 双向歌词模糊 `future_blur`

- 设置门控：`future_blur_enabled`，默认 `true`。
- 平台门槛：Android API 31 以下返回 `UNSUPPORTED`。
- 目标依赖：精确 `RecyclerView`、歌词 Fragment、歌词行向量、session processor、高亮 callback 和两个 ViewModel fallback 入口。
- 安装首先要求精确 `RecyclerView` 和歌词 Fragment。高亮路由中 callback 安装后优先使用 callback；callback 不可用时，使用非后台的四参数 ViewModel 事件或单参数事件。三个高亮入口全部缺失，或歌词行向量/session processor 等可选核心解析失败时，能力进入降级路径。
- 生命周期：歌词 Fragment `onCreateView` 时发现目标 RecyclerView，`onDestroyView` 时移除 listener、取消任务、清理 renderer 和 session 视图状态。
- 运行时边界：`OpenSourceLyricBlurPort` 只消费语义事件和目标访问器，不应重新引入 Apple Music 反射知识。

### 5.5 滚动暂停

滚动暂停没有独立的 feature key 或独立健康状态，属于 `OpenSourceLyricBlurPort`：

- `ACTION_DOWN` 和 `ACTION_MOVE` 进入手动滚动态，并取消待恢复任务。
- 滚动期间调用 `applyBlur(includeFocus = false, immediate = true)`，移除焦点相关模糊。
- `ACTION_UP`、`ACTION_CANCEL` 或滚动变化后安排 1000 ms 恢复任务。
- 恢复时重新按照当前高亮 session 应用双向模糊。
- Fragment 销毁时必须移除滚动监听和恢复任务。

### 5.6 歌词字体 `lyrics_typeface`

- 设置门控：`lyrics_font_enabled`，默认 `false`；实际字体元数据由 `fontManifest` 提供。
- 资源阶段：为 `LyricsTypefaceLayoutContract.layoutNames` 中的 12 个当前布局契约注册回调，并标记 instrumental 行。
- 应用阶段：解析歌词 Fragment 和精确 RecyclerView，Hook `onResume`，然后激活共享 `LyricsTypefaceSession`。
- 加载模型：远程字体读取、大小检查、SHA-256 校验和 Typeface 解析在后台单线程进行；加载完成后重新应用仍处于观察期的 lyric root。
- 失败策略：安装期 `prepare()` 失败会让能力显式 `DEGRADED`；运行期字体加载或单个 style 创建失败时保留 Apple Music 原字体，不能因字体失败阻断歌词生命周期。
- `ACTIVE` 可在字体仍为 `Loading` 时返回；日志必须说明是 `font ready` 还是 `font loading in background`。

### 5.7 当前歌曲身份 `current_song_identity`

- 没有设置开关，由 `CurrentSongIdentityFeature` 总是尝试安装；请求不可用时 fail closed。
- 目标依赖：`LyricsInstallMethod` 用于当前项身份 seam，metadata 发布方法，metadata 到 `PlaybackItem` 转换方法，以及歌词 Fragment 当前 `BaseContentItem` 字段。
- 运行链路：Hook metadata 发布 → 转换为 `PlaybackItem` → 通过 `CurrentItemIdentitySeam` 读取身份 → 写入 `CurrentSongIdentityCache`。
- 进程间请求：`CurrentSongIdentityProtocol` 使用签名权限保护的广播和 `ResultReceiver`，设置进程请求当前项，目标进程只回复缓存的 Apple Music ID、标题和艺人。
- `ACTIVE` 还要求 receiver 注册成功；仅 Hook metadata 成功不能标记为 `ACTIVE`。
- 该能力与自定义歌词共享 `CurrentSongIdentityCache` 和 `CurrentItemIdentitySeam`，但两项能力必须独立解析、独立返回 `Missing`/`Ambiguous` 和独立上报。

### 5.8 自定义歌词 `custom_lyrics`

- 设置门控：`custom_lyrics_enabled`，默认 `false`；旧键 `online_lyric_replacement_enabled` 只用于 schema 解码迁移。
- 目标依赖：`I2(SongInfoPtr)`、`SongInfoPtr`、`SongInfoNative`、`TTMLParserNative`、`songInfoFromTTML(String)`、歌词可用性谓词和当前项身份字段。
- 安装入口：`I2` 的名称是契约的一部分。相同参数形状的 `R2` 不能被结构回退误选。
- 替换链路：读取 ID 到 TTML 的映射，后台解析 native pointer，检查指针存活和 Adam ID，再在 `I2` 前替换参数。
- ready-late：自定义 pointer 尚未准备好时，以 Fragment 身份和 Apple Music ID 记录一次待重入项；发布后只有 Fragment 仍可用、当前项仍匹配且 replacement 仍 ready 才重入 `I2`。所有失败均 fail open，保留原生 pointer。
- 可用性入口：原生无歌词且 replacement 已 ready 时，availability Hook 才把结果改为 `true`。
- `ACTIVE` 要求安装入口、parser surface、身份 seam、availability Hook 等核心步骤成功；只装好 `I2` 替换但没有 availability 入口时必须 `DEGRADED`。

## 6. 配置 schema 和双进程边界

### 6.1 单一事实来源

`ModuleSettingsSchema.kt` 是设置进程和目标进程共同使用的 schema。新增版本适配不得在 adapter、Feature 或 `TargetConfigClient` 中复制一套默认值、键名或迁移规则。

当前 `ModuleConstants.CONFIG_SCHEMA_VERSION` 为 `7`，remote preferences group 为 `settings`。

### 6.2 当前键和默认值

| 分类 | 键 | 默认/规则 |
| --- | --- | --- |
| 普通设置 | `dual_pane_enabled` | `true` |
| 普通设置 | `disable_editorial_video_on_tablet` | `true` |
| 普通设置 | `phone_liquid_glass_enabled` | `false` |
| 普通设置 | `future_blur_enabled` | `true` |
| 普通设置 | `navigation_compensation_enabled` | `false` |
| 普通设置 | `lyric_blur_radius_offset_px` | `0`，按 `ModuleSettings` 的最小/最大值 clamp |
| 普通设置 | `custom_lyrics_enabled` | `false`；缺失时读取旧 `online_lyric_replacement_enabled` |
| 字体 manifest | `lyrics_font_enabled` | `false` |
| 字体 manifest | `lyrics_font_file_id` | 字符串并经过字体 manifest policy 校验 |
| 字体 manifest | `lyrics_font_display_name` | 字符串 |
| 字体 manifest | `lyrics_font_size_bytes` | 长整数并经过字体 policy 校验 |
| 字体 manifest | `lyrics_font_sha256` | 字符串并经过字体 manifest policy 校验 |
| 旧 manifest | `custom_lyrics_manifest` | 旧版字符串 manifest；新索引优先使用远程文件指针 |
| 索引指针 | `custom_lyrics_index_file_id` | 与 generation、SHA-256、size 一起完整校验 |
| 索引指针 | `custom_lyrics_index_generation` | 必须大于等于 1 |
| 索引指针 | `custom_lyrics_index_sha256` | 必须是有效 SHA-256 |
| 索引指针 | `custom_lyrics_index_size_bytes` | 必须在允许的索引大小范围内 |
| schema | `schema_version` | 当前值为 `7` |

写入值只允许 `Boolean`、`Int`、`Long` 和 `String`。普通设置写入故意不携带字体 manifest、custom lyrics manifest 和索引 pointer，避免旧快照覆盖已经提交的远程文件事务。

### 6.3 迁移和进程通信

- 嵌入设置入口由 `HookEntry` 在 Apple Music 主进程中接线；首次启动时可从 libxposed API 102 remote preferences/remote file 迁移，之后由 `HostPrivateEmbeddedStorage` 和 `EmbeddedConfigurationSession` 保存普通设置、歌词索引与字体文件。
- `ModuleSettingsSchema.upgrade` 只在存储版本低于当前 schema 时生成新值；当前或更高版本不被重写。
- 目标进程通过 `TargetConfigClient` 只读嵌入宿主配置，并通过宿主文件 opener 读取字体或自定义歌词索引。
- remote preferences 不可用时，已完成迁移的宿主仍可读写本地配置；首次迁移未完成时设置页只读并在下一次进程启动重试。目标侧不会继续读取旧的 `module-settings`，适配不能假设两个进程共享普通内存。
- 目标进程通过 `reportHealth` 写日志上报，不向设置进程直接写回 feature 状态。

### 6.4 配置适配禁区

版本适配不应：

- 为新版 Apple Music 增加专用配置键。
- 在 feature 中直接读取 `SharedPreferences` 或重新实现默认值。
- 改变 schema version 只为让 profile 生效。
- 把远程文件 pointer 当普通设置写入。
- 把当前歌曲标题或艺人作为身份匹配 fallback；身份必须使用 `CurrentItemIdentitySeam` 和 Apple Music ID。

## 7. 新版本适配流程

### 7.1 建立代码基线

适配开始前先记录工作区，不要覆盖用户或其他 agent 的未提交改动：

```powershell
git status --short
git branch --show-current
```

### 7.2 记录新版 APK 集合

```powershell
adb shell dumpsys package com.apple.android.music | Select-String 'versionName=|versionCode='
adb shell pm path com.apple.android.music
```

保存以下信息：

- 包名、`versionName`、`versionCode`。
- base APK 和全部 split APK 路径。
- APK SHA-256、签名证书 SHA-256。

不要只分析 base APK。`ApkTargetClassSource` 的结构索引明确合并 split APK；漏掉 split 会把真实存在的类误判为 `Missing`。

### 7.3 只读建立符号变化清单

对每个受影响 key 记录以下字段：

| 字段 | 要求 |
| --- | --- |
| Symbol ID/key | 使用源码中的稳定 ID，不用临时描述代替 |
| owner | 完整类名和 ClassLoader 来源 |
| member | 方法名或字段名；注明是否来自 `exactMethods`/`exactFields` |
| signature | 参数、返回值、静态性和继承关系 |
| structural proof | 能说明真实职责的兄弟方法、字段或调用路径 |
| profile policy | `NO_PROFILE`、`EXACT_REQUIRED` 或 `EXACT_PREFERRED` |
| source | 反编译、DEX、资源表和调用路径来源 |

重点核对：

- 混淆名变了但 owner 和结构未变：优先新增 profile 精确身份。
- 参数、返回值、静态性或 owner 变了：重新评审 adapter 契约，不能只替换字符串。
- 候选多于一个：加深契约，不按 DEX 顺序选择。
- `EXACT_REQUIRED` 符号缺失：接受 `Missing` 和能力降级，不把结构猜测强行升级为成功。
- 非 `com.apple.` 的稳定类：使用直接 `loadClass`，不要用索引缺失作为结论。

### 7.4 更新 profile

在 `TargetSymbols.kt` 添加独立 `AppleMusicProfile`，并在 `AppleMusicProfiles.match` 中添加严格二元组匹配。根据 APK 分析分别填充 `exactClasses`、`exactMethods` 和 `exactFields`。

更新 profile 时确认以下解析行为：

- 新版本正匹配。
- 版本名正确但 versionCode 错配不匹配。
- versionCode 正确但 versionName 错配不匹配。
- 包名不符不匹配。
- profile 精确命中不触发不必要的完整 DEX 枚举。
- 命中 profile 且 exact identity 缺失时，`EXACT_REQUIRED` 返回 `Missing`，`EXACT_PREFERRED` 才允许稳定名或结构回退；未登记版本单独记录其 stable/structural 结果。

### 7.5 决定是否修改 adapter

通常只改 profile 的情况：

- 混淆类名或方法名改变，但已确认的签名、owner、生命周期和参数语义不变。
- 只增加了新版本的 `exactMethods` 或 `exactFields` 身份。

必须改对应 adapter 或私有契约的情况：

- 生命周期入口改变。
- Fragment transaction、构造器或返回值类型改变。
- 目标资源树、resource ID 或布局参数字段变了。
- 目标需要新的 ready-late、身份或线程边界。
- `AlphaGradientEdgeFieldProfiles`、`LyricsLayoutFieldProfiles` 或 ConstraintLayout 字段映射出现新变体。

修改范围应保持在对应能力：

- 双栏和边界策略：`AppleMusicDualPaneTarget.kt` 及其专属 policy/profile。
- 歌词高亮接入：`AppleMusicBidirectionalLyricBlurTarget.kt`。
- 歌词字体：`AppleMusicLyricsTypefaceTarget.kt`、`LyricsTypefaceSession.kt`。
- 自定义歌词：`AppleMusicCustomLyricsTarget.kt`、`TtmlNativeParser.kt`、ready-late/session 文件。
- 当前歌曲身份：`AppleMusicCurrentSongIdentityTarget.kt`、`CurrentItemIdentitySeam.kt`。
- Editorial Video：`TargetAdaptation.kt` 内联 adapter。

不要把 `Class`、`Method`、`Field`、`TargetResolution`、`TargetSymbolResolver` 或 `AppleMusicSymbols` 引入 `*Feature.kt` 和目标无关的 `OpenSourceLyricBlurPort.kt`。

### 7.6 更新与私有资源相关的适配知识

`AppleMusicProfile` 之外还有一层双栏私有知识，必须单独记录：

- `AlphaGradientEdgeFieldProfiles`：AlphaGradientFrameLayout 的垂直/水平边缘布尔字段和整数配置字段。
- `LyricsLayoutFieldProfiles`：歌词 Fragment binding、container、recycler、gradients 和 metrics 字段。
- `TARGET_650_FIELD_NAMES`：只在目标 LayoutParams 类型为 `androidx.constraintlayout.widget.ConstraintLayout$b` 时启用的 6.5.0 字段映射。
- `LayoutInflationRegistry` 的资源名称推断：`bottom_navigation`、`fragment_player_main`、`fragment_player_lyrics_sheet`、`lyrics_line`、`lyrics_word_karaoke` 等。
- `LyricsTypefaceLayoutContract.layoutNames`：12 个歌词布局名称，包括 instrumental 行。
- `StaticCollapsedInterceptGuard`：仅对已验证的 Apple Music 6.5.2 / 1586 安装；方法必须由 `AppleMusicSymbols.StaticCollapsedInterceptMethod` 独立解析，运行时还要求 AM++ 标记的 `bottom_navigation_root_flat` 树及可见的 `player_lyrics` / `player_queue` 命中区域，其他版本跳过该 hook。

这些知识不参与 `AppleMusicProfiles.match`。新增版本时要单独确认变体，并把结果集中记录在对应 adapter 的适配知识中。

## 8. 诊断和失败处理

### 8.1 `resolved via structural_fallback`

这表示当前只有结构契约找到了唯一候选。应记录候选身份并补齐对应 profile，避免把结构候选当作跨版本身份。

### 8.2 `was not found`

按以下顺序排查：

- 版本名和版本码是否真的匹配预期 profile。
- 是否漏读 split APK。
- profile 的 owner、exact method 或 exact field 是否过期。
- 方法的静态性、参数、返回值和继承层级是否变化。
- `EXACT_REQUIRED` 是否按设计拒绝了不安全的结构猜测。
- 资源 ID、布局名称或 ClassLoader 是否变化。

不要第一步就放宽 owner 前缀或删除名称约束。

### 8.3 `was ambiguous`

这表示当前契约无法唯一表达职责。继续补充参数、返回值、静态性、owner、父类、兄弟方法或调用路径条件，直到候选唯一；不要选择第一个候选。

### 8.4 Apple Music 启动崩溃、FATAL 或 ANR

优先检查本次 adapter 的强制类型转换、参数数组、constructor、字段可访问性和返回值覆盖。不要用捕获所有异常后继续运行掩盖稳定性问题；能独立降级的能力应返回 `DEGRADED` 或 `FAILED`，不能让目标进程崩溃。

## 9. 适配记录模板

```markdown
## Apple Music X.Y.Z / versionCode

- Package：`com.apple.android.music`
- Profile ID：`apple-music-X.Y.Z-CODE`
- APK：base + split 清单
- APK SHA-256：
- 签名证书 SHA-256：
- 配置 schema：`7`
- 与上一版本相比的 profile symbol 变化：
  - 类：
  - 方法：
  - 字段：
- 与上一版本相比的结构契约变化：
- Adapter 行为变化：无 / 独立变更记录链接
- 私有资源/字段 profile 变化：
- 健康状态：
  - `dual_pane`：
  - `editorial_video`：
  - `phone_liquid_glass`：
  - `future_blur`：
  - `lyrics_typeface`：
  - `current_song_identity`：
  - `custom_lyrics`：
- 适配说明：
```

## 10. 完成定义

新版本适配完成，必须满足：

- 精确 profile 已按包名、版本名和版本码登记。
- 所有受影响目标符号都有可复核的身份、结构契约和独立 resolution 结果。
- `EXACT_REQUIRED`、`EXACT_PREFERRED`、`Missing` 和 `Ambiguous` 语义没有被绕过。
- Feature 层和目标无关运行时没有新增 Apple Music 私有知识。
- 配置 schema、远程文件和双进程边界没有被复制或绕开。
- 适配记录包含版本、APK、签名、profile、符号变化和 adapter 变化。
