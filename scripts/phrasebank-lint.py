#!/usr/bin/env python3
"""Validate phrasebank JSON files (docs/phrasebank.md).

The phrasebank will eventually hold thousands of authored sentences; a
mistake in one of them is shown, spoken and drilled to a learner who cannot
check it. Every batch must pass this lint before it ships. Checks are
structural — meaning is reviewed by humans (and a second model as a third
eye), never by this script.

Usage: scripts/phrasebank-lint.py <file.json>...
"""

import json
import sys

LEVELS = range(1, 8)
TENSES = {
    "present-simple", "present-progressive", "past-simple",
    "past-progressive", "present-perfect", "present-perfect-progressive",
    "past-perfect", "future-simple", "future-going-to", "future-perfect",
    "conditional-zero", "conditional-first", "conditional-second",
    "conditional-third", "modal", "imperative", "mixed",
}


def hebrew_letters(s):
    return sum(1 for ch in s if "֐" <= ch <= "׿")


def check_sentence(path, s, ids, errors):
    def err(msg):
        errors.append(f"{path}: {s.get('id', '<no id>')}: {msg}")

    for key in ("id", "level", "en", "he", "tense", "frame"):
        if key not in s:
            err(f"missing '{key}'")
            return
    if s["id"] in ids:
        err("duplicate id")
    ids.add(s["id"])
    if s["level"] not in LEVELS:
        err(f"level {s['level']} outside 1-7")
    if s["tense"] not in TENSES:
        err(f"unknown tense '{s['tense']}'")
    en, he = s["en"], s["he"]
    if not en.strip() or not he.strip():
        err("empty text")
        return
    if hebrew_letters(en):
        err("Hebrew letters in the en field")
    letters = sum(1 for ch in he if ch.isalpha())
    if letters == 0 or hebrew_letters(he) * 2 < letters:
        err("he field is not mostly Hebrew")
    for variant in ("he_f",):
        v = s.get(variant)
        if v is not None and hebrew_letters(v) == 0:
            err(f"{variant} is not Hebrew")

    align = s.get("align")
    if align is None:
        return
    en_words = en.split()
    he_words = he.split()
    en_seen = [False] * len(en_words)
    he_seen = [False] * len(he_words)
    for cue in align:
        for lang, words, seen in (("en", en_words, en_seen), ("he", he_words, he_seen)):
            span = cue.get(lang)
            if (
                not isinstance(span, list) or len(span) != 2 or
                not all(isinstance(x, int) for x in span)
            ):
                err(f"align cue {cue} has a malformed {lang} span")
                return
            lo, hi = span
            if lo > hi or lo < 0 or hi >= len(words):
                err(f"align cue {cue} out of range for {lang} ({len(words)} words)")
                return
            for i in range(lo, hi + 1):
                if seen[i]:
                    err(f"align cue {cue} overlaps another on {lang}[{i}]")
                seen[i] = True
    # Every English word must belong to exactly one cue when align is present:
    # a word the karaoke can highlight but the meaning row cannot answer for
    # reads as a bug to the learner.
    for i, used in enumerate(en_seen):
        if not used:
            err(f"en word {i} ('{en_words[i]}') not covered by any align cue")
    # Hebrew MAY have uncovered words (added function words); they simply
    # never light up.


def main(paths):
    errors = []
    total = 0
    for path in paths:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        if doc.get("format") != "tuki-phrasebank-v1":
            errors.append(f"{path}: format is not tuki-phrasebank-v1")
            continue
        ids = set()
        for s in doc.get("sentences", []):
            total += 1
            check_sentence(path, s, ids, errors)
        per_level = {}
        for s in doc.get("sentences", []):
            per_level[s.get("level")] = per_level.get(s.get("level"), 0) + 1
        print(f"{path}: {len(doc.get('sentences', []))} sentences, per level {dict(sorted(per_level.items()))}")
    if errors:
        print(f"\n{len(errors)} problem(s):", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    print(f"OK: {total} sentences across {len(paths)} file(s)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
