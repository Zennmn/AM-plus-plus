# 液态玻璃重构与验收

适配后续 Apple Music 版本请参阅 [液态玻璃新版本适配](liquid-glass-adaptation.md)，其中列出当前版本门控、Hook/资源依赖、展开交接和验收步骤。

## 实现边界

- 基准：AndroidLiquidGlass `65ab177e90e5c1d8c62e70cf7755841982da65f6`，Backdrop 2.0.1。
- 宿主：Apple Music 6.5.2 (1586)，Android 13+，宿主判定的手机布局。
- 导航保留 Apple Music 菜单、强调色、重复点击、深链和返回栈；渲染使用参考 LiquidBottomTabs。
- 迷你播放器保留原生 View、播放/下一首/长按/展开事件；NativeLiquidButton 使用与底栏面板一致的材质（8dp 模糊、24dp/24dp 透镜、40% 深浅色蒙层），保留参考按钮按压形变公式。此项为用户后续指定的外观调整。
- 新增 ViewBackdrop 只录制 `navigation_host_group`，供两块玻璃共享；不截图、不缩小背景、不录制玻璃自身。
- 低版本、平板、未匹配的宿主及接入失败使用原生 UI。设置默认关闭，修改后强制停止并重新打开宿主。

参考材质和 spring 参数没有重新拟合。宿主适配改变内容、强调色、外部布局及按钮高度：导航 64dp、选中胶囊 56dp；迷你播放器 64dp；两侧 16dp、间距 8dp、底部安全区上方 8dp。原始渲染文件可运行 `python scripts/verify-glass-reference.py` 校验。

## 模块与生命周期

`backdrop` 保存原始 commonMain/androidMain 渲染源码和新 ViewBackdrop。`glass` 保存参考组件、模块自己的 Compose 所有者及原生输入桥。`app` 的 PhoneGlassRuntime/PhoneGlassSession 负责宿主发现、菜单映射、占位高度、滚动末项避让、播放器过渡和属性恢复。

模块与宿主之间只交换平台 View/Menu/Drawable 和基本数据，不强转宿主 AndroidX 对象。Compose 使用模块资源及生命周期，显式同步宿主配置。硬件录制随源内容/几何失效更新；静止时复用。销毁或失败后释放录制节点、组合、监听器并恢复被修改的原生属性。

## 自动验证

```powershell
python scripts/verify-glass-reference.py
python scripts/verify-glass-host.py Apple+Music_6.5.2_APKPure.xapk
.\gradlew.bat :app:testDebugUnitTest :glass:testDebugUnitTest
.\gradlew.bat :app:lintDebug :app:lintVitalRelease :glass:lintDebug :glass-lab:lintDebug
.\gradlew.bat :app:assembleRelease :glass-lab:assembleDebug :glass-lab:assembleDebugAndroidTest
```

构建采用 AGP 9.3.2、Gradle 9.7.1、Kotlin/Compose compiler 2.4.10、Compose Multiplatform 1.12.0、SDK 37.0 / Build Tools 37.0.0、Java 17 字节码。Android app/library 使用 AGP 内置 Kotlin。签名沿用现有配置；本机原配置的 `signing/` 路径不存在时，可把 `AMPP_RELEASE_STORE_FILE` 指向仓库根目录已有的签名文件，不需修改密钥或密码。

DEX 校验直接解析指定 XAPK 的方法定义及继承关系，检查导航、菜单与底部面板签名，不把反编译的可读字段别名当成真实名称。

## 对照应用与设备测试

Glass Lab 独立安装，不需要 Root。A/B 切换同一场景的原始 Compose LayerBackdrop 与 Android View → RenderNode 桥，使用相同参考组件、底图、密度和输入。支持深浅色及滚动背景。“原生前景”另行启用生产使用的 NativeLiquidButton，检查原生 TextView 与玻璃共同形变和点击传递。

连接 Android 13+ 设备后执行 `:glass-lab:connectedDebugAndroidTest`，硬件 PixelCopy 测试输出原始和桥接截图至应用外部文件目录 `glass-parity`，检查两种主题和两个滚动位置的静态差分。该测试必须在设备上运行；构建测试 APK 不代表测试已执行，也不覆盖 LSPosed 注入。

## 手动安装验收

1. 安装 AM++ Release APK，确认 LSPosed 已启用模块并作用于 `com.apple.android.music`。
2. 确认宿主版本为 6.5.2 (1586)。开启“手机液态玻璃底栏”，强制停止并重开 Apple Music。
3. 滚动首页、资料库和搜索结果，检查玻璃后方内容实时移动及末项完整可见。
4. 依次点击、重复点击、拖动选中胶囊，再快速反向拖动、松手、返回与打开深链。选中项须与页面一致。
5. 播放/暂停、下一首、切歌、长按迷你播放器，反复上拉展开与收起；检查原生功能、命中区域及背景无跳变。
6. 检查冷启动、前后台、旋转、深浅色、字体缩放、RTL、键盘和系统导航方式变化；关闭功能并重启后应恢复原生。
7. 安装 Glass Lab，在相同主题下对照静态折射和拖拽/松手形变。记录手机型号、Android、刷新率、显示缩放及录屏。

日志过滤：`adb logcat -d -s AppleMusicEnhancer AMGlassLab AndroidRuntime`。玻璃挂载成功报告 `phone_liquid_glass: ACTIVE`，失败记录异常并恢复原生。性能可另用 `adb shell dumpsys gfxinfo com.apple.android.music framestats`；不要把录屏帧率当成实际刷新率。

## 验收状态

本次三项视觉修正：模块 Compose 画布四周增加 32dp 绘制余量，组件尺寸、参考动画及实际点击区域不变；沿宿主父容器允许溢出并记录恢复状态，关闭原导航面板和收起播放器的矩形阴影轮廓，保留 elevation 以维持触摸层级；移除 `navigation_host_group` 恰好等于导航栏 inset 的底部 margin，让页面绘制到手势条后方，按钮安全距离仍保留。真机检测该 margin 原为 48px。中间测试版降低 elevation 曾导致底栏触摸失效，最终版已改为仅处理 outlineProvider，并实测点击、长按、拖动切页及播放器展开/返回。截图确认长按透镜上沿完整、旧矩形阴影断层及底部内容白带消失。

补充修复新发现、资料库和搜索的模糊缺失：识别可见的宿主 ComposeView 场景，避免把它们误判为静态页并缩小采样视口，同时排除隐藏的缓存列表。GPU 采样诊断确认这些场景的底色透明，原来只录制内容会使模糊后的透明区域透出清晰原图；ViewBackdrop 现在先录制窗口底色，再录制页面。参考模糊半径保持不变。三页均已在真机滚动后截图确认模糊恢复。诊断截图导出及临时日志已从生产代码移除。

2026-09-19 已在连接的 PJD110（Android API 36，Apple Music 1586）安装修复后的签名 Release。LSPosed 日志报告 `phone_liquid_glass: ACTIVE`；实际确认两块玻璃位于底部、点击和拖动胶囊可切换页面、迷你播放器可展开原生播放器并返回收起。

本次修复：宿主按包名创建模块 Context 时被包可见性过滤，改为使用 Xposed 提供的 ApplicationInfo 加载模块资源；Compose 改用模块自己的 Recomposer，避免从宿主查找不兼容的 AndroidX 生命周期所有者；布局调整保留宿主原有约束参数；收起时隐藏完整播放器内容，避免透明底栏下露出其画面。该设备的模块异常需从 LSPosed 模块日志读取，普通过滤 logcat 没有相应输出。

730 项单元测试通过，最新 Release 构建及 app Lint 通过。Glass Lab 安装会话长时间停留在已提交但未完成状态，已取消本次测试安装，未改动设备安全设置。因此硬件 PixelCopy 对照、完整主题/配置/手势矩阵及帧耗时验收仍待执行，不能据当前功能验证宣称已达到一比一。
