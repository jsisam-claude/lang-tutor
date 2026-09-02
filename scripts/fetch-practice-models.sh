#!/usr/bin/env bash
# Fetch the three speech models the PRACTICE flavor ships INSIDE its APK
# (docs/practice-flavor.md): Tuki's ears (Whisper, short-window ACFT export),
# Tuki's voice (Kokoro) and the pronunciation coach (wav2vec2 phoneme CTC,
# int8). ~700 MB, SHA-256-pinned — the same pins scripts/download-sideload.sh
# uses for the phones' sideload folders, so both routes install byte-identical
# files.
#
# Writes to app/src/practice/assets/models/ (gitignored): only the practice
# APK packages them, the full flavor keeps getting them as packs. The app
# copies them out of the APK into files/models on first launch, so every
# engine keeps loading from a path. Without this script the practice APK
# still builds; it just needs a pack folder like a phone does.
#
# Usage: scripts/fetch-practice-models.sh   (run from anywhere; CI runs it
# before assembling)
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/fetch.sh"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/practice/assets/models"
HF=https://huggingface.co

# dest name|url|sha256 — names are the catalog's install names (core/packs
# catalog.json), which is what the engines look for in files/models.
MODELS=(
  "acft_whisper_small.en_10s.tflite|$HF/litert-community/whisper-acft/resolve/main/small.en/acft_whisper_small.en_10s_drq.tflite|58edc288e8aad1da2a3df0545edadf5f1c6119ff70682e37031119ad89130daf"
  "model_quantized.onnx|$HF/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx|fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478"
  "wav2vec2-phoneme-int8.onnx|$HF/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main/onnx/model_int8.onnx|74174710e34035bbb7f611601d016c32fc575de7a6f53b1078107dc10a84e7ae"
)

mkdir -p "$DEST"
trap 'clean_partials "$DEST"' EXIT

for entry in "${MODELS[@]}"; do
  IFS='|' read -r name url sha <<<"$entry"
  fetch_verified "$url" "$DEST/$name" "$sha" "$name"
done

# Prune anything this list no longer pins: the directory is owned entirely by
# this script, and a leftover here is not inert — it ships in the APK.
for f in "$DEST"/*; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  keep=0
  for entry in "${MODELS[@]}"; do [ "${entry%%|*}" = "$base" ] && keep=1 && break; done
  if [ "$keep" = 0 ]; then
    echo "   pruned   $base (no longer pinned; would otherwise ship in the APK)"
    rm -f "$f"
  fi
done
echo "practice models ready in $DEST"
