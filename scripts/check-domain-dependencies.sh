#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re
import sys

root = Path('src/main/java/com/shop/domain')
scan_layers = {'service', 'scheduler', 'repository'}
target_layers = {'service', 'repository'}

# domain-level directed edges found in service/scheduler/repository implementation code
edges = {}
for file in root.rglob('*.java'):
    rel = file.relative_to(root)
    parts = rel.parts
    if len(parts) < 3:
        continue

    src_domain, src_layer = parts[0], parts[1]
    if src_layer not in scan_layers:
        continue

    content = file.read_text(encoding='utf-8')
    for m in re.finditer(r'^import\s+com\.shop\.domain\.([a-z0-9_]+)\.([a-z0-9_]+)\..*;', content, re.MULTILINE):
        dst_domain, dst_layer = m.group(1), m.group(2)
        if dst_domain == src_domain:
            continue
        if dst_layer not in target_layers:
            continue
        edges.setdefault((src_domain, dst_domain), []).append((str(file), m.group(0)))

bidirectional = []
for (a,b), refs in edges.items():
    if (b,a) in edges and a < b:
        bidirectional.append((a,b,refs,edges[(b,a)]))

if bidirectional:
    print('Bidirectional cross-domain dependencies detected (forbidden):')
    for a,b,ab,ba in bidirectional:
        print(f'\n- {a} <-> {b}')
        for f,i in ab:
            print(f'  {a} -> {b}: {f}: {i}')
        for f,i in ba:
            print(f'  {b} -> {a}: {f}: {i}')
    sys.exit(1)

print('No bidirectional cross-domain service/repository dependencies found.')
PY
