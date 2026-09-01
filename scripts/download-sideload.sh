#!/usr/bin/env bash
# Download everything needed to sideload the tutor onto each supported device,
# one directory per device type:
#
#   sideload/pixel-9a/         8 GB  -> Gemma 4 E2B (base brain)
#   sideload/pixel-9/         12 GB  -> Gemma 4 E4B + E2B (per-session pick)
#   sideload/pixel-10-pro-xl/ 16 GB  -> Gemma 4 E4B (quality brain)
#   sideload/tab-s10-fe/       8 GB  -> NO brain: the practice flavor, speech only
#                                       (docs/practice-flavor.md)
#
# Each device dir gets: the model the CURRENT app loads, a generated push.sh
# (adb commands for that device), and speech/ — the bundled Whisper ASR (Tuki's
# ears) and the Kokoro ONNX voice (Tuki's mouth), both WIRED: push.sh installs
# them into files/models and the app uses them with no Google services.
#
# Usage:
#   scripts/download-sideload.sh                 # all three devices
#   scripts/download-sideload.sh pixel-9a        # one device
#   scripts/download-sideload.sh tab-s10-fe      # the tablet: speech models only
#   scripts/download-sideload.sh --no-speech     # models only
#   scripts/download-sideload.sh --ci-apk        # use CI's APK instead of your build
#   scripts/download-sideload.sh --hebrew        # ALSO fetch the Hebrew voice
#
# --hebrew adds ~630 MB per device and pulls NON-COMMERCIAL weights: the Hebrew
# Kokoro voice is trained on SASPEECH ((c) IPBC), licensed "non-commercial
# purposes only - not for commercial or broadcast needs". Fine for a free,
# non-monetised build; must be left out of anything sold or ad-supported.
# docs/feasibility.md section 6 has the full licence chain.
#
# Files are fetched once into sideload/_cache/, SHA-256-verified against the
# pinned hashes below, then hard-linked (or copied) into each device dir. A
# file already present with the right hash is never re-downloaded; the rules
# live in scripts/lib/fetch.sh.
#
# THE APK IS YOUR LOCAL BUILD. app/build/outputs/apk/debug/app-debug.apk is
# placed automatically whenever it exists — no flag needed. Pass --ci-apk to
# take the rolling debug-latest release instead. It used to be the other way
# round, and the old shape could swap builds under you: the placement was not
# gated on the flag at all, so once ANY earlier run had cached a CI APK, every
# later run copied it into the device dir and over the top of a local build you
# had just put there.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/fetch.sh"

HF=https://huggingface.co/litert-community
REPO_SLUG=jsisam-claude/lang-tutor
OUT=sideload
WITH_SPEECH=1
USE_CI_APK=0
DEVICES=()

for arg in "$@"; do
  case "$arg" in
    pixel-9a|pixel-9|pixel-10-pro-xl|tab-s10-fe) DEVICES+=("$arg") ;;
    --no-speech) WITH_SPEECH=0 ;;
    --hebrew) WITH_HEBREW=1 ;;
    --ci-apk) USE_CI_APK=1 ;;
    --apk) echo "note: --apk is now the default (your local build); use --ci-apk for CI's" >&2 ;;
    --out=*) OUT="${arg#--out=}" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $arg (see --help)"; exit 1 ;;
  esac
done
[ ${#DEVICES[@]} -eq 0 ] && DEVICES=(pixel-9a pixel-9 pixel-10-pro-xl tab-s10-fe)

WITH_HEBREW=${WITH_HEBREW:-0}

CACHE="$OUT/_cache"
mkdir -p "$CACHE"
# The one script that was missing this, and the one where it matters most:
# a ^C partway through a 3.66 GB model leaves the largest partial in the
# tree. Recurses, so $CACHE/apk is covered too. (A concurrent run of this
# same script could have its in-flight .part swept; downloading the same
# cache twice at once is not a supported thing to do.)
trap 'clean_partials "$CACHE"' EXIT

# ---- pinned artifacts: name | url-path | sha256 (empty = size-check only) ----
sha_of() {
  case "$1" in
    gemma-4-E2B-it.litertlm) echo "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c" ;;
    gemma-4-E4B-it.litertlm) echo "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0" ;;
    whisper_large_v3_turbo_30s_i4.tflite) echo "da3c91fcd149174cbb5abd3a5583ea95982c5e401c2d68cabac89117f5ce1a4c" ;;
    whisper_medium_30s_i4.tflite) echo "4d5a521109aa64383bcb99d1f1951316bce024a916f89683c95579db4f5ffa63" ;;
    acft_whisper_small.en_10s.tflite) echo "58edc288e8aad1da2a3df0545edadf5f1c6119ff70682e37031119ad89130daf" ;;
    model_quantized.onnx) echo "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478" ;;
    # ---- Hebrew voice: opt-in with --hebrew, NON-COMMERCIAL weights ----
    # Three pieces with three different licences; read docs/feasibility.md
    # section 6 before shipping any build that contains them.
    #   phonikud-1.0.int8.onnx  MIT                    (the nikud model)
    #   kokoro-hebrew.onnx      saspeech-noncommercial (the voice weights)
    #   voices-hebrew.bin       saspeech-noncommercial (the he_shaul table)
    phonikud-1.0.int8.onnx) echo "113afb58d3140502aa1e7691cdc6b240b56cf97e5852fc870e1a7fb5a400dd62" ;;
    kokoro-hebrew.onnx) echo "6b9b16fbba87fec17a21cd3b11203286e1e657014dbab236703fdf0cf13a9a2f" ;;
    voices-hebrew.bin) echo "5efaa05741532c144a535677f02e02fd72c76fd8ec45840d6e3194b38194e9a7" ;;
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
    model_quantized.onnx) echo "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx" ;;
    # Hebrew voice (see the license block in sha_of above).
    phonikud-1.0.int8.onnx) echo "https://huggingface.co/Phonikud/phonikud-onnx/resolve/main/phonikud-1.0.int8.onnx" ;;
    kokoro-hebrew.onnx) echo "https://huggingface.co/thewh1teagle/kokoro-hebrew-nc/resolve/main/kokoro.onnx" ;;
    voices-hebrew.bin) echo "https://huggingface.co/thewh1teagle/kokoro-hebrew-nc/resolve/main/voices-hebrew.bin" ;;
    # Pronunciation coach: IPA phoneme CTC model behind the GOP scorer.
    wav2vec2-phoneme-int8.onnx) echo "https://huggingface.co/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main/onnx/model_int8.onnx" ;;
    *) echo ""; return 1 ;;
  esac
}

fetch() { # fetch <name> -> cached path on stdout, non-zero on any failure
  local name="$1" url sha f="$CACHE/$1"
  url=$(url_of "$name"); sha=$(sha_of "$name")
  # An unrecorded pin used to warn and continue, which made "no hash" mean
  # "anything on disk is fine". These are multi-GB weights that get pushed to
  # a child's phone; refuse instead.
  case "$sha" in ""|__*__) echo "!! $name: no pinned SHA-256 — refusing" >&2; return 1;; esac
  fetch_verified "$url" "$f" "$sha" "$name" || return 1
  echo "$f"
}

# fetch_into <name> <destdir> — the only correct way to call fetch().
#
# `place "$(fetch X)" dir` looks right and is not: fetch() runs in a command
# substitution, so a failure there exits the SUBSHELL. Verified: the old code
# printed its mismatch error, deleted the file, then called place() with an
# EMPTY path, continued, and exited 0 — a device dir silently missing its
# model under an "All set" banner.
fetch_into() {
  local f
  f=$(fetch "$1") || { echo "!! aborting: could not obtain $1" >&2; exit 1; }
  place "$f" "$2"
}

# The Hebrew voice ships as an .npz holding one he_shaul.npy of shape
# (510, 1, 256) float32. The app reads conditioning tables as RAW little-endian
# floats — exactly what every English voice already is — so unwrap it here
# rather than teach the device about numpy containers. The ARCHIVE is the thing
# under a SHA-256 pin; this step is a deterministic function of it.
extract_hebrew_voice() { # extract_hebrew_voice <archive> <out.bin>
  # Deterministic function of a hash-pinned archive, so a correct output is
  # reusable: 510*256*4 bytes is the only shape that can be right. Skipping
  # also stops the per-device loop rewriting a file it has already hard-linked
  # into an earlier device dir.
  if [ -f "$2" ] && [ "$(wc -c < "$2")" -eq 522240 ]; then
    echo "   cached   $(basename "$2")" >&2
    return 0
  fi
  python3 - "$1" "$2" <<'PYX'
import sys, zipfile, struct
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    name = next(n for n in z.namelist() if n.endswith(".npy"))
    raw = z.read(name)
assert raw[:6] == b"\x93NUMPY", "not a .npy payload"
header_len = struct.unpack("<H", raw[8:10])[0]
body = raw[10 + header_len:]
expected = 510 * 256 * 4
assert len(body) == expected, f"{name}: got {len(body)} bytes, expected {expected}"
# Atomic: a killed interpreter must not leave a short file that the size
# check above would then have to catch on the next run.
import os
open(dst + ".part", "wb").write(body)
os.replace(dst + ".part", dst)
print(f">> extracted {name} -> {dst} ({len(body)} bytes)", file=sys.stderr)
PYX
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
    # The tablet runs the practice flavor: no language model at all.
    tab-s10-fe) echo "" ;;
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
for A in app-full-debug.apk app-practice-debug.apk; do
  [ -f "\$A" ] || continue
  echo ">> installing APK (\$A)"; adb install -r "\$A"
done
adb shell run-as "\$PKG" mkdir -p files/models
for M in $models; do
  echo ">> pushing brain (\$M) via /data/local/tmp (staging)"
  adb push "\$M" /data/local/tmp/"\$M"
  adb shell "run-as \$PKG cp /data/local/tmp/'\$M' files/models/'\$M'"
  adb shell rm -f /data/local/tmp/"\$M"
done
adb shell run-as "\$PKG" ls -l files/models
for W in speech/whisper_*.tflite speech/acft_whisper_*.tflite speech/model_quantized.onnx speech/wav2vec2-phoneme-int8.onnx speech/phonikud-1.0.int8.onnx speech/kokoro-hebrew.onnx speech/he_shaul.bin; do
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

# Which flavor each device runs (docs/practice-flavor.md): the phones get
# `full` (conversation + practice), the tablet gets `practice` (no brain, no
# model runtime). The asset names are what CI publishes to debug-latest.
apk_name_for() {
  case "$1" in
    tab-s10-fe) echo "app-practice-debug.apk" ;;
    *) echo "app-full-debug.apk" ;;
  esac
}
local_apk_for() {
  case "$1" in
    tab-s10-fe) echo "app/build/outputs/apk/practice/debug/app-practice-debug.apk" ;;
    *) echo "app/build/outputs/apk/full/debug/app-full-debug.apk" ;;
  esac
}

fetch_ci_apk() { # fetch_ci_apk <asset>: only with --ci-apk, the rolling debug-latest release
  local name="$1"
  mkdir -p "$CACHE/apk"
  local url="https://github.com/$REPO_SLUG/releases/download/debug-latest/$name"
  # No pin is possible — the release asset moves with every green build — so
  # this one writes to .part and moves only on success. That is the same
  # atomicity the pinned artifacts get; it just cannot also verify content.
  if curl -fsSL --retry 4 --retry-all-errors -o "$CACHE/apk/$name.part" "$url"; then
    mv -f "$CACHE/apk/$name.part" "$CACHE/apk/$name"
    echo ">> APK: fetched CI's debug-latest release ($name)" >&2
    return 0
  fi
  rm -f "$CACHE/apk/$name.part"
  if command -v gh >/dev/null && gh release download debug-latest -R "$REPO_SLUG" \
       -p "$name" --clobber --dir "$CACHE/apk.part" 2>/dev/null; then
    mv -f "$CACHE/apk.part/$name" "$CACHE/apk/$name"
    rmdir "$CACHE/apk.part" 2>/dev/null || true
    echo ">> APK: fetched CI's debug-latest release ($name, via gh)" >&2
    return 0
  fi
  rm -rf "$CACHE/apk.part"
  echo "!! APK: could not fetch $name from debug-latest; skipping" >&2
  return 1
}

# apk_to_place <device> -> path on stdout, or empty. Your build wins unless
# you ask for CI's. Nothing is placed implicitly from the cache: the old code
# placed whatever happened to be in $CACHE/apk regardless of any flag, which is
# how a stale CI build could overwrite the APK you had just compiled.
apk_to_place() {
  local name; name="$(apk_name_for "$1")"
  if [ "$USE_CI_APK" = 1 ]; then
    fetch_ci_apk "$name" >/dev/null 2>&1 || fetch_ci_apk "$name" || return 0
    [ -f "$CACHE/apk/$name" ] && echo "$CACHE/apk/$name"
    return 0
  fi
  local built; built="$(local_apk_for "$1")"
  [ -f "$built" ] && echo "$built"
  return 0
}

# ------------------------------- main ----------------------------------------
PLACED_ANY=0
for dev in "${DEVICES[@]}"; do
  devdir="$OUT/$dev"
  models=$(models_for "$dev")
  APK_SRC="$(apk_to_place "$dev")"
  if [ -n "$APK_SRC" ]; then
    PLACED_ANY=1
    if [ "$USE_CI_APK" = 1 ]; then
      echo "== $dev APK: CI debug-latest ($APK_SRC)"
    else
      echo "== $dev APK: your local build ($APK_SRC, built $(date -r "$APK_SRC" '+%Y-%m-%d %H:%M' 2>/dev/null || echo 'unknown'))"
    fi
  else
    echo "== $dev APK: none — build $(apk_name_for "$dev") first, or pass --ci-apk"
  fi
  echo "== $dev -> $devdir"
  for m in $models; do
    fetch_into "$m" "$devdir"
  done
  if [ "$WITH_SPEECH" = 1 ]; then
    for f in "$(whisper_for "$dev")" model_quantized.onnx wav2vec2-phoneme-int8.onnx; do
      fetch_into "$f" "$devdir/speech"
    done
    if [ "$WITH_HEBREW" = 1 ]; then
      fetch_into phonikud-1.0.int8.onnx "$devdir/speech"
      fetch_into kokoro-hebrew.onnx "$devdir/speech"
      heb=$(fetch voices-hebrew.bin) || { echo "!! aborting: voices-hebrew.bin" >&2; exit 1; }
      extract_hebrew_voice "$heb" "$CACHE/he_shaul.bin"
      place "$CACHE/he_shaul.bin" "$devdir/speech"
    fi
    cat > "$devdir/speech/README.txt" <<'NOTE'
Bundled speech models, pushed into files/models by push.sh:
- acft_whisper_*.tflite    — Tuki's ears: short-window ASR, no Google services
- model_quantized.onnx     — Tuki's English voice: Kokoro TTS (24 kHz)
- wav2vec2-phoneme-int8.onnx — pronunciation coach (per-sound scoring)
Hebrew voice (only with --hebrew; NON-COMMERCIAL weights, see below):
- phonikud-1.0.int8.onnx   — nikud restoration (MIT)
- kokoro-hebrew.onnx       — Hebrew Kokoro voice (saspeech-noncommercial)
- he_shaul.bin             — its one conditioning table (saspeech-noncommercial)
All are read by the current APK as soon as they are in place.

LICENSE: the Hebrew VOICE weights are trained on SASPEECH ((c) Israeli Public
Broadcasting Corporation) and are licensed for NON-COMMERCIAL use only, "not
for commercial or broadcast needs". They are fine in a free, non-monetised
app and must be removed from any build that is sold, ad-supported, or
otherwise commercial. docs/feasibility.md section 6 has the full chain.
NOTE
  fi
  [ -n "$APK_SRC" ] && place "$APK_SRC" "$devdir"
  # shellcheck disable=SC2086 # word-splitting the model list is intended
  write_push_sh "$devdir" $models
done

echo
echo "All set. Per-device: cd $OUT/<device> && ./push.sh"
if [ "$PLACED_ANY" = 0 ]; then
  echo "No APK placed. ./gradlew :app:assembleFullDebug (phones) / :app:assemblePracticeDebug (tablet), then re-run — or --ci-apk for CI's."
fi
