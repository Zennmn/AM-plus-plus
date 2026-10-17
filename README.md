<a id="top"></a>

<p align="center">
  <img src="docs/images/b851cbb7f571c6666f1a41377baa778b.jpg" alt="AM++ icon" width="180">
</p>

<h1 align="center">AM++</h1>

<p align="center">
  简体中文 | <a href="README.en.md">English</a>
</p>

<p align="center">
  Apple Music 的 Android 增强模块：平板双栏、歌词模糊与字体、自定义歌词、歌曲名修正、液态玻璃底栏。
</p>

<p align="center">
  <a href="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml"><img src="https://github.com/Zennmn/AM-plus-plus/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/Zennmn/AM-plus-plus/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Zennmn/AM-plus-plus" alt="GNU GPL v3.0"></a>
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+">
  <img src="https://img.shields.io/badge/libxposed-API%20102-7F52FF" alt="libxposed API 102">
</p>

<p align="center">
  没有 Lsposed？可以直接安装<a href="https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded-2026.08.10-r1">npatch 嵌入版</a>。
</p>

<details>
<summary>目录</summary>

1. [项目简介](#项目简介)
2. [功能](#功能)
3. [效果展示](#效果展示)
4. [安装](#安装)
5. [使用](#使用)
6. [从源码构建](#从源码构建)
7. [项目结构](#项目结构)
8. [路线图](#路线图)
9. [贡献](#贡献)
10. [隐私与权限](#隐私与权限)
11. [许可证与致谢](#许可证与致谢)

</details>

## 项目简介

设置页嵌在 Apple Music 自己的设置列表中，入口是“AM++ 模块设置”。

支持通过 ZIP 导入和管理插件；开发说明见 [插件开发手册](docs/plugin-development.md)。

## 功能

| 功能 | 默认 | 说明 |
| --- | --- | --- |
| 平板双栏播放器 | 开启 | 平板横屏时左侧播放器、右侧实时歌词，并同时抑制 Editorial Video。 |
| 强制显示蜂窝数据入口 | 关闭 | Apple Music 6.5.2/1586、6.5.3/1599 和开发分支的 7.0.0-beta/1606、1607、1609、1611 上保留原生数据分组，并恢复蜂窝使用偏好的可用性判断。独立于双栏和歌曲名修正；重开 Apple Music 后显示。 |
| 双向歌词模糊 | 开启 | 当前高亮行清晰，前后歌词按距离逐渐模糊；手动滚动时暂停，停止约 1 秒后恢复。需要 Android 12 及以上。 |
| CJK 长尾歌词动画 | 开启 | 让 CJK 歌词复用 Apple Music 原生的 rush-gradient 动画。需重开 Apple Music。 |
| 歌曲名显示修正 | 关闭 | 按所选地区改写显示的歌曲名与元数据。模式：按歌曲原地区修正 / 固定中国大陆 / 固定日本。需重开 Apple Music。 |
| 自定义歌词 | 关闭 | 按 Apple Music ID 注入 TTML，支持手动 TTML、AMLL、AM-Lyrics、Lunabeat 导入与 ZIP 备份恢复。 |
| 自动实时补全 | 开启 | 自定义歌词开启后，为缺词、非逐字或缺翻译的歌曲自动查找歌词服务。 |
| 歌词字体 | 关闭 | 导入 TTF/OTF 应用到播放器歌词，可一键恢复原字体。 |
| 液态玻璃底栏 | 关闭 | Android 13 及以上：6.5.2/6.5.3 保留手机与双栏平板横屏的原有玻璃布局；开发分支的 7.0.0-beta/1606、1607、1609、1611 使用新版手机底导航/平板顶部导航与独立 mini，支持单栏和双栏。真机验收中；参照[功能保全与验收矩阵](docs/feature-preservation.md)。 |
| ios底栏动画 | 开启 | 开启后使用ios同款液态玻璃底栏动画，实时生效，无需重开。 |

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

### 液态玻璃底栏

<p align="center">
  <img src="docs/images/liquid-glass-demo.jpg" alt="液态玻璃底栏与迷你播放器（主页与资料库）" width="720">
</p>

## 安装

### Xposed 模块（推荐）

前置条件：已安装 Apple Music，以及一个支持 libxposed API 102 的 Xposed 框架。判断框架兼容性以框架核心报告的 API 版本为准，不要只看 Manager 应用版本。

1. 从 [Releases](https://github.com/Zennmn/AM-plus-plus/releases/latest) 下载并安装 AM++ APK。
2. 在 LSPosed 或兼容的 Xposed 管理器中启用 **AM++**。
3. 作用域只勾选 Apple Music（`com.apple.android.music`）。
4. 强制停止并重新打开 Apple Music。
5. 打开 Apple Music → 设置 → “AM++ 模块设置”。

### npatch 嵌入版

没有Lsposed的话可以选择<a href="https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded-2026.08.10-r1">npatch 嵌入版</a>，无法和官方版本共存。

## 使用

每个开关的含义见上方[功能](#功能)表，下面只写需要多步操作的流程。

### 自定义歌词

1. 进入设置页的“自定义歌词”，打开“自定义歌词替换”。
2. 点“获取 ID”，从当前播放的歌曲读取 Apple Music ID、标题和艺术家；也可以手动填写 ID。
3. 选择歌词来源：粘贴或导入本地 TTML，或按 Apple Music ID 从 AMLL、AM-Lyrics、Lunabeat 导入。
4. 保存映射并启用该歌曲。
5. 强制停止并重新打开 Apple Music。

自定义歌词支持编辑、删除和按名称或 Apple Music ID 搜索，也支持 ZIP 备份与恢复，恢复时可以选择覆盖冲突项或保留当前版本。校验不通过的 TTML 会被拒绝，此时保留 Apple Music 原歌词。从 AMLL 取回的 TTML 会先转换成 Apple Music 格式再填入编辑框。

AMLL 转换会按每行开始时间整理歌词、翻译和音译顺序；开始时间相同的翻译按原顺序去重并用换行合并，结束时间可以不同。多声部歌词和各行音译分别保留，合并译文放在该组第一行，其他行用空格占位以保持对应关系。在线更新和自动实时补全使用相同的转换规则。

手动编写 TTML 时可参考 [Apple Music TTML 格式说明](docs/apple-music-ttml-format.md)。

“自动实时补全”开启后，播放中检测到原生歌词缺失、不是逐字时间轴、或外语逐字歌词缺翻译，且没有可用手动歌词时，才会请求候选歌词；关闭后播放过程中不再请求。手动导入始终由用户主动触发。

Lunabeat 会缓存 manifest 和歌曲索引，只在远端 revision 变化时重新下载。

### 歌词字体

1. 在设置页的“歌词字体”中选择 TTF 或 OTF 文件。
2. 等导入完成，然后强制停止并重新打开 Apple Music。
3. 需要还原时点“恢复原字体”，再重开 Apple Music。

字体只覆盖播放器歌词，不修改系统字体或设置页字体。

### 液态玻璃底栏

打开“液态玻璃底栏”，强制停止并重新打开 Apple Music。生效范围是手机布局与开启“平板双栏播放器”的平板横屏（平板竖屏或双栏关闭时保持原生界面）；平板形态下底栏与迷你播放器并排在同一条底部胶囊行内（底栏在左、迷你在右、同高居中），整行约 2/3 屏宽居中、左右留白等长，展开播放器时右下小胶囊四边插值摊开为全屏。底栏使用 AndroidLiquidGlass 的 LiquidBottomTabs，迷你播放器使用 LiquidButton 材质和按压形变，播放控件仍是原生实现；页面背景通过共享硬件 RenderNode 采样。宿主接入与维护流程见 [宿主适配手册](docs/host-adaptation-guide.md)。

开启后可微调两个附加项（关闭液态玻璃时不显示、也不生效）：“底栏高度”（`0..48dp`，即底栏距屏幕底部的距离，同时调整内容底部留白与播放器 peek 高度）与“底栏背景模糊强度”（`0..24dp`，同时作用于底栏面板和迷你播放器）；两者均为重开 Apple Music 后生效。每项右上角有小恢复按钮，可单独一键回到默认值（`16dp` / `4dp`）。

第三个附加项“随滚动收缩”仅在已登记的 7.0.0-beta 手机端（1606/1607/1609）开放，平板及旧版本不启用，也不显示相关设置。该项默认开启，实时生效、无需重开：下滑超过 6dp 时，底栏胶囊收成 43dp 小胶囊并只显示当前选中项的图标，迷你播放器变窄后与它并排在同一行，搜索键在收缩过程中分离到该行右端；上滑、切换底栏项目或旋转屏幕都会展开。分离随弹簧进度连续发生，只有搜索键可见可点时才接受点击。底栏占用高度同步收缩，内容底部留白与播放器 peek 高度一起变化。若菜单无法识别出可分离的搜索项，则保持单胶囊收缩。mini 不可见时仍允许收缩，左右控件照常运动，中央留空。

滚动信号有两条来源：原生滚动容器按实际滚动位移判定；资料库、新建和部分搜索等 Compose 页面改由页面根节点的手势判定，手指上滑即页面下滑。两条来源共用手势起点归属，页面手势抬起后仍跟踪原生惯性滚动。从底栏交互带或迷你播放器发起的整次手势不触发收缩，手指移出区域后仍保持排除。手势判定只观察、不消费或改写事件；横向为主的页面滑动也不会触发收缩。

页面到顶或到底后继续朝边界外拖动，不触发底栏收缩或展开；只有页面还能朝该方向滚动时才响应，向内容内侧滚动仍正常生效。空页面或内容不足一屏时也不会仅因手指滑动改变底栏状态。

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

CI 还会检查玻璃渲染器源码，并对 `glass` 执行 Lint（PR 构建用 `assembleDebug` 代替 `assembleRelease`）。

生成的 Release APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

生成正式签名的 Release APK 需要签名配置：把 `keystore.properties.example` 复制为被 Git 忽略的 `keystore.properties`，填写 keystore 路径、密码与别名（也可以用 `AMPP_RELEASE_STORE_FILE` 等环境变量覆盖）。没有签名配置时仍可构建 Debug APK。

## 项目结构

```text
app/src/main/java/        模块入口、依赖装配、设置页、功能编排与 Android 存储
app/src/main/resources/   libxposed 模块元数据
core/                     纯配置/歌词业务、网络来源、缓存与布局策略
host-api/                 语义宿主能力、事件、安装结果与订阅
hook-runtime/             libxposed 包装、注册作用域与日志
host-applemusic/           版本 profile、反射/DexKit 解析与原生宿主接入
glass/                    AndroidLiquidGlass 渲染器（固定提交纳入）
backdrop/                 上游 Backdrop 库
docs/                     适配与功能文档
scripts/                  可选的真机回归、录屏分析与 host profile 校验脚本
```

## 路线图

- [x] 平板横屏双栏播放器
- [x] 双向歌词模糊
- [x] 自定义歌词注入与备份恢复
- [x] 歌词字体导入与恢复
- [x] 液态玻璃底栏与迷你播放器（手机；开启双栏的平板横屏生效范围待真机验收）
- [x] 歌曲名显示修正
- [ ] 持续适配后续 Apple Music 版本

## 贡献

欢迎提交 Issue 和 Pull Request。改动代码的 PR 请至少运行 `test`、`lintVitalRelease` 和 `assembleRelease`；涉及界面行为时，请在 Issue 或 PR 中附上设备型号、Android 版本、Apple Music 版本以及截图或录屏。适配 Apple Music 新版本前，请先读 [宿主适配手册](docs/host-adaptation-guide.md)。完整文档入口见 [文档目录](docs/README.md)。

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
