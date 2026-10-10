# 后续版本适配入口

版本资料集中在 `host-applemusic/src/main/resources/host-profiles/`。每个精确包名/版本名/版本号只有一份 JSON；`index.json` 控制旧引擎的候选顺序。生产入口、玻璃资格、蜂窝资格、静态脚本都读取这里。`host-applemusic/src/test/resources/baseline/` 是 97c4b69 导出的冻结快照，不随普通重构更新。

## 模块边界

| 模块 | 内容 | 禁止依赖 |
|---|---|---|
| core | schema、配置/歌词/字体事务、网络来源、缓存、布局和会话策略 | Android、宿主反射、libxposed、玻璃 |
| host-api | 能力安装结果、配置读取、歌曲身份、可关闭订阅、导航/mini/player 区域 | 混淆成员、反射类型、宿主 AndroidX、渲染器 |
| hook-runtime | libxposed 包装和注册作用域 | app、宿主实现、玻璃 |
| host-applemusic | 两套旧解析引擎、DexKit、native TTML、双栏、设置入口和元数据接入 | app、glass |
| app | APK 入口、装配、存储、业务开关、设置 View、玻璃会话呈现 | 功能/页面自行解析宿主成员 |
| glass / backdrop | 材质呈现和固定上游源码 | Apple Music 版本白名单 |
| plugin-api | 独立插件 SDK，入口/生命周期/Hook/存储接口 | 项目内部模块、libxposed、Apple Music 私有符号 |
| plugin-runtime | ZIP 导入、动态加载、状态与冲突分析 | app、host-applemusic、宿主版本 profile |

`AppleMusicHostFactory` 是 app 装配宿主实现的入口。设置页接收 `SettingsViewBridge`/`SettingsActivityMatcher`，不直接依赖 Apple Music 工厂。自动歌词源、发布器与存储仍由 app 注入，宿主模块不会反向读取 app 的内容管理器。

## 普通混淆变化

1. 先保存实际安装包 SHA-256、二进制 Manifest、全部 DEX/split 和资源证据，区分“类仍存在”和“实际活跃路径”。
2. 新建精确 tuple JSON，填写 `indexed`、`hookTargets`、运行时成员、资源、能力、页面族和 `verification`。完整描述符优先于只记录名字。
3. 新 profile 默认 `ambiguityPolicy=reject-ambiguous`，不得复制旧 `legacyFirstMatchExceptions`。契约和回退实现留在 Kotlin，数据不能执行代码。
4. `productionEnabled` 保持 false，先给新 tuple 增加解析、错误签名、歧义、继承、缓存重校验和类加载器测试。
5. 运行 `python scripts/verify-profile-data.py`、`python scripts/verify-architecture.py` 和 `python scripts/verify-host-profile.py PACKAGE --version-name NAME --version-code CODE --glass`。静态脚本直接核对二进制 Manifest，参数不能伪造宿主版本。APKS/XAPK 收集所有 split DEX 和布局。
6. 执行全模块测试、Lint、构建，再按功能保全矩阵进行旧版本回归和新版本真机验收。用户明确要求适配精确 beta 时，可以在开发分支为用户测试启用该 tuple；运行时验收状态必须独立记录，不能将静态通过写为真机通过。其他版本仍需独立取证。

旧 HLE 引擎的“版本名或版本号匹配”和已审核候选回退暂时保留，外层生产门禁始终精确匹配 tuple。不要直接收紧旧引擎后把未验证的旧功能失效当作正常重构。

## 页面架构变化 / 7.x

`PlayerSurfacePort` 分别提供导航、mini 和播放器区域及导航位置。`LegacyChromeHostBinding` 接现有 Activity；`FragmentPlayerSurfaceAdapter` 按 view 身份和配置 revision 创建/销毁会话。FragmentContainerView 的限制通过 `restrictedPlayerContainer` 明确表达；模块视图挂入允许的 sibling/普通容器，不能直接往受限容器塞 View。

`HostViewSessionController` 关闭旧会话后才发布新会话；旧 owner 的延迟 destroy 不能关闭新 owner。`OwnedHostProperty` 只恢复模块仍拥有的最后写入；原生暂停封面 scale、后续 alpha/translation 写入保留。旧 SONG/QUEUE 语义维持，不以新导航替换播放器状态。

7.0.0-beta/1606 已新增精确生产 profile，为用户测试启用；历史 research 夹具继续留在测试目录。工厂按 `fragment-content` 分派 settings2、双栏与 Fragment 玻璃。新平板使用顶部导航和独立底部 mini 的原生边界，抽屉保留原生交互；玻璃不依赖双栏开关。新布局和渐变字段只从当前 tuple 读取。700 适配过程记录已从当前目录移除，可从 Git 历史查阅；真机验收仍需逐项确认。正式版和其他 beta 必须重新取证。此前实验 APK 没有整体合并。

## 1606 平板双栏与媒体组件

当前实现入口为 `FragmentTabletDualPaneCoordinator` / `FragmentTabletDualPaneSession`，媒体组件位于 `hook/tabletmedia/`。门禁仍是精确宿主 tuple、双栏设置开启、`screenWidthDp >= 600`、横屏及原生 `useNavigationDrawer`；不要把这些改动直接推广到其他版本或手机。

### 最终布局规则

- SONG/QUEUE 控制区使用原布局参数的 `matchConstraintPercentHeight = 0.25f`。这是容器内比例，不是整屏高度；保留原生 metadata 缩进、字体、播放图标尺寸、padding 和封面尺寸。混淆 `androidx.constraintlayout.widget.ConstraintLayout$b` 的高度比例字段为 `S`，guideline begin 为 `a`；修改和恢复原 params，不用通用构造器复制，避免丢失原生约束。
- 原生底部歌词、队列、输出动作保留布局尺寸但隐藏显示/点击，音量条占用原 footer。音量条连同两端图标，总宽度对齐原生进度条的内容边界；优先移动音量行或把空白触摸高度从 44dp 压到 24dp，保留 24dp 图标。音量条触摸区域与播放/进度控件仍保留 4dp 净间距；可用宽度不超过 80dp 或高度不足 24dp 时音量条无法放入。
- 不添加左下角输出按钮；原生输出动作继续隐藏。音量正常中心保持比原 footer 中心高 6dp，删除输出按钮不调整音量及其他组件的位置。
- 右下歌词/队列代理使用原生 drawable、selector、tint 和状态样式，44dp 点击区域、8dp 间隔、底部 12dp。队列点击/长按交回原生控件；歌词点击执行双栏展开/关闭。翻译按钮保留原生；不新增随机/循环按钮。原生三颗播放图标保持默认大小，仅将水波纹半径设为图标最大半径外加 8dp，并使用白色 20% 不透明度；关闭组件恢复原 drawable。
- 原生 pane `onViewCreated` 就准备比例、图标和隐藏 footer；每次 update 在切页快速返回之前再次隐藏所有保留 pane 的 footer，避免首次打开延后生效或 SONG/QUEUE 交叉淡入时闪出旧按钮。歌词渐变视口不额外预留底部按钮高度。

媒体资源从打包的 `tablet-media/` 读取，APK 内资源读取由 `AppleMusicHostProfiles.openTabletMediaAsset` 处理；来源见根目录 `THIRD_PARTY_NOTICES.md`。profile 中保留了导入播放模式代码的 `tablet-playback-*` 调用契约，不能据此认定当前 UI 启用了随机/循环按钮。`invocationOnly` 允许抽象接口方法参与调用验证；Hook 目标仍不能是抽象、bridge 或 synthetic 方法。

### 当前歌曲歌词可用性

`FragmentTabletLyricsAvailability` 每次读取当前歌曲并使用原生规则：`i1.i(currentItem) && (fc.d.c(context) || currentItem.hasOfflineLyrics())`。`i1.i` 包含原生订阅资格及普通/自定义歌词判断，`fc.d.c` 使用原生网络许可。不要只读取保留控件的 `isEnabled`、右栏是否可见或旧歌词文本：原生 binding 的 forced-open 标志可在换歌后继续保持启用，造成无歌词歌曲显示上一首歌词。

| 原生契约 | 完整成员与类型 |
|---|---|
| 当前歌曲 | `com.apple.android.music.player.fragment.PlayerMainFragment.M : com.apple.android.music.model.BaseContentItem`，非静态；值需实现 `PlaybackItem` |
| 歌词资格 | `com.apple.android.music.player.i1.i(com.apple.android.music.model.PlaybackItem) : boolean`，静态 |
| 网络许可 | `fc.d.c(android.content.Context) : boolean`，静态 |
| 离线歌词 | `com.apple.android.music.model.PlaybackItem.hasOfflineLyrics() : boolean`，非静态接口调用 |

这四项目前在 `FragmentTabletLyricsAvailability.native` 中解析和校验类型，尚未纳入 JSON 的 `indexed` 契约；适配下一 tuple 时必须单独核对实际 DEX，不能仅复制 profile 或把 profile 静态通过当作这些成员已验证。当前歌曲缺失或查询失败时代理禁用并关闭右栏；可用性恢复只重新打开自动关闭的右栏，手动关闭的状态保持。

### 封面与下半区动画

关闭歌词时，SONG 和 QUEUE 共用居中的左 host，保留列宽。歌词开关和自动可用性变化使用 280ms 平移/淡入淡出；切换 SONG/QUEUE 不清空手动关闭状态或重置居中位置。

播放器展开/收起时，下半区保持最终横坐标，继续使用原生显现过程；只有封面按原生 slide 移动。`TabletLyricsPaneMotion` 令 `C = centeredOffset * 歌词关闭动画比例`，左 host 保持原生 X 加 `C`，选中的封面在原生 X 上补偿 `-C * (1 - slide)`，所以封面净新增位移为 `C * slide`。不要用 95% 阈值清零位移，也不要把整个 host 乘 slide，否则分别产生封面横跳和下半区从左侧滑入。

封面沿用原生 selector `PlayerMainFragment.f1(PlayerMainFragment) : android.view.View`（静态）。在 `PlayerMainFragment$i.d(float) : void` 的前置回调释放上一帧 X 补偿，原生计算完后再合成；owner 字段是 `h : PlayerMainFragment`。保留原生 scale、Y、暂停比例和视频内容，重复 pre-draw 不累计补偿，换 pane 或释放会话时恢复模块仍拥有的原生 X。

相关自动回归包括 `TabletSongLayoutTest`、`TabletNativeControlRowsTest`、`TabletComponentGeometryTest`、`TabletComponentStartupTest`、`TabletNativeActionStyleTest`、`TabletTransportRippleTest`、`FragmentTabletLyricsAvailabilityTest` 和 `TabletLyricsPaneMotionTest`。覆盖混淆 params、保留 pane 首帧、短窗口 footer、旧歌词/旧按钮、SONG/QUEUE 切换、双向/反向拖动和补偿恢复；完整真机场景见 [功能保全与验收矩阵](feature-preservation.md#1606-平板双栏补充验收)，自动通过不能替代该验收。

## 热路径与安装

冷启动顺序维持：精确版本 → 配置迁移/绑定 → DPI → 资源回调 → Application 后功能 → 设置入口。资源基础设施失败停止后续安装；目标缺失只降级对应能力。

Hook 注册先处于 preparing，必要目标全部成功后 activate；失败 close 后已注册回调变为原生透传。不依赖框架提供可靠 unhook。原生歌词缓存持有不透明句柄，JavaCPP 地址/liveness 与 Adam ID 校验留在宿主适配器，进入 I2（旧版）或 w2（1606）前才解包。

Chrome ID、字段、方法在绑定时缓存；布局变化显式失效视图缓存。slide/alpha 回调不扫描 DEX 或发现反射成员。文件、网络、TTML parse 继续在后台。字体 Hook 已安装和字体实际加载分别报告。

## 验证与回滚

`./gradlew test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :host-applemusic:lintDebug :app:assembleRelease`，CI 另运行架构/profile/玻璃参考源码校验。配置 schema 15、键、文件 ID、ZIP、目录、DB/cache namespace 不变，代码阶段回滚无需反向数据迁移。

源码契约覆盖拆分后整个职责组件；实际行为测试仍执行原夹具。不可通过删断言、更新冻结快照或加入猜测候选消除失败。
