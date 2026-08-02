#!/usr/bin/env bash
# Download everything needed to sideload the tutor onto each supported device,
# one directory per device type:
#
#   sideload/pixel-9a/         8 GB  -> Gemma 4 E2B (base brain)
#   sideload/pixel-9/         12 GB  -> Gemma 4 E4B (quality brain)
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
#   scripts/download-sideload.sh --apk           # also fetch the latest CI APK (needs `gh` logged in)
#
# Files are fetched once into sideload/_cache/, SHA-256-verified against the
# pinned hashes below, then hard-linked (or copied) into each device dir.
set -euo pipefail

HF=https://huggingface.co/litert-community
REPO_SLUG=jsisam-claude/lang-tutor
BRANCH=claude/on-device-language-tutor-m6lj1z
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
    model.onnx) echo "dfe0a8f33002654fa560c4cdb796d934b6aa84b3bfb16779646a5b0f1bd9d968" ;;
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
    # Hebrew voice (Phonikud stack): Piper VITS voice + nikud model.
    model.onnx) echo "https://huggingface.co/Phonikud/phonikud-tts-checkpoints/resolve/main/model.onnx" ;;
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

model_for() { # brain the CURRENT app loads per device
  case "$1" in
    pixel-9a) echo "gemma-4-E2B-it.litertlm" ;;
    pixel-9|pixel-10-pro-xl) echo "gemma-4-E4B-it.litertlm" ;;
  esac
}

whisper_for() { # the recommended ASR — same short-window model on every device
  case "$1" in
    *) echo "acft_whisper_small.en_10s.tflite" ;;
  esac
}

write_push_sh() { # write_push_sh <devdir> <model>
  cat > "$1/push.sh" <<PUSH
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
echo ">> pushing model ($2) via /data/local/tmp (staging)"
adb push "$2" /data/local/tmp/"$2"
echo ">> moving into the app's internal files dir (run-as, debug builds only)"
adb shell run-as "\$PKG" mkdir -p files/models
adb shell "run-as \$PKG cp /data/local/tmp/'$2' files/models/'$2'"
adb shell rm -f /data/local/tmp/"$2"
adb shell run-as "\$PKG" ls -l files/models
for W in speech/whisper_*.tflite speech/acft_whisper_*.tflite speech/model_q8f16.onnx speech/model.onnx speech/phonikud-1.0.int8.onnx speech/wav2vec2-phoneme-int8.onnx; do
  [ -f "\$W" ] || continue
  WB=\$(basename "\$W")
  echo ">> pushing bundled speech model (\$WB) — works without Google services"
  adb push "\$W" /data/local/tmp/"\$WB"
  adb shell "run-as \$PKG cp /data/local/tmp/'\$WB' files/models/'\$WB'"
  adb shell rm -f /data/local/tmp/"\$WB"
done
echo ">> done. Open the app — badge: On-device Tuki; mic = bundled Whisper; voices = Kokoro (EN) + Phonikud (HE)."
PUSH
  chmod +x "$1/push.sh"
}

fetch_apk() { # latest green CI artifact via gh (optional)
  command -v gh >/dev/null || { echo "!! --apk needs the GitHub CLI (gh) logged in; skipping"; return 0; }
  local run
  run=$(gh run list -R "$REPO_SLUG" --branch "$BRANCH" --workflow android-ci \
        --status success --limit 1 --json databaseId -q '.[0].databaseId') || true
  [ -z "${run:-}" ] && { echo "!! no green run found; skipping APK"; return 0; }
  echo ">> fetching APK artifact from run $run"
  gh run download "$run" -R "$REPO_SLUG" -n app-debug --dir "$CACHE/apk"
}

# ------------------------------- main ----------------------------------------
[ "$WITH_APK" = 1 ] && fetch_apk

for dev in "${DEVICES[@]}"; do
  devdir="$OUT/$dev"
  model=$(model_for "$dev")
  echo "== $dev -> $devdir"
  place "$(fetch "$model")" "$devdir"
  if [ "$WITH_SPEECH" = 1 ]; then
    for f in "$(whisper_for "$dev")" model_q8f16.onnx model.onnx phonikud-1.0.int8.onnx wav2vec2-phoneme-int8.onnx; do
      place "$(fetch "$f")" "$devdir/speech"
    done
    cat > "$devdir/speech/README.txt" <<'NOTE'
Bundled speech models, pushed into files/models by push.sh:
- acft_whisper_*.tflite    — Tuki's ears: short-window ASR, no Google services
- model_q8f16.onnx         — Tuki's English voice: Kokoro TTS (24 kHz)
- model.onnx               — Tuki's Hebrew voice: Piper VITS (22.05 kHz)
- phonikud-1.0.int8.onnx   — Hebrew nikud model (vowel points for the voice)
- wav2vec2-phoneme-int8.onnx — pronunciation coach (per-sound scoring)
All are read by the current APK as soon as they are in place.
NOTE
  fi
  if [ -f "$CACHE/apk/app-debug.apk" ]; then place "$CACHE/apk/app-debug.apk" "$devdir"; fi
  write_push_sh "$devdir" "$model"
done

echo
echo "All set. Per-device: cd $OUT/<device> && ./push.sh"
echo "APK (if not fetched with --apk): https://github.com/$REPO_SLUG/actions?query=branch%3A${BRANCH//\//%2F} -> top green run -> Artifacts -> app-debug"
