# Apple Music 宿主适配手册

本手册供维护者为一个具体 Apple Music 构建建立、维护和验收 AM++ 接入。适配对象是包名、versionName 和 versionCode 组成的精确版本组合；正式版、beta 和同名的不同构建分别处理。手册覆盖当前 Activity、Fragment 两类宿主，并作为后续版本的统一流程。

各功能的运行规则、成员契约和维护入口集中在本手册；[适配清单](host-adaptation-checklist.md)集中逐项核对与人工场景，[功能保全与验收矩阵](feature-preservation.md)定义跨功能边界和结果记录方式。单次任务的排查过程、代码改动和构建日志保存在任务记录中。

## 1. 适用范围与版本登记

版本资料位于 [host-profiles](../host-applemusic/src/main/resources/host-profiles/)。支持范围由 `index.json`、精确 profile 的 `productionEnabled` 和能力标志共同确定。版本表、成员映射、已知降级与该构建的证据在档案中维护，本手册不重复登记。

“已启用”表示允许模块接入该构建，不表示全部功能已经通过当前代码的真机验收。功能还受设置、Android API 和设备形态限制；人工验收模板见 [功能矩阵](feature-preservation.md)，实际结果在对应任务中记录。

页面族按实际入口、生命周期和布局识别：`legacy-activity` 与 `fragment-content` 分别使用自己的接入。正式版、beta、相邻构建和新页面族均需独立分析，版本号大小和主版本号不能代替页面族识别。

`AppleMusicHostProfiles.find` 按版本名、版本号定位文件，并校验包名；生产资格另检查 `productionEnabled`。资料文件名使用 `<versionName>-<versionCode>.json`。同一组合只能登记一次。索引顺序还影响旧 HLE 引擎的已审核候选，适配时应保留现有条目的相对顺序。

## 2. 架构与修改位置

| 模块 | 职责 | 适配时的边界 |
|---|---|---|
| `core` | 配置、歌词与字体业务、来源、缓存、布局和会话策略 | 不引入 Android、宿主反射或 Xposed 依赖 |
| `host-api` | 宿主能力、歌曲身份、区域接口、配置读取、安装结果和可关闭订阅 | 不暴露混淆成员、反射类型或宿主 AndroidX 实现 |
| `hook-runtime` | Xposed 接入、注册作用域、通用调用缓存 | 不承载 Apple Music 版本策略或业务页面 |
| `host-applemusic` | profile、符号解析、原生歌词、设置、播放器、导航和元数据接入 | 所有 Apple Music 私有成员和页面族差异集中于此；玻璃的原生位移读取和 Compose 边界识别也只在此层实现 |
| `app` | 功能装配、配置存储、设置界面、歌词服务与玻璃会话 | 通过宿主接口使用能力，不自行寻找混淆成员 |
| `glass` / `backdrop` | 材质、采样和呈现 | 不管理 Apple Music 版本资格 |
| `plugin-api` / `plugin-runtime` | 插件 SDK、导入、加载、生命周期和冲突分析 | 不随单个宿主构建改变公共协议 |

宿主装配入口为 `AppleMusicHostFactory`，按当前精确 profile 的 `family` 选择设置、双栏、蜂窝和视频处理路径。只发生混淆变化时，优先修改版本数据；调用形式、生命周期或布局语义变化时，修改对应宿主适配层。

内部接入遵循三类职责：

- **Contract**：解析并校验成员、资源及相互类型关系，持有缓存句柄。
- **Runtime / Coordinator**：注册 Hook、管理活动宿主和能力状态。
- **Session / Binding**：管理具体视图、监听器、延迟任务和属性恢复。

业务开关、缓存规则和歌词来源逻辑不应因混淆名字变化而重写。新页面族需要单独的能力工厂，不能默认归入已有路径。

## 3. 版本资料与成员契约

### 3.1 profile 的主要区域

| 区域 | 应记录的内容 |
|---|---|
| 身份与资格 | `id`、`packageName`、`versionName`、`versionCode`、`family`、`productionEnabled`、`profileRevision` |
| `capabilities` | 玻璃、蜂窝、收起拦截、播放器恢复等独立资格 |
| `indexed` | 类、方法、字段映射；支持精确契约的路径还填写 `methodContracts` / `fieldContracts` |
| `hookTargets` | HLE Hook 入口、签名约束、调用方约束及 `runtimeMemberNames` |
| `artistComposeProfile` | 可选 Compose 艺人页的完整方法、字段、构造器和艺人类型/top-songs key；声明后必须完成安装前契约解析 |
| `settings` / `chrome` | 设置入口、原生设置模型、导航和播放器资源或旧宿主契约 |
| `layoutVariants` / `cjk` | 歌词 binding、渐变和前景文本等版本数据 |
| `cjk.terminalGradient` | 可选的 CJK 末词羽化补偿精确契约：扫光/余量方法、reader、行词向量、词 ID/时间与动画标签 |
| `lyricBlur` | 两类页面族独立使用的完整歌词模糊契约：显示提交、文档/行身份、绑定、词动画、视图、触摸/滚动与 Seek |
| `lyricWrapSweep` | 可选的跨行歌词扫光精确契约：原生扫光调用、动画更新与退场渐暗回调、静态遮罩与暗色调色板、视觉行遮罩键与词时间、媒体时间轴及暂停/恢复/Seek 入口 |
| `lyricResultSubmission` | 结果提交通道的精确契约：结果容器、发布入口、派生时间范围索引重建与当前歌词身份读取；启用该通道的构建必须完成安装前契约解析 |
| `startupPagePrewarm` | 可选的导航一级页面预热 v1：内容视图生命周期、有效 tab、容器构造、原生隐藏保留事务、参数键、页面共享状态保护与各形态导航资源 |
| `catalogQueryRenames` | 实际目录查询 owner 与方法映射，配合完整签名和调用路径核对 |
| Fragment 专用区域 | 双栏、歌词运行时、逐字宽度、视频接力、chrome 和恢复所需契约 |
| `verification` | 离线检查需要的 DEX 描述符、字段类型、资源名和辅助接口证据 |
| `evidence` / `knownDegradations` | 来源、证据状态、运行时确认范围及已知能力缺口 |
| 匹配策略 | `ambiguityPolicy` 和历史 `legacyFirstMatchExceptions` |

JSON 描述宿主事实，不承载可执行逻辑。参数变换、回退条件、线程和生命周期策略由 Kotlin 实现。profile schema 当前为 1，应用配置 schema 当前为 15。

### 3.2 完整描述符与语义核对

| 成员 | 必须核对 |
|---|---|
| 方法 | 实际声明 owner、名字、参数顺序和完整类型、返回类型、static、抽象/bridge/synthetic 属性 |
| 字段 | 实际声明 owner、名字、完整类型、实例归属和被读写的含义 |
| 构造器 | owner、完整参数类型与顺序、创建对象的实际用途 |
| 接口调用 | 返回对象实现关系、实际声明接口、调用形式；必要时使用 `invocationOnly` |
| Hook 目标 | 可 Hook 的具体实现，及它是否位于实际活跃调用路径 |
| 资源 | 名称、类型、限定符、实际取值、包含布局、运行时父容器和参数类型 |

“类存在”“参数个数相同”和“成员能反射调用”均不足以证明正确适配。混淆类名可在新包中承担其他职责；旧方法名也可能仍存在，却接收另一种模型。应沿调用方、返回对象和后续读写核对完整链路。

继承成员记录实际声明 owner。抽象接口可作为带 `invocationOnly` 的调用契约，不能作为新增 Hook 目标。数组、primitive、可空对象和实例/静态调用不能相互替代。

### 3.3 两类解析路径

`IndexedTargetSymbolResolver` 为每个符号缓存解析结果，包括缺失和歧义。已声明的精确方法或字段契约不匹配时返回缺失；稳定名和结构回退是否允许，由该符号的策略决定。必需成员不能通过另一个相似符号补齐。

HLE 的 `AppleMusicHookProfiles` 将 JSON 转为兼容引擎的候选。Fragment 档案使用严格候选；旧 Activity 档案保留既有版本匹配和已审核回退顺序。外层生产门禁始终精确匹配版本组合。

新档案采用 `reject-ambiguous`。历史首个匹配例外只适用于原有已审核目标，不能作为新版本的默认策略。若发现两个候选，应补充类型、调用关系或契约，无法唯一确定时降级对应能力。

## 4. 每个版本的适配流程

### 4.1 确认安装包与代码基线

1. 从实际 base APK 的二进制 Manifest 确认包名、versionName、versionCode；包文件名及外层清单只作辅助。
2. 清点 base、全部配置或功能 split、各 DEX 和资源来源。不能假定 DEX 仅位于 base。
3. 记录代码基线、拟复用的页面族及对照宿主。保存可回溯的源码位置、描述符和资源证据。
4. 明确工作范围：源码分析、静态检查、构建及人工功能验收分别安排。包哈希只在明确要求时计算。
5. 保存当前代码与必要测试产物，以便对照和回退。

Java 反编译用于理解调用链，成员声明与完整签名以 DEX/smali 为准。工具生成的类型推断、命名修复以及大小写冲突后的文件名不是宿主原始类名；嵌套类、接口和泛型擦除后的实际类型按字节码记录。资源反编译还需核对限定符和实际包含关系。

### 4.2 建立完整接入清单

从工厂、能力入口和实际调用链展开，覆盖设置、歌曲身份、自定义/自动歌词、模糊、CJK、字体、名称修正、手机/平板玻璃、双栏、视频与背景、蜂窝及 DPI。

每项都记录“直接 Hook 入口”和“回调继续使用的附属成员”。附属成员包括 binding、holder、getter、listener、列表、Fragment 事务、浏览器接口和模型字段。没有变化的契约也应有当前安装包依据。

### 4.3 建立档案并实现接入

1. 建立精确 tuple JSON，开发期保持 `productionEnabled=false`。
2. 按实际页面族填写通用区域和该族必需区域；从相邻构建取模板时逐项重新核对。
3. 将新依赖同时加入 profile、代码维护的必需角色清单和适配清单。
4. 调整 Contract、Runtime、Session 中发生变化的调用或生命周期；同一能力不同时维护一份固定旧版本映射和一份新映射。
5. 缓存成员与资源，处理失败和恢复。缺失目标应明确报告能力状态。
6. 对照全部布局限定符和生成 binding，确认原生重绑定、尺寸动画和异步回调不会覆盖模块布局规则。

### 4.4 检查、登记与交付

静态核对和源码检查用于发现成员与资料缺口；APK 构建用于确认可以生成交付物；人工验收用于确认具体功能和设备场景。三者分别记录。

完成接入后登记到 `index.json`。为明确请求的用户测试启用精确 tuple 时，保留尚未完成的运行时验收状态。相邻构建和未登记版本仍不获得资格。

交付至少包括精确 profile、必要宿主接入、当前通用文档、可安装 APK、能力缺口和按版本填写的验收结果。发布、推送和 PR 依任务授权单独处理。

## 5. Activity 页面族

`legacy-activity` 的主要入口为 `AppleMusicDualPaneTarget`、`DualPaneShell` / `ConstraintLayoutPane`、`LegacyChromeHostBinding` 和旧设置桥。

| 接入域 | 每个构建应核对 |
|---|---|
| Activity 与播放器 | PlayerActivity 根视图、BottomSheet、控制器初始化/创建/选择 pane、原生枚举和状态访问器 |
| 双栏资源 | 主播放器布局、左右 host、ConstraintLayout 字段、歌词 sheet、导航高度、收起边界与共享左栏的角色载体 |
| 歌词同步 | Fragment binding、RecyclerView、渐变、度量、行词回调及监听清理 |
| 旧 chrome | 导航 holder、导航切页、mini、展开进度、拖动与采样区域 |
| 设置 | PreferenceFragment、PreferenceScreen、设置行 key/标题/摘要、生命周期及 Activity 结果桥 |
| 视频规则 | 既有 Editorial URL 处理范围、预览及 Music Video 的原生行为 |
| 名称修正 | HLE 模型与 binding、Epoxy、资料库、主页等全部显示表面 |

旧宿主的 AndroidX 参数类可能经过重新打包和字段混淆。应依据实际字段类型和原生使用位置确认映射，不能直接采用另一构建的字段名，也不能用模块自带 AndroidX 对象替换宿主对象。

旧路径的布局安装、原生状态选择及已审核候选回退属于兼容语义。调整通用接入时，需对已登记构建分别列出回归项。已有冻结夹具位于 `host-applemusic/src/test/resources/baseline/`；普通适配不能以更新夹具消除行为差异。

玻璃、蜂窝和 `staticCollapsedIntercept` 是独立资格，按当前 profile 判断，不能扩展到其他构建。操作表、主页封面等子面的已知降级单独记录，不能因其他表面成功而省略。

## 6. Fragment 页面族

`fragment-content` 的播放器、页面导航与 mini 生命周期由 Fragment 接入；不能套用旧 Activity 的 holder、设置或事务模型。

### 6.1 必需契约

| 区域 | 代码入口 | 完整性要求 |
|---|---|---|
| `fragmentDualPane` | `FragmentDualPaneContract` | 主播放器状态、Fragment/Manager/事务、pane LiveData、封面 callback、BottomSheet、歌词可用性及 binding 完成接入 |
| `fragmentLyricsRuntime` | `FragmentLyricsRuntimeContract` / `BetaLyricsPaneRuntime` | 生命周期、binding/附属容器、RecyclerView、度量、行监听注册与清理 |
| `fragmentKaraokeWidth` | `FragmentKaraokeWidthContract` | adapter、缓存宽度、holder、item、binding 与 flexbox |
| `fragmentTabletVideoHandoff` | `FragmentTabletVideoContract` / `FragmentTabletVideoHandoff` | 元数据 listener、视频尺寸/原生 resize 入口、静态基准/目标尺寸、TextureView、浏览器接口、模式和动画字段 |
| `fragmentDualPane.songLayout` | 歌曲栏 Contract / Runtime / Session | 明确需要覆盖布局时的参数、Guideline、Barrier、binding 与 padding setter |
| `fragmentChrome.members` | `FragmentChromeContract` / `FragmentChromeInventory` | 导航、菜单、LiveData、进度、生命周期、BottomSheet 和 mini 启动 |
| `indexed` 与 `playerRecovery` | `FragmentPlayerRecoveryContract` | 封面、尺寸基准、背景、进入/共享动画及销毁恢复的精确契约 |
| `settings` | Fragment 原生设置工厂及模型契约 | ViewModel、原生行模型、构造器、callback、Compose 入口及结果归属 |

`FragmentDualPanePreflight` 按 `FragmentDualPaneInventory` 解析双栏及附属组，再校验成员间的类型关系。JSON 少写必需角色会导致预检失败。chrome 和播放器恢复分别完成自己的必需契约解析，不能把双栏预检成功当作其他能力完整。

`songLayoutPolicy` 显式选择 `native` 或 `1606-column`。后者是现有列布局策略的标识，所有成员仍从当前版本 profile 读取。`native` 不携带布局覆盖；`1606-column` 必须提供 `songLayout`。新增布局策略需要代码和契约共同实现。

`layoutReview` 的六个字段必须有当前构建证据：`artwork`、`metadata`、`controls`、`progress`、`binding`、`restoration`。填写证据说明已经做过对照，不表示真机通过。

### 6.2 Fragment 生命周期与容器

`FragmentTabletDualPaneCoordinator` 管理控制器，`FragmentTabletDualPaneSession` 管理单个播放器视图。Fragment view 重建、配置变化和 controller 销毁分别处理。保存状态期间的事务应延后；恢复中的歌词 host 保持可测量，不能在事务提交前折成无效宽度。

右栏运行时 ID 和 Fragment 恢复 tag 属于持久恢复约定。其历史命名不影响作用，不能随版本号改名。SONG / QUEUE 使用原生状态和选择方法，独立歌词放在右栏。

`PlayerSurfacePort` 及其 Fragment 适配提供导航、mini 和播放器区域。模块视图只挂入允许的普通容器或 sibling；受限的 FragmentContainerView 不接受任意模块 View。

### 6.3 导航、mini 与设置

手机使用原生底导航与 mini 边界；平板使用顶部导航和独立底部 mini。平板 Fragment 玻璃与双栏开关独立。导航模型、三个 getter、选择 callback、重复点击、搜索及抽屉逐项核对，不能只找一个签名相似的方法。

手机玻璃的收回目标和终态布局通过宿主端口协调。`fragmentChrome.members` 必须登记原生启动平滑落位的 `settleSheet(View, int, boolean)` 和重新计算折叠坐标的 `refreshCollapsedOffset()`，并核对当前构建的目标状态参数、BottomSheet 状态、折叠坐标和 peek setter 语义。原生开始向折叠状态落位前，先同步模块占位高度并刷新折叠坐标，再让原生动画读取目标；相同 peek 值也须能刷新过期坐标。模块写入不能被记成新的原生 peek 基线。

收回期间可能因 inset 或布局变化再次改变折叠坐标。宿主适配须记录原生本次落位的 owner、sheet、目标状态和目标坐标；只有仍在该次收回的 settling 且目标坐标确实变化时，才通过原生平滑落位入口更新剩余动画终点。保持正常拖动及展开、隐藏路径，不直接改写 View 的 top/translation，不在目标未变时重复启动动画；原生开始其他目标或进入拖动/稳定状态、会话销毁时清理记录。动画结束后的 `FragmentPhoneChromePort.reconcileCollapsedSheet` 位置检查仅作兜底：原生已折叠但实际位置仍偏离有效目标时，合并布局请求并在完成前阻止该帧绘制。该检查覆盖后续 inset 和布局变化，不能仅依赖 peek 数值变化或进度归零。

原生面板位置校准须先于 mini 折叠锚点采样。材质岛的待处理尺寸和边距应在同一轮绘制前完成测量与布局，不能依赖多个 pre-draw listener 的注册顺序。适配新版本时沿用宿主端口边界，状态及坐标成员从当前 profile 预先解析；旧 Activity 与平板布局分别维护。

Fragment 手机与平板共用 `FragmentMiniStartup`，在现有玻璃接入创建 `FragmentSurfaceBinding` 时启动 mini 恢复，不以导航位于顶部或底部区分。需核对两种布局的 `miniBlur` 资源及其原生显示语义：材质已为 `VISIBLE` 时不补调显示；尚未显示时，必须同时满足原生登录判断及当前播放对象存在，才调用原生显示入口，不能只修改材质 View 的 visibility。显示方法可能为实例调用，也可能是静态的 `(owner, boolean)`；从当前 profile 解析并按准确描述符及调用标识处理。

每个 Fragment view 会话先投递一次检查，条件未满足时每 250ms 重查，最多 40 次检查；补调显示、已显示或异常时结束，耗尽后保持原生状态。每次延迟检查确认 owner 当前 view 仍为该 root 且已附着，会话销毁时撤销全部待执行回调。适配手机与平板时分别覆盖账号/队列延迟恢复、无登录或无当前对象、已显示、超时和 view 重建场景；沿用既有玻璃安装资格，不将该接入等同于独立的原生播放器恢复功能。

底栏随滚动收缩须单独登记 `capabilities.glassScrollCollapse`，不能由普通玻璃资格推导。设置界面只通过 `SettingsViewBridge.supportsGlassScrollCollapse(activity)` 查询可用性；SDK、宿主平板资格和精确版本能力由适配层判断，旧页面族默认返回不可用，不在 UI 导入或读取具体宿主 profile。目前只为 `7.0.0-beta` 的 1606、1607、1609 profile 开放；会话还须属于手机底导航，且不使用宿主 `is_tablet` 布局。旧 Activity、所有平板布局与未登记构建保持展开，不显示收缩开关和回弹参数。旧配置中的平板收缩字段仅为读写兼容保留，不参与资格判定。新构建完成导航、滚动和手势契约核对后才能单独开放该能力。收缩与展开共用唯一动画 owner：`GlassBarCollapseController` 持有启用与外观 Compose 状态、目标布尔、单个 `Animatable`、协程作用域和滚动监听生命周期，导航、原生 mini、内容 padding 与 sheet peek 只读取同一进度；`PhoneGlassSession` 只负责创建该 controller 并接入解析出的应用几何，不复制收缩数学或另建动画。

动画自定义参数集中在“高级设置 → 底栏动画设置”二级页，主页面保留“随滚动收缩”开关。二级页入口同时检查宿主收缩能力、玻璃及收缩开关；四项参数复用原有配置键、范围、`EnhancementDefaults` 和实时渲染传递，不按宿主版本复制设置。`EmbeddedAnimationSettingsPage` 只绘制设置控件，不拥有动画或宿主反射。子页与主页面共用同一个 `EmbeddedSettingsDraft`，修改须从最新草稿合并，连续调整、单项恢复默认和返回后不能覆盖其他参数；系统返回与标题栏返回均回到主页面，对话框销毁须释放返回回调和页面刷新引用。

新增宿主形态时重新核对几何前提：收缩控件当前与 `GlassPolicy.MINI_HEIGHT_DP` 共用高度，不能把不同 mini 高度的宿主直接套入现有恒等式。当前收缩手机导航没有前导抽屉按钮；若新宿主同时需要前导按钮与收缩，绘制、共享几何和触摸命中必须传入相同的 leading 宽度，不能只在绘制侧扣减。重新同步上游组件时，按 `backdrop/UPSTREAM.md` 保留本地收缩接入及裁切选择。

原生列表位移通过 `ChromeHostBinding.verticalScrollOffset(View)` 读取。宿主适配在发现滚动容器时，根据实际运行时类缓存 Android `View.computeVerticalScrollOffset(): int` 的覆写方法，滚动回调只调用缓存成员；不能把宿主 RecyclerView 强转为模块 AndroidX，也不能用 `scrollY` 替代 RecyclerView 或 ListView 的列表位移。该方法遵循 Android framework 的固定覆写契约，若宿主未来不再通过该契约公开位移，应在适配层提供有证据的新读取方式。单类读取失败时停用该类的原生信号，保留页面手势观察；会话关闭清理缓存。原生位移读取与 Compose 边界识别都留在 `host-applemusic` 的宿主绑定中，`app` 只经 `ChromeHostBinding` 的 `verticalScrollOffset`、`canScrollVertically` 端口取用；新增宿主形态时在绑定层扩展，不在功能代码里增加宿主类名或反射。

内容根的触摸观察只路由当前 view 会话的一次事件，不消费、不改写或重新分发事件。ACTION_DOWN 先记录导航及 mini 的起点归属：展开态按整条标签拖拽交互带判断，收缩态按共享收缩策略计算胶囊和可交互搜索控件的当前边界，含布局方向及最小触摸尺寸，不能把中央空白也当作导航控件。从底栏或 mini 发起的整次手势均不驱动收缩，即使手指移出控件也保持排除。该归属同时约束原生位移信号，直到下一次页面手势；页面手势抬起后继续允许原生惯性滚动。取消事件不作为抬手速度处理，多指、后台及关闭开关清理输入状态，避免旧位移或旧速度改变新会话的底栏。起点归属与整次手势状态由 `GlassBarCollapseInput` 独占，其单次页面手势记录 `downTime`、命中的原生目标、边界目标、是否仍在触摸、已确认方向和位移基线；`GlassBarTouchRegions` 只做 chrome 的屏幕空间命中，不消费、不改写也不重新分发事件，原生 mini 控件仍由宿主分派。胶囊和分离搜索的宽高及 top 共用 `GlassBarCollapseLayout.controls(...)`，绘制与触摸命中共用这组几何。

玻璃已完成呈现并接管受支持的手机导航时，宿主适配还须隔离被隐藏的原生导航子树的触摸分派；`alpha = 0` 和无障碍隐藏均不代表禁止触摸。接管前向该原生导航发送一次 `ACTION_CANCEL`，清理可能尚未结束的原生按压；隔离依据当前 view 会话的呈现接管状态，撤销接管或销毁后恢复原生分派。隔离只作用于原生导航身份，不覆盖内容根、mini 或整个玻璃行，不修改菜单启用状态及程序化选中接口；收缩后的空白区域继续交由页面或原生 mini 接收。未支持收缩的宿主与平板不进入该接入。

收缩胶囊的图标与强调色须复用实际分离出的搜索项身份。只有当前选中项就是已分离的搜索项时，才使用首个标签图标及非强调色；两标签菜单、搜索不在末尾或无法分离时，保留实际选中标签的图标与强调色，不能仅凭 `isSearch` 回退。

层级接入、前台恢复和设置刷新时确认当前 `ViewTreeObserver`。实例替换后从旧观察器移除滚动监听，再按启用状态绑定新实例；销毁后不得重新绑定。

两路信号需明确方向所有权。手指接触期间只由手势跟踪器决定方向，原生回调只更新位移基线；手势中达到反向阈值时仍能立即改变目标。原生惯性阶段只跟踪 ACTION_DOWN 命中的当前滚动容器，且只能延续该手势最后确认的方向。底栏弹簧运行期间不以原生位移重新判定目标，避免 peek、内容留白、列表条目尺寸估算和边缘回弹引起的位移把底栏反向拉回。不能同时累加多个可见或离屏列表的偏移。

手势在 MOVE 阶段已确认方向后，ACTION_UP 仅结束该次手势，不再用抬手末尾的位置变化重定向底栏。未决的短促滑动可在抬手时补判，但末端位移或速度须与整次手势的净移动方向一致，不能因触点回带或极短速度窗口产生相反意图。手指未离屏时持续反向拖动仍按 MOVE 阈值改变方向，不锁死整次手势。

手势位移超过阈值仍不等于页面能滚动。接受收缩或展开意图前，通过宿主端口查询当前命中目标的 `View.canScrollVertically(direction)`，其中 `-1` 指向内容起点、`+1` 指向末尾；到顶或到底继续向外推、不足一屏的静态内容以及无法确认边界的区域均不改变底栏状态，向内容内侧滚动仍正常触发。拒绝的边界意图不能成为后续惯性阶段的确认方向。

原生列表使用实际容器的 framework 滚动契约。ComposeView 外壳不代表内部的滚动能力，应在当前 profile 识别出的 Compose 场景内找到覆写 `canScrollVertically(int)` 的语义 owner，并缓存运行时类的契约识别结果；调用沿用 framework 虚方法，不借用模块 Compose 类型或宿主混淆私有字段。Compose 边界与触摸起点相关，须等子树收到当前 ACTION_DOWN 后，在 MOVE/UP 的意图判定阶段查询，不能在根节点预处理 DOWN 时读取上一次手势的语义位置。每次手势重新选择命中目标，view 重建后重新发现当前场景，关闭宿主绑定时清理契约类缓存。缓存的是已发现的 Compose 场景列表及其语义 owner，每帧只对这几棵已发现场景根检查 `isShown` 和高度，不缓存可见性布尔，使仅可见性变化不会让缓存过期；整树枚举、边界 owner 与起点目标集合只在层级失效时重新扫描，避免每帧遍历整棵树。

收缩和展开各自的目标必须幂等：同一目标的后续滚动信号不重启弹簧。真正改变目标时立即交给同一个 `Animatable` 重定向，并保留当前值与速度；即使旧动画尚未进入第一帧、当前值恰好等于新目标，也要撤销旧目标的运动。验收时分别从完全展开和完全收缩的稳定端点开始，覆盖持续同向拖动、短促滑动、抬手惯性和中途反向，确保不会出现离开端点一点又被反向拉回的循环。进度由 `Animatable.animateTo` 驱动，每帧回调只请求宿主下一帧重绘（`postInvalidateOnAnimation`），并在动画结束或取消的收尾中补发最后一帧；不恢复常驻 `snapshotFlow` 采集或逐帧轮询。

搜索项只在菜单末项且剩余导航项足够时分离；语义识别不成立时保持单胶囊收缩。所有视觉表面、peek 和内容留白共用收缩进度。mini 尚未显示或暂时消失时仍允许收缩，左右控件照常运动、中央留空属于布局约定，不添加 mini 可见性前置条件。播放器 sheet 的原生收回校准与底栏收缩分别维护；收缩弹簧运动期间暂停 sheet 落点校准，停止后恢复，不能永久关闭主线的原生落点修正。关闭开关时的展开过渡同样让导航、mini、padding 与 peek 读取同一条曲线；`expand()` 先清理旧手势与惯性，切换导航目的地（含宿主主动更新选中项）和配置改变都调用它，会话重建不保留旧状态。mini 槽位边距按布局方向以 start/end 计算，RTL 下两侧互换。

Fragment 设置使用原生 ViewModel 和设置行模型，保留分类、行去重、点击与文件选择结果归属。构造器中的 artwork 类型、callback 和 Unit 不应借用其他构建的类型。

## 7. 各功能的适配要点

维护规则集中在本节：[歌词模糊](#lyrics-blur)、[CJK 辉光与末词扫光](#cjk-animation)、[跨行歌词扫光](#lyric-wrap-sweep)、[歌词结果提交](#lyrics-result-submission)、[玻璃底栏收缩渲染](#glass-collapse-rendering)。成员核对与完整人工场景集中在 [适配清单](host-adaptation-checklist.md)。

<a id="lyrics-blur"></a>

### 7.1 歌词模糊适配与维护

`AppleMusicLyricBlurContract` 对两类页面族均解析独立 `lyricBlur` 区域，不依赖双栏开关或双栏契约解析成功。以下规则用于已登记构建维护和新版本适配；精确版本、成员与能力资格仍以当前 profile 为准。

#### 7.1.1 显示行为

| 项目 | 规则 |
|---|---|
| 高亮歌词 | 正常跟随时焦点半径和边缘权重目标均为 0；实际效果可以在 300ms 内逐渐清晰 |
| 过渡 | 清晰与模糊两个方向均为 300ms 线性插值；同目标事件不重启动画，目标改变时从当前值接续 |
| 已唱歌词 | 从距离 0 起，基础半径为 `0、13、17、22px`，更远保持 22px |
| 待唱歌词 | 从距离 0 起，基础半径为 `0、8、13、17、22px`，更远保持 22px |
| 用户偏移 | 使用现有配置键、单位和范围，高亮目标 0 不受偏移影响 |
| 无三点前奏 | 有首句时间与播放位置或宿主前奏状态的证据时，文档首句清晰，后续按原曲线模糊 |
| 三点延迟显示 | 三点实际绑定、显示且有尺寸后，平滑切到间奏距离；首句未高亮时基础目标为 8px，已高亮仍为 0 |
| 间奏 | 锚点为间奏后的第一条歌词序号；最近的已唱、待唱歌词分别按距离 1；三点自身不施加模块模糊 |
| Credits | 单独分类，继承文档末尾歌词的焦点强度 |
| 歌词结束 | 保留末句期间最后有效的焦点和模糊分布，不因高亮集合变空让整页清晰；文档变化与 Seek 清除旧焦点 |
| 用户浏览 | 触摸或用户拖动后平滑解除焦点模糊；长按与用户惯性滚动继续保持浏览模式 |
| 恢复跟随 | 手指离开且列表空闲 1 秒后，按最新显示状态平滑恢复；再次触摸或滚动撤销计时 |
| 程序滚动 | 宿主自动跟随不会主动进入浏览模式 |
| 平板边缘 | 上下 10% 区域、最大 5px；浏览模式保留边缘效果 |

前奏、间奏和高亮保护分开保存。前奏确认在文档变化和 Seek 时清除，在宿主实际显示行/词或进入其他间奏时更新；不能仅因空高亮集合就推断为前奏或间奏，也不能把第一条可见行伪装成高亮。

#### 7.1.2 分层与入口

| 层 | 职责与维护入口 |
|---|---|
| `core` | [LyricBlurState](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricBlurState.kt)：事件、代际、归并、保护集合、焦点与交互；[BidirectionalBlurPolicy](../core/src/main/kotlin/dev/amenhancer/module/hook/BidirectionalBlurPolicy.kt)：原半径曲线、偏移、量化与插值 |
| `host-api` | [LyricBlurHostPort](../host-api/src/main/java/dev/amenhancer/module/hook/LyricBlurHostPort.kt)：文档、显示快照与行身份接口；不暴露宿主私有对象 |
| `host-applemusic` 契约 | [AppleMusicLyricBlurContract](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurContract.kt)：解析 profile、必需角色及类型关系；[HostPort](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurHostPort.kt)：读取宿主已经应用的显示状态 |
| `host-applemusic` 会话 | [Runtime](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricBlurRuntime.kt)：Hook 与活动视图；[LyricBlurViewSession](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewSession.kt)：每个 root 的绑定、恢复任务和帧驱动 |
| `host-applemusic` 渲染 | [Renderer](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurViewRenderer.kt)：逻辑行动画与边缘几何；[EffectOwner](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricBlurEffectOwner.kt)：效果写入与恢复 |
| `hook-runtime` | [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt)：PREPARING、激活与关闭 |
| 装配 | [AppleMusicHostFactory](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) 创建唯一 [能力目标](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicBidirectionalLyricBlurTarget.kt)；`app` 保留设置、门禁与能力状态入口 |

Credits 与三点语义标记由 [LyricCreditsRowResourceHook](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricCreditsRowResourceHook.kt) 接入。字体、翻译、CJK 和双栏通过现有架构协作，模糊不依赖双栏开关或双栏安装成功。

仅混淆名变化、语义一致时，主要更新精确 profile。宿主提交时序、模型或生命周期变化时，修改相应 Contract、HostPort 或 Runtime；共享状态与渲染规则继续复用。新页面族必须重新分析，不能仅凭主版本号选旧接入。

#### 7.1.3 宿主契约与显示读取

必需角色由代码维护。新增依赖时同步更新 `AppleMusicLyricBlurContract`、当前 profile 和 `scripts/verify-host-profile.py` 的独立清单；JSON 少写一个角色不能绕过安装预检。

每个构建独立核对以下链路：

- Fragment 生命周期、binding、精确 RecyclerView、子项监听、触摸和滚动状态。
- 逐行与逐字 adapter 的提交、快照、位置映射、类型、稳定 ID、文档安装、绑定及回收。
- 文档 token/reader、native 行和词 ID、首句时间与当前媒体浏览器播放位置。
- 主词、背景词、发音与背景发音四路模型及实际文字动画、开始/结束/取消释放；纯装饰动画不延长保护。
- Native、VM 刷新及媒体控制器各个实际 Seek 入口。

方法记录实际声明 owner、参数、返回类型、static 和调用方式，字段记录 owner、完整类型及对象归属。沿实际 `extends` / `implements` 关系确认继承或接口成员；使用 DEX 的擦除签名，不把反编译工具推断的泛型、类型或修复名称当作契约。

| 身份或角色 | 用途 |
|---|---|
| `nativeLineId` | 文档内原生歌词身份，用于显示保护与文档映射 |
| `lyricOrdinal` | 文档内歌词序号，用于距离计算 |
| `adapterPosition` | 当前列表位置，经 adapter 映射后才能对应歌词 |
| `nativeLineId/nativeWordId` getter | 原生对象 ID 角色，与 adapter getter 分开登记 |
| `lineId/wordId` getter | adapter 稳定 ID 角色，用于核对当前 holder 与绑定身份 |
| `nativeLineBegin/nativeLineEnd/mediaBrowser/mediaPosition` | 首句时间、文档最大结束时间与当前播放位置；前奏和歌词结束所需证据 |
| `wordAnimationTag` | 当前构建的标记接口 getter；识别主动画中的纯位移子项 |

在 adapter 实际提交方法完成后重新读取其显示集合，包括宿主因输入是旧集合子集而跳过更新的情况。宿主等待 ItemAnimator、再向 Handler 提交期间，继续使用已应用的显示状态。Native 和 VM 事件可以触发刷新，不能直接把新 raw 集合替换为显示集合，也不能因 Native Hook 安装成功永久屏蔽其他来源。

词保护读取四路模型的主文字动画，递归排除 `KARAOKE_WORD_LIFT_TAG` 位移子项；`p` 中的辉光、缩放和回落不作为高亮证据。只观察相关文字子动画的开始、结束与取消，及时触发快照刷新；不等待整个装饰动画组结束，不使用固定 grace 或修改宿主动画。模型归属复用弱身份映射，避免绑定字段改变 `hashCode` 后丢失结束通知。保护集合与焦点独立：即使焦点进入间奏，仍在显示的高亮/词行保持目标 0。

Native vector、pointer 及包装对象在 Hook 当次同步解码，只向主线程提交复制出的数值。`Complete` 空集合、`Pending`、`Partial` 和 `Failed` 分别处理；不完整读取不能解释为空高亮。没有可信显示状态时平滑解除焦点模糊，等待下一次有效事件恢复。`NO_POSITION`、未知类型和身份不一致的行不参与距离计算。

#### 7.1.4 文档、视图与交互生命周期

每个歌词 root 有独立会话，全屏和右栏可同时存在。事件按适用的视图、文档、播放与交互代际校验；所有状态在主线程串行归并，一帧使用同一份状态与行快照。

文档安装、清空、adapter 替换及自定义 TTML 重装更新文档代际，即使宿主包装对象引用未变化。Seek 更新播放代际，清理旧词流和过渡；集合缩小不能作为 Seek 证据。

末句进入有效高亮后记录当前焦点；后续真实高亮继续更新它，显示集合正常清空后转为独立 `Ending` 焦点，保留原曲线与清晰锚点的边缘保护。暂停打开或重建时，可用完整文档的最大 `getEnd()` 与当前播放位置确认结束，再使用末句焦点。读取失败不当作正常结束，文档变化和 Seek 清除旧记录；手动浏览与 1 秒恢复规则继续生效。

动画按逻辑行保存。同一歌词再次出现可衔接原进度；View 换绑时撤销旧租约，按新行状态初始化，不继承旧半径。首次取得 adapter、文档、binding 或行布局就安排首帧，包括暂停时打开歌词；初始化等待仅在后续宿主事件重读，不增加固定延迟轮询。

观察原生触摸与滚动方法，不替换 `OnTouchListener`，不消费事件。浏览期间高亮更新只更新状态；恢复任务携带会话和交互代际，再次触摸、继续滚动、换歌或销毁均使旧任务失效。

关闭视图取消帧回调、恢复任务和监听，释放仍拥有的效果。旧会话回调不得操作新视图。

#### 7.1.5 渲染、所有权与安装

焦点半径和边缘保护权重分别按 300ms 动画计算：

```text
最终半径 = 量化并限制到 0–32(
    max(动画中的焦点半径, 当前边缘半径 × 动画中的边缘权重)
)
```

边缘使用经过行 View 变换、位移与列表滚动的矩形，视口扣除 padding。几何更新不重启焦点动画。每个会话保留一个帧驱动，合并脏状态与动画推进；动画结束且状态、几何不变时停止请求连续帧。`DECAL` 效果最多缓存 32 个，量化半径及宿主效果版本不变时不重复写入。

存在模块模糊时，由渲染会话持有列表裁剪租约：关闭 RecyclerView 的逐子项边界裁剪，让模糊自然扩散，避免短小的 Credits 行出现矩形硬边；列表自身仍按当前视口与宿主显式裁剪范围的交集裁剪，原有 padding 和外层渐隐保留。尺寸或宿主裁剪范围变化时更新，不改变行高、间距、字体、透明度或原半径曲线；半径全部归零、行释放、列表替换或会话关闭时归还仍拥有的裁剪属性。实现只使用 SDK 属性，由各精确版本共用，不增加混淆成员契约。

Android 12 起在歌词视图创建前观察 SDK `View` 构造和 `setRenderEffect` 写入，记录最新已知宿主效果；模块写入带重入标记并与宿主效果合成。释放只撤销仍由模块拥有的最后写入，恢复已知的最新宿主效果。初始效果未知的既有 View 不强行接管，不使用效果 getter、隐藏 API 或 `alpha = 1f` 清理。

安装顺序固定为：精确版本资格与 Android 门禁 → 解析全部必需契约 → PREPARING scope 注册 Hook 和清理 → 全部成功后 activate → 返回能力状态。必需解析或注册失败关闭整组，报告降级并保留宿主原生行为。

#### 7.1.6 诊断

沿用 `AMPP-LYRIC-PROBE` 入口，DEBUG 启用后才输出详细事件。每会话最多 128 条，ID/行观察最多 32 项，重复摘要与失败原因合并限量。记录构建、来源、各代际、行映射、保护集合、焦点、模式、目标/实际焦点半径及动画时间，不记录歌词文本。

逐项核对及人工场景见 [清单：歌词模糊](host-adaptation-checklist.md#lyrics-blur-checks)。

<a id="cjk-animation"></a>

### 7.2 CJK 辉光与末词扫光适配

#### 7.2.1 功能范围与开关

现有 `cjkKaraokeAnimationEnabled` 同时控制 CJK 辉光和末词扫光补偿，不增加配置键或独立开关。修改设置后按原安装方式重启宿主生效；关闭时不安装这两部分 hook。

辉光沿用单个、未合并 CJK 词的原生触发门槛、前景绑定、回落和结束清理。末词补偿依据原生词序列识别尾词，不额外要求该尾词触发长尾辉光。

末词补偿只调整逐字内部渐变终点及其配套的延迟补亮动画，不修改原生逐字时间、词分组、整行切换、滚动或位移。本实现不包含词动画防中断、重新绑定保护或换行遮罩修正；跨行遮罩修复是独立能力，见 [7.3 跨行歌词扫光](#lyric-wrap-sweep)，不受本开关、本契约或本注册作用域控制。

#### 7.2.2 代码与安装链

| 层 | 入口 | 责任 |
|---|---|---|
| `core` | [CjkTerminalGradientPolicy.kt](../core/src/main/kotlin/dev/amenhancer/module/hook/CjkTerminalGradientPolicy.kt) | 羽化终点余量规则，不依赖 Android 或宿主成员 |
| `host-applemusic` | [AppleMusicCjkTerminalGradientTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkTerminalGradientTarget.kt) | `TerminalGradientContract`、末词识别、原生扫光 hook 和失败释放 |
| `host-applemusic` | [AppleMusicCjkKaraokeAnimationTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt) | 辉光安装成功后安装末词补偿，合并能力状态 |
| `host-applemusic` | [AppleMusicHostFactory.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) | 将当前精确 profile 和宿主 ClassLoader 注入补偿能力 |
| `app` | [CjkKaraokeAnimationFeature.kt](../app/src/main/java/dev/amenhancer/module/hook/CjkKaraokeAnimationFeature.kt) | 读取现有开关，决定是否安装整个 CJK 能力 |

末词契约在安装前通过 `FragmentProfileMembers` 解析完整成员清单。两个新 hook 使用同一 `HookRegistrationScope`，全部注册完成后才激活；失败关闭 scope，部分注册的回调保持失活。

#### 7.2.3 末词补偿规则

渐变的完全高亮边缘落后于扫光前缘一个羽化宽度。终点刚好停在字形右端时，羽化尾部尚未通过末字，末字会留下未完全高亮的区域。

对于合法的 CJK 尾词扫光，终点余量改为 `max(original, feather)`。仅接受有限数值及 `0 < feather <= 1`；原生余量已经更大时保留它，无效几何保持原生结果。

末词选择读取当前 reader 对应行的完整原文和原生发音向量，分别选出合法 `end` 最大的词 ID；相同 `end` 取向量中后出现的词。向量大小必须在 `2..4096`，词时间必须满足 `begin >= 0` 且 `end > begin`。两路 ID 独立读取，结果按 reader 弱引用与行 ID 缓存。

补偿只作用于当前 `d0` 调用中的对应 CJK 词，保留第八个参数（零基索引 7）为 `true` 的原生分支和 Latin 文本。`c0` 的词对象必须与当前扫光词一致；线程作用域在嵌套调用和异常退出后恢复。

有效几何补偿发生后，从 `d0` 的动画返回列表移除标签为 `KARAOKE_SPLIT_ON_CHARS_LANGUAGES_RUSH_GRADIENT_TAG` 的延迟动画，避免已完成的遮罩被随后补亮覆盖。其他动画及原生逐字时长保留；未发生有效补偿时保留原动画列表。

#### 7.2.4 精确版本契约

目前仅以下档案声明 `cjk.terminalGradient`：

| 宿主 | profile | 契约 |
|---|---|---|
| `7.0.0-beta / 1606` | [7.0.0-beta-1606.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1606.json) | `contractVersion = 1` |
| `7.0.0-beta / 1609` | [7.0.0-beta-1609.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json) | `contractVersion = 1` |
| `7.0.0-beta / 1611` | [7.0.0-beta-1611.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json) | `contractVersion = 1` |

缺少本契约的已登记版本保留原有辉光，安装信息明确报告末词补偿不支持。已声明契约但成员解析或注册失败时，CJK 能力报告降级，已安装的辉光继续运行。未知版本不借用这两个档案。

以下简写仅用于阅读：`A` 为 `com.apple.android.music.player.A`，`Word` 为 `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel$e`，`Flex` 为 `com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout`，`Mask` 为 `Flex$a`。新版本必须核对完整 owner、参数、返回值和静态属性。

| 角色 | 1606 / 1609 成员或关系 |
|---|---|
| `build` | 实例 `A.d0(Integer, Mask[], Word, List, Flex, boolean, boolean, boolean, int): ArrayList` |
| `margin` | 静态 `A.c0(float, Flex, Mask, Word, ViewDataBinding, boolean): float` |
| `reader` | 实例字段 `A.p: com.apple.android.music.ttml.j` |
| `rowId` / `wordId` / `text` | `Word.a: int` / `Word.b: int` / `Word.c: String` |
| `readerLine` / `lineGet` | reader 的 `a(int)` 返回 `LyricsLine$LyricsLinePtr`，其 `get()` 返回原生行 |
| `lineWords` / `linePronunciationWords` | 原生行的 `getWords()` / `getPronunciationWords()` 返回 `LyricsWordVector` |
| `vectorSize` / `vectorGet` / `wordGet` | `size(): long`、`get(long)` 返回词指针、指针 `get()` 返回原生词 |
| `wordBegin` / `wordEnd` / `nativeWordId` | `LyricsTiming.getBegin()/getEnd(): int`、原生词 `getWordId(): int` |
| `animationTag` | `N7.c.getTag(): Object`；接口方法以 `invocationOnly` 解析 |

`TerminalGradientContract` 固定要求上述 13 个方法角色和 4 个字段角色，JSON 漏写任何角色均不能通过安装前解析。辉光使用的 `cjk.foregroundTextField` 和 `bindingOwners` 继续独立核对，不由末词契约代替。

#### 7.2.5 后续版本适配

1. 核对精确宿主身份和原生扫光调用链，确认 `build`、`margin` 仍负责相同的几何计算；不能只凭名字或参数数量复制档案。
2. 更新 `cjk.terminalGradient` 的全部描述符，确认 reader 按行取词、词 ID 和词时间的含义，以及原文/发音的独立 ID。
3. 核对第八个参数的分支语义、羽化宽度单位、延迟动画标签和标签接口。若参数语义变化，连同 Kotlin 分支规则一起适配。
4. 确认文档替换、词序列或发音更新时 reader 的替换方式。若新宿主复用同一 reader 并改写词数据，需要增加相应缓存失效入口。
5. 核对完整必需角色清单与安装状态；使用离线接口工具时确认它显式覆盖本契约，其他区域的检查结果不能代替本项结果。
6. 由使用者手动验收，并按实际宿主和场景记录结果。仅编译成功或静态成员匹配不等于真机动画通过。

末词羽化规则、原生尾词选择和延迟补亮处理源自 Tcrrry-Lyrics-Plugin 1.0.6 的 `NativeTerminalGradientFix.kt`（GPL-3.0）。AM++ 增加精确成员契约、原子注册及现有 CJK 开关联动，详见 [第三方来源说明](../THIRD_PARTY_NOTICES.md)。

逐项核对及人工场景见 [清单：CJK 辉光与末词扫光](host-adaptation-checklist.md#cjk-animation-checks)。

<a id="lyric-wrap-sweep"></a>

### 7.3 跨行歌词扫光适配（独立能力）

宿主原生逐字高亮按 TTML 词处理；词跨多个视觉行时几何按行计算，后续行容易落后或残留暗边。本能力在保留宿主原生 `ValueAnimator`/`AnimatorSet` 与歌词身份的前提下，只修正跨行词的遮罩几何与进度。

它是独立能力：使用自己的能力集合、契约区域、注册作用域与安装状态，不读取 `cjkKaraokeAnimationEnabled`，不依赖 `cjk.terminalGradient` 的安装结果。两者会接入同一个原生 `d0`，但契约和作用域独立：单行尾词保留 7.2 补偿；跨行接管成功后由本节更新各行遮罩及片段羽化，原生单行更新被抑制，避免叠加写入。能力键为 `ModuleConstants.FEATURE_LYRIC_WRAP_SWEEP`（`lyric_wrap_sweep`），由 `LyricWrapSweepFeature` 通过能力接口安装，没有独立设置项。

模块启用后，仅当当前精确 profile 在顶层声明 `lyricWrapSweep` 且 `contractVersion = 1` 时默认安装，无需手动开启；未声明的已登记版本明确报告不支持并保留原生渲染。当前声明本契约的版本为 `7.0.0-beta / 1609` 与 `7.0.0-beta / 1611`。

#### 7.3.1 代码与安装链

| 层 | 入口 | 责任 |
|---|---|---|
| `core` | [LyricWrapSweepPolicy.kt](../core/src/main/kotlin/dev/amenhancer/module/hook/LyricWrapSweepPolicy.kt) | 跨行片段计划、局部进度、边缘终点、词进度、渐变位置与跨 span 交接边界；不依赖 Android 或宿主成员 |
| `host-api` | [TargetAdaptation.kt](../host-api/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt) | `LyricWrapSweepTarget` 能力接口与默认不支持实现 |
| `host-applemusic` 契约 | [LyricWrapSweepContract.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepContract.kt) | 解析 `lyricWrapSweep` 成员、校验类型关系、读取媒体浏览器位置并识别延迟补亮动画 |
| `host-applemusic` 几何 | [LyricWrapSweepGeometry.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepGeometry.kt) | 视觉行识别、跨行判定、弱引用几何校验与多遮罩写入 |
| `host-applemusic` 目标 | [AppleMusicLyricWrapSweepTarget.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicLyricWrapSweepTarget.kt) | Hook 注册、接管与退役、静态帧缓存、布局失效与失败释放 |
| `host-applemusic` 装配 | [AppleMusicHostFactory.kt](../host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt) | 注入当前精确 profile 与宿主 ClassLoader |
| `app` | [LyricWrapSweepFeature.kt](../app/src/main/java/dev/amenhancer/module/hook/LyricWrapSweepFeature.kt) | 通过能力接口安装并上报能力状态 |
| `hook-runtime` | [HookRegistrationScope](../hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt) | PREPARING、激活与关闭 |

契约在安装前由 `FragmentProfileMembers` 解析完整必需角色；全部注册成功后才 activate。必需解析或注册失败时关闭 scope、报告降级，并保持宿主原生渲染。

#### 7.3.2 接管规则与时间轴

模块不新建动画器或全局计时器。仅在动画器已注册、其更新监听器的遮罩身份与注册来源遮罩一致、且 RTL 方向与注册方向一致时，才抑制原生单行写入并改为多遮罩写入；其余情况保持原生回调。同一 flexbox 与同一 bank 只保留最新注册的接管：注册新词时，先前同 flexbox 同 bank 的接管立即退役。

进度使用同一毫秒时间轴：词起止来自宿主词对象的 `begin`/`duration`，播放位置来自原生媒体浏览器 `getCurrentPosition`。初始与每帧进度都读取 `(position - begin) / duration` 的限幅值；同一接管内只前进，原生完成 tick 完成整词，向后 Seek 后由新接管重新计算较低进度。仅在几何已就绪、词已开始且尚未结束（`position >= begin` 且初始进度 < 1）时，把动画时长最多缩至实际剩余段；延迟布局不先改时长。不把 JNI 事件 duration 或原生 listener 的行内横坐标误当整词进度。暂停与恢复作用于该词原生 `AnimatorSet`，保留其子动画归属。

进度按实际视觉行字形宽度累计：每个视觉行按其在 flexbox 中的归一化左右边界构成片段，局部进度与边缘终点由累计宽度决定，完全高亮的边缘在本行完成时到达片段末端。多行结束时随行尾字形宽度的羽化已包含在该终点计算内，不需要额外补亮动画。

#### 7.3.3 遮罩、静态恢复与生命周期

| 时机 | 处理 |
|---|---|
| 宿主 `build` 之后 | 仅当词实际跨多个视觉行时注册接管；保留原生扫光及延迟单行补亮动画的调度。主扫光每次先释放当前接管标记，成功绘制后才重新持有；`rushUpdate` 仅在仍持有且上次绘制的几何仍有效时抑制单行补亮写入，失败时保留原生回调 |
| 跨行词完成绘制后 / `maskUpdate` 写入 | 只有跨行几何实际完成绘制且颜色为方向一致的四 stop 渐变时，才为其遮罩记录已亮边界。后继原生扫光、补亮与静态写入只在原几何身份仍有效时约束两个 stop：LTR 不后退，RTL 不前移；写入成功后才更新记录。颜色、`mask.i`、原生动画时间与调度保留 |
| 宿主 `staticMask`（`W`）之前 | 词为空、未开始、媒体位置不可用或前缀调色板为原生暗色时，先清除对应 flexbox/bank 的交接保护，再让原版绘制；不能让上一段亮色边界覆盖未来行或暗色状态 |
| 宿主 `staticMask`（`W`）之后 | 先清除同 flexbox 同 bank 的旧静态帧；词为空、未开始、前缀调色板为原生暗色或形状不支持时保留原生结果。其余已开始的跨行词保存静态帧；同 flexbox 同 bank 有活动接管时，按该接管的最新进度、颜色与羽化重绘；无活动接管时按当前播放位置重建，羽化按当前宽度重算 |
| 宿主 `fadeUpdate`（`A$o.onAnimationUpdate`）之前 | 通过弱引用记录的遮罩与 flexbox/bank 对应关系核对当前遮罩身份，清除该 bank 静态帧和交接保护并退役旧扫光与补亮写入；原版渐暗回调继续执行。过期遮罩不撤销新绑定的接管 |
| 宿主 `rebind`（`i0`）前后 | 前置核对 holder 四路词 Map 中的对象身份，退役已不属于当前行的接管/静态帧/交接保护并使几何失效；后置重绘保留的静态帧 |
| 布局变化 | 对应 flexbox 的布局变化清除交接保护，使缓存几何失效并按当前状态重绘 |
| `bind` / `recycle` 之前 | 退役绑定根所属的接管与静态帧，清除其交接保护 |
| `document` 之前 | 退役该 adapter 的接管与静态帧，清除其交接保护 |
| `clearMasks` 之前 | 退役该 flexbox 的接管并清除其静态帧及交接保护 |
| `seek` 之前 | 退役该媒体浏览器的接管与静态帧，清除交接保护，取消该词原生 `wordAnimator` 释放 `word.o` 去重门禁，使新位置可重新建立动画 |
| `pause` / `pauseRequested` / `resume` 之后 | 同步所属 adapter 的原生词动画集暂停或恢复，发音子动画缺少独立 owner 时使用其原生扫光动画 |
| 作用域关闭 | 清空接管、静态帧、交接保护与遮罩归属，移除布局监听 |

退役后的回调不得写入新绑定的行或替换后的文档；被退役的动画器不再产生原生单行写入。

`W` 同时服务于已唱词恢复和未来歌词行的初始化，不能把该方法被调用当作词已开始的依据。原生初始化可用该行最后一个词及暗色前缀绘制未唱状态；模块仅在媒体位置不早于词 `begin` 时重建跨行静态帧。未到开始时间时保留原生暗色，不能把限幅后的零进度映射到 span 起点并点亮其前缀。缓存帧在每次重绘前重新检查开始时间；未来词或无效绑定替换旧状态时，应清掉对应 bank 的旧帧，避免重绑定与布局回调再次覆盖原生结果。

词已开始或已结束也不代表该行应保持高亮。静态恢复必须读取 `W` 的前缀调色板（第 4 个参数），并按原版内容比较规则识别前景/背景暗色数组；原生暗色状态不保存亮色静态帧。退场渐暗绕过 `W` 直接写遮罩，必须由精确 `fadeUpdate` 入口撤销缓存及旧动画写入权限，避免后续布局或重绑定恢复亮色。入场渐亮的首帧也可能使用暗色前缀，不能仅凭该前缀退役活动扫光；退场以专用回调为准，亮暗曲线继续由宿主控制。

跨行词最后片段结束后，下一 span 可能只占一个视觉行并回到原生扫光。跨行片段把完全亮色 stop 推至自身终点，但原生 `mask.i` 仍保存限于该片段的前沿；原生后继按前沿减羽化宽度绘制，可能使前一段尾部重新进入渐变。交接保护在已完成的跨行遮罩上记住两个实际绘制 stop，并以几何快照校验宽度、文本/Layout 和遮罩身份，只让后续兼容方向的四 stop 渐变继续前进。后继仍使用原版动画；不通过抬高 `mask.i` 消耗短 span 的扫光余量，也不全局关闭羽化。纯暗色写入释放该遮罩保护，其他不兼容颜色/位置形状保持原生。

#### 7.3.4 几何语义与已知边界

遮罩的键是视觉 Flexbox 行的首个 child 索引（精确档案的 `maskFirst`，即 `Flex$a.c`），`maskLast`（`Flex$a.d`）界定该行 child 范围；`topMasks`/`bottomMasks`（`Flex` 的 `T`/`U` 字段）按该键索引。它不是 TTML `lineId`、歌词序号或 adapter position。绑定到同一遮罩行的多个视觉片段按字形宽度并集累计。

同一 flexbox 内，`TOP` / `BOTTOM` 是原文与发音两个垂直遮罩 bank（不是前景与背景）：`staticMask` 的位置枚举决定使用哪个 bank，`build` 一侧由来源遮罩落在哪个 bank 判定。前景与背景是两个独立 flexbox（当前版本为原生 `p9.Y` 与 `p9.X`），二者不共享同一组 bank。模块只接管该词所在的 flexbox 与其 bank，另一 bank 及另一 flexbox 保持宿主原生。

以下情况不接管多行扫光并保留宿主原生动画（fail-open）：单行词、片段数少于 2、无法构造单行文本片段（文本宽度、行数或布局无效）、宽度或边界无效、绑定与遮罩行对应不唯一。单行后继只有共享已完成跨行词的有效遮罩时才受交接边界保护；其他单行写入透传。混合方向复合形状（同一视觉行在文本顺序中被重复访问）无法用单一遮罩表示，同样保持原生。几何在宽度、布局或绑定身份变化后失效并重绘。

跨行计划生成的两个羽化 stop 与写回 `mask.i` 的边缘值均钳制在该片段边界内，完成当前词不会提前点亮下一词。交接层只保留已经实际写入的后继边界，防止旧静态刷新拉回已经前进的扫光；不凭歌词长度预测未来边缘。文本内容与原生词文本、TextView 内容及 Layout 身份均参与复用校验。一个 TextView 自身包含多行的复合形状当前保留原生，不能作为本能力已修复的场景。

#### 7.3.5 精确版本契约

目前仅以下档案声明顶层 `lyricWrapSweep`：

| 宿主 | profile | 契约 |
|---|---|---|
| `7.0.0-beta / 1609` | [7.0.0-beta-1609.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json) | `contractVersion = 1` |
| `7.0.0-beta / 1611` | [7.0.0-beta-1611.json](../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1611.json) | `contractVersion = 1` |

缺少本契约的已登记版本报告不支持并保留原生渲染；已声明契约但成员解析或注册失败时报告降级，同样保留原生行为。未知版本不借用 1609 档案。

以下简写仅用于阅读：`A` 为 `com.apple.android.music.player.A`，`Word` 为 `com.apple.android.music.player.viewmodel.PlayerLyricsViewModel$e`，`Flex` 为 `com.apple.android.music.common.views.FullWidthAlphaGradientFlexboxLayout`，`Mask` 为 `Flex$a`，`Anim` 为 `com.apple.android.music.player.t`，`Holder` 为 `com.apple.android.music.player.f1$b`，`Callback` 为 `com.apple.android.music.player.fragment.N`，`Browser` 为 `J4.B`。新版本必须核对完整 owner、参数、返回值和静态属性。

| 方法角色 | 1609 成员 |
|---|---|
| `build` | 实例 `A.d0(Integer, Mask[], Word, List, Flex, boolean, boolean, boolean, int): ArrayList` |
| `update` | 实例 `Anim.onAnimationUpdate(ValueAnimator): void` |
| `rushUpdate` | 实例 `com.apple.android.music.player.u.onAnimationUpdate(ValueAnimator): void` |
| `fadeUpdate` | 实例 `A$o.onAnimationUpdate(ValueAnimator): void` |
| `staticMask` | 实例 `A.W(Word, List, Flex, int[], int[], boolean, Flex$b): void` |
| `rebind` | 实例 `A.i0(A$a, int): void` |
| `bind` | 实例 `A.k(RecyclerView$D, int): void` |
| `recycle` | 实例 `A.r(RecyclerView$D): void` |
| `document` | 实例 `A.R(SongInfo$SongInfoPtr): void` |
| `clearMasks` | 实例 `Flex.v(): void` |
| `textView` | 静态 `A.v0(androidx.databinding.ViewDataBinding): CustomTextView` |
| `maskUpdate` | 实例 `Mask.b(int[], float[], Float): void` |
| `animationTag` | `N7.c.getTag(): Object`；以 `invocationOnly` 解析 |
| `mediaBrowser` | 实例 `ia.a$c.getMediaBrowser(): J4.w` |
| `mediaPosition` | 实例 `Browser.getCurrentPosition(): long` |
| `seek` | 实例 `Browser.seekTo(int, long): void` |
| `pause` / `pauseRequested` / `resume` | 实例 `A.I(): void` / `A.J(): void` / `A.K(): void` |

| 字段角色 | 1609 成员 |
|---|---|
| `topMasks` / `bottomMasks` | `Flex.T` / `Flex.U`：`SparseArray` |
| `maskFirst` / `maskLast` | `Mask.c` / `Mask.d`：`int` |
| `wordBegin` / `wordDuration` | `Word.d` / `Word.e`：`int` |
| `wordText` / `wordAnimator` | `Word.c`：`String` / `Word.o`：`Animator` |
| `words` / `backgroundWords` / `pronunciationWords` / `pronunciationBackgroundWords` | `A$a.G` / `H` / `I` / `J`：`ArrayMap` |
| `bindingRoot` | `androidx.databinding.ViewDataBinding.d`：`View` |
| `holderBinding` | `Holder.u`：`ViewDataBinding` |
| `adapterCallback` / `callbackFragment` | `A.y`：`fragment.N` / `Callback.a`：`PlayerLyricsViewFragment` |
| `featherPixels` | `A.b0`：`float` |
| `listenerMask` / `listenerColors` / `listenerFeather` / `listenerRtl` | `Anim.f`：`Flex$a` / `Anim.g`：`int[]` / `Anim.e`：`float` / `Anim.a`：`boolean` |
| `foregroundInactiveColors` / `backgroundInactiveColors` | `A.P` / `A.Y`：`int[]`；原版前景/背景暗色调色板 |
| `fadeMask` | `A$o.f`：`Flex$a`；原版退场渐暗写入的遮罩 |

除角色本身外，安装前还校验以下类型关系，缺少任一项即视为契约不成立：

1. `build`、`rebind`、`adapterCallback` 同一 owner；`build` 的第 3 个参数类型与 `wordBegin`/`wordDuration` 的 owner 一致。
2. `clearMasks` 声明在 `ViewGroup` 子类上；`topMasks`/`bottomMasks` 与 `clearMasks` 同 owner。
3. `maskUpdate`、`maskFirst`、`maskLast` 同 owner（`Mask`），`maskUpdate` 的参数严格为 `int[]`、`float[]` 和可空装箱 `Float`；第二个参数是 shader stop，第三个参数更新原版 `mask.i`。
4. `listenerMask` 的 owner 与 `update` 相同，且其类型等于 `maskUpdate` 的 owner。
5. `update` 的唯一参数为 `ValueAnimator`；`bindingRoot` 的类型是 `View` 子类。
6. `callbackFragment` 的 owner 等于 `adapterCallback` 的类型；`mediaBrowser` 的 owner 可由 `callbackFragment` 类型赋值；`mediaPosition` 的 owner 可由 `mediaBrowser` 的返回类型赋值。
7. `rushUpdate` 的唯一参数为 `ValueAnimator`；词文本、词动画与词时间字段同 owner；四路词 Map 声明于 `rebind` 的 holder 参数类型，字段为 Map 子类。
8. `fadeUpdate` 的唯一参数为 `ValueAnimator`；`fadeMask` 与该回调同 owner，字段类型等于 `maskUpdate` 的 owner；两路暗色调色板与 `build` 同 owner，字段类型为 `int[]`。

`LyricWrapSweepContract` 固定要求上述 19 个方法角色和 24 个字段角色，另要求 `contractVersion = 1` 且档案 `productionEnabled`；JSON 漏写任何角色均不能通过安装前解析。`scripts/verify-host-profile.py` 与 `scripts/verify-profile-data.py` 的必需角色清单应与本契约同步维护。

#### 7.3.6 后续版本适配

1. 核对精确宿主身份与原生扫光调用链，确认 `build`、`update`、`staticMask` 仍负责相同的跨行几何和动画更新，不能只凭名字或参数数量复制档案。
2. 更新 `lyricWrapSweep` 的全部描述符，确认视觉行遮罩键仍是首个 child 索引、`maskLast` 仍是该行范围，以及原文/发音两个 bank 的字段。
3. 核对词对象的时间字段单位与 `begin`/`duration` 语义，确认媒体浏览器的播放位置与词时间处于同一毫秒时间轴；不一致时连同进度与时长规则一起适配。
4. 核对延迟补亮动画的标签接口、取值与精确更新回调，确认抑制写入只针对当前仍有效持有遮罩的接管；主扫光失败或上次绘制几何失效时须透传原生补亮。标签语义变化时同步修改 Kotlin 判断。单行尾词仍由 7.2 补偿处理，跨行词使用本节片段羽化，不能叠加两套遮罩写入。
5. 核对 `staticMask` 前缀参数与前景/背景暗色调色板的比较规则，以及退场渐暗绕过静态入口时的实际更新回调和遮罩字段；区分入场暗色首帧与退场状态，保留宿主亮暗曲线。
6. 核对后继 span 的主扫光、补亮和静态恢复是否均经 `maskUpdate` 写入；确认四个 stop 为 `0, s1, s2, 1`，LTR 颜色为高 alpha / 高 alpha / 低 alpha / 低 alpha，RTL 顺序相反，以及 `mask.i` 的原生前沿语义。若形状或语义改变，连同交接保护一起适配，不能直接扩大原生进度。
7. 核对换绑、回收、文档替换、清空遮罩、布局变化、暗色写入和 Seek 的实际入口，确认退役与静态帧/交接保护失效仍然覆盖；新增入口需要同步代码与档案。
8. 由使用者手动验收，并按实际宿主和场景记录结果。仅静态成员匹配或构建成功不等于真机通过。

逐项核对及人工场景见 [清单：跨行歌词扫光](host-adaptation-checklist.md#lyric-wrap-sweep-checks)。各版本的实际验收结果应记录于对应PR或独立验收记录，手册和清单保持通用维护规则与验收标准。

<a id="glass-collapse-rendering"></a>

### 7.4 玻璃底栏收缩渲染契约

底栏随滚动收缩会逐帧改变胶囊的实际宽高，玻璃渲染链在尺寸变化时需要重新处理。收缩渲染路径只对真正支持收缩的宿主启用，并把与尺寸无关的部分复用起来；材质配方、几何、弹簧曲线和输入归属都保持原样。

| 项目 | 规则 |
|---|---|
| 启用边界 | 目录组件 `LiquidBottomTabs` 的 `optimizeCollapseRendering` 默认 `false`；`GlassNavigation` 仅在收到非空收缩进度（`collapseProgress != null`）时置为 `true`。不具备收缩的宿主继续走原始渲染路径 |
| 效果前缀复用 | 与尺寸无关的 `vibrancy()` 与 `blur(radiusPx)` 前缀以物理像素半径为键复用；Dp 半径须按当前密度换算，像素半径变化时重建；`lens` 等依赖尺寸、按压或圆角的效果仍在每次应用效果块时重新求值。缓存类型放在上游命名空间之外，不改动 `com.kyant.backdrop` 的效果源 |
| 材质 modifier 稳定 | 启用路径下，面板、折射源与选择水珠的材质 modifier 用 `remember` 按各自的 backdrop、半径和材质输入复用，不改变效果顺序与绘制内容 |
| 承载层尺寸 | `drawBackdrop` / `drawPlainBackdrop` 的 `minLayerSize` 默认 `IntSize.Zero`，仅表示包含效果 padding 的最小承载像素尺寸。收缩路径按展开几何与最大回弹空间预留；带模糊的材质通过 `VibrantBlur.paddedSize(...)` 包含镜片收减前的 padding，避免实际 padding 变化再次调整承载尺寸。实际内容超过预留范围时仍按实际尺寸绘制，不能裁掉有效内容 |
| 逻辑绘制尺寸 | 扩大的承载层不能改变效果作用域的 `size`、背景坐标、圆角和命中区域；录制回调仍看到当前逻辑尺寸及当前 padding，绘制结束后恢复 DrawContext。输出限制在原有 padding 边界内，不能因预留空间露出多余背景 |
| 裁切合成 | 只在收缩材质调用点选择 `CompositingStrategy.Auto`，让单独的裁切层避免强制离屏合成；材质效果层继续承担模糊与镜片。不改渲染器默认的 `Offscreen`，透明度分组、阴影、高亮和原生内容仍沿用既有边界 |
| mini 接入边界 | 手机会话按当前父容器宽度提供 mini 的回弹上界（原生 slot 的边距不为负）；只在 `hasPresentation`、收缩动画运行且完整播放器展开进度为零时预留承载层。打开播放器的材质变形回到原有路径，原生内容布局与事件不接管。未启用收缩、旧构建和平板不进入新增路径 |
| 稳定端点 | 大承载层仅在过渡期间预留，完全展开／收缩后最小尺寸回到零，以当前实际尺寸录制；分离搜索和 mini 的预留门禁使用派生布尔，避免为门禁在每帧重组。`VibrantBlur.paddedSize(IntSize.Zero)` 保持零值，不为关闭的预留添加 padding |
| 分段采样 | 系统 Trace 启用时，`AM++/collapse/progress`（进度乘10000）与 `running` 标记动画，`source/record`、`effects/update`、`backdrop/record`、`backdrop/createLayer` 分别标记背景源、效果更新、效果层录制与层创建；导航和 mini 的 `measure`、`layout`、`recordView` 区分 View 阶段。名称均带 `AM++/` 前缀，CPU 区段必须成对结束，不采样时不写标记；分段耗时不代表 GPU 执行时间 |
| 尺寸与资源恢复 | 预留值由当前窗口宽度、密度、展开几何和材质输入计算，不保留历史最大尺寸；旋转、分屏、密度及参数变化后重新计算，节点移除时按现有生命周期释放层和效果 |
| 隐藏节点生命周期 | 选择水珠不可见时，折射源仍保持附着并保留已有层与资源，但不测量、放置其子级，也不进入内部背景录制；分离搜索控件同样保留材质与绘制节点并跳过子级测量与放置。挂起与恢复之间保留上述节点的资源，可见性门禁翻转后在下一帧绘制前恢复测量与放置 |
| 进度读取位置 | 收缩路径的胶囊、标签行、选择水珠与 compact 面共用按当前原始进度派生的不可变几何快照；连续尺寸在测量中、位移与透明度在 `graphicsLayer` 中读取。组合只读取显隐、交互和承载预留门禁；材质 modifier 独立复用，分离搜索沿用同样的阶段读取原则 |
| 动态尺寸语义 | `GlassCollapseModifiers` 在启用路径按当前像素几何测量，每个轴分别调用并保留原 modifier 顺序和中间约束收敛，不能随意合并宽高 coordinator。背板与折射源保留 required 尺寸，其余尺寸按传入约束收敛。未启用路径仍调用原有 width/height/requiredWidth/requiredHeight 和 alpha；透明度的裁切规则与原有 alpha 一致 |
| 收缩胶囊轮廓 | `GlassCollapseShapes` 按运行时收缩资格为手机收缩表面选择 Circular 标准圆角，不依赖独立诊断构建。背板、折射源、选择水珠、标签裁切、compact、搜索和 mini 保持同一选择；其他调用方默认仍为连续曲线，完整播放器沿原有圆角过渡。两种曲线存在外观差异，新增宿主须人工确认，不能把某设备的性能收益视为通用保证 |
| 交互几何时效 | 拖拽命中、索引换算、按压高亮与位移补偿读取当前几何提供者；配置或行宽变化时替换提供者，不能沿用首次组合或上次显隐门禁时的宽度。标签单元格及水珠沿 clamped resting width 布局，仅背板继承回弹 |
| 共同边界 | 不改变收缩弹簧及其参数、共享控制几何与命中（`GlassBarCollapseLayout.controls(...)`）、原生列表位移、peek、内容留白、mini 的原生输入，也不改变通用渲染器（`DrawBackdropModifier`、效果作用域及效果源）的默认行为 |

适配新版本或新增收缩宿主时沿用这些边界：通过宿主资格检查后提供非空进度；宿主只提供当前窗口的几何上界，材质前缀和 padding 预留由渲染层计算。不要在宿主适配层复制效果缓存，不要将文字／图标整体缩放或冻结背景作为承载层复用，也不要改动上游目录组件的默认值。修改已声明的上游本地补丁时同步维护来源说明及本地源码 pin。逐项核对与人工场景见 [清单：底栏收缩渲染](host-adaptation-checklist.md#glass-collapse-rendering-checks)。

定位性能问题时使用同一页面、刷新率及相近滑动方式，按显式动画标记区分过渡与稳定状态。检查 trace 缓冲区覆盖情况，元数据与帧时间线可放入独立 buffer；只分析实际保留的时间范围。录制边界、CPU 父子区段及并行线程分别处理，不能直接相加，也不能把整个录制窗口的掉帧率称为动画掉帧率。细分标记没有覆盖的成本应保留为未归因项。

### 7.5 其他功能与共同规则

| 功能 | 必须保持的业务与接入规则 |
|---|---|
| 当前歌曲身份 | 元数据转换器、当前 item 和歌曲 ID 对应实际播放对象；异步结果再次核对当前歌曲 |
| 自定义歌词 | 原生 TTML 解析、有效指针检查、普通/无官方歌词歌曲、多 ID、禁用优先级、结果提交与文档一致性、重复提交抑制；显示由原生观察者完成 |
| 自动补全 | 既有触发条件、来源顺序、手动/禁用优先级、代次控制、重试和缓存策略；切歌或 ViewModel 会话结束后旧结果不能提交 |
| 字体 | TTF/OTF、后台加载、重试、行词一致、视图重建和原字体恢复；区分 Hook 安装与字体就绪 |
| 名称修正 | 原地区/cn/jp 模式、原名与标识证据、目录查询、离线及持久缓存、显示优先级；各表面单独安装与报告 |
| 玻璃 | Android API、精确资格、导航/mini 区域、采样、触摸、展开过渡和属性恢复；材质准备完成后接管原生呈现 |
| 蜂窝入口 | 精确能力资格、原生登录/类别逻辑、availability 接入和默认开关；不使无资格构建自动支持 |
| DPI 与数据 | 仅宿主范围、冷启动和配置重建、失败失活；配置键、文件 ID、目录、SQLite、缓存 namespace 和 ZIP 格式保全 |
| 视频与背景 | 页面族自己的原生视频策略、尺寸/比例、暂停缩放、表面切换、shader/背景清理和后台恢复 |

名称修正至少覆盖播放器、队列、历史、操作表、最近搜索、资料库、专辑、歌单、艺术家和主页。除目标方法外，还需核对模型绑定、Epoxy holder、LiveData、辅助 getter 和实际封面加载路径。一个表面成功不能代表整项能力成功。

目录查询需核对实际接口、请求结构和后续返回路径；Compose/LiveData 需核对当前类型别名及真实参数类型。名称相同或反射调用成功不足以确认它们仍服务于同一表面。

<a id="lyrics-result-submission"></a>

### 7.6 歌词结果提交与异步注入适配

自定义歌词与自动补全在原生加载之外提供候选文档。宿主原生加载完成后把结果写入歌词 ViewModel，Fragment 的观察者再调用原生安装入口。本节描述这条共用的结果提交通道，它取代模块直接重入 Fragment 安装方法，查询、解析、来源顺序和缓存策略保持不变。精确版本、成员与资格仍以当前 profile 为准，逐项核对见[清单：歌词结果提交](host-adaptation-checklist.md#lyrics-result-submission-checks)。

#### 7.6.1 提交层与安装归属

| 项目 | 规则 |
|---|---|
| 提交目标 | 当前播放器歌词 ViewModel 的结果容器；由 `LyricsResultSubmissionBridge.create` 按当前精确 profile 解析并持有 |
| 显示安装 | 仍由原生观察者和原生安装入口完成，模块不重入安装方法 |
| 附加接入 | 在原生安装入口、歌词 Fragment 恢复以及原生 item 更新回调后附加当前 ViewModel，读取结果容器已有值并恢复请求身份 |
| Fragment item | 不主动改写 Fragment 的当前 item，由宿主在原生 item 更新回调中自行更新；模块仅在回调返回后重新附加 |
| item 更新契约 | 复用歌词 Fragment 已有的精确 itemUpdate 条目，随结果通道同一精确 profile 解析，不新增 profile 成员 |
| 安装观察 | 安装入口 before 观察用于附加；after 观察在调用无异常时确认该次安装已返回，不代表界面已完整渲染 |
| 不改写项 | 不改写安装参数、不持有 ready-late 台账，也不启用旧的重应用或协调器活动路径 |
| 到达时机 | 后台查询或解析完成后，在当前身份与安装上下文匹配时立即提交；身份不一致时等待原生 item 更新后恢复 |
| 结果选择 | 有可用手动条目时使用手动结果；歌曲被禁用时不注入；自动补全只在缺少手动结果且资格允许时作为候选 |
| 资格判断 | 沿用原生歌词可用性谓词，不因新增通道放宽或收紧资格 |
| 线程 | 只在主线程提交，与原生自身的结果写入同一线程 |

原生安装入口的 before 观察只用于附加当前 ViewModel 并恢复请求身份，after 观察在调用无异常时确认该次安装已返回，用来结束待安装状态；这个确认只说明安装调用本身返回，不代表歌词视图已完整渲染，功能仍以人工场景核对。原生 item 更新回调是重附加的触发点：宿主在回调中检查并更新自己的 item，模块只在回调返回后重新附加，不把 item 改写成模块期望的值。

以提交取代直接调用 Fragment 安装方法后，模块不再按 Fragment 逐个寻找等待中的视图并重入安装。视图重建、观察者随生命周期重订阅都由宿主完成，正常路径与重建路径共用同一份结果。

#### 7.6.2 结果容器与派生索引一致

宿主发布歌词结果时，同时更新结果容器和由该文档派生的时间范围索引。只替换观察者收到的指针会留下内部保存值与索引不一致，后续身份读取和列表刷新仍以旧文档为准。

| 成员 | 一致性要求 |
|---|---|
| 结果容器 | 保存并发布同一份文档；当前歌词身份读取必须得到新文档 |
| 派生时间范围索引 | 与结果容器在同一提交中按新文档重建，不能只更新其一 |
| 索引就绪时机 | 替换文档先按新文档重建派生索引，再写入结果容器并通知观察者；观察者收到新指针前索引已经就绪 |
| 当前歌词身份 | 结果容器被替换后，身份读取返回新文档的 ID |
| 原生迟到返回 | 原生自身加载的迟到结果也经过同一选择与提交规则，按现有来源优先级和原生文档质量决定保留或替换 |

结果容器的写入先保存再通知观察者。调用抛出的异常不能单独判断这次写入是否已经提交：异常既可能发生在写入生效后的通知阶段，也可能出现在写入之前，观察者自身抛出的异常也无法回滚已经保存的值。因此提交失败时不重新 setValue 回滚到先前指针，也不盲目恢复先前的选择，而是读取结果容器实际保存的文档，把选择与派生索引对齐到该文档，避免第二次写入重复触发观察者副作用。

恢复只在原会话仍有效时进行：同一 ViewModel、同一结果容器、同一请求代次都成立才写回对齐后的选择与索引；期间会话已切换（换歌、ViewModel 更换或会话关闭）时直接放弃，不把旧会话的对齐结果写进新会话。读取或重建本身失败时只记录一次同类原因并使索引成功标记失效，保留下次提交重新构建的机会，不宣称已经恢复成功。

派生索引的成功结果只在当前请求的同一存活指针上复用：实际再次重建之前、调用上下文 `setScript` 之前，以及请求或 ViewModel 结束时都会失效。原生普通发布无需重复扫描：模块预建索引后原生紧随的同一指针调用可跳过一次；`postValue` 路径原生先完成索引构建，主线程交付的同一结果可复用该成功结果。其他原生索引调用仍执行，不做全局无限期的同指针去重。构建失败或同一 ViewModel 存在交叠、过期构建时，不保留可复用的成功标记，留给后续重建；观察者异常不等于索引构建失败，应按实际保存文档判断。

#### 7.6.3 身份、代次与去重

| 校验项 | 规则 |
|---|---|
| 播放请求代次 | 提交前核对仍是当前播放请求，陈旧后台结果不提交 |
| 显式请求优先 | 请求身份区分显式捕获与推断：显式 loadLyrics 捕获的请求 ID 为当前权威，不被覆盖；推断身份可被独立佐证修正 |
| 推断身份佐证 | 非显式推断的请求身份可据独立佐证修正：结果身份与附加 Fragment 的当前 item ID 一致且 ViewModel 相同时按该结果恢复，播放身份缓存滞后也保住原生结果。该佐证仅在无法判为已知旧身份时成立：缓存与 Fragment 身份不一致、且 Fragment 身份是最近已发布歌曲（可重新绑定）时，不得用该旧 Fragment 与迟到指针反推覆盖缓存；身份不确定或多次往返时保守等待 |
| 缓存核验范围 | 播放身份缓存只用于判断模块替换是否可行，不因缓存滞后改写已捕获请求或把原生结果重新归属 |
| 缺失或未知请求 | 尚未捕获请求或已捕获的请求被退役时，按已验证的当前播放身份或歌词页面身份恢复绑定；播放缓存缺失且加载入口未命中时，可从 Fragment 当前 item 恢复，避免因保留失效的非零请求而无法恢复；恢复后仍按当前身份校验 |
| 同 ViewModel 附加 | 附加沿用同一 ViewModel 时不覆盖已捕获的非零请求；请求退役与 ViewModel 更换分别由各自的生命周期处理 |
| 播放通知去重 | 播放身份通知按实际 ID 变化处理，同一 ID 的重复通知不清除仍在领先的请求 |
| item 更新退役 | 原生 item 更新把 Fragment 的 item 从旧身份改到新身份、而当前请求仍是旧身份时，只退役旧请求；已经领先的新请求不变 |
| 歌曲身份 | 提交前核对结果所属 Apple Music ID 仍为当前歌曲 |
| 文档身份 | 以解析器给出的文档文本身份标识本次结果，按请求去重；同一请求的等价文档不重复提交 |
| 视图与会话 | 按当前 ViewModel 会话提交，会话结束或播放请求更换后丢弃迟到结果；视图重建由原生观察者恢复最新结果 |
| 绑定恢复 | 加载入口未命中时按已验证的当前播放与歌词页面身份恢复绑定；附加即绑定当前 ViewModel 与结果容器，容器内仍是上一首结果时保留等待 |
| 无身份发布 | 不带文档身份的空值或错误发布不覆盖已确认的当前文档；先按实时指针核对当前文档身份，再判断是否为空 |
| 迟到写入与索引 | 被抑制的迟到写入不提交；其后续索引调用使用已确认的当前文档，不按空值清空索引 |
| 异步交付 | `postValue` 只读观察，不取消原生调用也不逐条转交；同一原生主线程投递任务执行前的多次发布由结果容器合并，只交付最后一个值，中间被覆盖的值不触发模块提交选择或额外结果通知 |
| 空结果交付边界 | 只对无文档身份的空 Pair 以弱引用记录发布代次；标签早于最近真实请求/owner 边界时拒绝该空 Pair，未知绑定恢复不构成真实边界 |
| 恢复不移动边界 | 未知绑定恢复只改变内部代次、不推进真实边界；同一请求内的恢复不误拒空 Pair，也不让原生空态因此永久丢失 |
| 交付时身份判定 | 非空结果在交付时按当前请求与实时身份判定，不使用入队时的播放缓存快照拒绝当前歌曲 |

提交成功后更新文档归属；仅因再次触发而重复解析得到的同一文档，不再提交一次。文档文本身份相同而对象不同的候选按同一文档处理。

附加当前 ViewModel 时不能只凭结果容器已有值判断就绪。原生加载入口未命中、结果早于附加到达或 ViewModel 被复用时，容器里可能仍是上一首歌曲的结果：此时按已验证的当前播放身份恢复请求绑定，播放身份不可用时才使用当前歌词页面身份；附加本身仍绑定当前 ViewModel 与结果容器。实时指针确实属于当前歌曲时才据该值注入；指针与当前歌曲不一致时保持未就绪，也不把旧文档当作待注入结果。已观察到空态后仍须读取实时指针，以恢复可能绕过写入 Hook 的后续有效文档；模块自身已选择的替换指针不得反过来作为原生文档参与质量判断。

原生发布的空值或错误结果可能不携带文档身份，无法区分当前失败与迟到失败。这类发布不得替换已确认的当前文档：先读取结果容器实时指针并与当前歌曲身份核对，存在当前可用文档时抑制该发布，其后续索引调用也必须使用保留的当前文档，不能按空值清空。空信号只有在当前没有可用文档时才进入既有降级路径。判断依据是歌曲身份与实时指针，不从回调到达顺序或代次推断发布来源。

异步发布保留结果容器自身的 pending 槽位和主线程投递：同一投递任务执行前的多次 `postValue` 只交付最后一个值，中间被覆盖的值不触发模块提交选择或额外结果通知。宿主自身的索引调用仍按原生流程执行，并参与既有的一致性处理。模块只对无文档身份的空 Pair 以弱引用记录发布代次，并在真正交付时核验标签是否早于最近真实边界；真实边界由原生加载请求、更换 ViewModel、实际切歌、请求退役、ViewModel 清空或会话关闭推进，单纯的未知绑定恢复不推进边界，因此同一请求内的恢复不会误丢空态。非空结果按交付时的当前请求与实时身份判定，不用入队时的播放缓存快照拒绝当前歌曲。目标容器不再被跟踪或会话关闭时，模块不再接管其写入，宿主容器仍执行原生交付。

#### 7.6.4 安装确认与重发

选择替换文档与确认已经安装是两个独立状态：结果写入完成选择，安装入口 after 观察确认安装调用返回后才结束待安装状态。

| 项目 | 规则 |
|---|---|
| 选择与安装确认 | 结果写入只完成选择；安装入口无异常返回后，仅在附加 Fragment、ViewModel、歌曲 ID 与选中指针均匹配时确认交付 |
| 未安装补发 | 身份对齐后同一指针尚未确认安装时，允许经结果容器补发；每次外部恢复回调最多产生一次补发，自身写入不得再次排队 |
| 安装确认后去重 | 该指针已确认安装后不再因同一指针重复提交，避免 installer-before attach 形成重复写入循环 |
| 待安装状态清理 | 播放请求更换、ViewModel 更换或会话关闭时清除待安装状态，已选择指针与待安装标记一并失效 |
| 身份不符暂缓 | 附加的 Fragment 身份与目标请求或 ViewModel 不一致时暂缓模块替换，待身份对齐后按新身份重新判定 |
| 同指针等价 | 同一文档文本身份的等价结果按同一指针处理，除未确认安装的补发外不重复提交 |
| 待匹配候选 | Fragment 的 item ID 与当前播放身份暂不一致且不是当前 ViewModel 时，以有界弱引用候选记住（Fragment 与初次记录的 ViewModel 各持一个弱引用），不立即采用，也不改写 Fragment item |
| 候选重试 | 缓存或就绪回调在主线程先重读当前身份与选择器，再按当前 ID 匹配候选；仅当 Fragment 的 ViewModel 字段仍是初次记录的同一实例且 View 未销毁时才附加 |
| 活动 owner 优先 | 已有歌词面板 owner 仅在 View 仍附着、其 ViewModel 字段就是当前跟踪实例、且 Fragment ID 匹配当前请求/当前身份时，才阻止同歌曲旧候选替换；无 owner 的 load 或已脱离、销毁的旧面板不阻止新候选 |
| 候选清理与唤醒 | ViewModel 清空、会话关闭、候选被回收或 View 销毁时移除对应候选；当前 ViewModel 清空时一并清除已跟踪的 owner 并唤醒其余候选 |

附加当前 ViewModel 时若换到新的 Fragment 实例且已有选中结果，需要重新进入待安装状态；普通的同实例重复附加不重置确认。身份对齐后重新附加并确认。安装确认只表示匹配的安装调用无异常返回，界面是否完整渲染仍由人工场景核对。

候选只记住暂缓附加的页面，不改变 Fragment 的当前 item。缓存或就绪回调在交付时重新读取当前身份；一次未知绑定恢复引起的内部代次变化不会丢弃这次唤醒，暂缓的 Fragment 即使后续没有再收到生命周期回调也会被重试匹配。已有歌词面板 owner 的优先权来自它当前仍活跃的显示归属，一次请求捕获本身不足以据此判定活动 owner；当前 ViewModel 清空时一并清除已跟踪的 owner，并唤醒其余候选重新匹配。

#### 7.6.5 反射边界与降级

结果写入与发布观察 Hook 应声明在实际实现发布逻辑的 LiveData 基类，不能只 Hook `MutableLiveData` 的短转发方法：ART 内联转发后可能绕过该方法。基类混淆名随精确构建声明，且所有回调先按歌词结果容器实例身份过滤。`resultValue` 用于接管时读取已保存的结果；原生加载已完成、ViewModel 被复用或转发被内联时，也必须能够根据当前 Fragment 的已验证 item ID 恢复提交上下文，不能永久等待下一次 `loadLyrics` 或结果写入。

结果通道只在当前精确 profile 声明契约的构建上安装。

- 结果容器、发布入口、派生索引重建和当前歌词身份读取均按 profile 的完整描述符解析，记录实际声明 owner、类型和调用形式。
- 结果通道的角色清单固定为现有的 11 个方法与 2 个字段；item 更新回调复用歌词 Fragment 已有的精确条目并随同一精确 profile 解析，不向该清单追加角色，profile 未命中时整个通道降级。
- 缺少任一必需角色时该能力报告降级并保留原生歌词；不猜测父类、不按参数个数匹配、不扫描 DEX 发现成员。
- setter 名称不代表返回类型为 void。`SongInfoNative.setScript(String)` 返回 boolean，`pointerScript` 必须声明 boolean；Kotlin 忽略返回值的调用写法不能代替原始 DEX 描述符。完整契约中任一成员不匹配都会阻止该通道安装。
- 模块只对当前歌词 ViewModel 实例提交，不替换或改写 LiveData 的全局类型，不注册全局观察者，翻译与发音等订阅者不受影响。
- `postValue` 观察与 `setValue` 接管声明在同一精确基类；`postValue` 只读观察，不取消原生调用。空 Pair 的发布代次标签以弱引用为键复用现有标识映射，候选验活只使用 Fragment 的公开 `getView`，不新增 profile 成员、宿主私有字段或新类；公开 `getView` 无法解析时该通道整体降级。
- 提交或索引重建异常时 fail-open，保留宿主已经发布的结果，只记录一次同类原因。调用抛出的异常不能单独判断写入是否已经提交：此时读取实际保存的文档对齐选择与索引，不用第二次 setValue 回滚。读取或重建失败时撤销索引复用资格并如实记录，不把该次提交当作恢复成功。

#### 7.6.6 分层与代码位置

| 模块 | 职责 |
|---|---|
| `core` | 候选准备、来源与缓存会话、后台刷新代次；不依赖 Android 或宿主反射 |
| `host-api` | 共用的能力状态、当前歌曲身份与会话接口 |
| `host-applemusic` 契约 / 会话 | `LyricsResultSubmissionBridge` 解析结果通道角色与 item 更新条目、管理选择与安装确认去重、持有当前 ViewModel 并以 `onReplacementPublished` 回调在主线程提交 |
| `app` | 装配能力，复用现有查询、解析与缓存路径 |

#### 7.6.7 后续版本适配

- 结果容器类型、发布方法、派生索引重建方法或身份读取变化时，更新对应精确契约，不沿用旧描述符。
- 原生把结果发布改为其他载体、引入中间层或改变结果类型时，重新分析提交点，不能只改混淆名。
- 安装入口变化仍按其自身契约维护；结果通道不改变原生安装入口的显示语义。
- 结果容器的写入语义（保存与通知的先后、异常是否等于写入失败）或派生索引的构建入口变化时，重新分析提交失败后的对齐方式与索引复用判据，不能假设旧的一次性跳过一次与成功复用仍然成立。
- item 更新回调沿用既有的精确条目，不扩充结果通道角色清单；该条目未命中精确 profile 时结果通道降级。
- 新增依赖同步写入当前 profile、代码维护的必需角色清单和 `scripts/verify-host-profile.py`。
- 结果容器的异步发布语义（`postValue` 是否合并、是否逐条交付）或真实请求/owner 边界的推进时机变化时，重新分析空 Pair 的代次标签与交付判据，不能沿用旧的入队/交付 ID 一致规则。
- 公开 Fragment 生命周期方法（如 `getView`）变化或不可解析时，候选验活按降级处理，不猜测替代入口。

<a id="artist-compose-profile"></a>

### Compose 艺人页名称修正

1607 与 1609 的艺人页由 `profiles2.GenericProfileViewModel` 发布不可变 Success 状态。本接入沿用 HLE 的 `AppleArtistSurfaceHost`、entity/alias、歌曲地区证据、元数据应用和刷新队列；宿主私有对象留在 `host-applemusic`。没有新增艺人查询通道，也不根据艺人名称推断语言或替换 logo。

`ARTIST_SURFACE_CLASSES` 的 `compose_profile_view_model` 和 `compose_profile_success_state` 是表面声明；完整调用信息集中在 `artistComposeProfile`。两个角色或本区域中任意一项已声明，就尝试安装独立的 `hle.artist-compose-profile` 组。缺少另一角色、契约或实际成员时报告降级；完全未声明的旧档案不安装该表面。角色缓存只有在当前档案的全部声明解析成功后才完成。

| 必需契约角色 | 应核对的内容 |
|---|---|
| 方法 `setUiState` | 实际声明 owner、状态参数、void 返回及实例调用；Success 可赋给该参数，不猜测其直接父类 |
| 方法 `headerData` / `shelves` | 以 MediaEntity 为参数；返回类型分别与 Success 的 header/shelves 字段一致 |
| 方法 `entityType` / `entityTitle` / `entityViews` | 实际 MediaEntity 的 String/String/Map getter 完整签名 |
| 方法 `viewEntities` | 当前 views map 值对象的实例 getter，返回 MediaEntity[]；1609 的泛型签名确认值为 `internals.Relationship` |
| 字段 `id` / `title` / `header` / `shelves` / `entity` / `revision` | 六个不同的 Success 实例字段，完整类型分别与构造器和 builder 一致 |
| 构造器 `success` | 当前 Success owner 和完整参数顺序；契约 v1 为 id、title、header、shelves、entity、long revision |
| `artistType` / `topSongsView` | 当前包中的艺人类型与热门歌曲 key；现有档案为 artists / top-songs |

`ArtistComposeProfileContract` 复用现有成员解析器，在注册前解析并校验全部句柄、静态/实例、返回值、字段及类型关系。`REQUIRED` 和 `scripts/verify-host-profile.py` 的独立清单由代码维护；JSON 漏写角色不能绕过预检。`verification` 同步登记附属 getter。views 路径按已声明的值类型调用，不猜测 `getData` 回退。

`ArtistComposeProfileSessions` 管理弱 owner、当前 Success、艺人/歌曲引用和 pending 刷新。每次原生 Success 替换都会关闭旧会话并取消其排队任务；loading/error、非艺人或缺少 ID/属性同样清理。帧队列以会话身份区分工作，执行前确认会话仍属于活动安装组。作用域关闭只清理该组的引用、状态与待执行任务，已从队列取出的旧任务也会因会话失活而跳过。

alias 到达或恢复原名时，按当前实体重建 title/header/shelves 并发布 Success；v1 保留原生 ID 和 revision。后续版本若调整状态布局、构造参数顺序、相等性、发布线程或生命周期，需要重新分析对应 Contract/Runtime/Session，不能只改混淆名。仅名称或类型别名变化时优先修改精确档案。适配覆盖检查从 `index.json` 遍历，不写死版本文件列表。

1607 与 1609 均保留 `runtimeVerified=false`。本次新增 views/getter 契约的包内依据来自本地 1609 DEX；1607 使用同名 MediaAPI 和其原有 `profiles.X` 头部映射，新增 getter 仍需对照 1607 安装包。人工场景见[清单中的 Compose 艺人页检查](host-adaptation-checklist.md#artist-compose-profile-checks)，结果按具体版本单独填写。

旧 Activity Editorial 处理和 Fragment 原生视频策略分别保留。停用的全局目录语言功能及未启用的播放模式 UI 不因新增档案自动获得启用。

## 8. 平板布局与视频尺寸

### 8.1 资格、参考视觉与尺度

双栏使用既有门禁：精确版本、开关、横屏、宽度至少 600dp，以及原生 `useNavigationDrawer`。DPI 覆盖和多窗口会改变逻辑视口，应按实际窗口重新计算。

状态栏隐藏由独立普通设置 `dual_pane_hide_status_bar_enabled` 控制，模型与缺键解码均默认关闭；旧配置没有该键时保留宿主状态栏，不将此前的强制隐藏行为迁移为开启。设置入口位于“平板双栏播放器”下方，随普通设置持久化，重启应用后生效。共享左栏会话初始化时读取一次配置，不能在展开或收起动画每帧解码配置；Legacy 与 Fragment 两类页面族共用同一门控。

仅在该选项开启且双栏播放器完全展开时隐藏窗口状态栏，SONG/QUEUE 切换及收起动画过程中保持隐藏；收起停稳、退出双栏资格、视图分离或会话销毁时恢复进入前的可见性。选项关闭时不接管状态栏。不能在收起第一帧显示状态栏并改变窗口 inset，以免原生缩回 mini 的目标在动画中发生布局变化。Android 30 及以上只对 `WindowInsets.Type.statusBars()` 操作，允许边缘手势临时唤出；较低版本仅持有 `FLAG_FULLSCREEN` 对应位。导航栏、手势区域、decor fitting 与其他窗口标志保持宿主控制，不通过全量重写系统栏 flags 实现全屏。隐藏后使用新的实际视口重新计算几何，不保留旧状态栏高度作额外顶部留白。

左栏层级由真实原生组件构成：metadata、progress、transport、favorite、more、音质与封面保持原生，保留父链、数据绑定、动作绑定、icon selector 与字体样式。统一的 core grid/flex 响应式规则只负责布局：按参考比例摆放这些原生 View，计算位置、间距与容器尺寸，渲染尺寸以 native drawable 矩阵为准；模块不建立自绘左栏替代树。SONG 与 QUEUE 底部共用同一进度、播放及音量布局；QUEUE 顶部信息和列表保留原生。右栏歌词和右下代理保持既有路径。

参考规则来源为 YouLyPlus 的 AMLL `horizontal.module.css`、PrebuiltLyricPlayer、MusicInfo、MenuButton、BouncingSlider 与 VolumeControlSlider。原生已有组件仅应用尺寸、位置、间距与排版比例，图标与文字沿用该构建的 native drawable / icon selector 与字体样式；原生字形轮廓随宿主实现。模块自补音量组件与左下输出按钮使用参考路径及 viewBox，分别连接 Android 系统媒体音量与既有平台输出入口；不以参考资产替换其他原生图标。来源及许可证见 [第三方说明](../THIRD_PARTY_NOTICES.md)。

CSS 视口必须依据参考浏览器的实际缩放建立，不能直接对应 Android dp。当前参考环境为电脑系统显示缩放 225%、浏览器缩放 100%，由 core 的 `REFERENCE_PIXEL_SCALE = 2.25` 统一声明 CSS 到物理像素的倍率。以实际 player_root 宽高除以此倍率得到 W/H，解析 CSS 媒体与容器查询，再将结果乘同一倍率。固定 CSS 长度、em、字号、图标矩阵、音量绘制和交互位移、底部输出按钮与队列裁剪间隔均使用该值；不能各自读取 Android density 或按截图逐项补偏移。

例如 3392×2400 的完整播放器视口对应约 1507.56×1066.67 CSS px，应命中 H > 1000 的 metadata padding 和 H ≤ 1080 的媒体图标分支。不能按 420dpi 得到的约 1292×914dp 选择参考断点。字号采用物理 px 写入，避免系统 fontScale 再缩放一次。窗口尺寸变化时重新解析同一套响应式规则，不固定 1080 高画板或整体缩放旧矩形；同像素视口下参考视觉不随 Android 显示密度改变。

Android dp 仅用于原生交互与平台层：收起横条的最小命中区和叠加层 z 间距仍遵循设备密度，绘制尺寸保持参考 CSS 比例。横向遮挡仅扣除尚未由父布局消化的部分；竖向 inset 用于父坐标映射，不重复改变百分比基准。参考的浏览器全屏未切换样式表；系统栏显隐也不应切换到另一套几何。Android 保留的手势栏不额外占用参考纵向 grid 行。

更换参考环境时须先核对全屏内容区的 CSS 宽高、浏览器 devicePixelRatio、页面缩放和串流是否额外缩放，再统一更新倍率；不能仅凭输出图片分辨率推导。聊天图片压缩不属于浏览器缩放。

| 项目 | 参考规则 |
|---|---|
| 分区 | grid 列为 `.45fr .55fr 0fr`，扣除列间距后解析；内容在左列居中 |
| 内容宽 | `min(60cqh,38cqw)`；H ≤ 1000 时为 `min(55cqh,38cqw)`，H ≤ 512 时为 `min(50cqh,38cqw)`；封面与控制组共宽 |
| 纵向 grid | `minmax(30px,.45fr) auto auto 25% 0fr .3fr`；H ≤ 768 时末行 `.2fr`。保留小于 1 的 fr 总和、auto 行 stretch 与 min-content 的语义，不把剩余空间按 fr 比例强行分完 |
| 横条 | 模块自补，参考 50×8px、圆角 4px、thumb wrapper margin 为 2vh；绘制锚点与命中区独立 |
| 控制组 | 高 25%，`min-height:0`、column flex；顶部 padding 为 `1.75em−8px`，H ≤ 640 时为 0。media 区 `flex:1; min-height:0`，不人为预留 24px 或挤小封面 |
| 间距与字体 | 默认 gap 8px、div em 16px；H ≤ 768 时 gap 2px、div em 12.8px。button 沿用 UA 13⅓px 字号，分别解析 em |
| Metadata | 字号 `max(2vh,1em)`、行高 1.25em，标题 500、艺人 400、透明度 .9/.45、字距 .4px；H ≥ 1000 时 metadata 块上下 padding 为 2vh/1vh |
| 更多 | 原生 more 按钮保留纵向省略号形态，favorite 保留原生星形；位置与尺寸按参考比例摆放。动作连接原生更多菜单 |
| 进度 | 原生 SeekBar 与两端时间标签保留原生绘制；按 policy 布置轨道占位与标签 gap，清理负 margin 避免重叠；标签字体 `max(1.2vh,.8em)`、透明度 .5，上距 4px（H ≤ 768 时为 0） |
| 播放区 | 五个等分分布槽位，按钮宽 `min(18%,100cqh)`；切歌图标按前/后一首路径占比（参考 80/134 与 native 39/40）保持比例；上一首、播放暂停、下一首、favorite 与 more 均保留原生，模式资源走 native drawable |
| 音量 | 模块自补，使用参考扬声器路径、viewBox 与 BouncingSlider 反馈；完整 speaker/track/speaker 组合与进度区同宽、同左边界，不采用源样式的 −12px/−8px margin；状态与操作连接系统 STREAM_MUSIC |
| 底部输出 | 0fr 行按参考辅助按钮的 4em/3em 预留，窗口侧边距按 div em 的 4em/2em 计算；输出图标的可见尺寸为 modeIconSize 的 68%，保持原工具行中心，必要时扩大命中框。右栏工具保持现有位置 |

参考音量条的源路径在绑定阶段解析。扬声器保留 32×40、43×40 的 viewBox，短窗口赋 24×24 外框时仍按 SVG 的居中等比规则绘制。轨道由 8px 展开至 20px：展开使用 280ms easeOut，收起使用质量 1、刚度 200、阻尼 12 的弹簧；越界位移为 `tanh(2×越界比例)×12px`，整组位移弹簧为刚度 300、阻尼 12。端点扬声器各路径按 1→1.2→.85→1 回弹，三段各 200ms、逐路径延迟 50ms。Android 绘制及帧调度移植这些参数，不宣称与浏览器 framer-motion 逐帧数值相同。拖动实时写入系统音量并回读实际值，取消不额外提交，隐藏时清理动画、手势及监听；鼠标悬停沿用展开反馈。

音量等宽规则约束整个组件外框，不是将轨道本身拉到进度宽；扬声器占位和 SVG 内部留白保留，轨道填满两侧图标之间的剩余空间。SONG/QUEUE 共用相同边界。

左下输出按钮复用 `IosOutputDrawable`，在绑定时解析参考 AirPlay 的原始路径，并按路径可见边界去掉 64×64 viewBox 的外圈空白，居中等比绘制。可见图标边长使用随机/循环 `modeIconSize` 的 68%，不叠加浏览器按钮默认 padding；透明度保持 .45。工具行中心不变，命中框至少容纳原工具框和放大后的图标。它属于窗口底部工具行，不随关闭歌词后的左栏居中横移；在共享叠加层中抵消其横向位移，不额外逐帧测量。点击只调用现有 `PlatformAudioOutputSwitcher.open`：支持的系统使用公开媒体输出面板，ROM 适配及不可用回退沿用原入口。不可通过私有 SystemUI 广播或宿主反射猜测接口。SONG/QUEUE 切换共用按钮，sheet 过渡或隐藏时关闭交互，销毁时随叠加层移除。

进度区域保持原生 SeekBar 与时间标签：轨道占位、标签 gap 与负 margin 清理按 policy 完成，不引入触摸代理，拖动、监听与无障碍始终走原生实例。播放暂停保留原生绑定的 drawable、描述与监听器；上一首、播放暂停、下一首、favorite、more 仍由原生 View 处理动作，不改写绑定或动作 selector。模式状态和操作只走精确档案声明的 `tablet-playback-*` 契约；缺失时不猜测宿主成员。

无损、杜比和卡拉 OK 标识保持宿主默认大小与状态：不覆盖其宽高、最小尺寸、padding、子图标矩阵、字号、透明度或 selector。仅在共享布局准备时，按原生 LayoutParams 测量未 GONE 的标识固有高度；时间标签行取参考行高与原生标识高度的较大值，交给 core 统一计算进度区和剩余 media flex。不能把标识压进参考文字行高，也不能只放开裁切而让它侵入播放区域。SONG/QUEUE 共用同一规则，原生绑定继续负责标识内容、显隐、动作与播放状态。

原生左栏各组件完整保留原有视觉、触摸与无障碍暴露，模块不写入自绘替代视觉，也不整体清零 `player_controls` 父 alpha。保留 `player_controls` 父容器及其原生测量，供数据绑定和右栏代理读取。会话销毁、视图替换、资格变化及失败时回退模块布局写入，并释放音量监听与叠加层。几何输入无效时恢复原生布局。绑定更换时清理上一会话的布局写入，避免旧状态落到上一首的视图。

模块自补的随机/循环按钮沿用 native vector drawable，并为每个按钮独立 mutate。通过 `FIT_XY` 与对称内容 padding 将 drawable bounds 设为参考目标像素大小，不能以固有尺寸缓存再叠加 ImageMatrix 放大，也不转换成 Bitmap 或放大整个 View。保留原生矢量内部留白和选中外形，不以参考 SVG 替换。

随机/循环的交互反馈按参考 `MediaButton` 实现：点击后仅图标绘制做 700ms 的 `1 → .85 → 1.1 → 1` 缩放，关键时刻为 0/20/50/100%，每段使用 CSS 默认 ease；按住时停止该回弹。按住或鼠标悬停时圆形底色在 300ms 内过渡至白色 `0x22` alpha，退出恢复透明。按钮命中区、布局和背景不随图标回弹缩放。动画仅由交互触发，空闲无持续回调；过渡、隐藏、禁用和分离停止动画并复位，系统关闭动画时直接使用静态状态。本规则作用于模块自补模式按钮，原生 transport 的绑定、动画与动作保持宿主管理。

横条命中使用独立的触摸目标，绘制仍遵循参考尺寸。收起交给 framework `ACTION_COLLAPSE` 与 Activity 返回链；不新增私有收起契约，不扩成整屏点击层。保留 sheet 与封面手势。

封面几何是单独的原生桥接边界。约束字段必须按当前构建恢复；混淆 `$b` 的物理 leftToLeft/leftToRight/rightToLeft/rightToRight 为 e/f/g/h，相对 startToStart/endToEnd 为 t/v。不能将这组宿主字段用于模块视觉树，也不能用左右反向的映射依靠相对锚掩盖错误。

适配时以参考 CSS 视口、播放状态及源断点对照，检查 512/640/768/1000/1080 高度边界和宽度查询两侧。原生交互密度和参考视觉倍率独立；不得用 dp 高度触发 CSS 分支。由使用者在目标设备手动确认观感、操作、旋转与多窗口，不以构建成功代替视觉验收。


封面缩放完全由原生处理：模块不持有强制 scaleX/scaleY=1，也不写入或覆盖原生的暂停缩放、sheet、队列与共享过渡动画。nativeCover 的 card、parent 归属与原生 Animator 保持宿主行为；模块对封面的写入仅限 8.1 内容宽规则下的位置与尺寸。

封面圆角、阴影与裁切由原生 image / video 处理，模块不接入 ViewOutlineProvider、clipToOutline 或自算圆角；与参考组件圆角风格的差异随原生实现走，不再标注为模块复刻项。

### 8.2 完整布局和数据绑定

布局核对必须覆盖所有有效的 `layout`、`layout-land`、宽度限定符、dimen/integer/bool，以及递归 include。资源名称相同也可能取到不同值。

特别检查：

- 封面 Guideline、ratio、上下锚点、实际 inset 和元数据 Barrier。
- 标题/副标题/合作信息/更多按钮的边界及是否依赖封面宽度。
- 进度和直播进度的容器 padding、音质标识、播放按钮及 footer 约束。
- generated binding 是否在换歌、播放状态变化或异步加载后重写 padding、尺寸和可见性。
- adapter 首次绑定是否早于右栏测量，以及逐字有效宽度是否从正确视口初始化。
- 左栏各角色的真实载体：封面容器、metadata、progress、transport、volume 与收起横条宿主各自的 View、父链、同名 include 与限定符差异；角色是否与右栏或 QUEUE 共用同一布局。
- 左栏 binding 覆盖：换歌、播放状态、歌词可用性与异步加载后是否重写左栏的尺寸、padding、字号和可见性；横条宿主是否被原生重新测量或替换。

音量与横条为模块组件，不依赖宿主同名控件。反射与资源在安装或视图绑定阶段解析。Fragment 的原生 binding 完成接入只处理当前播放器子树，在测量前准备控制区和 footer。尺寸修改后应完成新布局再绘制；普通重新布局期间，已经放置的模块控件保留有效状态。

SONG 的 `player_controls` 直接位于封面与标题所在的 ConstraintLayout；QUEUE 的同名 include 位于 `controls` FrameLayout。必须分别识别两种父链，再交由同一底部控制区布局函数处理。QUEUE 控制区从共享进度起点延伸到原生父容器底端，进度与播放按钮仍按共享几何定位，内层保留 FrameLayout 参数；不能向 FrameLayout 子项写入 ConstraintLayout 锚点。

QUEUE 列表沿用原生覆盖式 footer 契约：渐变容器保留原始视口与约束，底 padding 按控制区外层高度同步，其他 padding 由宿主管理。原生遮罩仅初始化一次，修改 controls.top 后必须同步底部覆盖长度及 stop 位置。模块复用同一个 `AlphaGradientFrameLayout` 的原生渐变 API，将渐隐终点放在新进度起点上方 8CSSpx，羽化长度读取 `queue_bottom_gradient_height`；控制区以下保持透明，滚动回调写入旧颜色时在绘制前恢复该边界。

渐变 API 的解析在绑定阶段按当前构建 `gradientEdges` 档案核对并缓存。原生 positions 允许为空，表示均匀分布；不能强转为非空数组，也不能分步发布长度不同的 colors 与 positions。两者作为同一个 ramp 持有，通过原生双数组 setter 一次更新；bottom size 独立持有，顶部渐变不变。恢复空 positions 时先通过 setter 发布等长的均匀 stop 数组以标记 shader 更新，再恢复已核对的 `f: float[]` 字段为空，保留原始状态及属性所有权规则。新版本须同时核对 getter、双数组 setter、尺寸方法与该字段。

`clipBounds` 在原始视口内使用同一透明终点作边界，先于原生渐变写入；不能通过缩短渐变容器的布局高度重复扣除控制区。原生渐变解析或更新失败只释放该实例的渐变属性、停止重试并记录原因，保留列表裁剪和共享播放器布局，不能调用整套播放器降级路径。退出时分别释放裁剪、ramp 与尺寸。顶部信息、列表适配器、滚动与原生封面过渡保持宿主路径。

共享控制区的准备与封面尺寸变换分离。安装、pane view 创建及 native binding 完成标记布局失效；pane 尚未附着时，在事务后合并一次准备。尺寸、密度、insets、原生绑定或父布局变化时重新计算；原生封面帧捕获前与 pre-draw 只在失效或未就绪时准备。先捕获原生右侧按钮基线，再应用共享控制几何；参数变化后重新测量，不能绘制旧矩形。冷启动首次展开与新 pane 的首帧不等待 sheet 停稳，但已准备的动画帧不得重复扫描控件树、重算几何或逐属性反射校正；封面尺寸变换继续保留原生动画保护。

sheet 中途复用已测量的叠加布局，仅更新 alpha、位移和交互门禁，不重复测量整屏 overlay 或查询播放模式。原生媒体更新可能在 crossfade 期间重新显示已隐藏的 footer，快速路径须对已缓存的原生 footer View 身份重新施加隐藏与交互门禁，不能重新遍历控件树或提前恢复仍在过渡中的视图。布局矩形与测量约束不变时跳过 measure/layout。已就绪时 SONG/QUEUE 均由共享会话提供音量和模式按钮，停止旧音量显示与监听；失效时按所有权恢复原生参数并保留既有降级路径。右下代理不依赖参考控制组高度计算底线。冷启动显示及展开/收起流畅度由使用者分别对照，不能以静态路径分析或构建成功代替帧率验收。

左栏的原生桥接资源（封面容器、标题/艺人、进度、播放动作与更多动作）在视图绑定时按名称解析并缓存，ConstraintLayout 参数字段复用该构建已恢复的映射。目标构建若更改资源名、限定符、父链或字段映射，需要同步维护对应契约与映射，并在会话重建后重新解析；不能沿用旧缓存或旧字段名。

### 8.3 封面、视频与恢复

静态封面与左栏内容同宽，按 8.1 的内容宽规则取值，并在封面区间内居中，不再以进度轨道宽度为基准。尺寸由实际逻辑视口和参考断点重新计算；不人为为控制区挤小封面，输入无效时保持原生布局。横屏静态封面使用参考的完整尺寸；暂停缩放、sheet、队列与共享过渡动画由原生处理，模块不再写入 scale。

视频需要分别核对外层 `artwork_container`、内层 card、静态图、TextureView、尺寸基准和目标尺寸。静态转视频、视频转静态、视频转视频、静态转静态必须独立验收。

当前 Fragment 双栏由视图会话维护普通/视频共用的外框和静态尺寸基准。进入视频模式不释放双栏外框；原生 image/TextureView 继续负责视频比例和渐变。`FragmentTabletVideoHandoff` 在原生尺寸计算和 resize 前通过 Coordinator 准备当前会话的基准，视频目标按其比例适配该外框，静态目标使用同一基准。`staticBaseline`、`targetSize` 和 `resize` 均为当前精确 profile 的必需角色。

`FragmentPlayerArtworkRecovery` 优先读取当前双栏会话的目标尺寸；视图尚未完成重测时，也不使用旧的测量值覆盖新基准。外框修改请求布局，并在布局完成后绘制。会话退出、视图更换和销毁时按属性所有权恢复；进入/共享过渡、sheet 进度及非双栏状态仍使用各自的原生边界。

原生视频尺寸回调可能经过临时零尺寸，再进入有效尺寸；生成视频宽高比的函数还可能读取缓存的静态基准。每个新构建应追踪这条完整路径，检查外框、基准和目标是否一致。原生内部比例/渐变动画和模块外框变化分别判断；动画期间的写入、让权和恢复也要明确到具体 View 及生效时机。

播放器恢复、双栏尺寸和视频接力可能处理不同层级的 View。应列出每个写入者，确认不会交替恢复和覆盖同一参数。视频表面接力必须确认当前 owner、视图和播放状态；仅凭旧纹理仍可见不能认定它属于当前歌曲。

关闭歌词后的居中、右栏可用性与封面 sheet 补偿只适用于对应 Fragment 会话。原生 callback 执行前释放上一帧补偿，执行后合成当前帧；不累计 X，不覆盖原生 scale/Y，SONG / QUEUE 切换保留手动关闭状态。

## 9. 安装、降级与资源所有权

安装顺序为精确版本识别、配置迁移/绑定、DPI、资源回调、Application 后功能、设置入口。资源基础设施失败应停止依赖它的安装；单个能力目标缺失由该能力报告。

新增 Hook 使用 `HookRegistrationScope`：注册阶段为 preparing，必需项成功后 activate，失败或退出 close。关闭后的回调保持原生透传；不依赖框架一定提供可用的 unhook。

HLE 使用 `HleMetadataInstallation` 和 `AppleHookRegistrar.stageInstallation` 按表面分组。可选子组单独降级，父组失效关闭其子组；后续加入的 getter 继承所属组。已捕获的内层安装错误仍须上报，不能仅记日志后把整项能力报告为成功。

`HostViewSessionController` 以视图身份和配置 revision 管理会话，先关闭旧会话再发布新会话。`OwnedHostProperty` 仅恢复模块仍拥有的最后写入，原生后续写入保留。每个 listener、observer、animation listener 和延迟任务明确所属会话及清理时机。

成员调用缓存按 owner、完整签名及类加载器身份组织；旧路径明确采用历史兼容调用时保留原候选语义。slide、pre-draw、歌词高亮和逐字动画不扫描 DEX、不发现新反射成员。网络、文件读取和 TTML 处理沿用后台业务路径。

## 10. 检查、验收与回退

### 10.1 证据阶段

| 阶段 | 能证明什么 | 不能替代什么 |
|---|---|---|
| 安装包/源码分析 | 版本身份、成员描述符、调用关系、资源与布局决定 | 当前设备的实际触发和显示 |
| 静态工具 | 工具覆盖范围内的数据/契约一致性 | 所有辅助路径、资源取值和运行表现 |
| APK 构建 | 可生成本次代码的安装包 | Hook 实际安装、视觉和功能正确性 |
| 能力状态 | 当前进程中安装、降级、未支持或失败的结果 | 全部场景和旧版本回归 |
| 人工验收 | 指定版本、设备、设置和操作下的结果 | 未执行场景及其他版本 |

`productionEnabled` 与 `runtimeVerified` 分别维护。局部场景确认不能把整个档案改为运行时验证完成；没有对应字段或证据也不能推断为通过。

### 10.2 工具与执行范围

现有辅助入口包括 `scripts/verify-profile-data.py`、`scripts/verify-architecture.py` 和 `scripts/verify-host-profile.py`。按证据缺口和当次任务范围选择，工具说明见 [scripts 文档](../scripts/README.md)。

宿主静态脚本读取二进制 base Manifest，并收集包内 split 的 DEX 与布局名；显式版本参数必须与真实 Manifest 一致。开发期未启用的 profile 可通过 `--profile` 提供。静态匹配结果应同时记录脚本覆盖不到的语义依赖。

项目默认由使用者执行功能验收，不主动操作设备或运行功能脚本、全量测试、哈希验证。既有测试夹具供获授权的检查和 CI 使用；仅文档修改不需要构建 APK。构建交付物时选择任务需要的 Gradle 构建目标，记录产物与实际结果。

### 10.3 完成条件与回退

一个构建的适配记录应能回答：

1. 精确身份、页面族和能力资格是否明确。
2. 所有功能和附属契约是否有证据，缺口是否可报告。
3. 资源、binding、视频与恢复是否已按实际路径核对。
4. 配置、数据和插件接口是否保持兼容。
5. 构建、人工场景与旧版本回归分别完成到哪一步。
6. 出现问题时能否停用该 tuple 或能力，并恢复原生行为。

回退通过停用新版本登记及撤销对应接入实现完成。保持配置 schema、键、文件 ID、数据库/缓存 namespace 和 ZIP 格式时，无需反向数据迁移；涉及数据格式变化的工作必须另行定义迁移和回退，不能作为普通混淆适配附带处理。

## 11. 导航页面启动预热

启动预热是独立宿主能力，与玻璃、收缩动画和播放器双栏无依赖。配置键 `startup_page_prewarm_enabled` 默认开启；模型与缺键解码共用 `EnhancementDefaults.STARTUP_PAGE_PREWARM_ENABLED`，已经明确保存的关闭值继续生效，不通过升级强制覆盖用户选择。它属于 schema15 的普通布尔设置，不修改资产、索引或迁移 marker；开关在重开应用后生效。设置入口只在精确生产档案授予 `startupPagePrewarm` 能力、提供完整 v1 契约并登记当前手机或平板形态的导航资源时显示，默认开启不授予未适配版本或形态该能力。

适配前先确认原生一级 tab 采用按标签保留实例的隐藏/显示机制，核对每页容器、参数键、导航图和真实根页面。`StartupPagePrewarmContract` 使用 `FragmentProfileMembers` 解析完整成员、字段和构造器；不能通过主版本号、通用 Fragment 扫描或邻近构建推测成员。原生创建流程需要额外参数、容器释放策略变化或共享状态保护失效时，先停用能力再补齐契约。

入口与安装共同限定 `startupPagePrewarm.schemaVersion == 1`。新增构建须把预热的完整方法、字段、构造器及必需角色纳入 `scripts/verify-host-profile.py` 的离线成员核对；仅描述符匹配不能代替隐藏保留、生命周期和共享状态语义确认。调用协议或角色集合发生不兼容变化时，应设计新契约和解析器，不能继续冒用旧 v1 门禁。

导航资源须与当前形态 chrome 的语义角色及实际内容布局相互核对；资源表中存在同名 ID 不代表它位于当前视图树。v1 的 `navigationResource` 保留手机内容根中的底部导航登记，可选 `tabletNavigationResource` 登记 Activity 中的平板抽屉菜单，并要求精确的 `tabletMenu` 方法角色；未登记的形态不安装预热。平板顶部 Compose 导航不能调用手机菜单 API：通过原生抽屉菜单筛选实际可用的一级模型，并用菜单项的 checked 状态核对当前选中模型与主导航标签；不主动打开抽屉或设置选中项。分别核对 `hostResource` 的真实页面视口，只向一级页面宿主添加隐藏容器，不能预热抽屉或播放器内容。玻璃隐藏原生导航或抽屉关闭时仍应能读取原生菜单，不能把菜单视图的可见性当作页面就绪条件。日志分别记录内容回调、任务排队、页面事务完成及结束原因/数量；等待超时须带具体门禁，不能将 Hook 的 ACTIVE 安装状态当作已完成预热。

`FragmentStartupPagePrewarmTarget` 负责安装与失败隔离，按当前形态选择精确档案中的导航资源；手机和平板共用 `StartupPagePrewarmSession`，只持有当前内容视图的有界启动任务。当前有效菜单、主导航和视口就绪后，依次创建缺失且可用的一级页面，按原生标签加入隐藏容器并允许原生事务重排，再用实际视口做一次隐藏页测量/布局。已存在或系统恢复的隐藏页面复用原实例和状态，也做一次预布局；不能将容器存在等同于已布局。尚无 View 时有界等待，完成后再标记当前实例。不创建歌单、专辑和搜索结果详情，不维护额外的全局页面缓存。

预热不得调用原生切页入口、改写 primaryNavigationFragment、选中项、上次访问页或返回栈。隐藏容器的初始播放上下文观察者和资料库创建时的共享工具栏重置需要按精确调用范围保护；真实显示、普通资料库/详情访问和其他属性事件继续使用原生行为。适配新版本时重新核对这些副作用，不能只确认容器能够构造。

资料库工具栏保护依赖原生 view 生命周期在主线程执行，且重置事件在隐藏资料库 `onViewCreated` 内同步派发。新构建改为异步或跨线程事件时，必须重新设计事件归属，不沿用当前调用窗口。停止预热只停止后续排队和预布局，已经提交的原生事务仍可完成并由原生隐藏保留/释放；迟到回调不能继续预布局或操作新内容视图，视图重建必须先关闭旧会话并复核 owner 当前 root。

整轮任务使用10秒就绪期限，独立超时回调也须覆盖原生事务迟迟不完成的等待状态，并在所有结束路径撤销。每次只提交一个原生事务，事务完成后再继续下一页。触摸、滑动和导航切页不中断这轮启动预热，不监听手势或滚动状态；每步重新核对当前原生选中项并复用最新页面实例，不回切启动时的页面。内容视图隐藏/脱离/销毁、已保存状态或导航列表变化时停止后续任务，失败不阻断原生切页。开关关闭并重开后不安装预热 Hook。手机和平板横竖屏分别确认真实内容视口、重建后的新会话，以及抽屉与播放器单栏/双栏不被预热事务改动。页面与后台数据仍按原生生命周期释放；启动后立即操作可能与预热争用主线程，内存占用、启动负载、隐藏异步刷新和首次真实显示的绘制开销需由使用者人工对照，不能把预建成功等同于首次切页零掉帧。


