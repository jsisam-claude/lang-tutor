# Learner tracks — one spine, four tracks

Decision record (2026-08-27): how the tutor serves audiences beyond the
original young-child focus, what varies per audience, and the concrete next
step for Hebrew explanations.

**Status (2026-08-26):** the track itself and the Hebrew escape hatch are
built — `LearnerTrack` on the profile, `TrackConfig` in `core/tutor`, a picker
in the Parent Zone, and the "הסבר בעברית" control on the conversation screen.
Onboarding, spaced retrieval and track C's exam content are still plan only;
each section below says which.

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

Implementation shape — **built**: `LearnerTrack` is a field on
`LearnerProfile`, and `TrackConfig.of(track)` in `core/tutor` is the config
bundle: persona suffix appended to the shared system prompt, reply budget,
whether corrections name the rule, and whether written Hebrew is any use to
this learner. Not a code fork — one lookup at `startSession`.

Two details worth keeping straight:

- **The age band still floors the reply budget.** A 4–6 unit gets 48 tokens
  even on the Exam track, because the constraint there is the material, not
  the learner's profile. Track budgets only apply above that floor.
- **The picker lives in the Parent Zone, not onboarding.** The three-question
  onboarding the plan calls for (age band / goal / "can you read English?")
  is still unbuilt; an adult changing the setting after watching one session
  is the better first version of it anyway.

Content warning, unchanged: track C needs exam-style material the ten pre-A1
units do not resemble — a content project, not a code one.

## Hebrew explanations — the next step (greenlit, small)

Prerequisites already in the tree: E4B passed the Hebrew eval gate at 4.45 on
the shipping runtime (`eval/hebrew/results/VERDICT.md`); the safety filter is
Hebrew-aware; `TtsRouter` already degrades mixed output correctly (speaks the
English words, shows Hebrew as text).

All three steps are **built** (`TutorOrchestrator`, `HebrewHelpTest`):

1. **Deterministic trigger, not detection**: a "הסבר בעברית" button on the
   conversation screen, plus auto-trigger when the learner *types* Hebrew.
   No confusion-guessing from ASR confidence — the learner is the only one
   who knows they are lost, and typing Hebrew says so plainly. The shared
   definition of "is this Hebrew" now lives in `core/speech`'s `HebrewText`,
   used by the voice router, the trigger, and the transcript's text
   direction alike.
2. **A policy instruction, not a prompt rewrite**: the tap injects one
   turn-instruction (`HEBREW_HELP_INSTRUCTION`) through the same
   `DialoguePolicy → instruction` plumbing every other move uses. The session
   does not enter a "Hebrew mode"; the next turn is ordinary English. The one
   thing the turn does get of its own is a bigger token budget — a bilingual
   answer carries two scripts and the ordinary budget clips it mid-sentence.
   Asking for Hebrew adds no child turn to the transcript: it is a request to
   re-explain, not something the learner said.
3. **Two gates, not one**: the loaded tier must be E4B — E2B *failed* the
   Hebrew gate (4.03, meta-AI flag), and shipping its Hebrew would ship what
   the eval rejected — **and** the track must be one written Hebrew helps.
   The button is absent, not disabled, when either gate is shut.

Known limitation, now enforced rather than merely noted: this delivers Hebrew
as **text**, which serves B/C/D fully and A not at all — so track A is not
offered it. For pre-readers the answer remains
pre-recorded human Hebrew audio for the ~20 fixed instruction lines
(docs/product-phases.md); dynamic spoken Hebrew waits on a commercially
licensed voice (docs/feasibility.md §6).

## First artifact: "Just chat"

A freeform three-way chat room (the learner + two parrots, Tuki and Kiki) —
the conversation-first shape Track D wants, useful to every literate track as
low-stakes practice. Built now; see `core/tutor .../chat/ChatRoom.kt` and the
"Just chat" entry on the Home screen. Output passes the same safety filter as
lessons; each parrot speaks with its own voice.
