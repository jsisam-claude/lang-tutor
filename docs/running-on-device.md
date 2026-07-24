# Running the first version on a Pixel

This is the honest bring-up guide for the first end-to-end run. The app builds
into a real APK/AAB in CI; this gets it onto a device and talking. **It has not
been run on hardware yet** — expect first-device surprises (see *Honest caveats*).

## What runs
The full voice loop with **real engines**:

```
you speak → PlatformAsrEngine (Android on-device SpeechRecognizer)
          → LiteRtLmEngine (Gemma 4 via LiteRT-LM)
          → PlatformTtsEngine (Android TextToSpeech) → you hear Tuki
```

The LLM is real **only when a `.litertlm` model file is on the device**; without
one the app runs the scripted demo engine and the Conversation screen shows
`🎬 Demo Tuki`. With the model present it shows `🧠 On-device Tuki (Gemma 4)`.

## Prerequisites
- Pixel 9/10 (or Pro/Pro XL), Android 16. **16 GB RAM for E4B** (quality tier),
  12 GB is fine for E2B (base tier).
- `adb` connected; a machine with the Android SDK to build the APK.

## 1. Build the app
CI already compiles `:app:assembleDebug` + `:app:bundleDebug` on every push, so
any green run proves it builds. To get an installable APK, on a machine with the
Android SDK and normal network (this dev container's egress blocks `dl.google.com`,
so build locally or in CI, not here):

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 2. Put the model on the device

**Option A — in-app download (no adb).** Launch the app → **Parent Zone**
(solve the gate) → **Packs** → **Better Conversations (E4B)** → consent. The real
downloader (`RealPackRepository`) streams the model from Hugging Face straight to
`filesDir/models/gemma-4-E4B-it.litertlm` — exactly where the engine looks — and
verifies its SHA-256 before marking it installed. Needs network + ~3.7 GB free.
Return to the conversation and the badge flips to **🧠 On-device Tuki**.

**Option B — adb push (offline / dev).** Download the exact mobile artifact and
push it to the app's external files dir, the other path `AppContainer` searches:

```bash
# Quality tier (recommended, 16 GB devices) — 3.66 GB
huggingface-cli download litert-community/gemma-4-E4B-it-litert-lm \
    gemma-4-E4B-it.litertlm --local-dir .

adb shell mkdir -p /sdcard/Android/data/org.sisam.langtutor/files/models
adb push gemma-4-E4B-it.litertlm \
    /sdcard/Android/data/org.sisam.langtutor/files/models/
```

For the base tier instead, use `litert-community/gemma-4-E2B-it-litert-lm`
(`gemma-4-E2B-it.litertlm`, 2.59 GB). The filename must match exactly — the app
looks for `models/gemma-4-E4B-it.litertlm` then `models/gemma-4-E2B-it.litertlm`,
under both the app files dir and the external files dir above.

## 3. Run it
1. Launch the app; open **Talking with Tuki**.
2. Grant the microphone permission when asked.
3. Confirm the badge reads **🧠 On-device Tuki (Gemma 4)**. If it says
   **🎬 Demo Tuki**, the model file wasn't found — recheck the path/filename in step 2.
4. Wait for **"Waking Tuki up…"** to clear (first load can be slow — see caveats),
   then hold the 🎙️ button, say a short English sentence, and release.

## Honest caveats (read before judging the result)
- **First-load latency is unmeasured.** On this project's weak CI CPU, loading
  E4B took ~5 minutes (with an XNNPack weight-cache rebuild). A Pixel with the
  Tensor backend should be *far* faster, but the first load after install may
  still be notable — that's what the "Waking Tuki up…" state is for. Measure it
  (docs/bench.md) before assuming it's broken.
- **Three engine assumptions only a device confirms** (all flagged in
  `LiteRtLmEngine.kt`):
  1. `Message.toString()` yields clean reply text — if replies show role framing
     or JSON, that's this (one-line fix).
  2. streamed messages are token *deltas* — if text visibly duplicates, they're
     cumulative (one-line fix).
  3. `Backend.GPU()` binds on Tensor — if it doesn't, it silently falls back to
     CPU (slower/hotter, still works). Watch logcat for the backend actually used.
- **English works; Hebrew input does not (yet).** `PlatformAsrEngine` is hardcoded
  to `en-US` and is adult-tuned (kids' speech has higher error rates). Hebrew
  *output* depends on the device having a Hebrew TTS voice installed.
- **Pronunciation scoring is a stub** (`FakePronunciationScorer`) — that feature
  won't give real feedback until the in-house CTC-GOP model is built.
- **Not safety-certified.** The output filter is a basic blocklist; real
  COPPA/Play-Families review + red-teaming is required before any child uses it.
- Model delivery here is a manual `adb push` for dev bring-up. A shippable build
  needs the install-time asset pack or a real in-app downloader (the pack repo is
  still a fake) — and E4B's 3.66 GB vs Play's per-device limits is an open
  packaging decision.

## If something breaks
- Badge stuck on **Demo Tuki** → wrong path/filename, or `getExternalFilesDir`
  differs; `adb shell run-as org.sisam.langtutor ls files/models` to check.
- App loads then errors on first turn → likely the `toString()` extraction;
  capture the reply text from logcat and adjust `LiteRtLmEngine.generate`.
- No speech recognized → device may lack on-device recognition; the engine falls
  back to `createSpeechRecognizer` (needs Google app), or grant RECORD_AUDIO via
  `adb shell pm grant org.sisam.langtutor android.permission.RECORD_AUDIO`.
