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

code.jar 必须包含 classes.dex 和可选 classes2.dex 等 Android DEX。普通 JVM JAR、AAR、资源表、XML 布局、SO、插件 Activity/Service 不属于 v1。使用标准 View 代码创建设置 UI，普通素材通过 openAsset 读取。

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
- createSettings(Context) 在主线程调用，只允许本次正在运行的插件创建设置页。返回 PluginSettingsSession，页面关闭或 Activity 销毁时 close。设置统一显示为 AM++ 插件管理下的普通设置子页；AM++ 使用同一 `getView()`，页面内容与滚动由插件负责，不创建独立 Activity。退出子页、切换设置分类或关闭模块设置时由 AM++ 关闭会话；仅运行中插件经原有入口打开设置。

设置页承载沿用既有 `PluginSettingsSession`、`getView()`、`close()` 与 ZIP 契约，不新增 `API_VERSION` 或 manifest 字段；旧插件无需重打包。

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
