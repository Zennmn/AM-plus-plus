# CJK Karaoke Animation 交接文档

## 目的

本分支保存 Apple Music 6.5.2/1586 的 CJK 逐词动画实验。它不属于 `main` 稳定线，后续维护、设备验证或替换动画策略都从本分支继续。

## 当前分支与远端

- 分支：`codex/cjk-karaoke-animation`
- 远端：[Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation](https://github.com/Zennmn/AM-plus-plus/tree/codex/cjk-karaoke-animation)
- 主分支基线：`main`（当前不包含本实验功能）
- 当前工作树中的 XAPK、`androguard.db*`、`.work/` 和 `github-lyrics-export-readable/` 均为未跟踪逆向输入，不应提交。

## 当前实现（AM++ 自绘 ValueAnimator）

旧方案曾在 `z.a0` 调用期间 Hook `I0$a.a(CharSequence, Set)`，临时改写 Apple 的 `j0/k0` 分类结果来复用原生 rush-gradient。该方案已从运行时移除，当前不再 Hook `I0$a.a`，也不修改 Apple 的字符集合或原生动画判定。

当前方案只把精确的 `z.a0(z$a, lineId, wordId, duration, isBackground)` 当作数据 seam：

1. `z$a.G/H` 是前景/背景 karaoke 的 `ArrayMap<Integer, PlayerLyricsViewModel$e>`；只按 `wordId` 从对应的 G/H 读取，避免误取 pronunciation 的 I/J map。
2. `e.i/e.j` 是单个词 binding；CJK 拆分时回退到 `e.k` binding 列表。
3. generated binding 的 `CustomTextView`（6.5.2 中为字段 `U`）是实际逐词 View。AM++ 在 `z.a0` after-hook 中对这些 View 启动自己的 `ValueAnimator`，duration 直接来自该词的 `LyricsTiming`。
4. 对 CJK 前景词，before/after 都会取消并清空宿主 `e.o` 及其 `e.p` 中带 `KARAOKE_WORD_LIFT_TAG` 的子动画；随后归一化被取消动画遗留的 translation/scale。`z.g0` 重建 flexbox、`z.p` RecyclerView rebind、`onDestroyView` 和 View detach 都会取消并恢复 AM++ 动画，避免 recycled View 残留。
5. 背景人声不接管；混排词按实际 binding/TextView 的 CJK 内容过滤。宿主 binding 文本尚未异步完成时，使用 `e.c` 的 native wordText 判断脚本。

## 关键代码位置

- 宿主 seam 与 binding 解析：`app/src/main/java/dev/amenhancer/module/hook/AppleMusicCjkKaraokeAnimationTarget.kt`
- AM++ 动画控制器/时序策略：`app/src/main/java/dev/amenhancer/module/hook/CjkLyricValueAnimator.kt`
- 动画策略 JVM 测试：`app/src/test/java/dev/amenhancer/module/hook/CjkLyricValueAnimatorTest.kt`
- Feature gate：`app/src/main/java/dev/amenhancer/module/hook/CjkKaraokeAnimationFeature.kt`
- 版本符号：`app/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt`
- Feature 注册：`app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt`
- 独立设置 key：`cjk_karaoke_animation_enabled`，字段 `ModuleSettings.cjkKaraokeAnimationEnabled`，默认值 `true`。

## 已知边界与真机验证重点

- 目前 exact profile 仍只针对 Apple Music `6.5.2/1586`；其它版本会 degraded，不会猜测混淆方法。
- 这是第一版安全自绘实现：动画改动 TextView 的 alpha/translation/scale，尚未模拟 Apple 的渐变 mask 绘制；CJK 前景词会先取消宿主 word-lift/rush animator，以减少属性竞争。
- `z.a0` 可能因宿主已有 `e.o` animator 提前 return；after-hook 仍使用 holder map 读取 binding，但若 binding 尚未生成会 fail-open。应重点测试：中文、日文、韩文、CJK+Latin 混排、长 duration 词、快速切歌、滚动回收和关闭开关后的重启行为。
- 不要把动画降级为整行 RecyclerView 动画；逐词 binding seam 是本实验的核心。

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
- Apple Music 6.5.2 输入包：未跟踪的 `Apple+Music_6.5.2_APKPure.xapk`；不要把它或 `androguard.db*` 加入提交。

## 建议后续调用的 skills

- `planning-with-files-zh`：继续维护多步骤逆向/实现计划。
- `diagnosing-bugs`：设备上出现跳动、错位、回收复用或动画残留时建立诊断回路。
- `tdd`：为脚本分类、时序门槛、View 回收和动画取消策略补回归测试。
- `code-review`：准备把实验功能合并回主线前，审查版本边界和 fail-open 行为。
- `github:github`：查看远端分支、创建 PR 或发布后续提交。
