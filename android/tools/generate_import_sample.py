#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the original geometric TTF used only by the font-import tests.

Requires fonttools==4.60.1. No source font files are read.
"""

import argparse
import hashlib
import struct
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont


def shape(*contours):
    pen = TTGlyphPen(None)
    for points in contours:
        pen.moveTo(points[0])
        for point in points[1:]:
            pen.lineTo(point)
        pen.closePath()
    return pen.glyph()


def build(output):
    builder = FontBuilder(1000, isTTF=True)
    order = [".notdef", "space", "box", "triangle"]
    builder.setupGlyphOrder(order)
    # These four simple outlines are authored here; no installed or external
    # typeface, outline, font metrics, or font metadata is used as input.
    builder.setupGlyf({
        ".notdef": shape([(80, 0), (80, 700), (520, 700), (520, 0)],
                         [(160, 100), (440, 100), (440, 600), (160, 600)]),
        "space": shape(),
        "box": shape([(100, 0), (100, 600), (500, 600), (500, 0)],
                     [(180, 80), (420, 80), (420, 520), (180, 520)]),
        "triangle": shape([(40, 0), (300, 700), (560, 0)]),
    })
    builder.setupCharacterMap({0x20: "space", 0x41: "triangle", 0x42: "box", 0x25A1: "box"})
    builder.setupHorizontalMetrics({name: (600, 0 if name == "space" else 40) for name in order})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({
        "familyName": "Shyniflex Import Test",
        "styleName": "Regular",
        "uniqueFontIdentifier": "ShyniflexImportTest-1.0",
        "fullName": "Shyniflex Import Test Regular",
        "psName": "ShyniflexImportTest-Regular",
        "version": "Version 1.000",
        "copyright": "Copyright (C) 2026 shyniflex contributors. Original test fixture.",
        "licenseDescription": "GNU General Public License version 3 or later; see repository LICENSE.",
        "licenseInfoURL": "https://www.gnu.org/licenses/gpl-3.0.html",
    })
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200,
                     usWinAscent=800, usWinDescent=200, fsType=0)
    builder.setupPost()
    builder.setupMaxp()
    # Fixed OpenType (1904-epoch) timestamps make regeneration byte-identical.
    builder.font["head"].created = builder.font["head"].modified = 3850070400
    builder.font.recalcTimestamp = False
    output.parent.mkdir(parents=True, exist_ok=True)
    builder.save(output)
    validate(output)


def validate(path):
    data = path.read_bytes()
    assert data[:4] == b"\x00\x01\x00\x00", "Expected TrueType sfnt signature"
    padded = data + b"\0" * (-len(data) % 4)
    checksum = sum(struct.unpack(">%dI" % (len(padded) // 4), padded)) & 0xFFFFFFFF
    assert checksum == 0xB1B0AFBA, "Invalid sfnt checksum adjustment"
    font = TTFont(path, checkChecksums=2, lazy=False, recalcTimestamp=False)
    for tag in font.keys():
        font[tag]  # Force every table to parse.
    assert font.getGlyphOrder() == [".notdef", "space", "box", "triangle"]
    assert font.getBestCmap()[0x41] == "triangle"
    assert font["maxp"].numGlyphs == 4
    font.close()
    print(f"{path}: {len(data)} bytes; SHA256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.output)
