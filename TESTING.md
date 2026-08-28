# Device testing — quick start (Pixel 9 / 9a / 10 Pro XL)

Everything needed to run today's test, copy-paste ready. Full background:
[docs/running-on-device.md](docs/running-on-device.md).

## 0. One-command option: per-device sideload dirs

[`scripts/download-sideload.sh`](scripts/download-sideload.sh) downloads every
dependency, SHA-256-verified, into one directory per device — then each dir's
`push.sh` does the adb work. Anything already present with the right hash is
not re-downloaded, so re-running is free.

**The APK is your local build.** `app/build/outputs/apk/debug/app-debug.apk`
is placed automatically whenever it exists, so the usual loop is
`./gradlew :app:assembleDebug` then re-run this. Pass `--ci-apk` for CI's.

```bash
scripts/download-sideload.sh            # sideload/pixel-9a, /pixel-9, /pixel-10-pro-xl
scripts/download-sideload.sh pixel-9a   # just one device
scripts/download-sideload.sh --ci-apk   # take CI's APK instead of your own build

cd sideload/pixel-9a && ./push.sh       # installs the APK (if any) + pushes the model
```

Each dir contains the device's brain(s) — 9a → E2B 2.6 GB; **9 → E4B *and*
E2B** (the app picks per session by free memory, see the Pixel 9 section);
10 Pro XL → E4B — plus `speech/` — everything Tuki needs to hear and speak: the Whisper
ASR (286 MB), the Kokoro English voice (86 MB) and the pronunciation coach
(318 MB). (The Hebrew voice is no longer fetched — see the Hebrew section
below for why.) All of them are WIRED: `push.sh`
installs them into `files/models` and the current APK reads them, with no
Google services involved. Manual steps below if you prefer doing it by hand.

## 1. Get the APK

**One stable link** — every green build replaces the APK on the rolling
pre-release (check the release notes for the commit + build time):

- <https://github.com/jsisam-claude/lang-tutor/releases/tag/debug-latest>

```bash
# browser: download app-debug.apk from the link above, or with gh:
gh release download debug-latest -R jsisam-claude/lang-tutor -p app-debug.apk --clobber
adb install -r app-debug.apk  # -r upgrades in place
# if behavior looks stale, clean install:
#   adb uninstall org.sisam.langtutor && adb install app-debug.apk
```

(The per-run **app-debug** artifact still exists when Actions storage quota
allows, under the top green run at
<https://github.com/jsisam-claude/lang-tutor/actions?query=branch%3Aclaude%2Fon-device-language-tutor-m6lj1z> —
but the release link above is the reliable path.)

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
| Download fails `SSLHandshakeException` | A **user-installed** CA is intercepting TLS (VPN/ad-blocker/WiFi filter); the app trusts system CAs only. Use the debug **Ignore SSL** button, try mobile data, or sideload/import the models — the Parent Zone importers work in release builds and verify the same SHA-256. A CA in the **system** store is trusted and causes no error at all (docs/building-on-debian.md §TLS) |
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

**First real Pixel 9 numbers (2026-08-27, GPU backend):** whole turns
0.9–2.2 s, time-to-first-token ≈ 270 ms warm / ≈ 700 ms first turn, decode
~12–14 est-tok/s — against 8.2 s whole turns on CPU before the GPU fix. If
your `turn done` lines sit far from these, send them. And if Tuki ever says a
mangled number ("1.0,293" for "1.0293"), report it immediately — a known
Mali GPU precision issue shows exactly that signature, and it decides whether
we pin CPU for numbers.

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

## Pixel 9 (12 GB) — what this tier tests (new)
The Pixel 9 gets the E4B quality brain, and it is the device with the least
room for it: E4B's working set plus the speech stack fits a fresh boot but not
always a phone that has been running apps all day. Two behaviors are new and
worth exercising **on the 9 specifically**:

1. **Memory-aware model pick — once per launch.** With BOTH models installed
   (install the E2B pack alongside E4B), the app picks a tier ONCE, at launch,
   from free memory measured before its own model loads — per-session
   re-picking was removed after it measured the memory our own preloaded E4B
   had just occupied and "downgraded" to E2B, discarding the warm model. The
   badge shows the result — `Gemma 4 · E4B` or `· E2B` — and logcat tag
   `TukiLlm` prints `model_pick: … (sticky for this process)` with the reason.
   To force the fallback: open a heavy game and a dozen Chrome tabs BEFORE
   launching Tuki. To force E4B: reboot, open Tuki first. The 4.5 / 3.0 GB
   bars are container-era estimates — **if your Pixel runs E4B happily below
   the bar, or dies above it, send that logcat line; it recalibrates the
   bars.**
2. **Engines release under pressure.** Logcat tag `TukiMem` prints
   `trim level=N -> released ...` when Android signals pressure; the released
   engine reloads visibly (status line + `TukiStep`) on next use. The failure
   this prevents: app killed mid-conversation after using the pronunciation
   coach and the voice in the same session. If the app still gets killed,
   capture `adb shell dumpsys meminfo org.sisam.langtutor` right before — that
   plus `TukiMem` decides whether the LLM itself must join the trim list.

3. **Per-turn prefill reuse.** Turn 1 logs `convo rebuild: prefilling N
   messages`; every later turn in the same lesson should log
   `convo reuse: prefilling 1 message instead of N` — that reuse is what keeps
   Tuki's thinking time flat as the conversation grows instead of climbing
   every turn. If rebuilds appear mid-session, send the `TukiLlm` lines around
   them. (This change also fixes a real bug: the lesson guidance the policy
   writes — "the child is practicing X, praise and recast" — was silently
   dropped before it ever reached the model. Replies should now feel more
   lesson-aware; that difference is worth noting too.)

Also Pixel-9-relevant: ASR threads are set to 4 (half the cores) — on the
Tensor G4's 4×A520+3×A720+1×X4 layout, watch `TukiAsr` for transcript wobble
between identical attempts (docs/asr-model-eval.md explains why fewer can be
more accurate).

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
Tuki speaks with OUR bundled Kokoro voice whenever `models/model_quantized.onnx`
(86 MB) is on device — no Google services, no system voices (GrapheneOS has
neither, so previously replies were silent there). Install it like the LLM:
Parent Zone → Packs → "Tuki's Voice (Kokoro)", or import/share the file, or
`push.sh` (re-run `scripts/download-sideload.sh` first to fetch it). The voice
style itself (af_heart) ships inside the APK. Logcat tag `TukiTts` shows
session load time and per-sentence synth ms vs seconds of audio — that ratio
(RTF) on your Pixel is bench data worth sending. First device numbers: about
1.7-3.4 s to synthesize 1.4-3.5 s of audio on a Pixel 9, i.e. RTF near 1.0 —
close to real time, which streaming hides for all but the first sentence.

The line also prints the waveform's shape (`peak`/`rms`/`zcr`) against
reference values. That exists because the previously shipped export returned an
all-NaN waveform on ARM while being clean on x86, which reached the speaker as
a burst of noise; `zcr` near 0.5 or `peak=0.000` means the model is at fault,
not the audio path.

**New: Tuki starts talking at the first sentence.** Replies used to wait for
the model to finish the WHOLE answer before any audio; now the first finished
sentence goes to the voice while the rest is still being written (watch the
reply text keep growing on screen after speech starts). Worth timing on
device: the gap from the child finishing to Tuki's first word is the number
that this change shrinks — please report it before/after if you have an older
APK around. Names outside the dictionary
(Noa, Yael…) use approximate letter-to-sound rules — report any that sound
wrong.

## Tuki speaks Hebrew (new — opt-in download)

Tuki now has a Hebrew voice: the same Kokoro engine as English, with the
Phonikud front end and a Hebrew-trained export. It is **not in the APK** and
**not fetched by default** — its weights are non-commercial (see below).

```bash
scripts/download-sideload.sh pixel-9 --hebrew   # +~630 MB
cd sideload/pixel-9 && ./push.sh
```

That installs three files into `files/models/`: `phonikud-1.0.int8.onnx` (nikud
restoration, MIT), `kokoro-hebrew.onnx` (the voice), and `he_shaul.bin` (its one
conditioning table). The app picks them up with no restart — `hasHebrewTts`
flips as soon as all three are present.

What should change once they are in place:

- A reply that **mixes** Hebrew and English now speaks BOTH, taking turns
  between the two voices. Before, the Hebrew was stripped and only the English
  was spoken. This is the normal shape of a Hebrew-help reply, so it is the
  fastest way to check the voice works at all.
- The **הסבר בעברית** button appears for a **4–6 unit** too (units 001–006),
  which it never did before: written Hebrew is useless to a child who cannot
  read it, but *spoken* Hebrew is exactly what they need. The E4B tier gate
  still applies.
- Logcat tag `TukiTtsHe` prints the nikud load and per-line synthesis, in the
  same `synth N tokens -> Xs in Yms peak/rms/zcr` shape as the English voice —
  so the same sanity check applies. Silence with a plausible duration, or a
  `zcr` near 0.5, means a bad waveform, not a bad voice.

Worth reporting: whether the Hebrew is **intelligible and correctly stressed**,
not merely audible. The nikud model is guessing vowels, and a wrong guess is a
real word pronounced as a different real word.

Two known rough edges, both expected:

- The voice is **Shaul Amsterdamski, an adult male news journalist**. It is
  right for an adult learner and wrong for a parrot talking to a five-year-old.
  That is a recording problem, not a bug.
- The export is **fp32, 325 MB** (the English voice is int8 at 92 MB), so the
  first Hebrew line pays a bigger load and an 8 GB Pixel 9a feels it most.

**LICENCE — read before shipping anything.** The voice weights are trained on
SASPEECH (© Israeli Public Broadcasting Corporation) and licensed
*"non-commercial purposes only — not for commercial or broadcast needs"*. That
is fine for this free, non-monetised app. **If it ever gains a paid tier or
ads, the Hebrew voice has to come out.** Nothing bundles it, so removing it is
deleting three files. `docs/feasibility.md` §6 has the full chain.

## When Tuki asks you to repeat (new)
The listener now reports how SURE it was about what it heard (it used to
claim a fixed high confidence, so the "please say it again" branch never
fired). Mumble or whisper into the mic and Tuki should ask you to repeat
instead of answering nonsense; logcat `TukiAsr` prints the per-window
confidence. If it asks-to-repeat on clearly spoken phrases, or answers
confidently on garbage, send those confidence lines — the 0.5 threshold was
set in the container and real recordings calibrate it.

## Hands-free mic (bundled Silero VAD)
The mic model ships INSIDE the APK (2.3 MB), so when a Whisper model is
installed the conversation screen shows a **Hands-free** switch. On: tap the
mic once and just talk — the detector ends the turn after ~0.7 s of quiet (a
kid-friendly pause; short blips like a door slam are ignored, and a silent room
gives the turn back after 10 s). Off: the button stays hold-to-talk. The mic
button turns red while listening. Logcat tag `TukiAsr` prints
`endpoint: SILENCE frames a..b` when the VAD closes a turn; `TukiVad` shows the
model load. Measured in-container: 0.21 ms per 32 ms frame (~150× realtime),
so it should be free next to the ASR — worth confirming on the 9a.

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

## Large font / display size (new)
The UI was re-worked for the two Android accessibility dials, so both are
worth a pass:

**Settings → Display → Display size and text** — push *Font size* and *Display
size* each up a couple of notches (or all the way, for the honest stress test)
and walk the app.

What should happen: **ornament shrinks, controls do not.** Tuki gets smaller on
every screen, the splash perch disappears entirely at the extreme, gutters
narrow — and every button stays tappable and no text is cut off. Specific
things that used to break and should not now:

- Chat bubbles ran off the right edge once the display-size setting shrank the
  screen below the old fixed 300 dp cap. They are a fraction of the real width
  now.
- The conversation screen's status chrome (model badge, engine status, warm-up
  hint) was pinned above the transcript and ate the whole viewport at a large
  font, squeezing the conversation to nothing. It scrolls with the transcript
  now — you should see it when the conversation is empty and lose it once
  there are a few turns. That is deliberate.
- The splash used to clip its own progress bar. It now scrolls if it has to.

Anything that clips, overlaps, or cannot be tapped is a bug — screenshot it
with the font/display setting you used.

## Hebrew explanations (new)
Tuki can now explain in written Hebrew, on request. Two gates, both required,
and the control is **absent rather than greyed out** when either is shut:

1. The loaded model must be **E4B** — check the badge under the title reads
   `Gemma 4 · E4B`. E2B failed the Hebrew eval (4.03 with a meta-AI flag) so it
   is not offered there at all.
2. The learner's track must be one that reads: **Parent Zone → Who is
   learning?** → anything except *"Young child (not reading yet)"*.

3. The open unit must not be a **4–6** unit *unless the Hebrew voice is
   installed*. Units 001–006 are 4–6, so on a build without the voice **pick
   unit 007 or later** to see the button at all: a child in a 4–6 unit cannot
   read Hebrew either. With the voice installed the button appears there too,
   because they can hear it instead.

Then, in **Talking with Tuki**, a **הסבר בעברית** button appears under the mic
prompt. Two ways to trigger it:

- Tap the button — a **הסבר בעברית** bubble appears as YOUR turn in the
  transcript (it has to: the engine sends the last message of a request as the
  user turn, so a silent request handed the model Tuki's own previous reply as
  your words), then Tuki re-explains its last point in Hebrew and carries on in
  English. The next turn is ordinary English again; there is no "Hebrew mode"
  to leave. Asking earns no stars — it is help, not practice.
- **Type** anything in Hebrew into the text box. That triggers the same thing
  without the button, on the theory that a learner typing Hebrew has already
  told you English is not landing.

Expect the Hebrew to be **shown, not spoken** — the Hebrew voice is out of this
build over licensing (see the section above), so the router speaks the English
half and displays the whole line. Hebrew text should render right-to-left with
its punctuation in the right place; if a line comes out backwards or with a
stray full stop on the wrong end, that is a bug worth a screenshot.

Worth reporting: whether the Hebrew is actually *correct and useful*, not just
present. That is the thing the eval scored 4.45 on synthetic prompts and which
only real use can confirm.

## Vocabulary room (new)
Home → **Vocabulary room 🗣️** → pick a level (Words / Short sentences / Big
sentences — bucketed by length from the same ten units, so the counts on the
cards are real). Then: Tuki says a line, you hold the mic and say it back.

- **Correct** → stars fly, a chime, +5 XP, next line. "Correct" is judged on
  the TRANSCRIPT (were the words there), not on pronunciation — extra words
  around the target are free, and a five-word sentence tolerates one miss.
- **Wrong** → Tuki says "Almost! Listen again." and repeats the line SLOWER
  (the slow-clear TTS mode getting used for the first time). Third miss moves
  on with encouragement — the room is designed to never be a dead end.
- **Silence** → "I didn't hear you" and the try is not counted.
- The per-sound colour row appears after attempts when the coach model is
  installed; it never gates progress.
- Round ends with a score screen, a MIX burst, and Again / Pick a level.

**Where the lines come from:** when a model is installed, the LLM writes a
fresh set every round (the picker says so — "Tuki makes up new lines every
round ✨"), seeded with a rotating topic so consecutive rounds differ. Every
generated line runs a gauntlet before a child ever hears it: level bucket
check, plain-English charset (no digits, no Hebrew, no markdown), the same
safety filter as everywhere else, dedupe — and anything that fails is
silently replaced from the curriculum deck, so a round is always full. The
NEXT round is written while you play the current one, so "Again!" is instant.

The drill LOOP itself still has no model in it — the LLM writes, never
judges — so the room starts immediately from the curriculum deck even while
a cold model is still loading, and works with no model at all (demo builds
use the deck only). First-ever mic hold pays the Whisper load once (status
line says so).

Worth reporting here: repeated or samey lines across several rounds (the
topic seed should prevent it), any line that feels off-level or off-tone for
a child, and whether the first "Again!" is genuinely instant.

### The parrot voice (new)
Praise now sounds like a parrot: after a correct repetition (and on the
"good try" advance), Tuki's line comes back pitched up ~3 semitones with a
gentle warble, announced by a short "brrp!" trill. This is a strict split,
and the split IS the feature to verify:

- **Teaching speech must be completely untouched** — the target line, its
  slow "Almost! Listen again." recast, the intro, vocab, conversation
  replies, Hebrew. If any line the child is meant to COPY sounds processed,
  that is a bug, report it immediately.
- Only the short praise lines ("Great job!", "Well done!", "You said it!",
  "Perfect!", "Good try! Let's do the next one.") should be parrot-flavored.
- **Kiki, in "Just chat", is fully flavored** — and pitched ABOVE Tuki's
  praise register, so the three speakers separate by ear alone: clean voice =
  the teacher, flavored = the parrot, higher flavored = the other parrot.
  Tuki's chat replies stay clean (they are the model English in that room);
  Kiki's short reactions are where the character lives. If Kiki becomes hard
  to understand, that is a dial worth reporting (`ParrotEffect.KIKI_PITCH`).

The effect is DSP over the same voice (no second model): pitch and formants
up together — the cartoon-creature treatment — plus a ~6.5 Hz flutter.
Matters of taste to report: too squawky / not squawky enough, the flourish
getting old, or the praise becoming hard to understand (it should still be
perfectly intelligible, just birdy). The dials are three constants in
`ParrotEffect`.

Worth reporting: whether the word-judge feels FAIR on a real child's voice —
too strict (right sentence rejected: send the transcript line from logcat) or
too lax (nonsense accepted). The thresholds live in `WordMatch` and real
attempts are what calibrate them.

## Just chat has a mic now
The trailing control in the chat room swaps like every messenger's: **mic when
the input is empty, Send once you type**. Hold the mic, talk, release — the
transcript is sent as your message immediately, and the parrots answer it.
There is no confirm step on purpose: the room is conversational-flow practice
(for adults and teens as much as kids), and your bubble shows exactly what was
heard — including mishearings, which the parrots will gamely answer.

Both the mic and Send now stay disabled until the parrots have **finished
talking**, not just finished generating. Before, a message sent while Kiki was
still speaking was silently dropped after the input field had already cleared
— if you saw text vanish without a reply, that was it, and it is fixed.

The mic is hidden entirely on a device with no recognition path. First-ever
hold may pause on "Heard you! One moment…" while Whisper loads.

## Rewards and the sticker room (new)
Three cues, each meaning a different thing — they should never all mean "you
did something":

| You see | You hear | It fired because |
|---|---|---|
| Gold coins arcing up | two quick bright notes | a turn finished |
| Spinning stars | a rising major triad | your pronunciation scored ≥ 0.8 |
| Soft drifting flakes | a quiet fifth | pronunciation 0.5–0.8 — "nearly" |
| *(nothing)* | *(nothing)* | below 0.5. Deliberate: the coloured phonemes already say what happened |

The coins land **after** Tuki finishes speaking, and the star lands right after
you speak, while Tuki is thinking. Both play on the **media** volume, the same
one as Tuki's voice — so they follow the same slider, and a phone in vibrate
mode silences neither. That is deliberate: routing them to the system stream
would have muted the reward on a silent phone while the tutor kept talking.
If a chime ever plays over Tuki's voice, or is loud enough to compete with it,
say so — it is mixed at peak 0.34 against the voice's ~0.46, but that ratio is
theory until a real device confirms it.

Worth trying specifically: earn **two milestones in one sitting** (100 XP) and
check you get **two** trips to the room, one after the other. And back out of
the room with the Back gesture instead of picking — you should NOT bounce
straight back in, but you should be asked again at the next milestone.

**The sticker room** appears for a young learner every 50 XP (ten turns). Young
means either the track is *"Young child (not reading yet)"* **or** the open
unit is a 4–6 unit — so units 001–006 trigger it with the default settings.

Expected: the lesson is interrupted, you land in a room with eight stickers,
tapping one takes it (a burst plays, the shelf at the bottom grows), and about
two seconds later you are back in the **same** conversation, mid-session, with
Tuki still loaded. If it dumps you on the home screen, or the model reloads,
that is a bug. Backing out with the system Back gesture instead of picking
should NOT bounce you straight back in — you get asked again at the next
milestone.

## Accelerators: what the 2026-08-27 device round settled
Three changes went out together and the device answered all of them. Two are
kept, one is reverted, and the round produced a rule.

**The rule, relearned: one accelerator variable per install.** MTP-on-GPU and
Whisper-on-GPU shipped in the same build. Both touch the GPU, they initialised
96 ms apart, and the process died natively twice — so neither could be
attributed and the LLM ended up pinned to CPU for the install. That is the
exact failure mode that pulled MTP the first time.

**REVERTED — Whisper on the GPU delegate.** It was slower (rtf 2.14 → 4.20
against ~2.8 on CPU), and — decisively — it took the LLM's GPU backend down
with it: two GPU runtimes initialising 96 ms apart crashed the process
natively twice, pinning the language model to CPU (`first token after 4184ms`
against 618 ms). With the delegate gone, `GPU verdict: USED with MTP` came
back clean, which confirms the delegate was the crasher.

*Correction:* an earlier version of this section also claimed the delegate
"wrecked accuracy", citing confidence falling from 0.91 to 0.40–0.54. **The
0.91 was an invented example in this document, not a measurement** — `conf=`
logging landed in the same commit as the delegate, so no before-figure
existed. CPU-only runs show `conf=0.48–0.54`, i.e. the same range. And
`-> 1 tokens` is the correct output for a one-word drill answer, not a
failure. The revert stands on the crash and the timings.

**RESTORED — Whisper's thread count.** Its `THREADS = 4` is an accuracy
calibration, not a performance default: the export is dynamic-range quantized,
XNNPACK partitions reductions by thread count, and the count therefore changes
the winning token on marginal frames (`docs/asr-model-eval.md`: 17/18 correct
at 2 threads, 9/12 at 4 on a 4-core host). Folding it into the shared thermal
budget dropped it to 3 and **measurably degraded recognition — noticed in
ordinary use before any log showed it.** Back to 4, with the reason stated
where the next person will read it. The ONNX engines keep the shared budget;
they have no such calibration.

**KEPT — accelerator initialisation is now serialised** (`AcceleratorGate`).
Only one runtime may bring up an accelerator at a time. This is what makes the
crash markers honest: Kokoro's XNNPACK session took 8.3 s to build while the
LLM's GPU attempt crashed underneath it, and XNNPACK was pinned off for the
install **despite being entirely innocent** — which is why the last run
reported `TukiTts: XNNPACK skipped for this install`. Inference still runs
concurrently; only bring-up is exclusive.

**STILL UNMEASURED — XNNPACK for the voice.** It has never actually run: the
coach got it (`TukiGop: XNNPACK, 3 threads`) but the voice was pinned off by
that false attribution. So every TTS number so far is portable kernels. The
thread cut 4→3 alone moved cold rtf from 1.02 to **0.94**, which is small.
This is the number still to get.

**CONFIRMED — MTP is genuinely running.** The native runtime reports its own
draft acceptance rate:
```
llm_litert_mtp_drafter.cc:172] MTP Drafter - Success rate: 0.458333
```
46 % of drafted tokens accepted, with `mtp_drafter` (206 ops) and `verify`
(2801 ops) both delegated to the GPU. So the feature works; our
`decode ~N est-tok/s` figure is simply too crude to show it on 40-character
replies — it is `chars÷4÷seconds` over a couple of seconds. Do not conclude
MTP is inert from that number.

**FIXED — the OpenCL sampler was missing.** Every engine load printed:
```
sampler_factory.cc:403] OpenCL sampler not available, falling back to
  statically linked C API ... libLiteRtTopKOpenClSampler.so not found
```
`scripts/fetch-gpu-libs.sh` deliberately skipped that library, on the
reasoning that the combined accelerator "already carries the CL path". True of
the *accelerator*; wrong about the *sampler*, which the runtime dlopens
separately by name. **Sampling runs once per token**, so every token's top-K
was being done off the GPU by a fallback. Now fetched and pinned. This is the
most promising remaining lead on decode speed — re-run `scripts/fetch-gpu-libs.sh`
before building, and the warning should be gone.

**KEPT — MTP on GPU**, now with a clean run at it: the ladder
(`gpu+mtp` → `gpu` → `cpu+mtp`) worked exactly as designed, stepping down one
rung per crash, and CPU+MTP came up fine. With the ASR delegate gone the GPU
rungs get an uncontended test.

### Reset the pinned hints before measuring
A reinstall bumps `lastUpdateTime` and clears every hint automatically. To
force it without reinstalling:

```bash
adb shell run-as org.sisam.langtutor ls files/models/
adb shell "run-as org.sisam.langtutor sh -c 'rm -f files/models/*.xnnpack-skip \
  files/models/*.cpu-hint files/models/*.gpu-mtp-skip files/models/*attempting'"
```

Then the four lines that matter, in order:
```
TukiOnnx     TukiTts: XNNPACK, 3 threads                 ← must say XNNPACK now
TukiLlm      GPU verdict: USED with MTP (speculative decoding)
TukiTts      synth … rtf=                                 ← cold AND after 5 min
TukiLatency  first audio …ms after mic release            ← the real number
```

## Battery when the app is not in use (new)
The app is built to cost nothing in the background: **no services, no
alarms, no scheduled jobs, no wake locks, no polling** — network happens only
for downloads you start. An idle backgrounded process just gets frozen by
Android (and by GrapheneOS more aggressively), at effectively zero drain.

What used to be able to outlive the screen was in-flight work: a reply being
spoken to a pocket, or — worse — an open microphone from a hands-free
session. Now, the moment the app leaves the screen (home, app switch, lock),
it quiesces: speech stops and the mic is released. Logcat shows
`quiesced: app backgrounded — speech and mic released` under `TukiMem`.

How to verify:
- Start Tuki talking (voice test or a reply), press home mid-sentence → the
  voice must cut within a beat.
- Turn on hands-free in a conversation, press home while it is listening →
  the mic indicator (status-bar green dot; GrapheneOS shows it prominently)
  must disappear immediately.
- After a day of normal use, Settings → Battery → Tuki should show **no
  background usage** to speak of.

Two deliberate non-cuts, both bounded: an LLM reply already decoding runs to
its token cap (seconds) rather than poisoning the conversation you return
to, and a model load already in flight finishes rather than being re-paid
later. Rotation does not quiesce — only a real exit does.

### What happens to the loaded model and assets
A loaded model costs no battery — weights sitting in RAM schedule no work,
and a frozen cached process cannot run any. What residency DOES cost is
being the fattest target in the low-memory killer's sights: a cached app
holding 4+ GB is the first thing Android kills, and then coming back pays a
full cold start anyway.

So the policy is two-stage:

| When | What happens |
|---|---|
| Leaving the screen | Speech stops, mic released. Models stay warm. |
| Back within **3 minutes** | Nothing was lost — instant. |
| Backgrounded past 3 min | LLM unloaded, Whisper/Kokoro/coach/Hebrew released. Logcat: `released heavy engines (backgrounded 3 min)` |
| System asks for memory while cached | Same full release, immediately, on the OS's own signal |

Coming back after a release is **not** an error state: the next turn reloads
the model on demand (you will see "Waking Tuki up…"), and Home's preload
button correctly reads un-loaded again. The tier choice (E4B vs E2B)
deliberately survives — giving memory back must not silently re-open that
decision mid-process.

Worth checking on device: background for ~4 minutes, return, and confirm the
conversation you left is still there and its next turn works after the
reload pause. Also confirm `adb shell dumpsys meminfo org.sisam.langtutor`
drops by gigabytes a few minutes after backgrounding.

## Speech engine speed and heat (new — please measure)
Measured on your Pixel 9 log of 2026-08-27: Kokoro was synthesizing at
**RTF 1.0–2.0** — as long to make the audio as the audio lasts — and getting
worse with use. Identical work, four minutes apart:

```
17:55:18  synth 11 tokens -> 2.08s in 2126ms    RTF 1.02
17:59:23  synth 11 tokens -> 2.00s in 3912ms    RTF 1.96
```

`dumpsys thermalservice` at that moment: **BIG core 90°C**, MID 71, LITTLE 66,
GPU only 56, skin 39 with `Thermal Status: 1`. The GPU decode never
degraded (7–8 tok/s throughout) — only CPU work halved. Two causes, both
ours:

- **No ONNX execution provider was configured anywhere.** Every model ran
  ORT's portable reference kernels. Now XNNPACK, which is ARM-tuned.
- **Every engine asked for 4 threads.** Tensor G4 has only FOUR fast cores
  (1×X4 + 3×A720 + 4×A520), so each session saturated exactly the cores that
  get hot. Now a shared budget of 3, leaving one fast core for the LLM's
  CPU-side work and the audio thread.

### The four lines that answer everything
One command now captures the whole picture — no `dumpsys`, no arithmetic:

```bash
adb logcat -v time -s TukiLatency:I TukiOnnx:I TukiThermal:I TukiStep:I \
                     TukiTts:I TukiAsr:I TukiLlm:I TukiMem:I
```

**1. Is it responsive?** The one number that matters — the learner stops, to
the first sound back — measured end to end, including cold loads:
```
TukiLatency  first audio 3420ms after mic release [thermal LIGHT hr=0.98]
```
Marked at mic release / Send in all three rooms. **Anything over ~1.5 s is
the complaint you reported, quantified.**

**2. Did the optimization land?** Once per model:
```
TukiOnnx  TukiTts: XNNPACK, 3 threads          ← what we want
TukiOnnx  TukiTts: portable kernels, 3 threads ← didn't bind; send this
```

**3. Is the engine fast enough?** RTF is now printed, not left to arithmetic —
1.0 means synthesis takes as long as the speech lasts:
```
TukiTts  synth 22 tokens -> 2.58s in 2745ms rtf=1.06 peak=0.435 …
TukiAsr  transcribed 1120ms audio in 3159ms rtf=2.82 conf=0.91
```

**4. Was the phone throttling while you measured?** Every completed step now
carries thermal context when it is not cool, and transitions are logged the
moment they happen:
```
TukiStep     ✔ TTS_RUN 22 phonemes in 2745ms [thermal LIGHT hr=0.98]
TukiThermal  thermal status -> LIGHT (headroom 0.99)
```
`hr` is Android's forecast headroom: **1.0 is the throttling point.** A
benchmark taken near 1.0 is measuring the weather, not the code — which is
exactly what happened on 2026-08-27 and took a separate `dumpsys` to discover.

And once at startup, so a pasted log says what silicon produced it:
```
TukiMem  device: Pixel 9 (Tensor G4) cores=8 onnxThreads=3 ram=12GB thermal=NONE headroom=0.31
```

**What to measure:** run the vocabulary room for ~5 minutes, then send the
`TukiLatency`, `TukiOnnx` and `TukiTts rtf=` lines from the START and the END
of the run. That comparison — same work, cold vs warm, with headroom printed
beside it — settles the remaining question by itself:

- **rtf drops well under 1.0 and stays there** → XNNPACK fixed it; next stop
  is streaming for perceived latency.
- **rtf near 1.0 with `XNNPACK` bound and `hr` well below 1.0** → not heat and
  not our config: Kokoro is the wrong engine for this device, and
  `docs/feasibility.md` §6 has the alternative (Piper, documented at RTF ~0.2
  on a Raspberry Pi 4 — effectively instant on a Tensor).
- **rtf fine cold, bad warm, `hr` climbing to 1.0** → still thermal, and the
  answer is the degradation ladder rather than a faster engine.

XNNPACK carries the same crash-hint machinery as the LLM's GPU attempt — a
marker file written before the attempt, so a native crash inside the provider
pins the portable kernels for that install instead of crash-looping. Graph
optimization deliberately stays at BASIC: ORT's extended optimizer was seen
to crash on the Kokoro graph, and that stays one variable per install.

## Known limits in this build (expected, not bugs)
- **English speech recognition only.** The bundled model is an English-only
  export, so a Hebrew answer into the mic will come back as English-looking
  nonsense. Hebrew works for typing (and on-screen text), not for listening.
- A turn is capped at 30 seconds. Past the model's 10-second window the audio is
  split at the quietest gap and transcribed piece by piece (`utterance split
  into N windows` in logcat), so long answers survive — but each piece is
  decoded independently, so a sentence spanning a cut can read oddly.
- Names outside the voice's dictionary use letter-to-sound rules.
- **Tuki does not speak Hebrew aloud in this build** — see the Hebrew section
  above (licensing). Hebrew text still displays; all-Hebrew lines are skipped
  silently, mixed lines speak their English words.
- Pronunciation thresholds were calibrated on synthesized speech (see the
  section above) — the numbers you report are what recalibrates them.
- **Hebrew explanations are text only UNTIL you install the Hebrew voice**
  (`--hebrew`, see above). Without it a Hebrew explanation is displayed and
  only its English half is spoken — the documented degradation, not a failure.
- The learner track is set in the Parent Zone, not by onboarding — the
  three-question onboarding the plan calls for is not built yet, so a fresh
  profile starts on **Beginner**.
- Debug-signed test build — not Play-ready, not safety-certified for children yet.
