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
- A supported Pixel, Android 15/16. The app detects RAM (`ActivityManager`) and
  offers the model tier that fits:
  - **Pixel 9a (8 GB)** → *Base Tutor (E2B)* — English-focused; Hebrew limited.
  - **Pixel 9 (12 GB)** and **Pixel 10 Pro XL (16 GB)** → *Better Conversations
    (E4B)* — full quality, strongest Hebrew. (Pro XL also sees the experimental 8B.)
  - E4B on a 12 GB Pixel 9 is the borderline case to watch on-device (see caveats).
  - **Galaxy Tab S10 FE (8 GB)** → the *practice* flavor: no model at all, every
    practice room, speech models from the pack folder ([practice-flavor.md](practice-flavor.md)).
- `adb` connected; a machine with the Android SDK to build the APK, or just
  download CI's APK from the rolling `debug-latest` release (TESTING.md §1).

## 1. Build the app
CI already compiles both flavors (`:app:assembleFullDebug`,
`:app:assemblePracticeDebug`) + `:app:bundleFullDebug` on every push, so
any green run proves it builds. To get an installable APK, on a machine with the
Android SDK and normal network (this dev container's egress blocks `dl.google.com`,
so build locally or in CI, not here):

```bash
./gradlew :app:assembleFullDebug        # phones
adb install app/build/outputs/apk/full/debug/app-full-debug.apk
./gradlew :app:assemblePracticeDebug    # the tablet
adb install app/build/outputs/apk/practice/debug/app-practice-debug.apk
```

## 2. Put the model on the device

**Option A — in-app download (no adb).** Launch the app → **Parent Zone**
(solve the gate) → **Packs** → tap the model pack your device is offered
(*Better Conversations (E4B)* on a 12/16 GB Pixel; *Base Tutor (E2B)* on the 9a)
→ consent. The real downloader (`RealPackRepository`) streams the model from
Hugging Face straight to `filesDir/models/…` — exactly where the engine looks —
and verifies its SHA-256 before marking it installed. Needs network + ~2.6 GB
(E2B) or ~3.7 GB (E4B) free. Return to the conversation and the badge flips to
**🧠 On-device Tuki**.

**Option B — adb push (offline / dev).** Download the exact mobile artifact and
place it in the app's INTERNAL files dir. (Android 13+ scoped storage blocks
`adb push` into `/sdcard/Android/data/...`, so stage via `/data/local/tmp` and
`run-as` — this works because the build is debuggable, and internal files is
the first path `AppContainer` searches):

```bash
# Quality tier (12/16 GB devices) — 3.66 GB
huggingface-cli download litert-community/gemma-4-E4B-it-litert-lm \
    gemma-4-E4B-it.litertlm --local-dir .

adb push gemma-4-E4B-it.litertlm /data/local/tmp/
adb shell run-as org.sisam.langtutor mkdir -p files/models
adb shell "run-as org.sisam.langtutor cp /data/local/tmp/gemma-4-E4B-it.litertlm files/models/"
adb shell rm -f /data/local/tmp/gemma-4-E4B-it.litertlm
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
- **Memory is tight on the smaller devices.** E2B on the 9a (8 GB) and E4B on the
  Pixel 9 (12 GB) both run near the ceiling alongside the speech stack + OS; under
  pressure Android may kill the model process. Unmeasured — watch for reloads/OOM
  on those two. The 10 Pro XL (16 GB) has the most headroom. If E4B is unstable on
  the Pixel 9, the honest fallback is to bump its RAM gate back to 16.
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
