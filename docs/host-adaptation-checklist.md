# 宿主版本适配清单

每个精确构建单独复制本清单到任务记录填写，本文件保持可重复使用。勾选表示该项已有可追溯证据，不能用成员匹配、构建或安装成功代替人工通过。

状态统一为“已核对 / 未确认 / 不适用 / 已知降级 / 新异常”；不适用注明页面族、能力或形态依据。每项关联下面的证据表，人工操作与预期只在[功能保全矩阵](feature-preservation.md)维护，实现规则查[宿主适配手册](host-adaptation-guide.md)。

## 1. 构建身份、范围与基线

| 项目 | 本构建填写内容 |
|---|---|
| 精确身份 | 实际宿主 packageName / `TargetBuild` 的 versionName、versionCode，base二进制Manifest位置 |
| 安装包 | 格式、base、全部split、各DEX及资源来源 |
| 代码基线 | 提交/分支、已有未提交工作、可回退代码与产物 |
| 对照宿主 | 对照构建、实际页面族、复用与独立分析范围 |
| 档案 | 文件名、id、profileRevision、索引位置、生产资格；`profile.packageName` 表示原包与内部命名空间 |
| 能力边界 | 能力位、独立契约、设置/API/手机平板形态与已知缺口 |
| 执行范围 | 源码/资源分析、所需检查、构建与使用者人工场景分别安排 |
| 数据兼容 | 当前配置schema/键、文件ID、目录/marker、SQLite/cache、ZIP及公共插件协议 |
| 作用域 | `module.prop` 的 `staticScope`、`scope.list`，以及 LSPosed 手动选择改名宿主的结果 |

- [ ] 入口按实际宿主 packageName 校验通过，`TargetBuild` 记录实际包名；首次加载与声明主进程过滤保持生效。
- [ ] 身份、外层清单/显式参数一致，正式版/beta/同名不同构建独立建档。
- [ ] 仅 Android 安装包名变化；内部类、方法和资源保持原包常量，实际包名只用于身份、版本及组件，其他改包行为未套用本兼容规则。
- [ ] 所有DEX/资源split已覆盖，页面族由真实入口/生命周期确定。
- [ ] 直接比较原始已适配版与目标版时，base对base、同类ABI split对同ABI、密度split对同密度，其余split按相同身份与限定符配对；目标新增或缺失项单独登记，未跨来源替代。
- [ ] 当前档案与参考档案资格区分，未凭旧类存在或版本号推定兼容。
- [ ] 原始owner/擦除描述符有字节码依据，未采用工具修复名/推断类型。
- [ ] 类结构、字段顺序或相似描述符仅记为结构候选；转为已确认映射前，owner/完整签名、声明与实例关系、调用/读写语义、Hook具体实现及生命周期作用均已闭合，未以结构或描述符匹配单独授予运行时兼容。
- [ ] 默认人工验收；设备/功能脚本、全量测试、哈希仅按明确任务范围执行。

## 2. 资料、解析与证据

规则依据：[profile与三种接入方式](host-adaptation-guide.md#profile-contracts)。

- [ ] 文件名与精确 versionName+versionCode 一致、id 唯一且索引有序；`profile.packageName` 仅描述原包和内部命名空间，不作运行时白名单。
- [ ] 每条接缝标明索引、HLE或独立契约方式及回退策略。
- [ ] 必需角色/完整描述符、类型关系与当前调用链一致，抽象调用与Hook具体实现已区分。
- [ ] 新依赖已同步profile、代码Inventory/Contract、verification和适用离线覆盖。
- [ ] 新档案拒绝歧义；历史首匹配/旧HLE候选语义没有无依据扩大或替换。
- [ ] productionEnabled与runtimeVerified按阶段分开，evidence/knownDegradations与实际证据一致。

### 2.1 逐接缝证据模板

| 功能 / 角色 | profile或代码位置 | 当前包成员 / 资源 | 调用语义 / 类型关系 | 解析与失败决定 | 状态 |
|---|---|---|---|---|---|
| 待填写 | 区域、角色和入口 | owner/完整签名；资源类型/限定符/父链 | 调用方、返回对象、原生读写及清理 | 精确解析、允许回退、安装前置 | 待填写 |

直接入口继续展开binding、holder、getter、listener、模型与清理路径；不在本模板另抄整套档案。

### 2.2 功能覆盖表

按此表逐域填写。自动来源/LLM虽然共用提交，模糊与CJK各有适用结论；简体替换和声修复、跨行扫光与活动行高亮共用“歌词效果优化”开关，但仍分别填写安装、降级与失败结论。

| 功能域 | 资料 / 接入依据 | 源码与包证据 | 安装 / 降级状态 | 人工结果入口 |
|---|---|---|---|---|
| 共用歌词结果提交 | indexed、lyricResultSubmission、安装与item更新 | 待填写 | 待填写 | 待填写 |
| 当前歌曲、手动/自动/刷新来源 | 身份、parser、资格、来源与缓存会话 | 待填写 | 待填写 | 待填写 |
| 简体替换和声修复 | 共享lyric_effects_optimization_enabled、LyricsParserFeature/LyricsParserTarget、单installParserRepair与replacement x-bg | 待填写 | 待填写 | 待填写 |
| LLM翻译 | 精确原文观察、模块解析排除、共用通道 | 待填写 | 待填写 | 待填写 |
| 模糊 | lyricBlur、显示提交、行词动画、效果所有权 | 待填写 | 待填写 | 待填写 |
| CJK辉光与末词 | cjk、可选terminalGradient、原生动画 | 待填写 | 待填写 | 待填写 |
| 歌词效果优化：跨行扫光 | 共享lyric_effects_optimization_enabled、独立lyricWrapSweep、视觉行/媒体时间/退役 | 待填写 | 待填写 | 待填写 |
| 歌词效果优化：活动行高亮 | 共享lyric_effects_optimization_enabled、独立lyricBackgroundHighlight、活动行/词ID/焦点 | 待填写 | 待填写 | 待填写 |
| 字体与歌词面板 | 生命周期、binding/adapter、资源/度量 | 待填写 | 待填写 | 待填写 |
| 手机/平板玻璃、mini | chrome/fragmentChrome、导航与sheet | 待填写 | 待填写 | 待填写 |
| 底栏收缩 | 独立资格、滚动/触摸、共享几何与渲染 | 待填写 | 待填写 | 待填写 |
| 启动预热 | 独立能力/契约、当前形态导航与视口 | 待填写 | 待填写 | 待填写 |
| 双栏、视频与恢复 | 页面族、布局/binding、尺寸基准与所有权 | 待填写 | 待填写 | 待填写 |
| 名称修正及Compose艺人页 | HLE各表面、目录/模型、artistComposeProfile | 待填写 | 待填写 | 待填写 |
| 设置、蜂窝、DPI | 设置桥、原生资格、宿主密度与重建 | 待填写 | 待填写 | 待填写 |
| 配置/数据/插件 | 迁移/原子存储、备份、公共SDK/注册记录 | 待填写 | 待填写 | 待填写 |

## 3. 页面族与资源判定

依据：[适配流程与页面族](host-adaptation-guide.md#adaptation-workflow)。仅填写实际页面族，另一族标记不适用。

| 页面族 | 待核对的接缝组 | 证据与结论 |
|---|---|---|
| Activity | PlayerActivity/BottomSheet/controller/pane、双栏资源、歌词同步、旧导航/mini、Preference结果桥、Editorial与HLE | 待填写 |
| Fragment | fragmentDualPane、lyricsRuntime、karaokeWidth、tabletVideoHandoff、songLayout、chrome、playerRecovery、settings | 待填写 |

- [ ] 工厂已有该family的独立接入，未把新族默认套入旧路径。
- [ ] Fragment各组安装前分别解析；双栏成功未代替chrome、恢复或歌词能力完整。
- [ ] songLayoutPolicy与布局契约匹配，layoutReview六类证据齐全。
- [ ] host ID/恢复tag及状态保存/右栏测量规则保持，新view关闭旧会话。
- [ ] 模块挂载容器和宿主LayoutParams类型正确，未用模块AndroidX替代宿主对象。

### 3.1 资源与binding证据

| 证据类 | 本构建应说明的事实 | 结果 |
|---|---|---|
| artwork | 限定符、外框/card/image/TextureView、约束与尺寸写入者 | 待填写 |
| metadata | title/subtitle/更多动作、真实父链、文本绑定 | 待填写 |
| controls | 原生播放动作/状态/selector、模式契约、代理与显隐 | 待填写 |
| progress | 普通/直播SeekBar、时间、原生监听/无障碍和质量标识 | 待填写 |
| binding | include/生成绑定、换歌/播放/异步覆盖、角色是否共享 | 待填写 |
| restoration | 旋转/重建/后台/切歌/SONG/QUEUE及属性所有权 | 待填写 |

- [ ] 全部有效限定符与递归include已分析，资源名/数字ID未跨构建盲目复用。
- [ ] View父链/参数与binding覆盖有依据，回调使用缓存，配置/视图变化重新解析。

## 4. 按功能域判定

各域勾选后在2.1/2.2表补证据和状态，人工结果链接对应矩阵场景。仅修改共享策略时，再按第5节复核实现；新构建仍覆盖所有适用功能。

<a id="lyrics-result-submission-checks"></a>

### 4.1 共用歌词结果提交

[维护规则](host-adaptation-guide.md#lyrics-result-submission) · [人工场景](feature-preservation.md#lyrics-result-submission)

- [ ] 结果容器、发布实现、索引、指针/歌曲身份、安装及item更新均命中当前精确契约。
- [ ] LiveData实际声明基类、返回类型与公开生命周期入口有证据，按容器实例过滤。
- [ ] 索引/结果保存一致，异常后的读取对齐与复用失效判据已核对。
- [ ] 显式/推断请求、实时身份、postValue合并与空Pair真实边界符合当前宿主语义。
- [ ] 附加、待安装补发、候选owner/销毁与降级边界齐全，未直接重入安装或改写item。
- [ ] 安装入口空值抑制以同 Fragment、同 ViewModel、同请求及同播放 ID 为前提，并能取得存活且 ID 匹配的当前文档或 ready replacement；缺一即保持原生空安装。
- [ ] 空安装抑制只记录此前未知的原生观察、保留选择、置待安装并经既有结果通道主线程补发；不改安装入参、不重入安装，未为已有信息新增 profile 角色。
- [ ] 同指针去重前读取实际跟踪 LiveData 的当前值；空值或容器更换不沿用旧安装确认，读取失败保留选择与后续恢复机会，不当作去重成功。
- [ ] 附加和同一 ViewModel 的后续请求都会刷新实际 `lyricsResult` 容器，不继续向旧结果容器发布。
- [ ] 发布调用、容器实际保存和原生安装分别留证；仅有发布轨迹不能证明页面已覆盖，非空原生结果仍按既有仲裁。
- [ ] 失败定位能区分 Hook 入口未调用与身份、容器、文档状态导致的早退，诊断按会话有界且不输出歌词正文或凭据。
- [ ] 资料库补全后的 getId 变化已核对；仅以与播放器发布快照相同的非零 persistentId 建立配置身份关系，零值、不可读或不相等时不猜别名。
- [ ] 配置/缓存使用逻辑 ID，指针/索引/安装使用当前实际 item ID；core 的逻辑身份不会掩盖原生指针失活或绑定被意外改变。
- [ ] 同文档切换实际提交 ID 时，后台使用保留 TTML 准备独立指针，新对象不被旧文档去重折回；不改旧指针、不在主线程解析、不复制裸地址或猜测所有权。
- [ ] 同 Handle 的进行中准备合并目标变化，只接纳最新目标；解析失败不循环唤醒，实际传入指针失活不被其他存活指针掩盖，无原文时保留原生行为。
- [ ] 原生结果先于 Fragment native ID 对齐时，分别核对接收归属与提交目标：仅同当前 canonical 请求、同当前 ViewModel 且显式加载已捕获该 raw ID、结果仍存活时暂存一个结果，owner 对齐后经实际容器恢复；空/失活结果不占位，新请求、换 VM、切歌或关闭使其退役，且不改现有 native 目标 ID 优先级。

<a id="lyrics-sources-checks"></a>

### 4.2 当前歌曲与歌词来源

[维护规则](host-adaptation-guide.md#lyrics-sources) · [人工场景](feature-preservation.md#lyrics-sources)

- [ ] 当前item/ID、多ID、元数据转换与原生TTML/指针来源对应实际播放对象。
- [ ] 手动/禁用优先、原生质量门槛及用户来源次序沿所选策略，未因通道适配改变；原生来自宿主结果，未增加原生源网络或发布通道。
- [ ] `automatic_lyrics_prefer_native` 缺键开启，来源顺序缺键为 AMLL/AM-Lyrics/AM原生/Lunabeat；解码、保存和运行时会将旧顺序中位于原生前的 Lunabeat 仅移到原生之后，其他来源相对顺序不变，并继续补齐缺项、去重、过滤未知项。
- [ ] 两种原生优先模式共用逐字/语言/译文资格；关闭优先时达标原生仅查询前置来源，不达标或不可用仍查询全部第三方，包括原生后置的 Lunabeat，Lunabeat 不禁用；全部未命中回退可用原生。缓存按 provider 位置使用，来源计划变化撤销旧代次。
- [ ] 自动发布显式标记 `autoManaged`，索引/备份/更新保留归属；缺字段旧项和明确手动操作受保护，不按来源名称推断归属。
- [ ] 自动实时补全二级页包含总开关、原生优先及排序；来源行右侧拖动柄按住上下拖动、松手保存，取消不保存，柄外页面滚动正常且不与拖动争用；原生项显隐保留位置，触控拖动与无障碍上移/下移均不能使 Lunabeat 跨到原生之前，也不能使原生跨到 Lunabeat 之后，隐藏原生时按完整顺序判断，阻止后不保存新顺序；排序保存、返回父页及无障碍动作正确；模块列表使用自身 AndroidX 资源及宿主有效密度，不依赖宿主私有列表或资源 ID。
- [ ] 排序区标题、资格说明、来源文字和拖动柄的左右内容边界与上方两个开关一致，手机/平板布局均沿既有内容留白；说明明确原生在排序模式下仍须达标，并提示 Lunabeat 必须排在 AM 原生之后。
- [ ] 缓存/来源刷新/发布与后台代次、取消、恢复和共用提交都有当前接入依据。
- [ ] AMLL Kotlin 转换器与 CLI 的译文/音译和声 `span` 显式使用 TTML 默认命名空间及 `ttm` 元数据命名空间，未继承 Apple 轨道的默认命名空间。

<a id="simplified-replacement-backgrounds-checks"></a>

#### 4.2.1 简体系统语言替换和声修复

[维护规则](host-adaptation-guide.md#simplified-replacement-backgrounds) · [人工场景](feature-preservation.md#lyrics-sources)

- [ ] `FeatureInstallation` 始终登记 `LyricsParserFeature`；共享 `lyricEffectsOptimizationEnabled` 关闭时返回 `DISABLED` 且不调用 `LyricsParserTarget.install()`，无新增设置，并遵循现有重启策略。
- [ ] 共享设置开启时，`LyricsParserTarget.install()` 委派的 `AppleMusicCustomLyricsTarget.installParserRepair()` 提供单解析 Hook；普通原生修复不依赖自定义歌词开关或替换会话运行。
- [ ] 自定义歌词开启而 `LyricsParserFeature` 关闭时，`CustomLyricsFeature` 仍可安装同一 `installParserRepair()` 以保留元数据和 raw-TTML 观察；重复入口复用同一 Hook，before 在共享设置关闭时跳过改写。
- [ ] `TTMLParser$TTMLParserNative#songInfoFromTTML(String): SongInfo$SongInfoPtr` 的 owner、实例归属、唯一参数与返回闭合到同一精确符号；不可用时只降级本能力并透传原歌词。
- [ ] 只安装一个解析 Hook：before 先检查共享设置及 locale 再修复参数，after 向可选观察者提供修复前的原始 TTML；设置关闭时观察仍可用，不存在第二 Hook 顺序依赖。
- [ ] 门禁要求共享设置开启，且 locale 语言为 `zh`、显式 `Hans`，或脚本缺省、地区为 `CN`/`SG`；其他情况不执行。原生 replacement 资格或自定义歌词开关不能替代门禁。
- [ ] 仅使用唯一 `type="replacement"`、`xml:lang="zh-Hans"` 轨道；正文 key 唯一，`x-bg` 组顺序对应，组内词数和完整、有效、唯一的词时间数值等价，双方声明的组时间一致。
- [ ] 规范 TTML 根/前缀与 Apple 轨道命名空间可验证，替换轨每个 `x-bg` 显式切回 TTML；超出输入上限、重复 key 或轨道歧义时整份透传，缺内容或词/时间不兼容时整行回退，无行内部分写入。
- [ ] 只填正文既有 `x-bg` 的词文本；正文行/span 时间、层次、属性、主唱前景及其他辅助轨不变，未引入通用转换器或原生内存位移。
- [ ] 共享设置开启时，普通原生歌词和 `OpaqueTtmlParser` 的模块文档都经过同一门禁与修复入口，普通原生歌词不需要启用自定义歌词；模块解析标记继续排除其原生来源归属。
- [ ] LLM 注册表持有原始原生 TTML，而非修复后的宿主入参；修复失败、输出不兼容或能力降级时仍以原文调用原生解析。
- [ ] 人工结果分别覆盖共享设置关闭、开启但自定义歌词关闭、自定义歌词开启而设置关闭的观察路径、`zh-Hans`/`zh-CN`/`zh-SG` 正向、`zh-Hant`/`zh-TW`/非中文反向、模块文档、正文与其他轨保持不变，以及缺 key/重复 key/时间冲突的原文回退。

<a id="llm-lyrics-fallback-checks"></a>

### 4.3 LLM原生歌词翻译

[维护规则](host-adaptation-guide.md#llm-lyrics-fallback) · [人工场景](feature-preservation.md#llm-lyrics-fallback)

- [ ] 原生原文观察与确切指针关联、模块解析排除及数量/字节界限成立。
- [ ] 最终采用原生及时间轴/语言/译文资格先于 LLM 缓存；所需查询未完成不翻译，不达标原生等待全部第三方未命中后才可回退，手动或第三方结果撤销译文；输出关联与原生解析失败保留原文。
- [ ] 主唱与每段和声独立提取和翻译，过滤后的和声位置传到提示词；同段共用唯一 key 和一个 `text for`，和声以 TTML 命名空间的 `x-bg` 子节点写回，原时轴、声部、音译与重复行保留。
- [ ] 翻译提示词覆盖上下文/语气/意象、信达雅润色、按语境处理重复及完整和声，中文译文不使用逗号和句号；无冲突的旧规则或示例，风格调整不改变返回格式与行序约束，可继续复用现有缓存。
- [ ] 纯和声段落、多个和声、和声先于主唱及不同声部语言均有对应关系；正文缺 key 时仅补一次；声部分组或输出约定变化已提升现有提示词/缓存版本。
- [ ] 无独立猜测式宿主映射，结果继续通过共用通道；原文入口缺失能明确停用。
- [ ] 若改客户端/设置/缓存，已登记第5节维护结论与相应矩阵场景。

<a id="lyrics-blur-checks"></a>

### 4.4 歌词模糊

[维护规则](host-adaptation-guide.md#lyrics-blur) · [人工场景](feature-preservation.md#lyrics-blur)

- [ ] 独立lyricBlur角色/关系覆盖当前族，不依赖双栏预检或开关。
- [ ] adapter提交/显示快照、实际文档边界、ID映射与四路真实文字动画读取有当前证据，未把外层安装请求等同文档替换。
- [ ] 前奏/三点、Ending、Seek、文档/视图/交互失效与不完整读取分别判定。
- [ ] SDK效果/裁剪租约、模块写入和宿主后写的所有权及关闭恢复明确。

<a id="cjk-animation-checks"></a>

### 4.5 CJK辉光与末词补偿

[维护规则](host-adaptation-guide.md#cjk-animation) · [人工场景](feature-preservation.md#cjk-animation)

- [ ] 辉光前景绑定与原生门槛有据；精确 profile 的 `cjk.maxSpanGlowCharacters` 缺省为 `1`。显式启用时，`2..min(值, 3)` 字的单个原始未合并 CJK span 须 `trim` 后全属既有六区块，累计时长/文本长度与原生 span 一致，`splitBindingCount` 在 `0..字数`。
- [ ] 多字 span 按总时长/字数 ≥1000ms 放行，不得按宿主合并的多个原始 span 总时长放行；仅单字分支调整回落子动画延迟，多字保留宿主错峰，原生时长/文本/分组/长度 g 与末词补偿不变。新构建须独立确认元数据与绑定语义，默认不继承。
- [ ] 可选terminalGradient按代码必需角色完整解析。
- [ ] 原文/发音尾词、时间/ID、余量调用与延迟补亮标签语义一致。
- [ ] 开关/独立scope、缺契约不支持及失败保留辉光的状态正确。

<a id="lyric-wrap-sweep-checks"></a>

### 4.6 跨行歌词扫光

[维护规则](host-adaptation-guide.md#lyric-wrap-sweep) · [人工场景](feature-preservation.md#lyric-wrap-sweep)

- [ ] 与简体替换和声修复、活动行高亮共用“歌词效果优化”开关；默认/缺键关闭且更改后重启生效。关闭时本 Feature 返回 `DISABLED`、不调用 `target.install`，CJK 辉光开关不控制本项。
- [ ] 独立 `lyricWrapSweep` 契约/资格/作用域明确；设置开启只作门禁，不因共用开关或其他能力成功而授予支持。
- [ ] 视觉行键、child范围、原文/发音bank与四路模型关系有当前证据。
- [ ] 时间单位、原生动画、静态/暗色/渐暗/后继写入的真实路径已核对。
- [ ] 换绑/回收/文档/布局/Seek/暂停恢复与旧写入退役覆盖，几何不支持保持原生。

<a id="lyric-background-highlight-checks"></a>

#### 4.6.1 歌词效果优化：活动行高亮

[维护规则](host-adaptation-guide.md#lyric-background-highlight) · [人工场景](feature-preservation.md#lyric-background-highlight)

- [ ] 与简体替换和声修复、跨行扫光共用“歌词效果优化”开关；默认/缺键关闭且更改后重启生效。关闭时本 Feature 返回 `DISABLED`、不调用 `target.install`。
- [ ] 精确 profile 的 `lyricBackgroundHighlight` 分区声明独立契约，代码必需成员/关系校验、调用方去优化和注册均成功；未声明与安装失败的状态及 scope 清理正确。与 `lyricWrapSweep` 分别报告支持、降级或失败，一方不可用不阻止另一方。
- [ ] 当前构建活动行的内部过滤路径有据；原始输入仍含父行时也能识别未结束背景句，候选覆盖当前、输入与已保留行。
- [ ] `lineSubset` 仅处理同次提交、同一当前集合的首次过滤结果及真子集变化；模块保留与释放不影响其他原生成员或调用。
- [ ] 正常提交与结束补交复制合并父行主词/背景词 ID，原生消费路径已核对；原始入参不被修改，释放只清模块旧父行。
- [ ] 提交后的集合、重绑通知、焦点与显示快照一致；模糊及行词动画读取该状态，滚动仍由原生交互门槛控制。
- [ ] 窗口以父句起点及原文/发音背景词最终结束点为界；媒体时钟、行调度偏移、到期与补交载荷语义已分别确认，TTML/媒体时钟未改。
- [ ] 暂停停止到期轮询，恢复继续；Seek、换文档、脱离和视图销毁的释放路径完整，reader 与各 adapter 会话隔离，旧回调不写入新绑定。
- [ ] 能力状态如实区分安装与运行失败；异常释放/清理、后续会话重建及非主线程透传有据，正常逐句路径无排查日志。

<a id="lyrics-typeface-checks"></a>

### 4.7 字体与歌词面板

[维护规则](host-adaptation-guide.md#lyrics-typeface) · [人工场景](feature-preservation.md#lyrics-typeface)

- [ ] 普通/逐字/资源行、binding/adapter、右栏宽度/度量和行监听接入完整。
- [ ] 后台字体准备、重试、就绪状态、重建与原字体恢复分别有依据。

<a id="chrome-glass-checks"></a>

### 4.8 玻璃导航、mini与sheet

[维护规则](host-adaptation-guide.md#chrome-glass) · [人工场景](feature-preservation.md#chrome-glass)

- [ ] 精确玻璃/API/形态资格，导航模型/getter/callback与资源已核对。
- [ ] 手机/平板导航、抽屉、mini与采样区域按真实树接入；菜单与布局就绪分别有据，平板顶栏的单抽屉按钮占位保持原生，材质展开、锚点/玻璃承载均无待布局且宽高一致、Compose内容度量有效后才接管。
- [ ] 窗口/配置变化及导航暂时透明时仍跟随锚点；几何同步后的同一就绪结果控制原生显隐、玻璃透明度与背景采样启停，布局未稳定时恢复原生且无双导航重叠。
- [ ] sheet状态/peek/settle/坐标刷新与原生动画终点、基线恢复有证据。
- [ ] 若声明 `collapsedPeekMode=offset`，已证明当前 setter 的完整副作用；只在当前owner、固定peek、稳定收起且无待布局时偏移，其他状态/关闭恢复沿原生 setter。
- [ ] 若声明 `contentBackdropNode`，完整字段与原生录制更新时机有据；唯一子树、坐标/尺寸/显隐及就绪门禁、View回退和宿主节点所有权正确。
- [ ] 预绘制中的裁剪/留白 setter 仅在值变化时调用；已核对原生列表同值写入是否仍请求布局，避免持续布局/采样循环，且原值捕获、宿主真实变更接管与关闭恢复保持正确。
- [ ] 共用mini启动的显示/登录/当前对象条件、调用形式及有界任务与root归属正确。

<a id="glass-collapse-rendering-checks"></a>

### 4.9 底栏收缩与渲染

[维护规则](host-adaptation-guide.md#glass-collapse-rendering) · [人工场景](feature-preservation.md#glass-collapse-rendering)

- [ ] 独立收缩能力与手机/非平板形态成立，设置通过语义桥查询。
- [ ] 原生位移和Compose方向边界由绑定提供，实际容器/语义owner及缓存失效有据。
- [ ] 层级发现止于外层滚动目标/Compose场景，不展开列表行；pager承载继续深入，隐藏Compose显隐仍可识别，旧目标padding可恢复。
- [ ] 资格、接入与收缩配置共用会话设置快照，短周期和前台恢复刷新有效，重复布局不重复解码。
- [ ] 长列表非末尾的留白更新可合并至停稳，到末尾/短内容/未知边界/新目标/几何与前台变化立即同步；导航和mini局部测量不传播整页布局或抑制原生输入。
- [ ] 原生导航触摸隔离、mini原生输入、搜索身份及共享几何的当前形态前提已核对。
- [ ] 收缩与sheet校准、导航切页/配置/关闭恢复的边界正确；新leading/高度变化已单独分析。
- [ ] 可见标签仍由 `BasicText` 绘制，并由 `onTextLayout` 按 tab ID 保存最近一次原始约束与 `TextLayoutResult`；表随菜单、样式、配置、字体 resolver、密度或方向变化重建，容量限于当前菜单。
- [ ] 隐藏折射标签仅在文字、样式、字体状态（无 stale）及原始约束匹配时复用可见结果；不匹配或隐藏层先测量时由 `BasicText` 独立测量回退。`refractionContent` 默认仍使用原 `content`，仅 `GlassNavigation` 收缩路径传入替代文字绘制；可见与折射内容的图标、绘制/着色、触摸、图层及原单行省略号、基线、命中、选中和无障碍语义分别保留，原生列表与宿主契约边界不变，未承诺固定命中率或统一减半。
- [ ] 折射文字复用命中时已按结果尺寸测量、放置真实绘制节点并保留基线；未命中时只放置原 `BasicText`，不依赖空 placement 或未放置子树绘制。人工检查各选中标签、切页、展开/收缩及配置变化后的文字、着色与折射源完整性。
- [ ] 若改输入/动画/材质或同步上游，已登记第5节通用维护结论；性能说法有目标设备证据。

<a id="startup-page-prewarm-checks"></a>

### 4.10 导航一级页面启动预热

[维护规则](host-adaptation-guide.md#startup-page-prewarm) · [人工场景](feature-preservation.md#startup-page-prewarm)

- [ ] 精确能力、契约版本与当前手机/平板导航资源同时满足，未登记形态不安装。
- [ ] 原生按标签隐藏保留、tab模型/checked、参数/导航图及真实hostResource有据。
- [ ] tabArgumentKey/titleArgumentKey 已追溯当前包的参数写入/读取与常量初始化，完整键值和Bundle值类型一致；Class.getName拼接键已确认实际引用类，未因描述符通过而沿用旧值。
- [ ] 隐藏播放上下文/资料库事件保护的线程与同步归属符合当前包。
- [ ] 一次一事务、实际视口预布局、恢复实例复用、独立超时及旧root停止明确。
- [ ] 安装、排队、页面完成数量与具体停止原因分开记录，未以ACTIVE代表预热完成。

<a id="tablet-layout-checks"></a>

### 4.11 平板布局、视频与恢复

[维护规则](host-adaptation-guide.md#tablet-layout) · [人工场景](feature-preservation.md#tablet-layout)

- [ ] 精确构建与开关符合资格；Activity的is_tablet/横屏、Fragment的宽度至少600dp/宽大于高/原生drawer分别有据，实际窗口/inset/DPI输入正确。
- [ ] 原生角色和SONG/QUEUE两种父链符合资源/binding证据，未用错误参数类型。
- [ ] 共享布局使用当前CSS视口策略，封面基准与实际调用一致，原生scale/动作保持。
- [ ] 当前页面族的音量/输出、原生footer、模式契约/右下代理范围明确，收起命中分别按CSS尺寸、dp下限与可用空间判定，几何失效和恢复归属完整。
- [ ] 视频外框、静态基准、目标及resize先后有证据，零尺寸与迟到owner分别处理。
- [ ] QUEUE定制渐变按实际调用路径判定；getter/双数组setter/nullable positions、覆盖footer与单实例降级有据，未将共享类全部行为推定为两族均可用。
- [ ] 若改布局策略/交互，已登记第5节并覆盖矩阵的断点/密度/多窗口与恢复场景。

<a id="metadata-checks"></a>

### 4.12 名称修正表面

[维护规则](host-adaptation-guide.md#metadata) · [人工场景](feature-preservation.md#metadata)

| 表面 | 入口/模型/binding/getter证据 | 安装与子组降级 | 人工结果链接 |
|---|---|---|---|
| 播放器 | 待填写 | 待填写 | 待填写 |
| 队列 / 历史 | 待填写 | 待填写 | 待填写 |
| 操作表 / 最近搜索 | 待填写 | 待填写 | 待填写 |
| 资料库 | 待填写 | 待填写 | 待填写 |
| 专辑 / 歌单 | 待填写 | 待填写 | 待填写 |
| 艺术家 | 待填写 | 待填写 | 待填写 |
| 主页 / 封面连续性 | 待填写 | 待填写 | 待填写 |

合并行填写时仍分别给每个表面结论，不以一个表面代替另一表面。

- [ ] 实际目录接口/返回、类型别名与附属成员已核对，原地区/原名/ID/缓存语义保持。
- [ ] 未播放歌曲的原生 genre 已进入统一解析；未知/失败结果采用可见触发的有界退避，新身份/地区证据才清负状态，重绑不会清退避。
- [ ] 身份阶段、后续批处理分别限流，同 ID 在途合并，异常/超时释放容量，当前可见优先级和固定地区模式隔离保持。
- [ ] 派生缓存容量、弱身份登记、按类成员缓存、候选集复用及文本扫描角色门禁明确；分批动作仍执行 ID/代次/可见性复查，详细日志门控。
- [ ] 可选子组、已知降级与新回归分别报告，原生点击/跳页/封面路径保留。

<a id="artist-compose-profile-checks"></a>

#### Compose艺人页

[维护规则](host-adaptation-guide.md#artist-compose-profile) · [人工场景](feature-preservation.md#artist-compose-profile)

- [ ] 角色/契约声明与独立安装组对应，部分声明缺失降级，未声明不安装。
- [ ] 方法、Success字段/构造器、views数组getter与类型关系有本构建证据。
- [ ] 状态替换/alias延迟/恢复原名的会话、pending和组关闭范围明确。
- [ ] 已核对实体/shelf/Success 判等是否包含文字；有显示变化时实际行会收到通知，不靠修改原生 ID/revision 强制刷新。
- [ ] 适用时 `metadataPresentation` v1 的快照工厂/读写、实体类型、lockup、Room 准备/分页行及搜索实体字段完整，实例/static、参数位置和类型关系在注册前解析；安装组降级独立报告。
- [ ] 属性 getter 的快照依赖、同别名合并、弱引用、恢复及关闭清理明确；歌曲更新不重复重建整页。
- [ ] 热门歌曲与查看全部的 Compose/StateFlow、分页 Epoxy/PagedList 分支分别闭合实体登记、查询及最终显示；搜索结果与最近搜索分别核对精确行身份。

<a id="settings-data-plugins-checks"></a>

### 4.13 设置、蜂窝、DPI、数据与插件

[维护规则](host-adaptation-guide.md#settings-data-plugins) · [人工场景](feature-preservation.md#settings-data-plugins)

- [ ] 设置分类/去重、原生构造器/callback、结果桥与文件归属匹配当前族。
- [ ] 偏好列表回调或 Compose 参数变化已声明 `settings.members` v1 的 `preferenceItems` / `compose` 完整契约；方法归属、返回、参数和必需角色在注册前解析，旧档案未声明时仍使用已审核签名。
- [ ] 设置入口与 DPI 的组件过滤使用实际宿主；宿主类、方法和资源继续使用现有原包常量。
- [ ] 蜂窝独立资格与原生登录/availability、DPI宿主范围及失败边界正确。
- [ ] 配置/资产/缓存/迁移与ZIP兼容范围明确，格式变化有独立迁移/回退。
- [ ] 普通宿主设置跳过独立及旧内联歌词索引；歌词消费者走独立后台索引，完整解码保留导入/迁移语义。
- [ ] 公共SDK/插件生命周期及通用Hook注册记录保持，业务未带入宿主私有接口。

## 5. 共享实现改动时的补充判定

纯混淆/资源更新在上面核对接缝并执行适用人工场景。只有实际改动下面的共享实现时，才逐行填写维护结论；写明未改及依据，避免把每次版本接入变成重写通用策略。

| 修改域 | 必须登记的维护结论 | 规则与人工结果 |
|---|---|---|
| 歌词结果桥 | 请求/文档/owner边界、索引异常对齐、原生异步合并、安装前附加与空安装抑制、实际容器值驱动的选择/安装确认和去重 | [规则](host-adaptation-guide.md#lyrics-result-submission)；矩阵结果链接 |
| 来源/LLM | 资格/优先级、格式校验、客户端共享编码、独立缓存、取消/迟到及失败恢复 | [规则](host-adaptation-guide.md#llm-lyrics-fallback)；矩阵结果链接 |
| 简体替换和声修复 | 共享歌词效果优化开关/重启门禁、单 parser Hook 两处安装入口、语言门禁、原文快照、专项修复完整性与失败透传 | [规则](host-adaptation-guide.md#simplified-replacement-backgrounds)；矩阵结果链接 |
| 模糊 | 显示提交与四路动画保护、代际、帧驱动、效果/裁剪所有权 | [规则](host-adaptation-guide.md#lyrics-blur)；矩阵结果链接 |
| CJK辉光与末词 | 独立 `cjk` 配置/作用域、原生时序、末词释放与补偿一致性 | [CJK](host-adaptation-guide.md#cjk-animation)；矩阵结果链接 |
| 歌词效果优化 | 共用配置键与重启门禁；替换和声修复使用单解析 Hook，跨行扫光与活动行高亮各按独立契约安装、报告和验收 | [替换和声](host-adaptation-guide.md#simplified-replacement-backgrounds) / [跨行](host-adaptation-guide.md#lyric-wrap-sweep) / [活动行](host-adaptation-guide.md#lyric-background-highlight)；矩阵结果链接 |
| 玻璃输入/动画 | 单一owner、起点归属、方向/边界、共享几何、原生触摸及展开恢复 | [规则](host-adaptation-guide.md#glass-collapse-rendering)；矩阵结果链接 |
| 玻璃材质/上游 | 默认门禁、前缀/承载复用、帧阶段、隐藏资源与当前窗口失效 | [规则](host-adaptation-guide.md#glass-collapse-rendering)；来源pin与人工结果 |
| 预热 | 隐藏事务/同步副作用归属、有界任务与日志阶段，不改导航状态 | [规则](host-adaptation-guide.md#startup-page-prewarm)；矩阵结果链接 |
| 平板布局/视频 | CSS策略、原生角色/模式、QUEUE渐变、首帧失效、尺寸写入者与恢复 | [规则](host-adaptation-guide.md#tablet-layout)；矩阵结果链接 |
| 设置/数据/SDK | 最新草稿保存、返回/失败/取消、schema键与资产兼容、注册记录/公共协议 | [规则](host-adaptation-guide.md#settings-data-plugins)；矩阵结果链接 |

## 6. 安装、生命周期与失败

依据：[安装与所有权](host-adaptation-guide.md#installation)。

- [ ] 资源与Application后安装顺序明确，基础设施失败停止依赖项。
- [ ] 仅改包名场景中，`module.prop` 为 `staticScope=false`、`scope.list` 保留 `com.apple.android.music`，并在 LSPosed 手动选择改名后的宿主。
- [ ] 必需解析先于activate，失败/close后的回调原生透传且状态如实上报。
- [ ] HLE父/子组、后加getter与Hook注册记录同归属，可选缺口不伪装成功。
- [ ] view/config变化先关旧再发新，监听/observer/动画/延迟任务清理完整。
- [ ] 仅恢复仍拥有的最后写入，原生后续值保留，热路径不发现新反射。
- [ ] 网络/文件/字体/TTML仍在既有后台路径，旧异步结果不操作新会话。

## 7. 人工验收、交付与回退

按功能覆盖表跳转[矩阵](feature-preservation.md)执行全部适用场景，结果复制[记录模板](feature-preservation.md#acceptance-record)保存在任务记录；本清单不再维护第二套人工步骤。

| 阶段 | 实际执行内容及结果 | 未覆盖项 / 原因 |
|---|---|---|
| 安装包/源码/资源分析 | 待填写 | 待填写 |
| 所需静态检查 | 实际入口及结果；未执行注明 | 待填写 |
| APK构建 | 目标、实际产物和结果；无构建需求注明 | 待填写 |
| Hook能力状态 | 按功能/子组填写 | 待填写 |
| 使用者人工验收 | 版本、设备、场景结果链接 | 待填写 |
| 已支持构建回归 | 各构建实际范围分别填写 | 待填写 |

- [ ] 全部适用域有明确状态，未执行/不适用/已知降级没有填成通过。
- [ ] 实际宿主包名、TargetBuild 版本、profile 原包及人工结果分别记录，启用资格/运行时状态与证据一致。
- [ ] 配置/数据/插件协议的兼容或迁移范围与交付物相符。
- [ ] 新tuple/能力停用、原生恢复与代码/产物回退方法明确。
- [ ] 通用文档仅更新规则和判据，单次分析/结果留在任务记录，旧冻结基线未无据改写。


