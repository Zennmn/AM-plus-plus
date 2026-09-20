<a id="top"></a>

<p align="center">
  <img src="docs/images/b851cbb7f571c6666f1a41377baa778b.jpg" alt="AM++ icon" width="180">
</p>

<h1 align="center">AM++</h1>

<p align="center">
  Apple Music 的 Android 增强模块：平板双栏、歌词模糊与字体、自定义歌词、歌曲名修正、手机液态玻璃底栏。
</p>

<p align="center">
  <a href="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml"><img src="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/Zennmn/AM-plus-plus/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Zennmn/AM-plus-plus" alt="GNU GPL v3.0"></a>
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+">
  <img src="https://img.shields.io/badge/libxposed-API%20102-7F52FF" alt="libxposed API 102">
</p>

<details>
<summary>目录</summary>

1. [项目简介](#项目简介)
2. [功能](#功能)
3. [效果展示](#效果展示)
4. [兼容性与限制](#兼容性与限制)
5. [安装](#安装)
6. [使用](#使用)
7. [从源码构建](#从源码构建)
8. [项目结构](#项目结构)
9. [路线图](#路线图)
10. [贡献](#贡献)
11. [隐私与权限](#隐私与权限)
12. [许可证与致谢](#许可证与致谢)

</details>

## 项目简介

AM++ 通过 libxposed API 102 注入 Apple Music（`com.apple.android.music`）。它不替换播放器，只在保留原有播放流程的前提下补充增强能力。

设置页嵌在 Apple Music 自己的设置列表中，入口是“AM++ 模块设置”，没有独立的桌面图标。首次启动时会把 Xposed remote preferences／remote file 中的旧配置迁移到 Apple Music 宿主私有目录，之后设置和文件都保存在那里。

## 功能

| 功能 | 默认 | 说明 |
| --- | --- | --- |
| 平板双栏播放器 | 开启 | 平板横屏时左侧播放器、右侧实时歌词，并同时抑制 Editorial Video。 |
| 双向歌词模糊 | 开启 | 当前高亮行清晰，前后歌词按距离逐渐模糊；手动滚动时暂停，停止约 1 秒后恢复。需要 Android 12 及以上。 |
| CJK 长尾歌词动画 | 开启 | 让 CJK 歌词复用 Apple Music 原生的 rush-gradient 动画。需重开 Apple Music。 |
| 歌词模糊半径偏移 | `0px` | 在基础半径上增减 `-10..10px`。 |
| 歌曲名显示修正 | 关闭 | 按所选地区改写显示的歌曲名与元数据。模式：按歌曲原地区修正 / 固定中国大陆 / 固定日本。需重开 Apple Music。 |
| 自定义歌词 | 关闭 | 按 Apple Music ID 注入 TTML，支持手动 TTML、AMLL、AM-Lyrics、Lunabeat 导入与 ZIP 备份恢复。 |
| 自动实时补全 | 开启 | 自定义歌词开启后，为缺词、非逐字或缺翻译的歌曲自动查找歌词服务。 |
| 歌词字体 | 关闭 | 导入 TTF/OTF 应用到播放器歌词，可一键恢复原字体。 |
| 手机液态玻璃底栏 | 关闭 | Android 13 及以上且 Apple Music 6.5.2/6.5.3 手机布局时，底栏与迷你播放器改用液态玻璃。 |
| 平板底栏补偿 | 关闭 | 平板底栏显示异常时使用的兼容选项。 |
| Apple Music 内部 DPI | 跟随系统 | 只改 Apple Music 进程的资源密度，`160..640`，`0` 表示跟随系统。需完全重开 Apple Music。 |

## 效果展示

### 自定义歌词注入

<p align="center">
  <img src="docs/images/bf32d15a3519cef0051d8a208b58ab42.jpg" alt="自定义歌词注入示例一" width="48%">
  <img src="docs/images/918d640313475d68cf66fff0e63f4e19.jpg" alt="自定义歌词注入示例二" width="48%">
</p>

### MiSans 字体替换

<p align="center">
  <img src="docs/images/537369a74adc232f855165263c9ff1cc.jpg" alt="MiSans 字体替换前后对比" width="900">
</p>

### 平板双栏播放器与歌词模糊

<p align="center">
  <img src="docs/images/tablet-dual-pane-player-open-source-blur.png" alt="平板横屏双栏播放器与歌词模糊" width="900">
</p>

### 手机液态玻璃底栏

<p align="center">
  <img src="docs/images/liquid-glass-demo.jpg" alt="手机液态玻璃底栏与迷你播放器（主页与资料库）" width="720">
</p>

## 兼容性与限制

| 项目 | 支持范围 |
| --- | --- |
| Android | 8.0（API 26）及以上；双向歌词模糊需 12（API 31）及以上；手机液态玻璃需 13（API 33）及以上 |
| Xposed 框架 | 实现 libxposed API 102、remote preferences 和 remote file 的框架 |
| Apple Music | `6.5.1 (1583)`、`6.5.2 (1586)`、`6.5.3 (1599)` |

- 未列出的 Apple Music 版本 fail-closed：不装载 Hook，不会按旧符号猜测。
- 兼容性由精确 profile 和结构契约定位，Apple Music 升级后需要重新适配，流程见 [Apple Music 新版本适配手册](docs/apple-music-target-adaptation.md)。
- 6.5.3 (1599) 仍有两处降级，各自只影响一个子面：播放菜单／操作表的元数据改写、主页 Listen Now 封面连续性。
- 功能开关不会热卸载已安装的 Hook，改动后必须强制停止并重新打开 Apple Music。
- 自定义 TTML 上限 512 KiB。
- 手机液态玻璃只作用于手机布局；平板和其他版本继续使用原生底栏。

## 安装

### Xposed 模块（推荐）

前置条件：已安装 Apple Music，以及一个支持 libxposed API 102 的 Xposed 框架。判断框架兼容性以框架核心报告的 API 版本为准，不要只看 Manager 应用版本。

1. 从 [Releases](https://github.com/Zennmn/AM-plus-plus/releases/latest) 下载并安装 AM++ APK。
2. 在 LSPosed 或兼容的 Xposed 管理器中启用 **AM++**。
3. 作用域只勾选 Apple Music（`com.apple.android.music`）。
4. 强制停止并重新打开 Apple Music。
5. 打开 Apple Music → 设置 → “AM++ 模块设置”，确认页面显示已连接 libxposed API 102 后再修改设置。

### npatch 嵌入版

不想装 Xposed 框架时，可以改用把 AM++ 直接嵌进 Apple Music 的[嵌入版](https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded-2026.08.10-r1)。该发布页会累积多个版本，文件名开头的数字就是打包日期（例如 `920` 表示 9 月 20 日的版本），装数字最大的那个；里面的 `.apks` 需要用 NPatch 或 SAI 这类分卷安装器安装。

嵌入版与官方 Apple Music 签名不同，无法共存，安装前通常要先卸载官方版本，本地已下载的音乐会一并清除。它内置的模块按打包当天的提交构建，可能落后于最新 Release。

## 使用

每个开关的含义见上方[功能](#功能)表，下面只写需要多步操作的流程。

### 自定义歌词

1. 进入设置页的“自定义歌词”，打开“自定义歌词替换”。
2. 点“获取 ID”，从当前播放的歌曲读取 Apple Music ID、标题和艺术家；也可以手动填写 ID。
3. 选择歌词来源：粘贴或导入本地 TTML，或按 Apple Music ID 从 AMLL、AM-Lyrics、Lunabeat 导入。
4. 保存映射并启用该歌曲。
5. 强制停止并重新打开 Apple Music。

自定义歌词支持编辑、删除和按名称或 Apple Music ID 搜索，也支持 ZIP 备份与恢复，恢复时可以选择覆盖冲突项或保留当前版本。校验不通过的 TTML 会被拒绝，此时保留 Apple Music 原歌词。从 AMLL 取回的 TTML 会先转换成 Apple Music 格式再填入编辑框。

“自动实时补全”开启后，播放中检测到原生歌词缺失、不是逐字时间轴、或外语逐字歌词缺翻译，且没有可用手动歌词时，才会请求候选歌词；关闭后播放过程中不再请求。手动导入始终由用户主动触发。

Lunabeat 会缓存 manifest 和歌曲索引，只在远端 revision 变化时重新下载。

### 歌词字体

1. 在设置页的“歌词字体”中选择 TTF 或 OTF 文件。
2. 等导入完成，然后强制停止并重新打开 Apple Music。
3. 需要还原时点“恢复原字体”，再重开 Apple Music。

字体只覆盖播放器歌词，不修改系统字体或设置页字体。

### 手机液态玻璃

打开“手机液态玻璃底栏”，强制停止并重新打开 Apple Music。底栏使用 AndroidLiquidGlass 的 LiquidBottomTabs，迷你播放器使用 LiquidButton 材质和按压形变，播放控件仍是原生实现；页面背景通过共享硬件 RenderNode 采样。逐项依赖与维护流程见 [液态玻璃新版本适配](docs/liquid-glass-adaptation.md)。

### 歌曲名显示修正

打开“歌曲名显示修正”并选好模式后重开 Apple Music。关闭时跟随 Apple Music 账号地区；开启后由模块按所选地区解析，并把结果缓存下来。

## 从源码构建

环境：JDK 17、Android SDK 37、Android Build Tools 37.0.0，以及项目自带的 Gradle Wrapper。项目用 Kotlin 编写，构建基于 Android Gradle Plugin，模块加载与跨进程配置使用 libxposed API/service。

Windows：

```powershell
.\gradlew.bat test :app:lintDebug :app:lintVitalRelease :app:assembleRelease
```

Linux 或 macOS：

```bash
chmod +x gradlew
./gradlew test :app:lintDebug :app:lintVitalRelease :app:assembleRelease
```

CI 还会检查玻璃渲染器源码并构建 `glass` 与 `glass-lab`（PR 构建用 `assembleDebug` 代替 `assembleRelease`）。

生成的 Release APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

生成正式签名的 Release APK 需要签名配置：把 `keystore.properties.example` 复制为被 Git 忽略的 `keystore.properties`，填写 keystore 路径、密码与别名（也可以用 `AMPP_RELEASE_STORE_FILE` 等环境变量覆盖）。没有签名配置时仍可构建 Debug APK。

## 项目结构

```text
app/src/main/java/        模块入口、设置页、配置、歌词与各功能 Hook
app/src/main/resources/   libxposed 模块元数据
app/src/test/             JVM 单元测试与结构回归测试
glass/                    AndroidLiquidGlass 渲染器（固定提交纳入）
backdrop/                 上游 Backdrop 库
glass-lab/                玻璃对比参照应用，不随模块发布
docs/images/              演示图
docs/                     适配手册与逐版本适配记录
scripts/                  可选的真机回归、录屏分析与 host profile 校验脚本
```

`scripts/` 中的设备脚本需要 ADB；部分液态玻璃检查还需要 root、Python 和 OpenCV，并按参考设备的分辨率写死了坐标，运行前用 `-Serial`、`-Device` 或 `ANDROID_SERIAL` 指定设备。`verify-host-profile.py` 不需要设备，只读校验 APK 里的 profile 符号。详见 [scripts/README.md](scripts/README.md)。

## 路线图

- [x] 平板横屏双栏播放器
- [x] 双向歌词模糊
- [x] 自定义歌词注入与备份恢复
- [x] 歌词字体导入与恢复
- [x] 手机液态玻璃底栏与迷你播放器
- [x] 歌曲名显示修正
- [ ] 补齐 Apple Music 6.5.3 的两处降级
- [ ] 持续适配后续 Apple Music 版本

## 贡献

欢迎提交 Issue 和 Pull Request。改动代码的 PR 请至少运行 `test`、`lintVitalRelease` 和 `assembleRelease`；涉及界面行为时，请在 Issue 或 PR 中附上设备型号、Android 版本、Apple Music 版本以及截图或录屏。适配 Apple Music 新版本前，请先读 [Apple Music 新版本适配手册](docs/apple-music-target-adaptation.md)。

## 隐私与权限

- 模块只声明 `INTERNET` 权限，用于设置页中用户主动触发的 AMLL、AM-Lyrics、Lunabeat 歌词导入，以及开启“自动实时补全”后符合条件的播放期歌词请求。
- 不申请存储或通知运行时权限，本地文件通过 Android 文件选择器读取。
- 模块不含分析服务，也不含独立入口 Activity。
- 首次迁移前的配置来自 Xposed remote preferences／remote file；迁移后普通设置、歌词索引和字体文件保存在 Apple Music 宿主私有目录。

## 许可证与致谢

本项目以 [GNU General Public License v3.0](LICENSE) 开源。第三方代码与依赖仍分别遵循其原始许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

- [AMLyricBlur](https://github.com/a23bc/amlyricblur)：双向歌词模糊核心的移植来源。
- [AndroidLiquidGlass / Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)：玻璃折射、高光、色散与参考交互。

<p align="right">(<a href="#top">返回顶部</a>)</p>
