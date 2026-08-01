#!/usr/bin/env python3
"""Assert that more than the highlighted lyric row is visibly rendered."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image


BOUNDS = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml")
    parser.add_argument("screenshot")
    args = parser.parse_args()

    image = np.asarray(Image.open(args.screenshot).convert("L"), dtype=np.int16)
    height, width = image.shape
    rows: dict[tuple[int, int], list[tuple[int, int, int, int]]] = {}
    for node in ET.parse(args.xml).iter("node"):
        match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
        if not match or node.attrib.get("class") != "android.widget.TextView":
            continue
        left, top, right, bottom = map(int, match.groups())
        if left < width // 2 + 50 or right > width - 50 or bottom - top < 50:
            continue
        key = next((key for key in rows if abs(key[0] - top) <= 4), (top, bottom))
        rows.setdefault(key, []).append((left, top, right, bottom))

    visible_rows = 0
    details = []
    for (top, bottom), boxes in sorted(rows.items()):
        left = max(0, min(box[0] for box in boxes) - 4)
        right = min(width, max(box[2] for box in boxes) + 4)
        top = max(0, top)
        bottom = min(height, bottom)
        crop = image[top:bottom, left:right]
        if crop.size == 0:
            continue
        horizontal = np.abs(np.diff(crop, axis=1))
        vertical = np.abs(np.diff(crop, axis=0))
        edge_pixels = int((horizontal > 3).sum() + (vertical > 3).sum())
        visible = edge_pixels >= 100
        visible_rows += int(visible)
        details.append(f"[{top},{bottom}] edges={edge_pixels} visible={str(visible).lower()}")

    print(f"CAR39_LYRICS rows={len(details)} visibleRows={visible_rows}")
    for detail in details:
        print(f"  {detail}")
    if visible_rows < 2:
        print("CAR39_RED only the highlighted lyric row is visibly rendered", file=sys.stderr)
        return 1
    print("CAR39_GREEN multiple lyric rows are visibly rendered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
