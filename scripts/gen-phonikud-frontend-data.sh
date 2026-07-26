#!/usr/bin/env bash
# Regenerate the committed Hebrew-TTS front-end data files in core/speech
# resources (both consumed by the pure-JVM Phonikud port):
#
#   phonikud/tokenizer-vocab.tsv — char→id vocab of the dicta char-level BERT
#     tokenizer ("id<TAB>char"), from Phonikud/phonikud tokenizer.json (MIT).
#     Specials: [UNK]=0 [CLS]=1 [SEP]=2 [PAD]=3 (encoded by name).
#   phonikud/phoneme-map.tsv — Piper phoneme_id_map of the Hebrew voice
#     ("id<TAB>char"), from Phonikud/phonikud-tts-checkpoints
#     model.config.json (^=1 BOS, $=2 EOS, _=0 PAD, phoneme_type "raw").
#
# Rerun only to refresh the pins; outputs are committed so builds and tests
# never need the network.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/core/speech/src/main/resources/phonikud"
mkdir -p "$DEST"

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
with open(f"{dest}/tokenizer-vocab.tsv", "w", encoding="utf-8") as f:
    for ch, i in sorted(vocab.items(), key=lambda kv: kv[1]):
        f.write(f"{i}\t{esc(ch)}\n")
print(f"tokenizer-vocab.tsv: {len(vocab)} entries")

cfg = json.load(urllib.request.urlopen(
    "https://huggingface.co/Phonikud/phonikud-tts-checkpoints/raw/main/model.config.json"))
id_map = cfg["phoneme_id_map"]
with open(f"{dest}/phoneme-map.tsv", "w", encoding="utf-8") as f:
    for ch, ids in sorted(id_map.items(), key=lambda kv: kv[1][0]):
        f.write(f"{ids[0]}\t{ch}\n")
print(f"phoneme-map.tsv: {len(id_map)} entries; "
      f"sr={cfg['audio']['sample_rate']} inference={cfg['inference']}")
PY

wc -l "$DEST/tokenizer-vocab.tsv" "$DEST/phoneme-map.tsv"
