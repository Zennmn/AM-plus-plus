"""Read-only validation of the exact 1586 DEX hook profile against a user-supplied XAPK.

Checks actual DEX definitions rather than JADX-generated Java names. No APK code is executed.
"""
import argparse
import io
import json
import struct
import zipfile
from pathlib import Path


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
        raise ValueError("Invalid ULEB128")

    strings = []
    for i in range(u32(56)):
        _, offset = uleb(u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        arguments = u32(base + 8)
        params = "" if not arguments else "".join(types[struct.unpack_from("<H", data, arguments + 4 + j * 2)[0]] for j in range(u32(arguments)))
        protos.append("(" + params + ")" + types[u32(base + 4)])
    methods = []
    for i in range(u32(88)):
        owner, proto, name = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        methods.append((types[owner], strings[name] + protos[proto]))
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        definitions = set()
        if offset:
            counts = []
            for _ in range(4):
                count, offset = uleb(offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, offset = uleb(offset)
                _, offset = uleb(offset)
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, offset = uleb(offset)
                    index += delta
                    _, offset = uleb(offset)
                    _, offset = uleb(offset)
                    definitions.add(methods[index][1])
        classes[class_name] = (types[superclass] if superclass != 0xFFFFFFFF else None, definitions)
    return classes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("xapk", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.xapk) as package:
        manifest = json.loads(package.read("manifest.json"))
        assert manifest["package_name"] == "com.apple.android.music"
        assert manifest["version_name"] == "6.5.2" and str(manifest["version_code"]) == "1586"
        apk = zipfile.ZipFile(io.BytesIO(package.read("com.apple.android.music.apk")))
    classes = {}
    for name in apk.namelist():
        if name.endswith(".dex"):
            classes.update(dex_classes(apk.read(name)))
    checks = {
        "Lcom/apple/android/music/common/activity/PlayerActivity$StackedBottomNavigationHolder;": ["c(F)V"],
        "Lcom/apple/android/music/player/PlayerBottomSheetBehavior;": ["F(IZ)V"],
        "Lcom/google/android/material/bottomnavigation/BottomNavigationView;": ["getMenu()Landroid/view/Menu;", "getSelectedItemId()I", "setSelectedItemId(I)V"],
    }
    for owner, signatures in checks.items():
        for signature in signatures:
            current = owner
            while current in classes:
                parent, definitions = classes[current]
                if signature in definitions:
                    print("PASS", owner, signature, "defined in", current)
                    break
                current = parent
            else:
                raise SystemExit(f"Missing required hook: {owner} {signature}")
    for name in ["res/layout/bottom_navigation.xml", "res/layout/mini_player.xml", "res/layout/activity_main_content_layout.xml"]:
        assert name in apk.namelist(), name
    print("PASS: Apple Music 6.5.2 (1586) hook signatures and layouts")


if __name__ == "__main__":
    main()
