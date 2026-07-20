# Device Bench Protocol — the numbers we still owe ourselves

Goal: replace the two most load-bearing ⚠️ figures in
[feasibility.md](feasibility.md) with measurements from *your* Pixel. Budget:
one afternoon, no code required.

## 1. LLM decode speed (the number that decides the product feel)

**Tool**: Google **AI Edge Gallery** app (Play Store; runs LiteRT models with a
built-in tok/s readout), or any local-LLM runner app that reports speed.

1. Install AI Edge Gallery → download **Gemma 4 E2B (int4)** inside it
   (Wi-Fi; ~2.6 GB). If a Pixel-10 TPU-optimized variant is offered, test both.
2. Prompt it with a realistic tutor turn (~120 tokens of instruction + a short
   child utterance) and generate ~80-token replies. Record for **5 runs**:
   time-to-first-token, decode tok/s, total wall time.
3. Repeat on battery ≤30% and while warm (after 10 min of use) — thermals show
   up here.
4. If the device is 16 GB, repeat with **E4B** (~3.7 GB).

**What decides what**:
- decode ≥ 12 tok/s cold → the 2–4 s voice-turn budget holds → plan unchanged.
- 6–12 tok/s → budget becomes ~3–6 s → P1 leans harder on latency masking and
  shorter replies; still shippable.
- < 6 tok/s sustained → escalate: TPU path becomes mandatory (Pixel 10) or the
  base model drops a tier. Update feasibility §3/§8 either way.

## 2. Thermal / battery reality

While running step 1's warm loop, in a shell (`adb shell`):

```bash
# battery drain: note level before/after a 20-minute session
dumpsys battery | grep level
# thermal headroom (0.0 cool … 1.0 = severe throttle):
dumpsys thermalservice | grep -A3 "Thermal Status"
```

Record: battery % consumed per 20-min session, thermal status reached, and
whether tok/s degraded between run 1 and run 5. Targets: ≤6% battery/session,
no more than MODERATE thermal status, ≤25% decode degradation.

## 3. Platform-speech shim sanity (no models needed)

Install this repo's debug build (`./gradlew :app:assembleDebug`, or from
Android Studio). On the Conversation screen: grant mic → hold the button →
say "I see a red ball" → release. You should see your real words transcribed
(on-device SpeechRecognizer), a scripted reply, and hear it spoken (platform
TTS). That's the UX skeleton with real audio I/O — the bundled engines slot in
behind the same interfaces.

## 4. Report back

Paste the five numbers (TTFT, tok/s cold/warm, battery %, thermal status) into
feasibility.md §3/§8 replacing the ⚠️ estimates, with the device + build noted.
