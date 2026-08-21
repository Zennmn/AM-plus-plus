# CJK Karaoke Animation 交接文档

## 目的

本分支保存 Apple Music 6.5.2/1586 的 CJK 长尾歌词动画实验。它不属于 `main` 稳定线，后续维护、设备验证或替换为 AM++ 自绘动画都从本分支继续。

## 当前分支与远端

- 分支：`codex/cjk-karaoke-animation`
- 当前提交：`39e9b08 feat: isolate CJK karaoke animation feature`
- 远端：[Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation](https://github.com/Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation)
- 主分支基线：`main`（当前不包含本实验功能）
- 当前分支已推送到 `origin`；不要把本分支代码直接合并到 `main`，除非完成新的设备验收。

## 已实现内容

1. 仅对 Apple Music `6.5.2/1586` 解析并 Hook：
   - `com.apple.android.music.player.z.a0`
   - `com.apple.android.music.utils.I0$a.a(CharSequence, Set)`
2. 在 `z.a0` 的线程局部调用范围内，临时放开 CJK 的 `k0/j0` 分类结果，以复用 Apple 原生 rush-gradient；不会全局修改 Apple 的静态字符集合，也不会改变 `g0` 的原始 CJK 排版路径。
3. 设置页和嵌入式设置页都有独立开关：
   - key：`cjk_karaoke_animation_enabled`
   - 字段：`ModuleSettings.cjkKaraokeAnimationEnabled`
   - 默认值：`true`
   - 关闭后需要重启 Apple Music，feature 才不会注册 Hook。

## 关键代码位置

- Hook 实现：`app/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt`
- Feature gate：`app/src/main/java/dev/amenhancer/module/hook/CjkKaraokeAnimationFeature.kt`
- 版本符号：`app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt`
- Feature 注册：`app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt`
- 设置模型/schema：`app/src/main/java/dev/amenhancer/module/model/ModuleModels.kt`、`app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt`
- 设置 UI：`app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt`、`app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt`

## 已知边界

- 当前方案是“复用 Apple 原生动画分支”，不是 AM++ 自己绘制的稳定实现；用户已观察到 CJK 仍可能出现动画错位、跳动或宽度问题。
- 后续更稳妥的路线是：在 Apple adapter 完成逐词 View 绑定后，由 AM++ 自己创建 `ValueAnimator`/渐变遮罩，并处理 View 回收、行切换、混排字符和取消旧动画。
- 该自绘路线尚未实现。不要只在整行 RecyclerView 上加动画；必须拿到实际词 View 和可靠的逐词时序。
- Apple 版本、混淆类名和方法签名是私有契约；新增版本必须重新做 exact profile 和设备验证。

## 继续维护步骤

```powershell
git fetch origin
git switch codex/cjk-karaoke-animation
git pull --ff-only
```

修改后至少运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintVitalRelease :app:assembleRelease
git diff --check
```

如需回到稳定线：

```powershell
git switch main
```

## 现有证据与过程记录

- 逆向结论、DEX/smali/native 符号和行为矩阵：仓库根目录 `findings.md`
- 时间线、测试、构建和分支操作记录：仓库根目录 `progress.md`
- 当前阶段计划：仓库根目录 `task_plan.md`
- Apple Music 6.5.2 输入包：工作区未跟踪的 `Apple+Music_6.5.2_APKPure.xapk`；不要把它或 `androguard.db*` 误加入提交。

## 建议后续调用的 skills

- `planning-with-files-zh`：继续维护多步骤逆向/实现计划。
- `diagnosing-bugs`：设备上出现跳动、错位、回收复用或动画残留时建立诊断回路。
- `tdd`：为脚本分类、时序门槛、View 回收和动画取消策略补回归测试。
- `code-review`：准备把实验功能合并回主线前，审查版本边界和 fail-open 行为。
- `github:github`：查看远端分支、创建 PR 或发布后续提交。
