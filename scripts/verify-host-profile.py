"""Read-only verification of the exact AM++ host profiles against a user-supplied package.

Every check below is an evidence claim taken from the adaptation record: the DEX class name, the
complete method signature (owner, static/instance descriptor and return type) and, where the
adaptation pinned one, the field name. No APK code is executed and no file is written.

Usage:
    python scripts/verify-host-profile.py apple-music-6-5-3.xapk \
        --version-name 6.5.3 --version-code 1599 --glass
"""
import argparse
import io
import json
import struct
import sys
import zipfile
from pathlib import Path


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return {class descriptor: {"super": ..., "methods": set, "fields": dict}}."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        methods = set()
        fields = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    _, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    _, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "methods": methods,
            "fields": fields,
        }
    return classes


LAYOUTS = [
    "res/layout/bottom_navigation.xml",
    "res/layout/mini_player.xml",
    "res/layout/activity_main_content_layout.xml",
]

# Apple Music ships androidx.lifecycle.LiveData obfuscated. The module accepts the host alias only
# because it still declares the LiveData public surface, so verify that surface here.
ALIASED_TYPES = {
    "Landroidx/lifecycle/G;": [
        "getValue()Ljava/lang/Object;",
        "observe(Landroidx/lifecycle/B;Landroidx/lifecycle/K;)V",
        "observeForever(Landroidx/lifecycle/K;)V",
    ],
}

# The direct catalog query, as resolved by AppleCatalogQueryMethod: the module pins one preferred
# name per hook table and only falls back to a verified rename per owner class.
CATALOG_QUERY_SHAPE = (
    "(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
)

# The content HTTP localization interceptor is where the module rewrites the storefront path
# segment and the l parameter and strips its own request token before the request leaves the app.
# Without it every storefront/language-scoped catalog lookup comes back empty, which silently
# degrades original-title resolution to the disk cache only. 6.5.3 moved the whole content-API
# family u8/a..u8/n to w8/a..w8/n, and 1599's own u8.a is an unrelated MediaApi model class.
CONTENT_HTTP_INTERCEPTORS = {
    "6.5.2": {
        "owner": "Lu8/a;",
        "signature": "a(LHi/f;)LCi/F;",
    },
    "6.5.3": {
        "owner": "Lw8/a;",
        "signature": "a(LLi/f;)LGi/D;",
        "vacated": ("Lu8/a;", "a(LLi/f;)LGi/D;"),
    },
}

# Glass seams: the phone navigation strip, the stacked holder translate entry point and the
# material BottomNavigationView menu contract shared by every supported build.
GLASS_METHODS = {
    "Lcom/apple/android/music/common/activity/PlayerActivity$StackedBottomNavigationHolder;": [
        "c(F)V",
    ],
    "Lcom/apple/android/music/player/PlayerBottomSheetBehavior;": ["F(IZ)V"],
    "Lcom/google/android/material/bottomnavigation/BottomNavigationView;": [
        "getMenu()Landroid/view/Menu;",
        "getSelectedItemId()I",
        "setSelectedItemId(I)V",
    ],
}

PROFILES = {
    "6.5.2": {
        "version_code": "1586",
        "methods": {
            "Lcom/apple/android/music/common/activity/PlayerActivity;": [
                "k1()Lcom/apple/android/music/common/activity/PlayerActivity$m;",
            ],
            "Lcom/apple/android/music/player/fragment/t0;": [],
            "Lcom/apple/android/music/player/O;": [],
            "Lcom/apple/android/music/player/e1;": [],
            "Lcom/apple/android/music/player/f1;": [],
            "Lcom/apple/android/music/player/fragment/e;": [],
            "Lcom/apple/android/music/player/fragment/m;": [],
            "Lcom/apple/android/music/player/fragment/d0;": ["onClick(Landroid/view/View;)V"],
            "Lcom/apple/android/music/player/e;": ["onMediaMetadataChanged(Lv3/v;)V"],
            "Lcom/apple/android/music/player/R0;": [],
            "Lcom/apple/android/music/playback/player/ExoMediaPlayer;": ["onAudioSessionId(I)V"],
            "Lcom/apple/android/music/playback/controller/LocalMediaPlayerController;": [
                "onPlaybackAudioVariantChanged(Lcom/apple/android/music/playback/player/MediaPlayer;IJLcom/google/android/exoplayer2/Format;Lcom/google/android/exoplayer2/Format;)V",
            ],
            "Lcom/apple/android/music/library2/LibraryMainContentEpoxyController;": [
                "buildModels(Lcom/apple/android/music/library2/M;Ljava/util/List;Ljava/util/List;Lcom/apple/android/music/library2/a;Lx6/c;)V",
            ],
            "Lcom/apple/android/music/common/L;": ["t(Lcom/apple/android/music/model/CollectionItemView;)V"],
            "Lcom/apple/android/music/common/behavior/StaticCollapsedBottomSheetBehavior;": [
                "h(Landroidx/coordinatorlayout/widget/CoordinatorLayout;Landroid/view/View;Landroid/view/MotionEvent;)Z",
            ],
            "LC1/w;": ["e(Landroidx/lifecycle/G;Lz0/n;)Lz0/p0;"],
            "Lz0/s0;": ["a(Ljava/lang/Object;Ljava/lang/Object;)Z"],
            # Content HTTP localization family: interceptor, chain, request/builder, headers,
            # response. The module reads these exact member names at runtime.
            "Lu8/a;": ["a(LHi/f;)LCi/F;"],
            "Lcom/apple/android/music/library3/LibraryComposeContentFragment;": [
                "B0()Lcom/apple/android/music/library2/LibraryViewModel;",
            ],
            "LCi/C;": ["b()LCi/C$a;"],
            "LCi/C$a;": [
                "b()LCi/C;",
                "d(Ljava/lang/String;Ljava/lang/String;)V",
                "h(Ljava/lang/String;)V",
            ],
            "LCi/v;": ["e(Ljava/lang/String;)Ljava/lang/String;"],
            "LHd/b;": ["onMeasure(II)V", "e(Landroid/content/Context;)LHd/a;"],
            "LJ5/a;": ["b(Landroid/content/Context;)[Ljava/lang/String;"],
            "Ly8/B;": [
                "b(Lcom/apple/android/music/mediaapi/models/Song;Landroid/os/Bundle;)Lcom/apple/android/music/model/Song;",
            ],
            "Lcom/apple/android/music/utils/I0$a;": ["a(Ljava/lang/CharSequence;Ljava/util/Set;)Z"],
            "Lcom/apple/android/music/player/z;": ["a0(Lcom/apple/android/music/player/z$a;IIIZ)V"],
        },
        "fields": {
            "Lcom/apple/android/music/common/activity/PlayerActivity;": ["c1"],
            "Lz0/s0;": ["a"],
            "LHi/f;": ["e"],
            "LCi/C;": ["a", "c"],
            "LCi/F;": ["a", "d", "f"],
            "LCi/v;": ["a"],
        },
        # 6.5.2 is the build where the shared preferred name B still carries the query body.
        "catalog_query": {"owner": "Ls8/F;", "verified": "B", "preferred": "B"},
    },
    "6.5.3": {
        "version_code": "1599",
        "methods": {
            "Lcom/apple/android/music/common/activity/PlayerActivity;": [
                "k1()Lcom/apple/android/music/common/activity/PlayerActivity$m;",
            ],
            "Lcom/apple/android/music/player/fragment/v0;": [],
            "Lcom/apple/android/music/player/P;": [],
            "Lcom/apple/android/music/player/e1;": [
                "d(Lv3/v;Lcom/apple/android/music/model/PlaybackItem;Ldg/e;)Lcom/apple/android/music/model/PlaybackItem;",
            ],
            "Lcom/apple/android/music/player/f1;": [
                "e(Lcom/apple/android/music/model/Song;F[Lcom/apple/android/music/mediaapi/models/internals/EditorialVideo$Flavor;)Ljava/lang/String;",
            ],
            "Lcom/apple/android/music/player/fragment/e;": [],
            "Lcom/apple/android/music/player/fragment/m;": [],
            "Lcom/apple/android/music/player/fragment/d0;": ["onClick(Landroid/view/View;)V"],
            "Lcom/apple/android/music/player/e;": ["onMediaMetadataChanged(Lv3/v;)V"],
            "Lcom/apple/android/music/player/R0;": [],
            "Lcom/apple/android/music/playback/player/ExoMediaPlayer;": ["onAudioSessionId(I)V"],
            "Lcom/apple/android/music/playback/controller/LocalMediaPlayerController;": [
                "onPlaybackAudioVariantChanged(Lcom/apple/android/music/playback/player/MediaPlayer;IJLcom/google/android/exoplayer2/Format;Lcom/google/android/exoplayer2/Format;)V",
            ],
            "Lcom/apple/android/music/library2/LibraryMainContentEpoxyController;": [
                "buildModels(Lcom/apple/android/music/library2/H;Ljava/util/List;Ljava/util/List;Lcom/apple/android/music/library2/a;Lz6/b;)V",
            ],
            "Lcom/apple/android/music/common/I;": ["t(Lcom/apple/android/music/model/CollectionItemView;)V"],
            "Lcom/apple/android/music/common/behavior/StaticCollapsedBottomSheetBehavior;": [
                "h(Landroidx/coordinatorlayout/widget/CoordinatorLayout;Landroid/view/View;Landroid/view/MotionEvent;)Z",
            ],
            "LDg/c;": ["l(Landroidx/lifecycle/G;Lz0/m;)Lz0/n0;"],
            "Lz0/p0;": ["a(Ljava/lang/Object;Ljava/lang/Object;)Z"],
            # The library Compose view-model getter was renamed again (6.5.0 B0, 6.5.1 A0 ->
            # 6.5.3 F0); the return type is unchanged, so both halves are asserted below.
            "Lcom/apple/android/music/library3/LibraryComposeContentFragment;": [
                "F0()Lcom/apple/android/music/library2/LibraryViewModel;",
            ],
            # Content HTTP localization family after the u8 -> w8 move, plus the MediaApi
            # parameter seam that owns the storefront field and the direct query method.
            "Lw8/a;": ["a(LLi/f;)LGi/D;"],
            "Lu8/E;": [
                "c0(Ljava/util/Map;)Ljava/util/LinkedHashMap;",
                "v(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            ],
            "LGi/A;": ["b()LGi/A$a;"],
            "LGi/A$a;": [
                "b()LGi/A;",
                "d(Ljava/lang/String;Ljava/lang/String;)V",
                "h(Ljava/lang/String;)V",
            ],
            "LGi/t;": ["e(Ljava/lang/String;)Ljava/lang/String;"],
            "LKd/b;": ["onMeasure(II)V", "e(Landroid/content/Context;)LKd/a;"],
            "LK5/a;": ["b(Landroid/content/Context;)[Ljava/lang/String;"],
            "LA8/D;": [
                "b(Lcom/apple/android/music/mediaapi/models/Song;Landroid/os/Bundle;)Lcom/apple/android/music/model/Song;",
            ],
            "Lcom/apple/android/music/utils/E0$a;": ["a(Ljava/lang/CharSequence;Ljava/util/Set;)Z"],
            "Lcom/apple/android/music/player/A;": ["a0(Lcom/apple/android/music/player/A$a;IIIZ)V"],
        },
        "fields": {
            "Lcom/apple/android/music/common/activity/PlayerActivity;": ["c1"],
            "Lz0/p0;": ["a"],
            "LLi/f;": ["e"],
            "LGi/A;": ["a", "c"],
            "LGi/D;": ["a", "d", "f"],
            "LGi/t;": ["a"],
        },
        # The preferred name survives on 1599 but describes another method, so the module has to
        # fall back to the verified rename v. Assert both halves of that claim.
        "catalog_query": {"owner": "Lu8/E;", "verified": "v", "preferred": "B"},
    },
}


def find_method(classes, owner, signature):
    current = owner
    while current in classes:
        if signature in classes[current]["methods"]:
            return current
        current = classes[current]["super"]
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path, help="XAPK or base APK")
    parser.add_argument("--version-name", default=None)
    parser.add_argument("--version-code", default=None)
    parser.add_argument("--glass", action="store_true", help="also verify the phone glass seams")
    args = parser.parse_args()

    with zipfile.ZipFile(args.package) as package:
        names = package.namelist()
        manifest = None
        if "manifest.json" in names:
            manifest = json.loads(package.read("manifest.json"))
        base_name = next(
            (name for name in names if name.endswith(".apk") and "config" not in name),
            None,
        )
        if base_name is None:
            base_name = args.package.name
            apk = zipfile.ZipFile(args.package)
        else:
            apk = zipfile.ZipFile(io.BytesIO(package.read(base_name)))
        version_name = args.version_name or (manifest or {}).get("version_name")
        version_code = args.version_code or str((manifest or {}).get("version_code", ""))
        profile = PROFILES.get(version_name or "")
        if profile is None:
            raise SystemExit(
                "Unsupported or undocumented version tuple: %s (%s). Known profiles: %s"
                % (version_name, version_code, ", ".join(sorted(PROFILES))),
            )
        if version_code != profile["version_code"]:
            raise SystemExit(
                "Version tuple mismatch: %s (%s) does not match the recorded %s profile"
                % (version_name, version_code, profile["version_code"]),
            )

        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(dex_classes(apk.read(name)))

        failures = []
        checks = 0

        for owner, signatures in profile["methods"].items():
            if owner not in classes:
                failures.append("missing class %s" % owner)
                continue
            for signature in signatures:
                checks += 1
                where = find_method(classes, owner, signature)
                if where is None:
                    failures.append("missing method %s %s" % (owner, signature))
        for owner, field_names in profile["fields"].items():
            for field_name in field_names:
                checks += 1
                if owner not in classes:
                    failures.append("missing class %s" % owner)
                elif field_name not in classes[owner]["fields"]:
                    failures.append("missing field %s %s" % (owner, field_name))

        for owner, signatures in ALIASED_TYPES.items():
            for signature in signatures:
                checks += 1
                if owner not in classes:
                    failures.append("missing obfuscated type %s" % owner)
                elif signature not in classes[owner]["methods"]:
                    failures.append("missing alias member %s %s" % (owner, signature))

        query = profile.get("catalog_query")
        if query:
            owner = query["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing catalog query class %s" % owner)
            elif query["verified"] + CATALOG_QUERY_SHAPE not in classes[owner]["methods"]:
                failures.append(
                    "missing catalog query %s %s"
                    % (owner, query["verified"] + CATALOG_QUERY_SHAPE)
                )
            if query["preferred"] != query["verified"]:
                checks += 1
                if owner in classes and (
                    query["preferred"] + CATALOG_QUERY_SHAPE in classes[owner]["methods"]
                ):
                    failures.append(
                        "%s %s satisfies the catalog query shape on %s, so the verified rename %s "
                        "is unreachable" % (
                            owner,
                            query["preferred"],
                            version_name,
                            query["verified"],
                        )
                    )

        interceptor = CONTENT_HTTP_INTERCEPTORS.get(version_name)
        if interceptor:
            owner = interceptor["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing content HTTP interceptor %s" % owner)
            elif interceptor["signature"] not in classes[owner]["methods"]:
                failures.append(
                    "missing content HTTP interceptor method %s %s"
                    % (owner, interceptor["signature"])
                )
            vacated = interceptor.get("vacated")
            if vacated:
                checks += 1
                vacated_owner, vacated_signature = vacated
                if vacated_owner in classes and (
                    vacated_signature in classes[vacated_owner]["methods"]
                ):
                    failures.append(
                        "%s still declares %s on %s, so the pinned interceptor %s is shadowed"
                        % (vacated_owner, vacated_signature, version_name, owner)
                    )

        if args.glass:
            for owner, signatures in GLASS_METHODS.items():
                for signature in signatures:
                    checks += 1
                    if find_method(classes, owner, signature) is None:
                        failures.append("missing glass hook %s %s" % (owner, signature))
            for layout in LAYOUTS:
                checks += 1
                if layout not in apk.namelist():
                    failures.append("missing layout %s" % layout)

        print("package: %s" % args.package)
        print("base apk: %s" % base_name)
        print("version tuple: %s (%s)" % (version_name, version_code))
        print("checks: %d, failures: %d" % (checks, len(failures)))
        if failures:
            for failure in failures:
                print("FAIL %s" % failure)
            sys.exit(1)
        print("PASS: Apple Music %s (%s) profile symbols verified" % (version_name, version_code))


if __name__ == "__main__":
    main()
