# -*- coding: utf-8 -*-
"""TTML 格式校验器 — 按 ttml格式.md 规则检查 ttml 目录下所有 *.ttml。

用法: python validate_ttml.py [目录]
返回码 0 = 全部通过
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")

TT = "{http://www.w3.org/ns/ttml}"
TTM = "{http://www.w3.org/ns/ttml#metadata}"
XMLID = "{http://www.w3.org/XML/1998/namespace}id"
ITUNES = "{http://music.apple.com/lyric-ttml-internal}"

SECTIONS = {"Verse", "Chorus", "PreChorus", "Bridge", "Intro", "Outro", "PostChorus", "Refrain", "Instrumental"}
TIME_RE = re.compile(r"^(?:(?:(\d+):)?(\d{1,2}):(\d{1,2})\.(\d{1,3}))$")
PAREN = "()（）"


def t2s(s):
    m = TIME_RE.match(s)
    if not m:
        return None
    h, mi, se, ms = m.group(1), m.group(2), m.group(3), m.group(4)
    if int(mi) >= 60 or int(se) >= 60:
        return None
    return (int(h or 0) * 3600 + int(mi) * 60 + int(se)) * 1000 + int(ms.ljust(3, "0"))


def check(path):
    errs = []
    warns = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        return [f"XML 解析失败: {e}"], []
    root = tree.getroot()
    timing = root.get(f"{ITUNES}timing")
    if timing not in ("Word", "Line"):
        errs.append(f"itunes:timing 缺失或非法: {timing!r}")

    reg_agents = [a.get(XMLID) for a in root.findall(f".//{TT}head//{TTM}agent")]
    reg_agents = [a for a in reg_agents if a]
    used_agents = set()

    divs = [d for d in root.iter(TT + "div")]
    if not divs:
        errs.append("无 div 段落")
    keys = []
    n_p = 0
    for d in divs:
        sp = d.get(f"{ITUNES}song-part")
        if sp is not None and sp not in SECTIONS:
            errs.append(f"段落取值非法: {sp!r}")
    for p in root.iter(TT + "p"):
        n_p += 1
        b, e = p.get("begin"), p.get("end")
        key = p.get(f"{ITUNES}key")
        ag = p.get(TTM + "agent")
        if key is None or not re.match(r"^L\d+$", key):
            errs.append(f"{key or '?'}: itunes:key 缺失/非法")
        else:
            keys.append(int(key[1:]))
        if ag is None:
            errs.append(f"{key or '?'}: 缺 ttm:agent")
        else:
            used_agents.add(ag)
            if ag not in reg_agents:
                errs.append(f"{key or '?'}: agent {ag} 未在 head 注册")
        if b is None or e is None:
            errs.append(f"{key or '?'}: p 缺 begin/end")
            continue
        bs, es = t2s(b), t2s(e)
        if bs is None or es is None:
            errs.append(f"{key or '?'}: p 时间戳非法 begin={b} end={e}")
        elif es <= bs:
            errs.append(f"{key or '?'}: p begin >= end")
        spans = list(p.findall(TT + "span"))
        if not spans:
            errs.append(f"{key or '?'}: p 内无 span（空行）")
        for sp in spans:
            if sp.get("begin") is None or sp.get("end") is None:
                errs.append(f"{key or '?'}: span 缺 begin/end: {sp.text!r}")
        for sp in spans:
            sb, se = sp.get("begin"), sp.get("end")
            if sb and se and t2s(sb) == t2s(se):
                warns.append(f"{key or '?'}: 零时长 span {sb} {sp.text!r}")
        # x-bg 结构
        xbgs = [sp for sp in spans if sp.get(f"{TTM}role") == "x-bg"]
        for x in xbgs:
            xb, xe = x.get("begin"), x.get("end")
            inner = [s for s in x.iter(TT + "span") if s is not x]
            if inner:
                for inn in inner:
                    if inn.get("begin") is None or inn.get("end") is None:
                        errs.append(f"{key or '?'}: x-bg 内层 span 缺时间")
                # 外层覆盖检查
                if xb and xe and t2s(xb) is not None and t2s(xe) is not None:
                    ob, oe = t2s(xb), t2s(xe)
                    inn_bs = [t2s(i.get("begin")) for i in inner if i.get("begin") and t2s(i.get("begin")) is not None]
                    inn_es = [t2s(i.get("end")) for i in inner if i.get("end") and t2s(i.get("end")) is not None]
                    if inn_bs and min(inn_bs) < ob:
                        errs.append(f"{key or '?'}: x-bg 外层 begin 早于内层")
                    if inn_es and max(inn_es) > oe:
                        errs.append(f"{key or '?'}: x-bg 外层 end 晚于内层")
            # x-bg 内不允许括号
            txt = "".join(x.itertext())
            if any(c in PAREN for c in txt):
                errs.append(f"{key or '?'}: x-bg 内出现括号: {txt[:20]!r}")
            # x-bg 内层不能再有 x-bg
            if any(i.get(f"{TTM}role") == "x-bg" for i in inner):
                errs.append(f"{key or '?'}: x-bg 嵌套 x-bg")
    if keys:
        if keys != list(range(1, len(keys) + 1)):
            errs.append(f"itunes:key 不连续: {keys[:5]}...")
    # 翻译轨
    metas = root.findall(f".//{TT}head//{ITUNES}translations//{ITUNES}translation")
    for tr in metas:
        texts = tr.findall(f"{ITUNES}text")
        fors = [t.get("for") for t in texts]
        if len(fors) != n_p:
            errs.append(f"翻译轨 text 数 {len(fors)} != p 数 {n_p}")
        for f_ in fors:
            if f_ is None or not re.match(r"^L\d+$", f_):
                errs.append(f"翻译 text for 非法: {f_!r}")
            elif int(f_[1:]) > n_p or int(f_[1:]) < 1:
                errs.append(f"翻译 text for 越界: {f_}")
        if len(set(fors)) != len(fors):
            errs.append("翻译轨存在重复 for")
        for t in texts:
            if t.text is None and not list(t):
                continue
            if any(ch.tag.endswith("span") for ch in t):
                errs.append(f"翻译 text for={t.get('for')}: 内含 span（必须纯文本）")
    # agent 注册数 = 使用数
    if reg_agents and used_agents and set(reg_agents) != used_agents:
        errs.append(f"agent 注册 {sorted(reg_agents)} != 使用 {sorted(used_agents)}")
    return errs, warns


def main(dirs):
    files = sorted(f for d in dirs for f in glob.glob(os.path.join(d, "*.ttml")))
    if not files:
        print("未找到 ttml 文件")
        return 2
    total_err = 0
    for f in files:
        errs, warns = check(f)
        name = os.path.basename(f)
        if not errs and not warns:
            print(f"OK    {name}")
            continue
        total_err += len(errs)
        print(f"{'FAIL' if errs else 'WARN'}  {name}")
        for e in errs:
            print(f"    - {e}")
        for w in warns:
            print(f"    ~ {w}")
    print(f"\n===== 文件 {len(files)} 个，错误 {total_err} 条 =====")
    return 1 if total_err else 0


if __name__ == "__main__":
    dirs = sys.argv[1:] or [r"D:\project\geci\ttml"]
    sys.exit(main(dirs))
