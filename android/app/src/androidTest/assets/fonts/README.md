# Font-import test fixture

`import-sample.ttf` is **Shyniflex Import Test Regular**, an original synthetic
TrueType font created for this project's instrumentation tests. Its outlines are
simple rectangles and a triangle authored in
[`generate_import_sample.py`](../../../../../tools/generate_import_sample.py).
No installed font, downloaded font, third-party outline, or external font
metadata is used as an input. This fixture replaces the previous local font
sample, which is not distributed with this source.

Copyright (C) 2026 shyniflex contributors. The font and its generator are
licensed under **GPL-3.0-or-later**, the same terms as the project; see
[`LICENSE`](../../../../../../LICENSE). The embedded font name table records
this license. fontTools is build tooling, not a source of glyph outlines.

The font contains four glyphs: `.notdef`, space, a box, and a triangle. It maps
space, `A`, `B`, and U+25A1. It is intentionally small and is suitable for testing
font import, hashing, persistence, and typeface loading. It is not a subtitle
typeface for normal viewing and is included only in the test APK.

## Reproduce

From the repository root, using Python and an active virtual environment:

```sh
python -m pip install fonttools==4.60.1
python android/tools/generate_import_sample.py android/app/src/androidTest/assets/fonts/import-sample.ttf
```

The generator fixes the font timestamps, parses every output table, checks its
character map and glyph count, and verifies the complete SFNT checksum. With
the pinned fontTools version, it produces:

- Size: **1,760 bytes**
- SHA-256: `d6597762a5e840ce7f3af631ba3f5b917e2e9f0ede2bc43fa93531e0b0e63d6c`

[`CustomSubtitleFontTest`](../../java/io/mirr/shynigsy/CustomSubtitleFontTest.kt)
exercises the actual Android `Typeface.Builder` import and reopen path.

Tooling reference: [fontTools 4.60.1](https://pypi.org/project/fonttools/4.60.1/)
([MIT license](https://github.com/fonttools/fonttools/blob/4.60.1/LICENSE)).
