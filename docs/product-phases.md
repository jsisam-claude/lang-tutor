# Product Spec — Feature List in 5 Development Phases

Companion to [feasibility.md](feasibility.md). Each phase is a shippable product,
not a milestone label: the speech loop works from Phase 1. Phases are the spine;
the **learner curriculum ladder** is the content track that grows across them.

---

## The curriculum ladder (5 learner levels, ages 4–13)

A placement test (P3; heuristic onboarding quiz in P1) puts each child on the
ladder; every activity is tagged with a level. Mapping to CEFR and to the Israeli
MoE English bands (English study formally starts ~grade 3, trending earlier):

| Level | Name (HE) | Ages ≈ | CEFR | Focus |
|---|---|---|---|---|
| **L0** | מקשיבים ומדברים (Listen & Speak) | 4–6 | pre-A1 oral | Oral vocab, TPR-style games, phonemic awareness, songs/chants. **Zero reading dependency**; all instructions in Hebrew (pre-recorded). |
| **L1** | אותיות וצלילים (Letters & Sounds) | 5–8 | pre-A1 literacy | Alphabet, systematic phonics, CVC decoding, sight words, letter formation. |
| **L2** | קוראים ומספרים (Read & Tell) | 7–10 | ~A1 | Decodable readers with synced audio, simple sentences, first writing, guided conversation. |
| **L3** | צומחים באנגלית (Growing) | 9–12 | A1+/A2 | Grammar patterns, paragraphs, freer conversation, school-curriculum alignment. |
| **L4** | שולטים! (Confident) | 11–13 | A2→B1 | Fluent roleplay, essays with feedback, idioms, listening to natural-speed speech. |

Hebrew-L1 transfer focus threads through all levels: θ/ð ("th"), w/v, short-i vs
long-e (ship/sheep), /æ/, final-devoicing, SVO word-order habits, false friends.

---

## The learning-science spine (what every feature hangs on)

- **Comprehensible input + TPR at L0**: listen–point–do before speak; songs and
  chants; no pressure to produce until the child volunteers.
- **Systematic synthetic phonics** at L1–L2, sequenced against Israeli MoE
  English expectations — plus **Hebrew-script contrast lessons** unique to this
  audience: left-to-right reading-habit training, mirror-letter confusion
  (b/d, p/q), and "English writes its vowels" orientation work.
- **Retrieval practice + FSRS-scheduled spaced repetition** — one scheduler,
  many item types (words, phonemes, sight words, grammar patterns), so weak
  pronunciation phonemes and lapsed vocab compete fairly for session time.
- **Recast-only correction** (repeat it correctly, never "wrong!"), one question
  per tutor turn, interleaved review/new content.
- **Session ritual** (P1 onward): warm-up review → new input → production
  moment ("say it to Tuki") → reward. Predictable structure is the retention
  engine for young kids; features slot *into* the ritual, not around it.
- **Motivation by age band, one economy, three skins**: 4–6 care-loop (feed and
  decorate Tuki), 7–9 collections/streaks/story worlds, 10–13 progress stats
  and self-set goals. No dark patterns: streak freezes are free, no loss
  framing for children.
- **Learner event log from day one**: append-only on-device record of every
  exposure/attempt/outcome with a versioned schema. Adaptivity is simple in
  P1, but the *history* accumulates immediately — you cannot retrofit data you
  never stored.

## Cross-cutting workstreams (run inside every phase)

| Workstream | How it grows |
|---|---|
| **Content ops** | Authoring scripts + schema migrations; native-speaker EN and HE studio audio; MoE-alignment QA checklist. Unit counts: P1 ~10 → P2 ~40 → P3 ~90 → P4 ~150 → P5 250+ (full band coverage). |
| **Tutor eval & QA** | Recorded child-utterance replay corpus (ages, accents, noise) as an ASR regression suite; tutor-reply rubric evals; on-device latency/battery/thermal macrobenchmarks; safety red-team suite grown every release (a release artifact, per Play GenAI-for-kids policy). |
| **Trust & accessibility** | P1: parent gate + **airplane-mode onboarding trick** ("turn on airplane mode now — Tuki keeps working"). P2: dyslexia-aware fonts, large type, TalkBack on core flows. P4: "what Tuki remembers" transparency screen + local parent digests. P5: full accessibility audit. |
| **Degradation ladder** | Full speech loop → scripted-TTS mode (templates, no LLM) → text-only; switched by thermal state, RAM pressure, and model-file integrity checks. Skeleton in P1, hardened every phase — also the answer to "what does a demo do on a hot phone". |
| **Measurement without telemetry** | All metrics computed on-device; pilot data leaves the device only via explicit parent-mediated export (QR/file share). No ambient analytics, ever — the zero-collection claim stays literally true. |

---

## Phase 1 — "Talking MVP" 🎙️

**Goal**: prove the core magic — a child speaks English to the phone and it
answers, with zero network — inside a real (small) curriculum.
**Levels unlocked**: L0 complete + L1 partial. **Target devices**: Pixel 9+ gate
(12 GB RAM minimum, runtime-checked).
**Audience honestly served**: ~4–8 (the L0–L1 band). The 9–13 experience only
becomes real in P3+ — pilots and marketing should say so.
**Stage gate — *do kids actually talk to it, and come back?*** Targets: ≥60% of
prompted turns attempted by voice; D7 return ≥35% in a ~20-family pilot; zero
safety-filter escapes.

### Features

**Speech loop (the headline)**
- Push-to-talk mic (big, hold-to-speak) with recording indicator and playback.
- On-device English ASR (Whisper-small or Moonshine-base) with **lesson-vocabulary
  hotword biasing**; confidence-gated flow — low confidence → warm "let's try
  again", never an error screen; audio never persisted beyond the turn.
- On-device LLM tutor (Gemma 4 E2B on LiteRT-LM, CPU baseline): templated tutor
  moves — praise, recast correction, next question — inside per-activity prompt
  templates (not free chat yet); English output, child-appropriate register
  enforced by system prompt + output filter.
- English TTS (Piper or Kokoro): normal + 🐢 slow-clear mode; word highlighted as
  spoken where shown.
- Turn state machine with visible states (listening / thinking / speaking) and a
  2–4 s latency budget, TTS sentence-streaming.

**Text tutor (dual channel from day one)**
- Chat pane running the same tutor policy via keyboard input (skips ASR).
- Tap-to-hear any English word/sentence anywhere in the UI.

**Curriculum & content**
- ~10 units for L0/early-L1 (colors & toys, animals, family, food, body, numbers,
  greetings…): vocab cards (image + word + Hebrew hint + audio), listen-and-choose,
  repeat-after-me, simple guided Q&A.
- Content pipeline: versioned local JSON schema + asset packs (art, human-recorded
  Hebrew + English audio for all fixed lines).
- Heuristic onboarding: age + "does the child read English?" → starting level.

**Hebrew-first UX**
- Full RTL Hebrew UI; **pre-recorded human Hebrew audio** for every fixed
  instruction (critical for pre-readers); Phonikud Hebrew TTS bundled for dynamic
  Hebrew lines; per-app language toggle (he/en) in Parent Zone.

**Foundation & trust**
- **No INTERNET permission** (manifest-enforced). All models via install-time
  asset packs; payload ≈3.0–3.5 GB.
- Parent gate (multiplication challenge) → settings, daily time limit, profile
  reset; single child profile; XP/stars/streak (light, no dark patterns).
- Safety v1: curated topics only, output blocklist filter, in-app report button
  writing a parent-visible local log. Session length soft-cap with a "Tuki is
  tired" break screen (doubles as thermal guard).
- **P1 gate task**: self-run Hebrew eval — Gemma 4 E2B vs Phi-4-mini on
  tutor-domain Hebrew prompts, scored by native speakers, before locking the model.
  **Pre-decided contingency**: if both fail the bar, all *dynamic* Hebrew
  generation is cut (templates + human audio only) — the product still ships.

**Habit & resilience (added on deeper review)**
- **Session ritual v1**: every day has the same shape — warm-up review → new
  input → speak-to-Tuki moment → reward — with a Tuki care-loop (feed/decorate)
  as the 4–6 reward skin.
- **Degradation ladder v0**: thermal/RAM/model-integrity checks drop the app to
  scripted-TTS or text-only mode instead of failing.
- **Learner event log v1**: versioned, append-only record of every
  exposure/attempt/outcome — the substrate all later adaptivity feeds on.
- **Scope honesty**: ~10 units is 2–3 weeks of daily content. P1 is a
  pilot/beta, not a public launch; P2's SRS arcade modes are what stretch
  authored content between drops.

**Exit criteria**: voice turn ≤4 s p50 on Pixel 10 / ≤6 s on Pixel 9; task-success
≥90% on constrained activities in a 10-child pilot; zero safety-filter escapes in
red-team suite v1; 20-min session without thermal throttle on Pixel 9.

---

## Phase 2 — "Literacy Engine" 📖

**Goal**: teach reading and writing properly — the text tutor becomes central.
**Levels unlocked**: L1 complete + L2.
**Stage gate — *does it measurably teach reading?*** Targets: CVC decoding
accuracy 60%→85% over a 6-week pilot; ≥50% of sessions completed without parent
help for ages 6+.

### Features

- **Phonics ladder**: letter–sound units in research-based sequence (s-a-t-p-i-n…),
  blending drills (tap-to-blend c-a-t), minimal pairs listening games.
- **Letter formation**: finger tracing with on-device stroke matching (template
  matching — deliberately no ML Kit digital-ink dependency, which requires Play
  services downloads); lowercase/uppercase; left-to-right writing habits for
  RTL-native kids.
- **Word building**: drag letter tiles to spell heard words (constrained-ASR
  dictation twin: child *speaks* the word they built).
- **Sight words** + **SRS vocabulary**: spaced-repetition queue mixing new/lapsed
  words into every session; parent-visible mastery list.
- **Decodable readers**: 20+ mini-books per level band with word-level audio sync
  (karaoke highlight), tap-any-word glossary, and comprehension Q&A run by the
  tutor (spoken or typed answers).
- **Spelling & dictation**: tutor speaks → child types/builds; gentle error
  highlighting (letter-level diff).
- **LLM as exercise generator**: cloze and variation exercises generated on-device
  *within templates* and validated against the unit's vocab list before display
  (generation stays inside safety rails).
- **Dyslexia-aware options**: letter-spacing, larger type, optional
  dyslexia-friendly font (bundled), audio-first mode.
- Handwriting/typed writing feedback v1: word-order and spelling recasts for
  1–2-sentence answers.
- **Lightweight multi-child profiles** (2–3, fast local switch) — pulled forward
  from P5: Israeli siblings share devices; full family features stay in P5.
- **Tap-to-talk with VAD auto-stop** as an age/accessibility option —
  hold-to-talk is motorically hard at 4–6 (the VAD model is already bundled).
- **SRS arcade mini-games**: procedurally recombined review that stays fresh
  between authored content drops (the answer to the P1 content cliff).
- **ASR-data spike (pulled forward from P3)**: child-speech data licensing +
  first fine-tune experiment now, so P3's conversation work isn't blocked on
  data acquisition — the two riskiest tech items no longer land together.
- **Content authoring tooling v1**: scripts + validators for the unit schema
  (the content-ops workstream's first real tooling).

**Exit criteria**: a non-reading 6-year-old can progress L1→L2 start without a
parent reading instructions aloud; SRS retention metrics live; readers' audio sync
frame-accurate on Pixel 9.

---

## Phase 3 — "Conversation & Pronunciation" 💬

**Goal**: free(er) conversation that stays safe, plus per-phoneme pronunciation
feedback — the features no offline competitor has.
**Levels unlocked**: L2 solidified + L3.
**Stage gate — *can free conversation stay safe and fun for 10+ turns?***
Targets: median free-talk length ≥10 child turns; zero red-team escapes and
<0.1% pilot-flagged replies; off-script kid-ASR WER ≤12%; voice-turn latency
p50 ≤3 s (streaming ASR + Tensor TPU path — the SLO tightens from P1's 4 s).

### Features

- **Guardrailed free conversation**: roleplay scenes (shop, birthday, pets,
  school…) with scene-scoped topics; tutor keeps one-question-at-a-time cadence;
  recast corrections logged to the learner model.
- **Pronunciation scoring (in-house CTC-GOP)**: ~20 MB phoneme model; per-phoneme
  red/amber/green, Hebrew-speaker tip library (th, w/v, ship/sheep…), listen/slow/
  retry loop; weak phonemes feed the SRS queue.
- **Kid-tuned ASR**: fine-tuned Whisper-class model on commercially-licensed child
  speech (MyST is non-commercial — budget a data license or alternatives +
  augmentation); code-switch biasing for Hebrew fillers.
- **Streaming ASR upgrade** (sherpa-onnx Zipformer): live partial transcripts
  during speech; keyword-spotting mode for L0 games.
- **Placement test** (adaptive, 5–8 min, speech + text) replacing the heuristic
  onboarding; periodic re-calibration.
- **Adaptive difficulty**: per-skill mastery estimates (vocab, phonics, listening,
  speaking) drive activity selection.
- **Gemma 4 audio-input experiment**: feed child audio directly to the LLM
  (single-model loop) for conversation mode; A/B against the ASR pipeline for
  latency/quality — promote only if it wins.
- **Safety v2** (Play GenAI-for-kids grade): small on-device input/output safety
  classifier alongside blocklists; topic fence telemetry (local); red-team suite
  v2 as a release artifact; AI-disclosure copy for parents; refined report flow.
- **School-textbook alignment mode**: the parent picks the school's English
  series; Tuki aligns weekly topics via bundled mapping tables — fully offline,
  and a strong Israel-market differentiator.
- **Voice-flow upgrades for all tiers** (detailed in the Advanced-track section):
  hands-free VAD endpointing, barge-in via echo cancellation, backchannel
  latency masking → perceived gap ~1–1.5 s.

**Exit criteria**: 10-turn coherent roleplay conversations rated ≥4/5 by parents
in pilot; pronunciation scores correlate with human teacher ratings (r ≥ 0.7 on a
validation set); kid-ASR WER ≤ ~12% on off-script pilot speech.

---

## Phase 4 — "Tutor Intelligence" 🧠

**Goal**: from "app with an LLM" to "tutor that knows this child".
**Levels unlocked**: L3 solidified + L4.
**Stage gate — *does personalization beat the P3 baseline?*** Targets: D30
retention +20% and vocabulary growth/week +25% vs the P3 cohort
(parent-exported pilot data); latency SLO tightens to p50 ≤2.5 s on Pixel 10+.

### Features

- **Persistent learner model**: per-child memory of vocab mastery, recurring
  errors, interests (favorite animals → story topics), pace; stored locally,
  parent-wipeable; injected into tutor prompts as compact context.
- **Hebrew-L1 error diagnosis library**: rule+model hybrid that recognizes
  transfer errors (word order, dropped copula "he happy", tense calques, false
  friends) and picks targeted micro-lessons.
- **Generated stories & lessons within rails**: personalized decodable stories
  (child's name, interests, mastered vocab + 5% stretch), validated by the safety
  layer and vocabulary checker before display; illustrated from a bundled art
  library.
- **Writing feedback v2**: paragraph-level feedback for L3/L4 (structure, tense
  consistency, connectors) with track-changes-style display.
- **Listening comprehension**: natural-speed dialogs (multi-voice TTS) with
  comprehension checks.
- **Offline parent reports**: weekly on-device report (minutes, words, phoneme
  progress, error trends) with optional manual PDF export; nothing transmitted.
- **E4B quality tier**: optional post-install add-on (or direct-APK SKU) for 16 GB
  devices — better generation quality where RAM allows; runtime model picker.
- Thermal/battery adaptive scheduler: decode-rate throttling and TTS pre-render
  when device is warm.
- **"What Tuki remembers" screen**: parent-visible learner memory with per-item
  delete — transparency that doubles as data-minimization evidence.
- **Model lifecycle discipline**: candidate ASR/LLM models shadow-evaluated
  on-device (in-RAM comparison against the incumbent; audio never persisted)
  before any swap; delta-friendly pack layout so refreshes patch small instead
  of re-shipping gigabytes.
- **Advanced Pack** (on-demand, 16 GB devices, ~4.5 GB): E4B/8B-class
  conversation model, verbatim learner ASR, speech-aware GEC, topic packs for
  grounded discussion, prosody scoring, audio feedback cards — the full
  voice-accuracy stack for B2-bound learners (see the Advanced-track section).

**Exit criteria**: measurable learning gains in a term-length pilot (pre/post
vocabulary + reading fluency); story generator passes 100% of safety/vocab
validation on 10k generations; parent-report comprehension in user tests.

---

## Phase 5 — "Scale & School" 🏫

**Goal**: from one child on one Pixel to families, classrooms, and new markets.
**Stage gate — *does it scale beyond the family device?*** Targets: classroom
pilots in ≥2 schools with teacher-adoption commitment; a second L1 shipped at
<15% incremental content cost (proving the L1-parametric architecture).
**Levels**: all levels mature + content depth (full L0–L4 coverage of Israeli MoE
band expectations).

### Features

- **Multi-child profiles** with per-child models and quick switching (still no
  accounts — device-local).
- **Classroom/teacher mode**: teacher device dashboard via local export/import
  (QR/file share — still offline); class content packs; Hebrew teacher guides.
- **Additional L1s**: Arabic, Russian, French UI + scaffolding + transfer-error
  libraries (architecture is L1-parametric from P1: all Hebrew-specific logic
  behind an interface).
- **Hebrew ASR turns** (finally): ivrit.ai turbo-class model as an optional pack
  on high-RAM devices for L0 children answering in Hebrew; constrained Hebrew
  fallback elsewhere.
- **Model refresh pipeline**: quarterly model/content updates as app updates
  (Play delta-patches asset packs); eval harness gating every model swap.
- **Device expansion**: tablets/foldables layouts (great for classrooms), broader
  Android flagship support (Snapdragon NPU path via LiteRT delegates — currently
  *better* accelerated than Pixel), min-spec probe with graceful "lite mode"
  (smaller LLM) on 8 GB devices.
- **Distribution**: Google Play (Families + **Teacher Approved** certification),
  direct-APK "truly zero network" SKU for schools/enterprise, storage-aware
  installer UX.
- **Accessibility**: TalkBack audit, motor-accessibility for tracing games,
  captions everywhere.

**Exit criteria**: Teacher Approved badge obtained; classroom pilot (2+ schools);
second L1 shipped; crash-free ≥99.8%; model update shipped through the pipeline
end-to-end.

---

## Advanced track: voice conversation & language accuracy

How the same on-device stack stretches to serve advanced (B2-bound) learners —
specifically for *spoken* conversation and *correct* language use. Ships as an
**on-demand Advanced Pack (~4.5 GB, 16 GB devices)**; the flow improvements land
in P3 for every tier, the accuracy stack in P4.

### Making voice feel like conversation (P3, all tiers)

- **Hands-free endpointing** replaces push-to-talk for older learners: open mic
  during the activity, Silero VAD + a tiny "utterance semantically complete?"
  classifier on the streaming transcript — saves ~500 ms of silence-timeout per
  turn.
- **Barge-in**: the learner can interrupt Tuki. Feasible offline because the TTS
  signal is known — acoustic echo cancellation subtracts it, VAD detects learner
  onset, playback pauses.
- **Latency masking**: a rule layer emits a natural backchannel ("Mm, right…")
  within ~300 ms while the LLM decodes behind it; streaming ASR runs *during*
  speech (final transcript ~200 ms after they stop) and prefill starts on
  stabilized partials. Real reply latency stays 2–4 s; **perceived** gap drops
  to ~1–1.5 s.
- **Long-form speech**: 30–60 s monologues ("describe your weekend") need
  streaming transducers, not 30 s-chunk batch models — another reason the P3
  streaming-ASR upgrade is the pivot.

### Correct use of language, by voice (P4, Advanced Pack)

- **The trap: standard ASR silently fixes learner errors.** A strong language
  model prior transcribes "he go to school" as "he *goes* to school" —
  destroying the evidence a grammar coach needs. Countermeasures: a
  **verbatim-tuned learner-speech ASR** (error-faithful transcripts;
  transducer architectures are also less LM-dominant than Whisper-style
  decoders), plus…
- **Acoustic morphology verification**: the forced-alignment/GOP model doubles
  as a grammar witness — did the /z/ of "goes" or the /d/ of "played" actually
  occur in the audio? Dropped inflections are precisely the Hebrew-speaker
  error class, and this catches them even when ASR autocorrects.
- **Speech-aware GEC specialist** (~200 MB): grammatical-error correction on the
  verified transcript, trained on spoken norms (fragments are fine in speech;
  article overuse, present-perfect avoidance, and calques are not).
- **Prosody & fluency scoring** via DSP + alignment (no large models): word and
  sentence stress (Hebrew final-stress transfer), intonation contours, speech
  rate, pause distribution, filled-pause counts.
- **Correction policy tuned for voice**: at most one natural recast per turn
  mid-conversation (fluency first); the full accuracy debrief arrives as a
  **post-session card with audio** — the learner's own clip played against the
  reference voice, per error. Self-hearing is the strongest correction signal,
  and it never leaves the device.
- **Accuracy drills that barely need the LLM**: shadowing (speak along, scored
  on phones + timing), read-aloud with live word highlighting, minimal-pair
  production tests.
- Plus the general advanced levers (see P4): role-fine-tuned conversation model,
  grounded discussion over bundled topic packs (on-device RAG), rolling
  conversation summaries for long-session coherence, E4B/8B-class model tier.

**Honest ceiling**: even with all of this, a free-roaming, culturally fluent C1
companion is beyond 2026 on-device models. *Structured* B2 practice — grounded
discussion, debates, precision feedback, natural-speed listening — is reachable,
and it is most of what an advanced learner pays a tutor for. The model tier
rides the fastest-improving curve in the stack; the Advanced Pack is designed to
swap models without touching the app.

## Stage gates, audience expansion, and rough sizing

| Phase | Kill/pivot question | Audience actually served | Rough effort (planning-grade estimate) |
|---|---|---|---|
| P1 | Do kids talk to it — and return? | ~4–8 | ~4–5 months, ~5 people (2 Android, 1 ML, 1 content, 1 design) |
| P2 | Does it measurably teach reading? | 4–10 | +3–4 months |
| P3 | Is free conversation safe + fun for 10+ turns? | 4–12 | +4–5 months (ML-heavy) |
| P4 | Does personalization beat P3? | 4–13 | +4 months |
| P5 | Does it scale beyond the family device? | 4–13 + classrooms | ongoing |

Effort figures are estimates, not commitments. The audience column is the
honest expansion track: each phase should be piloted and marketed to the band
it actually serves, not the 4–13 end-state.

## Phase → payload evolution

| Phase | On-device additions | Est. total payload |
|---|---|---|
| P1 | Gemma 4 E2B, EN ASR, EN+HE TTS, VAD, 10 units | ~3.0–3.5 GB |
| P2 | Phonics/reader content + art/audio, dyslexia font | +300–400 MB |
| P3 | Kid-ASR fine-tune (replaces base), streaming ASR, GOP scorer, safety classifier | +150–250 MB |
| P4 | Learner-model logic (code), story art library; optional E4B add-on | +200 MB (+3.7 GB opt.) |
| P5 | Per-L1 packs, optional Hebrew ASR pack (~1 GB), tablet assets | modular, on-demand packs |

Play's ~4 GB per-device budget holds through P3 with content discipline; P4+
optional packs use on-demand delivery or the direct-APK channel.
