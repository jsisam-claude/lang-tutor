# What other apps do that Tuki could — the scouted list

Scanned 2026-09-03. Eight scouts read the reading and language-tutoring
landscape against this repo; 55 ideas came back; 53 of them were then handed
to an independent verifier whose job was to check the pitch against the code
rather than to like it. Two never got a verifier and are marked as such.

This file exists because the scan itself is not the deliverable — a list of
55 good ideas that lives only in a chat log is a list that has to be
regenerated every time anyone asks. What follows is the whole list, grouped,
with the corrections the verifiers made.

## The offline constraint turned out not to be the filter

Every one of the 55 works with `HAS_LLM=false`: no server, no generation, no
new model. That is not a coincidence and it is not luck — the scouts were
told the constraint up front. But it is worth stating plainly, because the
intuition it contradicts is a common one: **almost nothing that a reading app
does for a child needs a language model.** Minimal pairs, spaced review,
blending, cloze, decodable stories, a progress card, hearing yourself back —
all of it is scheduling, authored content and audio. The generated
conversation is the one room that needs the model, and it is already the room
that only Pixels get.

So no idea was rejected for being impossible here. Every verdict came back
`FITS_WITH_CHANGES`, and the changes were the interesting part: what each one
would cost in *this* tree, which room it actually belongs in, and which of the
pitch's assumptions were wrong.

## The keystone: one dead class blocks a fifth of the list

Eighteen of the 53 verified ideas independently arrive at the same place.

`core/profile/.../KnowledgeTracing.kt` defines `BktModel` and `SkillState`.
`LearnerProfile.kt:117` declares `val skills: Map<String, SkillState>`. Grep
the tree outside the tests and there is exactly one hit — the declaration
itself. Nothing writes it. Nothing reads it. It has sat there since P1.

The consequence is not subtle. `DrillDeck.round` and `phraseRound` do
`.shuffled(random).take(sizeFor(level))`: **practice accumulates nothing.** A
learner who has said "the dog is sleeping" forty times and never once managed
"three thin things" gets the same shuffled draw tomorrow as a learner who has
done the opposite. Every idea below that says "due today", "weakest sound
first", "leech", "fade the scaffold", "honest progress card" is really the
same request — give the app a memory — and every one of them is cheap once
that memory exists and impossible until it does.

The write sites are already sitting on the profile: `DrillOrchestrator`,
`PictureVocabOrchestrator.onAnswer` and the GOP scorer all have the learner in
hand at the moment a result is known. The verifier's estimate for wiring the
observation writers was about a dozen call sites and no authored content at
all. This is the highest-leverage item on the list and it is not a feature —
it is the substrate five features are waiting on.

A smaller version of the same finding: `PhraseSentence.frame` — the field
whose own doc comment says "the repeatable sentence pattern, for slot drills"
— carries 334 distinct values across all 3,108 sentences and **is not read by
any Kotlin file in the tree.** The slot drill its schema anticipated was never
built. Similarly, 960 sentences carry hand-authored `align` cues that only the
gloss row uses today.

## The clusters

Numbers in brackets are effort as the verifier judged it against this repo,
not as the scout guessed it.

### 1. Perception — the missing half of the pronunciation loop

Four scouts proposed the same feature independently, which is usually a
signal.

- **Minimal-pair ear training** [small–medium]. Two words as icons plus text;
  Tuki says one; the learner taps which they heard. No microphone, no reading,
  no generation. The rationale is the strongest in the whole list: perception
  precedes production, so a learner who cannot *hear* ship/sheep will never
  reliably say it — and the GOP coach will keep marking them down on a
  contrast they cannot perceive. This is input training for a loop that
  currently only has an output side.
- **A different voice every trial** [small]. High-variability phonetic
  training is the finding that multi-talker practice transfers to new talkers
  and to the learner's own production, and single-talker practice does not.
  Tuki gets it for free: a Kokoro voice is a 510×256 conditioning table, not a
  model, so rotating among the 28 bundled voices costs nothing and adds no
  memory.

  The verifier's correction matters: this is **not** a `DrillSource` sibling.
  `ui/drill/DrillPane.kt` is a mic machine — `Prompting → Listening → Judging`
  driven by ASR and the coach. A no-microphone tap game has no state in it.
  The picture room is the right host; its mechanic is already almost exactly
  this.

  Cost: an authored minimal-pair bank keyed to the 15 sound ids already in
  `twisters.json`, gated by a test the way that file is.

### 2. Hear yourself

- **Play the attempt back beside Tuki's line** [small]. Four scouts proposed
  it. The clip is *already captured* — `AsrResult.audio` holds it — and it is
  currently discarded at the end of the turn. Two play buttons on
  `PronunciationFeedback`, hold the clip for the round instead of the turn,
  discard it explicitly on advance. Nothing is stored.
- **Keeping the clip on purpose** [medium] is a different feature with a
  different bar: adult-gated, opt-in, capped, expiring, with unambiguous copy
  about what is kept and for how long. Worth doing, worth doing carefully, and
  not to be confused with the first one.

### 3. Say what you actually said

The coach currently says *how wrong*. These say *what was wrong*.

- **Name the substitution, not the failure** [small]. "You said /s/, this word
  wants /θ/", with an example word for each. Needs a `heard` field threaded
  from `GopScorer` through `PronunciationScore`, plus an authored substitution
  table in Hebrew — one row per confusion Hebrew speakers actually make.
- **Put the marks on the word, not on an IPA row** [medium]. A child does not
  read IPA. Colour the word and its Hebrew letters instead. Needs
  word-boundary-aware phonemization (split the call per word, keep the index
  mapping) and a per-word rollup.
- **Say one true thing** [small]. Anti-demoralising rules: one worst
  *confident* sound, vowels excluded from the learner's view, first attempt
  never criticised. This is a policy layer over output that already exists.
- **The articulation card** [medium] and **the sound clinic** [medium] — "what
  your mouth does" behind a red sound: ~15–20 authored cue texts in Hebrew and
  simple drawn side-view diagrams, in a gated JSON file like `twisters.json`.
  No photography, no licensed art.

### 4. Memory — everything downstream of the keystone

- **The review round that opens the session** [medium] — five due items at the
  top of every room.
- **A due-today queue** [medium], FSRS (MIT-licensed, Kotlin) over an
  append-only review log; or **one review queue graded by the microphone**
  [large], which is the same thing with the coach as the grader.
- **Expanding retrieval inside the picture round** [small] — the cheapest
  entry point of all: replace the shuffled index list with a re-queue carrying
  a per-item next-position offset. Spaced repetition inside a single round,
  no persistence needed.
- **Scaffolds fade per item, not just per level** [medium] — read strength at
  render time; the gloss becomes tap-to-reveal for items the learner knows.
- **Review the frame, not the sentence** [small] — group by frame + theme +
  level, show the least-recently-seen variant. This is what the 334 dead
  `frame` values were for.
- **A leech list handed to a human** [medium] — after N consecutive lapses,
  stop drilling it and tell the adult, because an item that fails five times
  is a teaching problem, not a scheduling one.
- **The evidence card** [medium] — replace "Stars earned" with something
  honest, built on the attempt log P1 named and never built.

### 5. The phonics ladder

Ties directly to the queued short-stories work.

- **Grapheme cards with the Hebrew sound-bridge** [medium] — "these letters
  make a sound you already know". ~44 rows plus digraphs, ordered by teaching
  sequence, not alphabet.
- **Blending** [medium] — Tuki stretches the word, then says it fast. ~150 CVC
  words grouped by rime, each tagged with the graphemes it needs so a word
  never appears before its letters do.
- **Sight words** [small] — "words the letters lie about", ~100 by frequency.
- **Decodable story mode** [medium] — the phrasebank as pages rather than
  flashcards, with a decodability pass in `scripts/phrasebank-lint.py`. This
  is the strongest existing answer to the short-stories question, because it
  makes "which stories" a *derivable* property rather than a taste judgement.

### 6. New rounds over content that already exists

Cheap, because the content cost is roughly zero.

- **Cloze** [medium] — blank a content word that has an align cue. The align
  cues finally pay for themselves.
- **Prompt from the Hebrew, answer in English** [small] — reverse the drill.
- **Build the sentence from tiles** [small] — order judging, hints from the
  align cues.
- **A path through every room that never requires speaking** [medium] —
  distractors drawn from the same level and theme with a different frame,
  tested never to return the target. Matters for a mute child, a shy one, a
  noisy room, and a broken microphone.
- **Three cards, then everything else** [medium] — a short, finishable opening
  set instead of an undifferentiated pile.

### 7. Voice and latency

- **A disk-backed synthesis cache with round-ahead prefetch** [medium] — the
  single biggest responsiveness win available without changing engines, and
  the reason the Piper question resolved the way it did. Needs a cache key
  covering voice, phonology and speed, and invalidation when the picker
  changes.
- **Who reads to you today** [small] — a curated three-or-four-face voice row
  for the learner, layered over the existing 29-voice picker rather than
  replacing it.

### 8. The shape of a session

- **One button that runs the session** [medium] — a path, not a menu:
  picture → sound → drill → sticker, returning to the path rather than to
  home. `AppNav` is a flat `NavHost` today whose only orchestration is the
  sticker interceptor.
- **Hands-free rounds** [small] — after the first tap nobody touches the
  screen; needs an echo guard so Tuki's own playback does not open the mic.
- **One mic-mode setting honoured by every room** [small].
- **A goodbye, not a cut-off** [medium] and **make the daily limit real**
  [small] — an on-device session clock, a wind-down screen, never a deficit.
- **The round ends with what you said** [small] — per-line replay plus
  per-sound praise keyed to the 15 sounds.
- **Stickers that remember the line** [medium] — a sticker records which
  sentence earned it.

### 9. The adult

- **"From your grown-up"** [small] — an adult-set practice list on the child's
  home screen, picked from the 37 themes and 15 sounds.
- **Practise-together mode** [medium] — the adult prompts in Hebrew, the
  learner answers in English.
- **Session review** [medium] — what Tuki actually said, not only what someone
  flagged. A capped log with a retention rule and a delete control.
- **The one-page sheet** [medium] — a printable report and an off-screen
  practice list, Hebrew RTL with the English as an LTR island.
- **Tap any word** [medium] — the phrasebank as an offline dictionary, via a
  build-time reverse index over the align cues.
- **The picture shelf** [medium] — the icons as a browsable board with no round
  in it, because sometimes a child wants to look at things.

### 10. Accessibility — partly shipped since the scan

- **Colour is never the only signal** [small] — *done* (`1759aa8`): every
  scored symbol now carries a mark and a spoken description, and the row wraps
  instead of silently cutting at 24.
- **A screen-reader pass across the seven rooms** [medium] — *partly done*.
  The mic is reachable without touch (`c57a3f1`) and flagging a reply no
  longer requires a long press (`8b61b5e`). The sticker cells, picture cells,
  twister picker and Parent Zone switches have not been done.
- **Reading comfort: spacing dials, not a dyslexia font** [small] — three
  dials, defaulted off. The research does not support special fonts; it does
  support spacing.
- **Tap a word for its audio and gloss** [medium] — needs per-word tap targets
  respecting `A11y.tapTargetDp`.

### 11. The larger builds

Real, but each is a project.

- **Step into the dialogue** [large] — authored scenes with a schema, a lint
  script and a review pipeline. The authoring is the cost, not the code.
- **Read-then-check** [large] — the learner reads, Tuki follows along. Needs a
  streaming variant of `GopScorer.forcedAlign` over a growing buffer.
- **Say it like Tuki: the melody of the line, drawn** [medium–large] — an
  on-device pitch tracker (YIN over the captured PCM) with voiced/unvoiced
  masking and time normalisation.

## Two ideas have no verdict

53 of the 55 were verified against the code; two never reached a verifier
before the run's agent budget ran out. They sit in the clusters above
unmarked, because the honest statement is that the page cannot tell you which
two: recovering the pairing from the run journal matches 51 verdicts to their
idea by name, and the remaining pairs are ambiguous. So four entries above
carry slightly less weight than the rest, and there is no way to say which
four without re-running the verifier over the list.

## What this file does not decide

Nothing here is scheduled. The clustering is an argument about what is cheap
and what is load-bearing, not a plan. The one recommendation this file does
make is about order: **the keystone first.** Five of the clusters above are
one week of scheduling code away from being easy, and are permanently
expensive while `skills` stays dead.
