# The phrasebank — authored sentences for Levels 1–7

Status 2026-08-31 (end of day): **the theme map is complete and fully
verified** — 37 themes, 3,108 sentences, 12 per level per theme across all
seven levels (`core/content/src/main/resources/phrasebank/`), all wired
into the drill deck via `ResourcePhrasebankRepository` +
`phrasebank/index.json`. Every theme has been through the standing
adversarial gate (five runs over the scale-out: 262 raw findings, 121
upheld and applied; the rest refuted by skeptics or adjudicated by hand
with the rulings recorded in the applying commits). Authoring was
delegated to sibling-model agents against the batch brief; wiring,
arbitration, and fixes stayed with the maintainer session. Align cues:
levels 1–2 everywhere, level 3 in the batch-3 themes — deepening further
is data-only. Growth beyond this point is per-level enrichment (more
sentences per existing theme toward ~1,000/level), not new themes.

## Why it exists

Model-written Hebrew is trusted only on the E4B tier, and only for the
translation row (eval/hebrew/results/TRANSLATION-ROW.md). A prepared,
human-reviewable bank of correct English/Hebrew pairs gives every tier —
including the 9a's E2B — sound sentences, sound meanings, and repeatable
drill frames, with all tenses represented and emotions ("The bee is happy
because it found a flower") reaching down into the low levels.

## Schema — `tuki-phrasebank-v1`

```json
{
  "format": "tuki-phrasebank-v1",
  "theme": "honey-bee",
  "title": { "en": "...", "he": "..." },
  "sentences": [
    {
      "id": "bee-l2-001",
      "level": 2,
      "tense": "present-simple",
      "frame": "X-is-emotion-because",
      "en": "The bee is happy because it found a flower.",
      "he": "הדבורה שמחה כי היא מצאה פרח.",
      "he_f": "…feminine first-person variant, only when forms differ…",
      "align": [
        { "en": [0, 1], "he": [0, 0] },
        { "en": [2, 3], "he": [1, 1] }
      ]
    }
  ]
}
```

- `level`: 1–7 (the app's proficiency levels — never ages).
- `tense`: one of the tags in `scripts/phrasebank-lint.py`; used for
  coverage accounting so "all tenses" is checkable, not aspirational.
- `frame`: the repeatable sentence pattern, for drills that swap the slot
  word ("I see a ___").
- `he_f`: feminine first-person Hebrew, present only where the form differs
  (אוהב/אוהבת). The default `he` uses masculine first person.

### `align` — SRT-shaped segmentation, deliberately without timestamps

The question that shaped this: *should the JSON look like SRT so the spoken
word can be bolded and the translated words laid out below?* Half yes:

- **Cues yes.** Each `align` entry is a cue mapping a contiguous span of
  English word indices to a contiguous span of Hebrew word indices (indices
  into `en.split(" ")` / `he.split(" ")`, punctuation staying attached).
  That is what lets the UI light the Hebrew word(s) that mean the English
  word(s) currently being spoken or expected, karaoke-style, and it is the
  authored answer to "which words below are THIS word's translation".
  Where the two languages order differently ("a red flower" → "פרח אדום"),
  the cue widens to the phrase — spans stay contiguous, the mapping stays
  honest.
- **Timestamps no.** SRT's times are baked to one recording. Ours would be
  wrong for every voice, speed, and ±2% prosody jitter the TTS applies.
  Word timing is derived at runtime instead: per-word phoneme counts (the
  phonemizer already works per word for the gloss row) prorate the group's
  audio duration, checked against the player's real playback head. Authored
  data carries structure; the synthesizer owns time.

Rules, enforced by `scripts/phrasebank-lint.py`:

- `align` is optional per sentence. The seed authors it for Levels 1–3,
  where mapping is clean. Upper levels translate less literally (perfect
  tenses collapse, clauses reorder), and there the meaning row stands alone
  — which is exactly the meaning-first doctrine: the Hebrew row translates
  the SENTENCE, never word-by-word.
- When present, every English word belongs to exactly one cue (a word
  karaoke can highlight but the meaning row cannot answer for reads as a
  bug). Hebrew words may go uncovered (added function words simply never
  light up).
- Spans never overlap.

### What is never authored

- **Transliteration.** Always derived (`HebrewTransliteration`), never
  written by hand — one G2P, one romanization, zero drift.
- **Niqqud.** The `he` row is plain unvocalized Hebrew, like every other
  translation in the app.

## The level ladder (content contract)

| level | grammar center of gravity |
|---|---|
| 1 | 2–4 words; be + adjective, I see/like; colors, sizes, 1–3 |
| 2 | present simple + progressive; questions; want-to; can/can't (ability, permission); emotions + because |
| 3 | past simple; plurals, possessives; did-questions |
| 4 | future (going to first, then will); comparatives & superlatives; must/should; zero conditional |
| 5 | present perfect; may/might; first conditional |
| 6 | past progressive & perfect; second conditional; passive |
| 7 | perfect progressive; third conditional; reported speech; discourse structures |

Two ladder adjustments landed after checking the consultation's
CEFR/English-Grammar-Profile ordering against the ladder (they agreed on
everything else): *can/can't* belongs at Level 2, not 5 — ability and
permission are A1 structures — and Level 4 batches introduce *going to*
before predictive *will*. Levels are cumulative, so earlier-level
structures remain legal at every level above.

## Vocabulary source for batches (license-checked)

- **NGSL** (Browne, Culligan & Phillips; 2,809 high-frequency words) is the
  batch-authoring vocabulary guide. License per the project's pages:
  **CC BY-SA 4.0** — usable in a free app with attribution; ShareAlike
  binds anything derived from the LIST itself, not original sentences that
  merely use common words. The consultation round claimed plain CC-BY —
  **wrong on the SA clause**, which is exactly why license claims get
  checked before anything is embedded. Confirm the license line on the
  official site before the list itself (as data) ever ships in-app;
  guiding authoring requires no embedding at all.
- **Oxford 3000/5000, English Vocabulary Profile**: proprietary (OUP /
  Cambridge). Never embed, never reproduce. Consultation and prior
  knowledge agree here.

Every batch: common conversational subjects, common objects, frames that
repeat with one slot changed, emotions usable at every level, nothing
unsafe, nothing branded, nothing violent. Lower levels skew younger in
subject matter but the bank serves non-native speakers of all ages — no age
framing anywhere.

## Quality gate for every batch

1. `scripts/phrasebank-lint.py` — structure, indices, coverage, Hebrew-ness.
2. Author self-review of every Hebrew line for gender agreement, register,
   and meaning-first naturalness (this file's seed: authored + reviewed in
   one sitting, then re-read cold).
3. Owner spot-check (you) — the seed exists to calibrate this.
4. Optional third eye: a consultation model reads EN↔HE pairs and flags
   mismatches. Its verdicts are hints, never merges — the standing rule is
   that consultation output needs skeptical validation.

## Growth plan

- Seed: honey-bee, 1 theme × 7 levels × 12 = 84. Batch 2: market (same
  shape). **Batch 3: home-family, school-day, weather-seasons,
  getting-around** — 504 total across six themes, all wired into the drill
  deck via `ResourcePhrasebankRepository` + `phrasebank/index.json`.
  Batch 3 authors align cues for levels 1–2 (batch 2 reached level 3);
  later passes can deepen cue coverage without schema changes.
- Every batch now also passes an **adversarial verification workflow** on top
  of the lint: per level, two independent review lenses (Hebrew accuracy,
  pedagogy/level fit), then a skeptic per finding who must walk the criticism
  end-to-end to uphold it; only upheld findings are applied. The lint is
  structure; the workflow is meaning.
- After seed sign-off: batches of one theme across all levels (~200–350
  sentences each) until ~36 themes ≈ 7,000+ sentences, tracked per level
  and per tense by the lint's coverage report.
- Loader + deck wiring (queued): phrasebank sentences feed the drill deck
  by level and frame, replacing the model-written lines everywhere below
  the Hebrew-capable tier, and become the karaoke/repeat-after-me corpus.
