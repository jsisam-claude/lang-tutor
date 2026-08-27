# Aligned Hebrew gloss under English — design record

Queued 2026-08-27, not built. Requested shape:

```
There is:1   a lion:2   in the zoo:3
יש:1         אריה:2     בגן החיות:3
```

The `:n` markers are notation for the correspondence, not UI. The point is
that alignment is **phrase to phrase, not word to word**: "a lion" is two
English words under one Hebrew word, "in the zoo" is three under two. Any
design that assumes a word-for-word mapping is wrong from the first sentence,
which is why this is a design record and not a ticket.

## Terminology to settle first

The example is a **translation** gloss (אריה means *lion*). "Transliteration"
usually means the other thing — English *sounds* written in Hebrew letters:

```
There is    a lion       in the zoo
דֶ'ר איז    אַ לַאיוֹן    אִין דֶ' זוּ
```

Both are real and useful, and they serve **different learners**:

- **Translation** carries meaning. Useful to anyone who reads Hebrew.
- **Transliteration** lets a child who reads Hebrew but not Latin script
  *pronounce* English they cannot yet decode — the single biggest unlock for
  the pre-reader-of-English case, and the one that pairs with "Repeat after
  me".

They are not alternatives; a full reader might eventually want three rows.
The alignment machinery below is identical for both, so build it once.

## The hard part: RTL under LTR

Hebrew runs right-to-left, English left-to-right. Set both in their natural
order and chunk 1 is at opposite ends of the two lines:

```
There is    a lion    in the zoo
בגן החיות    אריה    יש              <- natural Hebrew: chunk 1 is at the RIGHT
```

Nothing lines up. Two ways out:

1. **Chunk-major layout (recommended).** Lay the CHUNKS out in English order,
   left to right; each Hebrew chunk is internally RTL. So `יש` sits under
   "There is", `אריה` under "a lion". This is what printed interlinear texts
   do, and it is what the request's example shows.
   Cost: the Hebrew row is no longer natural Hebrew — it reads as glossed
   fragments, not a sentence. That is the accepted trade in interlinear
   glossing, but it must be a deliberate choice, and a full natural
   translation may belong on a third line.
2. **Natural order plus non-positional linking** (colour or number). Preserves
   readable Hebrew; makes the reader hop. Rejected for pre-readers — hopping
   is exactly what they cannot do.

Compose specifics: each chunk is its own column, and the Hebrew cell needs
`LayoutDirection.Rtl` locally while the ROW stays LTR. `EnglishContent` is
today's LTR island; this needs its mirror, applied per cell rather than per
line. Long chunks wrap as units — a chunk must never split across lines, or
the alignment silently lies.

## Where the alignment comes from

Three sources, three different problems:

- **Curriculum lines** — author the chunking in the unit JSON. Exact, free at
  runtime, and reviewable. This is the only source that can be trusted
  unconditionally, so it should ship first.
- **LLM-generated drill lines** (`DrillGenerator`) — the model that wrote the
  sentence can gloss it, in the same call, in a parseable format. Cheap
  because it rides an existing generation, but it is a model output and needs
  the same gauntlet treatment as the lines themselves: chunk counts must
  match, every English word must appear exactly once, Hebrew must actually be
  Hebrew. A gloss that fails validation is dropped, and the line shows
  without one rather than with a wrong one.
- **Free conversation** — glossing arbitrary tutor replies is a second
  generation per turn. Given the response-speed work in flight, this is a
  later question, not a first one.

## Scope notes

- Track-aware, like everything else here: PRE_READER and BEGINNER want the
  gloss on by default, EXAM and IMPROVER almost certainly do not (a learner
  who can read English does not need a crutch, and the SLA argument for
  L1 support weakens fast with level). Parent Zone toggle.
- The gloss is TEXT. It does nothing for a child who cannot read Hebrew
  either — that case is still spoken Hebrew, which exists now.
- Do NOT feed the gloss to the safety filter as one blob; check the Hebrew
  as its own string, since the filter is Hebrew-aware.
