# The practice flavor — Tuki without the brain

Tuki ships in two build flavors from one source tree:

| Flavor | What it is | Reference devices |
|---|---|---|
| `full` | Everything: free conversation with Gemma 4 on device, plus every practice room | Pixel 9a (8 GB → E2B), Pixel 9 (12 GB → E4B/E2B), Pixel 10 Pro XL (16 GB → E4B) |
| `practice` | The authored curriculum only — no language model, no GPU runtime, nothing that exists solely to feed the model | Samsung Galaxy Tab S10 FE |

The practice flavor is not a cut-down demo. It is the half of the app that
was always designed to run without a model: the phrasebank drills (3,108
verified sentences across 37 themes and Levels 1–7, with authored Hebrew
translation, derived transliteration and karaoke alignment), the picture
room (168 drawn icons), the pronunciation coach, the bundled voice and ears.
The container's own doc on the drill loop already says it: "the LLM writes
lines upstream but never judges, and a missing or still-loading model never
blocks the room." What the flavor removes is the free-conversation rooms
(lesson, conversation, chat) and their generated Hebrew meaning rows, which
are the only things that need the 2.6–3.7 GB brain.

## Why a tablet, and why this one

A tablet is the natural classroom or kitchen-table device for structured
practice: bigger pictures, a stand, no phone calls. The Galaxy Tab S10 FE
was picked as the reference because it is the mainstream mid-range Samsung
tablet, which is what a school or a second family device is likely to be.

What it is (from Samsung's and GSMArena's published specifications and the
Exynos 1580 launch coverage; links at the end):

- **SoC:** Exynos 1580, 4 nm — one Cortex-A720 at 2.9 GHz, three Cortex-A720
  at 2.6 GHz, four Cortex-A520 at 1.9 GHz; Xclipse 540 GPU (RDNA 3 based,
  OpenCL 2.0 / Vulkan 1.3).
- **Memory / storage:** 8 GB RAM (12 GB on some configurations), 128–256 GB,
  **microSD up to 1.5 TB**.
- **Display:** 10.9" IPS, 2304 × 1440, 90 Hz.
- **Software:** Android 15 / One UI 7 at launch. Our minSdk is 31.
- **I/O:** USB-C 2.0 **with OTG**, S Pen included, 8,000 mAh.

### Fit, honestly

- **Memory is a non-issue without the model.** The practice engine set —
  Kokoro voice (86 MB), Whisper ears (286 MB), pronunciation coach (318 MB),
  VAD (2 MB), optional streaming preview (73 MB) — is well under 1 GB
  resident. With the model, an 8 GB device is an E2B machine at best under
  our own policy; without it, the tablet has headroom to spare.
- **CPU is the real question, and it is better than it looks.** The A720
  cores are mid-range — weaker peak than a Pixel 9's X4 — but the Pixel
  numbers we have were measured with Gemma occupying the big cores and the
  SoC throttling. On the practice flavor nothing competes with the voice and
  the ears, so TTS/ASR real-time factors should land at or better than the
  contended Pixel figures. XNNPACK is plain ARM NEON; nothing in the speech
  stack is vendor-specific. This is a prediction until the first device
  round measures it (see the test plan).
- **No Google services anywhere** by design, so One UI changes nothing.
  `resizeableActivity` defaults on for our target SDK, so DeX and
  multi-window work; the landscape pass and the central inset handling mean
  a 10.9" screen renders correctly. The layouts are still phone-proportioned
  single columns — usable, not yet tablet-optimised.
- **Unknowns to measure, not blockers:** microphone distance (a tablet on a
  stand is farther from the mouth than a held phone — VAD and pronunciation
  thresholds may want tuning), speaker echo into the mic during the
  voice-barge experiment, and XNNPACK behaviour on Exynos (the crash-marker
  machinery in `OnnxTuning` covers the failure mode if it appears).

## What is in and what is out

`BuildConfig.HAS_LLM` is the single switch in code; the flavor sets it.

| Piece | `full` | `practice` | Where the split lives |
|---|---|---|---|
| LiteRT-LM runtime (the model's inference library) | in | **out** | `fullImplementation(libs.litertlm.android)` |
| `LiteRtLmEngine` (the model wrapper) | in | **out** | `app/src/full/kotlin/…/engine/LiteRtLmEngine.kt`; `RealLlm` factory per flavor |
| GPU delegate libraries (`scripts/fetch-gpu-libs.sh`, ~34 MB) | in | **out** | `app/src/full/jniLibs/arm64-v8a/` |
| `libOpenCL` native-library declarations | in | **out** | `app/src/full/AndroidManifest.xml` |
| Lesson, conversation and chat rooms; unit cards | in | **out** | Home screen gated on `HAS_LLM` |
| Edge TPU probe and 60 Hz decode cap switches | in | **out** | Experimental section gated |
| "Hebrew explanations need E4B" note; model-tier badge | in | **out** | gated |
| Gemma packs in the pack list and the folder report | offered | **not expected** | `expectedPacks()` filters `PackKind.LLM` |
| Speech models (ears, voice, coach — ~700 MB) | packs: folder or download | **inside the APK** | `scripts/fetch-practice-models.sh` → `app/src/practice/assets/models/`, unpacked on first launch |
| Vocabulary room, picture room, phrasebank, coach | in | in | — |
| Kokoro voice, Whisper ears, VAD, streaming preview, barge probe | in | in | — |
| Thermal readout, accelerator gate, synthesis cache | in | in | generic; the voice uses them too |

What deliberately stays in `practice` even though the Pixel builds were
where it was born: the thermal readout (it explains slow voice numbers on
any SoC), the accelerator gate (XNNPACK session builds go through it), and
the streaming-preview experiment (LibriSpeech-trained, device-agnostic).
The rule is "exists only to feed the model → out", not "was first tested on
a Pixel → out".

Both flavors share one `applicationId`, so a device can move from
`practice` to `full` by installing the other APK over it — profile, stars
and stickers survive. `practice` carries a `-practice` version-name suffix
so a screenshot or a bug report says which one it is.

## The tablet needs nothing: everything is in the APK

The practice flavor ships its three speech models inside the APK (~870 MB
all told, arm64 only). On first launch the app copies them out into its
files directory — seconds on the tablet's storage, once per install, with
the splash holding until it is done — and every engine then loads from a
path exactly as on a phone. Parent Zone → Packs shows one line: everything
is built in. Nothing to pick, download, update or report; an app update is
a new APK. Only the optional Hebrew voice (non-commercial weights) stays
outside, as it does on the phones.

## One folder for the large files (phones)

Every large file a phone build does not bundle — the speech models and the
brain — is expected in **one directory**, and the app imports everything
from it with **one button** (Parent Zone → Packs → *Import from folder*).
The layout is the one `scripts/download-sideload.sh` writes:

```
<folder>/
  gemma-4-E2B-it.litertlm            (full only; the 9 also takes the E4B)
  speech/
    acft_whisper_small.en_10s.tflite   ears
    model_quantized.onnx               Tuki's voice (Kokoro)
    wav2vec2-phoneme-int8.onnx         pronunciation coach
```

On a phone that directory is a USB-C drive, a microSD card or Downloads.
The picker is the system document tree (Storage Access
Framework), so no storage permission is requested and USB/SD/cloud all look
the same. The folder is remembered — the next tap re-scans it — and the
scan ends with a **report**: every pack this device expects, marked
installed, imported now, or **missing**, with the exact filename and size
that is missing. A file present but corrupt is rejected by SHA-256 exactly
as a download would be. What the device expects is the RAM-gated pack list
minus, on `practice`, the language-model packs.

`scripts/download-sideload.sh tab-s10-fe` produces the tablet's directory
too: just the practice APK and a `push.sh` that installs it.

## Tuned for the tablet

Four behaviours differ on `practice`, each because the model is not there
to share the device with:

- **Thread budget.** The ONNX engines (voice, coach, streaming preview) get
  the whole big cluster — the cores in the top frequency tier, read from
  sysfs, capped at 4 — instead of the phones' 3-of-4 that leaves one fast
  core for the model. `adb logcat -s TukiOnnx` prints `heavyThreads=…` with
  the core count and reasoning once at start-up, so every rtf line can be
  read against it. Whisper keeps its own measured count on both flavors.
- **Resident engines.** The three-minute background release, which exists
  to hand back a multi-GB model, does nothing here: the engine set is under
  a gigabyte and a Kokoro session rebuild costs seconds a classroom tablet
  would pay on every return. A memory-trim signal from the system still
  releases everything.
- **Shorter splash.** Two seconds instead of four — the floor the voice and
  ears need, not the one the model needed. A first launch still waits for
  the bundled models to unpack.
- **Screen stays on in the rooms** (both flavors): a tablet on a stand no
  longer dims and locks mid-drill. Parent Zone and the sticker book follow
  the system timeout; the daily-minutes limit bounds the rest.

## Building and testing

```bash
scripts/fetch-practice-models.sh          # once: the ~700 MB of speech models the tablet APK carries
./gradlew :app:assemblePracticeDebug      # the tablet build (~870 MB)
./gradlew :app:assembleFullDebug          # the phone build (run scripts/fetch-gpu-libs.sh first)
```

CI builds both and publishes both to the rolling `debug-latest` release as
`app-practice-debug.apk` and `app-full-debug.apk`.

First tablet round — what to check, in order:

1. Install `app-practice-debug.apk`; the home screen shows the vocabulary
   and picture rooms and no lesson cards. Parent Zone shows no model packs
   and no Edge TPU / 60 Hz switches.
2. The first launch holds the splash a few seconds longer while it unpacks
   the bundled models (`adb logcat -s TukiMem` shows one `unpacked bundled`
   line per model with its time). Parent Zone → Packs should show the single
   "everything is built in" line and nothing else.
3. Picture room: tap through ten cards — the voice should start well under a
   second each after the first (the first pays the XNNPACK session build).
4. Vocabulary room: three drill turns. Watch `adb logcat -s TukiTts TukiAsr
   TukiGop TukiThermal` for the real-time factors; the hypothesis to confirm
   or refute is TTS rtf ≤ 1.2 and ASR rtf ≤ 1.5 with a cool SoC.
5. Put the tablet on a stand at arm's length and repeat two drill turns:
   this is the microphone-distance check. If the VAD misses the child, note
   the `TukiAsr` endpoint lines.

## Follow-ups this flavor makes worth doing

- A two-pane layout for the drill and picture rooms on width ≥ 840 dp
  (window size classes), so the tablet stops rendering a stretched phone.
- Google Play's large-screen quality requirements, if the tablet ever goes
  to Play (the direct-APK route has none).
- S Pen: tracing letters in the picture room is a natural Level 1 activity.

## Sources

- Samsung, *Galaxy Tab S10 FE and S10 FE+ key features* —
  <https://www.samsung.com/us/support/answer/ANS10005059/>
- GSMArena, *Samsung Galaxy Tab S10 FE — full tablet specifications* —
  <https://www.gsmarena.com/samsung_galaxy_tab_s10_fe-13761.php>
- Notebookcheck, *Samsung Galaxy Tab S10 FE+ review collection* —
  <https://www.notebookcheck.net/Samsung-Galaxy-Tab-S10-FE.1063856.0.html>
- GSMArena, *Exynos 1580 unveiled with Cortex-A720 cores, double the GPU
  hardware* —
  <https://m.gsmarena.com/exynos_1580_unveiled_with_cortexa720_cores_double_the_gpu_hardware-news-65051.php>
- FoneArena, *Samsung Exynos 1580 4nm SoC with Xclipse 540 GPU announced* —
  <https://www.fonearena.com/blog/438829/samsung-exynos-1580-features.html>
- NanoReview, *Samsung Exynos 1580: specs and benchmarks* —
  <https://nanoreview.net/en/soc/samsung-exynos-1580>
