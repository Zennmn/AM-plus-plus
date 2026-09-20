# 手机液态玻璃：Apple Music 新版本适配

本文依据当前 6.5.2 (1586) 实现编写，覆盖底栏、迷你播放器和展开过渡。它是维护流程，不代表任何未取证的新版本已经受支持。

> **2026-09 更新：玻璃已按精确 tuple 支持 6.5.2 (1586) 与 6.5.3 (1599)。** `GlassPolicy.SUPPORTED_BUILDS` 列出全部受支持 tuple，`PhoneLiquidGlassFeature` 与 `PhoneGlassRuntime` 都按该集合判定；6.5.3 的宿主缝重新取证记录见 [6.5.3 适配记录](apple-music-6.5.3-adaptation.md)。几何常量仍是单一来源（NAV 56 / MINI 43 / H 16 / GAP 8 / BOTTOM 16 / scrim 12dp·0.75·9 stops / PANEL_BLUR 4dp），未发现 6.5.3 的 inset 归属或 peek 语义差异。

通用的 APK 取证、设置入口和版本适配流程见 [适配手册](apple-music-target-adaptation.md)，渲染基准和构建命令见 [实现与验收](liquid-glass.md)。

## 1. 哪些需要适配

通常复用 `backdrop` 的渲染器和 `glass` 的材质、动画，重点修改 `app` 的宿主接入。新版即使保留同名资源或同签名方法，也必须重新确认其含义、调用时机和实例归属。不要用扩大模糊半径来掩盖采样、布局或透明度错误。

| 层 | 当前入口 | 新版核对内容 |
| --- | --- | --- |
| 配置与宿主资格 | `EmbeddedBootstrap`、`PhoneLiquidGlassFeature`、`GlassPolicy` | 精确 package/versionName/versionCode、Android 13+、官方手机判定、开关读取 |
| 发现与 Hook | `PhoneLiquidGlassResourceHook`、`PhoneGlassRuntime` | 布局膨胀入口、进度回调、peek setter、实例归属 |
| 宿主视图桥 | `PhoneGlassSession` | 容器类型/层级、菜单、背景源、insets、原生动画层 |
| 模块自有 UI | `GlassHostView`、`GlassNavigation`、`NativeLiquidButton`、`BottomScrim` | 模块资源/Compose 生命周期隔离、输入和坐标映射 |
| 背景录制 | `ViewBackdrop` | 窗口底色加内容、硬件录制、失效更新、不包含玻璃自身 |

`GlassPolicy.SUPPORTED_BUILDS` 是精确 tuple 集合（当前为 `6.5.2 (1586)`、`6.5.3 (1599)`）；`PhoneLiquidGlassFeature.install` 做同一集合的 identity 检查，`PhoneGlassRuntime.discover` 再调用 `GlassPolicy.supports`。bootstrap 支持宿主，不等于玻璃支持该宿主：新增宿主必须同时加入两处并在真机取证。几何常量目前对所有受支持 tuple 共用；只有当取证证明 inset 归属、peek 或层级语义不同时才按 tuple 拆分 profile。

## 2. 先建立新版证据表

保存新版完整 APK/XAPK 和 split 清单、SHA-256、签名、实际安装的版本 tuple、设备/API/显示密度。按通用手册使用 JADX 看调用语义、资源工具看布局，并以原始 DEX descriptor 核对最终 Hook 名称。反编译别名不能直接用于反射。

### 2.1 方法和实例契约

| 1586 的定位 | 当前用途 | 新版必须证明 |
| --- | --- | --- |
| `PlayerActivity$StackedBottomNavigationHolder.c(F)V` | after Hook 获取展开进度 | 参数是否仍是收起 0、展开 1；拖动、点击展开、取消和回收是否都会回调；当前用 declared Activity 字段找宿主，是否仍唯一且正确 |
| `PlayerBottomSheetBehavior.F(IZ)V` | 改写并主动设置 peek 高度 | 方法实际控制收起高度，参数单位/布尔语义一致；当前沿继承链查方法，不能只搜当前类 |
| `BottomSheetBehavior.G:I`、`B:I`、`B()I` | 首次接管时读取状态、收起位置及展开位置 | 1586 原始字段名为 G/B（不是 JADX 的 f45303G/f45298B）；在布局完成且修改 peek 之前读取，拖动/settling 时按当前位置计算进度 |
| Activity 及父类中的 BottomSheetBehavior 类型字段 | 找到当前播放器 Behavior | 当前按类型名包含 `BottomSheetBehavior` 取首个非空字段；新版多个候选时要精确解析，不能接管其他面板 |
| 导航对象 `getMenu()`、`getSelectedItemId()`、`setSelectedItemId(I)` | 读菜单并让原生导航执行选择 | 返回平台 `Menu`、菜单 ID 和选择语义；重复点击、返回栈、深链、禁用/隐藏项仍正常 |
| `ViewGroup.dispatchTouchEvent(MotionEvent)` | 观察 mini root 输入，驱动按压效果 | 仅匹配当前 session 的 miniRoot；不消费原生点击和纵向拖拽 |
| `View.setPadding(IIII)` | 重定向内容根的底部占位 | 仅匹配当前 session 的 source；新版若改用 margin、insets 或 Compose padding，应适配实际路径 |

`F` 不只出现在 Hook 安装中：更新几何和关闭 session 恢复 peek 也调用它。新版改名时必须统一替换这些调用点。若导航改成纯 Compose，不再提供 Menu/原生选择入口，需要新增导航 adapter，不能靠模拟屏幕点击替代。

### 2.2 视图和资源契约

资源通过名称动态解析，不应跨版本复制数值 ID。除了“存在”，还要记录实际类型、父容器、坐标、visibility、alpha、Z、LayoutParams、padding/margin 和窗口 inset。

| 当前名称 | 角色及核对重点 |
| --- | --- |
| layout `bottom_navigation`、`mini_player` | 资源期发现入口；改名或不再 inflate 会导致 session 根本不创建 |
| `bottom_navigation_root_stacked` | 手机堆叠布局标志；缺失时当前直接等待，不会挂载 |
| `bottom_navigation_tabs_frame` | 当前必须为 FrameLayout；承载玻璃、保留原生底部约束 |
| `bottom_navigation` | 隐藏外观但保留原生菜单和导航状态的对象 |
| `navigation_host_group` | ViewGroup 背景采样源；必须覆盖玻璃后的页面且不包含玻璃消费者 |
| `mini_player` / `mini_player_touch_panel` | 当前要求 FrameLayout；触摸观察对象，前者优先 |
| `mini_player_content` | 保持清晰的原生封面、文字、按钮；同步按压形变 |
| `player_sheet_container` | 迷你玻璃实际挂载父级，必须能覆盖展开过程；当前不是 FrameLayout 时回退 mini root，但不能据此判定展开效果兼容 |
| `player_root`、`player_top_shadow`、`background_layers`、`motion_switcher`、`player_fragments_host` | 收起时隐藏旧背景/完整播放器，展开时逐渐交接；新版拆层或改名要重新列出真实层 |
| `navigation_tabs_divider` | 原生分割线隐藏和恢复 |
| dimen `navigation_tabs_height`、`miniplayer_height`、`shadow_height` | 原生 peek 恢复及新 peek 计算；缺失返回 0 可能表现为几何错误而非崩溃 |
| color `color_primary` | 原生强调色；缺失会回退红色，应核对主题结果 |

## 3. 适配顺序

1. **确认 bootstrap 和设置。** 为已取证 tuple 增加支持并核对 SettingsFragment 等通用目标适配。先证明开关能保存、重启后可读取。
2. **建立玻璃专用版本映射。** 如需保留 1586，建议引入按精确 tuple 选择的 profile，集中存放方法/资源/层级差异；让 Feature、Runtime、Session 共用解析结果。不要直接覆盖旧常量，或放行所有更高 versionCode。更新不支持提示和带 `1586` 的诊断文字。
3. **核对资源注册和挂载。** 更新布局发现入口及必要容器定位；若存在多个同名视图，限定在当前 Activity/播放器树。等待视图就绪与确定不支持要能区分，避免永久“等待挂载”。
4. **先验证原生行为。** 确认导航选择、播放器进度、Behavior 和 peek 语义，再启用透明化和背景接管。未知/歧义候选不得靠首个同签名方法猜测。
5. **验证采样与几何。** 按下一节逐页检查；保留原有 LayoutParams 实例及约束，只修改并记录必要字段。
6. **验证展开交接。** 依据真实进度同步玻璃与原生背景，包含反向拖拽及取消。不要仅在点击时启动一个独立定时动画。
7. **补对应回归并验收。** 完成后才声明新 tuple 受支持，同时复验旧版。未适配能力继续准确报告状态。

若只换了混淆名，主要工作是 profile/Hook 映射；若布局容器变化，还要修改 Session；若导航和播放器完全迁入 Compose，属于宿主桥重建，需要重新找到状态/动作入口及采样边界，原有渲染器仍可作为基准。

## 4. 必须保留的视觉与交互契约

### 背景采样和底部占位

- `ViewBackdrop` 先录制窗口底色再绘制页面。透明 Compose 页面如果只录制内容，模糊结果会透出下面清晰的原图，表现为“只有新发现/资料库/搜索没模糊”。检查捕获内容和 alpha，不要先调材质。
- 当前识别可见的 RecyclerView/NestedScrollView/ScrollView/ListView，以及类名为 `androidx.compose.ui.platform.ComposeView` 的场景。新版自定义子类或新的承载方式可能不匹配，必须重新核对；隐藏的缓存列表不能作为当前滚动目标。
- 原生滚动页将避让量加到实际滚动容器，使最后一项可滚到玻璃上方；Compose 页避免给外层采样视口加 padding，但末项是否已由宿主避让仍需实测。
- 当前仅在 source 的 bottomMargin **恰等于 navigationBars bottom inset** 时移除该 margin。不能泛化为清零所有底部 margin，尤其是键盘避让。背景延伸与按钮安全距离分开计算，不能重复加 inset。
- 不采样包含玻璃自身的祖先，否则可能形成反馈；不把收起的完整播放器背景混入页面源。

### 底部渐变与抬高

- 底栏下方还有一条全宽渐变条（`BottomScrim`）：它作为 `bottom_navigation_tabs_frame` 的第一个子 View 铺满整个 frame，玻璃胶囊最后添加，所以它只画在胶囊之下，同样不能进入采样源。
- 效果分两层：blur(12dp) + vibrancy 先整块绘制，再经 `saveLayer` + `DstIn` 用 9 段 smoothstep 遮罩把模糊本身淡出，最后按同一条曲线叠加主题底色 wash（0.75 alpha，浅色 #FAFAFA / 深色 #121212）。只给 wash 做渐变、不遮罩模糊层会得到“上面一条硬边矩形”。新版若换了 frame 容器或原点，先确认 strip 的绘制范围来自 frame 的实际尺寸，再调整曲线和数值。
- 渐变条用 `GlassHostView(bleedDp = 0)`，与胶囊的 32dp 绘制余量区分开：它必须刚好铺满 frame，多一圈 bleed 会让底部出现比屏幕更宽的模糊带。
- 渐变条与底栏玻璃同生命周期：preDraw、activate、foreground、close 一起改 alpha，并对无障碍隐藏（`NO_HIDE_DESCENDANTS`）。关闭功能时要一并移除，只撤 tabs 会留下一条模糊带。
- 抬高由 `GlassPolicy.BOTTOM_DP`（当前 16dp）统一控制，`occupiedHeight = (NAV_HEIGHT_DP + BOTTOM_DP [+ MINI_HEIGHT_DP + GAP_DP]) × density + bottomInset`。frame 高度是 NAV+BOTTOM+inset，peek 额外加 mini 可见时的 `shadow_height`，滚动避让与 underlap 判定复用同一个 occupied 值；这四处必须同源，单独改一处会表现为内容被压住或手势区上方多留白。
- 抬高量取决于 inset 归属：1586 把 navigationBars 底 inset 计入 peek，同时把 source 上等值的 bottomMargin 归零。新版若改用 Compose insets、窗口 inset 或宿主自己让位，必须重新推导 BOTTOM_DP，不能照抄 16dp。
- 面板模糊是本地覆写：vendored 的 `catalog/components/LiquidBottomTabs.kt` 两处 `blur(...)` 与 `NativeLiquidButton` 都读 `GlassPolicy.PANEL_BLUR_DP`（当前 4dp）。`verify-glass-reference.py` 只覆盖 `backdrop/src`，catalog 改动不在哈希清单内，改它不会有脚本提示。

### 绘制边界与输入

- 保留 GlassHostView 的 32dp 绘制余量及祖先 overflow 处理；绘制范围增大不应扩大实际按钮命中区。
- 原生矩形阴影通过 outlineProvider 处理。保留 elevation/Z：之前清零 elevation 曾让底栏看得到却点不到。
- 保留宿主 LayoutParams 类型和约束；用通用参数复制会丢失 ConstraintLayout 底部锚点。
- mini 玻璃观察输入，原生 View 保留播放、下一首、长按和上拉行为。纵向滑动超过 touch slop 后取消模块按压效果，不截断宿主手势。

### 紧凑尺寸与设置入口

- 当前底栏高 56dp，左右边距各 16dp，图标 24dp、文字 11sp；迷你播放器高 43dp，封面和播放/下一首按钮为 32dp。dp 随宿主密度换算，sp 还受字体缩放影响，横向宽度随父容器变化。
- GlassPolicy 统一底栏和 mini 占位高度；底栏 panelHeight 同时控制玻璃面板与内部透镜/内容高度（面板减 8dp）。修改高度时同步检查宿主 FrameLayout、peek、滚动避让和展开起始高度，不能只缩放绘制层。
- prepareMini 修改 mini root/content 的实际高度，并在 mini content 子树内定位 video_surface_container、mini_player_play_btn、mini_player_next_btn 修改宽高。新版必须核对这些 ID、约束、封面内部内容尺寸及按钮触摸行为；改动前保存原生状态，退出时恢复。
- mini 折射范围和强度取 min(24dp, 实际最短边 × 0.375)，面板模糊读 `GlassPolicy.PANEL_BLUR_DP`（当前 4dp）。43dp 面板若沿用 24dp 折射范围，会覆盖中央退化梯度，可能出现细白横线。应按实际尺寸限制折射，展开时也连续更新，不能仅隐藏一条线。
- 设置入口已移除 WIP、开启二次确认和版本号文案，直接保存开关并提示重开应用。这只是展示调整，未放宽 bootstrap/Feature/GlassPolicy 的精确版本、系统及手机资格限制；维护文档仍保留适配版本证据。

### 迷你播放器展开

当前玻璃挂在 `player_sheet_container` 后方，不跟随 mini root 的提前隐藏而消失。`onSlide` 将进度规范到 0..1；`updateTransition` 用 smoothstep 在 0..0.35 交接原生背景，在 0.35..0.6 淡出玻璃，同时高度从 GlassPolicy.MINI_HEIGHT_DP（当前 43dp）向 sheet 高度变化，水平边距从 16dp 收到 0，顶部偏移归零，NativeLiquidButton 圆角随 expansion 变化。

这些区间是当前视觉基准，不是新宿主的 API 契约。新版若进度范围、回调时序、sheet 坐标或原生层淡入时机变化，应先正确映射进度和层，再根据录屏调整交接。必须覆盖点击展开、慢拖、快速反向、取消、完全展开后收回；展开首帧不能先撤掉模糊。仅修改 mini alpha 或把玻璃挂回 mini root 会重现断层。

动态封面 `motion_switcher` 单独在 0.6..0.85 渐入：其子树包含矩形渐变/模糊层，上拉初段可能仍是缩略图尺寸，不能随其他背景提前显示。该灰块修复已由用户真机确认。预绘制中的 outlineProvider 只在引用变化时设置，避免相同值触发持续失效；滚动 View 离开内容源树后恢复底部 padding/clipToPadding 并移除缓存，不因暂时隐藏而移除。

原生层透明度还会随动态封面、普通封面和歌词状态改变。PhoneGlassRuntime 通过 View.setAlpha(float) 仅跟踪当前 session 管理的层，NativeLayerAlpha 保存最新宿主 alpha，并乘以模块过渡系数；模块写入有重入保护。关闭时恢复最新宿主值，不能每帧重新套用激活时的 alpha 快照，否则切换动态封面歌曲后会重新显示旧封面。

### 资源和恢复

模块使用 Xposed 提供的 `moduleApplicationInfo` 加载资源，以及自己的 Recomposer/Compose 所有者；不要改回宿主中按包名 createPackageContext，也不要强转宿主 AndroidX 对象。主题、密度、字体和 RTL 仍从宿主配置同步。

接入异常要关闭 session、释放节点/监听器/组合并恢复原生属性。当前失败 Activity 会停止重试，Hook 安装失败也要求重启宿主；测试修正后须强制停止再启动。新增属性修改要加入恢复记录，避免关闭功能后残留透明度、padding 或轮廓变化。

播放器 Behavior 暂未赋值属于例外：挂载阶段每 50ms 重试一次，最多 20 次，成功或关闭 session 时取消待执行回调；耗尽后才报告失败。原生 peek 高度通过 Hook 持续记录宿主请求，模块自己的设置/恢复调用用重入保护排除。首次没有捕获到请求时，按 1586 stacked holder 的导航 inset + navigation_tabs_height + miniplayer_height 初始化回退值，不以 mini 当时是否可见决定恢复高度。

## 5. 验证工具的边界

`scripts/verify-host-profile.py` 取代了 1586 专用的 `verify-glass-host.py`：它按 `--version-name/--version-code`（或 XAPK 的 `manifest.json`）选择 profile，校验该版本档案里的每个 class/method/field，`--glass` 时再检查三组玻璃方法、继承关系和三个 layout 文件路径。它不验证资源 ID/容器类型、运行时层级、进度语义、背景采样或交互，所以 PASS 只代表静态档案成立，不能替代真机验收。

适配时将脚本扩展成明确选择目标 profile，或增加新版专用校验；保留旧版检查，按实际包结构处理 APK/split，并补充所依赖资源的取证记录。`verify-glass-reference.py` 只证明 vendored 源码与固定上游一致，不证明宿主兼容。

自动检查沿用 [实现与验收中的命令](liquid-glass.md#自动验证)，至少覆盖 `GlassPolicyTest`、`PhoneLiquidGlassStructuralRegressionTest`、`EmbeddedBootstrapTest` 及新增 profile 的匹配/拒绝测试。为版本选择、缺失/歧义符号、进度映射和几何策略补行为测试；源码字符串断言不能替代运行验证。文档修改本身不需要重新构建 APK。

真机至少记录以下结果，并在 PR 列出设备、API、宿主 tuple、主题和录屏：

- 五个导航页面及搜索键盘、空页面、长列表末项的采样与避让。
- 点击、重复点击、拖动切页、返回、深链，选中态与原生页面一致。
- 无 mini/有 mini、切歌、播放暂停、下一首、长按、展开/取消/收回全过程。
- 阴影横条、透镜上沿、底部白带、层级和命中范围。
- 深浅色、前后台、重建/旋转、显示及字体缩放、RTL、手势/三键导航。
- 关闭功能、未支持版本、平板或接入失败时恢复原生，不影响其他能力。
- Glass Lab 硬件 PixelCopy 与帧耗时；Lab 通过不等于宿主注入通过，构建测试 APK 不等于已运行测试。

当前 1586 已获用户真机视觉认可，但完整自动 PixelCopy/性能矩阵仍未完成。1599 的玻璃实现与 1586 共用同一套代码路径，只有宿主缝重新取证；真机验收仍未开始。新版验收必须独立记录，不能继承“已验证”结论。

## 6. 故障定位速查

| 症状 | 优先检查 |
| --- | --- |
| 开启后完全无变化 | bootstrap/Feature/GlassPolicy 三处资格、资源回调、stacked 标志、FrameLayout 类型、模块异常日志；缺失容器可能只停在等待状态 |
| 有玻璃但 ACTIVE 不出现 | backdrop ready、玻璃完成布局、菜单至少两项且当前 selectedId 存在 |
| 底栏跑到上面或遮挡 | LayoutParams 约束、peek setter、原生 dimen、inset 重复计入 |
| 看得到但点不了 | elevation/Z、透明原生层遮挡、输入桥坐标、事件消费 |
| 某些页面像没有模糊 | 采样透明度/窗口底色、source 视口、Compose 识别、隐藏列表误判 |
| 长按上沿截断/横条阴影 | 录制余量、祖先裁剪、旧 outline/分割线，而非先改 shader |
| 缩矮后 mini 中央细白线 | 折射范围是否超过半高、是否随实际尺寸限制；同时排查原生分割线 |
| 手势条上方白带 | source margin/inset、根 padding、背景是否实际覆盖底部 |
| 底部渐变条上沿是硬边或整块矩形 | 模糊层是否被同一曲线遮罩（saveLayer + DstIn）、wash 是否单独渐变、strip 是否被 bleed 撑出屏幕 |
| 抬高后内容被压住或手势区仍留白 | BOTTOM_DP 与 occupiedHeight 的四个消费点是否同源、inset 归属是否已变化 |
| 点 mini 瞬间失去模糊 | 玻璃是否随 mini 隐藏、sheet 父级是否回退、slide 回调和原生背景交接 |
| 展开后残留玻璃/关闭后异常 | slide 终点、动画层映射、close 恢复记录及实例归属 |

优先查看 LSPosed 模块日志，再辅助使用 logcat。该设备曾出现模块异常不在常规 logcat 过滤结果中；“没有 logcat 错误”不是成功证据。

## 7. 新版本记录补充模板

在通用适配记录后补充：

```markdown
### Phone liquid glass
- Exact build / APK hash:
- Slide owner + descriptor / collapsed-expanded range / Activity mapping:
- Peek owner + descriptor / Behavior instance / restore calculation:
- Layout discovery / resource and container changes:
- Menu and native action adapter:
- Backdrop source / opaque base / Compose and native scroll scenes:
- Insets / last-item avoidance / IME:
- Bottom fade / lift: host view, mask curve, blur and wash values, occupied-height consumers:
- Mini sheet parent / native background layers / transition evidence:
- Missing or ambiguous dependencies / native fallback:
- Old-version regression / new-version device matrix:
- Automated checks actually run / remaining gaps:
```
