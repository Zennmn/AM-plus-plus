#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
amll_to_apple_ttml.py -- convert AMLL TTML into Apple Music TTML.

Standalone Python port of the AM++ module converter
(app/src/main/java/dev/amenhancer/module/lyrics/AmllTtmlFormatConverter.kt).
It rewrites the AMLL variant of TTML (translations / romanizations inline in
every lyric line, empty ``xmlns=""`` overrides) into the Apple Music format
(auxiliary tracks declared once in the head and linked back through
``itunes:key``).

The rewrite is textual on purpose: whitespace between ``<span>`` tags carries
word separation and a DOM round-trip would not preserve it. Only markup is
examined; lyric text nodes are copied verbatim.

Usage:
    python amll_to_apple_ttml.py input.ttml [-o output.ttml] [--validate]
    python amll_to_apple_ttml.py --adam 1158609134 [-o output.ttml]
    python amll_to_apple_ttml.py --selftest

``--adam`` downloads the file from the amll-dev/amll-ttml-db repository the
module uses. ``-`` reads stdin. Without ``-o`` the result goes to stdout; the
``converted:`` line is written to stderr so it never pollutes the TTML.

Python 3.8+ only, no third-party dependencies.
"""

from __future__ import annotations

import argparse
import dataclasses
import re
import sys
import textwrap
import urllib.request
from typing import Callable

ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
ROLE_TRANSLATION = "x-translation"
ROLE_ROMAN = "x-roman"
ROLE_BACKGROUND = "x-bg"

# Pinned so Apple Music on Android renders both auxiliary tracks.
LYRIC_LANGUAGE = "ko"
TRANSLATION_LANGUAGE = "zh-Hans"
TRANSLITERATION_LANGUAGE = "ko-Latn"

# How Apple labels a translation meant to be shown under the lyric.
TRANSLATION_TYPE = "subtitle"

# Stands in for a line the track has no text for, keeping the entry there.
ABSENT_TEXT = " "

AUXILIARY_ROLES = {ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND}

AMLL_TTML_DB_BASE = (
    "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main"
)


def _attribute_pattern(name: str) -> re.Pattern:
    return re.compile(
        r"\s" + re.escape(name) + r'\s*=\s*"([^"]*)"|\s' + re.escape(name)
        + r"\s*=\s*'([^']*)'"
    )


EMPTY_DEFAULT_NAMESPACE = re.compile(r"""\s+xmlns\s*=\s*(?:""|'')""")
ROLE_ATTRIBUTE = _attribute_pattern("ttm:role")
LANGUAGE_ATTRIBUTE = _attribute_pattern("xml:lang")
KEY_ATTRIBUTE = _attribute_pattern("itunes:key")
BEGIN_ATTRIBUTE_VALUE = _attribute_pattern("begin")
TIMING_ATTRIBUTE_VALUE = _attribute_pattern("itunes:timing")
ITUNES_DECLARATION = _attribute_pattern("xmlns:itunes")
SPAN_TIMING = re.compile(
    r"""\s(?:begin|end)\s*=\s*"[^"]*"|\s(?:begin|end)\s*=\s*'[^']*'"""
)
PARENTHESIZED = re.compile(r"^\s*[(（].*[)）]\s*$", re.DOTALL)


@dataclasses.dataclass
class TtmlFormatConversion:
    ttml: str
    converted: bool


@dataclasses.dataclass
class Edit:
    start: int
    end: int
    replacement: str


@dataclasses.dataclass
class TrackEntry:
    key: str
    main: str | None
    background: str | None

    @property
    def has_text(self) -> bool:
        return self.main is not None or self.background is not None


@dataclasses.dataclass
class DeclaredTracks:
    translations: bool
    transliterations: bool

    @property
    def any(self) -> bool:
        return self.translations or self.transliterations


@dataclasses.dataclass
class Element:
    attributes: str
    start: int
    content_start: int
    content_end: int
    end: int
    self_closing: bool


# --- Minimal XML element scanning -------------------------------------


def literal_end(text: str, open_: int) -> int:
    """Exclusive end of a comment, CDATA section or processing instruction."""
    if text.startswith("<!--", open_):
        terminator = "-->"
    elif text.startswith("<![CDATA[", open_):
        terminator = "]]>"
    elif text.startswith("<?", open_):
        terminator = "?>"
    else:
        return -1
    close = text.find(terminator, open_)
    return len(text) if close < 0 else close + len(terminator)


def tag_end(text: str, open_: int) -> int:
    """Index of the ``>`` closing the tag, ignoring ``>`` in attribute values."""
    quote = None
    index = open_ + 1
    while index < len(text):
        character = text[index]
        if quote is not None:
            if character == quote:
                quote = None
        elif character in ('"', "'"):
            quote = character
        elif character == ">":
            return index
        index += 1
    return -1


def is_element(text: str, open_: int, name: str) -> bool:
    if text[open_ + 1 : open_ + 1 + len(name)].lower() != name.lower():
        return False
    nxt = text[open_ + 1 + len(name)] if open_ + 1 + len(name) < len(text) else None
    return nxt is not None and (nxt == ">" or nxt == "/" or nxt.isspace())


def is_close_tag(text: str, open_: int, name: str) -> bool:
    if not text.startswith("</", open_):
        return False
    if text[open_ + 2 : open_ + 2 + len(name)].lower() != name.lower():
        return False
    nxt = text[open_ + 2 + len(name)] if open_ + 2 + len(name) < len(text) else None
    return nxt is not None and (nxt == ">" or nxt.isspace())


def element_name(text: str, open_: int, tag_end_: int) -> str | None:
    if text.startswith("</", open_):
        return None
    index = open_ + 1
    while index < tag_end_:
        character = text[index]
        if character.isspace() or character in ("/", ">"):
            break
        index += 1
    name = text[open_ + 1 : index]
    return name if name else None


def parse_element(text: str, name: str, open_: int, tag_end_: int) -> Element | None:
    self_closing = text[tag_end_ - 1] == "/"
    attributes_end = tag_end_ - 1 if self_closing else tag_end_
    attributes = text[open_ + 1 + len(name) : attributes_end]
    if self_closing:
        return Element(attributes, open_, tag_end_ + 1, tag_end_ + 1, tag_end_ + 1, True)
    depth = 1
    index = tag_end_ + 1
    while index < len(text):
        open2 = text.find("<", index)
        if open2 < 0:
            return None
        literal = literal_end(text, open2)
        if literal > 0:
            index = literal
            continue
        end2 = tag_end(text, open2)
        if end2 < 0:
            return None
        if is_close_tag(text, open2, name):
            depth -= 1
            if depth == 0:
                return Element(attributes, open_, tag_end_ + 1, open2, end2 + 1, False)
        elif is_element(text, open2, name) and text[end2 - 1] != "/":
            depth += 1
        index = end2 + 1
    return None


def next_element(text: str, name: str, from_: int) -> Element | None:
    index = from_
    while index < len(text):
        open_ = text.find("<", index)
        if open_ < 0:
            return None
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            return None
        if is_element(text, open_, name):
            return parse_element(text, name, open_, end)
        index = end + 1
    return None


def child_elements(text: str, parent: Element) -> list[Element]:
    children: list[Element] = []
    index = parent.content_start
    while index < parent.content_end:
        open_ = text.find("<", index)
        if open_ < 0 or open_ >= parent.content_end:
            break
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            break
        name = element_name(text, open_, end)
        child = parse_element(text, name, open_, end) if name else None
        if child is None:
            index = end + 1
            continue
        children.append(child)
        index = child.end
    return children


def attribute(attributes: str, pattern: re.Pattern) -> str | None:
    match = pattern.search(attributes)
    if match is None:
        return None
    return match.group(1) if match.group(1) is not None else match.group(2)


def clock_seconds(value: str | None) -> float | None:
    """Seconds in ``3:02.550`` or AMLL's offset form ``1.5s``; None when unreadable."""
    if value is None:
        return None
    clock = value if ":" in value else (value[:-1] if value.endswith("s") else value)
    parts = clock.split(":")
    if not 1 <= len(parts) <= 3:
        return None
    seconds = 0.0
    for part in parts:
        try:
            seconds = seconds * 60 + float(part)
        except ValueError:
            return None
    return seconds


# --- Track migration --------------------------------------------------


def to_apple_format(ttml: str) -> TtmlFormatConversion:
    if not ttml:
        return TtmlFormatConversion(ttml, False)
    # Judged per kind, so a head that already declares one track does not
    # hide the other kind still sitting inline. An <iTunesMetadata> on its
    # own is no such signal: AMLL writes one to carry <songwriters>.
    declared = declared_tracks(ttml)
    if declared.any and not has_undeclared_inline_track(ttml, declared):
        # The head already speaks for every kind the body carries, so this is
        # Apple's own document and nothing, the root included, is rewritten.
        return TtmlFormatConversion(ttml, False)
    migrated = migrate_auxiliary_tracks(ttml, declared)
    body = next_element(migrated, "body", 0)
    result = normalize_tags(
        migrated,
        word_timed=body is not None and has_timed_span(migrated, body),
    )
    return TtmlFormatConversion(result, result != ttml)


def declared_tracks(ttml: str) -> DeclaredTracks:
    return DeclaredTracks(
        translations=next_element(ttml, "translations", 0) is not None,
        transliterations=next_element(ttml, "transliterations", 0) is not None,
    )


def has_undeclared_inline_track(ttml: str, declared: DeclaredTracks) -> bool:
    """True when the body still carries a kind the head leaves undeclared."""
    body = next_element(ttml, "body", 0)
    if body is None:
        return False
    index = body.content_start
    while index < body.content_end:
        open_ = ttml.find("<", index)
        if open_ < 0 or open_ >= body.content_end:
            return False
        literal = literal_end(ttml, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(ttml, open_)
        if end < 0:
            return False
        role = attribute(ttml[open_ : end + 1], ROLE_ATTRIBUTE)
        if role == ROLE_TRANSLATION and not declared.translations:
            return True
        if role == ROLE_ROMAN and not declared.transliterations:
            return True
        index = end + 1
    return False


def migrate_auxiliary_tracks(ttml: str, declared: DeclaredTracks) -> str:
    """Moves inline ``x-translation`` / ``x-roman`` spans into a head <iTunesMetadata>."""
    metadata = next_element(ttml, "metadata", 0)
    if metadata is None:
        return ttml

    translations: list[TrackEntry] = []
    transliterations: list[TrackEntry] = []
    edits: list[Edit] = []

    cursor = metadata.end
    while True:
        line = next_element(ttml, "p", cursor)
        if line is None:
            break
        cursor = line.end
        key = attribute(line.attributes, KEY_ATTRIBUTE)
        if key is None:
            continue
        collect_line(ttml, line, key, declared, translations, transliterations, edits)

    # Every keyed line contributes an entry, so a line the source left
    # untranslated still holds its place; whether any entry carries text
    # decides if the track is written at all. The edits are applied either
    # way, because a line may carry a background vocal that needs its body
    # rewrite even when no track follows.
    if not any(e.has_text for e in translations) and not any(
        e.has_text for e in transliterations
    ):
        return apply_edits(ttml, edits)

    edits.append(
        declare_tracks(ttml, metadata, build_tracks(translations, transliterations), declared)
    )
    return apply_edits(ttml, edits)


def collect_line(
    ttml: str,
    line: Element,
    key: str,
    declared: DeclaredTracks,
    translations: list[TrackEntry],
    transliterations: list[TrackEntry],
    edits: list[Edit],
) -> None:
    # Only one track of each kind survives the parser constraint, so the
    # first translation and first romanization of the line are kept. An
    # empty span is no translation, so a later non-empty one still counts.
    translation = None
    roman = None
    background_translation = None
    background_roman = None

    children = child_elements(ttml, line)
    for child in children:
        role = attribute(child.attributes, ROLE_ATTRIBUTE)
        if role == ROLE_TRANSLATION:
            if not declared.translations:
                if translation is None:
                    translation = auxiliary_text(ttml, child)
                edits.append(removal(ttml, child, line.content_start))
        elif role == ROLE_ROMAN:
            if not declared.transliterations:
                if roman is None:
                    roman = auxiliary_text(ttml, child)
                edits.append(removal(ttml, child, line.content_start))
        elif role == ROLE_BACKGROUND:
            nested = child_elements(ttml, child)
            removals: list[Edit] = []
            for span in nested:
                nested_role = attribute(span.attributes, ROLE_ATTRIBUTE)
                if nested_role == ROLE_TRANSLATION:
                    if not declared.translations:
                        if background_translation is None:
                            background_translation = auxiliary_text(ttml, span)
                        removals.append(removal(ttml, span, child.content_start))
                elif nested_role == ROLE_ROMAN:
                    if not declared.transliterations:
                        if background_roman is None:
                            background_roman = auxiliary_text(ttml, span)
                        removals.append(removal(ttml, span, child.content_start))
            edits.extend(background_edits(ttml, line, children, child, nested, removals))

    translations.append(TrackEntry(key, translation, background_translation))
    transliterations.append(TrackEntry(key, roman, background_roman))


def background_edits(
    ttml: str,
    line: Element,
    siblings: list[Element],
    background: Element,
    nested: list[Element],
    auxiliary_removals: list[Edit],
) -> list[Edit]:
    """
    Rewrites the background vocal that stays in the body: its ``begin`` / ``end``
    go, because Apple reads the vocal's extent from its syllables, and a vocal
    starting ahead of the lyrics moves to the front of the line, because Apple
    places the highlight in document order.
    """
    # Without syllables to read the extent from, the attributes are the only
    # timing the vocal has, so they stay and it keeps its place.
    syllable = first_syllable_begin(ttml, nested)
    if syllable is None:
        return auxiliary_removals

    open_tag = ttml[background.start : background.content_start]
    rewrite = auxiliary_removals + [
        Edit(background.start, background.content_start, SPAN_TIMING.sub("", open_tag))
    ]
    lyric = first_syllable_begin(ttml, siblings)
    if siblings[0] is background or lyric is None or syllable >= lyric:
        return rewrite

    return [
        Edit(
            line.content_start,
            line.content_start,
            render(ttml, background.start, background.end, rewrite),
        ),
        removal(ttml, background, line.content_start),
    ]


def first_syllable_begin(ttml: str, candidates: list[Element]) -> float | None:
    """Begin time of the first timed lyric span among candidates, if any."""
    for candidate in candidates:
        if attribute(candidate.attributes, ROLE_ATTRIBUTE) in AUXILIARY_ROLES:
            continue
        value = clock_seconds(attribute(candidate.attributes, BEGIN_ATTRIBUTE_VALUE))
        if value is not None:
            return value
    return None


def declare_tracks(
    ttml: str,
    metadata: Element,
    tracks: str,
    declared: DeclaredTracks,
) -> Edit:
    """
    Places the tracks in the head, joining the <iTunesMetadata> AMLL already
    wrote for <songwriters> -- ahead of what it holds, the order Apple uses --
    rather than opening a second one beside it.
    """
    container = next_element(ttml, "iTunesMetadata", metadata.start)
    if container is not None and container.end > metadata.content_end:
        container = None
    if container is None and metadata.self_closing:
        return Edit(
            metadata.start,
            metadata.end,
            f"<metadata{metadata.attributes}>{wrap_tracks(tracks)}</metadata>",
        )
    if container is None:
        return Edit(metadata.content_end, metadata.content_end, wrap_tracks(tracks))
    if container.self_closing:
        return Edit(
            container.start,
            container.end,
            f"<iTunesMetadata{container.attributes}>{tracks}</iTunesMetadata>",
        )
    # A newly migrated transliteration belongs after a translations track that
    # was already present. Every other new track starts at the front, where
    # build_tracks keeps Apple's kind order.
    if declared.translations and not declared.transliterations:
        existing = next_element(ttml, "translations", container.content_start)
        if existing is not None and existing.end <= container.content_end:
            return Edit(existing.end, existing.end, tracks)
    return Edit(container.content_start, container.content_start, tracks)


def build_tracks(
    translations: list[TrackEntry],
    transliterations: list[TrackEntry],
) -> str:
    parts: list[str] = []
    append_track(parts, translations, "translations", "translation", TRANSLATION_LANGUAGE, TRANSLATION_TYPE)
    append_track(parts, transliterations, "transliterations", "transliteration", TRANSLITERATION_LANGUAGE, None)
    return "".join(parts)


def append_track(
    parts: list[str],
    entries: list[TrackEntry],
    container: str,
    item: str,
    language: str,
    type_: str | None,
) -> None:
    # Nothing to say for this kind at all, so the track is not opened --
    # rather than opened over a column of placeholders.
    if not any(e.has_text for e in entries):
        return
    parts.append(f"<{container}>")
    head = f"<{item}"
    if type_ is not None:
        head += f' type="{type_}"'
    head += f' xml:lang="{language}">'
    parts.append(head)
    for entry in entries:
        parts.append(f'<text for="{entry.key}">')
        parts.append(append_entry_text(entry))
        parts.append("</text>")
    parts.append(f"</{item}>")
    parts.append(f"</{container}>")


def append_entry_text(entry: TrackEntry) -> str:
    """
    The body of one <text>: the line's own text, then its background one
    wrapped in an ``x-bg`` span. A line without text of its own still opens
    with [ABSENT_TEXT], both to keep an entry per line and because Apple
    reads what precedes the ``x-bg`` span as the main translation.
    """
    result = entry.main if entry.main is not None else ABSENT_TEXT
    if entry.background is None:
        return result
    return (
        result
        + f'<span ttm:role="{ROLE_BACKGROUND}">'
        + parenthesized(entry.background)
        + "</span>"
    )


def parenthesized(text: str) -> str:
    """Apple parenthesizes a background track; AMLL leaves that to the reader."""
    return text if PARENTHESIZED.match(text) else f"({text})"


def wrap_tracks(tracks: str) -> str:
    return f'<iTunesMetadata xmlns="{ITUNES_NAMESPACE}">{tracks}</iTunesMetadata>'


def auxiliary_text(ttml: str, element: Element) -> str | None:
    """A span's content, or None when it holds nothing worth carrying over."""
    if element.self_closing:
        return None
    content = ttml[element.content_start : element.content_end]
    return content if content.strip() else None


def removal(ttml: str, element: Element, lower_bound: int) -> Edit:
    """Deletes the auxiliary span together with the whitespace that preceded it."""
    start = element.start
    while start > lower_bound and ttml[start - 1].isspace():
        start -= 1
    return Edit(start, element.end, "")


# --- Root / tag normalization -----------------------------------------


def normalize_tags(ttml: str, word_timed: bool) -> str:
    """
    Declares the timing mode and lyric language on the root and drops the
    empty default namespace override from every other element.
    """
    output: list[str] = []
    index = 0
    root_seen = False
    while index < len(ttml):
        open_ = ttml.find("<", index)
        if open_ < 0:
            output.append(ttml[index:])
            break
        output.append(ttml[index:open_])
        literal = literal_end(ttml, open_)
        if literal > 0:
            output.append(ttml[open_:literal])
            index = literal
            continue
        end = tag_end(ttml, open_)
        if end < 0:
            output.append(ttml[open_:])
            break
        tag = ttml[open_ : end + 1]
        if not root_seen and is_element(ttml, open_, "tt"):
            root_seen = True
            output.append(with_root_attributes(tag, word_timed))
        elif tag.startswith("</"):
            output.append(tag)
        else:
            output.append(EMPTY_DEFAULT_NAMESPACE.sub("", tag))
        index = end + 1
    return "".join(output)


def with_root_attributes(tag: str, word_timed: bool) -> str:
    insert_at = len(tag) - 2 if tag.endswith("/>") else len(tag) - 1
    # Both attributes are authoritative here, so any inherited value is
    # dropped before the pinned one is appended.
    head = tag[:insert_at]
    head = LANGUAGE_ATTRIBUTE.sub("", head)
    head = TIMING_ATTRIBUTE_VALUE.sub("", head)
    additions: list[str] = []
    if not ITUNES_DECLARATION.search(head):
        additions.append(f' xmlns:itunes="{ITUNES_NAMESPACE}"')
    additions.append(f' itunes:timing="{"Word" if word_timed else "Line"}"')
    additions.append(f' xml:lang="{LYRIC_LANGUAGE}"')
    return head.rstrip() + "".join(additions) + tag[insert_at:]


def has_timed_span(text: str, body: Element) -> bool:
    index = body.content_start
    while index < body.content_end:
        open_ = text.find("<", index)
        if open_ < 0 or open_ >= body.content_end:
            return False
        literal = literal_end(text, open_)
        if literal > 0:
            index = literal
            continue
        end = tag_end(text, open_)
        if end < 0:
            return False
        if is_element(text, open_, "span") and (
            attribute(text[open_ : end + 1], BEGIN_ATTRIBUTE_VALUE) is not None
        ):
            return True
        index = end + 1
    return False


def apply_edits(ttml: str, edits: list[Edit]) -> str:
    return ttml if not edits else render(ttml, 0, len(ttml), edits)


def render(ttml: str, from_: int, to: int, edits: list[Edit]) -> str:
    """[ttml] between [from_] and [to], with every edit falling inside applied."""
    output: list[str] = []
    cursor = from_
    for edit in sorted(edits, key=lambda e: e.start):
        if edit.start < cursor or edit.end > to:
            continue
        output.append(ttml[cursor : edit.start])
        output.append(edit.replacement)
        cursor = edit.end
    output.append(ttml[cursor:to])
    return "".join(output)


# --- Input policy (mirrors TtmlInputPolicy in the module) -------------


def is_acceptable(ttml: str) -> bool:
    """Structural acceptance check matching the module's TtmlInputPolicy."""
    if not ttml:
        return False
    if len(ttml.encode("utf-8")) > 512 * 1024:
        return False
    lower = ttml.lower()
    if "<tt" not in lower or "<body" not in lower:
        return False
    if _count_tag(lower, "<p") > 4096:
        return False
    if _count_tag(lower, "<span") > 65536:
        return False
    return True


def _count_tag(text: str, tag: str) -> int:
    count = 0
    index = 0
    while True:
        index = text.find(tag, index)
        if index < 0:
            break
        nxt = index + len(tag)
        if nxt >= len(text) or text[nxt] in (" ", ">", "\t", "\n"):
            count += 1
        index = nxt
    return count


# --- CLI ---------------------------------------------------------------


def _fetch_adam(adam_id: str) -> str:
    url = f"{AMLL_TTML_DB_BASE}/am-lyrics/{adam_id}.ttml"
    request = urllib.request.Request(url, headers={"User-Agent": "amll-to-apple-ttml"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def _read_input(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def _write_output(path: str | None, content: str) -> None:
    if path is None or path == "-":
        sys.stdout.write(content)
        return
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(content)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert AMLL TTML into Apple Music TTML "
        "(port of the AM++ module AmllTtmlFormatConverter)."
    )
    parser.add_argument("input", nargs="?", help="input .ttml file, or '-' for stdin")
    parser.add_argument("-o", "--output", help="output file; default stdout")
    parser.add_argument(
        "--adam",
        metavar="ID",
        help="fetch <ID>.ttml from the amll-dev/amll-ttml-db repository instead of a file",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="run the module's structural acceptance policy on the result",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="run the ported unit-test fixtures and exit",
    )
    args = parser.parse_args(argv)

    if args.selftest:
        return _selftest()
    if args.adam and args.input:
        parser.error("--adam and a positional input are mutually exclusive")
    if not args.adam and not args.input:
        parser.error("an input file (or --adam) is required")

    try:
        source = _fetch_adam(args.adam) if args.adam else _read_input(args.input)
    except (OSError, urllib.error.HTTPError, urllib.error.URLError) as error:
        print(f"error: cannot read input: {error}", file=sys.stderr)
        return 2

    conversion = to_apple_format(source)
    print(f"converted: {conversion.converted}", file=sys.stderr)
    if args.validate and not is_acceptable(conversion.ttml):
        print("error: result fails the structural acceptance policy", file=sys.stderr)
        return 3
    _write_output(args.output, conversion.ttml)
    return 0


# --- Self-test (fixtures ported from AmllTtmlFormatConverterTest) ------


def _fail(message: str) -> int:
    print(f"FAIL: {message}", file=sys.stderr)
    return 1


def _selftest() -> int:
    checks: list[tuple[str, Callable[[], bool]]] = []

    amll_format = textwrap.dedent(
        """\
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:amll="http://www.example.com/ns/amll">
        <head><metadata xmlns=""><ttm:agent type="person" xml:id="v1"/></metadata></head>
        <body dur="00:04.000"><div xmlns="" begin="00:01.000" end="00:04.000">
        <p begin="00:01.000" end="00:02.000" ttm:agent="v1" itunes:key="L1">
        <span begin="00:01.000" end="00:01.500">aa</span> <span begin="00:01.500" end="00:02.000">bb</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
        <span ttm:role="x-roman">R1</span>
        </p>
        <p begin="00:03.000" end="00:04.000" ttm:agent="v1" itunes:key="L2">
        <span begin="00:03.000" end="00:04.000">cc</span>
        <span ttm:role="x-translation" xml:lang="zh-CN">T2</span>
        <span ttm:role="x-roman">R2</span>
        </p></div></body></tt>
        """
    )

    def check_inline_migration() -> bool:
        result = to_apple_format(amll_format)
        assert result.converted
        assert (
            '<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">'
            in result.ttml
        )
        assert (
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            "<text for=\"L1\">T1</text><text for=\"L2\">T2</text>"
            "</translation></translations>" in result.ttml
        )
        assert (
            '<transliterations><transliteration xml:lang="ko-Latn">'
            "<text for=\"L1\">R1</text><text for=\"L2\">R2</text>"
            "</transliteration></transliterations>" in result.ttml
        )
        body = result.ttml.split("<body", 1)[1]
        assert "x-translation" not in body and "x-roman" not in body
        assert '<span begin="00:01.000" end="00:01.500">aa</span>' in body
        root = result.ttml.split(">", 1)[0]
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="ko"' in root
        assert 'xmlns=""' not in result.ttml
        assert "</span> <span begin=\"00:01.500\"" in result.ttml
        return True

    checks.append(("inline spans migrate into head tracks", check_inline_migration))

    def check_line_timed() -> bool:
        line_timed = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            a line
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
            """
        )
        root = to_apple_format(line_timed).ttml.split(">", 1)[0]
        assert 'itunes:timing="Line"' in root
        assert 'xml:lang="ko"' in root
        return True

    checks.append(("no timed spans means line timing", check_line_timed))

    def check_declared_replaced_not_duplicated() -> bool:
        declared = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Line" xml:lang="ja">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation">T1</span>
            </p></div></body></tt>
            """
        )
        root = to_apple_format(declared).ttml.split(">", 1)[0]
        assert root.count("itunes:timing") == 1
        assert root.count("xml:lang") == 1
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="ko"' in root
        return True

    checks.append(("pinned root attributes replace inherited ones", check_declared_replaced_not_duplicated))

    def check_gapped_placeholder() -> bool:
        gapped = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p>
            <p begin="2.0" end="3.0" itunes:key="L3">
            <span begin="2.0" end="3.0">cc</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T3</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(gapped).ttml
        assert (
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            '<text for="L1">T1</text>'
            '<text for="L2"> </text>'
            '<text for="L3">T3</text>'
            "</translation></translations>" in result
        )
        return True

    checks.append(("untranslated lines keep a placeholder entry", check_gapped_placeholder))

    def check_roman_only() -> bool:
        roman_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns="">
            <p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            </p>
            <p begin="1.0" end="2.0" itunes:key="L2">
            <span begin="1.0" end="2.0">bb</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(roman_only).ttml
        assert "<translations>" not in result
        assert (
            '<transliterations><transliteration xml:lang="ko-Latn">'
            '<text for="L1">R1</text><text for="L2"> </text>'
            "</transliteration></transliterations>" in result
        )
        return True

    checks.append(("a kind nobody speaks for gets no track", check_roman_only))

    def check_background_only_line() -> bool:
        background_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="2.0" itunes:key="L9">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(background_only).ttml
        assert (
            '<text for="L9"> <span ttm:role="x-bg">(BT)</span></text>' in result
        )
        return True

    checks.append(("background-only translation opens with the placeholder", check_background_only_line))

    def check_leading_background_moves() -> bool:
        leading = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="1.0" end="3.0" itunes:key="L1">
            <span begin="2.0" end="3.0">aa</span>
            <span ttm:role="x-bg" begin="1.0" end="2.0"><span begin="1.0" end="2.0">(bb)</span>
            <span ttm:role="x-translation">BT</span></span>
            </p></div></body></tt>
            """
        )
        body = to_apple_format(leading).ttml.split("<body", 1)[1]
        assert (
            'itunes:key="L1">'
            '<span ttm:role="x-bg"><span begin="1.0" end="2.0">(bb)</span></span>' in body
        )
        assert body.index("x-bg") < body.index(">aa<")
        assert body.count("x-bg") == 1
        return True

    checks.append(("early background vocal moves to the front", check_leading_background_moves))

    def check_declared_and_inline() -> bool:
        declared_and_inline = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1">ro</text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-roman">R1</span>
            <span ttm:role="x-translation">T1</span></p></div></body></tt>
            """
        )
        result = to_apple_format(declared_and_inline).ttml
        assert result.count("<transliterations>") == 1
        assert '<text for="L1">R1</text>' not in result
        assert '<span ttm:role="x-roman">R1</span>' in result.split("<body", 1)[1]
        assert '<text for="L1">T1</text>' in result
        return True

    checks.append(("a declared kind keeps its inline spans", check_declared_and_inline))

    def check_mixed_is_idempotent() -> bool:
        mixed = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <transliterations><transliteration><text for="L1"><span begin="0.0" end="1.0">ro</span></text><text for="L2"><span begin="1.0" end="2.0">ma</span></text></transliteration></transliterations>
            </iTunesMetadata></metadata></head>
            <body><div>
            <p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span></p>
            <p begin="1.0" end="2.0" itunes:key="L2"><span begin="1.0" end="2.0">bb</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T2</span></p>
            </div></body></tt>
            """
        )
        once = to_apple_format(mixed)
        assert once.converted
        assert once.ttml.count("<iTunesMetadata") == 1
        assert once.ttml.count("<transliterations>") == 1
        assert once.ttml.index("<translations>") < once.ttml.index("<transliterations>")
        assert (
            '<transliterations><transliteration><text for="L1">'
            '<span begin="0.0" end="1.0">ro</span></text>' in once.ttml
        )
        assert "x-translation" not in once.ttml.split("<body", 1)[1]
        assert is_acceptable(once.ttml)
        twice = to_apple_format(once.ttml)
        assert not twice.converted and twice.ttml == once.ttml
        return True

    checks.append(("mixed document is migrated once and idempotent", check_mixed_is_idempotent))

    def check_no_key_keeps_spans() -> bool:
        no_key = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(no_key).ttml
        assert "<iTunesMetadata" not in result
        assert '<span ttm:role="x-translation" xml:lang="zh-CN">T1</span>' in result
        return True

    checks.append(("lines without a key keep their auxiliary spans", check_no_key_keeps_spans))

    def check_plain_root() -> bool:
        plain = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml">
            <head><metadata xmlns=""/></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """
        )
        result = to_apple_format(plain)
        assert result.converted
        assert "<iTunesMetadata" not in result.ttml
        assert 'xmlns=""' not in result.ttml
        root = result.ttml.split(">", 1)[0]
        assert 'itunes:timing="Word"' in root
        assert 'xml:lang="ko"' in root
        return True

    checks.append(("plain document still gets the root attributes", check_plain_root))

    def check_verbatim() -> bool:
        tricky = textwrap.dedent(
            """\
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- xmlns="" in a comment -->
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><amll:meta key="musicName" value="a &gt; b xmlns=&quot;&quot;"/></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">2 &lt; 3</span>
            <span ttm:role="x-translation">&lt;kept&gt;</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(tricky).ttml
        assert '<?xml version="1.0" encoding="UTF-8"?>' in result
        assert '<!-- xmlns="" in a comment -->' in result
        assert 'value="a &gt; b xmlns=&quot;&quot;"' in result
        assert ">2 &lt; 3<" in result
        assert "<text for=\"L1\">&lt;kept&gt;</text>" in result
        return True

    checks.append(("comments cdata and entities are copied verbatim", check_verbatim))

    def check_already_apple() -> bool:
        apple_format = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <translations><translation xml:lang="zh-CN"><text for="L1">T1</text></translation></translations>
            </iTunesMetadata></metadata></head>
            <body><div><p begin="0.0" end="1.0" itunes:key="L1"><span begin="0.0" end="1.0">aa</span></p></div></body></tt>
            """
        )
        result = to_apple_format(apple_format)
        assert not result.converted and result.ttml == apple_format
        return True

    checks.append(("an Apple document is left untouched", check_already_apple))

    def check_songwriters_join() -> bool:
        songwriters_only = textwrap.dedent(
            """\
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <head><metadata xmlns=""><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
            <songwriters><songwriter>Someone</songwriter></songwriters>
            </iTunesMetadata></metadata></head>
            <body><div xmlns=""><p begin="0.0" end="1.0" itunes:key="L1">
            <span begin="0.0" end="1.0">aa</span>
            <span ttm:role="x-translation" xml:lang="zh-CN">T1</span>
            </p></div></body></tt>
            """
        )
        result = to_apple_format(songwriters_only)
        assert result.converted
        assert result.ttml.count("<iTunesMetadata") == 1
        assert (
            '<iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">'
            '<translations><translation type="subtitle" xml:lang="zh-Hans">'
            '<text for="L1">T1</text></translation></translations>' in result.ttml
        )
        assert result.ttml.index("<translations>") < result.ttml.index("<songwriters>")
        return True

    checks.append(("tracks join the existing songwriters metadata", check_songwriters_join))

    def check_second_run_idempotent() -> bool:
        once = to_apple_format(amll_format)
        twice = to_apple_format(once.ttml)
        assert not twice.converted and twice.ttml == once.ttml
        return True

    checks.append(("the rewrite is idempotent", check_second_run_idempotent))

    def check_malformed_never_throws() -> bool:
        assert to_apple_format("").ttml == ""
        assert to_apple_format("plain text").ttml == "plain text"
        for malformed in ("<tt><body>", '<tt><head><metadata/></head><body><p itunes:key="L1">'):
            result = to_apple_format(malformed)
            assert "<iTunesMetadata" not in result.ttml
            assert result.ttml.startswith("<tt ")
            assert 'xml:lang="ko"' in result.ttml
        return True

    checks.append(("malformed and empty input never throws", check_malformed_never_throws))

    for name, check in checks:
        try:
            check()
        except AssertionError as error:
            return _fail(f"{name}: {error}")
    print(f"selftest: {len(checks)} checks passed")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", newline="")
    except (AttributeError, ValueError):
        pass
    sys.exit(main())
