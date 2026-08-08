<a id="top"></a>

<p align="center">
  <img src="docs/images/b851cbb7f571c6666f1a41377baa778b.jpg" alt="AM++ icon" width="180">
</p>

<h1 align="center">AM++</h1>

<p align="center">
  Apple Music 的 Android 增强模块，专注于播放器布局、歌词体验、歌词内容和字体显示。
</p>

<p align="center">
  <a href="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml"><img src="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/Zennmn/AM-plus-plus/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Zennmn/AM-plus-plus" alt="MIT License"></a>
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+">
  <img src="https://img.shields.io/badge/libxposed-API%20102-7F52FF" alt="libxposed API 102">
</p>

<details>
<summary>目录</summary>

1. [项目简介](#项目简介)
   - [功能](#功能)
   - [效果展示](#效果展示)
   - [兼容性与限制](#兼容性与限制)
2. [安装](#安装)
   - [前置条件](#前置条件)
   - [安装模块](#安装模块)
3. [使用](#使用)
   - [双向歌词模糊](#双向歌词模糊)
   - [自定义歌词](#自定义歌词)
   - [歌词字体](#歌词字体)
   - [手机液态玻璃](#手机液态玻璃)
   - [隐藏启动器图标](#隐藏启动器图标)
4. [构建技术](#构建技术)
5. [从源码构建](#从源码构建)
   - [环境](#环境)
6. [项目结构](#项目结构)
7. [路线图](#路线图)
8. [贡献](#贡献)
9. [隐私与权限](#隐私与权限)
10. [联系方式](#联系方式)
11. [许可证](#许可证)
12. [致谢](#致谢)

</details>

## 项目简介

AM++ 是一个通过 libxposed API 102 注入 Apple Music 的增强模块，目标包名为 `com.apple.android.music`。它不替换播放器本身，而是在保留 Apple Music 原有播放流程的基础上，补充平板双栏播放器、双向歌词模糊、自定义歌词注入、歌词字体替换和手机液态玻璃等体验增强。

模块同时提供独立的设置页。设置由 Xposed 框架的 remote preferences 管理；字体和自定义歌词正文使用 remote file 保存，并由 Apple Music 注入进程读取。

## 功能

| 功能 | 默认状态 | 生效范围与说明 |
| --- | --- | --- |
| 平板双栏播放器 | 开启 | Apple Music 官方判定为平板且横屏时，左侧显示播放器，右侧显示实时歌词。 |
| 平板禁用动态视频 | 开启 | 仅抑制平板横屏下的 Editorial Video，静态预览和普通 Music Video 不受影响。 |
| 双向歌词模糊 | 开启 | 当前高亮歌词保持清晰，历史歌词和后续歌词按距离逐渐模糊；手动滚动停止约 1 秒后恢复。 |
| 歌词模糊半径调节 | `0px` | 可在设置中对模糊半径增加或减少 `-10..10px`。 |
| 自定义歌词注入 | 关闭 | 按 Apple Music ID 替换 TTML，支持手动 TTML、AMLL 和网易云逐字歌词导入。 |
| 歌词字体替换 | 关闭 | 导入 TTF/OTF 后应用到播放器歌词布局，可恢复原字体；示例使用 MiSans。 |
| 手机液态玻璃 | 关闭 | 手机底栏和 mini-player 的实时模糊、半透明材质与选中胶囊，目前为 WIP。 |
| 平板底栏补偿 | 关闭 | 平板底栏显示异常时使用的兼容性选项。 |
| 隐藏启动器图标 | 显示 | 隐藏后可从 LSPosed 的模块详情重新打开设置页。 |

双向歌词模糊的核心逻辑移植并适配自 [a23bc/amlyricblur](https://github.com/a23bc/amlyricblur)。

## 效果展示

### 自定义歌词注入

自定义歌词按 Apple Music ID 与歌曲绑定。播放过程中不会自动联网识歌；网络导入只会在设置页中由用户主动点击触发。

<p align="center">
  <img src="docs/images/bf32d15a3519cef0051d8a208b58ab42.jpg" alt="自定义歌词注入示例一" width="48%">
  <img src="docs/images/918d640313475d68cf66fff0e63f4e19.jpg" alt="自定义歌词注入示例二" width="48%">
</p>

### MiSans 字体替换

导入字体后，播放器歌词中的文字会使用选定字体，同时保留 Apple Music 的字号和样式。下图为 MiSans 替换前后效果。

<p align="center">
  <img src="docs/images/537369a74adc232f855165263c9ff1cc.jpg" alt="MiSans 字体替换前后对比" width="900">
</p>

### 平板双栏播放器与歌词模糊

<p align="center">
  <img src="docs/images/tablet-dual-pane-player-open-source-blur.png" alt="平板横屏双栏播放器与歌词模糊" width="900">
</p>

### 手机液态玻璃（WIP）

<p align="center">
  <img src="docs/images/liquid-glass-demo.jpg" alt="手机液态玻璃底栏演示" width="420">
</p>

> [!WARNING]
> 这玩意纯纯半成品，bug多得离谱，截个图还是ok的。

## 兼容性与限制

| 项目 | 当前支持 |
| --- | --- |
| Android | Android 8.0（API 26）及以上 |
| Xposed 框架 | 支持 libxposed API 102、remote preferences 和 remote file 的实现 |
| Apple Music | `6.5.0 (1580)`、`6.5.1 (1583)` |
| 双向歌词模糊 | Android 12（API 31）及以上 |

- Apple Music 的内部类、方法和资源会随版本混淆或调整，未列出的版本不保证兼容。
- 目标版本通过精确 profile 和结构契约定位，升级 Apple Music 后可能需要重新适配。
- 功能开关不会热卸载当前 Apple Music 进程中已经安装的 Hook；修改后需要强制停止并重新打开 Apple Music。
- 自定义 TTML 文件大小上限为 512 KiB，字体文件大小上限为 16 MiB。
- 手机液态玻璃仍处于 WIP 阶段。

## 安装

### 前置条件

- 已安装 Apple Music。
- 已安装支持 libxposed API 102 的 Xposed 框架。判断兼容性时以框架核心报告的 API 版本为准，不要只看 Manager 应用版本。
- 设备运行 Android 8.0 或更高版本。

### 安装模块

1. 从 [Releases](https://github.com/Zennmn/AM-plus-plus/releases/latest) 下载 AM++ APK 并安装。
2. 在 LSPosed 或兼容的 Xposed 管理器中启用 **AM++**。
3. 仅将 Apple Music（`com.apple.android.music`）加入作用域。
4. 强制停止并重新打开 Apple Music。
5. 打开 AM++ 设置页，确认状态显示已连接 libxposed API 102 后再修改需要 remote preferences 或 remote file 的设置。

Apple Music 功能修改后都需要重新打开目标应用。隐藏启动器图标只影响 AM++ 的桌面入口，不影响 Apple Music Hook。

## 使用

### 双向歌词模糊

在设置页开启“双向歌词模糊”即可。当前高亮行保持清晰，当前行之前和之后的歌词都会随距离增加而变模糊。手动浏览歌词时，模糊会暂时移除；滚动稳定约 1 秒后，会恢复当前高亮位置对应的模糊效果。

“歌词模糊半径”可以用于微调强度，范围为 `-10..10px`。该功能需要 Android 12 或更高版本。

### 自定义歌词

1. 进入设置页的“自定义歌词”。
2. 点击“获取 ID”，从当前正在 Apple Music 播放的歌曲读取 Apple Music ID、标题和艺术家；也可以手动填写 ID。
3. 选择一种歌词来源：粘贴或导入本地 TTML、按 Apple Music ID 从 AMLL 导入，或输入网易云歌曲 ID 导入逐字 YRC 歌词。
4. 保存映射并启用对应歌曲。
5. 强制停止并重新打开 Apple Music，使替换生效。

自定义歌词支持编辑、删除和按名称或 Apple Music ID 搜索。歌词正文会在写入和注入前进行大小、结构和哈希校验，无法通过校验时会保留 Apple Music 原歌词。

自定义歌词还支持 ZIP 备份与恢复。恢复时可以选择覆盖冲突歌词，或保留当前版本。

从 AMLL 导入时，如果取回的 TTML 是 AMLL 格式，模块会先自动转换为 Apple Music 格式再填入编辑框。

AMLL 和网易云导入属于用户主动操作，可能需要网络连接；模块不会在播放过程中自动请求歌词服务。

### 歌词字体

1. 在设置页的“歌词字体”中选择 TTF 或 OTF 文件。
2. 等待字体导入完成。
3. 强制停止并重新打开 Apple Music。
4. 如需恢复，点击“恢复原字体”并重新打开 Apple Music。

字体只覆盖播放器歌词，不修改系统字体或 AM++ 设置页字体。MiSans 替换效果见上方演示图。

### 手机液态玻璃

在设置页开启“手机液态玻璃底栏”后重新打开 Apple Music。该功能只在手机路径启用，会修改底部导航栏和 mini-player 的背景材质与选中状态动画，目前仍可能出现视觉闪烁。

### 隐藏启动器图标

在设置页开启“隐藏启动器图标”即可移除桌面入口。隐藏后，从 LSPosed 的 AM++ 模块详情进入设置页。

## 构建技术

- [Kotlin](https://kotlinlang.org/)，项目主要实现语言。
- [Android Gradle Plugin](https://developer.android.com/build)，用于 Android 应用构建。
- [libxposed API / service](https://github.com/LSPosed/LSPosed)，用于模块加载、Hook 和跨进程配置文件服务。
- [BlurView](https://github.com/Dimezis/BlurView)，用于手机液态玻璃的背景模糊。

## 从源码构建

### 环境

- JDK 17
- Android SDK 37
- Android Build Tools 37.0.0
- 项目自带 Gradle Wrapper

Windows：

```powershell
.\gradlew.bat test lintVitalRelease assembleRelease
```

Linux 或 macOS：

```bash
chmod +x gradlew
./gradlew test lintVitalRelease assembleRelease
```

生成的 Release APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

本地生成正式 Release APK 需要签名配置。首次构建时，将 `keystore.properties.example` 复制为被 Git 忽略的 `keystore.properties`，并填写 keystore 路径、密码与别名。未提供签名配置时仍可构建 Debug APK，但不能生成可发布的正式签名产物。

## 项目结构

```text
app/src/main/java/       模块入口、设置页、配置、歌词和功能实现
app/src/main/resources/  libxposed 模块元数据
app/src/test/             JVM 单元测试与结构回归测试
docs/images/             项目演示图
docs/adr/                 架构决策记录
docs/apple-music-target-adaptation.md  Apple Music 版本适配手册
scripts/                  可选的真机回归与录屏分析脚本
```

`scripts/` 中的设备脚本需要 ADB；部分液态玻璃录屏检查还需要 root、Python、OpenCV，并针对参考设备的分辨率和坐标编写。运行前通过 `-Serial`、`-Device` 或 `ANDROID_SERIAL` 指定设备。

## 路线图

- [x] 平板横屏双栏播放器
- [x] 双向歌词模糊
- [x] 自定义歌词注入与备份恢复
- [x] 歌词字体导入与恢复
- [ ] 完善手机液态玻璃的冷启动和播放器收回稳定性
- [ ] 持续适配后续 Apple Music 版本

## 贡献

欢迎提交 Issue 和 Pull Request。

提交涉及代码的 PR 前，请至少运行：

```text
test
lintVitalRelease
assembleRelease
```

涉及界面行为时，请在 Issue 或 PR 中附上设备型号、Android 版本、Apple Music 版本以及截图或录屏。适配 Apple Music 新版本时，请先阅读 [Apple Music 新版本适配手册](docs/apple-music-target-adaptation.md)。

## 隐私与权限

- 模块声明了 `INTERNET` 权限，仅用于设置页中用户主动触发的 AMLL 或网易云歌词导入。
- 模块不会在播放过程中自动联网识别歌曲或请求歌词。
- 模块不申请存储或通知运行时权限；本地文件通过 Android 文件选择器读取。
- Apple Music 功能设置保存在 Xposed 框架管理的 remote preferences 中，歌词和字体文件保存在框架提供的 remote file 中。
- 模块不包含分析服务。启动器图标状态由 Android PackageManager 本地保存。

## 联系方式

- [提交 Issue](https://github.com/Zennmn/AM-plus-plus/issues)
- [查看 Releases](https://github.com/Zennmn/AM-plus-plus/releases)

## 许可证

本项目以 [MIT License](LICENSE) 开源。第三方代码与依赖仍分别遵循其原始许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

- [AMLyricBlur](https://github.com/a23bc/amlyricblur)：双向歌词模糊核心的移植来源。

<p align="right">(<a href="#top">返回顶部</a>)</p>
