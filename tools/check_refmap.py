#!/usr/bin/env python3
"""Checks the built jar's mixin reference map.

The mixins name Minecraft's methods by their readable names; a real server only knows the obfuscated ones, and the
reference map (written by the mixin annotation processor while compiling) is what translates them. A jar without it,
or with a mixin missing from it, starts fine in a dev run but stops a real server at launch ("No refMap loaded").
This fails the build instead. Usage: check_refmap.py <jar>
"""
import json
import sys
import zipfile


def main(path: str) -> int:
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        configs = [n for n in names if n.startswith('mixins.') and n.endswith('.json') and '/' not in n]
        if not configs:
            print(f'{path}: no mixin config found')
            return 1
        problems = []
        for cfg_name in configs:
            cfg = json.loads(jar.read(cfg_name))
            refmap = cfg.get('refmap')
            if not refmap:
                problems.append(f'{cfg_name} names no refmap')
                continue
            if refmap not in names:
                problems.append(f'{refmap} (named by {cfg_name}) is not in the jar')
                continue
            mappings = json.loads(jar.read(refmap)).get('mappings', {})
            package = cfg['package'].replace('.', '/')
            for side in ('mixins', 'server', 'client'):
                for mixin in cfg.get(side, []):
                    key = f'{package}/{mixin.replace(".", "/")}'
                    # a @Pseudo mixin with remap = false targets another mod by that mod's own names, which are never obfuscated:
                    # no entries needed. A @Pseudo mixin without remap = false still needs them (it may name Minecraft's methods).
                    cls = key + '.class'
                    if cls in names:
                        data = jar.read(cls)
                        if b'Lorg/spongepowered/asm/mixin/Pseudo;' in data and b'remap' in data:
                            continue
                    if not mappings.get(key):
                        problems.append(f'{mixin} has no entries in {refmap}')
    if problems:
        print(f'{path}: the mixin reference map is incomplete; a real server would refuse to start.')
        for p in problems:
            print('  - ' + p)
        print('Run ./gradlew clean build to rebuild it from scratch.')
        return 1
    print(f'{path}: mixin reference map complete')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
