#!/usr/bin/env bash
# Download everything needed to sideload the tutor onto each supported device,
# one directory per device type:
#
#   sideload/pixel-9a/         8 GB  -> Gemma 4 E2B (base brain)
#   sideload/pixel-9/         12 GB  -> Gemma 4 E4B + E2B (per-session pick)
#   sideload/pixel-10-pro-xl/ 16 GB  -> Gemma 4 E4B (quality brain)
#
# Each device dir gets: the model the CURRENT app loads, a generated push.sh
# (adb commands for that device), and speech/ — the bundled Whisper ASR (Tuki's
# ears) and the Kokoro ONNX voice (Tuki's mouth), both WIRED: push.sh installs
# them into files/models and the app uses them with no Google services.
#
# Usage:
#   scripts/download-sideload.sh                 # all three devices
#   scripts/download-sideload.sh pixel-9a        # one device
#   scripts/download-sideload.sh --no-speech     # models only
#   scripts/download-sideload.sh --apk           # also fetch the newest green CI APK
#
# Files are fetched once into sideload/_cache/, SHA-256-verified against the
# pinned hashes below, then hard-linked (or copied) into each device dir.
set -euo pipefail

HF=https://huggingface.co/litert-community
REPO_SLUG=jsisam-claude/lang-tutor
OUT=sideload
WITH_SPEECH=1
WITH_APK=0
DEVICES=()

for arg in "$@"; do
  case "$arg" in
    pixel-9a|pixel-9|pixel-10-pro-xl) DEVICES+=("$arg") ;;
    --no-speech) WITH_SPEECH=0 ;;
    --apk) WITH_APK=1 ;;
    --out=*) OUT="${arg#--out=}" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $arg (see --help)"; exit 1 ;;
  esac
done
[ ${#DEVICES[@]} -eq 0 ] && DEVICES=(pixel-9a pixel-9 pixel-10-pro-xl)

CACHE="$OUT/_cache"
mkdir -p "$CACHE"

# ---- pinned artifacts: name | url-path | sha256 (empty = size-check only) ----
sha_of() {
  case "$1" in
    gemma-4-E2B-it.litertlm) echo "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c" ;;
    gemma-4-E4B-it.litertlm) echo "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0" ;;
    whisper_large_v3_turbo_30s_i4.tflite) echo "da3c91fcd149174cbb5abd3a5583ea95982c5e401c2d68cabac89117f5ce1a4c" ;;
    whisper_medium_30s_i4.tflite) echo "4d5a521109aa64383bcb99d1f1951316bce024a916f89683c95579db4f5ffa63" ;;
    acft_whisper_small.en_10s.tflite) echo "58edc288e8aad1da2a3df0545edadf5f1c6119ff70682e37031119ad89130daf" ;;
    model_q8f16.onnx) echo "04c658aec1b6008857c2ad10f8c589d4180d0ec427e7e6118ceb487e215c3cd0" ;;
    # Hebrew nikud model (MIT) — pin kept for private testing, but NOT fetched by
    # default: the Piper Hebrew VOICE it fed turned out to be CC-BY-NC with an
    # academic-only rider (docs/feasibility.md) and was removed from the app.
    phonikud-1.0.int8.onnx) echo "113afb58d3140502aa1e7691cdc6b240b56cf97e5852fc870e1a7fb5a400dd62" ;;
    wav2vec2-phoneme-int8.onnx) echo "74174710e34035bbb7f611601d016c32fc575de7a6f53b1078107dc10a84e7ae" ;;
    *) echo "" ;;
  esac
}

url_of() {
  case "$1" in
    gemma-4-E2B-it.litertlm) echo "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm" ;;
    gemma-4-E4B-it.litertlm) echo "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm" ;;
    whisper_large_v3_turbo_30s_i4.tflite) echo "$HF/whisper-large-v3-turbo/resolve/main/whisper_large_v3_turbo_30s_i4.tflite" ;;
    whisper_medium_30s_i4.tflite) echo "$HF/whisper-medium/resolve/main/whisper_medium_30s_i4.tflite" ;;
    # Short-window ACFT export — the recommended ASR (see docs/asr-model-eval.md).
    acft_whisper_small.en_10s.tflite) echo "$HF/whisper-acft/resolve/main/small.en/acft_whisper_small.en_10s_drq.tflite" ;;
    # Kokoro voice model — single-graph ONNX build (q8f16), spoken by the app's
    # bundled ONNX Runtime engine.
    model_q8f16.onnx) echo "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_q8f16.onnx" ;;
    # Hebrew nikud model (see the license note in sha_of above).
    phonikud-1.0.int8.onnx) echo "https://huggingface.co/Phonikud/phonikud-onnx/resolve/main/phonikud-1.0.int8.onnx" ;;
    # Pronunciation coach: IPA phoneme CTC model behind the GOP scorer.
    wav2vec2-phoneme-int8.onnx) echo "https://huggingface.co/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main/onnx/model_int8.onnx" ;;
    *) echo ""; return 1 ;;
  esac
}

sha_check() { # sha_check <file> <expected>
  local got
  got=$( (sha256sum "$1" 2>/dev/null || shasum -a 256 "$1") | awk '{print $1}')
  [ "$got" = "$2" ]
}

fetch() { # fetch <name> -> cached path on stdout
  local name="$1" url sha f="$CACHE/$1"
  url=$(url_of "$name"); sha=$(sha_of "$name")
  # A __PLACEHOLDER__ means the pin hasn't been recorded yet: warn, don't block.
  case "$sha" in __*__) echo "!! $name: no pinned SHA-256 yet — integrity NOT verified" >&2; sha="";; esac
  if [ -f "$f" ] && { [ -z "$sha" ] || sha_check "$f" "$sha"; }; then
    echo "$f"; return 0
  fi
  echo ">> downloading $name" >&2
  curl -L --fail --retry 4 --retry-all-errors -C - -o "$f" "$url" >&2
  if [ -n "$sha" ] && ! sha_check "$f" "$sha"; then
    echo "!! SHA-256 MISMATCH for $name — deleting; re-run to retry" >&2
    rm -f "$f"; exit 1
  fi
  echo "$f"
}

place() { # place <cached> <destdir>
  mkdir -p "$2"
  ln -f "$1" "$2/$(basename "$1")" 2>/dev/null || cp -f "$1" "$2/$(basename "$1")"
}

models_for() { # brains the CURRENT app loads per device (space-separated)
  case "$1" in
    pixel-9a) echo "gemma-4-E2B-it.litertlm" ;;
    # The 12 GB Pixel 9 gets BOTH: the app picks E4B or E2B per session from
    # free memory (TESTING.md "Pixel 9"), so the fallback must be installed.
    pixel-9) echo "gemma-4-E4B-it.litertlm gemma-4-E2B-it.litertlm" ;;
    pixel-10-pro-xl) echo "gemma-4-E4B-it.litertlm" ;;
  esac
}

whisper_for() { # the recommended ASR — same short-window model on every device
  case "$1" in
    *) echo "acft_whisper_small.en_10s.tflite" ;;
  esac
}

write_push_sh() { # write_push_sh <devdir> <models...>
  local devdir="$1"; shift
  local models="$*"
  cat > "$devdir/push.sh" <<PUSH
#!/usr/bin/env bash
# Push this device's dependencies to the phone (and install the APK if present).
#
# Modern Android (13+) blocks adb push into /sdcard/Android/data (scoped
# storage), so the reliable route for this debug build is: push to
# /data/local/tmp, then run-as-copy into the app's INTERNAL files dir —
# which is also the first place the app looks for the model.
set -euo pipefail
cd "\$(dirname "\$0")"
PKG=org.sisam.langtutor
adb get-state >/dev/null # fails fast if no device
if ls app-debug.apk >/dev/null 2>&1; then
  echo ">> installing APK"; adb install -r app-debug.apk
fi
adb shell run-as "\$PKG" mkdir -p files/models
for M in $models; do
  echo ">> pushing brain (\$M) via /data/local/tmp (staging)"
  adb push "\$M" /data/local/tmp/"\$M"
  adb shell "run-as \$PKG cp /data/local/tmp/'\$M' files/models/'\$M'"
  adb shell rm -f /data/local/tmp/"\$M"
done
adb shell run-as "\$PKG" ls -l files/models
for W in speech/whisper_*.tflite speech/acft_whisper_*.tflite speech/model_q8f16.onnx speech/wav2vec2-phoneme-int8.onnx; do
  [ -f "\$W" ] || continue
  WB=\$(basename "\$W")
  echo ">> pushing bundled speech model (\$WB) — works without Google services"
  adb push "\$W" /data/local/tmp/"\$WB"
  adb shell "run-as \$PKG cp /data/local/tmp/'\$WB' files/models/'\$WB'"
  adb shell rm -f /data/local/tmp/"\$WB"
done
echo ">> done. Open the app — badge: On-device Tuki; mic = bundled Whisper; voice = Kokoro (EN)."
PUSH
  chmod +x "$devdir/push.sh"
}

fetch_apk() { # newest green APK from the rolling debug-latest release
  mkdir -p "$CACHE/apk"
  # CI replaces this release's asset on every green build, and the repo is
  # public, so a plain curl works with no GitHub CLI and no login. (There is
  # deliberately no per-run artifact to fall back to: the workflow publishes
  # only the release — see .github/workflows/android-ci.yml.)
  local url="https://github.com/$REPO_SLUG/releases/download/debug-latest/app-debug.apk"
  if curl -fsSL --retry 4 --retry-all-errors -o "$CACHE/apk/app-debug.apk.tmp" "$url"; then
    mv "$CACHE/apk/app-debug.apk.tmp" "$CACHE/apk/app-debug.apk"
    echo ">> fetched APK from the debug-latest release"
    return 0
  fi
  rm -f "$CACHE/apk/app-debug.apk.tmp"
  # Private-repo (or pre-release) case: fall back to the authenticated CLI.
  if command -v gh >/dev/null && gh release download debug-latest -R "$REPO_SLUG" \
       -p app-debug.apk --clobber --dir "$CACHE/apk" 2>/dev/null; then
    echo ">> fetched APK from the debug-latest release (via gh)"
    return 0
  fi
  echo "!! could not fetch the debug-latest APK; skipping (build one with :app:assembleDebug)"
  return 0
}

# ------------------------------- main ----------------------------------------
[ "$WITH_APK" = 1 ] && fetch_apk

for dev in "${DEVICES[@]}"; do
  devdir="$OUT/$dev"
  models=$(models_for "$dev")
  echo "== $dev -> $devdir"
  for m in $models; do
    place "$(fetch "$m")" "$devdir"
  done
  if [ "$WITH_SPEECH" = 1 ]; then
    for f in "$(whisper_for "$dev")" model_q8f16.onnx wav2vec2-phoneme-int8.onnx; do
      place "$(fetch "$f")" "$devdir/speech"
    done
    cat > "$devdir/speech/README.txt" <<'NOTE'
Bundled speech models, pushed into files/models by push.sh:
- acft_whisper_*.tflite    — Tuki's ears: short-window ASR, no Google services
- model_q8f16.onnx         — Tuki's English voice: Kokoro TTS (24 kHz)
- wav2vec2-phoneme-int8.onnx — pronunciation coach (per-sound scoring)
(The Hebrew voice was removed: its license is CC-BY-NC + academic-only.)
All are read by the current APK as soon as they are in place.
NOTE
  fi
  if [ -f "$CACHE/apk/app-debug.apk" ]; then place "$CACHE/apk/app-debug.apk" "$devdir"; fi
  # shellcheck disable=SC2086 # word-splitting the model list is intended
  write_push_sh "$devdir" $models
done

echo
echo "All set. Per-device: cd $OUT/<device> && ./push.sh"
echo "APK (if not fetched with --apk): https://github.com/$REPO_SLUG/releases/tag/debug-latest"
