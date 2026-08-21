# CJK Karaoke Animation 交接文档

## 目的

本分支保存 Apple Music 6.5.2/1586 的 CJK 长尾歌词动画实验。它不属于 `main` 稳定线，后续维护和设备验证都从本分支继续。

## 当前分支与远端

- 分支：`codex/cjk-karaoke-animation`
- 功能基线：`47de18a fix: block merged CJK chunks from native glow`
- 远端：[Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation](https://github.com/Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation)
- 主分支基线：`main`（当前不包含本实验功能）
- 当前分支已推送到 `origin`；不要把本分支代码直接合并到 `main`，除非完成新的设备验收。

## 已实现内容

1. 仅对 Apple Music `6.5.2/1586` 解析并 Hook：
   - `com.apple.android.music.player.z.a0`
   - `com.apple.android.music.utils.I0$a.a(CharSequence, Set)`
2. AM++ 不接管 Apple 的 duration/length 触发条件。`z.a0` 前只读取当前 `z$a.G/H -> e` 的 grouping metadata：`e.f`（累计 duration）、`e.g`（累计字符数）、`e.c`（词文本）和 `e.k`（拆分 binding）。只有规范化后恰好一个 CJK Unicode 字符、`e.f` 等于当前 native duration、`e.g == 1` 且没有多 binding 时，才临时放开 `k0/j0`；合并词保留 Apple 原始分类，不会进入长辉光分支。
3. 放行仍限定在 `z.a0` 的线程局部调用范围内，不会全局修改 Apple 的静态字符集合，也不会改变 `g0` 的原始 CJK 排版路径。
4. 设置页和嵌入式设置页都有独立开关：
   - key：`cjk_karaoke_animation_enabled`
   - 字段：`ModuleSettings.cjkKaraokeAnimationEnabled`
   - 默认值：`true`
   - 关闭后需要重启 Apple Music，feature 才不会注册 Hook。

## 临时生命周期探针

当前诊断提交会在 `AppleMusicCjkKaraokeAnimationTarget` 输出带 `[DEBUG-cjk-r2]` 前缀的日志，记录 `e.o/e.p`、Animator listener、词 View 的 alpha/translation/scale、TextView/Paint shadow 和背景 Drawable，以及动画结束或取消时的同一组状态。它还会给已发现的原生 Animator 加一个只读 `onAnimationEnd/onAnimationCancel` 观察 listener；这是临时诊断包，不应当作为长期发布包使用，拿到设备日志后应移除探针。

## 关键代码位置

- Hook 实现：`app/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt`
- Feature gate：`app/src/main/java/dev/amenhancer/module/hook/CjkKaraokeAnimationFeature.kt`
- 版本符号：`app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt`
- Feature 注册：`app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt`
- 设置模型/schema：`app/src/main/java/dev/amenhancer/module/model/ModuleModels.kt`、`app/src/main/java/dev/amenhancer/module/config/ModuleSettingsSchema.kt`
- 设置 UI：`app/src/main/java/dev/amenhancer/module/ui/SettingsActivity.kt`、`app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt`

## 已知边界

- 当前方案是“复用 Apple 原生动画分支”，不是 AM++ 自己绘制；AM++ 只阻止合并 CJK 词块进入原生长辉光分类。
- 判断失败时默认不放行，保持 Apple 原始行为；带组合音标、补充字符或多 binding 的文字会被保守跳过。
- Apple 版本、混淆类名和方法签名是私有契约；新增版本必须重新做 exact profile 和设备验证。
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
