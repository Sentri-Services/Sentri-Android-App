#!/usr/bin/env python3
"""Resource contract checks; no screen or style specification is introduced."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parent.parent

def resources(directory):
    result = {}
    for file in directory.glob('*.xml'):
        for element in ET.parse(file).getroot():
            if element.tag not in ('string', 'plurals'): continue
            name = element.attrib['name']
            assert name not in result, f'Duplicate resource: {name}'
            texts = [''.join(element.itertext())] if element.tag == 'string' else [''.join(e.itertext()) for e in element]
            placeholders = [sorted(re.findall(r'%(?:\d+\$)?[a-zA-Z]', t.replace('%%', ''))) for t in texts]
            assert all(p == placeholders[0] for p in placeholders), f'Plural format mismatch: {name}'
            if element.tag == 'plurals': assert any(e.get('quantity') == 'other' for e in element), f'Missing other: {name}'
            result[name] = (element.tag, placeholders[0])
    return result
cn = resources(root / 'app/src/main/res/values')
en = resources(root / 'app/src/main/res/values-en')
assert cn == en, f'Language key/type/placeholder mismatch: {sorted(set(cn) ^ set(en)) or "placeholder"}'
keys = re.findall(r'"(biz_[a-z0-9_]+)"', (root / 'app/src/main/java/com/sentri/nativeapp/i18n/BusinessEnumKeys.kt').read_text())
assert all(k in cn for k in keys), 'Missing enum translation'
print(f'Language contract verified: {len(cn)} entries, {len(keys)} canonical enum mappings.')
