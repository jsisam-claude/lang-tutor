#!/usr/bin/env bash
# Fetch Tuki's voice — the Kokoro af_heart style vectors — into APK assets,
# SHA-256-pinned. The 86 MB Kokoro model itself installs like the LLM (Parent
# Zone pack / import / sideload); this 510×256-float conditioning table is the
# only piece small enough to ship inside the APK, so a device that installs the
# model pack speaks immediately with no extra step.
#
# Source: onnx-community/Kokoro-82M-v1.0-ONNX voices/af_heart.bin (Apache-2.0).
# CI runs this before assembling; a local build without it still works — the
# engine only activates when both the model file AND this asset are present
# (asset missing → the app falls back to the platform TTS shim).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/kokoro"
NAME="af_heart.bin"
URL="https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/af_heart.bin"
SHA="d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b"

mkdir -p "$DEST"
out="$DEST/$NAME"
if [[ -f "$out" ]] && echo "$SHA  $out" | sha256sum -c --status 2>/dev/null; then
  echo "OK (cached)   $NAME"
  exit 0
fi
# --retry-all-errors: covers curl's HTTP/2 PROTOCOL_ERROR (exit 92), which
# plain --retry does not — see the same note in fetch-gpu-libs.sh.
curl -fsSL --retry 4 --retry-all-errors -o "$out.tmp" "$URL"
got="$(sha256sum "$out.tmp" | awk '{print $1}')"
if [[ "$got" != "$SHA" ]]; then
  rm -f "$out.tmp"
  echo "SHA-256 MISMATCH for $NAME: got $got, want $SHA" >&2
  exit 1
fi
mv "$out.tmp" "$out"
echo "OK (verified) $NAME -> $out"
