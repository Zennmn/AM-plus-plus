#!/usr/bin/env python3
"""Enforce module direction and private host knowledge without an Android runtime."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {
    'app': {'core','host-api','hook-runtime','host-applemusic','host-classical','glass','plugin-api','plugin-runtime'},
    'plugin-api': set(), 'plugin-runtime': {'plugin-api','hook-runtime'},
    'core': set(), 'host-api': {'core'}, 'hook-runtime': {'core'},
    'host-applemusic': {'core','host-api','hook-runtime'}, 'host-classical': {'core','host-api','hook-runtime'},
    'glass': {'core','backdrop'}, 'backdrop': set(),
}

def check():
    failures = []
    for module, allowed in ALLOWED.items():
        build = (ROOT/module/'build.gradle.kts').read_text(encoding='utf-8')
        dependencies = set(re.findall(r'project\(":([^"\n]+)"\)',build))
        for dependency in dependencies-allowed:
            failures.append(f'{module} -> {dependency} violates dependency direction')
    for path in (ROOT/'core/src/main').rglob('*.kt'):
        source = path.read_text(encoding='utf-8')
        for forbidden in (r'^import android[x]?\.',r'^import io\.github\.libxposed\.',r'^import java\.lang\.reflect\.',
                          r'^import dev\.amenhancer\.glass\.',r'^import org\.luckypray\.dexkit\.'):
            if re.search(forbidden,source,re.M): failures.append(f'{path.relative_to(ROOT)}: {forbidden}')
    for module in ('plugin-api', 'plugin-runtime'):
        for path in (ROOT/module/'src/main').rglob('*'):
            if path.suffix not in ('.kt', '.java'): continue
            source = path.read_text(encoding='utf-8')
            if re.search(r'com\.apple\.android\.music|AppleMusicSymbols|AppleMusicHostProfiles|ClassicalHostProfiles|ClassicalSymbols|^import .*\.(host\.(?:applemusic|classical)|glass|lyricon)\.',source,re.M):
                failures.append(f'{path.relative_to(ROOT)} contains host-specific implementation')
            if module == 'plugin-api' and re.search(r'^import (androidx|io\.github\.libxposed|org\.luckypray|dev\.amenhancer\.module)\.',source,re.M):
                failures.append(f'{path.relative_to(ROOT)} exposes internal SDK dependencies')
    for path in (ROOT/'host-api/src/main').rglob('*.kt'):
        source=path.read_text(encoding='utf-8')
        if re.search(r'^import (androidx|java\.lang\.reflect|io\.github\.libxposed|org\.luckypray)',source,re.M):
            failures.append(f'{path.relative_to(ROOT)} exposes implementation dependencies')
    for path in (ROOT/'app/src/main/java/dev/amenhancer/module').rglob('*.kt'):
        source = path.read_text(encoding='utf-8')
        if path.parent.name=='ui':
            if re.search(r'^import .*\.(lyrics\.source|host\.(?:applemusic|classical)|java\.lang\.reflect)\.',source,re.M):
                failures.append(f'{path.relative_to(ROOT)} imports native or network implementation')
            if re.search(r'Class\.forName|getDeclared(?:Field|Method)|\.declaredFields|ModernXposedRuntime\.callMethod',source):
                failures.append(f'{path.relative_to(ROOT)} discovers native members')
        if path.name.endswith('Feature.kt') or path.name in ('PhoneGlassSession.kt','TabletDualPaneGlassSession.kt','ClassicalGlassSession.kt') or path.name.startswith(('FragmentGlass','FragmentTabletGlass','FragmentPhoneGlass','AmMultiSurface')):
            if re.search(r'getDeclared(?:Field|Method)|\.declaredFields|AppleMusicSymbols|ClassicalSymbols|TargetSymbolResolver|ModernXposedRuntime\.callMethod',source):
                failures.append(f'{path.relative_to(ROOT)} bypasses semantic host API')
    if failures: raise SystemExit('\n'.join(failures))
    print('PASS: module dependencies, JVM core, semantic API, feature and settings boundaries')

if __name__=='__main__': check()
