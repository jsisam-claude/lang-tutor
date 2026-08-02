#!/usr/bin/env bash
# Fetch the hands-free listening model — Silero VAD v5 (MIT), int8 ONNX — into
# APK assets, SHA-256-pinned. At 639 KB it ships INSIDE the app: hands-free
# turn-taking needs no download and works on a de-googled phone out of the box.
#
# CI runs this before assembling. A build without it still works — the mic just
# stays push-to-talk (the toggle is hidden when the asset is absent).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/vad"
NAME="silero_vad.onnx"
URL="https://huggingface.co/onnx-community/silero-vad/resolve/main/onnx/model_int8.onnx"
SHA="90b026c95f054d59d7bf79387b0ed93c8950f35a4d8b741cd78d4bb23a7d2776"

mkdir -p "$DEST"
out="$DEST/$NAME"
if [[ -f "$out" ]] && echo "$SHA  $out" | sha256sum -c --status 2>/dev/null; then
  echo "OK (cached)   $NAME"
  exit 0
fi
# --retry-all-errors also covers curl's HTTP/2 PROTOCOL_ERROR (exit 92).
curl -fsSL --retry 4 --retry-all-errors -o "$out.tmp" "$URL"
got="$(sha256sum "$out.tmp" | awk '{print $1}')"
if [[ "$got" != "$SHA" ]]; then
  rm -f "$out.tmp"
  echo "SHA-256 MISMATCH for $NAME: got $got, want $SHA" >&2
  exit 1
fi
mv "$out.tmp" "$out"
echo "OK (verified) $NAME -> $out"
