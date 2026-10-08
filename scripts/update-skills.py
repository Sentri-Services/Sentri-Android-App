#!/usr/bin/env python3
"""Replace a reviewed upstream revision; roll back files and metadata on ordinary errors."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
from datetime import datetime
from zoneinfo import ZoneInfo

parser = argparse.ArgumentParser()
parser.add_argument('--revision', required=True, help='Reviewed 40-character commit SHA')
args = parser.parse_args()
assert len(args.revision) == 40 and all(c in '0123456789abcdef' for c in args.revision)
root = Path(__file__).resolve().parent.parent
vendor_paths = ['.agents/skills', '.agents/skills-lock.json', '.agents/MATT-POCOCK-LICENSE']
dirty = subprocess.check_output(['git', 'status', '--porcelain', '--', *vendor_paths], cwd=root)
assert not dirty, 'Commit or preserve staged, working-tree and untracked vendor edits before updating'
with tempfile.TemporaryDirectory() as tmp:
    archive = Path(tmp) / 'upstream.zip'
    with urllib.request.urlopen(f'https://codeload.github.com/mattpocock/skills/zip/{args.revision}', timeout=60) as response:
        archive.write_bytes(response.read())
    with zipfile.ZipFile(archive) as bundle:
        for name in bundle.namelist():
            assert not name.startswith('/') and '..' not in Path(name).parts
        bundle.extractall(tmp)
    upstream = Path(tmp) / f'skills-{args.revision}'
    staging = Path(tmp) / 'installed'
    staging.mkdir()
    skills = []
    for manifest in sorted((upstream / 'skills').rglob('SKILL.md')):
        destination = staging / manifest.parent.name
        assert not destination.exists(), f'Duplicate skill name: {destination.name}'
        shutil.copytree(manifest.parent, destination)
        skills.append({'name': destination.name, 'upstreamPath': str(manifest.parent.relative_to(upstream)),
            'files': {str(p.relative_to(destination)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted(destination.rglob('*')) if p.is_file()}})
    assert skills, 'Upstream has no skill manifests'
    backup = root / '.agents/skills.previous'
    assert not backup.exists(), 'Resolve the previous backup before updating'
    replacement = root / '.agents/skills.staging'
    assert not replacement.exists(), 'Resolve the previous staging directory before updating'
    lock_path = root / '.agents/skills-lock.json'
    license_path = root / '.agents/MATT-POCOCK-LICENSE'
    old_lock, old_license = lock_path.read_bytes(), license_path.read_bytes()
    lock = {'repository': 'https://github.com/mattpocock/skills', 'revision': args.revision,
        'installedAt': datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat(), 'skills': skills}
    new_lock = (json.dumps(lock, indent=2) + '\n').encode()
    new_license = next(upstream.glob('LICENSE*')).read_bytes()
    shutil.copytree(staging, replacement)
    try:
        (root / '.agents/skills').rename(backup)
        replacement.rename(root / '.agents/skills')
        lock_path.write_bytes(new_lock)
        license_path.write_bytes(new_license)
    except Exception:
        if backup.exists():
            if (root / '.agents/skills').exists():
                shutil.rmtree(root / '.agents/skills')
            backup.rename(root / '.agents/skills')
            lock_path.write_bytes(old_lock)
            license_path.write_bytes(old_license)
        raise
    finally:
        if replacement.exists():
            shutil.rmtree(replacement)
    shutil.rmtree(backup)
print(f'Installed {len(skills)} skills. Review the diff and run scripts/verify-skills.py.')
