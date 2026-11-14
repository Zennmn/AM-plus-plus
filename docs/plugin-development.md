# AM++ 插件 SDK v1

首次开发请先阅读 [插件作者教程](plugin-author-guide.md)，本文作为 SDK 与包格式参考。

插件是作者独立构建、用户在 AM++ 设置中导入的 ZIP。作者维护功能逻辑、Apple Music 目标定位及版本适配；AM++ 维护稳定 SDK、加载器与管理入口。插件与宿主同进程执行，不是隔离沙箱。

## 模块边界

plugin-api 定义 Android/Java 公共接口，不依赖项目其他模块；plugin-runtime 依赖 SDK 与 hook-runtime。app 装配运行时和管理 UI；hook-runtime 与 host-applemusic 的 HLE 注册出口登记 Hook 记录。配置、歌词缓存、profile 和玻璃等功能仍由各自模块维护。

SDK 与加载器没有 Apple Music 内部符号、布局或精确版本表。主项目现有精确版本门禁保持有效；适配新 Apple Music 时，主项目与插件作者各自维护自己的宿主接入。Android/Xposed 加载机制变化或 SDK 升级仍可能需要修改插件系统。

## 构建与包格式

JDK 17+、项目 Gradle Wrapper、Android SDK/Build Tools 37。导出 SDK：

```text
./gradlew :plugin-api:exportSdk
```

产物是 `plugin-api/build/sdk/ampp-plugin-api-v1.jar`，供插件 compileOnly 使用，不允许打包 SDK 自身。

验证独立开发路径：

```text
python scripts/build-plugin-example.py --android-sdk /absolute/android/sdk
```

脚本将样例复制到仓库外的临时目录，仅携带 SDK JAR 构建；ZIP 与 SDK 输出到 `build/plugin-example/`。作者可以直接复制 `examples/basic-plugin/` 为自己的项目，参照其 README 构建。

ZIP 根目录：

```text
plugin.json
code.jar
assets/       # 可选普通素材
```

code.jar 必须包含 classes.dex 和可选 classes2.dex 等 Android DEX。普通 JVM JAR、AAR、资源表、XML 布局、SO、插件 Activity/Service 不属于 v1。设置 UI 通过 View 交付，新开发界面使用 Miuix 标准组件；UI 依赖由插件工程管理，仍遵守上述资源与打包边界。普通素材通过 openAsset 读取。

plugin.json 示例：

```json
{
  "formatVersion": 1,
  "apiVersion": 1,
  "id": "org.example.myplugin",
  "name": "我的插件",
  "author": "作者",
  "versionName": "1.0.0",
  "versionCode": 1,
  "entryClass": "org.example.MyPlugin",
  "minAndroidApi": 26,
  "description": "功能说明"
}
```

description 可选，其余必填。ID 是小写反向域名，versionCode 为正整数，入口为 public、非抽象、无参构造的 AmppPlugin 子类。SDK/格式版本首版均精确为 1；不兼容 API 变更升级 API 版本，不因宿主更新升级。

导入不执行插件代码。上限：输入 128 MiB，解包 256 MiB，4096 条目，说明文件 64 KiB；内层 DEX JAR 同样检查实际解压大小，验证 DEX 头、长度、校验和与 SDK 重复定义。ZIP 路径穿越和重复条目被拒绝。

## SDK 与生命周期

- onLoad(PluginContext) 在后台调用，用于定位目标并注册 Hook，此时回调未激活。可抛 PluginUnsupportedException 给出不支持原因。
- onStart() 在冲突检查通过后、主线程调用。网络与耗时工作由作者放到后台。
- onStop() 关闭作用域后在主线程尽力调用一次；进程被杀死不保证调用。
- createSettings(Context) 在主线程调用，只允许本次正在运行的插件创建设置页。返回 PluginSettingsSession，设置作为插件管理下的普通子页，由 AM++ 提供标题、返回和转场，插件通过 `getView()` 返回正文并控制内容与滚动。退出子页、切换设置分类、关闭模块设置或 Activity 销毁时 close。

新插件设置正文以 Miuix 为基础，按当前窗口宽度适配手机和平板，包括横竖屏、分屏、浅深色、大字号与键盘出现的场景。手机和平板共用配置与页面逻辑，不按设备型号或固定像素尺寸写死布局。SDK 仍返回 View，既有插件继续兼容；具体 UI 要求见[作者教程](plugin-author-guide.md#71-设置页)。

新插件优先通过标准 AndroidView 嵌套滚动互操作驱动手机大标题，宽屏使用小标题；Compose 正文需显式接通到 View 父级的滚动链。正文不另套宿主滚动容器，也不应重复绘制整页顶栏或通过 Activity 返回退出宿主。未派发事件的 View 保留固定标题。

既有 `PluginSettingsScrollBridge` 的标题、能力声明与比例上报按页面接收，在 `createSettings` 前绑定，关闭或失败时解除。声明上报模式的页面由上报比例驱动顶栏，不与原生嵌套滚动双写。该入口仅保留兼容；状态与释放规则见[适配手册](host-adaptation-guide.md#settings-data-plugins)。`PluginSettingsSession`、SDK、ZIP 与 `API_VERSION` 保持不变，既有插件无需重打包。

PluginContext 提供 Application、宿主 ClassLoader、包名和版本、专属 data/cache 目录、素材流、日志、cleanup 登记及资源声明。SDK 的 ClassLoader 来自模块；插件定位宿主目标必须使用 getHostClassLoader()。

插件自行管理精确目标、线程和对象写入。采用与主项目相似的业务/适配/界面分层更便于作者适配新版，但加载器不理解插件业务。

```java
context.getHooks().observe(method, new PluginObserver() { /* before / after */ });
context.getHooks().hook(method, false, new PluginHook() { /* before / after */ });
context.claimResource("org.example.shared-menu", true);
context.onClose(() -> executor.shutdownNow());
```

observe 的参数数组是副本，没有结果/参数写入接口；被引用的宿主对象不是深拷贝。hook 的 PluginCall.getArguments() 可修改本次参数；returnResult 可返回或跳过原方法；throwException 指定异常。修改只在回调成功并通过类型检查后提交，失败保留进入回调前的参数/结果/宿主异常，并关闭该插件。原方法由现有 Hook 运行时执行，不重复重试。

PluginRegistration.close() 关闭单条注册。作用域关闭后已安装框架回调透传，不依赖框架物理 unhook。cleanup 逆序执行；加载器不能回滚作者直接修改的对象或未登记线程。

## 冲突与用户操作

现有初始化结束后按 ID 顺序提交各插件的独立加载任务；onLoad 可并发执行，完成顺序和启动顺序不作保证。每个插件加载完成后检查已有注册，再在主线程启动；一个插件加载阻塞不会阻止其他插件启动。仍在加载的插件所登记的目标也参与分析，后续出现独占重叠会关闭相关插件作用域。两条内置注册出口与 SDK 注册都纳入记录；按实际 Executable 比较，区分重载和 ClassLoader。

| 重叠 | 行为 |
|---|---|
| 观察者共存 | 允许 |
| 多个普通修改者 | 提示，允许共存，用户自行停用 |
| 插件独占与其他修改者 | 阻止相关冲突插件，用户调整后重启 |
| 插件独占与内置未知/修改注册 | 内置优先，阻止插件 |
| 逻辑资源独占重叠 | 阻止相关插件 |

内置注册无法确定行为时记录“未知”。动态新增注册也执行检查；并发变化串行检查，检查内部释放注册触发的变化会补查。绕过 SDK 注册的 Hook、不同目标之间的语义冲突不在分析范围内。逻辑资源 key 需参与者共同约定，声明本身不会自动创建或挂载 UI。

导入后默认关闭，同 ID 替换需要确认，保留配置与启用状态。启停、更新、删除在完全停止并重开 Apple Music 后生效，不热卸载代码。当前加载版本文件保留，下次启动清理旧代码。删除同时清理插件专属配置和缓存，数据不进入原歌词备份。

## 检查与验收

```text
python scripts/verify-architecture.py
python scripts/verify-profile-data.py
python scripts/verify-glass-reference.py
./gradlew test :app:lintDebug :app:lintVitalRelease :glass:lintDebug :host-applemusic:lintDebug :plugin-api:lintDebug :plugin-runtime:lintDebug :app:assembleDebug :app:assembleRelease :plugin-api:exportSdk
```

使用 -PpluginFixture=/absolute/basic-plugin.zip 将独立样例交给导入测试。包/存储、回调事务、生命周期、入口类身份、冲突和 SAF 路由有 JVM 测试；不等于设备 DEX 加载或真实 Hook 验收。

设备验收分别覆盖 LSPosed 与嵌入版：导入与取消、启用重启、Activity 观察日志、素材/设置、替换、禁用/删除、独占冲突、错误插件不阻止其他功能，以及 Android 14+ 的只读代码行为。
