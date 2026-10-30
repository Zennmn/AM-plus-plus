# Apple Music 宿主适配手册

本手册供维护者为具体 Apple Music 构建建立和维护 AM++ 接入。适配身份取实际宿主 `packageName` 与 `TargetBuild` 的 `versionName`、`versionCode`；正式版、beta 和同名不同构建分别处理。

| 要解决的问题 | 阅读位置 |
|---|---|
| 适配一个新构建 | [版本资料与解析](#profile-contracts) → [适配流程](#adaptation-workflow) → 对应页面族与功能域 |
| 修改业务、交互或渲染策略 | [架构边界](#architecture)及对应功能域的“通用维护”；只改混淆映射时不重写这些策略 |
| 逐项提供证据、登记缺口 | [适配清单](host-adaptation-checklist.md)，复制到任务记录填写 |
| 确认用户可见行为与人工场景 | [功能保全矩阵](feature-preservation.md)，按精确构建和设备记录结果 |

精确版本、混淆名字、完整描述符、资格和该构建的证据以 [host-profiles](../host-applemusic/src/main/resources/host-profiles/) 为准；必需角色和类型关系以对应 Contract / Inventory 为准。手册说明角色语义与维护方法，清单登记证据与判定，矩阵集中人工场景。单次分析、构建日志与实际验收结果保存在任务记录中。

<a id="scope"></a>

## 1. 版本准入与能力资格

实际宿主的版本信息由 [TargetBuildLookup.targetBuild](../host-applemusic/src/main/java/dev/amenhancer/module/hook/TargetBuildLookup.kt) 按 `packageName` 查询包信息读取，并以实际 `packageName`、`versionName`、`versionCode` 构造 `TargetBuild`。[AppleMusicHostProfiles](../host-applemusic/src/main/java/dev/amenhancer/host/applemusic/AppleMusicHostProfiles.kt) 只按精确 `versionName`+`versionCode` 选择共享 profile，生产入口另检查 `productionEnabled`。文件名为 `<versionName>-<versionCode>.json`，同一组合只登记一次；`index.json` 还决定旧 HLE 的已审核候选顺序，新增档案保留既有条目的相对顺序。`profile.packageName` 描述原包及内部类、方法、资源命名空间，不是运行时包名白名单。

本规则只覆盖 Android 安装包名（application ID）变化；内部类、方法和资源命名空间保持原包，其他改包行为不获得兼容。实际安装包名只用于身份、版本和组件。

生产准入、能力可用和人工通过分别判断：

1. 精确 `versionName`+`versionCode` 组合已登记且生产入口启用，才允许模块接入。
2. 功能按当前 profile 的能力位、独立契约、设置、Android API 和设备形态共同判断。`capabilities` 不是全部功能的目录；例如简体替换和声修复与跨行扫光虽由“歌词效果优化”设置门禁，仍依赖各自的精确接缝，LLM 则复用已有歌词通道。
3. 成员解析与 Hook 安装成功只说明该阶段就绪，不能代替页面完成、显示结果或人工验收。`productionEnabled` 与 `runtimeVerified` 各自依据实际证据维护，局部反馈不扩展为整个构建通过。

页面族按实际入口、生命周期和布局识别：`legacy-activity` 与 `fragment-content` 分别使用自己的接入。相邻构建、版本号大小和主版本号均不能代替该判断；新页面族先建立有证据的能力工厂。

<a id="architecture"></a>

## 2. 架构与改动位置

| 模块 | 职责 | 适配边界 |
|---|---|---|
| `core` | 配置模型、歌词/字体业务、来源、缓存、布局和会话策略 | 保持 JVM 业务，不引入 Android、反射或 Xposed |
| `host-api` | 宿主能力、歌曲身份、语义区域、配置读取、安装结果与可关闭订阅 | 可使用接口所需的 Android SDK 类型；不暴露混淆成员、反射或宿主 AndroidX |
| `hook-runtime` | Xposed 接入、注册作用域、通用调用缓存、Hook 注册记录 | 不承载 Apple Music 版本策略或业务页面 |
| `host-applemusic` | profile、符号与独立契约解析、歌词、设置、播放器、导航、元数据适配 | 私有成员和页面族差异集中于此，含原生位移读取与 Compose 边界识别 |
| `app` | 功能装配、配置存储、设置界面、歌词服务与玻璃会话 | 经宿主能力/端口使用功能，不自行发现混淆成员 |
| `glass` / `backdrop` | 材质、几何呈现、背景采样 | 不判断 Apple Music 版本资格；本地上游补丁按来源说明维护 |
| `plugin-api` / `plugin-runtime` | 公共 SDK、导入加载、生命周期与冲突分析 | 公共协议不随宿主混淆变化；冲突分析使用通用注册记录 |

部分模块共享 `dev.amenhancer.module.hook` 等包名，代码归属以目录和 Gradle 依赖为准。定位入口时同时记录模块路径，不能仅凭 import 判断边界。

### 2.1 装配与执行链

[HookEntry](../app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt) 在 Application 资源准备前按实际宿主 `packageName` 完成一致性校验，保留首次加载（`isFirstPackage`）与声明主进程过滤，再完成精确版本门禁、配置迁移/绑定与 DPI 接入。[FeatureInstallation](../app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt) 分开资源注册和 Application 后的能力安装；[AppleMusicAssembly](../app/src/main/java/dev/amenhancer/module/hook/AppleMusicAssembly.kt) 装配歌词服务，[AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 将当前 profile、ClassLoader 与服务注入 [TargetAdaptation](../host-api/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt)。

工厂按 `family` 选择设置、双栏、蜂窝和视频路径。单项功能只接收自己的能力；chrome、设置、资源和服务还各有辅助入口，完整接入范围不能仅从能力位或工厂字段枚举。每次适配沿实际安装计划及其调用链展开。

### 2.2 改动分流

| 实际变化 | 修改与复核范围 |
|---|---|
| 混淆名、类型别名、资源名变化，语义不变 | 当前精确 profile 与证据；复核相关角色、资源和适用功能 |
| 参数、返回、模型关系或调用语义变化 | 对应 Contract、解析器和调用处；同步必需角色与离线覆盖 |
| 生命周期、线程、原生发布或布局覆盖变化 | 对应 Runtime / Coordinator / Session，重新确认归属、失效、恢复与降级 |
| 来源、缓存、设置或渲染策略变化 | `core` / `app` / `glass` 中的策略与对应通用维护规则、行为矩阵；宿主私有变化仍在适配层 |
| 新页面族或不兼容协议 | 独立分析其接缝及契约版本，不能默认套入旧工厂或冒用原 schema |

内部职责沿用现有结构：**Contract** 解析并校验成员、资源和类型关系；**Runtime / Coordinator** 注册 Hook、管理活动宿主与能力；**Session / Binding** 持有视图、监听、延迟任务与属性恢复。普通版本适配复用业务规则，避免另建固定旧映射与新映射两条活动路径。

<a id="profile-contracts"></a>

## 3. 版本资料、解析与成员证据

### 3.1 profile 归属

| 区域 | 应表达的宿主事实 |
|---|---|
| 身份、资格与证据 | `id`、`packageName`（原包）/`versionName`+`versionCode`、`family`、`productionEnabled`、`profileRevision`、`capabilities`、`evidence`、`knownDegradations` |
| `indexed` | 符号映射及适用的 `methodContracts` / `fieldContracts` |
| `hookTargets` / `catalogQueryRenames` | HLE 入口、签名/调用方约束、`runtimeMemberNames`、目录查询映射 |
| `settings` / `chrome` | 设置模型、入口、导航、播放器资源及旧族成员；`settings.members` 保存变化后的完整偏好列表和 Compose 方法契约 |
| `layoutVariants` / `cjk` | binding、渐变变体、前景文本与可选 `terminalGradient` 契约 |
| `lyricResultSubmission` / `lyricBlur` / `lyricWrapSweep` / `lyricBackgroundHighlight` | 歌词结果、显示模糊、跨行扫光及活动行背景高亮优化的独立角色与契约 |
| Fragment 专用区域 | `fragmentDualPane`、`fragmentLyricsRuntime`、`fragmentKaraokeWidth`、`fragmentTabletVideoHandoff`、`fragmentChrome`、`playerRecovery` |
| `startupPagePrewarm` / `artistComposeProfile` | 独立预热、Compose 艺人表面的成员、构造器与语义参数 |
| `verification` / 匹配策略 | 离线描述符/资源证据、`ambiguityPolicy`、已审核的 `legacyFirstMatchExceptions` |

JSON 保存宿主事实，参数变换、线程、回退和生命周期策略由 Kotlin 实现。共享 profile 只按精确 `versionName`+`versionCode` 选择；`profile.packageName` 描述原包及内部类、方法、资源命名空间，不是运行时包名字段，实际运行时包名由 `TargetBuild` 单独记录。profile schema、各独立契约的版本字段和应用配置 schema 分别维护，以当前解析器及 [ModuleConstants](../core/src/main/kotlin/dev/amenhancer/module/ModuleConstants.kt) 为准；普通混淆适配不附带修改配置格式。

### 3.2 三种接入方式

| 方式 | 入口与行为 | 使用边界 |
|---|---|---|
| 索引符号 | [IndexedTargetSymbolResolver](../host-applemusic/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt) 缓存成功、缺失及歧义；显式精确描述符不匹配即缺失 | 稳定名/结构回退由符号策略决定，不能用相似符号补齐必需成员 |
| HLE 兼容候选 | [AppleMusicHookProfiles](../host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt) 将 JSON 转为旧引擎候选 | Fragment 使用严格候选；旧 Activity 保留既有内部匹配和已审核回退顺序，外层生产门禁仍精确 |
| 独立区域契约 | [FragmentProfileMembers](../host-applemusic/src/main/java/dev/amenhancer/module/hook/FragmentProfileMembers.kt) 解析完整方法/字段/构造器，能力 Contract 再校验类型关系 | 用于结果提交、模糊、跨行、预热、Fragment 等；名字中的 Fragment 不限制其只能用于该页面族 |

独立契约的必需角色由代码 Inventory / requirements 维护，JSON 少写角色会导致预检失败，不能把漏项解释为可选。各能力分别解析和报告，双栏预检成功不代表 chrome、恢复或歌词能力完整。

新增依赖时统一同步：当前 profile、代码必需角色/关系校验、适用的 `verification` 及 `scripts/verify-host-profile.py` 等离线覆盖；清单记录该能力的证据与结论，不复制完整描述符。协议发生不兼容变化时，连同契约版本和解析器设计升级。

新档案使用 `reject-ambiguous`。首个匹配例外仅保留已审核历史语义；多个候选需要补充类型或调用证据，仍无法唯一定位时降级对应能力。

### 3.3 语义证据要求

| 对象 | 必须确认的内容 |
|---|---|
| 方法 | 实际声明 owner、完整参数顺序/类型、返回、static、abstract/bridge/synthetic 与调用方式 |
| 字段 | 声明 owner、完整类型、实例归属及被读写含义 |
| 构造器 | owner、完整参数及顺序、创建对象的用途 |
| 接口或继承成员 | 实际声明接口/父类、返回对象实现关系；抽象成员仅按需声明 `invocationOnly` 调用契约 |
| Hook 目标 | 实际活跃链中的具体实现；抽象调用契约不能代替可 Hook 实现 |
| 资源与布局 | 名称/类型、限定符、实际值、递归 include、运行时父容器、LayoutParams 与 binding 后续写入 |

Java 反编译用于理解流程，原始 owner 与擦除签名以 DEX/smali 为准。类型推断、命名修复、大小写冲突后的文件名不能作为原始契约。“存在”“参数个数相同”“可以反射调用”均不能替代调用语义证据；数组、primitive、装箱与实例/静态不能互换。

类结构、字段顺序、相似描述符或反编译浏览中的近似签名只能提出结构候选，用于缩小核查范围；在缺少语义闭合前不能据此认定目标版本已适配、授予能力或推定运行时兼容。候选转为已确认映射时，须同时核对当前 split 中的原始 owner 与完整擦除签名或字段类型、实际声明及继承/接口关系、调用方与读写语义、Hook 的具体实现，以及页面生命周期中的实际作用；资源与布局还须按上表确认限定符和运行时父链。

<a id="adaptation-workflow"></a>

## 4. 每个构建的适配流程

### 4.1 建立基线

1. 从实际 base APK 的二进制 Manifest 和运行时宿主确认 `packageName`、`versionName`、`versionCode`，记录 `TargetBuild` 的实际包名；将 `profile.packageName` 单列为原包和内部命名空间。外层文件名/清单只作辅助。
2. 清点 base、全部配置/功能 split、各 DEX 与资源来源；DEX 不一定只在 base。由原始已适配版直接比较目标版时，先按实际安装计划逐项配对 base 对 base、同类 ABI split 对同 ABI、密度 split 对同密度，其余配置/功能 split 按相同身份与限定符配对；不得以 base 与 split、不同 ABI 或不同密度的产物互相替代。记录各证据的配对来源及目标新增、缺失的 split，再核对对应的 Manifest、DEX、资源和 native 内容。
3. 保存代码基线、实际页面族、对照宿主和可追溯证据；包哈希仅在明确要求时计算。
4. 分别确定源码分析、所需检查、构建和人工验收范围，再在任务记录填写[清单](host-adaptation-checklist.md)。

### 4.2 沿接入链补齐档案

以安装计划、工厂及[功能域索引](#feature-index)为入口，逐能力展开直接 Hook、附属 getter/model/binding/listener、资源与清理路径。设置、资源回调、服务、DPI 和插件兼容同样纳入；未变化的契约也需要当前安装包依据。

建立精确 JSON 时保持开发期 `productionEnabled=false`，按实际页面族补齐角色和资源。只改名字时复用现有策略；语义变化按[改动分流](#architecture)处理。对应能力在安装或视图绑定时解析缓存，失败有明确状态并保留原生行为。

### 4.3 按实际页面族复核

| 页面族 | 实现入口 | 必需复核范围 |
|---|---|---|
| `legacy-activity` | `AppleMusicDualPaneTarget`、`DualPaneShell` / `ConstraintLayoutPane`、`LegacyChromeHostBinding`、旧设置桥 | PlayerActivity/BottomSheet/controller/pane、双栏资源与歌词同步、导航 holder/mini、Preference 与结果桥、旧 Editorial 策略、HLE 各表面 |
| `fragment-content` | `FragmentDualPanePreflight`、`FragmentTabletDualPaneCoordinator` / Session、Fragment chrome 与原生设置工厂 | 下表的独立契约、view 会话/事务、手机与平板导航/mini、原生视频与恢复 |

旧 Activity 的重打包 AndroidX 参数须按实际类型和原生读写恢复，不能用模块 AndroidX 对象替代。冻结基线位于 `host-applemusic/src/test/resources/baseline/`，普通适配不能更新夹具来掩盖行为差异；旧 HLE 候选语义按第 3 节保留。

| Fragment 区域 | 语义与完整性 |
|---|---|
| `fragmentDualPane` | 主状态、Fragment/Manager/事务、pane LiveData、封面 callback、BottomSheet、歌词资格与 binding 完成 |
| `fragmentLyricsRuntime` / `fragmentKaraokeWidth` | 生命周期、binding/附属容器、RecyclerView、度量/行监听；adapter 宽度、holder/item/flexbox |
| `fragmentTabletVideoHandoff` | metadata listener、浏览器/视频尺寸、updateSize/resize、模式/动画、静态基准/目标与 TextureView |
| `fragmentDualPane.songLayout` | `songLayoutPolicy` 显式选 `native` 或 `1606-column`；前者不携带覆盖，后者必须提供布局契约 |
| `fragmentChrome.members` / `playerRecovery` | 导航/菜单/进度/mini/BottomSheet 与播放器封面/背景/进入/共享/销毁恢复分别解析 |
| `settings` | ViewModel、分类/action/artwork/callback 构造与字段、Compose 入口及文件结果归属 |

`FragmentDualPaneInventory` 与 preflight 在 Hook 前解析附属组并验证关系。`layoutReview` 的 artwork、metadata、controls、progress、binding、restoration 六项保留当前构建证据。`1606-column` 是策略标识，实际成员始终来自当前档案，新策略需要相应代码和契约。

Fragment view 重建、配置变化与 controller 销毁分别关闭会话。状态保存时延后事务，恢复中的右栏 host 保持可测量。运行时 host ID 和恢复 tag 是持久约定，不能随版本号改名；SONG/QUEUE 使用原生选择，独立歌词放右栏。模块视图挂入允许的普通容器或 sibling，遵守 FragmentContainerView 的子项限制。

### 4.4 登记、交付与回退

按实际阶段记录成员/资源分析、所需检查、构建、能力状态与人工场景。完成接入后登记 `index.json`；为用户测试启用精确 `versionName`+`versionCode` 组合时，运行时状态仍按已取得证据保存。交付物与执行范围按[验收与回退](#delivery)处理，不授予邻近构建资格。

<a id="feature-index"></a>

## 5. 功能域与维护入口

每个功能域先说明**生效条件与宿主接缝**，再给出**通用维护规则及重新分析的触发条件**。新构建核对全部适用接缝和人工场景；通用策略未变化时复用其规则，无需重写业务或逐项改动设置/渲染实现。

| 功能域 | profile / 主要接缝 | 人工场景 |
|---|---|---|
| [共用歌词结果提交](#lyrics-result-submission) | `indexed`、`lyricResultSubmission`、原生安装与 item 更新 | [结果提交](feature-preservation.md#lyrics-result-submission) |
| [歌词来源](#lyrics-sources)、[简体替换和声修复](#simplified-replacement-backgrounds)、[LLM](#llm-lyrics-fallback) | 原生 TTML 观察、共享歌词效果优化门禁的单解析器修复、指针/歌曲身份及共用结果通道 | [来源](feature-preservation.md#lyrics-sources)、[LLM](feature-preservation.md#llm-lyrics-fallback) |
| [模糊](#lyrics-blur)、[CJK](#cjk-animation)、[字体](#lyrics-typeface) | 各自契约、显示状态、绑定与资源 | [模糊](feature-preservation.md#lyrics-blur)、[CJK](feature-preservation.md#cjk-animation)、[字体](feature-preservation.md#lyrics-typeface) |
| [歌词效果优化](#lyric-background-highlight)：[简体替换和声修复](#simplified-replacement-backgrounds)、[逐行双声部对齐](#line-lyric-agent-alignment)、[CJK 末词补偿](#cjk-animation)、[跨行扫光](#lyric-wrap-sweep)与活动行高亮 | 共享设置门禁；解析修复、逐行对齐与末词补偿保留三项完整诊断并按 parser 与已声明可选项汇总状态，`terminalGradient` / `lyricWrapSweep` / `lyricBackgroundHighlight` 分别核对契约、降级和失败 | [末词](feature-preservation.md#cjk-animation)、[跨行](feature-preservation.md#lyric-wrap-sweep)、[活动行](feature-preservation.md#lyric-background-highlight) |
| [玻璃导航](#chrome-glass)、[收缩](#glass-collapse-rendering) | `chrome` / `fragmentChrome`、独立能力位、滚动与 sheet 端口 | [玻璃](feature-preservation.md#chrome-glass)、[收缩](feature-preservation.md#glass-collapse-rendering) |
| [启动预热](#startup-page-prewarm) | 独立能力、`startupPagePrewarm` 与形态导航资源 | [预热](feature-preservation.md#startup-page-prewarm) |
| [平板布局、视频与恢复](#tablet-layout) | 双栏、binding、资源、视频/恢复与布局变体 | [平板](feature-preservation.md#tablet-layout) |
| [名称修正](#metadata)、[Compose 艺人页](#artist-compose-profile) | HLE 表面、目录查询与 `artistComposeProfile` | [名称修正](feature-preservation.md#metadata) |
| [设置、DPI、数据与插件](#settings-data-plugins) | 设置桥、配置/存储、公共 SDK | [设置与数据](feature-preservation.md#settings-data-plugins) |

<a id="lyrics-result-submission"></a>

### 5.1 共用歌词结果提交

**生效条件与入口。** 当前精确 profile 声明 `lyricResultSubmission`，且 `LyricsViewModel` 与既有 item-update 均命中同一档案。[LyricsResultSubmissionContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsResultSubmissionContract.kt) 定义必需角色，[LyricsResultSubmissionBridge](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsResultSubmissionBridge.kt) 管理附加、选择、索引与安装确认；[AppleMusicCustomLyricsTarget](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt) 将手动、自动和 LLM 候选送入同一通道。

```text
原生加载 / 模块候选准备
  → 当前歌曲、请求与ViewModel校验
  → 同文档派生索引就绪 → 结果容器保存并发布
  → 原生观察者 → 原生安装入口 → 匹配的安装返回确认
```

结果容器与发布实现、索引重建、身份读取、原生安装和 item 更新都须按当前包确认。Hook 声明在真正实现发布逻辑的 LiveData 基类，避免短转发方法被 ART 内联；`resultSet` / `resultPost` 使用同一精确基类，所有回调先按容器实例过滤。`pointerScript` 要核对原始返回类型：`setScript(String)` 的现有协议返回 boolean，调用方忽略返回值不意味着描述符是 void。

#### 宿主附加与交付

| 事件 | 处理与归属 |
|---|---|
| 原生安装 before / Fragment 恢复 | 先附加当前 ViewModel 和实际结果容器，再重读已存值并恢复请求；安装入参为空时仅在当前会话可证明有可交付文档时抑制该次空安装；不改写安装参数，不重入 Fragment 安装方法 |
| 原生 item 更新 after | 宿主先更新自己的 item，再重新附加；复用歌词 Fragment 既有精确条目，不主动改写 item |
| 后台候选就绪 | 主线程重读当前身份与选择器，匹配后立即提交；不匹配时等待原生身份恢复，不重新查询 |
| 安装 after | 无异常返回，且 Fragment、ViewModel、歌曲 ID、选中指针均一致时确认交付；确认表示调用返回，完整显示由人工场景判断 |
| 重建 / 请求结束 | 原生观察者按生命周期恢复结果；请求、ViewModel 或会话结束清理选择与待安装状态，不启用旧 ready-late 台账或重应用协调器 |

安装入口收到空 `SongInfoPtr` 时，先完成附加，再判断是否抑制。只有 Fragment、当前 ViewModel、请求和播放 ID 一致，并且能取得存活、ID 匹配的当前文档或已 ready 的 replacement 时，才取消本次空安装。只把此前未知的原生结果记为已观察，保留当前选择并置为待安装，再通过既有结果通道在主线程排队补发；不得改写安装入参、直接重入安装方法或为同一信息新增 profile 成员。没有可用文档、切歌或身份不匹配、容器丢失、会话关闭时保持原生空安装与既有回退。非空原生结果仍按来源优先级、文档质量和请求身份仲裁。

配置歌曲 ID 与宿主当前 item ID 分开维护。资料库补全可在同一歌曲的 `persistentId` 不变时通过 `overwriteLibraryDataFrom` 更换 `getId()`；只有 item 与播放器发布时保存的非零 `persistentId` 快照相同，才将其归入当前配置歌曲 ID。公开 `getPersistentId(): long` 不可用、值为零或不相等时不建立别名，不凭标题、艺术家或数值长度匹配，也不改写宿主 item。发布时保存 persistentId 的值，不能日后读取可能已变异的 item 来补证旧快照。

请求、候选缓存及来源优先级使用配置 ID；原生指针、时间索引与安装校验使用当前 item 的实际 ID。`OpaqueTtmlParser` 在既有 Handle 中保留逻辑 ID 与原始绑定快照，只有原生绑定仍有效时才向 core 返回逻辑 ID；TTML 本身已带正确 ID、无需初始绑定时，也须建立该逻辑身份。

提交 ID 改变时，在既有准备执行器上由保留的 TTML 生成独立原生指针并绑定新 ID，不修改已经安装的旧指针，不在主线程解析或拼接裸地址。每个 Handle 合并进行中的准备，只接纳仍匹配最新目标的结果；失败不能自行循环唤醒。当前提交指针由 Handle 保持引用，历史指针仅作弱身份识别；宿主释放指针后重新准备，不因另一个指针存活而掩盖实际传入对象失活。没有原文时不猜测复制或所有权行为。文档去重同时核对绑定 ID 快照及当前提交指针身份，不能把新指针折回旧的已安装对象。

定位结果保护未生效时，使用有界诊断同时记录加载、item 更新、安装与结果写入的入口及早退原因，关联请求、播放 ID、owner、ViewModel、容器和选中指针身份。只有成功阶段日志缺失不能证明 Hook 未命中；包内代码、进程加载、入口调用、条件拒绝和最终显示分别判断。诊断不输出歌词正文、凭据或其他结果容器的内容。

结果写入、实际容器保存与安装确认分别保存。所有容器提交只在主线程进行，与原生写入保持同一线程。身份对齐后，选中指针尚未确认安装可经容器补发，每次外部恢复回调最多一次，自身写入不再次排队；确认后同文档去重，但去重前必须确认实际跟踪 LiveData 的当前值仍保存该指针。空值或更换容器不能沿用旧确认，应保留可用选择并经通道补发；实际保存值读取失败时保留选择、报告原因并等待后续恢复，不能将读取失败当作去重成功。附加以及同一 ViewModel 的新请求都要重新读取实际 `lyricsResult` 容器，不能只在新 ViewModel 时刷新。换到新 Fragment 实例且已有选择时重新等待确认；同实例重复附加不重置已确认状态，除非实际容器为空或不再保存选中指针。

原生结果的接收归属与提交目标 ID 分开校验。同一当前 canonical 请求、同一当前 ViewModel 的显式加载已捕获某 raw native ID 时，可只保留一个属于该 ID 且仍存活的结果；即使 Fragment 当前 native ID 尚未对齐也不得因 canonical 相同而丢弃，但不得提前提交。item-update、resume 或 ready 唤醒完成 owner 对齐后，再经实际结果容器恢复既有选择与仲裁。新请求、换 ViewModel、切歌或关闭使该暂存退役；无关或失活结果拒绝，现有 native 目标 ID 优先级不变，也不得为此新增线程、定时轮询、profile 角色或改写原生 item。

#### 结果与派生索引

替换前先按新文档重建时间范围索引，再写入结果容器；保存值、观察者收到的值与身份读取须对应同一文档。原生迟到结果也经过当前选择规则，不能只替换通知参数而留下内部旧值。

容器先保存后通知，异常可能发生在任一步。提交抛错时读取实际保存文档，将选择与索引对齐，避免第二次 `setValue` 回滚并重复观察者副作用。只有同一 ViewModel、同一容器、同一请求代次仍有效时才对齐；读取或重建失败只限量报告原因、撤销索引成功标记，保留下次重建机会。

索引成功只在当前请求的同一存活指针上复用：实际重建前、`setScript` 前、请求或 ViewModel 结束时失效。模块预建后紧随的同指针原生构建可跳过一次，`postValue` 先建索引再主线程交付可复用成功结果；其他原生调用仍执行。构建失败、交叠或过期构建不保留复用标记；观察者异常不能当作索引构建失败。

#### 请求、文档与异步边界

| 维度 | 维护规则 |
|---|---|
| 显式请求 | `loadLyrics` 捕获的请求 ID 为权威；播放缓存滞后不改写请求或重新归属原生结果，同 ID 通知不清理领先请求 |
| 推断请求 | 仅同 ViewModel 且结果 ID 与 Fragment 当前 item 一致时可据独立证据修正；已知旧 Fragment 与迟到指针不能反推覆盖新缓存，身份不确定或往返时等待 |
| 未知 / 已退役请求 | 优先用已验证当前播放身份恢复，播放缓存不可用且未捕获加载时才用歌词页面身份；同 ViewModel 附加不覆盖已捕获非零请求 |
| item 更新退役 | item 从旧歌更新到新歌而请求仍旧时，只退役旧请求；已经领先的新请求保留 |
| 已存结果 | 附加即绑定容器，存着上一首文档时保持等待；空态之后也重读实时指针，以发现绕过写入 Hook 的有效文档；模块替换指针不反作原生质量证据 |
| 文档去重 | 以解析器提供的文档文本身份按请求去重，等价文本的不同对象视为同文档；后台结果须核对歌曲、请求代次与当前会话 |
| 无身份空值 / 错误 | 存在已确认当前文档时抑制无身份发布，后续索引使用保留文档；无当前可用文档才进入原有空态，不能凭到达顺序推断来源 |
| `postValue` | 只读观察，保留原生 pending 槽位与主线程合并；同一投递任务前仅最后一值交付，中间值不额外选择或通知，宿主索引调用仍按原流程处理 |
| 空 Pair 标签 | 只对无文档身份的空 Pair 弱引用记录发布代次；交付时拒绝早于真实请求/owner 边界的标签 |
| 真实边界 | 原生加载、更换 ViewModel、实际切歌、请求退役、ViewModel 清空或会话关闭推进边界；未知绑定恢复只改内部代次，不误拒同请求空态 |
| 非空交付 | 交付时按当前请求与实时身份判断，不用入队时播放缓存快照拒绝当前歌曲；容器失去跟踪或关闭后恢复原生交付 |

#### 暂缓附加的候选

不匹配的 Fragment 只以有界弱引用保存 Fragment 与首次记录的 ViewModel，不立即采用、不改 item。缓存或就绪通知在主线程重新读取身份和选择器，确认 ViewModel 仍同一实例、View 未销毁后再附加；未知绑定恢复不吞掉唤醒，候选无需再收到生命周期事件也能恢复。

活动 owner 的优先权要求其 View 仍附着、字段仍指向当前 ViewModel、Fragment ID 匹配请求/当前歌曲。一次 load 捕获不代表活动面板，无 owner、已脱离或已销毁的旧面板不阻止新候选。ViewModel 清空同时清除 owner 并唤醒其他候选，候选回收、View 销毁和会话关闭及时清理。

**变更与降级。** 必需角色以当前 Contract 的 requirements 为准；v1 复用既有 item-update 与公开 `getView`，不为同一信息另增私有成员。协议或发布载体、保存/通知顺序、合并语义、索引构建或 owner 边界变化时，重新分析并同步契约/解析器；不将当前角色数量写成永久限制。精确解析或公开生命周期入口不可用时整个通道降级，提交异常保留宿主实际已发布结果，不做全局 LiveData 替换或全局观察。

[证据清单](host-adaptation-checklist.md#lyrics-result-submission-checks) · [人工场景](feature-preservation.md#lyrics-result-submission)

<a id="lyrics-sources"></a>

### 5.2 歌词来源与候选准备

**宿主接缝。** 当前歌曲身份由 `CurrentItemIdentitySeam` 读取精确 item、公开 ID 与可选 persistentId；配置身份仅按当前发布快照的精确关系归一，原生提交仍绑定实际 item ID。自定义歌词的原生 parser、SongInfoPtr、文档身份与可用性谓词由 `AppleMusicCustomLyricsTarget` 接入。手动、自动、刷新及 LLM 只准备候选，最终均通过[共用结果通道](#lyrics-result-submission)。字体、模糊、翻译、发音和其他订阅者继续使用原生显示链。

| 层 | 维护入口与职责 |
|---|---|
| `core` | [CustomLyricsReplacementSession](../core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsReplacementSession.kt)、[AutoLyricsReplacementSession](../core/src/main/kotlin/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt)、[AutoLyricsRefreshSession](../core/src/main/kotlin/dev/amenhancer/module/hook/AutoLyricsRefreshSession.kt)：资格、候选、代次、取消、解析与缓存策略 |
| `core` 来源 | [OnlineLyricClients / AutoLyricsSourceResolver](../core/src/main/kotlin/dev/amenhancer/module/lyrics/source/OnlineLyricClients.kt)、AMLL 转换、Lunabeat 与 AM-Lyrics：来源请求和格式转换 |
| `host-applemusic` | `CurrentItemIdentitySeam`、`OpaqueTtmlParser`、`TtmlTimingObservationRegistry` 与结果 Bridge：原生对象及反射只在适配层 |
| `app` | `AppleMusicAssembly` 与 [AutoLyricsReplacementSession.kt](../app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt) 的 `createAutoLyricsRuntime`：存储、执行器、来源服务与配置装配 |

**通用维护。** 歌曲禁用优先于模块候选，可用手动结果优先。`automatic_lyrics_prefer_native` 默认及缺键开启；开启时始终先判断达标 AM 原生，关闭时 AM 原生按 `automatic_lyrics_source_order` 的位置参与选择。两种模式共用既有逐字/语言/译文资格：已观察的原生须为逐字，已声明外语的逐字原生还须有非空译文；未观察的现存原生指针继续保守保留，不从正文猜测语言或质量。

原生优先关闭且原生符合上述资格时，排序模式才仅查询其前置第三方来源，前置均未命中后采用原生。不达标或不可用时跳过原生项，仍按顺序查询全部第三方来源，包括排在原生之后的 Lunabeat；全部未命中时仍保留可用原生，不清空当前歌词。AM 原生来自宿主结果，不新增网络客户端，也不发布成自定义条目。

来源顺序始终以稳定 ID 保存为同一份完整顺序，不新增显隐专用配置；默认 AMLL → AM-Lyrics → AM原生 → Lunabeat，配置解码及写入过滤未知项、去重并补齐缺项。规范化时若 Lunabeat 位于 AM 原生之前，只将 Lunabeat 移到 AM 原生之后，其他来源的相对顺序保持不变；读取、保存与运行时入口共同使用该结果，旧配置不能绕过约束。`automaticLyricsLookupSources` 统一计算结果选择、解析观察、可用性补偿和切歌预热的第三方查询范围。来源范围变化撤销旧请求代次，迟到结果不能越过新的原生位置；宿主通道适配不能重置用户顺序或改变这套选择规则。

自动发布的条目通过现有 `CustomLyricsEntry.autoManaged` 标识归属，缓存只在其 provider 所处位置生效，不抢占更靠前的来源或原生。新自动结果可替换已有自动管理项；手动导入、用户明确选择及缺少归属字段的旧条目默认受手动保护，不根据 provider 名称推断归属。归属随索引、备份和来源更新保留；用户明确编辑后按手动项保存。来源刷新只更新所记录的 provider，不把“更新”隐式改成重新选源。ETag、退避及切歌/退役取消继续沿原会话维护。

歌词管理页的“自动实时补全”进入独立二级页，总开关、原生优先和来源排序集中于该页。来源行使用右侧拖动柄：按住后上下拖动，松手保存；取消拖动不保存。拖动柄获得触摸后锁定行拖拽，柄外手势仍归页面滚动，拖动期间列表滚动不得与改序争用；触摸排序与无障碍的上移/下移动作更新同一份顺序。改序仅局部移动当前列表并写设置，不重建页面；连续改序及保存保持列表滚动位置和无障碍焦点，不造成屏幕闪暗。保存时必须先同步内部隐藏原生占位索引，使后续改序从最新完整顺序继续。排序列表使用模块自己的 AndroidX 与资源上下文，按宿主有效密度和配置展示，不借用宿主私有列表成员或资源 ID。原生优先开启时隐藏原生行；保存时采用用户调整后的可见来源顺序，并把隐藏原生占位放回：原位置仍能保持就保持，若 Lunabeat 已移动到该旧位置之前，则只把占位前插到 Lunabeat 之前，其他可见来源的相对顺序保持不变。隐藏期间旧原生位置不限制可见来源互调。原生优先关闭、原生与 Lunabeat 同时可见时，Lunabeat 不能上移跨过原生，原生不能下移跨过 Lunabeat；触摸拖动与无障碍上移/下移遵循同一约束，被阻止时不改顺序、不保存，完整顺序始终保持原生在 Lunabeat 之前。排序标题右上角复用既有重置 SVG 图标，只恢复 `ModuleSettings.DEFAULT_AUTOMATIC_LYRICS_SOURCE_ORDER`（AMLL → AM-Lyrics → AM 原生 → Lunabeat），不改变自动实时补全总开关或原生优先；重置后按当前原生优先状态重建显隐，隐藏原生仍按上述占位规则处理，完整顺序中 Lunabeat 仍位于 AM 原生之后。重置、原生显隐等同页刷新可沿用同页重建，但不得把旧内容当作跨页来源，不重放切页透明度或快照动画；真正跨页继续使用原有切页过渡。排序标题区沿用原有紧凑行样式，不额外设置最小高度或居中标题行；右上角重置沿用无底色 SVG 图标、主题配色和 44dp 触摸范围，标题与说明同列并保持 4dp 间距，标题、说明和来源行沿用相同的左右内容边界。设置保存和返回复用既有二级页流程，LLM 页面保持独立入口。

TTML 文件有界读取、输入合法性、原生指针存活/ID 检查和来源转换分别保留。自动来源发布自定义条目与临时缓存迁移继续使用已有发布器，刷新只处理允许更新的来源条目；LLM 使用独立缓存，不混入该发布链。TTML 结构与 AMLL 时间轴整理见[格式说明](apple-music-ttml-format.md)。

AMLL 转换器及 `scripts/amll_to_apple_ttml.py` 的译文/音译和声节点须显式绑定 TTML 默认命名空间和 `ttm` 元数据命名空间。`iTunesMetadata`、轨道及 `text` 属于 Apple 命名空间，内部 `x-bg` 的 `span` 属于 TTML，不能让它继承 Apple 默认命名空间；维护 Kotlin 与 CLI 时同步这项输出约定。

仅原生成员变化时更新精确档案；若原生质量/可用性含义、TTML 时间轴或生命周期变化，再调整对应宿主接缝或业务策略。[来源人工场景](feature-preservation.md#lyrics-sources)负责行为要求。

<a id="simplified-replacement-backgrounds"></a>

#### 5.2.1 简体系统语言下的替换和声修复

**安装与单一解析接缝。** `FeatureInstallation` 始终登记 `LyricsParserFeature`，由现有 `ModuleSettings.lyricEffectsOptimizationEnabled`（配置键 `lyric_effects_optimization_enabled`）控制生效；默认及缺键关闭，更改后按现有设置策略重启宿主。关闭时该 Feature 返回 `DISABLED`，且不调用 `LyricsParserTarget.install()`。开启时经 `LyricsParserTarget.install()` 委派 `AppleMusicCustomLyricsTarget.installParserRepair()`；宿主解析器不可用时只降级本项修复，不影响普通原生显示。

`CustomLyricsFeature` 开启时也可调用同一 `installParserRepair()`，以便自定义替换、自动来源或 LLM 取得原生元数据和修复前 TTML。两个入口复用同一目标实例及同一个解析 Hook；重复调用返回已安装结果，不产生第二 Hook。before 阶段先检查共享设置，再按语言和修复契约处理 `String` 参数；共享设置关闭时直接跳过输入改写。after 阶段的观察者仍接收修复前的原始 TTML，自定义歌词不另装解析 Hook。

**完整签名。** `TtmlSongInfoFromTtml` 必须解析到实例方法 `TTMLParser$TTMLParserNative#songInfoFromTTML(String): SongInfo$SongInfoPtr`：owner 为解析器类，非静态，唯一参数为 `String`，返回 `SongInfo$SongInfoPtr`。模块解析器的 `TtmlParserNative` 与这个既有符号必须指向同一宿主入口。完整签名不可验证或 Hook 安装失败时报告本能力降级并保留原生解析；不得用相似签名或原生内存位移补齐。

**语言与开关门禁。** 修复只在“歌词效果优化”开启且用户所选的系统语言命中时执行，不另增设置；开启后不要求自定义歌词替换已启用，自定义歌词开关也不能替代共享设置或语言门禁。locale 的语言必须为 `zh`：脚本显式标为 `Hans` 时命中；脚本缺省且地区为 `CN` 或 `SG` 时命中。`zh-Hant`、`zh-TW`、`zh-HK`、`zh-MO`、缺省脚本但地区不是 `CN`/`SG` 的 locale，以及非中文 locale 都直接返回原文。原生 `replacement` 轨是否存在、是否会被 Apple 的原生资格路径采用，不能替代这两道门禁，也不能放宽门禁。

**修复契约。** 专项处理入口为 `AmllTtmlFormatConverter.repairSimplifiedReplacementBackgrounds`；它不做通用 XML/TTML 转换，只按下列关系从唯一、明确标注 `type="replacement"` 与 `xml:lang="zh-Hans"` 的替换轨取得背景词文本：

1. 以唯一的正文 `itunes:key` 对应唯一的 `<text for="该 key">`。
2. 在每条 `text` 内按 `x-bg` 分组顺序对应正文与替换轨；两侧均声明的组起止时间必须数值等价。
3. 每组的背景词数量相同，所有词必须具备完整、有效且唯一的 `begin`/`end`，以数值等价的起止时间匹配词文本。
4. 仅替换正文既有 `x-bg` 内对应词 span 的文本内容。正文行与 span 的 begin/end、层次、属性和其他标签，主唱前景文本，以及翻译、音译等其他轨道全部保留。

修复仅接受默认 TTML 根及规范的 `ttm`、`itunes` 绑定；替换轨容器和 `text` 属于 Apple 命名空间，其中每个 `x-bg` 必须显式切回 TTML。其他前缀或重绑定无法可靠解释时保留原文。输入超过既有 TTML 大小/标签数上限、轨道不唯一或 key 重复时整份透传；缺少对应内容、分组/词数/时间不兼容时整行保持原文，该行不部分写入，其他可对应行仍可修复。修复异常不能阻断原生解析。

**原文与观察。** 发生修复时，before 阶段另外保留未经修改的原始 String；after 阶段向 `TtmlTimingObservationRegistry` 和 LLM 输入登记该原文及解析结果身份，不能把修复后的宿主入参当作原生原文。共享设置关闭时不写回任何文本，after 阶段仍可把原样的宿主输入交给观察者，因此自定义歌词开启时原生元数据与 raw-TTML 观察继续工作。`OpaqueTtmlParser.isModuleParse()` 继续排除模块解析的原生来源归属。普通原生歌词与模块生成的文档都经过同一个设置及语言门禁；设置开启时，普通原生歌词不依赖自定义歌词开关即可修复。

人工复核至少覆盖：共享设置关闭时普通原生歌词与自定义歌词输入都不发生改写，且开启自定义歌词时观察仍可用；设置开启后，`zh-Hans`、`zh-CN`、`zh-SG` 下普通原生歌词在自定义歌词关闭时修复成功；`zh-Hant`、`zh-TW` 与非中文 locale 保持原文；模块生成文档走同一门禁；修复后正文时间、标签、主唱前景与其他辅助轨不变；缺 key、重复 key、时间或分组冲突时原歌词仍能正常解析和显示。

<a id="llm-lyrics-fallback"></a>

### 5.3 LLM 原生歌词翻译

**生效与接缝。** LLM 复用歌词替换、自动补全与结果提交，不另建宿主成员映射。观察精确 `TtmlSongInfoFromTtml` 的真实调用，以原生结果指针弱身份关联有界原文快照；`OpaqueTtmlParser` 标记并排除模块自己的解析，防止生成歌词回流为原生输入。当前歌曲与原文的对应关系须可验证，不能使用“最近的 TTML”或视图文字补猜。

#### 通用维护：输入与输出

| 位置 | 职责 |
|---|---|
| [LlmLyricsTtml.kt](../core/src/main/kotlin/dev/amenhancer/module/lyrics/LlmLyricsTtml.kt) | 时间轴/语言/译文资格、正文抽取、输出副本补 key 与 Apple 译文轨写入 |
| [LlmLyricsClient.kt](../core/src/main/kotlin/dev/amenhancer/module/lyrics/LlmLyricsClient.kt) | 提示词、共享请求编码与传输、严格响应校验、期限与取消 |
| [LlmLyricsCache.kt](../core/src/main/kotlin/dev/amenhancer/module/lyrics/LlmLyricsCache.kt) | 独立文件缓存与最近使用淘汰 |
| [LlmLyricsReplacementSession.kt](../core/src/main/kotlin/dev/amenhancer/module/hook/LlmLyricsReplacementSession.kt) | 播放代次、原生被采用的门禁、去重与原生指针准备 |
| `app` / `host-applemusic` | 设置/私有存储/后台装配，以及原文观察/模块解析排除/共用结果提交，各守既有边界 |

手动/禁用状态及来源选择先于 LLM 缓存；只有最终采用 AM 原生时才允许补翻译，每次再播放重新判断。所需的第三方查询尚未完成时不开始翻译；原生不达标时须等全部第三方未命中后回退原生，再判断翻译资格。达标原生之后的来源不参与本次查询；采用第三方或手动结果时撤销在途及就绪译文。`onNativeSelected` 只由选源结果授予资格，输入仍须来自当前原生指针关联的原文，不以模块生成的 TTML 代替。

输入须有可用逐行或逐字时间轴；纯文本、无时间轴、空歌词不翻译。根 `xml:lang` 的中文、简繁体/地区标签与中文别名整首跳过；逐字文档缺根语言时不调用 LLM，自动查源仍按所选策略判断。逐行文档保守判断文本，含汉字的日文不能直接判中文。

日文、韩文中的纯汉字行依据声明语言，明确标中文的行/词保留；其他外文歌曲的中文行按文本判断保留。任意语言原生译文轨阻止整首翻译，空 `translations` 容器不算译文。缺 `itunes:key` 的逐行文档可按行位置处理，原 key 重复而无法唯一对应时拒绝。

模型只返回等长字符串数组，目标固定简体中文；业务层写入 `translation type="subtitle" xml:lang="zh-Hans"`，不让模型生成 XML 或时间戳。输出副本保留原文、词时间、声部、音译、行序及重复/空白行关系，缺 key 时只在副本补稳定唯一标识，已有 key 原样保留；复用已有空译文容器，输入原文与缓存身份不变。行数、类型、空值或结构不符时保留原歌词。

翻译提示词要求处理外语、方言和俚语，保留已有目标语言句子及重复音节；结合全篇上下文、语气和意象，逐句审校润色，强调信达雅中的“雅”，简单句不过度复杂化。同句及重复段落按语境和中文语感处理，和声须完整翻译，不能复制主唱译文充数；中文译文不使用逗号和句号。维护时同步清理冲突的旧规则和示例，风格要求与返回格式约束分别维护。

每个段落的主唱与各个 `x-bg` 和声分别提取、分别翻译，按段落顺序先主唱后和声建立输入，提示词标明过滤后数组中的和声位置且禁止合并。语言判断在各声部独立进行，声部自身或首个有效子节点的显式语言先于祖先继承，主唱推断语言不传给和声，中文声部原样回填；主唱为空时保留段落占位，仍可翻译和声。写回时每个段落只有一个 `text for="段落key"`，主唱译文为直接文本，和声译文为其内部的 `span ttm:role="x-bg"`，显式声明 `xmlns="http://www.w3.org/ns/ttml"` 和 `xmlns:ttm="http://www.w3.org/ns/ttml#metadata"`。多个和声保留各自对应及顺序，补 key 仅执行一次并由该段所有声部共用，不能给每个和声另建段落译文条目。

#### 通用维护：客户端、缓存与取消

接口使用配置的 OpenAI Compatible Chat Completions，Bearer、非流式、有界响应与 60 秒期限共用客户端，不隐式切换服务。翻译和手动连通测试共用 `encodeRequest`，按“模型思考”布尔在顶层发送 `thinking.type=enabled/disabled`，`stream=false` 单独控制非流式。思考默认及旧配置缺键均关闭，模式进入不可变请求配置和缓存身份；该字段是服务端扩展，拒绝时按失败处理，不移除参数重试、不按地址/模型维护分叉。

缓存为宿主私有 `files/ampp-llm-lyrics`，最多 100 首，命中更新 LRU，每首只保留一项。原文、规范化接口、模型、思考模式或提示词版本变化使旧项失效，旧缓存缺思考字段按关闭匹配；仅成功并被原生解析接受的结果可持久化，密钥不写缓存。不调用 `AutoLyricsPublisher`，不生成自定义条目，不参与自定义歌词备份或在线更新。

主唱/和声提取或输出结构变化影响缓存兼容性时，提升现有 `LlmLyricsClient.PROMPT_VERSION`，使旧格式在下一次合格查询时失效；单纯调整翻译风格时可保留版本并继续复用已有缓存。不增加缓存目录或配置 schema，也不批量改写自定义歌词。

网络/XML 在后台，单播放失败不循环重试。切歌、原文/配置变化、高优先级结果或关闭撤销旧任务，迟到完成仍通过共用通道校验。正常资格跳过保持安静，不保留逐阶段诊断集合、耗时统计或原因回调；原生 TTML 观察属于功能输入，不能随临时日志删除。连通错误通过[设置页](#settings-data-plugins)的手动测试展示。

**变更触发。** 新构建复核原文 parser、指针身份和结果通道；原生不再经过可观察入口时先停用兜底，再在宿主层建立有证据的输入。时间轴/语言/轨道变化在通用 TTML 层适配，版本名、反射、Hook 不进入客户端或缓存。

[证据清单](host-adaptation-checklist.md#llm-lyrics-fallback-checks) · [人工场景](feature-preservation.md#llm-lyrics-fallback)

<a id="lyrics-blur"></a>

### 5.4 歌词模糊

**生效与接缝。** Android 12 起，两类页面族均由独立 `lyricBlur` 契约接入，不依赖双栏开关或预检。[AppleMusicLyricBlurContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurContract.kt) 定义完整角色和关系；HostPort 只读已应用显示，Runtime 跟踪活动 root，`LyricBlurViewSession` 管理每 root 的绑定和帧驱动，Renderer / EffectOwner 管理动画及效果所有权。

| 每构建的接缝 | 必须保持的语义 |
|---|---|
| 生命周期、binding、RecyclerView、触摸/滚动及 child listener | 当前视图归属、绑定与释放均可确认 |
| 行/词 adapter 的提交、快照、映射、类型、稳定 ID、安装与回收 | 在实际提交完成后重读已应用显示；延迟提交期间保持旧显示，子集输入导致原生跳过更新也要重读 |
| token/reader、原生行词 ID、行序号与 adapter 位置 | `nativeLineId` 用于文档身份，`lyricOrdinal` 用于距离，adapterPosition 须先映射；native getter 与 adapter 稳定 ID 分别核对 |
| 主词/背景词/发音/背景发音四路模型及动画 | 保护真实文字动画，装饰辉光/缩放/位移不充当高亮证据 |
| 首句/文档结束时间、mediaBrowser/mediaPosition 与各 Seek 入口 | 前奏、Ending 和 Seek 有独立证据，集合缩小不等于 Seek |

显示集合以 adapter 已提交状态为准，Native/VM 只触发刷新，不直接拿 raw 集合替换，也不因某入口成功而屏蔽其他来源。文字动画递归排除 `KARAOKE_WORD_LIFT_TAG`，只观察相关子动画的开始/结束/取消；弱身份映射避免对象 hashCode 随绑定变化丢失通知。保护与焦点独立，间奏焦点不撤销仍显示的行/词保护。

Native vector/pointer 在 Hook 当次同步解码，只把复制数值送主线程。`Complete` 空集、Pending、Partial、Failed 分别处理，不完整读取不当作空高亮；无可信显示时平滑解除焦点，等有效事件恢复。`NO_POSITION`、未知类型和身份不一致行不参与距离计算。

#### 通用维护：状态、帧与效果

用户可见曲线、过渡时间、浏览恢复及边缘要求统一在[模糊矩阵](feature-preservation.md#lyrics-blur)维护；策略入口为 [LyricBlurState](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricBlurState.kt) 与 [BidirectionalBlurPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/BidirectionalBlurPolicy.kt)。

每个 root 独立会话，全屏与右栏可并存。视图/文档/播放/交互代际在主线程归并，一帧用同一状态和行快照。以 adapter 的文档安装/清空回调及 adapter 实例变化建立文档边界；adapter 实际重装 TTML 时包装引用不变也推进代际。外层 installDocument 请求只触发显示重读，原生拒绝的请求不单独建立新边界。Seek 清旧词流与过渡。

前奏、间奏、保护与 Ending 分开保存。空高亮不能推断前奏/间奏；三点须实际绑定可见且有尺寸后切换焦点。末句有效焦点在正常清空后保留 Ending；暂停重建可据完整文档最大结束时间与媒体位置恢复，读取失败不算结束。换文档/Seek 清旧焦点，手动浏览与恢复规则继续生效。

行动画按逻辑行保存，换绑撤销旧租约，首获 adapter/文档/binding/布局即安排首帧，暂停打开也可初始化；等待仅靠后续事件，不增加定时轮询。触摸/滚动只观察，不替换 OnTouchListener 或消费事件；恢复任务绑定会话/交互代际，继续触摸、滚动、换歌及销毁使旧任务失效。

```text
最终半径 = 量化并限制到0–32(max(动画焦点半径, 当前边缘半径 × 动画边缘权重))
```

边缘矩形计入变换、位移、滚动及视口 padding，几何变化不重启焦点动画。每会话一个帧驱动，状态/几何稳定且动画结束后停止连续帧；DECAL 效果最多缓存 32 个，量化半径及宿主效果版本不变时不重写。

模块模糊持有列表裁剪租约：允许逐子项模糊扩散，列表仍按视口与宿主裁剪交集裁切，保留 padding/外层渐隐；效果归零、行释放、列表变化或关闭时归还仍拥有属性，不改行高、字体、间距或透明度。效果观察在视图创建前接入 SDK View 构造与 setRenderEffect，记录最新已知宿主效果，模块写入带重入标记并合成；初始效果未知的既有 View 不强行接管，释放只撤销自身最后写入，不用隐藏 getter 或 alpha 清理。

**变更与降级。** 仅名字变化更新 profile；提交时序、模型或生命周期变化调整 Contract / HostPort / Runtime，复用共享状态与渲染。精确资格/API → 全部解析 → preparing 注册 → activate；失败关闭整组，视图关闭撤销帧/恢复任务/监听及仍拥有的效果。诊断沿用 `AMPP-LYRIC-PROBE`：DEBUG 才输出，每会话最多 128 条，ID/行观察最多 32 项，摘要/失败限量，不记录歌词文本。

[证据清单](host-adaptation-checklist.md#lyrics-blur-checks) · [人工场景](feature-preservation.md#lyrics-blur)

<a id="cjk-animation"></a>

### 5.5 歌词辉光与末词补偿

**生效与接缝。** `cjkKaraokeAnimationEnabled` 只控制 CJK 辉光，按原安装方式重启宿主生效，关闭时不安装 CJK 辉光。末词羽化补偿和英语拆词辉光由“歌词效果优化”的 `ModuleSettings.lyricEffectsOptimizationEnabled`（配置键 `lyric_effects_optimization_enabled`）门禁并重启生效。[AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 的现有 `lyricRepairs` lazy 组在 `LyricsParserFeature` 门禁下独立安装，并合并解析修复、逐行双声部对齐、末词补偿与英语拆词辉光的结果，保留各项完整诊断消息；复用现有 Target 和设置。`cjk.terminalGradient` 只登记补偿契约，与 CJK 辉光设置独立。CJK 辉光资格由 profile 的 `cjk.maxSpanGlowCharacters` 限定：缺省 `1`，只放行单个未合并 CJK 词；精确 profile 显式设为 `3` 时，另放行 2～3 字的单个原始未合并 CJK span。前景绑定与结束清理、`cjk.foregroundTextField` 和 bindingOwners 独立核对。`terminalGradient` 的角色仍由 Contract 解析，以代码为准，描述符只维护在精确 profile。

| 角色组 | 新构建复核的含义 |
|---|---|
| build / margin | 原生扫光调用与余量计算，完整参数、返回、实例/静态及嵌套调用关系 |
| reader / 行词向量 | 当前行原文/发音词的身份、时间和完整序列 |
| 词 ID / 文本 / 时间 | 尾词判定所用对象与当前扫光词一致 |
| animationTag | 接口 getter 和原生延迟补亮标签的语义，接口仅按 invocationOnly 调用 |

**多字 span。** 放行前须 `trim` 后全部属于现有六个 CJK 区块，字数为 `2..min(cjk.maxSpanGlowCharacters, 3)`，原生 span 总时长至少为字数 × 1000ms（即平均每字 ≥1000ms）。累计时长须等于原生 span 时长，累计文本长度须等于字数，`splitBindingCount` 须在 `0..字数`，且只接受前景。不得把宿主合并的多个原始 span 当作一个 span 按时长放行；原生时长、文本、词分组和长度 g 均保持不变。单字回落子动画延迟修正只作用于原单字分支，多字保留宿主错峰。

**英语拆词辉光。** 复用 `AppleMusicCjkKaraokeAnimationTarget.installSplitWordGlow`，精确 profile 须显式声明 `cjk.splitWordGlow=true`，缺省关闭。保留原生整词判定；只为长度超过 7 的前景 Latin 拆词组补充“任一原始 span 时长 ≥1000ms 且 trim 后长度为 2～7”的资格，标点计入长度。同组至少两个成员，须同属原生行 `a` 和非空组 ID `l`，各段文本 `c` 与时长 `e` 累计须对应原生 `g`、`f`，不可跨空格或混入其他文字脚本。仅在当前原生动画调用内临时令 `g=7`，正常、提前返回及异常退出均恢复。各片段以整词中心为共同横向 pivot，原生辉光上升同时开始、回落统一在上升时长后开始；屏蔽该组 `TimeAnimator` 的片段拉扯，原文、binding 及 TTML 分词扫光时间保持。无有效布局或词组已跨视觉行时不额外放行。新构建须核对回落 listener 的 `c` 视图和 `d` 辉光函数、上升 listener 的 `a` 函数及其 `a` 视图，以及原生回落结束的 pivot 恢复；该作用域内的 `TimeAnimator` 须只负责辉光片段间距。另须确认 `g` 的门槛语义及间接读取在原值与 7 下分支相同，不得影响扫光几何；不合并 TTML 或词模型。

**通用维护。** [CjkTerminalGradientPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/CjkTerminalGradientPolicy.kt) 对合法 CJK 尾词取 `max(original, feather)`，只接受有限值与 `0 < feather <= 1`。原余量更大时保留，无效几何透传；尾词不额外要求触发长尾辉光。

当前 reader 的原文与发音向量分别选合法 end 最大的词 ID，同 end 取后出现词。向量大小须在 2..4096，时间 begin 非负且 end 大于 begin；两路结果按 reader 弱身份与行 ID 缓存。补偿只影响当前扫光对应 CJK 词；现有协议的build第八参（零基索引7）为true时走原生分支，Latin文本也保持原生。margin词对象须与当前扫光词一致，嵌套/异常退出恢复线程作用域。

有效补偿后从原生返回动画列表移除 `KARAOKE_SPLIT_ON_CHARS_LANGUAGES_RUSH_GRADIENT_TAG` 延迟补亮，其他动画、时长、分组、滚动及位移保留；未补偿时原列表不变。它不承担换绑保护或跨行遮罩修复；[跨行能力](#lyric-wrap-sweep)与末词补偿均由“歌词效果优化”门禁，CJK 辉光开关只控制辉光，不控制它们。

**变更与降级。** CJK 辉光、英语拆词辉光与末词补偿分别安装、分别报告，任一失败不撤销其他项。`CjkKaraokeAnimationFeature` 只负责 CJK 辉光的安装和状态；末词契约缺失、解析或注册失败只使补偿不支持或降级。`lyricRepairs` 保留各项完整诊断消息，未声明能力的 `Unsupported` 也原样保留；整体状态按 parser 与已声明可选项计算：全为 `Active` 时为 `Active`，仅全为 `Unsupported` 时为 `Unsupported`，否则为 `Degraded`。子项失败或不可用不撤销其他已安装项。新增宿主构建须独立确认原生 span 元数据、前后景绑定和 split binding 语义后，才能在精确 profile 显式启用多字放行或英语拆词资格；默认值不继承前版本。时间、分组、标签或原生特殊分支语义变化时重新分析；若新宿主复用同一reader并原地改写词数据，须补相应缓存失效入口，不能只复制成员名。

[证据清单](host-adaptation-checklist.md#cjk-animation-checks) · [人工场景](feature-preservation.md#cjk-animation)

<a id="lyric-wrap-sweep"></a>

### 5.6 跨行歌词扫光

**生效与接缝。** 设置 `ModuleSettings.lyricEffectsOptimizationEnabled`（配置键 `lyric_effects_optimization_enabled`）是简体替换和声修复、逐行双声部对齐、CJK 末词补偿、跨行扫光与活动行高亮共用的现有开关，默认及缺键均为关闭，更改后重启宿主生效。关闭时 `LyricsParserFeature`、`LyricWrapSweepFeature` 与 `LyricBackgroundHighlightFeature` 都返回 `DISABLED`，且既有 `lyricRepairs` 组不调用解析修复、逐行对齐或末词补偿的 `install`；自定义歌词开启时仍可安装同一解析 Hook 供观察，但 before 不执行修复。开启后，解析修复、逐行对齐和末词补偿共用既有 `lyricRepairs` 安装入口，保留三项完整诊断并按前述规则汇总整体状态；跨行扫光与活动行高亮仍分别按精确 profile 的 `lyricWrapSweep` 与 `lyricBackgroundHighlight` 契约安装和核对，各自报告支持、降级或失败。任一子项不可用不阻止其他子项或撤销其安装。跨行扫光的 `LyricWrapSweepTarget` 使用能力键 `FEATURE_LYRIC_WRAP_SWEEP`，不新增独立设置。CJK 辉光开关只控制辉光，不控制这些 Feature。

入口为 [LyricWrapSweepContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepContract.kt)、[Geometry](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepGeometry.kt)、[AppleMusicLyricWrapSweepTarget](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricWrapSweepTarget.kt) 和 core 的 [LyricWrapSweepPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricWrapSweepPolicy.kt)。Contract 的必需清单与关系校验是协议依据，不另写混淆名或支持版本表。

| 接缝组 | 需要重新确认的语义 |
|---|---|
| build / update / rushUpdate / fadeUpdate / staticMask / maskUpdate | 实际扫光、补亮、渐暗与静态恢复路径，调色板/stop/原生前沿的读写归属 |
| 词时间 / wordAnimator / 媒体浏览器 | begin/duration 与 position 共用毫秒时间轴，Seek 与暂停恢复归属 |
| maskFirst / maskLast、topMasks / bottomMasks、四路词 Map | 视觉行键及 child 范围、原文/发音 bank、holder 绑定与模型身份 |
| binding/textView/callback 与回收入口 | 文本/Layout/根身份以及 rebind、bind、recycle、document、clearMasks 的完整失效路径 |

成员关系在安装前校验：词时间/文本/动画 owner 一致，build 参数对应词类型；遮罩更新与首尾字段同 owner、监听器遮罩类型一致；bank 字段属于同一 ViewGroup；holder 四路 Map、binding root、callback→Fragment→browser→position 返回链互相匹配。update/rush/fade 为真实 ValueAnimator 回调，fade 遮罩与暗色 int[] 调色板对应当前 adapter；装箱 Float 与 primitive 等描述符差别按第 3 节核对。

#### 通用维护：时间与几何

保留宿主 AnimatorSet / ValueAnimator 与歌词身份，不另建全局计时器。仅原生词跨多个有效视觉行，来源遮罩/监听器身份及 RTL 均一致时接管；同 flexbox 同 bank 只保留最新词。每次主扫光先释放持有标记，成功绘制才重新持有；补亮仅在持有且几何仍有效时抑制，其余透传。单行末词仍走前述末词补偿，跨行成功接管由本能力独占对应多遮罩写入。

初始及逐帧进度取 `(position - begin) / duration` 限幅，同次接管只前进，原生完成 tick 完成整词，向后 Seek 退役后由新接管降低进度。几何就绪且词已开始未结束才把动画时长最多缩到实际剩余段；延迟布局不先改时长，不把 JNI duration 或 listener 行内坐标当整词进度。

进度按视觉行实际字形宽度并集累计，片段终点包含多行羽化，stop 与原生前沿限制在本片段内。遮罩键为视觉行首 child 索引，maskLast 是该行 child 范围，不是歌词 ID/序号/adapter position。TOP/BOTTOM 是同 flexbox 的原文/发音 bank，前景/背景是两个独立 flexbox。几何复用同时核对宽度、绑定、词文本、TextView 文本/Layout 及遮罩身份。

单行、少于两个片段、无效宽度/布局、映射不唯一、单 TextView 自含多行或同视觉行被文本顺序重复访问的混合方向复合形状均保持原生。

#### 通用维护：静态帧、交接与退役

| 时机 | 必须保持的处理 |
|---|---|
| staticMask 前后 | 词空、未开始、媒体位置不可用或原生前缀为暗色时清该 bank 交接/旧帧并保留原生；其余已开始跨行词保存静态帧，有活动接管取其进度/颜色/羽化，无活动时据媒体位置重建 |
| 入场暗色首帧 | 不能仅因暗色前缀退役活动扫光；亮暗曲线留给宿主，真正退场由专用 fade 回调判断 |
| fadeUpdate | 核对当前遮罩的弱身份与 bank 后清静态帧/交接并退役旧写入，原版渐暗继续；过期遮罩不能撤销新绑定 |
| rebind 前后 / 布局变化 | 按 holder 模型身份退役不属于当前行的状态并使几何失效，后置重绘存活帧；布局改变清交接、重验几何 |
| bind / recycle / document / clearMasks | 分别按绑定 root、adapter 或 flexbox 释放接管、静态帧和交接保护 |
| Seek | 按 browser 退役状态并取消原生词动画，释放宿主去重门禁，使新位置重新建立动画 |
| pause / pauseRequested / resume | 同步所属 adapter 原生词动画集；发音缺独立 owner 时沿用其原生扫光动画 |
| scope 关闭 | 清全部弱状态与布局监听，旧/退役动画不再向替换后的行或文档写入 |

staticMask 同时用于未来行初始化和已唱恢复，不能因它被调用就点亮词前缀；每次重绘重新核对开始时间及原生暗色调色板，未来词/无效绑定清旧帧。词已开始或结束也不意味着行应亮。

跨行已实际完成绘制后，后继单行可能复用同一遮罩。只为方向兼容的四stop渐变记录已亮边界：stop为`0, s1, s2, 1`，LTR颜色为高alpha/高alpha/低alpha/低alpha，RTL反序；LTR边界不后退、RTL不前移，写入成功才推进记录，后继保留原生颜色、时长、调度与前沿值。暗色、fade、Seek、换绑、布局失效释放保护，不兼容形状透传；不通过抬高原生前沿或全局关闭羽化消耗短span余量，也不凭文本长度预测未绘制边界。

**变更触发。** 新构建复核时间单位、视觉行键/bank、静态前缀比较、退场和后继写入链及全部失效入口；语义变化连同进度、时长或交接策略适配，角色清单与离线覆盖按第 3 节同步。

[证据清单](host-adaptation-checklist.md#lyric-wrap-sweep-checks) · [人工场景](feature-preservation.md#lyric-wrap-sweep)

<a id="lyric-background-highlight"></a>

#### 5.6.1 歌词效果优化：活动行高亮

**生效与接缝。** 本能力与简体替换和声修复、跨行扫光共用前述“歌词效果优化”开关及重启门禁；开关开启后，本能力仍按自己的精确契约单独安装。[AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 经 `TargetAdaptation.lyricBackgroundHighlight` 提供独立的 [AppleMusicLyricBackgroundHighlightTarget](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBackgroundHighlightTarget.kt)，不依赖跨行扫光的安装结果；跨行不支持或失败不影响本能力，反之亦然。

启用要求精确 profile 声明 `lyricBackgroundHighlight`、`contractVersion: 4`，通过全部成员及关系校验，并成功对活动行提交方法 `O` 执行 `deoptimize` 后注册、激活 Hook。`lineSubset` 为 `f1.D(TreeSet,Collection)boolean static`，去优化确保此内部调用可被拦截。支持范围以精确 profile 的契约声明为准；开关开启后未声明契约报告不支持，解析、去优化或注册失败关闭本次注册 scope 并报告降级。

**活动行与词状态。** 1609 的 `A.k` 将背景行登记到 `q`，`O` 可在原始输入仍含父行时将其过滤掉。父行候选来自当前集合、原始输入和已保留行；输入中较旧或已经退出输入的行，只要仍在背景保留窗口内就参与保留。只在本次 `O`、`f1.D` 第一参数与当前 `r` 为同一实例时处理首次过滤结果：合并未结束父行，并在集合变化为真子集时放行更新；其余调用保持原生。结束时以该过滤结果判断应释放哪些模块父行，保留原生仍需要的成员及间奏、分组逻辑。

普通提交与结束补交都在模块副本中复制合并保留父行的主词和背景词 ID，不改调用方的集合与数组。`A.w/x` 缺行会使 `g0/i0/k` 清除遮罩或收起和声，故词 ID 必须随普通提交保留，释放只清模块旧父行。`O` 返回前校正这些父行在 `r/s` 中的成员关系，必要时经原生 `E/h` 通知重绑；核对 `Fragment.G0/H0` 后，仅在需要时调用原生 `N.b` 恢复焦点。行词动画、和声观察者及模糊共同消费提交后的活动状态，模糊以显示快照为准，滚动继续遵守原生用户交互门槛。

**时间与生命周期。** 保留窗口从父句 `begin` 到原文、发音背景词的最大有效 `end`，多个背景段取最终结束点；TTML 和媒体时钟保持原样。窗口判定及到期轮询读取媒体位置，不能使用含 `suggestedLineOffset` 的行调度载荷 `PLAYBACK_POSITION` 提前判定结束。结束补交按最近原生样本还原行时钟偏移，剩余间奏时长按媒体位置差扣减，补交不再次校准偏移；媒体位置不可读时跳过本次判定。

暂停停止到期轮询，恢复后继续按媒体位置判定。Seek、换文档、适配器脱离和视图销毁先尝试释放旧保留行，再移除对应会话；reader 身份变化不得复用旧窗口和时间基准。普通行重绑与回收沿原生绑定读取当前状态，不清除其他行仍有效的会话。关闭注册 scope 时取消轮询并清状态，多歌词视图按各自 adapter 隔离。

**状态与维护。** 安装健康状态由 Feature 统一报告；首次非主线程提交透传并报告降级。运行异常记录原因、尝试释放保留行并清理会话，后续提交可重新建立会话。正常逐句提交不输出诊断，异常日志不含歌词正文。新构建须重新确认过滤调用、词 ID 消费、焦点/显示快照、媒体与行调度时钟及生命周期语义；所需成员清单由代码契约维护，不能照搬相邻构建的混淆名。

[证据清单](host-adaptation-checklist.md#lyric-background-highlight-checks) · [人工场景](feature-preservation.md#lyric-background-highlight)

<a id="line-lyric-agent-alignment"></a>

#### 5.6.2 歌词效果优化：逐行双声部对齐

**生效与接缝。** [逐行对齐补丁](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LineLyricAgentAlignment.kt) 由精确 profile 的 `capabilities.lineLyricAgentAlignment=true` 授权，复用 `LyricsParserFeature` 的开关和安装组，不增加独立设置、Feature 或 Target。成员从 `lyricBlur` 中只选取 `lineBind`、`lineType`、`lineMap`、`lineReader`、`lineToken`、`readerCount`、`readerLine`、`lineGet`、`holderView`，不依赖其他模糊成员或模糊功能的安装。native `getAgent` 等方法按稳定 JavaCPP ABI 解析。缺少能力位时保留原 parser 结果；解析修复、逐行对齐与末词补偿的完整诊断由 `lyricRepairs` 合并，整体状态按上述规则汇总，任一子项失败不撤销其他已安装项。

**适用边界与行绑定。** 仅处理原生 `timing=Line`：排除 `Group` 后，`Person`/`Other` 有效声部恰为两个，且至少一个为 `Person`。首个实际出现的 `Person` 按原歌词的首字母方向确定主侧，另一声部使用对侧；`Group` 行、未知声部、单声部、更多声部及逐字文档恢复原生布局。普通行绑定后，每次为主文、译文、音译重算同一物理侧或原生对齐，并同步缩放中心；保留文字方向、尺寸、边距和时间数据，不缓存跨歌曲映射。延迟布局回调读取当前 view 状态，不保留 native 指针；无需独立 payload、recycle Hook 或常驻布局监听。

**维护触发。** 新版本授予能力位前，核对普通行筛选、位置换算、agent ABI、原生首字母方向判定、内容 payload 回到完整绑定及一次性布局回调顺序。人工场景覆盖双声部、原生回落、滚动复用、翻译/音译切换、混合脚本和开关重启；不能依据相邻版本自动继承资格。

<a id="lyrics-typeface"></a>

### 5.7 歌词字体与显示协作

**宿主接缝。** `AppleMusicLyricsTypefaceTarget` 与 `LyricsTypefaceSession` 复用同一资源/安装会话，核对普通行、逐字行、资源行与重建后的绑定。Credits 与三点语义由 `LyricCreditsRowResourceHook` 接入，Fragment 右栏宽度和度量走该页面族歌词 runtime，不借用模糊成功状态。

**通用维护。** TTF/OTF 后台加载、暂时失败重试、行词一致及原字体恢复保持既有字体业务；区分 Hook 安装与字体真正就绪。字体、翻译、发音、模糊、CJK 和跨行均沿原生订阅与绑定协作，布局/字体变化触发对应几何失效，关闭仅释放仍拥有属性。

[人工场景](feature-preservation.md#lyrics-typeface)

<a id="chrome-glass"></a>

### 5.8 玻璃导航、mini 与原生面板

**生效与接缝。** 玻璃要求 Android 13 起及精确 `capabilities.glass`，材质、菜单及几何就绪后才接管。旧 Activity 与 Fragment 使用自己的 chrome；Fragment 手机底导航、平板顶导航/抽屉及独立底部 mini 分别核对，平板玻璃与双栏开关独立。资源/成员在 `chrome` / `fragmentChrome`，应用经 [ChromeHostBinding](../host-api/src/main/java/dev/amenhancer/module/hook/ChromeHostBinding.kt) / PlayerSurfacePort 使用语义端口。

| 每构建接缝 | 维护要求 |
|---|---|
| 导航模型、getter、选择 callback、菜单与资源 | 标签身份、重复点击、搜索/抽屉与原生选择来自实际路径；资源存在不代表当前树可用 |
| sheet 状态/坐标/peek、settleSheet、refreshCollapsedOffset | 原生计算收回终点前同步模块占位并刷新坐标，同 peek 也可刷新；模块值不成为新的原生恢复基线 |
| miniBlur、登录/当前对象及显示入口 | 精确核对静态/实例调用、显示语义与手机/平板资源；不用直接改 visibility 代替原生显示 |
| 原生滚动容器、Compose 场景与 pager | 由宿主绑定识别并缓存，经端口提供位移和方向边界，应用不发现反射或强转宿主 AndroidX |

导航菜单就绪与原生布局完成分别判断。当前 Fragment 平板顶栏须从仅抽屉按钮的方形占位展开为宽大于高的材质；新构建须确认这一形态语义，不以固定像素或菜单数量代替布局完成。接管还要求原生锚点与玻璃承载均无待布局、宽高一致，且 Compose 内容度量满足当前布局约束。窗口或配置变化、导航暂时透明时仍继续跟随锚点；每帧先同步几何，再用同一就绪结果控制原生显隐、玻璃透明度与背景采样启停。布局尚未稳定时保留或恢复原生，并使玻璃导航在同一帧透明，避免两套导航重叠。

#### 通用维护：sheet 与 mini 生命周期

原生收回 settling 中只在同 owner、sheet、目标状态下，目标坐标确实改变时通过原生平滑入口更新剩余终点；拖动/展开/隐藏、新目标或稳定状态清旧记录，不直接改 top/translation，也不因相同目标重启动画。终态 `reconcileCollapsedSheet` 只作兜底：已折叠而实际落点错误时合并布局并在完成前阻止该帧绘制，后续 inset/布局变化同样覆盖。

稳定收起的逐帧 peek 更新可单独声明 `fragmentChrome.phone.behavior.collapsedPeekMode="offset"`，缺省继续调用原生 setter。放行前追踪当前包 setter 及其调用：确认固定 peek 只写已声明字段、重算 collapsed offset，并在 state=4 时请求布局，没有遗漏通知或其他副作用。绑定仅在当前 owner、非自动 peek、sheet/父树无待布局且实际位置已对齐时更新字段、调用原生重算并 `offsetTopAndBottom`；目标越界请求原生布局，拖动/settling/展开/恢复仍走 setter。这不允许直接移动正在运行的原生 sheet 动画。

sheet 校准先于 mini 锚点采样，材质岛尺寸/边距在同轮绘制前完成布局，不依赖多个 pre-draw listener 顺序。底栏收缩弹簧运行时暂停 sheet 落点校准，结束后恢复。

Fragment 手机/平板共用 `FragmentMiniStartup`，在 `FragmentSurfaceBinding` 创建时启动。先检查一次，条件未就绪每 250ms 再查，最多 40 次；已显示、补调成功或异常即结束。补调要求原生登录且存在当前播放对象；每次确认 owner 当前 root 仍附着，销毁取消全部回调。该接入沿既有玻璃资格，不等同独立播放器恢复能力。

原生内容、材质采样和模块岛按视图会话管理。关闭/失败/视图替换恢复仍拥有的透明度、裁剪、尺寸和原生触摸，旧回调不能影响新树。旧 Activity 保留自己的导航/mini 组合与视频语义。

宿主已维护兼容的内容 RenderNode 时，可在 `fragmentChrome.members.fields.contentBackdropNode` 声明完整 owner/name/type，经既有解析器预解析；未声明保持 View 采样。宿主绑定通过 Canvas 语义端口提供录制，核对直接父类型、唯一内容子树、原点/矩阵/alpha/Z、尺寸、父滚动/padding及 display-list 就绪。宿主本轮正常绘制在 RenderThread 使用引用前更新同一节点；窗口背景、源脏状态与代次仍由 `ViewBackdrop` 处理。首帧未就绪或层级/几何不匹配时返回未绘制并回退 `source.draw`；模块只释放自己的记录，不丢弃宿主节点，也不在材质库发现私有字段。

预绘制中的裁剪和留白更新必须先比较当前值，只在实际变化时调用 setter。不要假设原生控件对同值写入没有副作用：适配原生列表时须追踪其 `setClipToPadding` 等覆写，确认首次布局后是否仍无条件 `requestLayout`。每帧重复写入会形成“预绘制 → 请求布局 → 全局布局通知 → 下帧预绘制”的循环，连带触发层级扫描、背景录制和材质绘制；纯 Compose 场景未进入该分支不能证明原生列表路径正常。保持原值捕获、离开目标/关闭时的恢复，以及宿主真实改值后重新接管的语义；不能用降低动画帧率或冻结背景掩盖布局循环。

**变更触发。** 导航模型、原生落位、资源层级或显示/恢复机制改变时调整绑定/会话，复核当前形态；普通材质修改沿用这些宿主接缝。独立收缩规则见下一节。

[人工场景](feature-preservation.md#chrome-glass)

<a id="glass-collapse-rendering"></a>

### 5.9 底栏收缩：宿主资格、输入与渲染

**生效与接缝。** 收缩另需 `capabilities.glassScrollCollapse`，由适配层结合 SDK、精确版本及手机底导航/非 `is_tablet` 形态判断。设置只调用 `SettingsViewBridge.supportsGlassScrollCollapse(activity)`，旧 Activity、平板和未授予能力的档案保持展开并隐藏收缩/回弹入口；旧平板配置字段仅作读写兼容。

新形态先确认 mini 与控件高度前提。当前收缩手机无前导抽屉按钮；若新宿主有 leading 或不同高度，绘制、共享几何和触摸命中须使用同一实际输入，不能只改绘制扣减。菜单末项确认为搜索且剩余标签足够时才分离，否则保持单胶囊。mini 不可见不阻止收缩，中央可留空；图标/强调色取实际分离项身份，不能仅凭 isSearch 回退。

#### 通用维护：方向、触摸与单一动画

`GlassBarCollapseController` 唯一持有目标、启用/外观状态、Animatable、协程及滚动监听，`PhoneGlassSession` 只接入几何；导航、mini 和 peek 逐帧读同一进度，内容留白取同一几何并可在下述边界合并布局。输入由 `GlassBarCollapseInput` 独占，`GlassBarTouchRegions` 只做屏幕命中，`GlassBarCollapseLayout.controls` 提供共享几何。

| 输入阶段 | 规则 |
|---|---|
| ACTION_DOWN | 固定页面/导航/搜索/mini 起点归属、当前目标与位移基线；展开态整条拖拽带，收缩态按实际控件、RTL 与最小触摸尺寸命中，中央空白不冒充导航 |
| MOVE | 触摸期间只由手势定方向，原生回调更新基线；反向达到阈值即可改目标；底栏/mini 起点整次排除，即使移出控件 |
| 边界判定 | 当前目标 canScrollVertically(-1/+1) 决定能否向内容起点/末尾滚；边缘外推、静态短内容或未知区域不改状态，拒绝意图不成为惯性方向 |
| UP / CANCEL | MOVE 已确认则 UP 只结束；未决短滑可按净方向补判，不被末端回带/短速度窗反转；CANCEL 不作速度判定，多指/后台/关闭清状态 |
| 原生惯性 | 只跟踪起点命中的容器，延续最后确认方向；弹簧期间不因 peek、padding、列表估算或回弹位移反判，也不累加多列表 |
| 导航/配置/会话变化 | `expand()` 清手势与惯性并同步展开，宿主主动选中也处理；重建不沿旧状态，mini start/end 边距随 RTL 交换 |

原生位移缓存实际运行时类的 framework `computeVerticalScrollOffset(): int` 覆写，不用 RecyclerView 的 scrollY 或模块 AndroidX 强转；单类失败只停该类信号，保留页面观察。Compose 外壳不代表可滚动：在 profile 识别场景内找语义 owner，等子树处理 DOWN 后于 MOVE/UP 查询当前位置的 framework 边界。每手势重选目标，层级失效才重新发现容器/更新 owner；每帧仅检查已发现场景 isShown/高度，不缓存可见性结论，关闭清类缓存。

列表回收也可能触发 global layout，层级发现用显式栈遍历，在已识别的外层滚动目标或 Compose 场景停止，不逐行展开列表子树；ViewPager 的页面承载 RecyclerView 不作为滚动目标，继续深入页面。隐藏 Compose 场景仍登记，以覆盖不触发布局的显隐切换；目标替换/分离时恢复旧 padding。资格检查、材质初始化和收缩配置共用会话设置快照，沿既有短周期及前台恢复刷新，不在重复接入时重新解码设置。

收缩弹簧运行且播放器稳定收起时，已有留白的长列表若仍可向末尾滚动，保留已应用的底 padding，停稳后提交当前占高，避免每帧让整个列表重新布局；到末尾、短内容、未知边界、新目标及几何变化立即更新，前台恢复丢弃旧值。模块导航/mini材质沿既有隔离容器局部测量，在本轮绘制前刷完。原生 mini 仅在自身与父 FrameLayout 无待布局时局部测量更新边距，其他情形交还正常布局；不重挂原生控件，不抑制滚动输入。

只观察内容根事件，不消费、改写或重新分发。呈现接管后隐藏的原生导航仍可能收触摸：先向该身份发送 ACTION_CANCEL，再按当前 view 呈现状态隔离其子树；撤销/销毁恢复分派。隔离不覆盖内容根、mini、整条玻璃行，也不改菜单启用或程序化选择；未支持收缩的宿主不进入此路径。

同目标信号不重启弹簧，变更目标立即重定向同一 Animatable 并保留当前值/速度，旧动画未进首帧也要撤销。`animateTo` 每帧只 postInvalidateOnAnimation，结束/取消补末帧，不常驻 snapshotFlow 或轮询。层级接入、前台恢复和设置刷新时更新 ViewTreeObserver，换实例先解绑旧监听，销毁后不重绑。

#### 通用维护：收缩渲染

下列为修改收缩/材质实现或同步上游时复核的规则。纯版本名字变化复用这些实现，仍按矩阵覆盖适用的显示和操作场景。

| 层面 | 维护约束 |
|---|---|
| 启用 | `LiquidBottomTabs.optimizeCollapseRendering` 默认 false；GlassNavigation 只在非空 collapseProgress 时打开，其他宿主及通用渲染默认保持 |
| 材质复用 | vibrancy/blur 前缀按物理像素半径缓存，密度换算变化重建；lens/按压/圆角按当前尺寸求值。面板/折射源/水珠 modifier remember 按材质输入复用，缓存放上游命名空间之外 |
| 承载预留 | minLayerSize 默认为零，仅收缩过渡按当前窗口展开几何/回弹上界和 VibrantBlur.paddedSize 预留；零值仍为零，稳定端点回零，不保留历史最大值 |
| 逻辑尺寸 | 预留不改变效果 size、背景坐标、圆角或命中，录制仍见当前逻辑尺寸/padding，结束恢复 DrawContext；有效内容超预留仍绘制，额外背景不泄露 |
| 合成与形状 | 收缩调用点选 Auto 裁切，材质效果继续负责模糊/镜片，默认 Offscreen 不改。GlassCollapseShapes 仅收缩手机选 Circular，完整播放器/其他宿主保持原圆角路径 |
| mini | hasPresentation、收缩运行且完整播放器进度为零才预留；上界取当前父宽，slot 边距不为负，打开播放器回原材质路径，原生布局/输入保留 |
| 隐藏节点 | 折射源和分离搜索不可见时保持附着与层/材质资源，挂起子测量/放置及内部录制；门禁翻转后下次绘制前恢复 |
| 帧阶段 | 连续尺寸在测量、位移/alpha 在 graphicsLayer 读当前不可变几何，组合只读显隐/交互/承载门禁；动态 modifier 逐轴按原顺序收敛约束，背板/折射源保留 required 语义 |
| 标签排版 | 可见标签继续使用 `BasicText`；当前导航实例按 tab ID 只保存最近一次原始约束与 `TextLayoutResult`，表随菜单/样式/配置/字体 resolver/密度/方向重建，容量限当前菜单。隐藏折射标签仅在文字、样式、字体状态（无 stale）及原始约束匹配时复用该结果，否则由 `BasicText` 独立测量回退 |
| 交互时效 | 拖拽、索引、按压高亮和补偿读当前几何提供者；标签/水珠沿 clamped resting width，仅背板继承回弹。透明度裁切与原 alpha 一致 |
| 资源恢复 | 旋转、分屏、密度/配置变化重算预留；移除时释放层/效果，修改本地上游补丁同步维护 `backdrop/UPSTREAM.md` 与源码 pin |

渲染优化保留弹簧参数、共享几何、位移端口、peek、内容留白和 mini 原生输入。不能以整体缩放文字/图标或冻结背景代替承载复用，单设备性能收益不作为所有设备保证。

可见标签仍使用 `BasicText`，并由 `onTextLayout` 捕获真实的 `TextLayoutResult`；当前导航实例按 tab ID 只保存最近一次原始约束与结果，表随菜单、样式、配置、字体 resolver、密度或方向变化重建，容量限于当前菜单。隐藏折射标签仅在文字、样式、字体状态（无 stale）及原始约束匹配时直接使用该结果绘制；不匹配或隐藏层先于可见层测量时，继续由原 `BasicText` 独立测量回退。`LiquidBottomTabs` 的可选 `refractionContent` 默认仍使用原 `content`，只有 `GlassNavigation` 收缩路径传入替代文字绘制。可见与折射两份内容各自的图标、绘制/着色、触摸、图层及原单行省略号、基线、命中、选中和无障碍语义分别保留；原生列表和宿主契约边界不变。该复用是当前实例的最近一次排版结果复用，未命中原 `BasicText` 回退，不承诺固定命中率、统一减半或整页 RecyclerView 布局减少。

折射文字命中复用时，仍须按排版结果的实际尺寸测量、放置独立绘制节点，并保留基线；仅跳过重复 `BasicText` 的测量与放置。不能用空的 placement 块替代文字承载，或依赖未放置子树的绘制 modifier，否则选中指示器采样的源可能缺字。未命中时只测量、放置原 `BasicText`，两条路径都保留原有折射层坐标、颜色滤镜和显隐门禁。

需要测量时沿既有 `AM++/` Trace：collapse/progress（进度×10000）/running、source/record、effects/update、backdrop/record/createLayer 及导航/mini measure/layout/recordView 分段；CPU 区段成对结束，不采样不写标记，CPU 分段不等于 GPU 耗时。操作与视觉标准见[收缩矩阵](feature-preservation.md#glass-collapse-rendering)，[清单](host-adaptation-checklist.md#glass-collapse-rendering-checks)仅登记证据及结果。

性能对照使用同一页面、刷新率及相近滑动方式，按动画标记分别分析过渡与稳定区间；核对 Trace 缓冲区覆盖，只分析实际保留的时间。录制边界、CPU 父子区段及并行线程分别解释，不直接相加，不把整段录制掉帧率称为动画掉帧率。标记未覆盖的开销保留为未归因项，不直接归为纹理分配、shader 编译或 GPU 成本。

<a id="startup-page-prewarm"></a>

### 5.10 导航一级页面启动预热

**生效与接缝。** 独立宿主能力，与玻璃/收缩/双栏无依赖。精确生产档案须授予 `startupPagePrewarm`、提供解析器支持的完整 v1 契约及当前形态导航资源，入口和安装共同限定 schema。普通配置默认由 EnhancementDefaults 与缺键解码共用，已有关闭值保留；默认开启不授予未知构建或形态资格，重开应用生效。

`StartupPagePrewarmContract` 使用 FragmentProfileMembers 解析生命周期、tab 模型、容器构造/参数、原生隐藏事务和共享状态保护；`FragmentStartupPagePrewarmTarget` 安装及隔离失败，`StartupPagePrewarmSession` 持有当前内容 root 的有界任务。原生必须按标签隐藏/显示保留实例，所有导航图、参数键和真实根页面以当前包为证据。

`tabArgumentKey` 与 `titleArgumentKey` 须追溯原生正常创建容器的参数写入点和对应读取点，记录当前包的完整键值与 Bundle 值类型。若键由 `Class.getName()` 与后缀拼接，须确认常量初始化引用的实际类；不能直接沿用旧值或只按类映射推导。成员描述符通过无法证明参数键有效，错误键会使异步 Fragment 事务在原生创建视图时失败。

手机 navigationResource 位于内容根的底部导航；平板可选 tabletNavigationResource 从 Activity 取抽屉菜单并需 tabletMenu 角色，顶部 Compose 不套手机 API。菜单 checked、选中模型及主导航标签一致，不主动开抽屉或选中项；抽屉关闭/玻璃隐藏导航时仍可读取。hostResource 指向一级页面视口，不能预热抽屉/播放器。就绪依据实际视口尺寸与原生菜单/选中状态，不要求平板底部导航具有可见高度。

#### 通用维护：有界任务与副作用

就绪后逐个创建缺失可用一级页面，按原生标签加入隐藏容器并允许事务重排，再用实际视口测量/布局。已访问/系统恢复实例复用并预布局，容器存在不等于已布局；无 View 时有界等待，完成才标记该实例。不创建详情，不维护额外全局页面缓存。

全轮使用 10 秒就绪期限，每次只提交一个事务，完成后再推进；独立超时覆盖事务不回调的等待，全部结束路径撤销。触摸/滑动/切页不中断本轮，每步读取最新选中项与实例，不回切启动页。root 隐藏/脱离/销毁、后台或已保存状态、tab 列表变化停止后续任务；已提交原生事务可完成，迟到回调不预布局新 root。

不调用切页、不改 primaryNavigationFragment、选中项、上次访问或返回栈。隐藏容器播放上下文观察与资料库工具栏重置按精确调用范围保护；普通访问和真实显示继续原生。资料库保护只适用于主线程 view 生命周期内同步派发的事件，新构建若变异步/跨线程须重定归属，不能沿旧窗口。

**变更与降级。** 原生参数、保留机制、共享副作用或事务协议变化先停能力再适配；描述符匹配不替代语义确认，新协议升级契约/解析器。日志区分内容回调、排队、事务完成、完成数量与具体停止/超时门禁，ACTIVE 安装不等于预热完成。启动负载、内存与首次真实显示开销由[人工场景](feature-preservation.md#startup-page-prewarm)分别确认。

<a id="tablet-layout"></a>

### 5.11 平板布局、视频与播放器恢复

**生效与接缝。** 双栏先满足精确构建资格及开关。旧 Activity 使用原生 `is_tablet` 与横屏配置判定；Fragment 使用当前窗口 `screenWidthDp >= 600`、宽大于高及原生 `useNavigationDrawer` 判定。DPI/多窗口改变逻辑视口，按各页面族的实际输入重新判断。SONG/QUEUE 保留原生状态，右栏独立歌词；Fragment 双栏、视频接力与播放器恢复分别解析自身契约。

| 每构建接缝 | 必需证据 |
|---|---|
| 全布局与资源 | 全部 layout/land/宽度限定符、dimen/integer/bool、递归 include；角色 View、父链、LayoutParams、Guideline/Barrier 与重打包约束字段 |
| generated binding | 换歌、播放状态、异步加载是否重写尺寸/padding/字号/可见性，首次 adapter 绑定是否早于右栏测量 |
| 左栏角色 | metadata、progress、transport、favorite/more、质量标识、封面及控制容器真实载体，与 QUEUE/右栏是否共用布局 |
| 视频链 | metadata→尺寸计算→updateSize/resize，模式/动画、浏览器与视频 size、外框/card/image/TextureView、staticBaseline/targetSize |
| 恢复链 | 封面来源、尺寸目标、背景 shader/paint、进入/共享/sheet/销毁各写入者及属性归属 |

封面约束只用当前档案已恢复的字段映射，区分 physical left/right 与 relative start/end，不能靠相对锚掩盖左右反向，也不能拿宿主字段操作模块视觉树。资源与反射在安装/绑定时缓存，视图/配置重建重新解析。

**歌词 View 重建。** 独立右栏须核对 Fragment 保留、View 重建时的 adapter 创建、结果观察及安装去重。如果原生创建新 adapter，却保留以歌词指针身份去重的旧安装字段，可在 `fragmentLyricsRuntime.fields.installedPointer` 声明该构建的精确字段。预检确认字段属于歌词 Fragment、类型对应当前档案的 `SONG_INFO_PTR` 且可写；未声明的构建不启用此修复，也不推测字段。`BetaLyricsPaneRuntime` 仅在独立右栏 `onCreateView` 原生方法执行前清除旧安装标记，使新 View 的原生观察者能按既有结果通道填充当前歌词。保留 ViewModel 结果及指针所有权，不重入安装、不强制重新请求；普通 resume 或逐帧刷新不得清除此标记。人工验收须包含原生设置返回后的同曲原生/补全歌词，以及关闭自定义歌词后的原生显示。

#### 通用维护：响应式视觉与原生交互

共享左栏由真实原生 View 构成，core 的 [TabletArtworkLayoutPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/TabletArtworkLayoutPolicy.kt) 计算参考 grid/flex，宿主 [TabletSongColumnLayout](../host-applemusic/src/main/java/dev/amenhancer/module/hook/tabletmedia/TabletSongColumnLayout.kt) 接入。左栏保留父链、binding、动作、selector、字体和无损/杜比/卡拉 OK 标识，只按策略布局；音量、输出与模式等补充控件沿既有组件，不另建替代原生左栏的树。

两族静态 SONG 路径均可接入共享左栏、音量、输出与收起横条。旧 Activity 以 `keepNativeFooter=true` 保留原生底部动作；Fragment 另接 SONG/QUEUE 共用补充控件、关闭歌词居中、右下歌词/队列代理及有契约的模式按钮。QUEUE 定制渐变以实际 `present(queueVisible=true)` 调用为前提，不能由复用布局类推定旧族具备全部 Fragment 行为。

参考为 YouLyPlus horizontal.module.css 及播放器组件，来源/许可证见[第三方说明](../THIRD_PARTY_NOTICES.md)。CSS 长度与 Android dp 分开：统一 REFERENCE_PIXEL_SCALE 将 player_root 物理宽高换为 CSS 视口，再解析媒体/容器断点、输出物理像素；当前策略常量为 2.25，字号写 px，不二次乘 fontScale。Android density 仅用于平台命中/z 间距等。同像素视口保持同一参考几何，不以 dp 高度切换 CSS 分支，也不整体缩放固定画板。

更换参考时先确认内容 CSS 宽高、devicePixelRatio、页面缩放及串流缩放，再统一调整策略；截图尺寸或聊天压缩不作为倍率证据。

| 策略面 | 需要保留的语义；具体计算以 policy 为准 |
|---|---|
| grid / 内容 | 名义 .45/.55 分列，扣列间距；内容宽 min(60cqh,38cqw)，较短视口按既有55/50cqh分支；封面与控制组共宽，不改用进度宽/上方剩余高度决定封面 |
| 纵向 grid | 保留 minmax、auto stretch、min-content 及 fr 总和小于1时的空余，不强行分完；control 25%且 min-height:0，不额外预留高度挤封面 |
| metadata / 进度 | em/字号/间距按CSS断点，原生 SeekBar、时间标签和音质标识完整保留，清负margin；标签行取参考行高与原生标识固有高较大值 |
| 播放区 / 图标 | 五等分槽位和参考尺寸，切歌路径占比沿既有参考80/134与native39/40换算；保留原生glyph/selector/点击/无障碍，不以参考SVG替换 |
| 音量 / 输出 | 组件整体与进度同宽，图标保留viewBox比例/留白，轨道填剩余；输出锚定窗口底部工具行，不随关闭歌词的左栏居中位移 |
| 视口 / inset | 只扣未由父布局消化的横遮挡，竖inset作父坐标映射；系统栏显隐不切另一套几何，不为手势栏多造grid行 |

音量使用参考路径及系统 STREAM_MUSIC，源路径在绑定阶段解析；32×40/43×40 viewBox 在短窗24×24外框中等比绘制。轨道8→20px，展开280ms easeOut、收起质量1/刚度200/阻尼12弹簧，越界 tanh(2×比例)×12px，整组位移刚度300/阻尼12；端点路径1→1.2→.85→1，三段各200ms、路径延迟50ms。拖动实时写/回读系统音量，取消不额外提交，隐藏释放动画/手势/监听，悬停沿展开反馈；不承诺与浏览器逐帧相同。

输出复用 IosOutputDrawable 与参考AirPlay可见边界，图标边长取modeIconSize的68%、alpha .45，居中等比且命中框容纳原工具框。点击只调用 PlatformAudioOutputSwitcher 的公开面板与既有ROM回退，不猜私有SystemUI/宿主入口；过渡/隐藏停交互，销毁移除。

原生 transport/SeekBar 不引入触摸代理，不改动作绑定。Fragment 的补充随机/循环按钮沿 native vector 独立 mutate，用 bounds/padding 设目标像素，不转Bitmap或整体放大View；模式状态/操作只走当前 `tablet-playback-*` 契约，缺失降级。图标点击反馈700ms按0/20/50/100%的1→.85→1.1→1，按住停止；按住/悬停圆底300ms过渡至白色0x22。命中/布局/背景不随图标缩放，隐藏/禁用/分离停止，系统关动画取静态。

收起横条为独立小范围目标，经 framework ACTION_COLLAPSE/Activity 返回链执行。横条绘制尺寸为50×8 CSS px，先按统一参考倍率换算为物理像素；命中宽取横条实际宽与44dp的较大值，目标高取横条实际高与44dp的较大值，再按封面上方及播放器可用空间裁限，至少容纳横条自身，不扩整屏。Fragment 的右下歌词/队列代理保留44dp点击区、8dp间距与底部12dp；原生动作保留约束但撤销其被替代视觉/点击，代理保留selector/tint/状态与原生动作归属。

状态栏隐藏使用独立普通设置 `dual_pane_hide_status_bar_enabled`，模型/缺键默认关闭，旧配置不自动继承曾强制隐藏。共享会话初始化读一次，不逐帧解码；仅开启且完全展开后隐藏。Fragment 按真实展开进度在收起途中及 SONG/QUEUE 切换时维持，收起停稳后恢复；旧 Activity 按当前 SONG 布局的实际可见性处理。退出资格、分离或销毁恢复进入前状态。Android30起只操作 statusBars，旧版只持有 FLAG_FULLSCREEN 对应位，导航栏/手势/decor fitting留原生；隐藏后按真实视口重算。

#### 通用维护：binding、QUEUE与失效

SONG 的 player_controls 在封面/标题的 ConstraintLayout，QUEUE 的同名 include 在 controls FrameLayout，分别识别后使用同一布局函数，不能向FrameLayout子项写约束锚。QUEUE控制区从共享进度到父底，内层保留原参数。原生组件不重新挂载，player_controls父alpha与测量保留；补充音量/横条不查找宿主同名控件。

当前 Fragment 的 QUEUE 定制列表沿覆盖式footer：保留原渐变视口/约束，底padding随控制区外高，其余padding留原生；渐隐终点为新进度上方8CSSpx，羽化读queue_bottom_gradient_height，滚动后恢复边界。gradientEdges的getter、双数组setter、尺寸与nullable positions字段绑定时缓存；颜色/stop等长一次发布，空positions表示均匀分布。恢复空值先发布均匀stop使shader更新，再恢复精确字段为空。clipBounds在原视口内先于渐变应用，不通过缩容器重复扣高度；渐变失败只释放该实例渐变并停止重试，保留列表裁剪/共享布局。

安装、pane创建、binding完成标记布局失效，未附着pane在事务后合并准备；尺寸、密度、inset、binding/父链变化重算。先捕获原生代理基线再布局，完成新测量后绘制；首展开/新pane首帧不等sheet停稳，已就绪动画帧不重扫树或逐属性反射校正。sheet中只复用已测叠加层更新alpha/位移/交互，矩形/约束不变不重复measure/layout。

crossfade原生可能重显footer，快速路径对缓存View再施加隐藏/交互门禁，不重扫树或提前恢复。共享会话就绪后SONG/QUEUE共用音量/模式，停旧监听；失效恢复仍拥有参数和原降级。原生质量标识不改尺寸/padding/矩阵/字号/selector，仅布局准备时测固有高度；不能挤进文字行或侵入播放区。销毁/替换/失败释放布局写入、监听与叠加层，原生后续值按所有权保留。

#### 视频、封面与恢复

封面位置/尺寸使用同一内容宽策略，scaleX/scaleY、暂停、sheet、队列与共享动画，以及圆角/阴影/裁切都由原生处理，不强制scale=1或追加自算outline。Fragment视图会话持有普通/视频共用外框与staticBaseline/targetSize，进入视频不释放外框；原生image/TextureView处理比例/渐变。

FragmentTabletVideoHandoff在原生尺寸/resize前通过Coordinator准备当前基准，视频按比例适配，静态用同一基准；恢复优先取当前双栏会话目标，未重测不拿旧尺寸覆盖新基准，布局完成后再绘制。临时零视频尺寸、尺寸函数读取旧静态基准等路径需沿实际调用追踪。

列出恢复、双栏与视频每个写入者和作用View，防止交替恢复覆盖。接力核对当前owner/视图/歌曲，旧纹理仍可见不证明属于当前歌曲。关闭歌词居中、右栏可用性与sheet补偿仅作用相应Fragment会话：原生callback前释放上一帧补偿，后合成当前帧，不累计X、不改原生scale/Y，SONG/QUEUE不重置手动歌词状态。旧Activity Editorial URL处理、预览及Music Video策略独立保留。

**变更触发。** 布局父链、binding覆盖、尺寸基准/动画、状态保存或原生恢复变化时重分析对应Contract/Session；参考策略变化按CSS视口与512/640/768/1000/1080高度断点及宽度分支复核。新构建仍覆盖[平板人工场景](feature-preservation.md#tablet-layout)，不把静态布局或构建成功当观感/流畅度通过。

<a id="metadata"></a>

### 5.12 名称修正与元数据表面

**生效与接缝。** HLE 按 `hookTargets`、runtimeMemberNames、catalogQueryRenames 及精确类型接入；旧族兼容顺序与新族严格候选按第3节分别保留。目录接口、请求结构和返回路径、Compose/LiveData类型别名都需实际包证据。

接入至少覆盖播放器、队列、历史、操作表、最近搜索、资料库、专辑、歌单、艺术家、主页与封面连续性。每表面展开Epoxy holder、model/binding、LiveData、辅助getter及封面加载，分别安装/报告。原地区/cn/jp、原名/ISRC/ID证据、目录查询、缓存/离线及显示优先级沿现有业务，一个表面成功不代表整项通过；已知子面降级保存在profile。

页面歌曲应保留原生实体提供的 genre，并按歌曲 ID 送入同一原名解析器；不能要求播放后才能取得地区证据。空别名、未知地区或请求失败只代表本次没有可用结果，后续可见绑定采用有上限的退避间隔，不在每次重绑时清除退避。新的 lookup ID、关联艺人 ID、genre 或已确认地区语言可重置负状态；合作艺人、候选身份与原名置信度校验继续保留，地区显示名不充当原名证据。

目录身份阶段与后续批量查询分别限制并发，复用同 ID 在途请求，按当前可见/活动页优先级选取；成功、空结果、同步异常和超时完成均释放容量。身份和艺人语言派生缓存有容量边界，不通过清空已确认原名来减小缓存。绑定登记使用弱身份索引，反射缓存只保存按实际运行类解析的成员；候选集合每次更新复用，文本树扫描先判断适用角色。可见项分批入现有帧队列，每项执行前重查绑定 ID、代次与当前可见性，不能把无界整批塞进一个动作而绕过帧预算。详细逐项日志在调用点门控，保留安装、失败和降级信息。

<a id="artist-compose-profile"></a>

#### Compose 艺人页

GenericProfileViewModel 发布不可变Success状态，复用 `AppleArtistSurfaceHost`、entity/alias、歌曲地区证据、元数据应用与刷新队列，不建另一艺人查询通道，不从名称或logo推断语言。

`ARTIST_SURFACE_CLASSES` 的 compose_profile_view_model / compose_profile_success_state 与 `artistComposeProfile` 任一声明即尝试独立 hle.artist-compose-profile 组；声明不全或成员缺失降级，完全未声明不安装。缓存只在全部声明解析成功后完成。

`ArtistComposeProfileContract` 在注册前解析setUiState、header/shelves builder、entityType/entityTitle/entityViews、viewEntities、Success字段与构造器，并校验实例/static、返回/字段及赋值关系。Success可赋给setUiState参数，不猜直接父类；v1保留id、title、header、shelves、entity、long revision顺序，六字段须不同且与builder/构造参数一致。views按声明值类型调用其数组getter，不猜getData回退；artistType与topSongsView语义参数同样来自当前包。角色完整性以REQUIRED和适用离线清单为准，每构建getter证据独立记录，不借其他包结果。

`ArtistComposeProfileSessions` 弱持有owner/Success/艺人歌曲及pending。新Success、loading/error、非艺人或缺ID/属性关闭旧会话和排队任务；帧队列执行前确认会话和安装组仍活动，关闭只清本组，已出队旧任务也须失活。alias到达/恢复原名按当前entity重建title/header/shelves，保留原生ID/revision；top-songs关联在enrich前，地区判断仍来自歌曲。

原生实体或 shelf 的 `equals` 可能忽略标题，原地修改属性后重建相同列表仍会被 Compose 跳过。先核对真实文字读取链与快照实现；适用构建在 `metadataPresentation` v1 声明 `createState/readState/writeState/entityType/lockup/roomPrepare/pagedRow/pagedRebuild` 完整方法及 `searchSongRow/searchGridRow` 实体字段，沿既有成员解析器一次解析全部必需角色，独立安装 `hle.metadata-presentation`。未声明的旧档案沿用原路径，声明不全或解析失败报告本组降级。

显示状态通过已登记属性的 getter 在实际行组合内读取宿主 SnapshotState，alias 到达后更新该状态，让读取它的行失效；不改原生实体 ID、Success revision、点击和收藏字段。状态按属性弱身份持有，同别名由原生结构判等合并，恢复/关闭时发出恢复通知并清理状态与任务。该路径可用时，歌曲只通知对应显示依赖，艺人标题仍走既有 Success 重建；不要再为每首歌曲重建整页。

“查看全部”应从真实导航动作分别追到 Compose/StateFlow 和分页 Epoxy/PagedList 的实体准备、行构建与最终绑定，不复用资料库 LiveData 的假设。Compose 在原生 lockup 读取前登记当前行实体，分页行保存 model 与实体的弱身份关系，最终绑定再登记精确 binding/ID；有原生 model 缓存时使用声明的分页重建方法清缓存后构建，仍由既有控制器队列合并请求。搜索结果与最近搜索独立确认，歌曲行通过当前构建声明的实体字段识别，不凭标题相似匹配；沿现有查询、帧队列和恢复链提交结果。

**变更触发。** 状态布局、构造器、相等性、发布线程或生命周期变化时重分析Contract/Runtime/Session，名称/别名变化优先改精确档案。覆盖从index遍历，不硬编码版本文件列表。停用的全局目录语言及未启用播放模式UI不会因加档案自动启用。

[证据清单](host-adaptation-checklist.md#artist-compose-profile-checks) · [名称修正场景](feature-preservation.md#metadata) · [Compose场景](feature-preservation.md#artist-compose-profile)

<a id="settings-data-plugins"></a>

### 5.13 设置、蜂窝、DPI、数据与插件

**宿主接缝。** 旧设置为PreferenceFragment/PreferenceScreen与Activity结果桥，Fragment设置使用原生ViewModel/分类/action模型及Compose入口。两路均确认行key/分类去重、点击、callback及SAF/文件结果归属；构造器artwork/callback/Unit按当前包类型，不借相邻构建。UI经SettingsViewBridge等接口查询资格，不读profile或发现私有成员。

偏好列表的回调数量、函数接口或 Compose 参数变化时，在当前档案声明 `settings.members`，使用 `contractVersion=1` 及 `methods.preferenceItems`、`methods.compose` 的完整 owner/name/parameters/returns/static。两项由既有成员解析器在注册前完整解析，并分别校验 ViewModel/List 与设置 Fragment/void 的归属；只更新方法名不能适配参数变化。旧档案未声明该区域时保留其已审核签名，不能以目标构建的新参数覆盖旧路径。Compose 入口只观察原生参数，不自行调用或重排偏好列表回调；离线覆盖同步核对该区域的必需角色与完整描述符。

蜂窝入口要求独立精确能力，保留原生登录、类别、availability与默认关闭；不同页面族使用各自目标。DPI仅宿主范围，配置合法性、冷启动/重建与失败失活走既有AppleMusicDpiOverride，不把密度变化当新宿主资格。设置入口与 DPI 的组件过滤使用实际宿主 `packageName`；宿主类、方法和资源继续使用现有原包常量，不以安装包名替换。

#### 通用维护：配置与设置界面

普通参数通过共享EmbeddedSettingsDraft合并最新草稿并持久化，无全局“保存”；输入弹窗和歌词编辑器保留确认保存。失败提示并保留当前草稿，后续仍走同一保存链。子页共用对话框/草稿/主题，不另建Activity或配置管线；维护页面枚举时核对渲染、标题、尺寸、返回和销毁清理，子页编辑刷新停留原页。

LLM设置在自定义歌词页进入二级页，开关/API地址/密钥/模型/思考/测试集中管理；配置和测试不随翻译关闭隐藏，启用仍受上级开关约束。长字段标题与值上下分行、值单行省略、密钥遮蔽。图标使用EmbeddedSettingsSvgAssets各自viewport/描边与主题：已有填充资产保留1254 viewport，新轮廓使用24 viewport及圆角描边，避免改全局缩放。底栏动画二级页复用原四项配置/defaults，入口受宿主/玻璃/收缩资格，恢复单项/返回不能覆盖其他参数。

手动“测试连接”只显式点击时读取当前API/模型/密钥快照，在后台通过共享客户端发简短Chat Completions，验证非空模型内容；不读写歌词/缓存、不改开关、不代替整首翻译资格验证。在途单次、离页/关对话框取消并丢迟到结果；固定本地化原因区分缺配置、认证、404、429、其他HTTP、超时、DNS/TLS/连接及响应错误，不显示密钥、原异常或服务正文。

#### 通用维护：数据和公共协议

配置schema/键、文件ID/目录、迁移marker、SQLite/cache namespace及ZIP格式有明确归属，普通设置保存不覆盖字体/歌词等资产。继续使用既有原子发布、事务恢复、只读迁移重试、导入冲突与取消路径；格式变更单独设计迁移/回退。

宿主普通设置通过 `decodeOrdinarySettings` 解码，跳过独立歌词索引及旧内联歌词 JSON；显示/动画与资格检查不能因读取开关而反复解析歌词资产。歌词查询继续通过 `customLyricsManifest()` 的独立后台读取与缓存，完整 `decode` 保留导入/迁移语义。调整读取路径时核对消费者是否需要资产字段，不能用普通设置快照代替歌词索引。

插件公共SDK与runtime不持有Apple Music私有事实；内置Hook经通用注册记录参与冲突分析，作用域关闭同步释放记录。SDK/生命周期/冲突规则唯一参考[插件开发手册](plugin-development.md)，独立工程操作见[作者教程](plugin-author-guide.md)，不因宿主混淆改公共协议。设置文案/语言维护见[本地化说明](localization.md)。

[人工场景](feature-preservation.md#settings-data-plugins)

<a id="installation"></a>

## 6. 安装、降级与所有权

安装顺序为精确版本识别、配置迁移/绑定、DPI、资源回调、Application后功能与设置入口。资源基础设施失败停止依赖它的安装，单能力失败由该能力报告。Feature状态区分disabled、unsupported、degraded、failed及active，不把局部组成功上报为整功能成功。

仅安装包名改变时，`scope.list` 保留 `com.apple.android.music`，`module.prop` 使用 `staticScope=false`；安装后在 LSPosed 中手动为模块选择改名后的 Apple Music。首次加载与声明主进程过滤仍按实际宿主生效。

新增Hook使用 [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt)：preparing注册，必需项成功后activate，失败/退出close；关闭后回调原生透传，不依赖框架一定unhook。通用 [HookRegistrations](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrations.kt) 保存注册元信息，ModernXposedRuntime与AppleHookRegistrar接入内置记录，插件冲突分析复用同一来源。

HLE通过HleMetadataInstallation及AppleHookRegistrar.stageInstallation按表面分组，可选子组单独降级，父组关闭子组，后加getter继承所属组；已捕获的内层失败仍须上报。HostViewSessionController按view身份/config revision先关旧再发新，OwnedHostProperty只恢复自身仍拥有的最后写入，保留原生后续值。监听、observer、动画listener、任务都指明会话与清理入口。

成员缓存按owner、完整签名、ClassLoader身份组织，旧兼容调用保留原候选语义。slide、pre-draw、高亮及逐字热路径不扫DEX或发现新成员；文件、网络、字体和TTML处理用既有后台路径。

<a id="delivery"></a>

## 7. 检查、人工验收与回退

| 阶段 | 能证明什么 | 仍需分别确认 |
|---|---|---|
| 包/源码分析 | 精确身份、描述符、调用与布局语义 | 当前设备是否触发及显示 |
| 静态工具 | 覆盖范围内资料/契约一致性 | 未覆盖辅助路径、资源取值及运行行为 |
| APK构建 | 本次代码可生成安装包 | Hook、视觉及功能正确性 |
| 能力状态 | 当前进程各能力/子组安装与失败结果 | 页面任务完成和全部场景 |
| 人工验收 | 指定构建、设备、设置和操作结果 | 未执行场景及其他构建 |

工具说明见 [scripts文档](../scripts/README.md)：verify-profile-data.py、verify-architecture.py、verify-host-profile.py按当次证据缺口选用。宿主静态脚本读base二进制Manifest和包内split DEX/布局名，显式版本参数须一致；开发档案可经--profile传入。工具PASS不能替代资源/调用语义与人工场景。

项目默认由使用者手动验收；不主动操作设备或运行功能脚本、全量测试、哈希验证。仅文档修改不构建APK；构建交付时选择任务所需目标并记录实际产物。维护既有结构回归断言时随共用通道/配置变化更新覆盖，不能因文档重排修改夹具来掩盖差异。

每构建用[清单](host-adaptation-checklist.md)登记实际完成阶段、缺口和回退，以[矩阵记录模板](feature-preservation.md#acceptance-record)记录人工结果。交付至少明确精确profile、必要接入、通用文档、任务所需安装包、未覆盖场景及能力缺口；发布/推送/PR依任务授权。

回退先停新tuple或相应能力并恢复原生路径。配置/数据格式保持时通常无需反向迁移；格式变更须给出独立迁移/回退。已支持构建分别登记回归范围，未执行不填通过，不能扩大候选或改冻结基线替代证据。


