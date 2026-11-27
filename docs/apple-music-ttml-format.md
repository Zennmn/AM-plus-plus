# Apple Music TTML

本仓库的歌词使用 Apple Music 逐字 TTML。下面以现有歌词为准：元数据、翻译和音译可按歌曲需要添加，保留原有的时间轴和声部结构。

## 1. 总体结构

```xml
<?xml version="1.0" encoding="utf-8"?>
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
    xmlns:amll="http://www.example.com/ns/amll"
    xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
    xml:lang="ja" itunes:timing="Word">
  <head>
    <metadata>
      <ttm:agent type="person" xml:id="v1"/>
      <amll:meta key="musicName" value="歌名"/>
      <amll:meta key="artists" value="歌手名"/>
      <amll:meta key="album" value="专辑名"/>
      <amll:meta key="appleMusicId" value="123456789"/>
      <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <translations>
          <translation type="subtitle" xml:lang="zh-Hans">
            <text for="L1">天空</text>
          </translation>
        </translations>
        <transliterations>
          <transliteration xml:lang="ja-Latn">
            <text for="L1"><span begin="0:00.500" end="0:00.750">so</span><span begin="0:00.750" end="0:01.000">ra</span></text>
          </transliteration>
        </transliterations>
      </iTunesMetadata>
    </metadata>
  </head>
  <body dur="0:01.000">
    <div begin="0:00.500" end="0:01.000" itunes:songPart="Verse">
      <p begin="0:00.500" end="0:01.000" itunes:key="L1" ttm:agent="v1"><span begin="0:00.500" end="0:00.750">そ</span><span begin="0:00.750" end="0:01.000">ら</span></p>
    </div>
  </body>
</tt>
```

## 2. 头部

- 根元素用 `xml:lang` 标记正文语言，如 `ja`、`en`、`zh-Hans`。本库使用 `itunes:timing="Word"`；校验器也识别 `Line` 标记，但仍会检查行内的 `<span>`。
- 主声部通常用 `v1`。正文引用的声部都要在 `<head>` 注册，且不要注册未使用的声部。仓库使用 `person`、`group` 和 `other`；声部 ID 以实际引用为准。`<ttm:name type="full">` 可选。
- `songwriters` 可选，只填词曲作者。编曲、制作、原唱、和声或音乐协力等身份本身不属于这一字段。
- AMLL 元数据可选。上传器可读取 `artists`、`musicName`、`album` 和 `appleMusicId`。有多个 Apple Music ID 时，每个 ID 写成单独一条 `<amll:meta>`；上传器会把所选主 ID 以外的 ID 写入索引的 `alternateIds`。

## 3. 行（`<p>`）

- `itunes:key` 从 `L1` 起按正文顺序连续编号，跨段落不重置。
- 每行都要有 `ttm:agent` 和 `begin`、`end`，且行的结束时间晚于开始时间。
- 同一或不同声部的行都可以有意重叠。保留与演唱一致的时间轴，发现误标时再修正。
- 有冒号的时间可写为 `M:SS.fff` 或 `H:MM:SS.fff`，分钟和秒小于 60；小数可有 1–3 位，`.1` 表示 100 毫秒。也可写为总秒数，如 `12.3` 或 `12.3s`。

## 4. 逐字时间轴（`<span>`）

- 每个高亮单元用一个 `<span>`，显式填写 `begin` 和 `end`。需要显示词间空格时，可在相邻 `<span>` 之间直接写空格。
- 校验器对正文行的直接子 `<span>` 发出零时长警告（`begin` 和 `end` 表示同一时刻）。这种写法可以入库，但该单元可能不渲染；警告不会阻止上传。

## 5. 和声（`x-bg`）

```xml
<span ttm:role="x-bg">
  <span begin="0:52.718" end="0:52.998">(No</span> <span begin="0:52.998" end="0:53.948">time)</span>
</span>
```

- 一整段和声用一个 `ttm:role="x-bg"` 外层，内层逐字或逐词填写时间。外层可以省略时间；填写时用首个内层单元的开始时间和末个单元的结束时间。
- 括号可保留，也可省略，两种写法都不会触发括号警告。既有设备测试的显示结果有差异；制作新稿时，可以把括号并入首末词的 `<span>`，避免把括号另做高亮单元。
- 独立演唱的整行可以用单独的声部；绑定在主句中的背景声用 `x-bg`。

## 6. 翻译轨

- 普通译文放在 `translations/translation`，常用 `type="subtitle" xml:lang="zh-Hans"`；`replacement` 用于替换型文本。普通译文轨要覆盖正文的全部行，用 `<text for="L…">` 关联，不能重复引用同一行。无需翻译的哼唱或拟声可填回原词。
- `<text>` 可以是纯文本，也可以用内嵌 `<span>` 表示和声。两种写法在本库均可接受。需要兼顾不同客户端时，可用纯文本，并用全角括号区分并入主句的和声。原译文中用于区分声部的「」和『』建议保留。
- 旧稿中可见以 `und-Latn` 等语言标签放在翻译轨里的行级罗马音，这类轨道可省略部分行。新稿的音译用独立音译轨。

## 7. 音译轨

- 在 `iTunesMetadata` 内用 `transliterations/transliteration`，和 `translations` 同级。推荐标注音译语言，如 `ja-Latn`、`ko-Latn`、`zh-Latn` 或 `zh-Latn-pinyin`；仓库也保留未标语言的音译轨。
- 用 `<text for="L…">` 逐行对应正文。行级音译可以直接写文本；逐字音译沿用正文每个单元的 `begin`、`end` 和嵌套结构，包括已有的 `x-bg`，只替换文字。音译中的 `<span>` 和 `<text>` 继承 `iTunesMetadata` 的 Apple 命名空间，`ttm:role` 仍用 TTML 元数据命名空间。英文等无需转写的单元可照抄。
- 现有独立音译轨均覆盖正文的所有行。校验器目前不核对独立音译轨的覆盖率或时间对应关系。

## 8. 段落（`<div>`）

- 段落可带 `begin`、`end`；`<body>` 可带 `dur`。没有段落标签时，可以直接用 `<div>` 包住歌词行。
- 段落标签推荐用 `itunes:songPart`，也保留 `itunes:song-part`。常见值为 `Verse`、`Chorus`、`PreChorus`、`Bridge`、`Intro`、`Outro`、`PostChorus`、`Refrain` 和 `Instrumental`。当前校验器只对连字符写法检查取值。
