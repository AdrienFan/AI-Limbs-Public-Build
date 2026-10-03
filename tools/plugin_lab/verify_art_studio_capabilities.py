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
    for name in re.findall(r'\b(?:capability|registerCapability)\(\s*"([^"]+)"', source)
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

# Keep examples in the existing plugin CI gate: a capability without usable help
# must not silently receive Resolver's zero/empty automatic example again.
HELP = ENTRY.with_name('ArtCapabilityHelp.kt')
help_source = HELP.read_text(encoding='utf-8')
help_parts = {
    name: json.loads(body)
    for name, body in re.findall(
        r'private const val (EXAMPLES(?:_\d+)?|FIELDS|SCOPED(?:_\d+)?) = """\s*(\{.*?\})\s*"""',
        help_source,
        re.S,
    )
}
if not any(n=='EXAMPLES' or n.startswith('EXAMPLES_') for n in help_parts) or 'FIELDS' not in help_parts:
    raise SystemExit('Art Studio help metadata blocks are missing or invalid')
if 'SCOPED' in help_parts:
    scoped = help_parts['SCOPED']
    if any(name.startswith('SCOPED_') for name in help_parts):
        raise SystemExit('Art Studio scoped help mixes legacy and segmented metadata')
else:
    names = sorted((name for name in help_parts if name.startswith('SCOPED_')), key=lambda name: int(name.split('_')[1]))
    if not names:
        raise SystemExit('Art Studio scoped help metadata is missing')
    scoped = {}
    for name in names:
        overlap = set(scoped) & set(help_parts[name])
        if overlap:
            raise SystemExit(f'Duplicate scoped help keys: {sorted(overlap)}')
        scoped.update(help_parts[name])
example_names = [n for n in help_parts if n == 'EXAMPLES' or n.startswith('EXAMPLES_')]
if 'EXAMPLES' in example_names and len(example_names) != 1:
    raise SystemExit('Art Studio examples mix legacy and segmented metadata')
merged_examples = {}
for name in sorted(example_names):
    if set(merged_examples) & set(help_parts[name]):
        raise SystemExit('Duplicate Art Studio example keys')
    merged_examples.update(help_parts[name])
help_blocks = {'EXAMPLES': merged_examples, 'FIELDS': help_parts['FIELDS'], 'SCOPED': scoped}
expected_help = {identity.removeprefix(f'{plugin_id}.') for identity in declared}
examples = help_blocks['EXAMPLES']
if set(examples) != expected_help:
    raise SystemExit(f'Art Studio example coverage mismatch: missing={sorted(expected_help-set(examples))}, extra={sorted(set(examples)-expected_help)}')
for name, help_item in examples.items():
    if not isinstance(help_item['args'], dict) or not help_item['summary'].strip() or not isinstance(help_item['note'], str):
        raise SystemExit(f'Invalid capability help: {name}')
    for key in ('color', 'background', 'baseColor', 'boundaryColor', 'gradientEndColor', 'regionColor'):
        if key in help_item['args'] and not re.fullmatch(r'#[A-Fa-f0-9]{8}', help_item['args'][key]):
            raise SystemExit(f'Invalid example color: {name}.{key}')
parameter_names = set(re.findall(r'\bp\("([^"]+)"', source[source.index('internal fun parametersFor'):]))
if parameter_names - set(help_blocks['FIELDS']):
    raise SystemExit(f'Missing parameter help: {sorted(parameter_names-set(help_blocks["FIELDS"]))}')
for key, rule in help_blocks['FIELDS'].items():
    if not rule['description'].strip() or rule['description'] == key:
        raise SystemExit(f'Parameter help is only a name: {key}')
if 'suggestedParamsJson = ArtCapabilityHelp.example(name).toString()' not in source:
    raise SystemExit('Art Studio examples are not published through Runtime API')
if 'ArtCapabilityHelp.property(name, field)' not in source:
    raise SystemExit('Art Studio parameter enums are not published in inputSchema')
print(f'Art Studio compact help OK: {len(examples)} examples; {len(parameter_names)} documented parameter names; Runtime API wiring present.')
