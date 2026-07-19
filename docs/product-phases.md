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

## Phase 1 — "Talking MVP" 🎙️

**Goal**: prove the core magic — a child speaks English to the phone and it
answers, with zero network — inside a real (small) curriculum.
**Levels unlocked**: L0 complete + L1 partial. **Target devices**: Pixel 9+ gate
(12 GB RAM minimum, runtime-checked).

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

**Exit criteria**: voice turn ≤4 s p50 on Pixel 10 / ≤6 s on Pixel 9; task-success
≥90% on constrained activities in a 10-child pilot; zero safety-filter escapes in
red-team suite v1; 20-min session without thermal throttle on Pixel 9.

---

## Phase 2 — "Literacy Engine" 📖

**Goal**: teach reading and writing properly — the text tutor becomes central.
**Levels unlocked**: L1 complete + L2.

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

**Exit criteria**: a non-reading 6-year-old can progress L1→L2 start without a
parent reading instructions aloud; SRS retention metrics live; readers' audio sync
frame-accurate on Pixel 9.

---

## Phase 3 — "Conversation & Pronunciation" 💬

**Goal**: free(er) conversation that stays safe, plus per-phoneme pronunciation
feedback — the features no offline competitor has.
**Levels unlocked**: L2 solidified + L3.

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

**Exit criteria**: 10-turn coherent roleplay conversations rated ≥4/5 by parents
in pilot; pronunciation scores correlate with human teacher ratings (r ≥ 0.7 on a
validation set); kid-ASR WER ≤ ~12% on off-script pilot speech.

---

## Phase 4 — "Tutor Intelligence" 🧠

**Goal**: from "app with an LLM" to "tutor that knows this child".
**Levels unlocked**: L3 solidified + L4.

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

**Exit criteria**: measurable learning gains in a term-length pilot (pre/post
vocabulary + reading fluency); story generator passes 100% of safety/vocab
validation on 10k generations; parent-report comprehension in user tests.

---

## Phase 5 — "Scale & School" 🏫

**Goal**: from one child on one Pixel to families, classrooms, and new markets.
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
