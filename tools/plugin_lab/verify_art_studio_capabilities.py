#!/usr/bin/env python3
"""Fail CI when literal Art Studio runtime capabilities are absent from plugin.json."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "plugin-lab/packages/art-studio/plugin.json"
ENTRY = ROOT / "plugin-lab/plugins/art-studio/src/main/java/com/ai/limbs/plugins/artstudio/ArtStudioEntry.kt"

manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
plugin_id = manifest["plugin_id"]
declared_list = manifest["provides"]["capabilities"]
declared = set(declared_list)

if len(declared) != len(declared_list):
    print("Art Studio manifest contains duplicate capability declarations.", file=sys.stderr)
    sys.exit(1)

source = ENTRY.read_text(encoding="utf-8")
literal_names = {
    name
    for name in re.findall(r'\bcapability\(\s*"([^"]+)"', source)
    if "$" not in name
}
runtime_literals = {f"{plugin_id}.{name}" for name in literal_names}
missing = sorted(runtime_literals - declared)
if missing:
    print("Art Studio runtime registers capabilities missing from plugin.json:", file=sys.stderr)
    for capability_id in missing:
        print(f"  - {capability_id}", file=sys.stderr)
    sys.exit(1)

wrong_prefix = sorted(
    capability_id
    for capability_id in declared
    if not capability_id.startswith(f"{plugin_id}.")
)
if wrong_prefix:
    print("Art Studio manifest has capabilities outside its plugin namespace:", file=sys.stderr)
    for capability_id in wrong_prefix:
        print(f"  - {capability_id}", file=sys.stderr)
    sys.exit(1)

print(
    f"Art Studio capability declarations OK: "
    f"{len(runtime_literals)} literal runtime registrations covered; "
    f"{len(declared)} manifest declarations total."
)

# The native menu and AI entry share this inventory. A missing dispatcher branch
# would otherwise ship as a clickable menu that throws only on the phone.
CATALOG = ENTRY.with_name('ArtStudioMenuCatalog.kt')
STORE = ENTRY.with_name('ArtStore.kt')
parts = re.findall(r'"""(.*?)"""', CATALOG.read_text(encoding='utf-8'), re.S)
menus = [json.loads(part) for part in parts]
expected = ['图层', '选择', '滤镜', '工具', '设置', '窗口', '帮助']
if [m['title'] for m in menus] != expected:
    raise SystemExit('Art Studio remaining menu order or coverage is invalid')


def leaves(node):
    if 'children' in node:
        for child in node['children']:
            yield from leaves(child)
    elif 'id' in node:
        yield node


items = [item for menu in menus for item in leaves(menu)]
identities = [item['id'] for item in items]
if len(identities) != len(set(identities)):
    raise SystemExit('Duplicate leaf menu identities')
store_source = STORE.read_text(encoding='utf-8')
start = store_source.index('fun executeMenu(')
end = store_source.index('private fun applyMenuFilter(', start)
handlers = set(re.findall(r'"([a-zA-Z0-9_.-]+)"', store_source[start:end]))
implemented = [item for item in items if item['implemented']]
for item in items:
    if item['implemented']:
        if item['id'] not in handlers or 'parameters' not in item or 'documentWrite' not in item:
            raise SystemExit(f'Menu has no shared execution contract: {item["id"]}')
    elif not item.get('reason'):
        raise SystemExit(f'Unavailable menu has no reason: {item["id"]}')
for item in implemented:
    if item['id'].startswith('filter.') or item['id'] in {'cut_layer_clipboard', 'merge_layer', 'flatten_image'}:
        if not item['documentWrite']:
            raise SystemExit(f'Destructive menu lacks revision protection: {item["id"]}')
for name in ('menu.catalog', 'menu.execute'):
    if f'{plugin_id}.{name}' not in declared:
        raise SystemExit(f'Missing shared menu capability: {name}')
print(f'Art Studio menus OK: {len(items)} leaf items, {len(implemented)} shared implementations; disabled reasons complete.')
