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
- ME! 翻译 `<text>` 内的和声写法：`<span xmlns=... xmlns:ttm=... ttm:role="x-bg">(Yeah)</span>`（带命名空间重声明）——**但实测 AM 本体对 text 内 span 不识别**，本项目用纯文本

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
<text for="L1">译文</text>
```

**格式要求：**
- 每个 `<text for>` 对应一个 `<p>` 的 key，**每行都有**（缺 17 行 → 整条翻译轨不识别；实测仅首行缺 1 行可容忍）
- **纯文本，零改写，零 span**
- 无翻译的哼唱/拟声行：**原词回填**作为 `<text>` 内容（回填同步剥引号/不成对括号）

**实测雷区：**
1. `<text>` 内塞 x-bg span → **整条翻译轨失效**
2. 翻译文本去掉「」『』 → **整条不识别**；原样保留 → 识别（AM 靠「」『』把译文对应到正文双声部/x-bg 结构）
3. 和声行的翻译并入主句翻译时，和声部分加全角括号（`（没什么时间了）`）显示清晰且不影响识别
4. 正文（歌词）去「」『』不影响识别——只有翻译轨必须保留

## 7. 段落（`<div>`）

- `<div itunes:song-part="Verse">`；取值：Verse/Chorus/PreChorus/Bridge/Intro/Outro/PostChorus/Refrain/Instrumental（官方建议值，任意值也可）
- key 编号跨段落**连续**
- 无段落标注时用空 `<div>`（AM 也能显示）

## 8. 备注

- Ruby 注音：`tts:ruby`（container/base/textContainer/text）AMLL 支持，本项目未用
- 翻译轨 `type`：`subtitle` 普通译文 / `replacement` 替换型
- 元数据 `by:`（歌词作者）未利用
