#!/usr/bin/env python3
"""Build the app's Chinese conversion dictionaries from OpenCC.

Source: https://github.com/BYVoid/OpenCC (Apache-2.0), data/dictionary/*.txt.
Output: app/src/main/assets/chinese/*.txt — one "src<TAB>dst" pair per line.

Cleaning rules (mirrored by the ChineseVariantDataTest):
- the first candidate wins for multi-candidate character entries;
- same-length pairs only (keeps every karaoke token span index-stable);
- identity pairs kept for phrases (context guards, e.g. 皇后 -> 皇后),
  dropped for characters (pure no-ops).
"""
import re
import urllib.request

BASE = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/"
OUT = "app/src/main/assets/chinese/"
FILES = {
    "STCharacters.txt": ("stcharacters.txt", "char"),
    "STPhrases.txt": ("stphrases.txt", "phrase"),
    "TSCharacters.txt": ("tscharacters.txt", "char"),
    "TSPhrases.txt": ("tsphrases.txt", "phrase"),
}


def u16(s: str) -> int:
    """UTF-16 code-unit length == Kotlin String.length."""
    return len(s.encode("utf-16-le")) // 2


def clean(raw: str, kind: str):
    seen = set()
    rows = []
    for line in raw.splitlines():
        if not line or line.startswith("#"):
            continue
        # Tab-only split: keys and values may legitimately contain spaces
        # (fullwidth-space punctuation pairs); whitespace-splitting corrupts
        # them and the first-candidate pick relies on the value's own spacing.
        parts = line.rstrip("\r\n").split("\t")
        if len(parts) < 2:
            continue
        src, dst = parts[0], parts[1].split(" ")[0]
        if u16(src) != u16(dst):
            continue
        if kind == "char" and u16(src) != 1:
            continue
        if kind == "char" and src == dst:
            continue
        if src in seen:
            continue
        seen.add(src)
        rows.append(f"{src}\t{dst}")
    return rows


def main():
    import os
    os.makedirs(OUT, exist_ok=True)
    for name, (out, kind) in FILES.items():
        raw = urllib.request.urlopen(BASE + name, timeout=60).read().decode("utf-8")
        rows = clean(raw, kind)
        with open(OUT + out, "w", encoding="utf-8") as f:
            f.write("\n".join(rows) + "\n")
        print(f"{out}: {len(rows)} pairs")


if __name__ == "__main__":
    main()
