# Apple Music 新版本适配手册

本文面向 AM++ 维护者，适用于任意 Apple Music 新版本。目标是让既有功能在宿主内部实现变化后继续工作，而不是借版本升级顺手改变产品行为。

Apple Music 的类名、方法名、字段名和资源 ID 都属于私有实现。本文定义的是适配方法和代码契约；每次适配仍必须针对实际 APK 重新取证、解析、测试和验收。

## 1. 适配的基本契约

### 1.1 适配和行为变更是两件事

版本适配只解决“同一能力在新宿主中如何找到并安装”。以下语义默认保持不变：

- 功能开关的默认值、资格条件和 `FeatureHealth` 语义；
- Android API 门槛、官方平板/手机和横竖屏判定；
- 资源注册、`Application.onCreate` 安装、异常隔离和健康上报顺序；
- Hook 的 before/after 时机、参数替换、返回值覆盖和调用顺序；
- 双栏播放器的布局、Fragment transaction、折叠/展开和边界补偿；
- 歌词模糊的焦点、滚动暂停、跨歌曲清理和恢复时序；
- Editorial Video、歌词字体、自定义歌词和当前歌曲身份的范围；
- 配置 schema 的键名、默认值、编码、迁移和双进程边界。

如果新版迫使其中某项语义发生变化，应先写独立的行为变更记录，再修改 adapter；不要把行为变化伪装成 profile 更新。

### 1.2 四层边界

适配代码应保持四层分离：

```text
APK 证据 / profile
        ↓
TargetSymbolResolver（Found / Missing / Ambiguous）
        ↓
Target adapter（稳定的语义能力）
        ↓
Feature（不接触宿主私有反射对象）
```

Feature 只依赖 adapter 暴露的稳定语义。不要把 `Class.forName`、混淆方法名或宿主 View 的具体层级扩散到 feature 和设置代码中。

### 1.3 失败必须可见且可降级

每项目标能力都应有独立的健康状态：

- `ACTIVE`：所需符号已明确解析，Hook 已安装；
- `DEGRADED`：非关键子能力缺失，但主功能仍可安全运行；
- `FAILED`：关键能力无法安装，必须 fail-open，不得继续伪装为成功。

一个可选 Hook 的 Boolean 返回值不能被静默丢弃。目标进程不能因为适配失败崩溃，但日志和健康状态必须让维护者知道“哪些能力没有安装”。

## 2. 目标适配的运行架构

### 2.1 两阶段安装

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

资源和布局必须在宿主 inflate 目标布局之前注册；依赖目标类实例或版本 profile 的 Hook 不应提前到资源阶段。每个阶段都要幂等，失败时不能发布半初始化 session。

### 2.2 Feature 与目标能力的边界

目标适配层应按语义能力组织，而不是按“某个类里有几个方法”组织。例如：

| 能力 | 适配层职责 | Feature 看到的结果 |
| --- | --- | --- |
| 双栏播放器 | root、holder、fragment、边界和原生行为协调 | 可展开/收回的双栏播放器 |
| Editorial Video | 只在规定设备条件下抑制目标 URL | 视频行为保持原生，其余场景放行 |
| 双向歌词模糊 | 建立歌词 session、焦点和滚动状态 | 当前行清晰，其他行按距离模糊 |
| 歌词字体 | 定位目标 RecyclerView/文本渲染路径 | 只影响歌词字体 |
| 自定义歌词 | 定位歌曲身份和歌词加载入口 | 原始值缺失时 fail-open |
| 当前歌曲身份 | 提供稳定的歌曲 ID/对象快照 | 供多个 feature 复用 |
| 媒体库刷新 | 定位 MediaLibrary singleton、update/ready 和请求入口 | 只影响手动刷新/目录补全按钮 |
| 标题修正 | 定位标题转换、缓存和 miss 回填入口 | 候选缺失时保留宿主标题 |
| 目录语言 | 定位 storefront、Accept-Language 和 catalog map | 只重写语言字段，保留请求返回契约 |
| 设置页 | 把模块设置注入宿主原生设置页 | 配置入口可见且可持久化 |

### 2.3 关键文件

适配工作通常涉及：

- `TargetSymbols.kt`：版本 profile、symbol key 和 resolver；
- `TargetAdaptation.kt`：能力组装和 adapter 边界；
- `AppleMusicDualPaneTarget.kt`：双栏播放器目标实现；
- `StaticCollapsedInterceptGuard.kt`：可独立降级的触摸 interception 适配；
- `EmbeddedBootstrap.kt`、`HookEntry.kt`：精确构建门控、生命周期和设置入口；
- `TargetSymbolResolver` 相关代码：class/method/field 的证据化解析；
- `app/src/test/...`：结构回归、纯策略和目标能力测试。

## 3. 先建立新版本的证据档案

### 3.1 输入包清单

每次适配都要保存原始 APK/XAPK 的以下信息：

| 字段 | 要求 |
| --- | --- |
| 文件名和来源 | 保留原名，不以 PR 标题代替 |
| package name | 必须和作用域、profile 完全一致 |
| version name/code | 以安装后的包管理器和 APK 元数据为准 |
| base/split 集合 | 记录 ABI、density、语言等 split |
| SHA-256 | 至少保存原始包和 base APK 摘要 |
| 签名证书 | 记录证书摘要，避免分析错包 |
| 测试设备 | 型号、Android 版本、ABI、分辨率和屏幕密度 |

不要从文件名猜 versionCode。一次适配中把版本 code 写错，会导致 profile、bootstrap 和日志全部看似一致但实际不匹配。

### 3.2 安装后的复核

```powershell
adb shell dumpsys package com.apple.android.music |
  Select-String 'versionName=|versionCode='
```

如果是 XAPK/APKS，先解包得到完整 split 集合；只分析 base APK 可能漏掉资源或实际执行的 ABI dex。

### 3.3 证据分级

将发现分成三类：

1. **精确证据**：owner、完整参数、返回值、继承链、资源名和版本 tuple 同时匹配；
2. **结构证据**：名称变化，但调用关系、字段类型、构造参数和相邻资源关系一致；
3. **猜测**：只凭混淆短名、方法顺序或单个资源 ID 推断。

生产 Hook 只能依赖前两类，第三类只能作为待验证候选，不能静默安装。

## 4. 版本 profile 设计

### 4.1 profile 是精确构建知识

一个 profile 至少包含：

```text
packageName
versionName
versionCode
exactClasses
exactMethods
exactFields
resourceIds
evidence / analysis notes
```

profile 的键是精确的 `packageName + versionName + versionCode`。不要用“6.5.x”“最新版”或模糊范围代替。多个版本共享实现时，也应在每个版本条目中明确写出证据，而不是默认继承。

### 4.2 添加 profile 的规则

- 先复制上一版作为分析起点，逐项重新确认；
- 解析结果全部为 `Found` 且无歧义后，才把版本标为 supported；
- 资源、类、方法和字段应独立记录，不能因为主类找到就假设其他成员仍然存在；
- 只对已验证版本开放会改变行为的 Hook；
- 未识别版本应保持 `UNSUPPORTED` 或 `DEGRADED`，不能“尽力猜测”。

### 4.3 profile 和通用符号的关系

公共 API 或 AndroidX 成员可以使用稳定的结构解析；Apple Music 私有成员必须绑定 profile 或明确的结构 fallback。某个版本特有的私有 guard 不应塞进所有旧版本的公共 profile，否则会制造“profile 显示已支持、实际能力从未验证”的假象。

## 5. 目标符号解析模型

### 5.1 每个符号独立解析

每个 class、method、field 都要独立得到：

```text
Found(candidate, evidence)
Missing(reason)
Ambiguous(candidates, reason)
```

禁止以下写法：

```kotlin
declaredMethods.firstOrNull { it.name == "h" }
```

即使当前版本只有一个候选，也应同时校验 owner、static/instance、参数类型、返回类型、可见性和调用结构。候选多于一个时宁可降级，也不要挑第一个。

### 5.2 解析层级

推荐按以下顺序解析：

1. exact profile：已验证的 owner/name/descriptor；
2. constrained structural fallback：owner、签名、字段类型、调用者和资源关系均满足约束；
3. 不安装并报告 Missing/Ambiguous。

Fallback 必须有边界、有日志、有测试。不能用 fallback 绕过版本门控，也不能将私有类名硬编码在多个 adapter 中。

### 5.3 解析诊断

日志至少包含：

- package/version tuple；
- symbol key 和 owner；
- 使用的策略（exact/profile/structural fallback）；
- 候选数量和被拒绝原因；
- 安装结果和 FeatureHealth。

这些信息比“Hook installed”更有用，尤其是方法混淆后签名未变但语义已经改变的情况。

## 6. Hook 时序和原生行为所有权

### 6.1 资源期和实例期分离

- 资源 ID/布局替换：在目标 Application 创建前注册；
- 目标类和方法 Hook：在 profile 解析完成后安装；
- 需要 view tree 的判断：等到目标实例和布局真正创建后进行；
- 设置页桥接：在宿主原生 SettingsFragment 生命周期中接入。

不要在资源期创建依赖 Activity/CoordinatorLayout 的对象，也不要在实例 Hook 中重复注册资源。

### 6.2 原生 holder 和动画由谁负责

如果 AM++ 改造了宿主 View tree，必须明确“模块改哪些几何条件”和“原生 holder 管哪些状态”：

- 原生 holder 负责 peek、展开/收回、过渡动画和最终状态；
- 模块负责目标 root、Fragment、资源布局和必要的边界条件；
- 模块不应永久改 mini-player 的 visibility、alpha 或动画；
- 发现目标 root 后，不要因为“有 flat root”就无条件保留另一个 native holder；
- holder 的 return/call 语义发生变化时，必须有独立行为记录和运行时测试。

## 7. 双栏播放器适配方法

### 7.1 先确认目标 View tree

不要只检查某个资源 ID 是否存在。至少确认：

- 当前 root 属于目标 PlayerActivity/CoordinatorLayout；
- root 是由本模块转换过的目标树，而不是宿主其他页面的同名控件；
- `player_container`、`player_sheet_container`、tabs frame、lyrics/queue child 的父子关系符合预期；
- 当前版本和 profile 与解析结果一致。

### 7.2 布局和边界的两个量

将“检测重叠的高度”和“实际位移的 inset”分开：

```text
tabsHeight      = tabs frame 的完整高度
menuHeight      = 原生底栏菜单高度
navigationInset = tabsHeight - menuHeight
```

边界判断使用完整 `tabsHeight`，collapsed settled translation 通常只使用 `-navigationInset`。不要把“完整 frame 高度”直接当成播放器平移量，也不要用额外 bottom margin 复制 native sheet boundary。

这条规则必须由纯单元测试锁定：

- tabs frame 高度变化时，overlap 判断变化；
- settled translation 只随 navigation inset 变化；
- 原生 holder 仍拥有 peek/transition；
- compensation 开关关闭时，模块不再改边界。

### 7.3 资源和布局的可验证约束

适配记录应明确记录资源名而不是只记录十六进制 ID。常见检查包括：

- tabs frame 是否完整宽度、是否在正确的 CoordinatorLayout 下；
- menu height 和 stacked container 的 padding 是否符合设计；
- player container 是否仍由 native sheet 管理，是否错误设置 bottom margin；
- root transform 是否只作用于目标页面；
- 普通手机、portrait、非双栏页面是否完全放行。

## 8. 触摸 interception Guard

### 8.1 为什么不能按 Behavior 类全局放行

`CoordinatorLayout.Behavior.onInterceptTouchEvent()` 决定 Behavior 是否接管整串触摸事件。对某个混淆 Behavior 类的所有实例直接设置 `param.result = false`，可能影响无关页面，也可能让播放器在 `ACTION_DOWN` 时失去后续拖拽事件。

“未命中区域会回到 `onTouchEvent`”不能作为普遍保证。是否进入 `onTouchEvent` 取决于 CoordinatorLayout 对整条事件流的选择。

### 8.2 最小 bypass 条件

建议把判断拆成可测试的纯策略，只有以下条件同时满足才绕过原始 interception：

- 当前构建是已验证的 profile；
- 官方平板、横屏、双栏功能和补偿开关均开启；
- coordinator 是目标 coordinator；
- child 是目标 player sheet，并位于本模块转换过的 View tree 下；
- MotionEvent 命中 lyrics/queue 等已确认会被误拦截的区域；
- 对同一 behavior 建立 DOWN→UP/CANCEL 的短 gesture latch。

否则应调用 Apple 原始方法，保持其拖拽和展开逻辑。

示例接口：

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

Guard 的反射安装是独立能力。方法缺失、签名歧义或安装异常必须报告 `DEGRADED/FAILED`，不能继续返回 `ACTIVE`。

## 9. 设置页、配置 schema 和双进程边界

### 9.1 不要假设模块有 launcher Activity

独立模块可以没有可启动 Activity，设置页也可以嵌入 Apple Music 原生 SettingsFragment。适配新版本时要分别确认：

- bootstrap 是否接受新的 package/version tuple；
- SettingsFragment 的 owner、继承链和 preference setup 方法；
- preference 的 key、title、summary、add/remove 路径；
- 设置入口是否出现在 Apple Music 原生设置页；
- 修改后重启宿主是否仍能读到同一配置。

“作用域已启用但设置入口消失”不应直接归因于作用域。常见原因是 bootstrap 版本门控遗漏、SettingsFragment profile 失效或资源期注册太晚。

### 9.2 配置 schema 是单一事实来源

适配不应复制键名和默认值。所有设置都应从 `ModuleSettingsSchema` 或同等单一来源读取：

- key、类型、默认值和序列化编码；
- remote preference/file 位置；
- 迁移 marker、冲突处理和失败重试；
- 独立模块进程与宿主进程之间的同步边界。

新版本宿主私有目录变化时，只适配存储路径和初始化时序，不要改动业务配置语义。

## 10. 其他目标能力的适配要点

### 10.1 歌词和字体

- 先确认目标 RecyclerView/文本容器的精确类型，不能只按资源名猜；
- blur session 要在切歌、退出播放器和异常时清理；
- 字体替换只作用于歌词目标，不要污染搜索、推荐或设置页面；
- 自定义歌词注入失败时保留宿主原始歌词。

### 10.2 当前歌曲身份和显示修正

身份解析应尽量在 adapter 中完成一次并复用快照。UI getter 不应遍历关系、读磁盘、等待网络或同步等待后台任务。候选值为空或异常时保留原始 title/artist/album，保持 fail-open。

### 10.3 Editorial Video 和手机液态玻璃

这些能力有独立设备资格和资源期约束。版本适配时只更新目标符号和资源定位，不要扩大 URL 抑制、窗口背景或手机/平板资格范围。

### 10.4 媒体库刷新

媒体库刷新通常同时依赖 MediaLibrary 类型、singleton、ready/update 方法、更新原因枚举，以及可选的 native catalog refresh/native pointer。适配时：

- 先确认用户主动刷新请求的入口和广播/回调生命周期；
- 独立解析 `MediaLibraryType`、singleton、ready、update 和更新原因，不要把一个方法找到当成整项能力成功；
- 优先使用结构明确的 `UserInitiatedPoll` 等更新原因，枚举缺失时返回 `DEGRADED`；
- native pointer 或 native catalog refresh 缺失时，只关闭加速/回填路径，不影响播放器和歌词；
- 注册 request responder 失败时报告 `DEGRADED`，不得让宿主启动失败；
- 如果刷新后需要目录补全，和标题修正共享同一个 bounded cache，禁止每次点击重新扫描配置或遍历关系。

### 10.5 标题修正和目录缓存

标题修正通常跨越“宿主显示转换 → Apple Music ID → 目录缓存 → miss 后台回填”四个边界。适配新版本时：

- 重新确认标题转换方法的 owner、参数/返回类型和调用时机；
- 保留宿主原始 title/artist/album 作为 fail-open 值，候选为空、异常或缓存 miss 时不阻塞 UI；
- 标题缓存应延迟到第一次实际解析或用户主动刷新，不要在 `Application.onCreate` 扫描偏好或创建无界任务；
- 当前歌曲身份解析、标题缓存和媒体库刷新必须共享同一身份/缓存语义，避免重复解析和跨歌曲污染；
- miss 回填应有 bounded queue、去重、后台执行和可重试结果，不能在主线程做目录查询；
- title correction 开关关闭时，不应创建 catalog lookup、scheduler 或额外的宿主 Hook。

### 10.6 目录语言

目录语言不是一个单独的字符串 Hook，而是一组请求契约适配。新版可能把语言分散在 storefront、Accept-Language、iCloud helper、Store API/iTunes header map、Media API 参数和 store lookup 参数中。适配时：

- 为每个语言承载点独立解析和报告状态；
- 只改语言字段，保留其他 header、map key、未知值和原始返回对象；
- 同时处理 raw language tag 与 `Accept-Language` header 映射，避免只改 UI locale 而目录仍返回默认语言；
- map 重写应在值类型正确时复制并替换，未命中或类型不符时返回原 map；
- 不直接 Hook suspend/Continuation 返回值，除非已经验证其返回契约；
- 至少覆盖 storefront、header、map、raw tag 和无相关字段的 fail-open 测试。

## 11. 测试策略

### 11.1 先写行为测试，再更新 profile

针对每个容易回归的语义写 RED 测试，再实现 GREEN：

- holder：目标 root 返回正确的 native holder，非目标 root 调用原始实现；
- boundary：完整 tabs frame 参与 overlap，translation 使用 navigation inset；
- guard：portrait、双栏关闭、补偿关闭、无关 coordinator、无关 child 和非按钮区域均放行原始方法；
- library refresh：缺少 MediaLibrary 私有符号时只让刷新能力降级，播放器和歌词仍可安装；
- title correction：缓存 miss、空候选和后台回填失败均保留宿主原始显示；
- catalog language：各语言承载点分别重写，未知 map/key/类型保持原值；
- settings：supported build 显示设置入口，unsupported build 不发布半初始化设置；
- resolver：Missing/Ambiguous 时不安装错误 Hook，健康状态准确反映结果。

只检查 `source.contains(...)` 不足以证明运行时行为。结构测试可以防止代码误删，但必须搭配纯策略测试、resolver seam 测试和设备验收。

### 11.2 建议命令

```powershell
./gradlew.bat testDebugUnitTest --no-daemon
./gradlew.bat lintDebug lintVitalRelease assembleDebug assembleRelease --no-daemon
git diff --check
```

如果只修改 profile 或 adapter，也要运行受影响的定向测试，再运行全量 JVM；不要只依赖编译通过。

### 11.3 设备验收

在目标设备上再次确认宿主版本：

```powershell
adb shell dumpsys package com.apple.android.music |
  Select-String 'versionName=|versionCode='
adb shell uiautomator dump /sdcard/ampp.xml
adb pull /sdcard/ampp.xml .work/ampp.xml
```

至少验收：

1. 设置入口可见、可打开、可保存，重启后仍在；
2. 平板横屏双栏播放器的 bottom navigation、mini-player、歌词和队列按钮均存在；
3. 展开、收回、拖拽和点击事件互不破坏；
4. portrait、手机、双栏关闭和补偿关闭路径保持原生行为；
5. logcat 能区分 profile resolution、Hook 安装和 `ACTIVE/DEGRADED/FAILED`；
6. PR 附带设备型号、Android 版本、宿主版本和截图/录屏。

## 12. 常见症状和排查顺序

| 症状 | 优先检查 | 常见根因 |
| --- | --- | --- |
| 作用域已开但设置入口消失 | bootstrap 日志、SettingsFragment profile、资源注册时序 | 新版本未加入 exact build gate，或设置 Hook owner/方法变化 |
| 底部栏灰掉/布局异常 | holder 选择、root transform、tabs frame 层级 | flat root 提前 return、holder 语义错误或资源注册太晚 |
| mini-player 消失 | `k1()` 返回值、native holder 生命周期、visibility/alpha Hook | 错保留 flat holder，或模块接管了原生动画 |
| collapsed 播放器被底栏推入/遮挡 | `tabsHeight` 与 `navigationInset` 是否分离 | 把完整 tabs 高度直接当 translation，或重复修改 margin |
| 歌词/队列按钮失效 | guard 的 coordinator/child/区域判断、gesture latch | 对 Behavior 类全局返回 false，或 DOWN 事件被截断 |
| 日志显示 ACTIVE 但功能没生效 | 每个独立 install 的返回值和 FeatureHealth | 忽略 Boolean、Missing/Ambiguous 被吞掉 |
| 只在某个版本崩溃 | profile tuple、owner、descriptor、split 集合 | 误用旧 profile 或只分析 base APK |

排查顺序应是：版本身份 → bootstrap → 资源时序 → profile/resolver → holder/adapter → 设备 UI 行为。不要先通过扩大 Hook 作用域“碰运气”。

## 13. 新版本适配流程

1. 保存 APK/XAPK、split 清单、SHA-256、签名和设备信息。
2. 安装后用包管理器确认 package/versionName/versionCode。
3. 建立旧版与新版的 class、method、field、resource 差异表，只读分析，不立即修改业务行为。
4. 为每个目标符号标记 Found/Missing/Ambiguous，并记录证据和候选拒绝原因。
5. 更新 exact profile 和结构 fallback 的边界；未确认的符号保持未支持。
6. 先修 bootstrap/设置入口，再修资源期注册和目标 adapter。
7. 为 holder、boundary、touch guard、resolver 和设置 bootstrap 补行为测试。
8. 运行定向测试、全量 JVM、lint、Debug/Release 构建和 diff 检查。
9. 在准确宿主版本的目标设备上完成设置、双栏、歌词、拖拽和 fail-open 验收。
10. 在 PR 中提交适配记录：版本证据、符号表、行为差异、测试结果、日志和截图/录屏。

## 14. 适配记录模板

复制以下模板到 PR 描述或 `docs/` 中，按实际版本填写：

```markdown
## Apple Music <versionName> / <versionCode>

- package:
- source APK/XAPK:
- SHA-256:
- base/split/ABI:
- signer:
- test device / Android / density:

### Supported capabilities
- [ ] embedded settings
- [ ] dual-pane player
- [ ] lyrics blur
- [ ] lyrics typeface
- [ ] custom lyrics
- [ ] library refresh
- [ ] title correction
- [ ] catalog language
- [ ] current-song identity
- [ ] editorial video
- [ ] phone liquid glass

### Symbol changes
| symbol key | owner | descriptor | strategy | evidence | status |
| --- | --- | --- | --- | --- | --- |

### Behavior checks
- holder ownership:
- boundary geometry:
- touch interception scope:
- native animation/drag ownership:
- fail-open/degraded behavior:

### Validation
- JVM:
- lint/build:
- device smoke:
- screenshots/video:
- known limitations:
```

## 15. 完成定义

一次新版本适配只有同时满足以下条件才算完成：

- 版本 tuple 和 APK 证据可复现；
- 每个目标符号都有独立解析结果和可追踪 profile；
- unsupported、Missing、Ambiguous 和 Hook 异常不会误报 `ACTIVE`；
- 设置入口、资源时序和配置迁移经过验证；
- native holder、动画、拖拽和 boundary 语义没有被无记录改变；
- 全量 JVM、lint、Debug/Release 和 `git diff --check` 通过；
- 目标设备上有截图/录屏和 logcat 证据；
- PR 明确列出尚未验证的版本、设备或能力。

适配完成的标准不是“编译成功”或“某个页面出现了”，而是新宿主中既有功能仍按原契约工作，并且失败时能安全、可诊断地退化。

## 16. 代码入口

- [`TargetSymbols.kt`](../app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt)：版本 profile、symbol key 和解析策略。
- [`TargetAdaptation.kt`](../app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt)：目标能力组装和 adapter 边界。
- [`AppleMusicDualPaneTarget.kt`](../app/src/main/java/dev/amenhancer/module/hook/AppleMusicDualPaneTarget.kt)：双栏播放器、holder、布局和边界 Hook。
- [`StaticCollapsedInterceptGuard.kt`](../app/src/main/java/dev/amenhancer/module/hook/StaticCollapsedInterceptGuard.kt)：触摸 interception 的独立适配点。
- [`EmbeddedBootstrap.kt`](../app/src/main/java/dev/amenhancer/module/hook/EmbeddedBootstrap.kt)：宿主版本门控和嵌入设置初始化。
- [`HookEntry.kt`](../app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt)：宿主生命周期和资源注册时序。
