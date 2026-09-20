# Apple Music 新版本适配手册

本文面向 AM++ 维护者，适用于任意 Apple Music 新版本。目标是让既有能力在宿主内部实现变化后继续工作，而不是借版本升级顺手改变产品行为。

本版依据 6.5.1 (1583) → 6.5.2 (1586) → **6.5.3 (1599)** 三轮真实适配重写，重点补上了 6.5.3 暴露的三类坑：R8 名字复用、契约里写死库类名、以及模块自身在安装期的失败放大。文中的案例都来自实测日志与字节码，不是推测。

Apple Music 的类名、方法名、字段名和资源 ID 都属于私有实现。**本文定义的是适配方法和代码契约；每次适配仍必须针对实际 APK 重新取证、解析、测试和验收。** 文中出现的具体符号名只是范例，不能当作新版本的答案。

## 0. 三件产物与一条底线

每次适配必须产出三样东西：

1. **证据档案**：新版本 APK/XAPK 的清单、SHA-256、签名、实际安装的 tuple、DEX 规模、逐符号判定结果（含被拒绝的候选与原因）。放在仓库外（例如 `%TEMP%\ampp-<version>-analysis`），不提交二进制或反编译产物。
2. **新 profile**：`AppleMusicHookProfiles` 的新 hook profile + `TargetSymbols` 的新符号档案。旧档案字节不动。
3. **适配记录**：`docs/apple-music-<version>-adaptation.md`，含版本证据、符号变更表、行为校验、未验证项。

底线：**未识别版本 fail-closed**。宁可报告「不支持 / DEGRADED」，也不要按旧名字装上去赌一把。任何「名字对上了就装」的做法在本项目已经造成过两次线上级别的故障（见 §4）。

## 1. 适配的基本契约

### 1.1 适配和行为变更是两件事

版本适配只解决「同一能力在新宿主中如何找到并安装」。以下语义默认保持不变：

- 功能开关的默认值、资格条件和 `FeatureHealth` 语义；
- Android API 门槛、官方平板/手机和横竖屏判定；
- 资源注册、`Application.onCreate` 安装、异常隔离和健康上报顺序；
- Hook 的 before/after 时机、参数替换、返回值覆盖和调用顺序；
- 双栏播放器的布局、Fragment transaction、折叠/展开和边界补偿；
- 歌词模糊的焦点、滚动暂停、跨歌曲清理和恢复时序；
- Editorial Video、歌词字体、自定义歌词和当前歌曲身份的范围；
- 配置 schema 的键名、默认值、编码、迁移和双进程边界。

如果新版迫使其中某项语义发生变化，应先写独立的行为变更记录，再修改 adapter；不要把行为变化伪装成 profile 更新。真机上看到的「新交互」先按「适配不改行为」处理，另立需求。

### 1.2 四层边界

| 层 | 内容 | 适配手段 |
| --- | --- | --- |
| 配置与资格 | `EmbeddedBootstrap`、`GlassPolicy`、`PhoneLiquidGlassFeature` | 精确 tuple 集合，逐版本加入 |
| 发现与 Hook | `AppleMusicHookProfiles`（hook 点）、`TargetSymbols`（符号档案） | 逐符号重新取证后写新档案 |
| 宿主视图桥 | `PhoneGlassSession`、`HleMetadataSurfaceBridge` | 容器类型/层级/资源 id/回调语义复核 |
| 模块自有 UI | `GlassHostView`、`GlassNavigation`、`NativeLiquidButton`、`BottomScrim` | 尽量不动；跨版本变化只应发生在接入层 |

原则：能复用渲染与材质就不动，改动集中在接入层与档案层。

### 1.3 失败必须可见且可降级

每个 hook 点、每个能力独立报告状态。解析失败要带上「尝试过哪些目标、各自为什么被拒」，而不是一句 `unresolved`。允许部分能力 DEGRADED，不允许静默失效——6.5.3 首轮就是这样：状态显示 ACTIVE，但目录查询全挂，用户看到的是「歌名没修正」。

### 1.4 逐符号证据，不做猜测

每个符号的判定只有三种结果：Found（附证据）、Missing（附已试列表）、Ambiguous（附候选与歧义原因）。`Ambiguous` 不允许「取第一个」，要么补证据收敛，要么保持未安装。

## 2. 开始之前：模块自身的运行时前提
### 1.5 运行架构：两阶段安装

推荐保持以下时序：

```text
HookEntry.onPackageReady
    ├─ 包名、框架 API、remote capability 门控
    └─ FeatureInstallation
         ├─ 资源期回调注册
         ├─ LayoutInflater hook
         └─ Application.onCreate before-hook
                ├─ 宿主绑定和配置迁移
                ├─ 资源/布局注册
                └─ after-hook 安装目标能力与设置桥接
```

资源与布局必须在宿主 inflate 目标布局之前注册；依赖目标类实例或版本 profile 的 hook 不要提前到资源阶段。每个阶段都要幂等，失败时不能发布半初始化的 session（见 §2.3）。

### 1.6 Feature 与目标能力的边界

适配层按「语义能力」组织，不按「某个类里有几个方法」组织：

| 能力 | 适配层职责 | Feature 看到的结果 |
| --- | --- | --- |
| 双栏播放器 | root、holder、fragment、边界与原生行为协调 | 可展开/收回的双栏播放器 |
| Editorial Video | 只在规定设备条件下抑制目标 URL | 其余场景保持原生 |
| 双向歌词模糊 | 建立歌词 session、焦点与滚动状态 | 当前行清晰，其他行按距离模糊 |
| 歌词字体 | 定位目标 RecyclerView/文本渲染路径 | 只影响歌词字体 |
| 自定义歌词 | 定位歌曲身份与歌词加载入口 | 原始值缺失时 fail-open |
| 当前歌曲身份 | 提供稳定的歌曲 ID/对象快照 | 供多个 feature 复用 |
| 媒体库刷新 | 定位 MediaLibrary singleton、update/ready 与请求入口 | 只影响手动刷新/目录补全 |
| 标题修正 | 定位标题转换、缓存与 miss 回填入口 | 候选缺失时保留宿主标题 |
| 目录语言 | 定位 storefront、Accept-Language 与 catalog map | 只重写语言字段，保留请求返回契约 |
| 设置页 | 把模块设置注入宿主原生设置页 | 入口可见且可持久化 |

每个能力都要能独立降级——这也是 §2.4 解析顺序的要求。


这一节是 6.5.3 首轮故障的教训：能力「装不上」经常不是宿主变了，而是模块自己在安装期就崩了。

### 2.1 模块以 `extractNativeLibs=false` 打包

`libdexkit.so` 留在模块 APK 内，安装后的 `nativeLibraryDir` 可能是空字符串，嵌入宿主下还可能是 **null**。任何依赖 native 的组件都必须能自解包：

- 用 `getModuleApplicationInfo()?.nativeLibraryDir.orEmpty()` 兜住 null；注意 `orEmpty()` 兜不住「目录存在但为空」，两种都要判；
- 加载顺序：已释放到磁盘的 `.so` → `System.loadLibrary("dexkit")` → 按 `Build.SUPPORTED_ABIS` 从模块 APK 解包 `lib/<abi>/libdexkit.so` 到私有 cache 再 `System.load`；
- 全部失败时把每一步的原因聚合抛错，不要把最后一个异常当唯一原因。

实现见 `AppleMusicDexKitResolver.loadExtractedDexKit / loadPackagedDexKit`；模块 APK 路径由 `HleMetadataRuntime` 从 `sourceDir` / `publicSourceDir` / `splitSourceDirs` 提供。

### 2.2 嵌入/管理器内嵌环境下 ApplicationInfo 可能缺失

NPatch 等嵌入场景里 `module.getModuleApplicationInfo()` 可能返回 null，此时 `moduleApkPaths` 会为空。代码必须在这种输入下仍然给出可控的失败信息（`DexKit native library unavailable: no native library directory; …`），而不是 NPE。

### 2.3 句柄只在安装成功后发布

**`install()` 成功返回后才给 `surfaceBridge` 之类的字段赋值。** 否则安装中途抛异常时，字段已赋值、内部 `lateinit` 还没初始化，早已注册好的宿主回调会持续抛 `UninitializedPropertyAccessException`——6.5.3 首轮就是这样把 363 条异常刷进日志、把整个运行时拖成 DEGRADED 的。

规则：长生命周期回调访问的句柄，要么在 `install()` 返回后赋值，要么在调用点用 `::field.isInitialized` 守卫。

### 2.4 解析顺序决定成败

applier / coordinator 构造时**最先解析的那个 hook 点**会决定整体成败。6.5.3 首轮里，`AppleInAppMetadataApplier` 先解析 `IN_APP_QUEUE_ADAPTER_SUBMIT`，而该目标在 1599 名字被复用，于是失败并转入 DexKit，最终拖垮整个元数据运行时。

规则：让解析顺序「先独立后依赖」，单个 hook 失败只降级对应子面；发现「某个 hook 失败导致整块能力不可用」时，按缺陷处理，而不是在档案里绕过。

## 3. 证据档案：先取证，再改代码

### 3.1 输入包清单

- 记录 XAPK/APK 文件名、SHA-256、签名证书摘要、`minSdk/targetSdk/compileSdk`、split 集合。
- **XAPK 不一定有 `manifest.json`**（6.5.3 样本就没有），此时版本 tuple 必须由命令行显式声明，脚本与记录都要写清楚。
- base APK 名不固定：6.5.2 样本是 `base.apk`，6.5.3 样本是 `com.apple.android.music.apk`。
- DEX 有多份（`classes.dex` … `classes4.dex`）。用 descriptor 字符串在 APK 里搜到的位置**可能是引用而不是定义**；判断「类定义在哪个 dex」要用反汇编输出里的 `Class descriptor  : 'Lx;'` 定位。
- 同时记录旧版本信息用于差分（6.5.2 37105 类 / 6.5.3 37519 类，共享 26713）。

### 3.2 安装后的复核

真机覆盖安装后先确认 tuple 与来源一致，再谈功能：

```powershell
adb shell su -c "dumpsys package com.apple.android.music | grep -E 'versionName=|versionCode='"
```

签名与版本 tuple 不一致时先停下来：可能装的是别的渠道包，全部证据作废。

### 3.3 证据分级

| 级别 | 含义 | 能否直接写进档案 |
| --- | --- | --- |
| 名称命中 | 只确认字符串存在 | 不可以 |
| 结构证据 | 方法/字段骨架、参数类型、返回值、父类、调用点一致 | 可以，附对比明细 |
| 字节码证据 | 助记符序列/常量/调用目标一致 | 可以，附对比方法 |
| 运行时证据 | 真机日志证明该 hook 生效 | 作为验收依据，写入适配记录 |

反编译工具给出的别名不能直接用于反射；必须以原始 DEX descriptor 为准。

### 3.4 字节码级取证（本轮新增手段）

设备自带 `dexdump`（`/apex/com.android.art/bin/dexdump`），不需要本地 jadx。流程：

1. 从 base APK 抽出含目标类的 dex（可能不是 `classes.dex`）：

```powershell
python -c "import zipfile; z = zipfile.ZipFile('base.apk'); [open(n, 'wb').write(z.read(n)) for n in z.namelist() if n.endswith('.dex')]"
```

2. 推上设备并反汇编，再截取目标类那一段：

```powershell
adb push classes2.dex /data/local/tmp/d.dex
adb shell su -c "dexdump -d /data/local/tmp/d.dex > /data/local/tmp/d.txt"
# 截取建议写成脚本再 push（PowerShell 下嵌套引号极易出错）：
#   awk '/Class descriptor/ { keep = (index($0, \"Ls8/F;\") > 0) } keep' d.txt > seg.txt
adb push seg.sh /data/local/tmp/seg.sh
adb shell su -c "sh /data/local/tmp/seg.sh"
adb pull /data/local/tmp/seg.txt .
```

3. 比对方式（本项目实际用的两种，够用且稳）：

- **助记符序列**：从 `insns size` 之后逐行取 `|NNNN: mnemonic` 的 mnemonic 列表，逐条比较。6.5.2 的 `s8.F#B` 与 6.5.3 的 `u8.E#v` 是 **353 条完全相同**；同名 `u8.E#B` 是另一段 352 条。
- **类的字段骨架**：实例字段名与类型逐项对应（`u8.E` 的 24 个字段与 `s8.F` 一一对应，含 storefront 字段 `s`）。

### 3.5 产物留存

反编译产物、清单 JSON、对比脚本放仓库外（`%TEMP%\`），仓库里只提交结论与可复跑的校验脚本。例外：JVM 测试用的宿主替身 fixture（见 §15.3）要进仓库。

## 4. 名字复用：本项目的头号故障源

R8 在版本间**复用短名字**。同一个名字在新宿主里可能指向完全不同的类或方法。「名字存在」什么都证明不了。

### 4.1 四种复用（类名 / 方法名 / 库类名 / 整包家族位移）

1. **类名复用**：`Y8.a` 在 6.5.2 是队列适配器，在 1599 是一个无关的 protobuf 模式映射辅助类。→ 必须比骨架＋运行时成员名。
2. **方法名复用**：`u8.E#B` 在 1599 存在，但参数是 `(Continuation, String, Map)`，不是目录查询要求的 `(String, Map, Continuation)`。→ 必须比形状，必要时比字节码。
3. **库类名混淆**：`androidx.lifecycle.LiveData` 在 6.5.2 与 6.5.3 里都叫 `androidx.lifecycle.G`（`MutableLiveData` 却保留原名）。→ 契约里不能写死库类名。
4. **整包家族位移**：6.5.3 把内容 API 的整个包 `u8/a..u8/n`（14 个类）搬到 `w8/a..w8/n`，字母序与形状逐项对应，而新的 `u8` 包换成了另一批类。→ 别只比对单个类；先按包比家族规模与形状顺序，命中后逐类验证。

识别家族位移的最快做法：在上一版的清单里取目标类所在包的**全部类名**，与新版同名包及其邻包比对。若新版某包的类数、类名字母序、每个类的“方法数/字段数/父类”三元组都与旧包一一对应，基本可以判定整包改名；随后用字节码确认其中真正 Hook 的那个方法（§3.4）。

### 4.2 三条断言（写进校验脚本）

对「按形状找方法」的缝，脚本必须同时断言：

1. 该形状在类里**唯一**（多于一个即歧义，等于零即缺证据）；
2. 新版本里，**旧首选名不再占用这个形状**（否则已验证改名永远不会被用到，说明判断过时）；
3. 档案钉的名字**通过了契约校验**（不能只断言「方法存在」）。

### 4.3 已验证改名表

首选名依然按版本顺序排列；只有当首选名**未通过形状/契约校验**时，才允许使用「已验证改名」：

```kotlin
// AppleCatalogQueryMethod.VERIFIED_RENAMES
"s8.F" to mapOf("B" to "x"),   // 6.5.2 的另一份 R8 变体：查询保留在 x
"u8.E" to mapOf("B" to "v"),   // 6.5.3：查询连同 353 条指令体搬到 v
```

约束：改名必须逐条有证据；歧义（同形状多个候选）与未知宿主一律 fail-closed，不允许「猜一个」。表只按（宿主类, 首选名）索引，不做跨类搜索。

### 4.4 各版本的实际案例

| 缝 | 6.5.2 | 6.5.3 | 判定依据 |
| --- | --- | --- | --- |
| 队列适配器 | `Y8.a` | `a9.a` | 父类、8 方法、10 字段、运行时成员名逐项一致；1599 的 `Y8.a` 已是别的类 |
| 目录直连查询 | `s8.F#B` | `u8.E#v` | 353 条助记符逐条相同；同名 `u8.E#B` 是另一段 352 条 |
| Compose observeAsState | `C1.w#e` | `Dg.c#l` | 各代唯一该形状的静态方法；参数里的 LiveData 两代都是 `androidx.lifecycle.G` |
| 策略单例 | `z0.s0` | `z0.p0` | 字节码相同（`const/4 v0,#0; return v0`），其余同名候选体不同 |
| 内容 HTTP 本地化拦截器 | `u8.a#a` | `w8.a#a` | 整包家族 `u8/a..u8/n` → `w8/a..w8/n`，两代方法体同为 19 条助记符逐条相同；1599 的 `u8.a` 已是模型类 |
| MediaApi 参数本地化缝 | `s8.F#c0` | `u8.E#c0` | 与 `MEDIA_API_STOREFRONT_FIELD`、目录直连查询同属一个类；设备日志证实它接收模块的目录参数 Map |

## 5. Version profile 设计

### 5.1 profile 是精确构建知识

- `AppleMusicHookProfile`：`id`、`versionName`、`versionCodes`（集合）、`hookTargets`。
- `TargetSymbols`：`id`、`versionName`、`versionCodes`、逐 hook 点的 symbol id。
- 未列入新 profile 的 hook 点回落到共享表/结构回退，若也失败则保持未安装并报 DEGRADED。

### 5.2 添加 profile 的规则

- 只写有证据的条目；证据不足时**宁可不钉**（6.5.3 的 `PLAYER_METADATA_HUB` 就故意留空，走 exact-preferred 的结构回退）。
- 每条目标尽量写全 `className / methodName / parameterCount / parameterTypeNames / returnTypeName / isStatic / runtimeMemberNames`，让契约能真正收紧。
- 注释里写清「证据是什么」，例如「骨架 0.97 唯一匹配 + 调用点参数类型变化」，便于下一个人复核。
- 旧版本档案不改字节；新版本走新档案，选择逻辑用 tuple 精确匹配。

### 5.3 profile 与通用符号的关系

通用表（多版本共享的稳定符号）只有在确认整族都变时才动；单版本差异写进该版本 profile。

## 6. 目标符号解析模型

### 6.1 每个符号独立解析

不共享解析结果、不因为一个符号成功就推断同类符号。解析结果按 hook 点缓存。

### 6.2 解析层级

| 层级 | 手段 | 失败后的行为 |
| --- | --- | --- |
| 1 | 精确 profile 目标（含契约校验） | 进入下一层 |
| 2 | 兼容候选（旧版本目标，同样过契约） | 进入下一层 |
| 3 | DexKit 结构修复（以历史基线为种子） | 进入下一层 |
| 4 | 保持未安装 | 上报 DEGRADED 并写明已试原因 |

### 6.3 契约要按结构写，别写死库类名

`AppleMusicHookContracts` 里的参数/返回类型校验常见写法是比对类名，但**库类也会被混淆**。正确做法二选一：

- 接受一组已验证类型名：`RequireParameterType(0, "androidx.lifecycle.LiveData", "androidx.lifecycle.G")`；
- 或按结构判定（父类链、方法集、字段集），例如「这个参数类型必须声明 `getValue` / `observe` / `observeForever`」。

6.5.2 与 6.5.3 上 `COMPOSE_OBSERVE_AS_STATE` 都因为写死了 `androidx.lifecycle.LiveData` 而拒绝正确目标——这比 6.5.3 更早，值得引以为戒。

### 6.4 解析诊断

失败信息必须包含 hook 点、版本 displayName、尝试过的每个目标的拒绝原因（`class` / `signature` / `contract:<原因>` / `ambiguous(n)`），并在日志里保留至少一条可检索的原文，方便与真机日志对照。

## 7. Hook 时序与原生行为所有权

### 7.1 资源期与实例期分离

资源相关的 hook（布局膨胀、资源 id 查询）在资源期完成并缓存结果；实例期只做读值与回调。6.5.3 的玻璃会话把成功的资源 id 缓存起来，避免每次重建都重新查询。

### 7.2 原生 holder 与动画归属

原生动画层、holder 的位移与透明度仍由宿主负责，模块只做投影与材质，不接管原生动画时间线。跨版本复核点：holder 类的平移入口方法签名、peek setter、inset 归属。

## 8. 手机液态玻璃

逐项依赖见 [`docs/liquid-glass-adaptation.md`](liquid-glass-adaptation.md)，这里只列新版本必须核对的项：

- **资格**：`GlassPolicy.SUPPORTED_BUILDS` 与 `PhoneLiquidGlassFeature.install` 必须同时加入新 tuple；bootstrap 支持宿主 ≠ 玻璃支持宿主。
- **资源与布局**：`res/layout/bottom_navigation.xml`、`mini_player.xml`、`activity_main_content_layout.xml`；`navigation_host_group`、`bottom_navigation_tabs_frame`、`navigation_tabs_height`、`miniplayer_height`、`motion_*` 系列。
- **几何常量**：NAV 56 / MINI 43 / H 16 / GAP 8 / BOTTOM 16 / scrim 12dp·0.75·9 stops / PANEL_BLUR 4dp 保持单一来源；只有取证证明 inset 归属或 peek 语义不同，才按 tuple 拆 profile 差异。
- **真机判据**：底栏胶囊、迷你播放器、展开过渡、底部淡出四项分别截图/录屏确认；触摸与滚动避让要单独试。

## 9. 双栏播放器适配方法

6.5.3 复核结论：`com.apple.android.music.common.behavior.StaticCollapsedBottomSheetBehavior` 的类名、父类与方法签名一致，因此 guard 按证据放开，而不是按假设。

### 9.1 先确认目标 View tree

在宿主里定位真实容器：Activity 根、内容区、二级栏容器、分隔线。不要用 id 猜层级，用 dump 出来的实际树。

### 9.2 布局和边界的两个量

分隔位置与二级栏宽度是两个独立量，各自有上下限；平板与手机分别校验。折叠/展开由宿主 Behavior 驱动，模块只补边界。

### 9.3 资源和布局的可验证约束

新增布局 id 必须能在宿主资源里解析成功；解析失败要能降级回原生布局而不是崩。

## 10. 触摸 interception Guard

### 10.1 为什么不能按 Behavior 类全局放行

只按「父类是不是 BottomSheetBehavior」放行，会把模块自己的手势也吃掉。放行条件必须绑定到具体页面与具体 view。

### 10.2 最小 bypass 条件

按证据复核三件事：Behavior 的声明类与父类、拦截方法的完整签名、以及调用点所在的页面容器。6.5.3 三项全部一致，才把 guard 从「6.5.2 专属」放开到 tuple 集合；任何一项不一致就保持旧版本专属，并在新版本上报 DEGRADED，而不是静默安装。

## 11. 设置页、配置 schema 和双进程边界

### 11.1 不要假设模块有 launcher Activity

嵌入模式下设置入口寄生在宿主里；新版本要复核入口注入点（通常是宿主设置/关于页面）与开关读取路径，确认「看不到入口」不等于「功能没装」。

### 11.2 配置 schema 是单一事实来源

键名、默认值、编码、迁移规则不因版本变化而变。新增配置项要带宽窄的迁移路径，并保证双进程（宿主进程 / 模块进程）读到同一份数据。

## 12. 其他目标能力的适配要点

### 12.1 歌词与字体

复核歌词视图容器的类与层级、模糊层采样源、字体覆盖点（TextView/Typeface 替换）与 CJK 卡拉OK动画 owner。6.5.3 的 `player.z → player.A`（60 方法 / 55 字段一致）就是靠骨架比对确认的。

### 12.2 当前歌曲身份与显示修正

身份缝（MediaSession / 通知 / 播放元数据）与显示缝（列表 item、容器）分开复核。改名后要真机确认「同一首歌在不同页面拿到同一个 id」。

### 12.3 Editorial Video

复核视频 URL 解析方法与参数类型（6.5.3 的 `player.f1#e(Song,F,[Flavor]):String` 与 1586 一致）。

### 12.4 媒体库刷新（Compose 局部刷新）

两处容易漏：一是 `observeAsState` 这类缝的参数类型是**混淆后的库类**（见 §6.3）；二是 `NeverEqualPolicy` 之类的单例要靠字节码确认（同名类可能只是名字像）。

### 12.5 标题修正与目录缓存

先看日志里有没有 `Apple 内部目录直连查询失败`，再看 `原名候选` 是否带真实 `value`/`isrc`。缓存数据库与缓存命名空间不因版本变化而重置；离线时功能应降级而不是报错。

### 12.6 目录语言与目录直连查询

目录查询缝在后端的解析顺序里排得很前，一旦钉错名字，表现是「能力 ACTIVE、界面无变化」。适配后必须用一次真实解析（例如播放一首 CJK 曲目并观察 `原名候选`）确认，而不是只看 hook 安装日志。

## 13. 静态验证脚本（与档案同源）

### 13.1 用法

```powershell
python scripts/verify-host-profile.py "apple-music-6-5-3.xapk" --version-name 6.5.3 --version-code 1599 --glass
python scripts/verify-host-profile.py "Apple+Music_6.5.2_APKPure.xapk" --glass
```

XAPK 带 `manifest.json` 时 tuple 可省略；不带时必须命令行声明。脚本只读、不执行 APK 代码。

### 13.2 断言类型

| 断言 | 位置 | 说明 |
| --- | --- | --- |
| 类存在 | `PROFILES[v][\"methods\"]` 的 key | 含继承查找 |
| 方法签名 | 同上，完整 descriptor | 逐字符比对，防同名异形 |
| 字段存在 | `PROFILES[v][\"fields\"]` | runtime member 名 |
| 形状唯一 + 首选名占用 | `PROFILES[v][\"catalog_query\"]` | §4.2 的两条名字复用断言 |
| 别名表面 | `ALIASED_TYPES` | 混淆库类必须仍暴露预期方法 |
| 玻璃缝与布局 | `GLASS_METHODS` / `LAYOUTS`（`--glass`） | 三份布局 + 三个方法签名 |

当前基线：6.5.3 = 51 checks / 0 failures，6.5.2 = 37 checks / 0 failures（断言随每轮改名缝增加）。

### 13.3 何时新增断言

只要新增了一条档案目标、或修掉了一次「名字对不上」的故障，就同步加断言——否则下一个人只能靠真机撞。反过来说：任何写进适配记录的结论，都应该能被这个脚本重新验证。

## 14. 真机验收与日志仲裁

### 14.1 安装与 tuple 复核

覆盖安装 Release APK 后先跑 §3.2 的 tuple 命令，确认宿主与模块版本都对得上。

### 14.2 日志位置

模块日志走 Xposed 通道，**不在 logcat**：

```powershell
adb shell su -c "ls -t /data/adb/lspd/log/modules_*.log | head -1"
adb exec-out su -c "cat /data/adb/lspd/log/modules_XXXX.log" | Set-Content $env:TEMP\ampp.log -Encoding utf8
Select-String -Path $env:TEMP\ampp.log -Pattern "目录直连查询失败|原名候选|title_correction|安装失败"
```

`verbose_*.log` 是框架级详细日志，排「hook 为什么没生效」时用；模块自己的健康状态在 `modules_*.log` 里以 `能力名: ACTIVE/DEGRADED/FAILED` 形式出现。

### 14.3 判据与前后对照

不要用「看起来好点了」当结论，用可数的原文对照：

| 判据 | 期望 |
| --- | --- |
| `title_correction: ACTIVE` / `DEGRADED` | 与预期一致，DEGRADED 必须能说清子面 |
| `Apple 内部目录直连查询失败` | 0 次（修复前 10 次 → 修复后 0 次是 6.5.3 的实例） |
| `Apple 内部原名候选 … value=…, isrc=…` | 带真实值（修复前全为 `null/null, isrc=null`） |
| `Apple Music App 内元数据已覆盖` | 计数与标题语言符合预期 |
| `Hook 安装失败` / `NoSuchMethodException` | 0 次 |

对照方法：同一台设备、同一会话、冷启动后比较「修复前 APK」与「修复后 APK」的同类计数，把两段日志都留档。

### 14.4 冷启动与清理

改动 hook 后必须强制停止宿主再启动（`am force-stop` + `monkey -p … -c android.intent.category.LAUNCHER 1`），否则读到的是旧进程的注册结果。日志文件按启动轮转，注意取最新一份。

## 15. 测试策略

### 15.1 先写行为测试，再更新 profile

行为测试（JVM 结构测试）不依赖具体混淆名；profile 更新后它们必须仍然通过。改名类问题优先补「宿主替身 fixture + 解析用例」。

### 15.2 建议命令（与 CI 同一清单）

```powershell
$env:AMPP_RELEASE_STORE_FILE = '<keystore 路径>'   # Release 签名需要的环境变量
./gradlew.bat test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :glass-lab:lintDebug `
  :app:assembleRelease :glass-lab:assembleDebug :glass-lab:assembleDebugAndroidTest --no-daemon
git diff --check
```

`git diff --check` 对 vendored 参考源码的历史告警（`backdrop` / `glass`）不要顺手改：会破坏 `scripts/verify-glass-reference.py` 的哈希清单。

### 15.3 宿主替身 fixture

在 `app/src/test/java/` 下按宿主包名建替身类（例如 6.5.2 的 `s8.F`、6.5.3 的 `u8.E`），把「同名但签名不同」的诱饵方法一起写进去，用例里断言解析器选中了哪个方法。这是防止名字复用回归的唯一低成本手段。

## 16. 新版本适配核对清单

### 取证

- [ ] 保存原始包，记录 SHA-256、签名摘要、minSdk/targetSdk/compileSdk、split 清单
- [ ] 确认 versionName / versionCode；无 `manifest.json` 时在记录里标明「tuple 由命令行声明」
- [ ] 生成新旧版本的类/方法/字段清单并做差分：新增、消失、同名异形
- [ ] 每条候选给出证据级别；被拒绝的候选与原因一并入档
- [ ] 对形状敏感的缝做字节码或字段骨架比对

### 代码

- [ ] `EmbeddedBootstrap.SUPPORTED_BUILDS` 加入新 tuple
- [ ] `TargetSymbols` 新档案（含「故意不钉」的条目与理由）
- [ ] `AppleMusicHookProfiles` 新 profile，逐 hook 点写全描述符
- [ ] `AppleMusicHookContracts` 复核：涉及库类、宽泛类型的契约改成「多类型名/结构判定」
- [ ] 名字复用缝加入已验证改名表（如 `AppleCatalogQueryMethod.VERIFIED_RENAMES`）
- [ ] `GlassPolicy.SUPPORTED_BUILDS` 与 `PhoneLiquidGlassFeature` 同时加入新 tuple
- [ ] `StaticCollapsedInterceptGuard`：契约复核通过才放开，否则保持旧版本专属并报 DEGRADED
- [ ] native 依赖在 `nativeLibraryDir` 为空/null 时仍可加载
- [ ] `install()` 失败不会留下已发布的半初始化句柄
- [ ] 版本号递增（versionCode / versionName）
- [ ] README 支持版本表更新

### 测试与脚本

- [ ] 档案选择用例（新 tuple 走新档案；旧 tuple 字节不变）
- [ ] 门控用例（未识别 tuple 拒绝；更高版本/平板拒绝玻璃）
- [ ] 为改名或换语义的宿主类补替身 fixture 与解析用例
- [ ] 结构回归用例（双栏、guard、歌词、玻璃会话）语义不变
- [ ] `scripts/verify-host-profile.py` 增加新版本条目与断言，`scripts/README.md` 同步
- [ ] 全量任务清单与 `git diff --check`

### 文档

- [ ] 新增 `docs/apple-music-<version>-adaptation.md`（证据、符号变更表、行为校验、未验证项）
- [ ] `docs/liquid-glass-adaptation.md` 更新支持范围与差异
- [ ] PR 描述写清「为什么这么钉」与残留 DEGRADED 的复现方式

### 交付与验收

- [ ] Release APK 输出到固定目录并命名可辨
- [ ] 真机：覆盖安装 → tuple 复核 → 冷启动 → 抓日志
- [ ] 逐项验收：设置入口、歌词模糊、歌词字体、自定义歌词、曲库刷新、目录语言、当前歌曲身份、Editorial Video、CJK 卡拉OK、双栏、玻璃（底栏 / 迷你播放器 / 展开过渡 / 底部淡出）
- [ ] 未通过的项写明现象、影响面与复现步骤，不带「大概」「可能」

## 17. 常见症状与排查顺序

| 症状 | 常见根因 | 先看什么 |
| --- | --- | --- |
| 能力状态 ACTIVE，界面无变化 | 关键缝解析失败被局部吞掉 | 日志搜 `NoSuchMethodException` / `contract` / `unresolved` |
| 每次读元数据刷异常 | `install()` 中途失败留下半初始化句柄 | `UninitializedPropertyAccessException` 与安装顺序 |
| DexKit 相关能力全灭 | native 库没加载（目录空/null、无法解包） | `DexKit native library` 日志、`moduleApkPaths` |
| 一个 hook 失败拖垮整块能力 | 解析顺序把关键缝排在最前 | applier / coordinator 构造顺序 |
| hook 都装上了但行为不对 | 名字没变、语义变了 | 字节码比对 + 行为契约 |
| 玻璃完全无效果 | 两处资格没同时放开 | `GlassPolicy` 与 `PhoneLiquidGlassFeature` |
| 弹窗/遮挡/inset 错位 | inset 归属或 peek 语义变化 | 真机截图 + 布局 dump |
| 同名方法取到了别的重载 | 形状不唯一 | 校验脚本的「形状唯一」断言 |
| 只有缓存过的曲目被改写，新歌完全不动 | 模块自有目录查询没落到目标地区（拦截器缝缺失） | 搜 `内容 HTTP 本地化 Hook 安装失败`；按「是否显式指定 storefront」分层统计 `内部原名候选` 的命中率，显式地区那层若为 0 命中即为本故障 |

## 18. 适配记录模板

`docs/apple-music-<versionName>-adaptation.md` 建议包含：

- **版本证据**：包名、versionName/versionCode、输入包与 SHA-256、minSdk/targetSdk、签名、DEX 规模、与上一版的类差异
- **支持的能力**：逐能力一行，状态写「已适配 / 降级 / 未验证」
- **符号变更表**：旧 owner → 新 owner，附证据一句话
- **行为校验**：真机观测与对照表（修复前 / 修复后）
- **验证**：全量任务、静态脚本结果、设备信息
- **未验证项与限制**：明确列出，并写明下一轮怎么验

## 19. 完成定义

一次适配算完成，需要同时满足：

1. 新 tuple 在 bootstrap、玻璃资格、能力档案三处都按证据加入，旧版本行为字节不变；
2. `scripts/verify-host-profile.py` 对新旧版本都 PASS；
3. 全量测试 / lint / Release 构建通过；
4. 真机逐项验收通过，或明确 DEGRADED 且写明原因与复现方式；
5. 适配记录、玻璃手册、README 支持表已更新；
6. PR 描述包含证据链与未验证项，不夹带行为变更。

## 20. 代码入口与文件地图

| 关注点 | 文件 |
| --- | --- |
| 宿主 tuple 资格 | `app/src/main/java/dev/amenhancer/module/hook/EmbeddedBootstrap.kt` |
| 玻璃资格 | `glass/src/main/kotlin/dev/amenhancer/glass/GlassPolicy.kt`、`app/src/main/java/dev/amenhancer/module/hook/PhoneLiquidGlassFeature.kt` |
| 双栏 guard | `app/src/main/java/dev/amenhancer/module/hook/StaticCollapsedInterceptGuard.kt` |
| hook 点与 profile | `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt` |
| 符号档案 | `app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt` |
| 语义契约 | `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookContract.kt` |
| 已验证改名 | `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleCatalogQueryMethod.kt` |
| DexKit 与 native | `app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicDexKitResolver.kt` |
| 元数据运行时 | `app/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt`、`HleMetadataSurfaceBridge.kt` |
| 静态校验 | `scripts/verify-host-profile.py`、`scripts/README.md` |
| 适配记录 | `docs/apple-music-<version>-adaptation.md` |
| 玻璃逐项依赖 | `docs/liquid-glass-adaptation.md` |
