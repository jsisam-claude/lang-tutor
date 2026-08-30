# Learner Levels 1–7

> **2026-08-30: Levels replaced tracks.** The app's audience is non-native
> speakers of ALL ages at proficiency **Levels 1–7** — never age groups. The
> four tracks below survive only as a migration mapping (`LearnerProfile.
> effectiveLevel`: PRE_READER→1, BEGINNER→2, EXAM→4, IMPROVER→5); every dial
> they moved now lives in `LevelConfig` as a function of proficiency:
>
> | level | name | tokens | translit | meaning row | Hebrew help | ceremony |
> |---|---|---|---|---|---|---|
> | 1 | First words | 48 | on | on | on | on |
> | 2 | First sentences | 64 | on | on | on | — |
> | 3 | Simple stories | 80 | on | on | on | — |
> | 4 | Everyday English | 96 | — | on | on | — |
> | 5 | Conversations | 112 | — | — | on | — |
> | 6 | Rich English | 128 | — | — | — | — |
> | 7 | Mastery | 128 | — | — | — | — |
>
> Scaffolds fade in a strict order (transliteration, then the meaning row,
> then Hebrew help; 6–7 are English-only immersion on purpose), defaults are
> always overridable in the Parent Zone, and curriculum units carry a `level`
> instead of an age band (old bands mapped 4-6→1, 5-8→2, 7-10→3, 9-12→4,
> 11-13→5). Sentence content per level lives in the phrasebank
> (docs/phrasebank.md), whose ladder table is the content contract.
> Correction style still follows the SLA split the tracks encoded: recasts
> only at the bottom, named rules at the top.
>
> The remainder of this file is the ORIGINAL track design, kept for the
> reasoning that produced those levers; read its four tracks as the levels
> they became.

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

Known limitation, now enforced rather than merely noted: **without a Hebrew
voice installed** this delivers Hebrew as text, which serves B/C/D fully and A
not at all — so track A is not offered it. **With one** (opt-in, see
`docs/feasibility.md` §6) the limitation lifts and track A qualifies too: a
pre-reader cannot read the explanation but can hear it, and hearing it was
always the intended answer for them. The gate reads exactly that way — the tier
must be E4B, and then either the learner reads or we can speak. For the ~20 FIXED instruction lines the
answer remains pre-recorded human audio (docs/product-phases.md) — better
quality than any model, and a child-appropriate voice, which the shipped
Hebrew model is not. Dynamic spoken Hebrew is now available in a free build;
a *commercially* licensed voice is still outstanding (docs/feasibility.md §6).

## Reinforcement — what a learner gets back

**Status: built.** Three cues for everyone, plus one room for the youngest.
The cues are deliberately NOT track-aware — finishing a turn and saying a word
well mean the same thing at every age, and a learner who switches track should
not find the feedback has changed under them. Only the sticker room is gated.

| Cue | Fires when | Why that one |
|---|---|---|
| **Coins** (gold, tumbling) | a turn completes | The "you did the work" acknowledgement. Lands *after* Tuki finishes speaking — XP is awarded at the end of the turn — so it never plays over the sentence the learner is meant to hear. |
| **Stars** (bright, spinning) | pronunciation scores ≥ 0.8 | Did it *well*, not merely did it. Fires right after the attempt, while the model is still thinking, which is also when feedback is worth most. |
| **Flakes** (soft, drifting) | pronunciation scores 0.5–0.8 | "Nearly." A visibly and audibly different cue, so the difference is legible without anyone naming it. |
| *(nothing)* | below 0.5 | The coloured phonemes already say what happened. A celebration here would be a lie, and a cue that fires whatever you do teaches that the cue means nothing. |

Everything is synthesized or drawn: the particles are Canvas geometry and the
chimes are a few sines rendered at first use (`RewardChime`). No audio files,
no artwork, no third-party licence to audit — the same reasoning that produced
the parrot. Each chime is a consonant interval or major triad held near a
third of full scale and tagged `USAGE_ASSISTANCE_SONIFICATION`, so it sits
under the tutor's voice rather than over it.

**The sticker room** is the 4–6 reward skin
([product-phases.md](product-phases.md) calls for one). At every 50 XP — ten
turns, a session's worth — a young learner is taken to a room, picks one
sticker from eight, and is returned to the exact lesson they left, on its own,
a beat later. No confirm button and no wrong choice: a pre-reader cannot be
asked to press "Done". "Young" is either signal — the profile's track is
`PRE_READER`, or the open unit's age band is 4–6 — so a parent who never
touched the track setting still gets the right behaviour inside a 4–6 unit.

Owed-ness is derived, never stored: `xp / 50 > stickers.size`. Nothing to keep
in sync, and a crash mid-celebration just means the room opens again. A
milestone already offered this session is not re-offered, so backing out is
not a trap.

Honest tension: [product-phases.md](product-phases.md) warns against the
overjustification effect and says to "celebrate effortful milestones only".
Coins on every turn are arguably not that. The mitigations are that the cue is
peripheral (an overlay that takes no input and blocks nothing), brief, and
quiet — and that the *loud* cues are reserved for doing something well. This
is a judgement, not a measurement; it is the kind of thing the eval harness
cannot settle and a week with real children can.

## First artifact: "Just chat"

A freeform three-way chat room (the learner + two parrots, Tuki and Kiki) —
the conversation-first shape Track D wants, useful to every literate track as
low-stakes practice. Built now; see `core/tutor .../chat/ChatRoom.kt` and the
"Just chat" entry on the Home screen. Output passes the same safety filter as
lessons; each parrot speaks with its own voice.
