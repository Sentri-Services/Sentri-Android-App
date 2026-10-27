#!/usr/bin/env python3
"""Copy the canonical Sentri-UI icon paths into Android vector resources.

Run from the Android repo with: python3 scripts/sync_sentri_icons.py
The source is read only. Review source changes before regenerating assets.
"""

from pathlib import Path
import re
import xml.sax.saxutils

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "Sentri-UI/ux/design-system/components/bundle.js"
DEST = ROOT / "app/src/main/res/drawable"
KOTLIN = ROOT / "app/src/main/java/com/sentri/nativeapp/design/SentriIcons.kt"


def main() -> None:
    source = SOURCE.read_text()
    registry = source.split("const paths={", 1)[1].split("\n  };", 1)[0]
    paths = dict(re.findall(r"^\s*([a-z][a-z0-9]*):'([^']+)'", registry, re.M))
    if len(paths) < 45:
        raise SystemExit(f"Expected full icon registry; found {len(paths)} paths")
    DEST.mkdir(parents=True, exist_ok=True)
    for name, path in paths.items():
        if name == "more":
            shape = "\n".join(
                f'    <path android:fillColor="#FF000000" android:pathData="M{cx},10.5a1.5,1.5 0,1 0,0 3a1.5,1.5 0,1 0,0 -3z" />'
                for cx in (5, 12, 19)
            )
        else:
            shape = (
                '    <path android:fillColor="@android:color/transparent" '
                'android:strokeColor="#FF000000" android:strokeWidth="1.8" '
                'android:strokeLineCap="round" android:strokeLineJoin="round" '
                f'android:pathData="{xml.sax.saxutils.escape(path)}" />'
            )
        content = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24">\n'
            f'{shape}\n</vector>\n'
        )
        (DEST / f"sentri_ic_{name}.xml").write_text(content)
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    cases = "\n".join(f'        "{name}" -> R.drawable.sentri_ic_{name}' for name in paths)
    KOTLIN.write_text(
        "package com.sentri.nativeapp.design\n\n"
        "import androidx.annotation.DrawableRes\n"
        "import com.sentri.nativeapp.R\n\n"
        "/** Generated from Sentri-UI's canonical icon registry. Unknown names have no fallback glyph. */\n"
        "object SentriIcons {\n"
        "    @DrawableRes fun resolve(name: String): Int? = when (name) {\n"
        f"{cases}\n"
        '        "origin" -> R.drawable.sentri_ic_place\n'
        '        "arrow" -> R.drawable.sentri_ic_chevron\n'
        '        "condition" -> R.drawable.sentri_ic_plus\n'
        "        else -> null\n"
        "    }\n"
        "}\n"
    )
    print(f"Synced {len(paths)} icons from {SOURCE}")


if __name__ == "__main__":
    main()
