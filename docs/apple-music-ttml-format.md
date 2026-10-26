# Apple Music TTML


## 1. 总体结构

```xml
<?xml version='1.0' encoding='utf-8'?>
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
    xmlns:tts="http://www.w3.org/ns/ttml#styling"
    xmlns:amll="http://www.example.com/ns/amll"
    xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
    itunes:timing="Word">
  <head>
    <metadata>
      <ttm:agent type="person" xml:id="v1"><ttm:name type="full">歌手名</ttm:name></ttm:agent>
      <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <songwriters><songwriter>词曲作者1</songwriter></songwriters>
        <translations>
          <translation type="subtitle" xml:lang="zh-Hans">
            <text for="L1">译文1</text>
            ...
          </translation>
        </translations>
      </iTunesMetadata>
    </metadata>
  </head>
  <body>
    <div itunes:song-part="Verse"><p .../>...</div>
  </body>
</tt>
```

## 2. 头部

| 项 | 要求 |
|---|---|
| `itunes:timing` | `"Word"`（词级）或 `"Line"`（行级） |
| `ttm:agent` | 每首歌至少注册 v1（独唱也必须有）；**注册数 = 实际使用数**（不建空 agent）；`type="person"` 独唱 / `type="group"` 合唱（AMLL 惯例合唱 = v1000）；名字放 `<ttm:name type="full">` |
| `songwriters` | 词曲作者；**编曲/制作人/原唱/和声/音乐协力/工作室成员不进** |
| `translations` | `translation type="subtitle" xml:lang="zh-Hans"`；`replacement` 用于简繁转换类 |

## 3. 行（`<p>`）

```xml
<p begin="0:34.339" end="0:39.939" itunes:key="L1" ttm:agent="v1">...</p>
```

- **`itunes:key` 必须连续 L1..Ln**，跨段落不中断
- 每行必须带 `ttm:agent`（单歌手也是 v1）
- **同一 agent 的行时间不允许重叠**（源 LRC 有真重叠时必须裁时间轴）；不同 agent 可以重叠；混音重叠除外
- 时间戳格式：`MM:SS.mmm`（可省略小时 `HH:MM:SS.fff`）；**MM/SS 必须 <60**；毫秒 1-3 位自动补零；`SS.fff` 秒数、`12.3s` 秒数格式亦可

## 4. 词级时间轴（`<span>`）

```xml
<span begin="0:34.339" end="0:34.609">Bout</span> <span begin="0:34.609" end="0:34.889">time</span>
```

- 每个词一个字面 span，**begin/end 必须显式**
- **空格：span 间字面空格最合规**；独立空格 span 也允许（时间取前一词 end 或 00:00）
- **零时长 span（begin==end）AM 可能不渲染**（注音/括号 token 会出现）
- 译文 `<text>` 内的和声使用 `span ttm:role="x-bg"`，并显式切回 TTML 命名空间，完整结构见第 6 节；不能只依据标签名判定它与正文的 `span` 相同。

## 5. 和声 x-bg（重点坑区）

```xml
<span ttm:role="x-bg" begin="0:52.718" end="0:53.948">
  <span begin="0:52.718" end="0:52.998">No</span> <span begin="0:52.998" end="0:53.948">time</span>
</span>
```

**格式要求：**
- **分组**：一个外层 `x-bg` span 包**整段**和声，内层逐字/逐词 span 各带时间
- 外层 span 带 begin/end（begin == 第一个内层 span 的 begin；end == 最后一个内层 span 的 end）；**外层不带时间也合规**（纯包裹，AMLL 文档允许）
- 内层每个词一个 span，显式 begin/end

**实测雷区（全部 AM 实测验证）：**
1. **每词单独一个 x-bg span → AM 不逐字高亮**（跳到段末两个字）
2. **x-bg 内括号带时间戳 span → "整组全亮→全灭→逐字高亮"闪 bug**（独立的 `( )` token 成了多余高亮单元，卡在 AM 组/字高亮模式切换点）
3. **x-bg 内括号做无时间戳纯文本也不可靠**：半角 `( )` 被 AM 吞掉不显示，全角 `（）` 开头被吞、句尾漏出孤零零 `）`
4. **结论：x-bg 内括号一律不输出**（x-bg 靠浅色区分，不需要括号标记）
5. ME! 官方参考的写法是括号**并入词 span**（`(Ye` / `ah)`）——可行但不逐字
6. AMLL 规范建议 x-bg 用**半角括号包裹**、最多一对括号、背景声先于主声时 span 放主声前否则行尾——**但 AM 实测括号显示不可靠（见上），本项目结论是括号不输出**，浅色即区分
7. 整行括号行（不绑定）归 **v2 声部**正常亮度显示，不套 x-bg；绑定进主句的才用 x-bg

## 6. 翻译轨（坑最深的区域）

```xml
<text for="L1">主唱译文 <span xmlns="http://www.w3.org/ns/ttml"
  xmlns:ttm="http://www.w3.org/ns/ttml#metadata" ttm:role="x-bg">（和声译文）</span></text>
```

**格式要求：**
- 每个 `<text for>` 对应一个 `<p>` 的 key，**每行都有**（缺 17 行 → 整条翻译轨不识别；实测仅首行缺 1 行可容忍）
- `iTunesMetadata`、`translations`、`translation` 和 `text` 属于 Apple 命名空间；上例位于这些容器内。
- 主唱译文是 `text` 的直接文字；每段和声是其独立的 TTML `span ttm:role="x-bg"` 子节点，必须通过 `xmlns` 切回 TTML，并为角色绑定 `ttm` 元数据命名空间。只写 `<span ttm:role="x-bg">` 会继承父节点的 Apple 命名空间，不能表达同一节点。
- 主唱与和声分别翻译；多个和声保留各自顺序，只有和声的段落保留空白主唱占位。一个段落仍只对应一个 `text for`。
- 未送翻译的中文、哼唱/拟声或空行以原文或空白占位回填，保留可对应关系；翻译中的原有括号与标点不随格式转换任意剥除。

**维护检查：**
1. 按命名空间 URI 核对轨道父节点和 TTML 和声子节点，不能用“译文内有 span”作为拒绝标准。
2. `for` 必须引用对应正文段落 key；不能将每个和声另列为同 key 的 `text`，也不能漏掉未翻译的段落占位。
3. 结合当前原生解析入口与使用者人工场景确认显示；文件结构正确、原生指针有效和译文实际显示是不同层次的证据。

## 7. 段落（`<div>`）

- `<div itunes:song-part="Verse">`；取值：Verse/Chorus/PreChorus/Bridge/Intro/Outro/PostChorus/Refrain/Instrumental（官方建议值，任意值也可）
- key 编号跨段落**连续**
- 无段落标注时用空 `<div>`（AM 也能显示）

## 8. 备注

- Ruby 注音：`tts:ruby`（container/base/textContainer/text）AMLL 支持，本项目未用
- 翻译轨 `type`：`subtitle` 普通译文 / `replacement` 替换型
- 元数据 `by:`（歌词作者）未利用

## 9. AMLL 导入时的时间轴整理

AMLL 导入转换器会按 `<p begin>` 的实际时间稳定排序，翻译和音译轨按原 `itunes:key` 跟随歌词行排列。时钟格式与偏移格式按数值比较，例如 `00:01.500`、`1.5s`、`1500ms` 表示同一开始时间；不改写行或词的原时间字符串。

开始时间相同的歌词行保留各自声部及结束时间。每条翻译轨中，这些行的非空译文按原顺序去重，再用换行合并到该组第一行；其余行保留单空格占位。音译仍分别对应原行，正文逐词时间及词间空白保持不变。

排序保留原段落属性；段落时间交错时拆成连续的段落片段，并更新已有段落的 `begin/end` 边界。已有 Apple 格式的辅助轨同样参与整理。无法识别的行开始时间或正文结构保持原顺序；带辅助轨的文档若行 key 缺失、重复或无法与轨道关联，整份时间轴保持原顺序。

此处描述当前导入转换器的实际行为；背景译文和音译 `span` 按第 6 节显式绑定 TTML 与 `ttm` 命名空间，并保留空格占位。手动在线导入、在线更新、自动实时补全和 `scripts/amll_to_apple_ttml.py` 使用同一规则；LLM 译文同样保持 Apple 轨道父节点与 TTML 和声子节点的命名空间边界。

## 10. 简体替换轨与正文和声

部分原生歌词同时提供 `translation type="replacement" xml:lang="zh-Hans"` 与正文 `x-bg`。现有“歌词效果优化”开关开启且简体系统语言门禁命中时，专项修复可用替换轨中的背景词文本补全正文和声；关闭时保持解析输入不变，自定义歌词开关不能替代该门禁。开启后即使自定义歌词关闭，普通原生歌词仍可修复。这不等于把整份文档转换成另一种格式，也不改变 Apple 对 replacement 轨的原生采用资格。

匹配从唯一的正文 `p@itunes:key` 和对应的 `text@for` 开始。`x-bg` 组按出现顺序对应，双方声明的组时间须数值等价。组内词数相同，每词必须有完整、有效且唯一的 begin/end，并按数值等价的时间对匹配。轨道容器与 `text` 属于 Apple 命名空间，其中每个 `x-bg` 必须显式切回 TTML，并使用元数据命名空间的 `ttm:role="x-bg"`。

只替换正文既有 `x-bg` 词 span 的文本内容；时间、标签、属性、主唱前景及其他轨道保持原样。缺对应内容或分组、词数、结构、时间不兼容时整行回退，其他可对应行仍可修复；重复 key、轨道歧义、根/容器命名空间不支持或超出输入上限时整份透传。支持范围与维护要求见[适配手册](host-adaptation-guide.md#simplified-replacement-backgrounds)。
