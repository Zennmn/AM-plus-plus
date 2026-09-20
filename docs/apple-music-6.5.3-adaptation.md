# Apple Music 6.5.3 (1599) 适配记录

本文记录 AM++ 对 Apple Music 6.5.3 的适配证据、符号变更与验证状态。适配方法见 [Apple Music 新版本适配手册](apple-music-target-adaptation.md)，液态玻璃的逐项依赖见 [液态玻璃新版本适配](liquid-glass-adaptation.md)。

## 1. 版本证据

| 字段 | 值 |
| --- | --- |
| package | `com.apple.android.music` |
| versionName / versionCode | `6.5.3` / `1599` |
| 输入包 | `apple-music-6-5-3.xapk`（base `com.apple.android.music.apk` + `config.arm64_v8a`/`armeabi_v7a`/`x86`/`x86_64` + `ldpi..xxxhdpi` 密度 split；该 XAPK 无 `manifest.json`，版本 tuple 由命令行声明） |
| SHA-256 | `EC24E5CC3B882239DF3531EA676C6BF2B3A739CE2A7A39ACFA5DEEDDA5FF5F59` |
| minSdk / targetSdk / compileSdk | 30 / 35 / 35 |
| 签名 | V3.1 证书 SHA-256 `771d8674…fff9b`、V3.0 `88ba590e…100aa`，与 6.5.2 样本完全一致（Apple Inc. + Google），可覆盖安装 |
| DEX 规模 | 37519 类；与 1586 共享 26713，仅 1586 有 10392，仅 1599 有 10806 |

## 2. 支持的能力

| 能力 | 状态 |
| --- | --- |
| 嵌入设置入口 | 已适配（bootstrap 增加精确 tuple，未识别版本继续 fail-closed） |
| 双栏播放器 / 平板补偿 | 已适配（触摸 interception guard 的 Behavior 类名、父类与签名与 6.5.2 完全一致） |
| 歌词模糊、歌词字体、自定义歌词 | 已适配（`o2`、`e0/d0` 等缝的宿主类名沿用，profile 重新固定） |
| 标题修正 / 目录语言 / 当前歌曲身份 | 已适配（`A8.D`、`K5.a`、`u8.E` 重新固定，契约放宽为两代类型名并集） |
| 曲库 Compose 表面 | 已适配（`Dg.c#l`、`z0.p0`、`common.I#t` 重新固定） |
| Editorial Video / CJK 卡拉OK | 已适配（`player.f1` 不变；`player.z`→`player.A`） |
| 手机液态玻璃 | 已适配（`GlassPolicy` 支持 tuple 集合，玻璃几何常量不变） |

## 3. 符号变更表

判定只采用两类证据：**精确证据**（owner + 完整描述符 + 调用点）与**结构证据**（成员骨架一致、调用关系一致）。仅凭短名相同的推断不进入档案。

| symbol key | 6.5.2 owner | 6.5.3 owner | 证据 |
| --- | --- | --- | --- |
| `PLAYER_CONTROLLER` | `player.fragment.t0` | `player.fragment.v0` | 成员骨架 0.97 唯一匹配；`PlayerActivity.f1()` 返回类型由 `t0` 变 `v0`；`player.e1.g/d` 参数由 `t0$n` 变 `v0$n` |
| `METADATA_TO_ITEM_CONVERTER` | `player.O` | `player.P` | 7 个方法骨架完全一致，唯一匹配 |
| `CJK_KARAOKE_ANIMATION_OWNER` | `player.z` | `player.A` | 60 方法 + 55 字段完全一致；`a0(z$a,III,Z)` → `a0(A$a,III,Z)` |
| `STACKED_NAVIGATION_MENU` | `Hd.b` | `Kd.b` | 5 方法与 `onMeasure/onLayout/e(Context)` 一致，唯一匹配 |
| `MEDIA_ENTITY_TO_SONG_CONVERTER` | `y8.B` | `A8.D` | 静态 `b(Song, Bundle): Song` 全库唯一 |
| `STORE_FRONT_LANGUAGE_ARRAY_OWNER` | `J5.a` | `K5.a` | `a(Context): ContentBundlePtr` + `b(Context): String[]` 完全一致 |
| `CJK_UNICODE_BLOCK_HELPER_OWNER` | `utils.I0$a` | `utils.E0$a` | `a(CharSequence, Set): boolean` 全库唯一 |
| `MediaApiLanguageParamMethod` | `s8.F#c0` | `u8.E#c0` | 静态 `c0(Map): LinkedHashMap` 全库唯一 |
| `LISTEN_NOW_ARTWORK_RESOLVER` | `common.L#t` | `common.I#t` | 枚举方法集完全一致（含 `t(CollectionItemView)V`） |
| `LISTEN_NOW_MODEL_BUILDER` | 参数含 `common.F0` | 参数含 `common.B0` | 同一 lambda 的同一参数位；`common.B0` 六个方法集与 `common.F0` 一致 |
| `LIBRARY_EPOXY_BUILD` | `library2.M` / `x6.c` | `library2.H` / `z6.b` | 同一 `buildModels` 五参描述符，`library2.a` 不变 |
| `COMPOSE_OBSERVE_AS_STATE` | `C1.w#e(LiveData, Composer): z0.p0` | `Dg.c#l(LiveData, Composer): z0.n0` | 两代各只有一个该形状的静态方法；`z0.n0` 与 1586 `z0.p0` 一样只声明 `setValue`，运行实例 `z0.n1` 保留策略字段 `b` 与 get/setValue。两个版本里的 `LiveData` 本身是混淆名 `androidx.lifecycle.G`（见 §8） |
| `COMPOSE_NEVER_EQUAL_POLICY` | `z0.s0` | `z0.p0` | 字节码相同：`const/4 v0,#0; return v0`（其余同名候选体不同） |
| 曲库查询 / 事件枚举 | `G5.g` / `Vf.o` | `H5.g` / `Zf.o` | 方法骨架一致；`common.I#v(List,Z)` 返回类型同步变为 `Zf.o` |
| `PLAYER_METADATA_HUB` | `player.f` | **未固定** | 6.5.2 身份是被重命名的 lambda，1599 无足证据候选，故不写入档案，保留 `EXACT_PREFERRED` 的结构回退 |

未列出的 hook 点（媒体库刷新、编辑视频诊断、歌词 RecyclerView/Compose 文本等）在 6.5.3 上沿用既有“继承候选 + 契约校验”路径：候选必须在 owner、描述符与契约同时成立时才会安装，否则报告 `DEGRADED/FAILED`。

## 4. 行为校验

- holder 所有权：`PlayerActivity$StackedBottomNavigationHolder.c(F)V` 与 `PlayerBottomSheetBehavior` 在 1599 仍存在，原生 peek/拖拽/过渡归属未改变。
- 触摸 interception：`common.behavior.StaticCollapsedBottomSheetBehavior` 的名称、父类与 `h(CoordinatorLayout, View, MotionEvent): boolean` 签名与 6.5.2 完全一致，因此 guard 对两个版本都开放。
- 玻璃：`GlassPolicy.SUPPORTED_BUILDS` 为精确 tuple 集合（1586/1599）；几何常量仍是唯一来源；未发现 6.5.3 的 inset 归属或 peek 语义变化，故未拆版。
- fail-open：未识别版本、解析失败或契约不符时不安装 Hook，日志与 `FeatureHealth` 携带 `6.5.3 (1599)` 文案。

## 5. 验证

- JVM：`./gradlew.bat test` 全量通过（**769 项**），含 `AppleMusic653ProfileTest`（bootstrap tuple、档案选择、队列适配器与 Compose 缝改名、内容 HTTP 本地化拦截器、资料库 Compose VM getter、未固定符号回落）、宿主替身 fixture（`u8.E`、`w8.a`、`Li.f`、`Gi.A`、`Gi.D`）与玻璃资格用例。
- 静态取证（每轮追加断言后的最新值）：`python scripts/verify-host-profile.py apple-music-6-5-3.xapk --version-name 6.5.3 --version-code 1599 --glass` → `checks: 51, failures: 0`；同脚本对 6.5.2 样本 `checks: 37, failures: 0`。断言覆盖三处改名缝（目录直连查询的形状唯一性与首选名占用、内容 HTTP 本地化拦截器家族与反向占用、资料库 Compose VM getter）以及 `androidx.lifecycle.G` 的 LiveData 表面。
- lint/构建：与 CI 相同的任务清单（`test`、`lintDebug`、`lintVitalRelease`、`glass:lintDebug`、`glass-lab:lintDebug`、`assembleRelease`、`glass-lab:assembleDebug`、`glass-lab:assembleDebugAndroidTest`）。
- 真机：已完成三轮真机回归并各自修复（§7 / §8 / §9，设备 PJD110，宿主 6.5.3/1599，模块从 1.5.7/106 迭代到 1.5.10/109）；逐能力验收清单（设置入口、歌词模糊与字体、自定义歌词、曲库刷新、目录语言、当前歌曲身份、Editorial Video、CJK 卡拉OK、双栏、玻璃底栏+迷你播放器+展开过渡+底部淡出）仍待逐项核对。

## 6. 未验证项与限制

- `PLAYER_METADATA_HUB` 未固定：6.5.3 的元数据发布缝若结构回退也失败，相关能力会报告降级而不是静默失败。
- 仍为降级、只波及各自子面的两处（详见 §9.5）：`IN_APP_ACTION_SHEET_BINDING`（播放菜单/操作表元数据）与主页 Listen Now 封面连续性 Hook。
- 6.5.2 的 PixelCopy/性能矩阵结论不继承到 6.5.3，需单独测量。
- 曲库 Compose/双栏在 6.5.3 的真机行为（展开、收回、拖拽、按钮点击）尚未验收。
- 若后续收到与本文 XAPK 来源不同的 6.5.3 包（例如官方签名包或不同 split 集合），需重新取证并更新 SHA-256。

## 7. 首轮真机回归：歌名修正未生效的根因与修复（1.5.8 / 107）

首轮真机验收（PJD110，Apple Music 6.5.3/1599 + AM++ 1.5.7/106）报告“歌名修正完全未生效，多个界面都是”。模块日志显示 `title_correction: DEGRADED`，并伴随 300+ 条 `[HLE-metadata] Apple Music Hook 结果覆盖失败`。

根因是三个问题叠加，与符号改名本身无关：

1. `AppleInAppMetadataApplier` 构造时第一个解析的是 `IN_APP_QUEUE_ADAPTER_SUBMIT`。6.5.2 的候选 `Y8.a` 在 1599 已被复用于**另一个类**（protobuf 模式映射辅助类），名称命中但契约不符，直接解析失败后转入 DexKit 回退。
2. 模块以 `extractNativeLibs=false` 打包，`libdexkit.so` 留在 APK 内，安装后的 `nativeLibraryDir` 为空。旧的 `ensureDexKitLoaded()` 只接受已释放到磁盘的 `.so`，于是抛出 `IllegalArgumentException: DexKit native library missing: …/lib/arm64/libdexkit.so`。
3. 异常在 `HleMetadataSurfaceBridge.install()` 中途抛出，而 `surfaceBridge` 字段已经赋值、其内部 `lateinit metadataRegistrationCoordinator` 尚未初始化。先前装好的 content-item getter hook 继续调用它，于是每次读元数据都抛 `UninitializedPropertyAccessException`，整个 HLE 元数据运行时以 DEGRADED 收场，歌名修正自然全线不动。

修复：

1. DexKit 加载改为三级：已释放文件 → `System.loadLibrary("dexkit")`（覆盖“库在 APK 内”的加载器查找）→ 按 `Build.SUPPORTED_ABIS` 从模块 APK 解包到私有 cache 再 `System.load`；全部失败时聚合原因报错。新增 `moduleApkPaths` 参数，由 `HleMetadataRuntime` 从模块 `ApplicationInfo` 的 `sourceDir`/`publicSourceDir`/`splitSourceDirs` 提供。
2. `HleMetadataRuntime` 改为 `install()` 成功后才给 `surfaceBridge` 赋值。安装失败时字段保持未初始化，hook 走既有的 `::surfaceBridge.isInitialized` 守卫，不再产生异常洪泛。
3. 6.5.3 档案补齐队列适配器精确目标：`IN_APP_QUEUE_ADAPTER_SUBMIT` → `a9.a#B(1)`、`IN_APP_QUEUE_ADAPTER_BIND` → `a9.a#p(2)`。证据：1599 的 `a9.a` 与 1586 的 `Y8.a` 骨架逐项相同（父类 `androidx.recyclerview.widget.v`、8 个方法、10 个字段，含提交方法 `B` 与绑定方法 `p`），运行时成员名（`l`/`A`/`b`/`d`/`a`/`I`）不变。

真机复验（同一设备，模块 1.5.8/107）：

| 观测 | 结果 |
| --- | --- |
| 能力状态 | `title_correction: ACTIVE - HLE metadata runtime installed for 6.5.3 (1599); original metadata + persistent SQLite cache enabled [6.5.3 (1599)]` |
| DexKit | `Apple Music DexKit 查询并缓存成功: hook=MEDIA_API_LOCALIZATION elapsedMs=102 target=u8.E#c0(java.util.Map):java.util.LinkedHashMap[static]`，与档案里 `s8.F#c0 → u8.E#c0` 的独立取证互相印证 |
| 实际覆盖 | `歌曲元数据已更新…标题=Inside Joke` → `Apple Music App 内元数据已覆盖: id=1570795403, title=あいつら全員同窓会` → `Apple 播放元数据已覆盖: language=ja-JP, original=true, confirmed=true` |
| 异常 | 修复后进程内 `UninitializedPropertyAccessException` 计数为 0（旧进程的 363 条保留在更早的日志分段中） |

已知残留降级项（互不影响，均只波及对应子面）：`CONTENT_HTTP_LOCALIZATION` 的 DexKit 查询歧义（`count=2`，该 hook 安装失败）、`IN_APP_ACTION_SHEET_BINDING` 歧义（`count=186`）、`COMPOSE_OBSERVE_AS_STATE` 的 DexKit `count=0`（§8 已定位为 LiveData 别名契约问题并修复）。

## 8. 第二轮真机回归：目录直连查询的验证重命名（1.5.9 / 108）

第一轮修复只覆盖了队列适配器缝。真机日志里仍持续出现目录直连查询失败，导致原地区元数据解析全线返回空：

```
[HLE-metadata] Apple 内部目录直连查询失败: id=1645425554, language=null
java.lang.NoSuchMethodException: u8.E#B(String,Map,Continuation)
…
[HLE-metadata] Apple 地区批量元数据候选: entityType=ARTIST, requested=1, resolved=0, storefront=cn
```

这与上游 PR #57 记录的是同一类故障：档案钉住的成员名在下一个宿主版本上被 R8 复用到别的方法，代码按形状校验后拒绝，能力静默降级。差别只是这次发生在 6.5.3：

| 宿主 | 类 | 方法 | 描述符 | 结论 |
| --- | --- | --- | --- | --- |
| 6.5.2 (1586) | `s8.F` | `B` | `(String, Map, Continuation) -> Object` | 模块契约要求的目标 |
| 6.5.3 (1599) | `u8.E` | `B` | `(Hg/c, String, Map) -> Object` | 同名，参数顺序不同，契约拒绝 |
| 6.5.3 (1599) | `u8.E` | `v` | `(String, Map, Continuation) -> Object` | 真正的目标（全类唯一形状） |

方法体取证：用设备端 `dexdump -d` 解出两版 `classes` DEX 的方法体，按助记符序列比对（`s8.F#B` 612 个 16 位码元）。`s8.F#B` 与 `u8.E#v` 的 353 条助记符**逐条相同**，`u8.E#B` 为 352 条且序列不同。类身份另有字段证据：`u8.E` 的 24 个字段与 `s8.F` 一一对应，含 storefront 字段 `s`（`MEDIA_API_STOREFRONT_FIELD`）。

修复：`AppleCatalogQueryMethod` 改为 `VERIFIED_RENAMES` 表，按（宿主类, 首选名）声明已验证的改名（`s8.F#B → x`、`u8.E#B → v`），且只在首选名未通过签名契约后使用；歧义与未知宿主继续 fail-closed。JVM 侧新增 `u8.E` 替身 fixture 与改名用例，静态取证脚本新增“形状唯一 + 首选名不再占用该形状”两条断言。

同一轮修掉 `COMPOSE_OBSERVE_AS_STATE` 的契约缺陷：Apple Music 把 `androidx.lifecycle.LiveData` 也混淆了（两个版本里都叫 `androidx.lifecycle.G`，保留 `getValue`/`observe`/`observeForever`，而 `MutableLiveData` 保留原名），旧契约 `RequireParameterType(0, "androidx.lifecycle.LiveData")` 在两代宿主上都会拒绝正确目标。`RequireParameterType` 现在接受一组已验证类型名，LiveData 缝同时接受库名与宿主别名，未知宿主仍被拒绝。该缺陷在 6.5.2 上同样存在（契约与 6.5.3 共享），因此这一项不是 6.5.3 引入的回归。

真机复验（PJD110，模块 1.5.9/108，冷启动后同一会话）：

| 观测 | 修复前（1.5.7/106 段） | 修复后（1.5.9/108 段） |
| --- | --- | --- |
| `Apple 内部目录直连查询失败` | 10 次 | **0 次** |
| `NoSuchMethodException: u8.E#B(String,Map,Continuation)` | 有 | **0 次** |
| `Apple 内部原名候选` | 5 条，全部 `value=null/null, isrc=null` | 42 条，全部带真实 `value`/`isrc`（如 `Milabo/ZUTOMAYO`、`JPPO02001854`） |
| `Apple Music App 内元数据已覆盖` | 54 条 | 141 条（含 `食えない`、`春夢`、`凄美地`、`あいつら全員同窓会` 等原语言标题） |
| `资料库 Compose 局部刷新 Hook 安装失败` | 有 | **无**（`COMPOSE_OBSERVE_AS_STATE` 不再报契约失败，DexKit 也不再报 `count=0`） |
| 能力状态 | `title_correction: ACTIVE` + 目录能力静默降级 | `title_correction: ACTIVE - HLE metadata runtime installed for 6.5.3 (1599)` |

仍待单独取证的残留：`Apple 地区批量元数据候选: entityType=ARTIST, resolved=0`（固定地区歌手名批量查询，新包 652 行日志中出现 1 次），与目录直连查询无关，不影响歌名/专辑名的原名覆盖。

## 9. 第三轮真机回归：内容 HTTP 本地化拦截器 u8.a → w8.a（1.5.10 / 109）

现象（用户报告）：歌名修正只在**已经命中缓存的曲目**上生效，播放新歌不再改写标题。能力状态仍显示 `title_correction: ACTIVE`，因此不是安装失败，而是解析链路的某一环静默返回空。

### 9.1 定位

同一份真机日志（模块 1.5.9/108、宿主 6.5.3/1599）里，把 `Apple 内部原名候选` 按是否显式指定 storefront 分层统计：

| 查询形态 | 次数 | 命中（`value` 非空） |
| --- | --- | --- |
| 未指定 storefront/language（身份预取） | 444 | 405 |
| 显式指定 storefront/language（原语言解析） | 238 | **0** |

也就是说：凡是要落到目标 storefront 的查询全部为空，而这正是原名解析唯一的入口。同时每个会话都固定出现：

```
[debug] Apple Music DexKit 查询未得到唯一目标或存在歧义: hook=CONTENT_HTTP_LOCALIZATION count=2
Apple 内容 HTTP 本地化 Hook 安装失败
```

`CONTENT_HTTP_LOCALIZATION` 的职责是模块自有查询的请求改写：把 URL 里的 storefront 段改成目标地区、写入 `l=` 语言参数、并移除模块自己注入的 `hle_catalog_request` 令牌。该 hook 缺失时，显式地区的查询既带着不该外发的令牌、也无法保证落在目标地区，服务器返回空响应；解析回调拿到 `null`，`Apple 原名查询未命中: reason=setting_enabled` 随即回落到当前地区别名——界面上就是“标题完全没变”。旧曲目因为磁盘缓存里已经有原名，走缓存命中路径，不受影响，于是呈现为“只有缓存的生效”。

### 9.2 符号再取证

1599 的 `u8.a` 已复用为无关的 MediaApi 模型类（A..Z 一大批 suspend 方法），旧的精确目标因此失效，DexKit 又在全 DEX 里找到两个同形拦截器而放弃安装。

结构性证据：1586 的整包家族 `u8/a..u8/n`（14 个类：拦截器 a/c/d、协程续体 b/e/f、内容仓库 g..n）与 1599 的 `w8/a..w8/n` **逐字母一一对应，形状逐项相同**；1599 自己的 `u8` 包已经换成另一批类。

字节码证据（设备端 `dexdump -d`，两侧 `classes2.dex`）：

| 宿主 | 成员 | 描述符 | 指令数 |
| --- | --- | --- | --- |
| 6.5.2 (1586) | `u8.a#a` | `(LHi/f;)LCi/F;` | 19 |
| 6.5.3 (1599) | `w8.a#a` | `(LLi/f;)LGi/D;` | 19 |

两侧助记符序列**逐条相同**，仅重打包库的属主不同：`Hi/f→Li/f`、`Ci/C→Gi/A`、`Ci/F→Gi/D`、`Ci/v→Gi/t`、`Ci/y→Gi/w`、`E0/x→A0/h`、`ma/c→pa/c`。运行时成员名在 1599 全部保留（chain 字段 `e`；request 字段 `a`/`c`、builder `b`/`h`/`d`/`b`；headers 字段 `a`、方法 `e`；response 字段 `a`/`f`、状态 `d`），因此档案只需换属主与参数类型。

### 9.3 修复

1. 6.5.3 档案新增精确目标 `CONTENT_HTTP_LOCALIZATION → w8.a#a(1)`，参数类型 `Li.f`、返回类型 `Gi.D`，并复用与旧版本一致的运行时成员映射（提到 `contentHttpRuntimeMemberNames`，两个档案共享一份）。
2. 6.5.3 档案顺带钉住 `MEDIA_API_LOCALIZATION → u8.E#c0(1)`：设备日志里这一缝一直靠 DexKit 回退到同一个方法（`u8.E#c0` 是唯一接收模块目录参数 Map 的方法，与 1586 `s8.F#c0` 同名同形），现在改为档案直取，不再依赖全 DEX 扫描。
3. 静态取证脚本新增拦截器家族断言：6.5.3 校验 `w8.a#a(LLi/f;)LGi/D;`、`Li/f` 的 `e` 字段、`Gi/A` 的 `a`/`c` 字段与 `b()`、`Gi/A$a` 的 `h`/`d`/`b`、`Gi/t` 的 `a` 字段与 `e()`、`Gi/D` 的 `a`/`d`/`f` 字段，并新增一条“复用名不得再满足该签名”的反向断言（1599 的 `u8.a` 不能顶替 `w8.a`）；6.5.2 侧对称校验 `u8.a#a(LHi/f;)LCi/F;` 与 `Hi/f`/`Ci/C`/`Ci/F`/`Ci/v`。
4. JVM 侧新增 `w8.a`、`Li.f`、`Gi.A`、`Gi.D` 替身 fixture 与两条用例：档案钉住的属主/成员名，以及 `resolveMethod` 在 `u8.a` 缺席时直接命中 `w8.a` 且不进入 DexKit。
5. 同一轮把资料库 Compose 局部刷新的 VM getter 也钉进 6.5.3 档案：`LIBRARY_COMPOSE_VIEW_MODEL_GETTER` 在 6.5.0 叫 `B0`、6.5.1 叫 `A0`，1599 改名 `F0`，三者签名相同（`()Lcom/apple/android/music/library2/LibraryViewModel;`，且是该 Fragment 里唯一的零参同类型 getter）。DexKit 之前按旧名 `B0` 查询得到 `count=0`，因此该 hook 在 6.5.3 上一直安装失败。

### 9.4 验证

| 项 | 结果 |
| --- | --- |
| `python scripts/verify-host-profile.py apple-music-6-5-3.xapk --version-name 6.5.3 --version-code 1599 --glass` | 50 checks / 0 failures |
| `python scripts/verify-host-profile.py Apple+Music_6.5.2_APKPure.xapk --version-name 6.5.2 --version-code 1586` | 36 checks / 0 failures |
| JVM | 全量单测通过（新增 3 条档案用例与 4 个宿主替身 fixture） |
| 真机（PJD110，模块 1.5.10/109，宿主 6.5.3/1599，冷启动后同一会话） | 见下表 |

真机对照（同一设备；修复前取 1.5.9/108 会话，修复后取 1.5.10/109 会话）：

| 观测 | 1.5.9 / 108 | 1.5.10 / 109 |
| --- | --- | --- |
| `Apple 内容 HTTP 本地化 Hook 安装失败` | 每会话 1 次 | **0 次** |
| `Apple 内容 HTTP 本地化 Hook 已安装` | 无 | **有**（`5759-…-2-b`） |
| 显式地区查询命中率（`Apple 内部原名候选` 带 storefront 层） | 0 / 238 | 分层查询改走批量路径后 `Apple 地区批量元数据候选` 全部 `resolved=1`（含 `storefront=jp, language=ja-JP`） |
| `Apple 原名查询未命中` | 每首歌 1 次以上 | **0 次** |
| `Apple 播放元数据已覆盖 … original=true` | 仅缓存曲目 | 5 条（新会话内） |
| `资料库 Compose 局部刷新 Hook 安装失败` | 每会话 1 次 | **0 次**，改为 `资料库 Compose 局部刷新 Hook 已安装: content=com.apple.android.music.library3.LibraryComposeContentFragment#J1, observe=Dg.c#l` |

### 9.5 残留降级（与本项无关，互不影响）

`IN_APP_ACTION_SHEET_BINDING`（DexKit `count=186`，播放菜单/操作表元数据）与主页 Listen Now 封面连续性 Hook 仍未在 6.5.3 上安装；两者各自只波及对应子面，不影响歌名/专辑名/歌手名覆盖。前者在 1599 上连宿主类都换了（6.5.2 的 `l7.e8` 及其父类 `l7.v1` 都不存在），需要单独按形状重找；后者需要在 1599 的资源类里重新定位封面连续性缝。

---

## 10. 第四轮真机回归：平板双栏右栏歌词空白（1.6.1 / 111）

### 10.1 症状与定位

6.5.3 平板开启双栏后，播放器右栏（歌词）整块空白，左栏封面/标题/控制与其余能力都正常。

`AppleMusicDualPaneTarget.attachPairedFragments()` 是这条链路上唯一会静默收场的步骤：它先把控制器的状态写回 SONG，再用子 FragmentManager 事务把宿主自己的 SONG/LYRICS 两个 Fragment 换进左右 host。任一环节失败只写一行 `[AMENH-2]` debug 日志，界面表现就是右栏空白。

### 10.2 符号再取证（宿主 DEX 实测）

| 成员 | 6.5.2 (1586) | 6.5.3 (1599) | 判定依据 |
| --- | --- | --- | --- |
| 状态枚举 | `player.fragment.t0$n` | `player.fragment.v0$n` | 都是控制器内嵌枚举，常量 `SONG/LYRICS/QUEUE` 相同 |
| Fragment 访问器 | `f()Lcom/apple/android/music/common/fragment/a;` | **`e()Lcom/apple/android/music/common/fragment/a;`** | 两代各只有 1 个无参实例方法返回非 String 类；1599 的 `i(v0$o)` 带参数 |
| Tag 访问器 | `g()Ljava/lang/String;` | `g()Ljava/lang/String;` | 同名同签名 |
| 状态 LiveData 字段 | `S:Landroidx/lifecycle/MutableLiveData;` | **`Q:Landroidx/lifecycle/MutableLiveData;`** | 控制器字段在 1599 整体前移两位（`P:t0$m → N:v0$m`），但两代都只有这一个 LiveData 字段 |
| FragmentManager | `androidx/fragment/app/D`（`P()Z`） | `androidx/fragment/app/F`（`P()Z`） | 模块按 `getChildFragmentManager` 的返回类型反查，不写死类名 |
| 事务类 | `androidx/fragment/app/a`（`<init>(…D;)V`、`h(Z)I`） | `androidx/fragment/app/a`（`<init>(…F;)V`、`h(Z)I`） | 两代都存在、方法名不变；`e(I,Fragment,String)` 由父类 `O`/`Q` 提供，`a` 分别是其子类 |

也就是说，这条链路上只有「Fragment 访问器改名」与「状态 LiveData 字段位移」两处会断，事务与 Manager 部分两代通用。

### 10.3 修复

- 新增 `DualPaneStateAccessors`（`AppleMusicDualPaneTarget.kt`）：按形状解析状态枚举的两个访问器 —— 唯一的「无参实例方法、返回非 String/原始/数组类型」是 Fragment 访问器，唯一的「无参实例方法返回 String」是 tag 访问器。形状歧义或缺失时拒绝附件并打印诊断（fail-closed），不按声明顺序猜成员名。
- `forceSongState()` 的状态 LiveData 改为按类型定位（`androidx.lifecycle.MutableLiveData`），不再写死 6.5.2 的字段名 `S`。
- 6.5.2 行为不变：形状解析在两代都命中同一批成员（`f`/`g`、字段 `S`）。

### 10.4 验证

- JVM：新增 `DualPaneStateAccessorsTest`（5 项）覆盖 6.5.2 形状 `t0$n`、6.5.3 形状 `v0$n`、双访问器歧义必须 fail-closed，以及「源码不再按 6.5.2 成员名查找访问器/状态字段」的结构断言；夹具为 `app/src/test/java/com/apple/android/music/player/fragment/{t0,v0,x0}.java`。
- 真机：日志应出现 `[AMENH-2] state accessors resolved fragment=e tag=g`，右栏出现歌词。
- 全量任务与签名 Release 见 §5 的同一套命令。
