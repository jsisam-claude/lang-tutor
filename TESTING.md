# Device testing — quick start (Pixel 9 / 9a / 10 Pro XL)

Everything needed to run today's test, copy-paste ready. Full background:
[docs/running-on-device.md](docs/running-on-device.md).

## 0. One-command option: per-device sideload dirs

[`scripts/download-sideload.sh`](scripts/download-sideload.sh) downloads every
dependency, SHA-256-verified, into one directory per device — then each dir's
`push.sh` does the adb work:

```bash
scripts/download-sideload.sh            # sideload/pixel-9a, /pixel-9, /pixel-10-pro-xl
scripts/download-sideload.sh pixel-9a   # just one device
scripts/download-sideload.sh --apk      # also grab the latest CI APK (needs `gh` logged in)

cd sideload/pixel-9a && ./push.sh       # installs APK (if fetched) + pushes the model
```

Each dir contains the device's brain (9a → E2B 2.6 GB; 9 / 10 Pro XL → E4B
3.7 GB) plus `future-speech/` — the new native Whisper-ASR + Kokoro-TTS
artifacts (litert-community, published this week) staged for the next build
(the current APK doesn't read them yet; `push.sh` skips them). Manual steps
below if you prefer doing it by hand.

## 1. Get the APK

Latest green build on this branch (sign in to GitHub to download artifacts):

- **Runs:** <https://github.com/jsisam-claude/lang-tutor/actions?query=branch%3Aclaude%2Fon-device-language-tutor-m6lj1z>
- Open the **top green run** → scroll to **Artifacts** → download **app-debug** (~58 MB zip).

```bash
unzip app-debug.zip           # -> app-debug.apk (+ app-debug.aab)
adb install -r app-debug.apk  # -r upgrades in place
# if behavior looks stale, clean install:
#   adb uninstall org.sisam.langtutor && adb install app-debug.apk
```

## 2. Get the model onto the device (choose ONE)

The app detects RAM and offers the right tier: **9a (8 GB) → E2B**,
**9 (12 GB) / 10 Pro XL (16 GB) → E4B**.

### Option A — in-app (no adb)
App → **Parent Zone** (solve the math gate) → **חבילות שדרוג / Packs** →
tap the offered model → consent. Needs Wi-Fi + free space (2.6–3.7 GB).
- If it fails, the card shows the exact reason + **Retry** (resumes).
- On a certificate error (`SSLHandshakeException…`), a debug-only
  **"Ignore SSL & retry (testing)"** button appears — confirm the warning.
  The file is still SHA-256-verified.

### Option B — adb push
```bash
# Pixel 9a (E2B, 2.59 GB)
curl -L -o gemma-4-E2B-it.litertlm \
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
# expected sha256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c

# Pixel 9 / 10 Pro XL (E4B, 3.66 GB)
curl -L -o gemma-4-E4B-it.litertlm \
  "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
# expected sha256: 0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0

sha256sum gemma-4-*.litertlm   # verify before pushing

# Android 13+ blocks adb push into /sdcard/Android/data (scoped storage), so
# stage via /data/local/tmp and run-as-copy into the app's internal files dir
# (the first place the app looks). Works because this is a debug build.
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as org.sisam.langtutor mkdir -p files/models
adb shell "run-as org.sisam.langtutor cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/"
adb shell rm -f /data/local/tmp/gemma-4-E2B-it.litertlm
adb shell run-as org.sisam.langtutor ls -l files/models   # confirm it landed
# (or the E4B file — same steps; filenames must match exactly)
```

## 3. Run the test

1. Open **Talking with Tuki**; grant the microphone permission.
2. Check the badge under the title:
   - **🧠 On-device Tuki (Gemma 4)** → real model found. ✅
   - **🎬 Demo Tuki** → model file not found (see troubleshooting).
3. Wait out **"Waking Tuki up…"** — the first-ever load can take a while
   (unmeasured on hardware; give it a few minutes before judging).
4. Hold 🎙️, say a short English sentence ("I see a red ball"), release.
5. Expect: your words appear → Tuki thinks (streaming text) → Tuki speaks.

**Worth capturing while you test** (this is the decisive bench data):
```bash
adb logcat -v time | grep -iE "litert|accelerator|xnnpack|langtutor|tflite" | tee tuki-test.log
```
- Which backend actually loaded (GPU vs CPU lines in logcat)
- Rough seconds for first model load, and per-reply latency
- Reply text quality: duplicated text or role framing = known one-line fixes — screenshot it
- On 9a/9: any app kill / reload mid-session (memory pressure)

## 4. Troubleshooting

| Symptom | Likely cause / action |
|---|---|
| Badge stuck on **🎬 Demo Tuki** | Wrong path/filename. Check: `adb shell run-as org.sisam.langtutor ls -l files/models` (must show the exact `.litertlm` name, non-zero size) |
| `adb push` to `/sdcard/Android/data/...` denied | Expected on Android 13+ (scoped storage) — use the `/data/local/tmp` + `run-as` steps above |
| Import/download says checksum mismatch or incomplete | The copy is truncated (MTP drag-and-drop does this silently) or the download dropped. Verify on the computer: `sha256sum` must match TESTING.md's pin and E2B must be exactly 2,588,147,712 bytes; re-copy via cable and check the size in Files before importing |
| Download fails `SSLHandshakeException` | Network intercepts TLS (VPN/ad-blocker/WiFi filter). Use the debug **Ignore SSL** button, try mobile data, or use Option B |
| Download fails `Not enough storage` | Free up space; E4B needs ~3.9 GB headroom during install |
| No packs offered in Parent Zone | RAM detection issue — report device model + `adb shell cat /proc/meminfo \| head -1` |
| First reply never arrives / app killed | Memory pressure (most likely 9a with E2B, 9 with E4B). Note it — this decides the RAM gates |
| No speech recognized | `adb shell pm grant org.sisam.langtutor android.permission.RECORD_AUDIO`; device needs on-device recognition or the Google app |

## Mic on GrapheneOS (bundled Whisper)
The mic now uses OUR bundled Whisper ASR whenever a whisper tflite is present in
`files/models` — no Google services needed. `push.sh` installs it automatically
(9a: whisper-medium ~0.7 GB; 9/Pro XL: whisper-turbo ~0.8 GB). First
transcription loads the model (~10-30 s once); each utterance then takes several
seconds on CPU. Logcat tag `TukiAsr` shows mel/encode/decode timings.

## Known limits in this build (expected, not bugs)
- English speech only (bundled Whisper decodes with the English token set);
  Tuki's spoken voice still needs the Kokoro build (text replies meanwhile).
- Pronunciation scoring is a stub.
- Debug-signed test build — not Play-ready, not safety-certified for children yet.
