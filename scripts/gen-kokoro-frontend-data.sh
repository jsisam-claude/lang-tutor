#!/usr/bin/env bash
# Regenerate the committed Kokoro front-end data files in core/speech resources:
#
#   kokoro/cmudict.txt — CMU Pronouncing Dictionary (BSD-2-Clause, © Carnegie
#     Mellon University), first pronunciation per word, comments stripped.
#     Source pinned to cmusphinx/cmudict commit $CMUDICT_COMMIT.
#   kokoro/vocab.tsv — Kokoro/misaki phoneme-char → token-id map ("id<TAB>char",
#     one per line; the char may be a space). Source: hexgrad/Kokoro-82M
#     config.json `vocab` (Apache-2.0), 114 entries.
#
# Rerun only to refresh the pins; both outputs are committed so builds and
# tests never need the network.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/core/speech/src/main/resources/kokoro"
CMUDICT_COMMIT="74790861f652b15e4ac49015a90074ad62a27690"
mkdir -p "$DEST"

curl -fsSL "https://raw.githubusercontent.com/cmusphinx/cmudict/$CMUDICT_COMMIT/cmudict.dict" |
  awk '
    /^;;;/ { next }                       # header comments
    {
      sub(/ #.*$/, "")                    # trailing per-entry comments
      if ($1 ~ /\([0-9]+\)$/) next        # alternate pronunciations: keep first only
      print
    }
  ' > "$DEST/cmudict.txt"

python3 - "$DEST/vocab.tsv" <<'PY'
import json, sys, urllib.request
url = "https://huggingface.co/hexgrad/Kokoro-82M/raw/main/config.json"
vocab = json.load(urllib.request.urlopen(url))["vocab"]
with open(sys.argv[1], "w", encoding="utf-8") as f:
    for ch, i in sorted(vocab.items(), key=lambda kv: kv[1]):
        f.write(f"{i}\t{ch}\n")
print(f"vocab.tsv: {len(vocab)} entries")
PY

wc -l "$DEST/cmudict.txt" "$DEST/vocab.tsv"
