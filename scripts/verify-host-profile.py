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
import importlib.util
from pathlib import Path

_manifest_spec = importlib.util.spec_from_file_location('android_manifest',Path(__file__).with_name('android-package-manifest.py'))
_manifest_module = importlib.util.module_from_spec(_manifest_spec)
_manifest_spec.loader.exec_module(_manifest_module)


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
    """Return class descriptors, inherited interfaces, methods and fields."""
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
        interfaces_offset = u32(base + 12)
        interfaces = [] if not interfaces_offset else [
            types[u16(interfaces_offset + 4 + 2 * j)] for j in range(u32(interfaces_offset))
        ]
        offset = u32(base + 24)
        methods = set()
        fields = {}
        method_access = {}
        field_access = {}
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
                    flags, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
                    field_access[field_defs[index][0]] = flags
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
                    method_access[method_defs[index]] = flags
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "interfaces": interfaces,
            "methods": methods,
            "fields": fields,
            "method_access": method_access,
            "field_access": field_access,
        }
    return classes


PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "host-applemusic/src/main/resources/host-profiles"


def load_profiles():
    index = json.loads((PROFILE_DIRECTORY / "index.json").read_text(encoding="utf-8"))
    profiles = {}
    for filename in index["profiles"]:
        data = json.loads((PROFILE_DIRECTORY / filename).read_text(encoding="utf-8"))
        if data["productionEnabled"]:
            profiles[(data["versionName"], str(data["versionCode"]))] = data["verification"]
    return profiles


PROFILES = load_profiles()
CATALOG_QUERY_SHAPE = "(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"


def find_method(classes, owner, signature):
    current = owner
    while current in classes:
        if signature in classes[current]["methods"]:
            return current
        current = classes[current]["super"]
    return None


def verify_fragment_contracts(document, classes, failures):
    """Cover declared helper/binding/constructor dependencies as well as indexed hooks."""
    if document.get('family') != 'fragment-content':
        return 0
    primitive = {'void': 'V', 'int': 'I', 'long': 'J', 'float': 'F', 'boolean': 'Z',
                 'double': 'D', 'byte': 'B', 'short': 'S', 'char': 'C'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                             else 'L' + name.replace('.', '/') + ';')
    sections = {name: document.get(name) for name in (
        'fragmentDualPane', 'fragmentLyricsRuntime', 'fragmentKaraokeWidth', 'fragmentTabletVideoHandoff',
        'lyricPronunciation')}
    sections['fragmentChrome.members'] = (document.get('fragmentChrome') or {}).get('members')
    settings = document.get('settings') or {}
    presentation = document.get('metadataPresentation')
    if presentation is not None:
        required = {'methods': {'createState', 'readState', 'writeState', 'entityType', 'lockup', 'roomPrepare', 'pagedRow', 'pagedRebuild'},
                    'fields': {'searchSongRow', 'searchGridRow'}}
        if not isinstance(presentation, dict) or presentation.get('contractVersion') != 1:
            failures.append('metadataPresentation: missing versioned contract')
        else:
            for group, roles in required.items():
                entries = presentation.get(group, {})
                if not isinstance(entries, dict):
                    failures.append(f'metadataPresentation.{group}: invalid inventory')
                    continue
                for role in sorted(roles - entries.keys()):
                    failures.append(f'metadataPresentation.{group}.{role}: missing required role')
            sections['metadataPresentation'] = presentation
    settings_members = settings.get('members')
    if settings_members is not None:
        if not isinstance(settings_members, dict) or settings_members.get('contractVersion') != 1:
            failures.append('settings.members: missing versioned contract')
        else:
            methods = settings_members.get('methods', {})
            for role, owner_key, name_key, returns in (
                    ('preferenceItems', 'viewModelClass', 'preferenceItemsMethod', 'java.util.List'),
                    ('compose', 'fragmentClass', 'composeMethod', 'void')):
                contract = methods.get(role, {})
                if (contract.get('owner') != settings.get(owner_key) or
                        contract.get('name') != settings.get(name_key) or
                        contract.get('returns') != returns or contract.get('static') is not False):
                    failures.append(f'settings.members.methods.{role}: invalid required role')
            sections['settings.members'] = settings_members
    # Optional independent renderer correction. Missing roles cannot silently pass as an empty inventory.
    wrap = document.get('lyricWrapSweep')
    if wrap is not None:
        if not isinstance(wrap, dict) or wrap.get('contractVersion') != 1:
            failures.append('lyricWrapSweep: missing versioned contract')
        else:
            required = {
                'methods': {'build', 'update', 'rushUpdate', 'fadeUpdate', 'staticMask', 'rebind', 'bind', 'recycle', 'document',
                            'clearMasks', 'textView', 'maskUpdate', 'animationTag', 'mediaBrowser',
                            'mediaPosition', 'seek', 'pause', 'pauseRequested', 'resume'},
                'fields': {'topMasks', 'bottomMasks', 'maskFirst', 'maskLast', 'wordBegin', 'wordDuration',
                           'bindingRoot', 'holderBinding', 'adapterCallback', 'callbackFragment', 'featherPixels',
                           'listenerMask', 'listenerColors', 'listenerFeather', 'listenerRtl', 'wordText', 'wordAnimator',
                           'words', 'backgroundWords', 'pronunciationWords', 'pronunciationBackgroundWords',
                           'foregroundInactiveColors', 'backgroundInactiveColors', 'fadeMask'},
            }
            for group, roles in required.items():
                for role in sorted(roles - wrap.get(group, {}).keys()):
                    failures.append(f'lyricWrapSweep.{group}.{role}: missing required role')
            sections['lyricWrapSweep'] = wrap
    # Independent native active-row retention; an absent section leaves the capability unsupported.
    background = document.get('lyricBackgroundHighlight')
    if background is not None:
        if not isinstance(background, dict) or background.get('contractVersion') != 4:
            failures.append('lyricBackgroundHighlight: missing versioned contract')
        else:
            required = {
                'methods': {'commit', 'lineSubset', 'notifyRow', 'mapPosition', 'focus', 'payloadKind', 'document', 'detach',
                            'lineCount', 'lineAt', 'lineGet', 'lineId', 'backgroundWords',
                            'pronunciationBackgroundWords', 'vectorSize', 'vectorGet', 'wordGet',
                            'timingBegin', 'timingEnd', 'mediaBrowser', 'mediaPosition', 'seek',
                            'pause', 'pauseRequested', 'resume', 'destroyView'},
                'fields': {'reader', 'currentLines', 'previousLines', 'wordHighlightIds', 'backgroundHighlightIds',
                           'adapterCallback', 'callbackFragment', 'focusStart', 'focusEnd'},
            }
            for group, roles in required.items():
                entries = background.get(group, {})
                if not isinstance(entries, dict):
                    failures.append(f'lyricBackgroundHighlight.{group}: invalid inventory')
                    continue
                for role in sorted(roles - entries.keys()):
                    failures.append(f'lyricBackgroundHighlight.{group}.{role}: missing required role')
            sections['lyricBackgroundHighlight'] = background
    # Optional navigation startup prewarm. A declared capability needs its exact contract.
    prewarm = document.get('startupPagePrewarm')
    capability = (document.get('capabilities') or {}).get('startupPagePrewarm') is True
    if prewarm is None and capability:
        failures.append('startupPagePrewarm: capability declared without a contract section')
    if prewarm is not None:
        if not isinstance(prewarm, dict) or prewarm.get('schemaVersion') != 1:
            failures.append('startupPagePrewarm: missing versioned contract')
        elif not isinstance(prewarm.get('members'), dict):
            failures.append('startupPagePrewarm.members: missing complete member inventory')
        else:
            members = prewarm['members']
            required = {
                'methods': {'viewCreated', 'destroyView', 'activity', 'view', 'childManager', 'resumed',
                            'viewModel', 'tabs', 'value', 'selectedTab', 'kindLabel', 'kindId', 'menu', 'selectedId',
                            'find', 'stateSaved', 'arguments', 'add', 'hide', 'onCommit', 'commit', 'tag', 'hidden',
                            'observePlayContext', 'containerView', 'parent', 'libraryViewCreated', 'notifyEvent'},
                'fields': {'modelKind', 'primary', 'reordering'},
                'constructors': {'container', 'transaction'},
            }
            if prewarm.get('tabletNavigationResource'):
                required['methods'].add('tabletMenu')
            valid_groups = True
            for group, roles in required.items():
                entries = members.get(group, {})
                if not isinstance(entries, dict):
                    failures.append(f'startupPagePrewarm.members.{group}: invalid inventory')
                    valid_groups = False
                    continue
                for role in sorted(roles - entries.keys()):
                    failures.append(f'startupPagePrewarm.members.{group}.{role}: missing required role')
            if valid_groups:
                sections['startupPagePrewarm.members'] = members
    main = sections.get('fragmentDualPane') or {}
    policy = main.get('songLayoutPolicy')
    if policy == '1606-column':
        sections['fragmentDualPane.songLayout'] = main.get('songLayout')
    elif policy != 'native' or 'songLayout' in main:
        failures.append('fragmentDualPane.songLayoutPolicy: select an explicit, consistent layout policy')
    for role in ('artwork', 'metadata', 'controls', 'progress', 'binding', 'restoration'):
        if not str(main.get('layoutReview', {}).get(role, '')).strip():
            failures.append(f'fragmentDualPane.layoutReview.{role}: missing comparison evidence')
    checks = 0
    for section, data in sections.items():
        if (not isinstance(data, dict) or not data.get('methods') or
                (section != 'settings.members' and not data.get('fields'))):
            failures.append(f'{section}: missing complete member inventory')
            continue
        for group in ('methods', 'fields', 'constructors'):
            for role, contract in data.get(group, {}).items():
                checks += 1
                try:
                    if section == 'fragmentChrome.members' and group == 'methods' and role == 'activityTouch':
                        # This public framework method is absent from the application's DEX.
                        if contract != dict(owner='android.app.Activity', name='dispatchTouchEvent',
                                            parameters=['android.view.MotionEvent'], returns='boolean', static=False):
                            failures.append(f'{section}.{group}.{role}: invalid framework descriptor')
                        continue
                    native = classes.get(descriptor(contract['owner']), {})
                    if group == 'fields':
                        valid = (native.get('fields', {}).get(contract['name']) == descriptor(contract['type'])
                                 and not native.get('field_access', {}).get(contract['name'], 8) & 8)
                    else:
                        name = '<init>' if group == 'constructors' else contract['name']
                        returns = 'V' if group == 'constructors' else descriptor(contract['returns'])
                        signature = name + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + returns
                        flags = native.get('method_access', {}).get(signature)
                        forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly') else 0x400)
                        valid = (flags is not None and not flags & forbidden
                                 and bool(flags & 8) == contract.get('static', False))
                    if not valid:
                        failures.append(f'{section}.{group}.{role}: invalid exact descriptor {contract}')
                except (KeyError, TypeError, AttributeError) as error:
                    failures.append(f'{section}.{group}.{role}: incomplete descriptor ({error})')
    return checks


def verify_artist_compose_contract(document, classes, failures):
    """Optional artist surface; a partial declaration cannot suppress required invocation checks."""
    surface = document.get('hookTargets', {}).get('ARTIST_SURFACE_CLASSES', [])
    declared = [target.get('runtimeMemberNames', {}).get('ARTIST_RUNTIME_ROLE') for target in surface]
    roles = {'compose_profile_view_model', 'compose_profile_success_state'}
    if 'artistComposeProfile' not in document and not roles.intersection(declared):
        return 0
    for role in sorted(roles):
        if declared.count(role) != 1:
            failures.append(f'artistComposeProfile: expected one surface role {role}')
    data = document.get('artistComposeProfile')
    if not isinstance(data, dict) or data.get('contractVersion') != 1:
        failures.append('artistComposeProfile: missing versioned contract')
        return 0
    for key in ('artistType', 'topSongsView'):
        if not isinstance(data.get(key), str) or not data[key].strip():
            failures.append(f'artistComposeProfile.{key}: missing semantic key')
    required = {
        'methods': {'setUiState', 'headerData', 'shelves', 'entityType', 'entityTitle', 'entityViews', 'viewEntities'},
        'fields': {'id', 'title', 'header', 'shelves', 'entity', 'revision'},
        'constructors': {'success'},
    }
    primitive = {'void': 'V', 'long': 'J', 'int': 'I', 'boolean': 'Z'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[') else 'L' + name.replace('.', '/') + ';')
    checks = 0
    for group, expected in required.items():
        entries = data.get(group, {})
        if not isinstance(entries, dict):
            failures.append(f'artistComposeProfile.{group}: invalid inventory')
            continue
        for role in sorted(expected - entries.keys()):
            failures.append(f'artistComposeProfile.{group}.{role}: missing required role')
        for role, contract in entries.items():
            checks += 1
            try:
                native = classes.get(descriptor(contract['owner']), {})
                if group == 'fields':
                    valid = (native.get('fields', {}).get(contract['name']) == descriptor(contract['type'])
                             and not native.get('field_access', {}).get(contract['name'], 8) & 8)
                else:
                    name = '<init>' if group == 'constructors' else contract['name']
                    returns = 'V' if group == 'constructors' else descriptor(contract['returns'])
                    signature = name + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + returns
                    flags = native.get('method_access', {}).get(signature)
                    valid = (flags is not None and not flags & (8 | 0x40 | 0x400 | 0x1000)
                             and (group == 'constructors' or contract['static'] is False))
                if not valid:
                    failures.append(f'artistComposeProfile.{group}.{role}: invalid exact instance descriptor')
            except (KeyError, TypeError, AttributeError) as error:
                failures.append(f'artistComposeProfile.{group}.{role}: incomplete descriptor ({error})')
    try:
        m, f, c = data['methods'], data['fields'], data['constructors']['success']
        surface_classes = {target['runtimeMemberNames']['ARTIST_RUNTIME_ROLE']: target['className'] for target in surface}
        vm, state, entity = (surface_classes[role] for role in
                             ('compose_profile_view_model', 'compose_profile_success_state', 'media_entity'))
        assert all(m[role]['owner'] == vm for role in ('setUiState', 'headerData', 'shelves'))
        assert all(f[role]['owner'] == state for role in required['fields'])
        assert len({f[role]['name'] for role in required['fields']}) == 6
        assert [f[role]['type'] for role in ('id', 'title', 'shelves', 'entity', 'revision')] == [
            'java.lang.String', 'java.lang.String', 'java.util.List', entity, 'long']
        assert c['owner'] == state and c['parameters'] == [f[role]['type'] for role in
                                                        ('id', 'title', 'header', 'shelves', 'entity', 'revision')]
        assert m['setUiState']['returns'] == 'void' and len(m['setUiState']['parameters']) == 1
        ancestor = descriptor(state)
        publisher_type = descriptor(m['setUiState']['parameters'][0])
        while ancestor in classes and ancestor != publisher_type:
            ancestor = classes[ancestor]['super']
        assert ancestor == publisher_type
        for role in ('headerData', 'shelves'):
            assert m[role]['parameters'] == [entity]
            assert m[role]['returns'] == f['header' if role == 'headerData' else 'shelves']['type']
        for role, returns in (('entityType', 'java.lang.String'), ('entityTitle', 'java.lang.String'),
                              ('entityViews', 'java.util.Map')):
            assert m[role]['owner'] == entity and m[role]['parameters'] == [] and m[role]['returns'] == returns
        assert m['viewEntities']['parameters'] == [] and m['viewEntities']['returns'] == '[L' + entity + ';'
    except (KeyError, TypeError, AttributeError, AssertionError) as error:
        failures.append(f'artistComposeProfile: state/entity/builder relationship mismatch ({error})')
    return checks


def verify_lyric_blur_contract(document, classes, failures):
    """Independent blur inventory for both host families; run only when the verifier is requested."""
    data = document.get('lyricBlur')
    if data is None and not document.get('productionEnabled'):
        return 0
    if not isinstance(data, dict) or data.get('contractVersion') != 1:
        failures.append('lyricBlur: missing versioned production contract')
        return 0
    adapter_roles = ('Commit', 'Snapshot', 'Map', 'Type', 'Id', 'Reader', 'Token', 'Document', 'Bind', 'Payload', 'Recycle')
    native_roles = ('nativeLine', 'nativeWord', 'nativeBackground', 'nativePronunciation', 'nativePronunciationBackground')
    word_roles = ('wordProgress',)
    required_methods = {
        'createView', 'destroyView', 'resume', 'installDocument', 'readerCount', 'readerLine', 'lineGet', 'nativeLineId',
        'lineVectorSize', 'lineVectorGet', 'wordVectorSize', 'wordVectorGet', 'wordGet', 'wordLine', 'nativeWordId',
        'getAdapter', 'getScrollState', 'setScrollState', 'onTouchEvent', 'onInterceptTouchEvent', 'rowPosition',
        'rowHolder', 'registerChildListener', 'childAttach', 'childDetach', 'vmWord', 'vmLine', 'seek', 'seekItem',
        'nativeLineBegin', 'nativeLineEnd', 'mediaBrowser', 'mediaPosition', 'wordAnimationTag',
    } | {prefix + role for prefix in ('line', 'word') for role in adapter_roles} | set(native_roles) | {
        role + phase for role in word_roles for phase in ('Start', 'End')}
    required_fields = {
        'binding', 'recycler', 'activeAdapter', 'viewModel', 'holderView', 'holderId', 'holderType', 'wordLineId',
        'wordAnimator', 'childListeners', 'words', 'backgroundWords', 'pronunciationWords',
        'pronunciationBackgroundWords', 'instrumentalPosition', 'wordInstrumentalPosition',
    } | set(native_roles) | {role + 'Model' for role in word_roles}
    primitive = {'void': 'V', 'int': 'I', 'long': 'J', 'float': 'F', 'boolean': 'Z'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[') else 'L' + name.replace('.', '/') + ';')
    checks = 0
    for group, required in (('methods', required_methods), ('fields', required_fields)):
        declared = data.get(group, {})
        for role in sorted(required - declared.keys()):
            failures.append(f'lyricBlur.{group}.{role}: missing required role')
        for role, contract in declared.items():
            checks += 1
            try:
                native = classes.get(descriptor(contract['owner']), {})
                if group == 'fields':
                    valid = (native.get('fields', {}).get(contract['name']) == descriptor(contract['type'])
                             and not native.get('field_access', {}).get(contract['name'], 8) & 8)
                else:
                    signature = contract['name'] + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + descriptor(contract['returns'])
                    flags = native.get('method_access', {}).get(signature)
                    forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly') else 0x400)
                    valid = flags is not None and not flags & forbidden and bool(flags & 8) == contract['static']
                if not valid:
                    failures.append(f'lyricBlur.{group}.{role}: invalid exact descriptor')
            except (KeyError, TypeError, AttributeError) as error:
                failures.append(f'lyricBlur.{group}.{role}: incomplete descriptor ({error})')
    return checks


def verify_lyric_result_submission(document, classes, failures):
    """Check the explicitly declared result channel, without inferring missing roles."""
    data = document.get('lyricResultSubmission')
    if data is None:
        return 0
    if not isinstance(data, dict) or data.get('contractVersion') != 1:
        failures.append('lyricResultSubmission: invalid versioned contract')
        return 0
    script = data.get('methods', {}).get('pointerScript', {})
    if script.get('returns') != 'boolean':
        failures.append('lyricResultSubmission.pointerScript: setScript returns boolean')
    primitive = {'void': 'V', 'int': 'I', 'long': 'J', 'boolean': 'Z'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                             else 'L' + name.replace('.', '/') + ';')
    checks = 0
    required = {
        'methods': {'resultSet', 'resultPost', 'resultValue', 'fragmentResume', 'buildIndex', 'loadLyrics', 'systemLyricsLanguage',
                    'pointerGet', 'pointerAdamId', 'pointerScript', 'clear'},
        'fields': {'fragmentViewModel', 'lyricsResult'},
    }
    for group in ('methods', 'fields'):
        entries = data.get(group)
        if not isinstance(entries, dict) or not entries:
            failures.append(f'lyricResultSubmission.{group}: missing inventory')
            continue
        for role in sorted(required[group] - entries.keys()):
            failures.append(f'lyricResultSubmission.{group}.{role}: missing required role')
        for role, member in entries.items():
            checks += 1
            try:
                native = classes.get(descriptor(member['owner']), {})
                if group == 'fields':
                    valid = (native.get('fields', {}).get(member['name']) == descriptor(member['type'])
                             and not native.get('field_access', {}).get(member['name'], 8) & 8)
                else:
                    signature = member['name'] + '(' + ''.join(map(descriptor, member['parameters'])) + ')' + descriptor(member['returns'])
                    flags = native.get('method_access', {}).get(signature)
                    valid = (flags is not None and not flags & (0x40 | 0x400 | 0x1000)
                             and bool(flags & 8) == member.get('static', False))
                if not valid:
                    failures.append(f'lyricResultSubmission.{group}.{role}: invalid exact descriptor')
            except (KeyError, TypeError, AttributeError) as error:
                failures.append(f'lyricResultSubmission.{group}.{role}: incomplete descriptor ({error})')
    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path, help="APK, XAPK or APKS (all DEX splits are checked)")
    parser.add_argument("--version-name", default=None)
    parser.add_argument("--version-code", default=None)
    parser.add_argument("--profile", type=Path, help="explicit profile, including a disabled research build")
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
        package_name, actual_name, actual_code = _manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if package_name != 'com.apple.android.music':
            raise SystemExit('Unexpected manifest package: '+package_name)
        version_name = args.version_name or actual_name
        version_code = args.version_code or str(actual_code)
        if (version_name,str(version_code)) != (actual_name,str(actual_code)):
            raise SystemExit(f'Claimed tuple {version_name}/{version_code} differs from binary manifest {actual_name}/{actual_code}')
        if manifest and ((manifest.get('version_name') and manifest['version_name'] != actual_name) or
                         (manifest.get('version_code') and str(manifest['version_code']) != str(actual_code))):
            raise SystemExit('Split package manifest disagrees with base Android manifest')
        profile = PROFILES.get((version_name or "", str(version_code)))
        document = None
        if args.profile:
            document = json.loads(args.profile.read_text(encoding="utf-8"))
            if (document["packageName"], document["versionName"], int(document["versionCode"])) != (
                package_name, actual_name, actual_code
            ):
                raise SystemExit("Explicit profile differs from binary manifest")
            profile = document["verification"]
        if profile is None:
            raise SystemExit(
                "Unsupported or undocumented version tuple: %s (%s). Known profiles: %s"
                % (version_name, version_code, ", ".join("%s (%s)" % key for key in sorted(PROFILES))),
            )
        if version_code != profile["version_code"]:
            raise SystemExit(
                "Version tuple mismatch: %s (%s) does not match the recorded %s profile"
                % (version_name, version_code, profile["version_code"]),
            )

        ALIASED_TYPES = profile.get("aliases", {})
        GLASS_METHODS = profile.get("glass_methods", {})
        LAYOUTS = profile.get("layouts", [])
        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(dex_classes(apk.read(name)))
        available_layouts = set(apk.namelist())
        for split_name in names:
            if not split_name.endswith(".apk") or split_name == base_name:
                continue
            with zipfile.ZipFile(io.BytesIO(package.read(split_name))) as split:
                available_layouts.update(split.namelist())
                for name in split.namelist():
                    if name.endswith(".dex"):
                        classes.update(dex_classes(split.read(name)))

        failures = []
        checks = 0

        if document is None:
            candidate = PROFILE_DIRECTORY / f"{actual_name}-{actual_code}.json"
            if candidate.is_file():
                document = json.loads(candidate.read_text(encoding="utf-8"))
        if document:
            checks += verify_fragment_contracts(document, classes, failures)
            checks += verify_artist_compose_contract(document, classes, failures)
            checks += verify_lyric_blur_contract(document, classes, failures)
            checks += verify_lyric_result_submission(document, classes, failures)
            def descriptor(name):
                primitive = {'void':'V','int':'I','long':'J','float':'F','boolean':'Z',
                             'double':'D','byte':'B','short':'S','char':'C'}
                return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                                     else 'L' + name.replace('.', '/') + ';')
            for symbol, contract in document['indexed'].get('methodContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                signature = contract['name'] + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + descriptor(contract['returns'])
                flags = classes.get(owner, {}).get('method_access', {}).get(signature)
                # Invocation-only descriptors may name an abstract interface method;
                # reflection dispatches it to the concrete backend. Hook targets still
                # require a non-abstract, non-bridge implementation.
                forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly', False) else 0x400)
                if flags is None or bool(flags & 8) != contract['static'] or flags & forbidden:
                    failures.append(f'invalid exact method contract {symbol}: {owner} {signature}')
            for symbol, contract in document['indexed'].get('fieldContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                native = classes.get(owner, {})
                if native.get('fields', {}).get(contract['name']) != descriptor(contract['type']) or native.get('field_access', {}).get(contract['name'], 8) & 8:
                    failures.append(f'invalid exact field contract {symbol}: {owner} {contract["name"]}')

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

        for owner, fields in profile.get("fieldTypes", {}).items():
            for name, expected in fields.items():
                checks += 1
                native = classes.get(owner, {})
                if native.get("fields", {}).get(name) != expected or native.get("field_access", {}).get(name, 8) & 8:
                    failures.append("invalid native field descriptor %s %s:%s" % (owner, name, expected))

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

        interceptor = profile.get("content_http_interceptor")
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
                if layout not in available_layouts:
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
