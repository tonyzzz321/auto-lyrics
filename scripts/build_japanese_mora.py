#!/usr/bin/env python3
"""Build the app's per-kanji mora table from JMDict.

Source: JMdict (Electronic Dictionary Research and Development Group, CC
BY-SA 4.0) via the scriptin/jmdict-simplified JSON release. Output:
app/src/main/assets/japanese/kanji_mora.txt - one "kanji<TAB>mora" line
per character, with '#'-comment attribution headers.

Selection rules (mirrored by PronouncedLengthTest notes):
- only single-character kanji FORMS are used (a form of length 1 inside
  any entry; multi-char forms say nothing about one character);
- the reading mora count is taken from kana forms applying to that
  character, preferring common readings; ties resolved by majority over
  the mora counts of all best-rank entries;
- okurigana cannot occur (form is one character), readings like
  見=みる only enter via multi-char forms and are therefore never read.
The table prices the STANDALONE common reading of each kanji - the
karaoke-app standard approximation for mora-counting song lyrics. The
Kotlin loader falls back to 1 mora for missing characters.
"""
import json
import urllib.request

RELEASE = (
    "https://github.com/scriptin/jmdict-simplified/releases/download/"
    "3.6.2%2B20261026175559/jmdict-eng-3.6.2%2B20261026175559.json.tgz"
)
OUT = "app/src/main/assets/japanese/kanji_mora.txt"

SMALL_Y = set("ゃゅょャュョ")


def mora(kana: str) -> int:
    """Mora count of a kana spelling: one per kana mora character, small
    ya/yu/yo merge into the preceding mora, the prolonged-sound marks and
    the sokuon (small tsu) each carry their own mora."""
    n = 0
    for ch in kana:
        if ch in SMALL_Y:
            continue
        if ch in "゠〜ー":
            n += 1
            continue
        if "\u3041" <= ch <= "\u309f":
            n += 1
    return n


def main() -> None:
    import io
    import tarfile

    print(f"fetch {RELEASE}")
    raw = urllib.request.urlopen(RELEASE, timeout=120).read()
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith(".json"))
        data = json.load(tar.extractfile(member))

    best = {}
    for entry in data["words"]:
        kana = entry.get("kana") or []
        if not kana:
            continue
        kana_ranked = [
            (0 if k.get("common") else 1, mora(k["text"]), k) for k in kana
        ]
        for k in entry.get("kanji") or []:
            if len(k["text"]) != 1:
                continue
            ch = k["text"]
            if not ("\u4e00" <= ch <= "\u9fff"):
                continue
            tags = set(k.get("tags") or [])
            rank = 2 if ("oK" in tags or "sK" in tags) else (0 if k.get("common") else 1)
            readings = [
                (r, m)
                for (r, m, kk) in kana_ranked
                if m >= 1
                and ("*" in (kk.get("appliesToKanji") or ["*"]) or ch in kk["appliesToKanji"])
            ]
            if not readings:
                continue
            r_rank, m = min(readings)
            r = max(rank, r_rank)
            if ch not in best or r < best[ch][0]:
                best[ch] = (r, {m: 1})
            elif r == best[ch][0]:
                best[ch][1][m] = best[ch][1].get(m, 0) + 1

    table = {ch: max(c.items(), key=lambda kv: (kv[1], -kv[0]))[0]
             for ch, (r, c) in best.items()}

    with open(OUT, "w", encoding="utf-8") as out:
        out.write("# Per-kanji mora counts from JMdict (https://www.jmdict.org/),\n")
        out.write("# (C) The Electronic Dictionary Research and Development Group,\n")
        out.write("# licensed CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).\n")
        out.write("# Derived via scriptin/jmdict-simplified; see scripts/build_japanese_mora.py.\n")
        out.write("# Format: single kanji char, TAB, mora count of its most common\n")
        out.write("# standalone reading. Loader falls back to 1 for missing chars.\n")
        for ch in sorted(table):
            out.write(f"{ch}\t{table[ch]}\n")
    print(f"wrote {len(table)} kanji to {OUT}")


if __name__ == "__main__":
    main()
