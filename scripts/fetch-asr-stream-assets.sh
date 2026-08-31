#!/usr/bin/env bash
# Fetch the STREAMING ASR model — k2/icefall streaming Zipformer transducer
# (sherpa-onnx export, 2023-06-26, Apache-2.0) — into APK assets,
# SHA-256-pinned. Int8 encoder + fp32 decoder + int8 joiner ≈ 73 MB, chosen
# so the weights can ship INSIDE the app: true incremental decoding (real
# partials while the learner is still talking, near-zero end-of-turn ASR
# cost) must work with no download step and on a de-googled phone.
#
# Why this artifact:
# - Streaming by construction: the encoder takes chunk-16/left-128 cached
#   state tensors, so it decodes DURING capture — unlike Whisper's windowed
#   re-decode, which is why "heard so far" currently runs on speculative
#   full decodes (docs/latency.md).
# - Apache-2.0 (verified on the model page), so bundling in the APK is
#   clean; the int8 encoder keeps the whole trio at ~73 MB where the fp32
#   encoder alone is 262 MB — not bundleable.
# - Same runtime we already ship: ONNX Runtime drives Kokoro and the
#   pronunciation scorer; these exports carry explicit state tensors so no
#   sherpa-onnx native library is needed.
#
# NOT WIRED INTO CI YET — deliberately. The engine behind the experimental
# flag lands separately; until it reads these files, fetching them in CI
# would put 73 MB of dead weight in every debug APK. When the engine
# merges, add `- run: scripts/fetch-asr-stream-assets.sh` to android-ci.yml
# beside the other fetch steps, and the absent-asset rule applies as with
# the VAD: no asset, no streaming toggle, app otherwise unharmed.
#
# Whisper stays the judged-transcript engine; the streaming model feeds the
# live preview and endpointing until accuracy says otherwise.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/fetch.sh"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/asr-stream"
HF="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main"

mkdir -p "$DEST"
trap 'clean_partials "$DEST"' EXIT

fetch_verified "$HF/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" \
    "$DEST/encoder.int8.onnx" \
    "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1" \
    "streaming encoder (int8, 68 MB)"

# The decoder is tiny either way; fp32 sidesteps int8 embedding-lookup
# quantization loss for free (2 MB vs 1.3 MB).
fetch_verified "$HF/decoder-epoch-99-avg-1-chunk-16-left-128.onnx" \
    "$DEST/decoder.onnx" \
    "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef" \
    "streaming decoder (fp32, 2 MB)"

fetch_verified "$HF/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" \
    "$DEST/joiner.int8.onnx" \
    "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297" \
    "streaming joiner (int8, 0.3 MB)"

fetch_verified "$HF/tokens.txt" \
    "$DEST/tokens.txt" \
    "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb" \
    "BPE token table (500 pieces)"

echo "asr-stream assets ready in $DEST"
