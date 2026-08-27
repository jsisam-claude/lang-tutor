# Aligned Hebrew gloss under English — design record

**Status.** Both Hebrew rows are BUILT (2026-08-27). **Landscape** is still
queued.

```
I        see       a      lion
אַי      סִי       אֶ      לַיאֶן      <- sounds, aligned per word
אני רואה אריה                         <- meaning, one natural sentence
```

- **Transliteration**: `HebrewTransliteration` in `core/speech`, derived from
  the IPA the voice already uses. Free, exact, everywhere.
- **Translation**: authored where the curriculum has it (`Activity.Vocab`
  already carried `translation.he`); model-written in the chat room, in the
  same generation as the reply.

Two switches in Parent Zone, because they answer different questions — how to
say it, and what it means. Transliteration defaults on for PRE_READER and
BEGINNER; translation follows `TrackConfig.hebrewTextUseful`, which is already
"is written Hebrew any use to this learner" and so is off for PRE_READER.

### The alignment question, settled by having two different answers

The record agonised over phrase alignment — `a lion` being two English words
over one Hebrew word — and proposed chunk-major layout to force the rows to
line up. **That turned out to be the wrong problem.** The two rows are
different kinds of thing:

- The **pronunciation** is per word and therefore stacks in columns, always,
  with no ambiguity.
- The **meaning** is a sentence. Forcing it into columns would either lie
  about the correspondence or wreck Hebrew word order.

So the translation gets its own line, right-to-left, reading as ordinary
Hebrew. No chunking, no `:n` markers, no authored alignment. The requested
example shows exactly this shape.

### Trusting model Hebrew — the one category that was unlocked

`eval/hebrew/results/VERDICT.md` failed Gemma on Hebrew overall (3.73 against
a 4.0 gate) and P1 adopted "zero dynamic Hebrew". Reading the **category**
breakdown, that verdict argues *for* this feature rather than against it:

| category | score |
|---|---|
| **translate-scaffold** | **4.60** |
| bilingual-turn | 4.15 |
| praise | 4.10 |
| error-explain | 3.20 |
| vocab-hint | 3.15 |
| recast | 2.95 |

The failure is category-shaped, and the verdict's own recommendation is to
"consider unlocking **translate-scaffold only** first — it already passes
every threshold today." Translation is the single Hebrew task the model is
good at, and it is the only one asked for here. E4B passes the gate outright.

It still gets a gauntlet, because a learner cannot check this row: the
translation must be present, be actually Hebrew, be *mostly* Hebrew (the
recorded failure mode is cross-language leakage), be short enough to be a
translation rather than a new thought, and pass the safety filter. It also has
its dress removed — the shipping model wraps every translation as
`התרגום לעברית הוא: **…**`. Anything that fails shows no row at all. Never a
confident mistranslation.

### E4B only, and the gauntlet is not why

Measured 2026-08-27 against the alternatives on 16 tutor sentences
([TRANSLATION-ROW.md](../eval/hebrew/results/TRANSLATION-ROW.md)): **E4B got
16/16 semantically correct** — including `bear`, `duck`, `purple umbrella`,
`his sister` and `soup`, every one of which broke DictaLM 1.7B, TranslateGemma
and E2B. No second model is worth adding.

**E2B is a different story and the row is now gated off it**, on the same
`HEBREW_CAPABLE_TIER` gate Hebrew explanations already used. It produced
`soup`→סושי, `apples`→תפירות (stitches), `lion`→שור (ox) and leaked an Arabic
word mid-sentence. The gauntlet cannot help here and it is important to be
clear why: those outputs are fluent, well-formed, safe Hebrew that is simply
about the wrong thing. Every check in the gauntlet is structural, and no
structural check sees meaning. The only defence against a wrong translation is
a model that does not produce one.

Requested shape:

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

Both are built now, from different sources and with different trust
properties — see the status block above.

## What building it changed

Two assumptions in the original record were wrong for transliteration
specifically, and both made it much cheaper than planned.

**It does not need to be authored, generated, or validated.** The record
assumed three sources with three different trust problems. That is true of
*translation*, where only a human or a model knows that `אריה` means `lion`.
Transliteration is a function of the SOUNDS, and the app already computes
those: `KokoroPhonemizer.phonemizeToIpa` runs CMUdict (with `RuleG2p` for
names) for every line Tuki speaks. So the gloss is derived from the same
phoneme string the voice reads, which means:

- no content work, and no gaps in coverage — LLM-written drill lines and free
  conversation are glossed exactly as curriculum lines are;
- no second model call, so nothing to add to the response-speed budget;
- no gauntlet, because there is no model output to vet;
- and it **cannot drift from the audio**. If the letters and the voice ever
  disagreed, one of them would be lying to a child about how a word sounds.
  Sharing the phoneme string makes that unrepresentable.

**Alignment is word-to-word and exact.** The phrase-alignment problem — `a
lion` as two English words over one Hebrew word — is a *meaning* problem. Each
English word has its own pronunciation, so the transliteration row is one
Hebrew token per English token, always. `GlossedText` is a `FlowRow` of
per-word `Column`s, which wraps between columns and never inside one, so a
pairing cannot be split across a line break.

The RTL-under-LTR problem below is real and was solved as described: the row
is `LayoutDirection.Ltr`, each Hebrew cell flips to `Rtl` locally.

### The spelling decisions

Recorded in `HebrewTransliteration`'s own doc, but the two worth repeating:

- **`b`/`v`/`w` get three distinct letters** (`בּ`, `ב`, `ו`). Israeli custom
  writes both /v/ and /w/ as `ו`; "very" said as "wery" is a mistake Hebrew
  speakers actually make, so a pronunciation aid that blurs them is worse than
  useless.
- **The sounds Hebrew lacks are marked**: `θ ð ʧ ʤ ʒ` become `ת׳ ד׳ צ׳ ג׳ ז׳`,
  the geresh convention Hebrew already uses for Arabic. An unmarked near-miss
  would teach the wrong sound with no signal that it is an approximation.

Two English contrasts are deliberately NOT drawn, because no Hebrew spelling
carries them: `ʊ`/`u` (`book`/`blue`) and `ɜɹ`/`ɚ` (`bird`/`butter`). The
pronunciation coach teaches those; the gloss does not pretend to.

One Unicode trap, since it renders identically when wrong: **dagesh is
combining class 21 and the vowel points are 10-19**, so canonical order is
letter → vowel → dagesh, which is the reverse of how the marks are usually
described. Geresh is class 0 — a starter — so it comes after everything, or
later marks attach to the geresh rather than the letter. A test asserts NFC is
a no-op on the output.

### What is deliberately not glossed

- **Any line containing Hebrew.** The Hebrew-help turn returns one string with
  both languages; glossing it would transliterate Hebrew as English or leave
  gaps under half the words. `rememberGloss` checks and returns nothing.
- **The learner's own utterances** in chat. They know what they said; it is
  the reply they need help reading.
- **`EXAM` and `IMPROVER` by default.** They read English already, and a
  phonetic crutch competes with the spelling they are trying to internalise.
  The Parent Zone switch overrides in both directions.

## The hard part: RTL under LTR

*(This section describes the translation row, which is still queued. The
transliteration row does not have this problem — see above.)*

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

## Where the alignment comes from (translation row — still queued)

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

## Landscape

**Queued with the gloss, because they are the same problem.** The app already
rotates — nothing in the manifest locks orientation — so landscape "works"
today in the sense that it does not crash. It is not designed for.

A glossed reading line is the case that *wants* landscape: two stacked rows of
chunks need horizontal room more than anything else in the app, and a phone
held sideways gives ~2x the width for exactly the axis the chunks run along.
A sentence that wraps to three lines portrait may fit on one landscape, and a
wrapped interlinear block is much harder to read than a wrapped paragraph —
the eye has to re-find the pairing after every break.

What landscape needs, beyond not crashing:

- **The [A11y] rules apply to width too.** Today `A11y` reasons about
  `screenHeightDp` and font scale; landscape inverts the pressure — height
  becomes scarce (a 411x914 phone is 914x411 rotated), so the vertical stacks
  that fit portrait will not. The mascot, the big drill line and the mic are
  all in one vertical column right now.
- **Side-by-side layouts where the content is genuinely two things.** The
  vocabulary room is a line plus a mic; the conversation is a transcript plus
  controls. In landscape those want to be columns, not a squeezed stack.
  `A11y` should grow a `wideViewport` question to sit beside `shortViewport`.
- **Rotation must not interrupt.** `MainActivity.onStop` already guards on
  `isChangingConfigurations`, so a rotate does not silence Tuki or release the
  mic — that part is done and should stay tested.
- **State that survives**: the sticker room already learned this
  (`rememberSaveable`); the drill round, the picked level and the chat draft
  need the same audit.

Worth doing as one pass across every screen rather than per-screen, since the
answer is the same shape each time: a `wideViewport` branch that moves the
column into two, and the same decorative-yields rule already in place.

## Scope notes

- Track-aware, like everything else here: PRE_READER and BEGINNER want the
  gloss on by default, EXAM and IMPROVER almost certainly do not (a learner
  who can read English does not need a crutch, and the SLA argument for
  L1 support weakens fast with level). Parent Zone toggle.
- The gloss is TEXT. It does nothing for a child who cannot read Hebrew
  either — that case is still spoken Hebrew, which exists now.
- Do NOT feed the gloss to the safety filter as one blob; check the Hebrew
  as its own string, since the filter is Hebrew-aware.
