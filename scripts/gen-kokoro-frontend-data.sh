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
#
# These are COMMITTED resources, so the failure that matters is not a wasted
# download — it is destroying a good file. `curl ... > "$DEST/x"` truncates the
# target BEFORE curl runs, so any network failure left a 3.3 MB committed
# dictionary empty. Everything now lands on a .part and moves into place only
# once complete and non-empty; a run that dies leaves the previous file as it
# was.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/core/speech/src/main/resources/kokoro"
CMUDICT_COMMIT="74790861f652b15e4ac49015a90074ad62a27690"
# Pinned like cmudict is. `main` can move under us silently, and the vocab is
# 114 entries the Kotlin loader hard-asserts on — an upstream edit would break
# the build with no diff to point at.
KOKORO_REV="f3ff3571791e39611d31c381e3a41a3af07b4987"
mkdir -p "$DEST"
trap 'rm -f "$DEST"/*.part' EXIT

curl -fsSL "https://raw.githubusercontent.com/cmusphinx/cmudict/$CMUDICT_COMMIT/cmudict.dict" |
  awk '
    /^;;;/ { next }                       # header comments
    {
      sub(/ #.*$/, "")                    # trailing per-entry comments
      if ($1 ~ /\([0-9]+\)$/) next        # alternate pronunciations: keep first only
      print
    }
  ' > "$DEST/cmudict.txt.part"
[ -s "$DEST/cmudict.txt.part" ] || { echo "cmudict fetch produced nothing" >&2; exit 1; }
mv -f "$DEST/cmudict.txt.part" "$DEST/cmudict.txt"

python3 - "$DEST/vocab.tsv" "$KOKORO_REV" <<'PY'
import json, os, sys, urllib.request
dst, rev = sys.argv[1], sys.argv[2]
url = f"https://huggingface.co/hexgrad/Kokoro-82M/raw/{rev}/config.json"
vocab = json.load(urllib.request.urlopen(url))["vocab"]
assert len(vocab) == 114, f"expected 114 vocab entries, got {len(vocab)}"
with open(dst + ".part", "w", encoding="utf-8") as f:
    for ch, i in sorted(vocab.items(), key=lambda kv: kv[1]):
        f.write(f"{i}\t{ch}\n")
os.replace(dst + ".part", dst)
print(f"vocab.tsv: {len(vocab)} entries")
PY

wc -l "$DEST/cmudict.txt" "$DEST/vocab.tsv"
