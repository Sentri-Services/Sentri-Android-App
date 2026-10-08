#!/usr/bin/env python3
"""Check the complete pinned upstream skill collection, including bundled resources."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / '.agents/skills-lock.json').read_text())
expected = set()
for skill in lock['skills']:
    directory = root / '.agents/skills' / skill['name']
    expected.add(skill['name'])
    actual_files = {str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file()}
    assert actual_files == set(skill['files']), f"Resource mismatch: {skill['name']}"
    for filename, digest in skill['files'].items():
        actual = hashlib.sha256((directory / filename).read_bytes()).hexdigest()
        assert actual == digest, f"Upstream file changed: {skill['name']}/{filename}"
actual = {p.parent.name for p in (root / '.agents/skills').glob('*/SKILL.md')}
assert actual == expected, 'Installed skill collection differs from the lock'
print(f"Verified {len(expected)} complete skills at {lock['revision']}")
