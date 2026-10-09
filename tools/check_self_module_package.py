#!/usr/bin/env python3
"""Verify the produced .ails artifact and embedded page syntax on the cloud runner."""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('package', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--identity', required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.package) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate archive entry'
        files = {name: archive.read(name) for name in names}
    manifest = json.loads(files.pop('self.json'))
    assert manifest['format'] == 'AIL_SELF_V1' and manifest['module_type'] == 'self'
    assert manifest['package_kind'] == 'module'
    assert manifest['identity_id'] == str(uuid.UUID(args.identity))
    assert manifest['module_version'] == args.version
    assert manifest['state_schema_version'] == 1 and 1 in manifest['compatible_state_schemas']
    assert manifest['integrity']['algorithm'] == 'sha256'
    checks = manifest['integrity']['entries']
    assert set(checks) == set(files)
    for name, data in files.items():
        assert hashlib.sha256(data).hexdigest() == checks[name], 'Integrity mismatch: ' + name
    program = json.loads(files['program/blank.json'])
    assert program['entry'] == 'blank' and program['module_version'] == args.version
    descriptor = json.loads(files['resources/presentation.json'])
    assert descriptor['api_version'] == 1 and descriptor['runtime'] == 'html'
    with tempfile.TemporaryDirectory() as temp:
        for field in ('entry', 'summary_entry'):
            page = files[descriptor[field]].decode('utf-8', errors='strict')
            assert re.search(r'<head(?:\s[^>]*)?>', page, re.I)
            ids = re.findall(r'\bid="([^"]+)"', page)
            assert len(ids) == len(set(ids)), 'Duplicate page element ID'
            assert set(re.findall(r"\$\('([^']+)'\)", page)) <= set(ids), 'Missing page element'
            scripts = re.findall(r'<script>(.*?)</script>', page, re.S)
            assert scripts, 'Missing startup script'
            source = Path(temp, field + '.js')
            source.write_text('\n'.join(scripts))
            subprocess.run(['node', '--check', str(source)], check=True)
    report = dict(package=args.package.name, module_version=args.version,
                  identity_id=manifest['identity_id'], state_schema_version=1,
                  package_sha256=hashlib.sha256(args.package.read_bytes()).hexdigest(),
                  integrity_verified=True, page_syntax_verified=True,
                  android_build_required=False)
    Path(args.package.parent, 'package-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
