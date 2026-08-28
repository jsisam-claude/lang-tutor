#!/usr/bin/env bash
# Fetch the hands-free listening model — Silero VAD v6.2.1 (MIT), fp32 ONNX —
# into APK assets, SHA-256-pinned. At 2.3 MB it ships INSIDE the app: hands-free
# turn-taking needs no download and works on a de-googled phone out of the box.
#
# v6 over v5, measured (real speech + noise, in-container): in-speech
# confidence 0.62 vs 0.41 (0.60 vs 0.30 in noise — v5 sat under the gate's
# exit threshold, which is the "cuts the child off mid-word" failure), 7x
# lower false-trigger response to a click, same speed. Upstream additionally
# claims v6.2 improvements on CHILD voices specifically.
#
# CONTRACT CHANGE vs v5: the official v6 export expects a 64-sample rolling
# context PREPENDED to each 512-sample frame (input [1,576]). SileroVad.kt
# maintains that context; feeding a bare 512 frame returns ~0.0 on speech.
#
# CI runs this before assembling. A build without it still works — the mic just
# stays push-to-talk (the toggle is hidden when the asset is absent).
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/fetch.sh"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/vad"
NAME="silero_vad.onnx"
URL="https://raw.githubusercontent.com/snakers4/silero-vad/v6.2.1/src/silero_vad/data/silero_vad.onnx"
SHA="1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"

mkdir -p "$DEST"
trap 'clean_partials "$DEST"' EXIT
fetch_verified "$URL" "$DEST/$NAME" "$SHA" "$NAME"
