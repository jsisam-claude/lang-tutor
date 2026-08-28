#!/usr/bin/env bash
# Regenerate the committed Hebrew-TTS front-end data files in core/speech
# resources (both consumed by the pure-JVM Phonikud port):
#
#   phonikud/tokenizer-vocab.tsv — char→id vocab of the dicta char-level BERT
#     tokenizer ("id<TAB>char"), from Phonikud/phonikud tokenizer.json (MIT).
#     Specials: [UNK]=0 [CLS]=1 [SEP]=2 [PAD]=3 (encoded by name).
#
# Rerun only to refresh the pins; outputs are committed so builds and tests
# never need the network. Writes land on a .part and move into place only once
# complete, so a failed run cannot leave a committed file truncated.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/core/speech/src/main/resources/phonikud"
mkdir -p "$DEST"
trap 'rm -f "$DEST"/*.part' EXIT

python3 - "$DEST" <<'PY'
import json, sys, urllib.request

dest = sys.argv[1]

tok = json.load(urllib.request.urlopen(
    "https://huggingface.co/Phonikud/phonikud/raw/main/tokenizer.json"))
vocab = tok["model"]["vocab"]
# The char-level vocab includes literal tab/newline tokens — escape them so
# the TSV stays line-per-entry (the Kotlin loader unescapes).
def esc(ch):
    return ch.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
import os
with open(f"{dest}/tokenizer-vocab.tsv.part", "w", encoding="utf-8") as f:
    for ch, i in sorted(vocab.items(), key=lambda kv: kv[1]):
        f.write(f"{i}\t{esc(ch)}\n")
os.replace(f"{dest}/tokenizer-vocab.tsv.part", f"{dest}/tokenizer-vocab.tsv")
print(f"tokenizer-vocab.tsv: {len(vocab)} entries")

# NOTE: this script used to also emit phoneme-map.tsv, the Piper voice's
# phoneme_id_map. The Hebrew voice is Kokoro now, and Kokoro's Hebrew export
# shares the English vocabulary already committed at kokoro/vocab.tsv — so
# there is no second phoneme table to generate or keep in sync.
PY

# number-names.tsv — bare Hebrew number word → pointed form, from the phonikud
# source tree (MIT). Requires a phonikud checkout; skipped when absent so the
# other pins can still be refreshed alone.
PHONIKUD_SRC="${PHONIKUD_SRC:-}"
if [[ -n "$PHONIKUD_SRC" && -d "$PHONIKUD_SRC/phonikud/expander" ]]; then
  python3 - "$DEST/number-names.tsv" "$PHONIKUD_SRC" <<'PY'
import os, sys
sys.path.insert(0, sys.argv[2])
from phonikud.expander.number_names import NUMBER_NAMES
dst = sys.argv[1]
with open(dst + ".part", "w", encoding="utf-8") as f:
    for bare, pointed in NUMBER_NAMES.items():
        f.write(f"{bare}\t{pointed}\n")
os.replace(dst + ".part", dst)
print(f"number-names.tsv: {len(NUMBER_NAMES)} entries")
PY
else
  echo "PHONIKUD_SRC not set — skipping number-names.tsv refresh"
fi

# phoneme-map.tsv is NOT generated any more (see the note above). Naming it
# here made wc exit 1, failing the whole script after all its work succeeded.
wc -l "$DEST"/*.tsv
