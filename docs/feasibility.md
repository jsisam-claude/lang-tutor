# Feasibility Review — Fully On-Device Language Tutor for Kids

**Product**: English tutor for Hebrew-speaking children (ages 4–13), dual-channel
(full speech loop **and** text tutor), running entirely on-device on Pixel-class
Android hardware. Everything ships in the package — model weights included. No
account, no cloud, no data collection.

**Review date**: 2026-07-19 · **Target reference device**: Pixel 10 Pro XL
(Tensor G5, 16 GB RAM, Android 16) · **Floor device**: Pixel 9 (Tensor G4, 12 GB).

Confidence markers: unmarked = corroborated by primary/official sources ·
⚠️ = medium/low-confidence (secondary sources) · ❓ = could not verify, treat as open.

---

## 1. Executive summary

**Verdict: feasible today.** Every capability in the concept has at least one
concrete, licensable, on-device implementation path on Pixel 9/10-class hardware:

| Capability | Verdict | The path | The catch |
|---|---|---|---|
| On-device LLM tutor | ✅ Solid | Gemma 4 E2B (Apache 2.0, 2.58 GB) on LiteRT-LM | Hebrew output quality unbenchmarked ❓ |
| English ASR (lessons) | ✅ Solid | Whisper-small / Moonshine + vocab biasing | — |
| English ASR (free talk, kids) | 🟡 Workable | Kid-fine-tuned Whisper | ~22 pp child WER penalty stock; main kids corpus is non-commercial |
| English TTS | ✅ Solid | Piper (63 MB) or Kokoro-82M (<80 MB int8) | No purpose-built child voice; use slow-clear mode |
| Hebrew TTS | 🟡 Workable | Phonikud + phonikud-tts (CC-BY-4.0) + pre-recorded human audio | Quality below big-cloud TTS; nikud handled by Phonikud |
| Hebrew ASR | 🔴 Defer | ivrit.ai Whisper turbo (809 M) | Heavy; no small/streaming Hebrew model exists — design around it |
| Pronunciation scoring | 🟡 Build | CTC-GOP with ~20 MB phoneme model | No offline SDK exists anywhere — in-house work |
| Ship-it-all packaging | ✅ Fits | Play Asset Delivery, ~3.4–3.9 GB total | Play's ~4 GB per-device ceiling; E4B tier doesn't fit |
| Kids compliance | ✅ Clear path | Zero-collection by design + Play GenAI-for-kids controls | Safety filters/red-teaming are mandatory work, not optional |

**Why now**: three things changed in 2025–2026 that make this buildable where it
wasn't before — (1) **Gemma 4** (2026-04-02) put a genuinely capable multimodal
2B-effective model under **Apache 2.0**, with a **Pixel 10 TPU-optimized build
(2026-07-14)**; (2) **LiteRT-LM** matured into a production Android LLM runtime;
(3) **Phonikud** (2025) solved the Hebrew TTS nikud problem in an on-device,
commercially-licensable package.

**The honest product claim**: installing from Google Play is itself a network
download, so the claim is **"zero network after install — no account, no cloud, no
ongoing data."** A literally-never-online SKU is possible via direct APK
distribution (no Play size policy applies). The app ships with **no INTERNET
permission**, making offline a build-time guarantee rather than a promise.

---

## 2. Target hardware reality

### Device SKUs

| Device | Chip | RAM | Storage | Notes |
|---|---|---|---|---|
| Pixel 9 | Tensor G4 | 12 GB | 128/256 GB | Floor device |
| Pixel 9 Pro / Pro XL | Tensor G4 | 16 GB | 128 GB–1 TB | 9 Pro XL: UFS 3.1 |
| Pixel 10 | Tensor G5 | 12 GB | 128/256 GB | |
| Pixel 10 Pro | Tensor G5 | 16 GB | 128 GB–1 TB | |
| Pixel 10 Pro XL | Tensor G5 | 16 GB | 256 GB–1 TB | UFS 4.0; reference device |

Implications: base models (12 GB RAM) are the tight-but-OK floor — a ~2 GB-resident
LLM plus speech models co-exist with the OS, but 16 GB Pro devices give real
headroom. 128 GB storage floors comfortably hold a ~4 GB bundle; plan for users
with full devices (storage check + clear messaging at install).

### Tensor G4 → G5 (what changed, what didn't)

- **Tensor G5** (Pixel 10): Google's first TSMC-fabbed Tensor (3 nm N3E); CPU ~34%
  faster; **GPU vendor switched from ARM Mali to Imagination DXT-48-1536**; 4th-gen
  TPU "up to 60% more powerful," runs the newest Gemini Nano ~2.6× faster at ~50%
  less power ([Google](https://blog.google/products-and-platforms/devices/pixel/tensor-g5-pixel-10/), 2025).
- Independent benchmarking says G5's AI performance is **memory-bandwidth-bound**
  and trails Snapdragon 8 Elite in some AI suites; the new GPU throttles under
  sustained load ⚠️ ([Android Authority](https://www.androidauthority.com/pixel-10-game-emulation-test-3605149/), 2025).

### The acceleration reality check (important)

- **NNAPI is deprecated** (Android 15); NPU access now goes through **LiteRT
  delegates** supplied per silicon vendor ([Android developers](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)).
- Production-grade NPU-LLM acceleration exists today for **Qualcomm and MediaTek**
  (e.g. Gemma-3n-E2B at >1,600 tok/s prefill / 28 tok/s decode on Dimensity 9500
  NPU) — i.e., *Samsung-class phones, not Pixels*
  ([Google/Qualcomm](https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/); [MarkTechPost](https://www.marktechpost.com/2025/12/09/google-litert-neuropilot-stack-turns-mediatek-dimensity-npus-into-first-class-targets-for-on-device-llms/), 2025-12).
- On Pixel, the GPU path is weak: Tensor GPUs are not first-class OpenCL-LLM
  targets (LiteRT-LM GPU backend reported to silently fail on Pixel 8 Pro — OpenCL
  not found ⚠️ [LiteRT-LM#1860](https://github.com/google-ai-edge/LiteRT-LM/issues/1860));
  whether Pixel 9/Tensor G4 exposes usable OpenCL is not cleanly documented ❓.
- **Pixel's real accelerator is the TPU**, opened to third parties via the **Google
  Tensor ML SDK (Beta since I/O 2026)** — AOT-compiled models, Pixel 10 family
  ([Google](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/), 2026-05). On
  **2026-07-14** Google shipped a **Gemma 4 E2B build tuned for the Pixel 10
  Tensor G5 TPU** ([9to5Google](https://9to5google.com/2026/07/14/pixel-10-gemma-4/)).

**Design consequence**: architect for **CPU as the baseline** on all Pixels, treat
the **TPU as an accelerator bet on Pixel 10+**, and don't build anything that
depends on GPU inference.

---

## 3. The brain — on-device LLM

### Candidates (as of July 2026)

| Model | Params (eff.) | File (int4-class) | License | Modalities | Hebrew |
|---|---|---|---|---|---|
| **Gemma 4 E2B** ← default | ~2B (PLE) | **2.58 GB** .litertlm | **Apache 2.0** | text+vision+**audio**, function calling | in "140+ pretrained" — no published benchmark ❓ |
| Gemma 4 E4B (quality tier) | ~4B | 3.66 GB | Apache 2.0 | same | same ❓ |
| Phi-4-mini (fallback) | 3.8B | ~2.3–2.6 GB ⚠️ | MIT | text | **only small model documenting Hebrew** (weaker than EN) |
| Qwen3 1.7B/4B | 1.7/4B | ~1.4–2.5 GB | Apache 2.0 | text | in 119 langs, but open Hebrew-quality bug ([Qwen3#1114](https://github.com/QwenLM/Qwen3/issues/1114)) |
| Gemma 3n E2B/E4B | 2/4B | ~3–4.4 GB ⚠️ | Gemma Terms (flow-down obligations) | text+vision+audio | ❓ |
| Llama 3.2 1B/3B | 1/3B | ~0.7–2 GB | Community License (badge + MAU cap) | text | **not** in official 8 languages |

Sources: [Gemma 4 launch](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/) (2026-04-02);
[gemma-4-E4B litert-lm card](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm);
[Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4);
[Phi-4-mini](https://huggingface.co/microsoft/Phi-4-mini-instruct) (2025);
[Qwen3](https://qwenlm.github.io/blog/qwen3/) (2025-05); [Llama 3.2 license](https://www.llama.com/llama3_2/license/).

**Choice: Gemma 4 E2B**, for four reasons: Apache 2.0 (no license flow-down into a
paid kids' app), best-in-class mobile tooling (LiteRT-LM `.litertlm`, AI Packs),
**native audio input** (opens a "the LLM hears the child directly" mode), and the
July 2026 Pixel-10-TPU-optimized build. **E4B** is the quality tier where RAM and
distribution allow (see §8 — it does *not* fit Play's per-device budget alongside
the speech stack; it becomes an optional add-on).

**The Hebrew question (top-3 risk)**: no small model publishes a Hebrew benchmark.
Gemma 4 very likely covers Hebrew (140+ languages pretrained) but unverified ❓;
Phi-4-mini *documents* Hebrew (MIT-licensed fallback); Qwen3 has a filed quality
complaint. **Phase 1 includes a self-run Hebrew eval** (tutor-domain prompts:
instructions, translations, error explanations — scored by Hebrew speakers) before
committing. Mitigation baked into the design: the tutor **speaks English by
default**; Hebrew appears mostly as *fixed UI strings and pre-recorded audio*, with
LLM-generated Hebrew limited to short scaffolding sentences.

### Runtime

**LiteRT-LM** (the successor to the MediaPipe LLM Inference API, which is now
maintenance-only). CPU via XNNPACK everywhere; GPU (OpenCL/OpenGL) where present;
NPU via vendor delegates; **Tensor TPU via the Tensor ML SDK (Beta, AOT)**.
Gemma 4 audio input is live on Android; Gemma 3n audio has run on-device since
2025-09 (batch clips ≤30 s)
([LiteRT-LM](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/), 2026;
[AI Edge Gallery audio](https://developers.googleblog.com/google-ai-edge-gallery-now-with-audio-and-on-google-play/), 2025-09-09).

### Measured performance (what to actually expect)

| Path | Prefill | Decode | Source/confidence |
|---|---|---|---|
| Gemma 4 E2B, flagship GPU (Adreno, S25 Ultra) | fast | 52 tok/s (+MTP ≈2.2×) | [Google](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/) — *not transferable to Pixel* |
| Gemma 4 E2B, **Pixel 9 Pro-class CPU** | hundreds tok/s | **~10–25 tok/s** | ⚠️ secondary ([mindstudio](https://www.mindstudio.ai/blog/gemma-4-e2b-e4b-edge-models-phone-local), 2026) |
| Gemini Nano 4 on Pixel TPU (reference) | — | ~19 tok/s | ⚠️ [Android Authority](https://www.androidauthority.com/gemini-nano-4-benchmarks-3655763/) (2026) |
| Gemma 4 E2B, Raspberry Pi 5 CPU (floor ref.) | 133 tok/s | 7.6 tok/s | [Google](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/) |
| Gemma 3 1B, AI Edge | up to 2,585 tok/s | — | [Google](https://ai.google.dev/edge) |

Time-to-first-token ~1–3 s typical (up to ~11 s on long prompts ⚠️) — mitigated by
short tutor prompts, prefix caching of the system prompt, and keeping the model
loaded for the session. RAM while loaded: E2B ≈ 1.3–2 GB, E4B ≈ 2.5 GB + KV cache.

### Thermals & battery (shapes the whole product)

Sustained on-device inference throttles after **~15–25 min** (30–50% sustained
loss; one profile: GPU 680→231 MHz at 78.3 °C) and drains a flagship battery in
**~2–4 h** of continuous load ⚠️
([on-device LLM engineering surveys](https://v-chandra.github.io/on-device-llms/), 2026).
**Design consequence**: tutoring is **turn-based with idle gaps** (which matches
pedagogy for kids anyway); engines load per session and unload after; no always-on
streaming inference. A 15–20-minute session — the right length for a child — fits
inside the thermal envelope.

### Why not Gemini Nano / AICore?

Gemini Nano is OS-managed (downloaded/updated via AICore + Private Compute
Services) and **cannot be bundled in an APK**; ML Kit GenAI APIs are task-scoped,
quota'd, foreground-only, and language-limited
([Android](https://developer.android.com/ai/gemini-nano); [ML Kit GenAI](https://developers.google.com/ml-kit/genai)).
It fails the "everything in the package" requirement outright. Self-hosted open
weights on LiteRT-LM is the only design that meets it.

---

## 4. The ears — speech recognition

### English ASR options

| Engine | Size | Strengths | Limits |
|---|---|---|---|
| whisper.cpp small (q5) | ~250 MB | Robust accuracy | CPU-bound on Tensor; not streaming; Android GPU accel experimental |
| **Moonshine** tiny/base | 26–61 MB | Built for short edge utterances; faster than Whisper on clips | English-only (tiny) |
| sherpa-onnx streaming Zipformer | tens–hundreds MB | **True streaming partials**; **hotword biasing / keyword spotting**; VAD built in | No Hebrew |
| Vosk | ~50 MB | Zero-latency streaming; **JSGF grammar** (constrained recognition) | Dated acoustic models; no Hebrew |

Sources: [whisper.cpp](https://github.com/ggml-org/whisper.cpp);
[Moonshine](https://huggingface.co/UsefulSensors/moonshine) (+ [Flavors of Moonshine](https://arxiv.org/abs/2509.02523), 2025-09);
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ([hotwords](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html), [KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html));
[Vosk](https://alphacephei.com/vosk/).

Speed reference points: a quantized Whisper-tiny transcribed 30 s of audio in ~2 s
on a **Pixel 7** (~15× real-time) ⚠️; WhisperKit runs large-v3-turbo at 5–6×
real-time on iPhone 15 Pro's neural engine — Tensor has no equivalent ANE path, so
plan around **base/small-class models on CPU**
([discussion](https://github.com/openai/whisper/discussions/506); [WhisperKit](https://arxiv.org/abs/2507.10860), 2025-07).

**P1 design**: push-to-talk (no always-on mic — battery, privacy, and Play policy
all agree), utterance-level recognition with **lesson-vocabulary biasing**
(sherpa-onnx hotwords or Vosk grammar). Streaming partials arrive in P3.

### The children's-voice problem (top-3 risk)

- Stock Whisper: **~3% WER on adults vs ~25% on children** — a ~22-point gap
  (shorter vocal tracts, higher F0, unstable articulation, adult-dominated training
  data) ([The Learning Agency](https://the-learning-agency.com/the-cutting-ed/article/how-speech-recognition-systems-struggle-with-childrens-voices/)).
- **Fine-tuning halves it**: Kid-Whisper cut child WER ~50% (Whisper-small
  13.93% → 9.11% on MyST) ([arXiv 2309.07927](https://arxiv.org/abs/2309.07927)).
- **On-device precedent**: WOCCI 2025 fine-tuned Whisper `tiny.en` to 15.9% WER
  (11.8% filtered) running at **RTF 0.23–0.41 on a Raspberry Pi** — a Tensor core
  is much faster ([arXiv 2507.14451](https://arxiv.org/abs/2507.14451)).
- **Licensing catch**: MyST (470 h, grades 3–5 — the main kids corpus) is
  **non-commercial**; PF-STAR (14.2 h, ages 4–14) and CMU Kids (9 h) are small;
  ages 4–5 are under-represented everywhere. A commercial product needs a data
  license or its own (consented, on-device-recorded-with-parental-opt-in? — no:
  simplest is licensed/purchased corpora + synthetic augmentation).
- **Code-switching** (Hebrew kids mixing Hebrew into English turns) raises WER
  30–50% ⚠️ — handled with bilingual biasing lists and lesson design that
  anticipates Hebrew fillers ([Whisper Hebrish](https://huggingface.co/blog/danielrosehill/whisper-hebrish)).

Constrained lesson tasks largely dodge the problem (the recognizer picks among
expected answers); free conversation inherits it — hence the fine-tune in P3.

### Hebrew ASR — defer it

The only serious open Hebrew ASR line is **ivrit.ai's Whisper fine-tunes**; the
mobile-realistic candidate is `whisper-large-v3-turbo` (809 M params, ≈0.8–1 GB
int8; exact WER unverified ❓ — model cards/leaderboard blocked automated reads;
their Interspeech 2025 paper reports up to ~29% error reduction vs prior Hebrew
solutions on a 314 h crowdsourced corpus)
([ivrit.ai](https://huggingface.co/ivrit-ai); [ISCA](https://www.isca-archive.org/interspeech_2025/marmor25_interspeech.html)).
**No small/streaming Hebrew model exists.** Design consequence: the child's
*Hebrew* speech turns are optional and constrained (taps/choices instead), and
full Hebrew ASR waits for P5 hardware/model headroom.

---

## 5. Pronunciation scoring — build it (nobody sells it offline)

ELSA and SpeechSuper — the pronunciation-assessment vendors — are cloud APIs with
no offline mode ([ELSA API](https://elsaspeak.com/en/elsa-api/); [SpeechSuper](https://www.speechsuper.com/)).
The on-device route is classic **GOP (Goodness of Pronunciation)** modernized as
**CTC-GOP**: run a small phoneme-level CTC acoustic model, force-align against the
expected text, score each phoneme from the posteriors. Demonstrated at mobile
scale: a **17 MB** quantized Citrinet-256 doing character/phoneme GOP entirely
on-device ([writeup](https://dev.to/fabiosuizu/17mb-vs-12gb-how-a-tiny-model-beats-human-experts-at-pronunciation-scoring-5588));
active research through 2025–26 ([CTC-GOP+phonology](https://arxiv.org/html/2506.02080v2),
[logit-GOP](https://arxiv.org/html/2506.12067v2), [NOCASA 2025](https://arxiv.org/html/2509.03256v1)).
torchaudio's wav2vec2 forced-alignment exports to ONNX for mobile.

Scope honestly: phoneme-level red/amber/green plus targeted tips ("your *th*") —
useful and demo-able; not ELSA-grade prosody analytics. Hebrew-L1 focus: θ/ð, w/v,
short-i vs long-e (ship/sheep), final consonant devoicing, /r/ quality.

---

## 6. The mouth — text-to-speech

### English — solved

| Engine | Size | Quality/speed |
|---|---|---|
| **Piper** (safe default) | medium voice ~63 MB | CPU-only, RTF ~0.2 on a Raspberry Pi 4 → effectively instant on Tensor |
| **Kokoro-82M** (quality pick) | <80 MB int8 | RTF ~0.62 on Android ⚠️, ~833 MB RAM, noticeably more natural; sherpa-onnx support |
| KittenTTS | <25 MB | 2026 newcomer, faster than Kokoro, English-only |

No open-source purpose-built child voice exists — use a bright/clear voice +
`length_scale` slow-down (both engines support it), and pre-render fixed phrases.
([Piper](https://github.com/rhasspy/piper); [Kokoro/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/discussions/3383); [KittenTTS](https://github.com/KittenML/KittenTTS)).

### Hebrew — the gap that now has a path

Hebrew text is written **without vowels (nikud)**; TTS must restore them first.
This killed most options:

- Official Piper voices: **no Hebrew exists** ([VOICES.md](https://github.com/rhasspy/piper/blob/master/VOICES.md)).
- Meta MMS-TTS-heb: runs on-device but **CC-BY-NC** — blocked for a commercial app.
- Robo-Shaul/SASPEECH: dataset non-commercial; HebTTS (2024): great quality, too
  big/autoregressive for phones.

**The viable stack — Phonikud** ([arXiv 2506.12311](https://arxiv.org/abs/2506.12311), 2025-06;
[repo](https://github.com/thewh1teagle/phonikud)): a lightweight Hebrew
G2P that does **diacritization + phonemization in one int8 ONNX model**
(**CC-BY-4.0** — commercial use with attribution), paired with
[phonikud-tts](https://github.com/thewh1teagle/phonikud-tts) Piper/VITS Hebrew
voices (20–32 M params, trained on SASPEECH + ILSpeech). This is "Piper for
Hebrew," fully offline, bundle-friendly.

**Belt and suspenders**: all *fixed* Hebrew instruction lines ship as
**pre-recorded human audio** (best quality a child will hear, zero model risk);
Phonikud handles *dynamic* Hebrew only. Android's system TTS is not a fallback:
an offline Hebrew system voice is not reliably present, is user-downloaded, and
can't be bundled ❓ — never depend on it.

---

## 7. Packaging & distribution — the headline constraint

Google Play numbers (mid-2026):

- Base module (first download): **≤200 MB** compressed.
- **Per-device cumulative download ≈4 GB** — the number that matters, since every
  device needs all our weights. (Aggregate app-total limits rose to 8 GB — and
  34 GB for games in the Level Up program — but a single device still pulls ~4 GB.)
  ⚠️ Exact current per-pack figures should be re-verified on the live Play Console
  help page (automated reads were blocked).
- Install-time asset packs historically ~1 GB combined ⚠️; on-demand packs stretch
  the budget — worst case, models fetch on first launch instead of during install.
- Precedent check: **no known Play app ships multi-GB weights in the install
  artifact** — Google's own AI Edge Gallery is a 121 MB APK that downloads models
  (0.5–4 GB) at runtime. We'd be early, inside the rules.
  ([App Bundle FAQ](https://developer.android.com/guide/app-bundle/app-bundle-faq); [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery))

### The budget — it fits

| Component | Pick | Size |
|---|---|---|
| LLM | Gemma 4 E2B int4 `.litertlm` | 2.58 GB |
| English ASR | Whisper-small q5 (or Moonshine base, 62 MB) | 250 MB |
| English TTS | Kokoro-82M int8 (or Piper medium, 63 MB) | 80 MB |
| Hebrew TTS | Phonikud int8 + Piper-class Hebrew voice | ~60–100 MB |
| Pronunciation | phoneme-CTC scorer | ~20 MB |
| VAD | Silero | ~2 MB |
| Content | units, pre-recorded Hebrew audio, art | 300–500 MB |
| App + runtimes | Compose app, LiteRT-LM, sherpa-onnx | ~150 MB |
| **Total** | | **≈3.4–3.7 GB ✅ under 4 GB** |

**E4B (3.66 GB) does not fit** alongside the speech stack → optional post-install
add-on for 16 GB devices, or the direct-APK SKU (sideload has no size policy;
ZIP64/split APKs; genuinely never-online — also the enterprise/school channel).

Model/content updates ship as app updates (Play delta-patches asset packs) — no
runtime downloads needed, keeping the offline story intact.

---

## 8. How well will it work? (latency & quality budget)

### Voice-turn latency, Pixel 10 Pro XL, CPU baseline, pipelined

| Stage | Budget | Basis |
|---|---|---|
| Child speaks (push-to-talk) | 2–4 s utterance | — |
| ASR on utterance | **0.5–2 s** | Moonshine/Whisper-small class on flagship CPU ⚠️ |
| LLM first token | **~1–3 s** | short cached system prompt, prefill fast |
| LLM decode (30–60 token reply) | 2–5 s streamed | 10–25 tok/s CPU ⚠️ |
| TTS first audio | **+0.5–1 s** | sentence-streamed Piper/Kokoro (faster than real-time) |
| **Silence gap child hears** | **≈2–4 s** (worst ~6 s cold) | TTS starts on first sentence while LLM continues |

That's the cadence of a thoughtful human tutor, absorbed by a "Tuki is thinking"
animation. Text-tutor mode streams at/above reading speed. TPU acceleration
(Pixel 10) and MTP decode are upside, not dependencies.

### Quality expectations, candidly

- **Structured lesson speech (P1–P2): polished.** Constrained recognition
  neutralizes most of the kids-ASR penalty; templated LLM feedback is reliable.
- **Free conversation (P3): works with rough edges.** A 2B-effective model is a
  simple, patient, occasionally clunky English partner — right for A1/A2 practice
  inside roleplay rails; not a native-speaker-grade tutor. Forgiving dialogue
  design (confidence gating, "let's try again") covers ASR misses.
- **Pronunciation: useful, not ELSA-grade.** Phoneme-level guidance, honest tips.
- **Hebrew voice: good enough with the human-audio complement**; dynamic Hebrew
  TTS is the quality floor of the audio experience.
- **Sessions**: 15–20 min fits thermal and attention budgets alike; battery cost
  per session est. **3–6%** ⚠️ (turn-based duty cycle, models unloaded after).

---

## 9. Kids' safety & compliance (on-device ≠ exempt)

- **Play's GenAI rules apply regardless of where inference runs**: prevent
  restricted content, input/output safety filters, documented safety testing /
  red-teaming, **in-app report/flag mechanism**, AI disclosure; enforcement is
  stricter for child-targeted apps (2025-01 update; 2026-07-15 Families tightening)
  ([AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/14094294); [safeguards](https://support.google.com/googleplay/android-developer/answer/16353813)).
- **Families program**: target-age declaration, IARC rating, privacy policy +
  Data-safety form (declaring *zero collection*), no personalized ads (we have no
  ads), parental gate for settings/purchases
  ([Families policy](https://support.google.com/googleplay/android-developer/answer/9893335)).
  **Teacher Approved** review unlocks the Kids tab — a distribution moat worth the
  extra bar.
- **Our safety architecture** (per-layer, all offline): curated topic templates →
  strict kid-tutor system prompt → constrained decoding where possible → output
  filter (blocklists + small on-device classifier) → session topic fencing →
  report button + local incident log (parent-visible). Red-team evidence is a
  release artifact, not an afterthought.
- **Privacy law**: COPPA (2025 rule, compliance 2026-04-22) barely applies when
  nothing is collected — the strongest marketing claim in the category. Israel's
  Privacy Protection Law Amendment 13 (in force 2025-08-14) similarly mostly
  inapplicable to a zero-collection app (confirm with counsel ❓). EU AI Act
  education-context obligations (from 2026-08-02) matter only for EU expansion.

---

## 10. Competition & positioning

- **Kids' language apps** (Duolingo ABC, Khan Academy Kids, Lingokids): offline =
  pre-downloaded static content; **none does generative tutoring or conversation**.
- **Israel specifically**: Novakid is the incumbent (Israel is its 3rd-largest
  market; its Junior app targets ages 2–6) — **cloud-based, live-teacher-centric**.
  Its NovaPals AI-conversation app (2026-04) targets **ages 13+, cloud**.
- **On-device LLM tutoring**: research prototypes (e.g., offline programming
  tutors, UMass/ICALT 2025) and general-purpose local-LLM apps exist; **no shipped
  child-directed + fully-offline + generative product was found**.

**Positioning**: "the English tutor that lives inside the phone" — works on
flights and anywhere without connectivity, zero data leaves the device (the
strongest possible parental-trust story), one-time purchase potential without
server costs scaling per user.

---

## 11. Top risks & mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | Kids' ASR accuracy off-script | High | Constrained-first design; P3 fine-tune on commercially-licensed child speech; confidence-gated retry UX; code-switch biasing |
| 2 | LLM Hebrew quality unproven ❓ | High | P1 gate: self-run Hebrew eval (Gemma 4 E2B vs Phi-4-mini); English-first tutor voice; fixed Hebrew via humans/Phonikud |
| 3 | Play delivery ceiling (~4 GB/device) | Medium | Budget discipline (§7); on-demand pack fallback; direct-APK SKU; E4B as add-on |
| 4 | Thermal/battery on long sessions | Medium | Turn-based architecture, session caps, engine unload; matches child attention spans anyway |
| 5 | Play GenAI-for-kids review bar | Medium | Safety layers + documented red-teaming from P1; report flow; Teacher Approved track |
| 6 | Hebrew TTS quality perception | Medium | Pre-recorded human audio for all fixed lines; Phonikud only for dynamic text; parent-audible quality demo pre-purchase |
| 7 | Pixel TPU SDK immaturity (AOT-only Beta) | Low | CPU baseline is sufficient (§8); TPU is upside |
| 8 | Kids corpus licensing (MyST non-commercial) | Medium | License negotiation or alternative corpora + augmentation; never train on users' audio |

---

## 12. Bottom line

The concept survives contact with the evidence. The 2025–2026 stack (Gemma 4 +
LiteRT-LM + mature tiny ASR/TTS + Phonikud) makes a **fully-offline, genuinely
conversational, safety-bounded English tutor for Hebrew-speaking kids** buildable
on Pixel 9/10-class hardware, inside Google Play's delivery rules, with a
2–4-second voice loop and a ~3.6 GB footprint. The risks that remain are *product
engineering* risks (kid ASR tuning, Hebrew eval, safety casework) — not "does the
technology exist" risks. Phase plan: [product-phases.md](product-phases.md).
