#!/usr/bin/env bash
# Fetch Tuki's voices — the Kokoro style vectors — into APK assets,
# SHA-256-pinned.
#
# A Kokoro "voice" is NOT a model: it is a 510x256 float conditioning table
# (exactly 522,240 bytes, identical for every voice). The one 86 MB model
# synthesizes all of them, so carrying the whole English set costs ~14 MB of
# APK and buys a voice picker — cheap enough that shipping one voice and making
# people re-download to change it would be the odd choice.
#
# ENGLISH ONLY, deliberately: af_/am_ are American, bf_/bm_ are British. The
# upstream repo also has Spanish, French, Italian, Portuguese, Japanese,
# Mandarin and Hindi tables, none of which this tutor speaks. (hf_/hm_ is
# HINDI, not Hebrew — it does not help the Hebrew TTS gap.)
#
# Source: onnx-community/Kokoro-82M-v1.0-ONNX voices/*.bin (Apache-2.0).
# CI runs this before assembling; a local build without it still works — the
# engine only activates when both the model file AND its voice are present
# (voice missing -> the app falls back to the platform TTS shim).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$REPO_ROOT/app/src/main/assets/kokoro"
BASE="https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices"

# name|sha256
VOICES=(
  "af_alloy.bin|c4a6b876047fd7fb472edf4ebd63cfac7c3b958a7cae7c106e8f038ca6308c45"
  "af_aoede.bin|4a004c33430762e2461eedb2013fad808ef4ab3121f5300f554476caf58d8361"
  "af_bella.bin|f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b"
  "af_heart.bin|d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b"
  "af_jessica.bin|a240a5e3c15b43563d6e923bdca8ef5613a23471d9b77653694012435df23bd8"
  "af_kore.bin|9be5221b6a941c04b561959b8ff0b06e809444dcc4ab7e75a7b23606f691819e"
  "af_nicole.bin|cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a"
  "af_nova.bin|18778272caa0d0eebaea251c35fd635f038434f9eee5e691d02a174bd328414f"
  "af_river.bin|00a2bcf82b1d86e8f19902ede58c65ccf6c0e43b44b7d74fad54e5d8933c9c30"
  "af_sarah.bin|4409fbc125afabacc615d94db5398d847006a737b0247d6892b7a9a0007a2f0a"
  "af_sky.bin|4435255c9744f3f31659e0d714ab7689bf65d9e77ec1cce060f083912614f0b9"
  "am_adam.bin|162b035ed91cfc48b6046982184c645f72edcdd1b82843347f605d7bf7b15716"
  "am_echo.bin|3968b92c3c4cd1c4416dbded36c13eaa388a90d5788d02a13e4d781f5f8cf3c3"
  "am_eric.bin|e8b5be17edd1e3636901ce7598baafe2dc8dd8ff707a0c23bf9e461add7e2832"
  "am_fenrir.bin|c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43"
  "am_liam.bin|52403be32fd047c6a44517cb0bcd6b134f2a18baa73e70ef41651e0eab921ade"
  "am_michael.bin|1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1"
  "am_onyx.bin|da5d135b424164916d75a68ffb4c2abce3d7d5ccc82dd1ee6cf447ce286145e6"
  "am_puck.bin|fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0"
  "am_santa.bin|61150cf726ab6c5ed7a99f90a304f91f5a72c00c592e89ec94e5df11c319227a"
  "bf_alice.bin|08afa6ba24da61ea5e8efa139e5aadc938d83f0a6da5a900adaf763ac1da5573"
  "bf_emma.bin|669fe0647f9dd04fcab92f1439a40eeb4c8b4ab1f82e4996fe3d918ce4a63b73"
  "bf_isabella.bin|3754352c4aaa46d17f27654ab7518d65b62ad6163a0f55a5f4330c2da2c4e94f"
  "bf_lily.bin|5e0ee32ebe64a467124976b14e69590746f1c4ce41a12b587a50c862edfea335"
  "bm_daniel.bin|6b3194bbceffb746733cbc22c8f593dd44e401a71d53895a2dca891bc595a1e8"
  "bm_fable.bin|f889083196807b4adb15e9204252165f503b8d33d3982e681c52443c49d798f1"
  "bm_george.bin|c4b235a4c1f2cd3b939fed08b899ce9385638b763f7b73a59616c4fc9bd6c9bc"
  "bm_lewis.bin|b8f671cef828c30e66fdf0b0756a76bba58f6bb3398cbbf27058642acbcedb97"
)

mkdir -p "$DEST"
fetched=0
cached=0
for entry in "${VOICES[@]}"; do
  name="${entry%%|*}"
  sha="${entry##*|}"
  out="$DEST/$name"
  if [[ -f "$out" ]] && echo "$sha  $out" | sha256sum -c --status 2>/dev/null; then
    cached=$((cached + 1))
    continue
  fi
  # --retry-all-errors: covers curl's HTTP/2 PROTOCOL_ERROR (exit 92), which
  # plain --retry does not — see the same note in fetch-gpu-libs.sh.
  curl -fsSL --retry 4 --retry-all-errors -o "$out.tmp" "$BASE/$name"
  got="$(sha256sum "$out.tmp" | awk '{print $1}')"
  if [[ "$got" != "$sha" ]]; then
    rm -f "$out.tmp"
    echo "!! $name sha256 mismatch: expected $sha, got $got" >&2
    exit 1
  fi
  mv "$out.tmp" "$out"
  fetched=$((fetched + 1))
done
echo "Kokoro voices ready in $DEST ($fetched fetched, $cached cached, ${#VOICES[@]} total)"
