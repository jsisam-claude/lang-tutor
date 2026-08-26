# Learner tracks — one spine, four tracks

Decision record (2026-08-27): how the tutor serves audiences beyond the
original young-child focus, what varies per audience, and the concrete next
step for Hebrew explanations. Nothing here is built yet except where noted;
this is the plan the code grows toward.

## The five user types collapse into four tracks

Types 2 and 4 (young adults / adults who never learned English) differ in
theming and pace, not pedagogy — one track, two skins.

| Track | User types | Modality mix | Feedback style | Hebrew's role |
|---|---|---|---|---|
| **A · Pre-reader** | 1 — young kids, pre-alphabet | Audio + big images + animation; **no functional text** (karaoke highlighting is decoration to a pre-reader) | Recasts only, never explicit correction; praise effort | **Spoken** Hebrew only — Hebrew *text* is worthless to a child who cannot read Hebrew either |
| **B · Beginner** | 2, 4 — never learned / forgot | Bilingual text + audio + images; animation sparingly (adults read it as childish) | Recast plus a one-line "why", on request | Hebrew text explanations, fading as level rises |
| **C · Exam / academic** | 3 — bagrut / college prep | Text-first, minimal art; timed reading, writing feedback | Explicit metalinguistic — name the rule, cite the pattern | Hebrew *metalanguage* for grammar |
| **D · Improver** | 5 — learned, wants better | Conversation-first, text as transcript | Recasts + register/idiom notes | Escape hatch only, on request |

Pedagogical grounding, briefly: comprehensible input slightly above level
(i+1) for everyone; TPR-style "point and do" for pre-readers; **recasts for
children but explicit correction for adults** — the SLA research splits
exactly there (explicit grammar feedback measurably helps adults and mostly
bruises young children's willingness to speak); low affective filter
throughout, which the XP/praise design already serves. Caveat: this table is
literature-reasoned, not user-tested — the eval harness in `eval/` can A/B
persona prompts per track the same way it graded E4B, and should, before the
tracks are treated as settled.

## The levers (most already exist as code, hardcoded for one audience)

- **Persona register** — Tuki-the-parrot is right for A, patronizing for C.
  Same engine, different system prompt (and possibly skin).
- **Reply budget** — already age-scaled (48 tokens for AGES_4_6, else 96);
  generalize to track-scaled.
- **Hebrew-scaffold ratio** — a dial from "Hebrew-first with English islands"
  to "English immersion". The single strongest differentiator between B and D.
- **Input mode default** — speech-first for A/D, text-welcome for B/C.
- **Coach strictness** — per-sound marks off for A's free talk, on for C.
- **Voice** — the picker exists; energetic for A, calmer for B/C/D.
- **Spaced retrieval** — BKT skill states are persisted but nothing schedules
  reviews from them yet.

Implementation shape: a `LearnerTrack` field on the profile, set by three
onboarding questions (age band / goal / "can you read English?"), where each
track is a small config bundle — prompt, budget, scaffold ratio, feedback
mode, content filter — not a code fork. The curriculum's `ageBand` already
half-does this; the track generalizes it. Content warning: track C needs
exam-style material the ten pre-A1 units do not resemble — a content project,
not a code one.

## Hebrew explanations — the next step (greenlit, small)

Prerequisites already in the tree: E4B passed the Hebrew eval gate at 4.45 on
the shipping runtime (`eval/hebrew/results/VERDICT.md`); the safety filter is
Hebrew-aware; `TtsRouter` already degrades mixed output correctly (speaks the
English words, shows Hebrew as text).

1. **Deterministic trigger, not detection**: a "הסבר בעברית" button on the
   conversation screen, plus auto-trigger when the learner *types* Hebrew
   (`containsHebrew` already exists). No confusion-guessing from ASR
   confidence.
2. **A policy instruction, not a prompt rewrite**: the tap injects one
   turn-instruction — "Explain your last point briefly in written Hebrew,
   then continue in English." The `DialoguePolicy → instruction` plumbing
   carries it unchanged.
3. **Gate by tier**: allowed only when `modelTierLabel == E4B`. E2B *failed*
   the Hebrew gate (4.03, meta-AI flag); shipping its Hebrew would ship what
   the eval rejected.

Known limitation to design around: this delivers Hebrew as **text**, which
serves B/C/D fully and A not at all. For pre-readers the answer remains
pre-recorded human Hebrew audio for the ~20 fixed instruction lines
(docs/product-phases.md); dynamic spoken Hebrew waits on a commercially
licensed voice (docs/feasibility.md §6).

## First artifact: "Just chat"

A freeform three-way chat room (the learner + two parrots, Tuki and Kiki) —
the conversation-first shape Track D wants, useful to every literate track as
low-stakes practice. Built now; see `core/tutor .../chat/ChatRoom.kt` and the
"Just chat" entry on the Home screen. Output passes the same safety filter as
lessons; each parrot speaks with its own voice.
