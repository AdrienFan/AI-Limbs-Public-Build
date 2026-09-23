#!/usr/bin/env python3
"""Validate plugin versions and bind a packaged artifact to its Git source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGES = ROOT / 'plugin-lab/packages'
VERSION_NAME = re.compile(r'versionName\s*=\s*"([^"]+)"')


def git(*args: str) -> str:
    return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True).strip()


def source() -> dict:
    commit = git('rev-parse', 'HEAD')
    # A receipt must never attribute local source edits to an older commit.
    changed = git('status', '--porcelain', '--untracked-files=all', '--',
                  'plugin-lab', 'tools/plugin_lab', '.github/workflows')
    if changed:
        raise ValueError('Commit all plugin source and packaging changes before building')
    if os.environ.get('GITHUB_SHA') and os.environ['GITHUB_SHA'] != commit:
        raise ValueError('GitHub run SHA differs from checked-out source')
    repository = os.environ.get('GITHUB_REPOSITORY', 'AdrienFan/AI-Limbs-Public-Build')
    ref = os.environ.get('GITHUB_REF_NAME') or git('branch', '--show-current')
    run_id = os.environ.get('GITHUB_RUN_ID')
    return {
        'repository': repository,
        'commit': commit,
        'ref': ref,
        'url': f'https://github.com/{repository}/tree/{commit}',
        'run_url': f'https://github.com/{repository}/actions/runs/{run_id}' if run_id else None,
    }


def inventory() -> list[dict]:
    records = []
    seen = set()
    for manifest in sorted(PACKAGES.glob('*/[ep]*.json')):
        if manifest.name not in ('plugin.json', 'extension.json'):
            continue
        kind = 'extensions' if manifest.name == 'extension.json' else 'plugins'
        gradle = ROOT / 'plugin-lab' / kind / manifest.parent.name / 'build.gradle.kts'
        if not gradle.is_file():
            raise ValueError(f'Missing module for {manifest}')
        content = json.loads(manifest.read_text(encoding='utf-8'))
        identifier = content.get('extension_id') if kind == 'extensions' else content.get('plugin_id')
        version = content.get('version')
        match = VERSION_NAME.search(gradle.read_text(encoding='utf-8'))
        if not identifier or not isinstance(version, str) or not version.strip():
            raise ValueError(f'Missing id/version in {manifest}')
        if identifier in seen:
            raise ValueError(f'Duplicate plugin or extension id: {identifier}')
        if match is None or match.group(1) != version:
            raise ValueError(f'Manifest/Gradle version mismatch: {manifest}')
        seen.add(identifier)
        records.append({
            'id': identifier,
            'version': version,
            'kind': 'child' if kind == 'extensions' else 'parent',
            'manifest': manifest.relative_to(ROOT).as_posix(),
            'module': gradle.relative_to(ROOT).as_posix(),
            'source_tag': f'source/{manifest.parent.name}/v{version}',
        })
    if not records:
        raise ValueError('No plugin manifests found')
    return records


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(path)


def record(manifest: Path, package: Path) -> None:
    path = manifest.resolve()
    if path not in {ROOT / item['manifest'] for item in inventory()}:
        raise ValueError(f'Unregistered manifest: {manifest}')
    expected = json.loads(path.read_text(encoding='utf-8'))
    archive_name = path.name
    payload = 'payload/extension.apk' if archive_name == 'extension.json' else 'payload/plugin.apk'
    with zipfile.ZipFile(package) as archive:
        packaged = json.loads(archive.read(archive_name))
        apk_hash = digest(archive.read(payload))
    identifier = 'extension_id' if archive_name == 'extension.json' else 'plugin_id'
    if (packaged[identifier], packaged['version']) != (expected[identifier], expected['version']):
        raise ValueError('Packaged id/version does not match committed manifest')
    if packaged['integrity']['entries'][payload] != apk_hash:
        raise ValueError('Packaged APK hash does not match signed manifest')
    if not packaged.get('signature', {}).get('signer_id'):
        raise ValueError('Packaged plugin has no signing identity')
    sha = digest(package.read_bytes())
    meta = next(item for item in inventory() if item['manifest'] == path.relative_to(ROOT).as_posix())
    write_json(package.with_suffix(package.suffix + '.source.json'), {
        'schema_version': 1,
        'package': {'id': meta['id'], 'version': meta['version'], 'kind': meta['kind'],
                    'file': package.name, 'sha256': sha, 'payload_sha256': apk_hash,
                    'signer_id': packaged['signature']['signer_id']},
        'manifest': meta['manifest'],
        'source': {**source(), 'release_tag': meta['source_tag']},
    })


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('check')
    check.add_argument('--output', type=Path)
    artifact = sub.add_parser('record')
    artifact.add_argument('--manifest', type=Path, required=True)
    artifact.add_argument('--package', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'check':
        data = {'schema_version': 1, 'source': source(), 'packages': inventory()}
        if args.output:
            write_json(args.output, data)
        else:
            print(f"Validated {len(data['packages'])} plugin and extension sources at {data['source']['commit']}")
    else:
        record(args.manifest, args.package)


if __name__ == '__main__':
    main()
