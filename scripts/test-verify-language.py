#!/usr/bin/env python3
"""Regression: enum resource names containing digits must be checked, even if both languages omit them."""
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(__file__).with_name('verify-language.py').read_text()
with tempfile.TemporaryDirectory(prefix='sentri-language-contract-') as directory:
    root = Path(directory)
    checker = root / 'scripts/verify-language.py'
    checker.parent.mkdir()
    checker.write_text(source)
    mappings = root / 'app/src/main/java/com/sentri/nativeapp/i18n/BusinessEnumKeys.kt'
    mappings.parent.mkdir(parents=True)
    mappings.write_text('"SCORE_1" to "biz_body_condition_score_1"')
    resources = [root / f'app/src/main/res/{locale}/strings.xml' for locale in ('values', 'values-en')]
    for path in resources:
        path.parent.mkdir(parents=True)
        path.write_text('<resources><string name="biz_body_condition_score_1">Score</string></resources>')
    valid = subprocess.run([sys.executable, str(checker)], capture_output=True, text=True)
    assert valid.returncode == 0 and '1 canonical enum mappings' in valid.stdout, valid.stderr
    for path in resources:
        path.write_text('<resources/>')
    missing = subprocess.run([sys.executable, str(checker)], capture_output=True, text=True)
    assert missing.returncode != 0 and 'Missing enum translation' in missing.stderr, missing.stdout
print('Language checker regression passed: digit-bearing enum key checked; missing bilingual mapping rejected.')
