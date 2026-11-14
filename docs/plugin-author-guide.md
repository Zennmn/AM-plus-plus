# AM++ 插件开发教程

适用版本：插件 SDK v1，ZIP 格式 v1。本文面向独立插件作者，按“建立工程 → 编写功能 → 打包 → 导入 → 调试 → 更新适配”的顺序说明。

你不需要 fork AM++ 来发布插件。插件使用 SDK 编译，发布一个 ZIP；用户进入 Apple Music 内的 AM++ 设置导入、启用并重启。AM++ 主项目和插件分别更新。

## 1. 先明确你负责什么

| AM++ 负责 | 插件作者负责 |
|---|---|
| SDK、ZIP 导入和代码加载 | 插件功能、依赖和界面 |
| 启用状态、生命周期和管理入口 | Apple Music 内部目标定位及版本适配 |
| 统一注册记录与冲突提示 | 与 AM++、其他插件之间的行为兼容 |
| 插件专属配置/缓存目录 | 数据格式、数据迁移、线程及实际对象清理 |

首版支持方法/构造器 Hook、观察回调、普通素材，以及通过 Android View 交付的设置页。新开发的插件设置界面以 Miuix 标准组件为基础，并适配手机和平板；这属于界面规范，不改变 SDK v1 的 View 接口。插件可以实现主项目原本没有的功能。

首版没有统一歌词来源接口、播放器按钮插槽、Android XML 资源加载、SO 加载或插件 Activity/Service 注册。需要这些业务行为时，由作者使用自己的宿主适配实现；`claimResource` 只是冲突声明，不是 UI 挂载工具。

插件在宿主进程内执行。统一回调会处理插件错误，但无法回滚你直接修改的宿主对象，也不管理你未登记的线程。

## 2. 准备 SDK 和独立工程

### 2.1 环境

- JDK 17 或以上。
- 示例使用 Gradle 9.7.1；可以复制 AM++ 的 Gradle Wrapper。
- 示例使用 Android SDK Platform 37 和 Build Tools 37.0.0。
- 编译用的 `ampp-plugin-api-v1.jar`。

编译用 SDK 37 与最低运行 API 26 是不同概念。调用较新的 Android API 时，由作者提高 `minAndroidApi` 或判断运行时系统版本。

### 2.2 获取 SDK

已拿到 SDK JAR 的作者可以直接进入下一步。如果只有 AM++ 源码，在项目根目录执行：

```powershell
.\gradlew.bat :plugin-api:exportSdk
```

输出：`plugin-api/build/sdk/ampp-plugin-api-v1.jar`。这是编译接口，不需要用户单独导入或安装。

### 2.3 复制示例

复制源码中的 `examples/basic-plugin/` 到你自己的目录，例如 `C:\Dev\MyAmppPlugin`。保持它是一个独立工程，不在 AM++ 的 settings.gradle.kts 中 include 它。

该示例用于验证 SDK 接入、Hook 和配置生命周期；其中的原生 View 开关不作为新设置界面的模板。新增设置 UI 按[设置页规范](#71-设置页)使用 Miuix，并在插件工程中管理 UI 依赖。

```text
MyAmppPlugin/
├── settings.gradle.kts
├── build.gradle.kts
├── plugin.json
├── lib/
│   └── ampp-plugin-api-v1.jar
├── assets/
│   └── welcome.txt
└── src/main/java/example/
    └── BasicPlugin.java
```

把 SDK 放到 `lib/`。若没有安装全局 Gradle，再从 AM++ 源码复制 `gradlew`、`gradlew.bat` 和整个 `gradle/wrapper/` 目录到独立工程；其中应包含 wrapper JAR 和 properties。

示例中的依赖声明是：

```kotlin
dependencies { compileOnly(files(sdk.get(), androidJar)) }
```

SDK 必须用 compileOnly。不要把 SDK、AM++ 自身或 Apple Music 的类复制到插件包；这些类型由运行环境提供。

## 3. 填写插件说明

示例的 `plugin.json` 可以改成：

```json
{
  "formatVersion": 1,
  "apiVersion": 1,
  "id": "org.example.playerextra",
  "name": "播放器扩展",
  "author": "你的名字",
  "versionName": "1.0.0",
  "versionCode": 1,
  "entryClass": "example.BasicPlugin",
  "minAndroidApi": 26,
  "description": "说明插件提供的功能和支持范围"
}
```

| 字段 | 规则 |
|---|---|
| formatVersion | 当前为 1，表示包格式 |
| apiVersion | 当前为 1，表示使用的 SDK 接口版本 |
| id | 小写反向域名，建议使用你自己的命名空间；每段以字母开头，可含数字和下划线 |
| name / author | 必填、非空，展示给用户 |
| versionName | 必填、非空，展示版本名 |
| versionCode | 正整数，发布新版时增加 |
| entryClass | 入口类的完整类名，必须与编译结果一致 |
| minAndroidApi | 至少为 26；表示插件自己的最低运行要求 |
| description | 可选功能说明 |

ID 决定插件身份和存储目录。同 ID 导入会被视为替换，改 ID 会变成另一个插件。加载器根据 ID 判断替换，不自动把 versionCode 当作更新或降级门禁；用户确认时会看到版本变化。

Apple Music 版本支持范围不写入加载器的通用版本表。作者在代码里判断并给出原因，也可以在 description 中说明已验证的版本。

## 4. 编写入口和生命周期

用下面这个完整示例替换 `src/main/java/example/BasicPlugin.java`，即可得到包含观察回调和设置页的最小插件：

```java
package example;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import dev.amenhancer.plugin.api.AmppPlugin;
import dev.amenhancer.plugin.api.PluginContext;
import dev.amenhancer.plugin.api.PluginObservation;
import dev.amenhancer.plugin.api.PluginObserver;
import dev.amenhancer.plugin.api.PluginSettingsSession;

public final class BasicPlugin extends AmppPlugin {
    private PluginContext runtime;

    @Override
    public void onLoad(PluginContext context) throws Exception {
        runtime = context;
        context.getHooks().observe(
            Activity.class.getDeclaredMethod("onResume"),
            new PluginObserver() {
                @Override
                public void after(PluginObservation call) {
                    runtime.log("Activity resumed: "
                        + call.getReceiver().getClass().getName(), null);
                }
            }
        );
    }

    @Override
    public void onStart() {
        runtime.log("插件已启动", null);
    }

    @Override
    public void onStop() {
        runtime.log("插件已停止", null);
    }

    @Override
    public PluginSettingsSession createSettings(Context context) {
        TextView text = new TextView(context);
        text.setText("宿主版本：" + runtime.getHostVersionName());
        return new PluginSettingsSession() {
            @Override public View getView() { return text; }
            @Override public void close() { }
        };
    }
}
```

这个示例观察 Android Activity 生命周期，没有定位 Apple Music 私有成员。它用于验证 SDK、装载与回调路径，不代表已经实现某个音乐增强功能。

| 阶段 | 线程与行为 |
|---|---|
| onLoad | 各插件在独立后台任务执行，可并发；读取环境、定位目标、登记 Hook，此时本插件回调尚未激活 |
| 冲突检查 | 所有启用插件准备后分析注册；阻断冲突的插件不启动 |
| onStart | 主线程；作用域已经激活，完成轻量启动工作 |
| Hook before/after | 宿主调用目标的线程；不保证是主线程 |
| createSettings | 主线程；只有本次正在运行的插件可以打开 |
| onClose cleanup | 关闭作用域的线程；需要 UI 操作时自行切主线程 |
| onStop | 主线程，尽力调用一次；宿主被直接杀死时不保证调用 |

插件在 AM++ 原有初始化结束后启动，不能依赖捕获已经发生的 Application.onCreate 或早期资源初始化事件。

## 5. 定位目标与注册 Hook

### 5.1 使用宿主 ClassLoader

定位 Apple Music 内部类型时使用：

```java
ClassLoader loader = runtime.getHostClassLoader();
```

不要用插件自身 ClassLoader 或直接 Class.forName 猜测宿主类型。相同类名来自不同 ClassLoader 时，类型和方法身份并不相同。

建议把宿主适配集中在一个目录，例如：

```text
src/main/java/org/example/playerextra/
├── BasicPlugin.java
├── feature/             # 业务逻辑
├── host/                # 版本判断、类/方法/字段定位
└── ui/                  # View 与设置
```

版本适配器返回经过验证的 Method/Constructor，业务逻辑使用它们。不要在高频 Hook 回调里反复扫描 DEX 或发现反射成员。

### 5.2 观察和修改是不同注册

`observe(target, callback)` 提供只读调用信息。`getArguments()` 返回数组副本，不能通过修改这个数组改写本次方法参数。数组中的对象仍是原引用，直接修改它们会产生实际副作用。

`hook(target, exclusive, callback)` 提供可修改的 PluginCall。下面代码应放在入口类的成员方法中；target 是由你的适配器验证过的、接收一个 int 并返回 int 的方法：

```java
private void registerIntegerHook(java.lang.reflect.Method target) {
    runtime.getHooks().hook(target, false,
        new dev.amenhancer.plugin.api.PluginHook() {
            @Override
            public void before(dev.amenhancer.plugin.api.PluginCall call) {
                int value = (Integer) call.getArguments()[0];
                call.getArguments()[0] = Math.max(0, value);
            }

            @Override
            public void after(dev.amenhancer.plugin.api.PluginCall call) {
                if (call.getThrowable() != null) return;
                int result = (Integer) call.getResult();
                call.returnResult(Math.max(0, result));
            }
        }
    );
}
```

| 操作 | 效果 |
|---|---|
| before 中改 getArguments() | 成功提交后将修改后的参数交给原方法 |
| before 中 returnResult(value) | 跳过原方法，提供返回值 |
| after 中 returnResult(value) | 替换已执行方法的返回值，也会清除已有异常 |
| throwException(error) | 指定本次调用的异常结果 |
| 关闭 PluginRegistration | 停止对应逻辑回调，原框架注册仍可能存在 |

参数和返回值必须符合目标签名。回调抛错或提交前类型检查失败时，运行时关闭该插件，并保留进入此回调前的参数、结果和异常。已在前一个成功回调提交的修改仍然存在。

不要在 after 中无条件调用 returnResult，否则可能意外清除宿主原本的异常。不要为了重试功能而再次反射调用原方法，否则可能重复播放控制、保存或其他副作用。

## 6. 适配新版本和处理失败

从 PluginContext 获取宿主包名、versionName 和 versionCode，再选择你已经验证的适配器。必要目标缺失时抛出 PluginUnsupportedException：

```java
private java.lang.reflect.Method resolveNoArgMethod(
        String verifiedClassName, String verifiedMethodName)
        throws dev.amenhancer.plugin.api.PluginUnsupportedException {
    try {
        Class<?> type = runtime.getHostClassLoader()
            .loadClass(verifiedClassName);
        return type.getDeclaredMethod(verifiedMethodName);
    } catch (ClassNotFoundException | NoSuchMethodException error) {
        throw new dev.amenhancer.plugin.api.PluginUnsupportedException(
            "当前宿主版本缺少插件所需目标，请更新插件");
    }
}
```

此辅助方法仅展示“没有参数”的目标定位；有参数时必须核对完整签名，不能随意选择第一个同名方法。必要目标应在 onLoad 注册前尽量定位完成。

网络失败、没有搜索结果等正常业务结果由作者处理，通常记录日志并保留原有功能。把异常抛出 SDK Hook 回调会让运行时停止整个插件，并非只跳过这一次操作。

未来 Apple Music 更新时，作者更新自己的 host 适配器和插件版本。AM++ 仍需完成自身适配和精确版本支持登记；插件不能绕过主项目的版本门禁。插件加载机制没有 Apple Music 私有目标表，通常不需要随每个 AM 版本改动。

## 7. 设置、存储、素材和后台任务

### 7.1 设置页

createSettings 接收本次页面的 Context，返回新的 View 与 PluginSettingsSession。插件设置显示为 AM++ 插件管理下的普通子页，由 AM++ 提供标题、返回按钮和转场；插件通过 `getView()` 返回正文并负责内容与滚动，新插件不应重复绘制完整顶栏，也不应调用宿主 Activity 返回来关闭子页。退出子页、切换设置分类、关闭模块设置或窗口销毁时，AM++ 关闭对应会话；插件在 `close()` 中解除监听、取消页面任务，并确保可以安全重复调用。

新插件的设置正文使用 Miuix 的主题、卡片、设置项、开关、输入框及弹层等标准组件，跟随当前配置的浅深色与字体缩放，不另绘一套相似皮肤。手机和平板共用配置与页面逻辑，按当前可用窗口宽度调整分组、内容宽度和布局；覆盖窄屏、宽屏、横竖屏及分屏，不按机型或固定像素尺寸写死。键盘出现和大字号下，内容应能滚动且关键操作可见，不重复预留宿主顶栏和安全区。

新插件优先使用标准 AndroidView 嵌套滚动互操作：正文滚动组件实际派发事件，AM++ 在挂载期间启用根 View 的嵌套滚动，手机大标题随事件折叠，宽屏使用小标题；宿主不给正文另套纵向滚动容器。自定义滚动实现可参考 [Android 嵌套滚动互操作](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/nested-scroll#nested-scrolling-interop)。

Compose 正文通过 View 交付时，还需把内部 Compose 滚动连接到 View 父级；仅启用根 View 的嵌套滚动标志不会自动完成这个连接。Miuix/Compose 依赖由插件工程管理，并遵守现有 DEX、资源和类加载边界，不把 AM++ 内部 UI 类作为公共 SDK。

已采用 `PluginSettingsScrollBridge` 的插件可继续在 `createSettings` 中通过 `updateTitle`、`updateLargeTitle` 和 `updateSupportsScroll(true)` 声明顶栏信息，再用 `report` 上报 0～1 的折叠比例；关闭后应停止上报。该模式不再与原生嵌套滚动同时写标题；两条路径都没有输入时保持固定标题。此入口只保留兼容，既有插件无需重打包，新插件优先标准滚动。`PluginSettingsSession`、`getView()`、`close()`、SDK 与 ZIP 契约不变。

仓库示例 BasicPlugin 演示标准 View 的设置会话、配置文件写入与关闭清理，不是 Miuix UI 模板。XML 布局、R.drawable 等插件资源加载不属于 v1，普通图片可以从素材流解码成 Drawable/Bitmap；既有 View 插件继续兼容。

### 7.2 专属目录

```java
java.io.File data = runtime.getDataDirectory();
java.io.File cache = runtime.getCacheDirectory();
```

data 保存用户配置及需要跨更新保留的数据；cache 保存可以重新生成的内容。同 ID 更新保留这些目录，删除插件并重启后会清理。作者维护自己的数据格式和迁移，不修改 AM++ 配置 schema 或依赖主项目私有文件路径。

不要默认使用 Application.getSharedPreferences 的宿主全局命名空间。优先在 data 下管理自己的文件；文件/网络/解析等耗时任务放在后台。

### 7.3 素材

ZIP 中 `assets/welcome.txt` 的读取名称是 `welcome.txt`：

```java
try (java.io.InputStream input = runtime.openAsset("welcome.txt")) {
    // 读取插件自己的素材；流使用完即关闭。
}
```

路径不能为绝对路径，也不能包含反向路径段或反斜杠。

### 7.4 登记清理

```java
java.util.concurrent.ExecutorService executor =
    java.util.concurrent.Executors.newSingleThreadExecutor();
runtime.onClose(() -> executor.shutdownNow());
```

在需要执行任务的位置使用 executor.submit。关闭时 shutdownNow 只是发出取消/中断请求，任务仍应正确响应中断，网络连接也应自行关闭。cleanup 按登记的逆序执行，不要依赖进程被杀死时仍调用它。

## 8. 冲突与歌词兼容

| 注册重叠 | 处理 |
|---|---|
| 方法观察者共存 | 允许；方法观察者与写入者也不会被直接判为写入冲突 |
| 多个非独占修改者或内置未知注册 | 提示潜在冲突，允许共存 |
| 独占修改与另一个插件修改 | 阻止相关插件，用户选择后重启 |
| 插件独占修改与内置未知/修改注册 | 内置优先，阻止插件 |
| 同一逻辑资源有独占声明 | 阻止相关冲突插件 |

`exclusive=true` 表示你确实需要独占修改权，不应给所有 Hook 默认开启。普通重叠警告也不等于运行顺序或兼容性的保证；不要依赖插件 ID 排序来获得特定的最终修改结果。

共享一个逻辑位置的作者可以共同约定 key，再调用：

```java
dev.amenhancer.plugin.api.PluginRegistration claim =
    runtime.claimResource("shared:player-source-menu", true);
```

key 只有参与者一致声明时才有比较意义。它不自动寻找播放器菜单，也不扫描或回滚视图修改。尽量使用 SDK 注册 Hook；绕过入口的操作无法完整纳入冲突记录。

歌词增强插件作者还需要自己约定什么时候保留 AM++ 的歌词、什么时候主动替换，确保异步返回属于当前歌曲、切源不会被旧请求覆盖、多个刷新路径不会反复覆盖。没有 Hook 重叠也可能存在这类语义冲突；v1 不会自动协调来源优先级。

## 9. 打包与外部依赖

在独立工程根目录执行：

```powershell
.\gradlew.bat clean pluginZip '-PandroidSdk=C:/Android/Sdk'
```

以上 SDK 路径换成自己的实际路径。若使用全局 Gradle，把命令开头换成 `gradle`。clean 用于避免修改依赖或包内容后留下旧 DEX。

输出：`build/dist/basic-plugin.zip`。示例默认文件名与 ID 无关，作者可以修改 pluginZip 的 archiveFileName。

最终 ZIP 应直接包含 plugin.json、code.jar 和可选 assets，不能再包一层工程文件夹。code.jar 内必须有 classes.dex，而不是 JVM 的 .class 文件。不要把整个工程压缩后当成运行插件。

示例的打包过程是 Java 编译 → JAR → D8 → DEX code.jar → 插件 ZIP。它默认只处理本工程的类；新增 implementation 依赖后，还必须把这些运行时 JAR 交给 D8，否则构建可能成功，运行时却找不到依赖类。

对于普通 Java JAR 依赖，可以在示例 dex 任务内增加：

```kotlin
doFirst {
    args(configurations.runtimeClasspath.get().files.map { it.absolutePath })
}
```

这段增加的是 D8 的程序输入，保留现有 SDK classpath 和 Android lib 参数。不要把 SDK 改成 implementation。AAR 中的 Android 资源和原生库不能用这段自动接入；作者需要符合 v1 的打包边界。不同插件或宿主已经提供的同名依赖仍可能被父加载器解析到，相关兼容由作者验证。

## 10. 导入与调试

1. 使用包含插件系统的 AM++，以及主项目已经支持的 Apple Music 版本。
2. 打开 Apple Music → 设置 → AM++ 模块设置 → 插件 → 导入 ZIP。
3. 确认插件名称、作者和版本；新导入插件默认关闭。
4. 开启“下次启动启用”，完全停止并重开 Apple Music。
5. 查看本次运行状态、错误或冲突详情，运行中插件可以打开插件设置。

| 状态 | 排查方向 |
|---|---|
| 未运行 | 是否只导入而未启用，或尚未重启 |
| 加载中 | onLoad 是否做了耗时或阻塞工作 |
| 不支持 | Android 要求不足，或作者主动报告宿主目标缺失 |
| 失败 | 入口、依赖、加载、启动或 SDK 回调异常 |
| 冲突阻止 | 独占目标/资源重叠；调整相关启用状态后重启 |
| 运行中 | 入口已启动；仍需验证实际功能，不代表所有目标都被调用 |

插件使用 `runtime.log(message, error)` 输出日志。日志会带插件 ID 前缀，便于与其他插件区分。

```powershell
adb logcat -s AppleMusicEnhancer
```

用“找到目标”“onStart 完成”“首次回调”“应用结果”区分不同阶段。高频歌词/动画回调不要每帧写大量日志。更新插件后检查安装版本与本次运行版本是否一致，管理页显示待重启时旧代码仍可能运行。

## 11. 发布与验证

每次发布建议提供：插件 ZIP、版本号、已验证的 Apple Music/Android 范围、功能说明和已知兼容情况。保持 ID 稳定，增加 versionCode，更新 versionName，必要时迁移自己 data 目录里的数据。

启停、替换和删除都在重启后生效。替换保留配置与启用状态；失败导入保留已有版本。删除会清理插件自己的配置和缓存，不进入 AM++ 的歌词备份。

发布前实际验证：独立构建、首次导入、启用重启、目标回调、设置子页打开/退出、切换设置分类和关闭模块设置时的会话清理、更新保留配置、停用、删除，以及与 AM++ 原有功能和常用插件的组合。歌词/网络插件另外检查快速切歌、请求失败和过期结果。

设置 UI 需分别检查手机窄屏、平板宽屏及分屏，包含浅深色、大字号、键盘遮挡、滚动折叠和返回；记录实际验证的窗口范围，不以单一手机截图代替平板适配。

构建与导入检查不能替代 LSPosed、嵌入版以及 Android 14+ 的实际加载/Hook/页面验收。作者需要记录自己的功能、宿主和设备范围，不能从 SDK 或样例的结果推断插件功能通过。

API 与包格式参考见 [SDK 说明](plugin-development.md)，完整可构建样例见 [basic-plugin](../examples/basic-plugin/README.md)。
