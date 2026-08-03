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
3.7 GB) plus `speech/` — everything Tuki needs to hear and speak: the Whisper
ASR (286 MB), the Kokoro English voice (86 MB), the Hebrew voice + nikud model
(371 MB) and the pronunciation coach (318 MB). All of them are WIRED: `push.sh`
installs them into `files/models` and the current APK reads them, with no
Google services involved. Manual steps below if you prefer doing it by hand.

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

## Seeing what the app is doing (new)
Every model here loads lazily and some are hundreds of megabytes, so the first
mic press or first Hebrew line used to sit silent for tens of seconds. The
screens now show the step in progress — "Getting Tuki's ears ready…", "Waking
Tuki up…" — with a seconds counter once it passes 2 s, and debug builds print
the technical detail under it (`ASR_LOAD · acft_whisper_small.en_10s.tflite`).

The same steps go to logcat under tag **`TukiStep`**, entry and exit with
elapsed milliseconds:

```bash
adb logcat -v time -s TukiStep:I TukiLlm:I TukiAsr:I TukiTts:I TukiTtsHe:I TukiVad:I TukiGop:I
```
```
▶ LLM_LOAD gemma-4-E2B-it.litertlm on gpu
✖ LLM_LOAD gemma-4-E2B-it.litertlm on gpu failed after 4210ms: …
▶ LLM_LOAD gemma-4-E2B-it.litertlm on cpu
✔ LLM_LOAD gemma-4-E2B-it.litertlm on cpu in 38104ms
▶ ASR_LOAD acft_whisper_small.en_10s.tflite
✔ ASR_LOAD acft_whisper_small.en_10s.tflite in 9820ms
▶ ASR_RUN
✔ ASR_RUN in 612ms
```

**This is the bench data.** One `TukiStep` capture from a real session answers
most of the open questions at once: which backend won and what the failed
attempt cost, first-load times per model, and per-turn ASR/TTS latency. If you
send back one file from the device test, send this one.

## GPU generation (bundled WebGPU sampler)
CI builds now pack Google's prebuilt WebGPU sampler libraries
(`libLiteRtTopKWebGpuSampler.so` + shared Dawn, LiteRT-LM v0.14.0,
SHA-256-pinned by `scripts/fetch-gpu-libs.sh`) into the APK — the exact
libraries whose absence forced CPU decode on GrapheneOS. What to look for in
logcat (`TukiLlm` tag) on first session start:
- `loaded … backend=GPU … (smoke ok)` → GPU decode works; note tok/s vs CPU.
- `initialize failed on GPU` then `backend=CPU` → sampler still unusable; the
  engine remembers (`.cpu-hint` next to the model) and skips the GPU attempt on
  later launches, so only the FIRST session after an install/update pays it.
- App dies during "Waking Tuki up…" → native crash in the GPU attempt. Relaunch:
  the crash-loop guard pins CPU for this build (`previous GPU attempt crashed`
  in logcat) and the app works as before. Please send the crash line
  (`adb logcat -b crash -d`).

## Mic on GrapheneOS (bundled Whisper)
The mic uses OUR bundled Whisper ASR whenever a whisper tflite is present in
`files/models` — no Google services needed. `push.sh` installs it automatically:
**every device now gets the same 286 MB short-window model**
(`acft_whisper_small.en_10s.tflite`), which replaced the 664 MB 30-second
export. It measured ~12× faster at the same accuracy on child-length phrases —
sub-second instead of several seconds per utterance — because it encodes a
10-second window instead of padding a two-second answer out to 30. Full A/B:
[docs/asr-model-eval.md](docs/asr-model-eval.md).

First transcription loads the model (~10–30 s once); Logcat tag `TukiAsr` prints
the graph it detected (`graph: window 10s mel[1,80,1000] … layout ENGLISH`) and
then mel/encode/decode timings per utterance. **Please send those timings** —
the speed claim above comes from a container CPU, not from a Tensor.

If you already pushed a 30-second model, it still works: the engine reads window
size, encoder shape and token layout from the model's own signatures, so both
generations run unchanged. Delete the old file to reclaim the 664 MB.

Two things worth watching, both from the eval:
- **Same phrase, different transcript on a retry.** These graphs are
  dynamic-range quantized and the thread count changes the arithmetic order;
  in-container, transcripts got worse when threads matched the core count.
  Thread count is now 4 (was 6). If you see wobble between identical attempts,
  say so — that number should drop, not rise.
- **Foreign characters mid-sentence** (`I see a red 군`). That is the 30-second
  padding drift; the 10 s model should not do it at all. If it does, that is a
  new finding worth reporting.

## Tuki's voice (bundled Kokoro TTS)
Tuki speaks with OUR bundled Kokoro voice whenever `models/model_q8f16.onnx`
(86 MB) is on device — no Google services, no system voices (GrapheneOS has
neither, so previously replies were silent there). Install it like the LLM:
Parent Zone → Packs → "Tuki's Voice (Kokoro)", or import/share the file, or
`push.sh` (re-run `scripts/download-sideload.sh` first to fetch it). The voice
style itself (af_heart) ships inside the APK. Logcat tag `TukiTts` shows
session load time and per-sentence synth ms vs seconds of audio — that ratio
(RTF) on your Pixel is bench data worth sending. Names outside the dictionary
(Noa, Yael…) use approximate letter-to-sound rules — report any that sound
wrong.

## Tuki speaks Hebrew (bundled Phonikud TTS)
Hebrew speech works when BOTH Hebrew packs are installed (Parent Zone →
"Hebrew for Tuki" parts 1+2, or `push.sh`, which installs them): the nikud
model (~308 MB) adds vowel points on-device, a rules engine (golden-tested
against the reference) turns them into phonemes, and a Piper voice speaks at
22.05 kHz. Any tutor line containing Hebrew letters routes to the Hebrew
voice automatically; English lines keep using Kokoro. Logcat tag `TukiTtsHe`
shows session-load and per-sentence synth times (measured RTF ≈ 0.09 on a
single container CPU thread — realtime with headroom). Type a Hebrew sentence
in the chat to hear it. Known limits: digits in Hebrew text are not spoken
(no number expander yet), and mixed Hebrew-English sentences go entirely to
the Hebrew voice.

## Hands-free mic (bundled Silero VAD)
The mic model ships INSIDE the APK (639 KB), so when a Whisper model is
installed the conversation screen shows a **Hands-free** switch. On: tap the
mic once and just talk — the detector ends the turn after ~0.7 s of quiet (a
kid-friendly pause; short blips like a door slam are ignored, and a silent room
gives the turn back after 10 s). Off: the button stays hold-to-talk. The mic
button turns red while listening. Logcat tag `TukiAsr` prints
`endpoint: SILENCE frames a..b` when the VAD closes a turn; `TukiVad` shows the
model load. Measured in-container: 0.45 ms per 32 ms frame (~70× realtime) and
64 ms onset accuracy, so it should be free next to the ASR — worth confirming
on the 9a.

## Pronunciation coach (per-sound scoring)
Install **Parent Zone → Packs → "Pronunciation Coach"** (318 MB). After a
SPOKEN attempt at a lesson phrase, the screen shows each expected sound in
green / amber / red plus a star count. Typed turns and free conversation are
never scored. Logcat tag `TukiGop` prints the per-sound numbers
(`ɹ=-7.4` style: 0 means the model fully agreed, very negative means it heard
something else).

What to try: say the lesson phrase correctly, then deliberately mispronounce
one sound the way a Hebrew-speaking child would — "wed" for "red", "sink" for
"think", "wery" for "very". In container testing those substitutions scored
−4.9 to −7.4 while correct sounds scored 0.00. **Please report the numbers you
get**: the thresholds were calibrated on synthesized speech, and real children
in real rooms are the calibration that matters. Vowel-quality errors (æ vs ɛ)
did not separate in testing — treat vowel marks as advisory for now.

## Known limits in this build (expected, not bugs)
- **English speech recognition only.** The bundled model is an English-only
  export, so a Hebrew answer into the mic will come back as English-looking
  nonsense. Hebrew works for typing and for Tuki's voice, not for listening.
- A turn is capped at 30 seconds. Past the model's 10-second window the audio is
  split at the quietest gap and transcribed piece by piece (`utterance split
  into N windows` in logcat), so long answers survive — but each piece is
  decoded independently, so a sentence spanning a cut can read oddly.
- Names outside the voice's dictionary use letter-to-sound rules, and digits
  inside Hebrew text are not spoken yet.
- Pronunciation thresholds were calibrated on synthesized speech (see the
  section above) — the numbers you report are what recalibrates them.
- Debug-signed test build — not Play-ready, not safety-certified for children yet.
