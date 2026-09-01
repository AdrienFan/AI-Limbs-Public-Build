#!/usr/bin/env python3
from pathlib import Path
import hashlib, json, shutil, sys, zipfile
ROOT=Path(__file__).resolve().parents[2]
DIST=ROOT/'dist'; DIST.mkdir(exist_ok=True)

def find_apk(rel):
    files=sorted((ROOT/rel).glob('*.apk'))
    if len(files)!=1:
        raise SystemExit(f'Expected exactly one APK under {rel}, got {files}')
    return files[0]

def pack(out_name, manifest, apk, apk_entry):
    out=DIST/out_name
    if out.exists(): out.unlink()
    with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        z.writestr(manifest.name, manifest.read_bytes())
        z.write(apk, apk_entry)
    return out

items=[
 ('plugin-extension-hub.ailp', ROOT/'plugin-lab/packages/extension-hub/plugin.json', find_apk('plugin-lab/plugins/extension-hub/build/outputs/apk/debug'), 'payload/plugin.apk'),
 ('bridge-core.ailp', ROOT/'plugin-lab/packages/bridge-core/plugin.json', find_apk('plugin-lab/plugins/bridge-core/build/outputs/apk/debug'), 'payload/plugin.apk'),
 ('rdc-provider.ailx', ROOT/'plugin-lab/packages/rdc/extension.json', find_apk('plugin-lab/extensions/rdc/build/outputs/apk/debug'), 'payload/extension.apk'),
 ('triggercmd-provider.ailx', ROOT/'plugin-lab/packages/triggercmd/extension.json', find_apk('plugin-lab/extensions/triggercmd/build/outputs/apk/debug'), 'payload/extension.apk'),
 ('developer-guide.ailp', ROOT/'plugin-lab/packages/developer-guide/plugin.json', find_apk('plugin-lab/plugins/developer-guide/build/outputs/apk/debug'), 'payload/plugin.apk'),
]
for out_name, manifest, apk, entry in items:
    json.loads(manifest.read_text())
    out=pack(out_name,manifest,apk,entry)
    print(f'{out.name}\t{out.stat().st_size}\t{hashlib.sha256(out.read_bytes()).hexdigest()}')
