# 旧版功能保全与验收矩阵

基线：97c4b69；schema=15；Apple Music 1583/1586/1599。自动测试不等同真机验收。

7.0.0-beta/1606 的历史适配记录可从 Git 历史查阅，真机验收仍需逐项确认。下表保留旧版验收基线；新平板玻璃按 7.0 顶部导航和独立底部 mini 实现。

1606 双栏保留原生封面尺寸、缩放、暂停比例和视频内容；关闭歌词后的居中目标通过封面 X 补偿接入原生展开/收起过程，下半区保持居中显现。具体契约见 [宿主适配手册](host-adaptation-guide.md#1606-平板双栏与媒体组件)。这一变化仅针对 7.0；下表 6.5.x 的 Editorial 保全语义保持不变。

| 功能 | 保全语义 | 自动验证入口 | 真机状态 |
|---|---|---|---|
| 双栏 | 官方平板横屏、原生 SONG/QUEUE、独立右歌词、事务/旋转/锚点/渐变/封面 | DualPane、Tablet、StateAccessors 用例 | 待三版本对照 |
| Editorial | 双栏控制、仅平板横屏 URL、保留预览及 Music Video | TargetAdaptationBehavior | 待对照 |
| 模糊 | 双向、焦点、滚动暂停恢复、换歌、API31、-10..10px | Blur/Highlight 用例 | 待对照 |
| CJK | 单词作用域、汉字/日文/韩文、动画清理、不抢位移 | CjkKaraoke 用例 | 待对照 |
| 字体 | TTF/OTF、后台与重试、行词一致、恢复原字体 | Font、LyricsTypeface | 待导入/恢复 |
| 自定义歌词 | 获取ID、多ID、增删改查/禁用、TTML/指针校验、异步重应用 | CustomLyrics、Ttml 用例 | 待播放对照 |
| 在线/自动 | 三手动来源；自动 AMLL→Lunabeat→AM-Lyrics；manual/disabled优先；generation/退避/哈希/ETag | Online、AutoLyrics、Lunabeat 用例 | 待网络/离线/切歌 |
| 更新/ZIP | 批量、取消、进度、覆盖/保留冲突、事务恢复 | Update、Backup、Restore | 待恢复对照 |
| 元数据 | 原地区/cn/jp、所有展示表面、ISRC/原名证据、离线/持久缓存/优先级 | Metadata、Catalog、Cache 用例 | 待三模式/页面 |
| 玻璃 | API33，1586/1599；手机/旧平板横屏、导航/mini/手势/过渡/采样/恢复 | Glass、TabletGlass、LayerAlpha | 待帧/视觉/手势 |
| 玻璃参数/补偿 | gap16/0..48，blur4/0..24；独立恢复与显隐；玻璃接管补偿 | ModuleSettingsSchema、SettingsDraft | 待显隐/占位 |
| 蜂窝 | 默认false，1586/1599；构建作用域仅一次；availability独立 | CellularDataEntry | 待原生入口 |
| DPI | 0或160..640、仅宿主、冷启动/配置变化/失败失活 | AppleMusicDpiOverride | 待重启/旋转 |
| 设置 | 无launcher、原生入口、草稿/SAF归属/页面重建/清理 | EmbeddedSettings、EmbeddedOnlyArtifact | 待全流程 |
| 存储/迁移 | 原键/目录/ID/marker/schema/ZIP/SQLite；ordinary不覆盖资产；原子发布；只读重试 | ConfigurationMigration/Session/Storage/Index/Transaction | 待升级/回滚 |

1583 的玻璃/蜂窝保持不支持。6.5.0/1580 仅参考，不在生产注册范围。停用的全局目录语言功能不重新启用。

## 1606 平板双栏补充验收

以下补充对应当前平板双栏实现；上面的旧版验收基线不变。宿主单测和 release 构建已通过，完整真机验收仍待记录。

| 场景 | 应保留的行为 | 自动验证入口 | 真机状态 |
|---|---|---|---|
| 首次打开、旋转、重建 | SONG/QUEUE 控制区 25%；原生字体、缩进、图标/封面尺寸；首帧没有旧 footer 闪现 | TabletSongLayout、TabletNativeControlRows、TabletComponentStartup | 待完整验收 |
| 音量与底部动作 | 音量总宽度对齐进度条；短窗口压缩音量触摸空白；不添加输出按钮，原生底部动作不可见且不接收触摸；音量下方无残留水波纹，退出双栏恢复原生可见性；音量拖动可用 | TabletComponentGeometry、TabletComponentStartup | 待横竖比、触摸及切页对照 |
| 原生动作与反馈 | 右下 selector/状态稳定，队列调用原生，翻译原生；播放图标默认大小，水波纹外加 8dp、白色 20% | TabletNativeActionStyle、TabletTransportRipple、TabletComponentStartup | 待首点/连点/长按对照 |
| 换到无歌词歌曲 | 上一首文本或启用状态不能打开右栏；普通/自定义、网络许可和离线歌词按当前歌曲判定 | FragmentTabletLyricsAvailability、TabletComponentStartup | 待切歌、网络及离线对照 |
| 关闭歌词后切队列 | SONG/QUEUE 都居中，右栏继续关闭；可用歌词恢复只解除自动关闭，保留手动关闭状态 | TabletLyricsPaneMotion、TabletComponentStartup | 待切页和换歌对照 |
| 展开、收起、反向拖动 | 封面 mini 到居中目标连续，下半区不从左侧滑入；保留原生 scale/Y，重复帧和换 pane 不累计 X 补偿 | TabletLyricsPaneMotion、TabletComponentStartup | 待录屏、暂停/播放及动态封面对照 |

## 既有降级（独立修复）

| 子面 | 基线状态 | 重构验收规则 |
|---|---|---|
| 6.5.3 操作表元数据 | 已知目标歧义/降级 | 单独取证和修复，不能扩大到任意 binding |
| 6.5.3 Listen Now 封面连续性 | 已知降级 | 保持其余主页/元数据；单独取证、测试和真机验证 |

真机使用相同设备、宿主、设置、歌曲与操作脚本对照；每场景留日志/截图或录屏。性能至少五次，持续>10%退化须修复。缺少设备/原始旧包时保持待验收，不能写 PASS。
