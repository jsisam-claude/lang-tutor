# Fill the gap — the sentence-completion room

Added 2026-09-06. One sentence, one missing word, four choices; every item
derived from the phrasebank and the picture packs, with one small authored
table behind it.

## The principle everything rests on

"I saw a ___ in the garden" has three grammatical answers among *lion*, *bee*
and *cat*. With **ראיתי דבורה בגינה** under it, it has one. The sentence's own
Hebrew meaning — already authored, already reviewed, already shown in every
other room — is the KEY that makes the answer unique, and the room is built
so that it is the *only* thing that does.

That decides the shape of the distractors. They are deliberately
**same-class**: a noun faces nouns, an article faces determiners, a modal
faces modals. A learner who could eliminate by grammar would not be reading
for meaning, and reading for meaning is the exercise. So every rule in the
deck exists to remove the cases where the Hebrew line *cannot* decide, and
nothing else:

- a pair Hebrew renders with one word (may/might → אולי, in/at → ב) is never
  offered against itself;
- an article after *in/at/on/to/for* is never paired with the other article,
  because the ב/ל prefix swallows it — באוהל is *be-ohel* or *ba-ohel*
  without nikud, and that is 18% of the bank's "the";
- *a* and *the* face each other only where an align cue proves the Hebrew
  carries the distinction (a ה-initial word for "the", none for "a");
- a subject pronoun is only a gap when the verb admits three other pronouns
  — "___ am happy" is never asked;
- a word that already occurs in the sentence is never an option, nor is a
  word the learner has not met (nothing that first appears more than one
  Level above the line).

Because the Hebrew is the question rather than a scaffold, the room shows it
**unconditionally** — bypassing the Parent Zone translation switch and the
Level 5–7 immersion default that every other room honours. That is deliberate
and it is the one place the app does it.

## Three kinds of gap

There is no part-of-speech tagger on the device or in the build, so the deck
has three ways of knowing what a word is:

| kind | what it is | where the distractors come from | second key |
|---|---|---|---|
| **PACK** | a picture-pack word (numbers, shapes, animals) whose Hebrew really is in the line | the pack itself, pluralised to match | the drawn icon, named in Hebrew for a screen reader |
| **WORD** | any other open-class word | every word the bank attests in the same context — the keys to its left and right, plus its morphological shape | — |
| **CLOSED** | a function word in the authored table | its class, after the agreement and proof rules above | — |

The WORD pool is the tagger the app does not have. "is ___ than" with an
*-er* shape yields *calmer, sweeter, colder* for *faster*; "The ___ is" with
a base shape yields *pen, bus, nest* for *sand*. It is context plus shape, so
a minority of options read oddly — a predicate adjective can wander into a
sentence-initial noun's pool — but the Hebrew still keys the answer, so the
item stays correct. Two extra rules keep it clean: a pack word keeps its pack
(the icon is a better class than any pool), and words the bank uses after a
BE form ("is warm") are treated as adjectives and kept out of noun gaps.

A pack word is only a PACK gap when the line's Hebrew contains the pack
word's Hebrew stem. That single derived check is what keeps the town square
(הכיכר) from wearing a square icon and the aquarium (*fish tank*) from being
a fish; where it fails, the position falls back to an ordinary WORD gap with
no icon. Numbers and three animals change their written stem with gender or
number, so their forms are the one Hebrew the table authors.

When the word before a noun gap is *a/an* and the pack cannot field three
same-article words, the article **joins the gap** and every option brings its
own: *an elephant | a lion, a giraffe, an owl*. Otherwise the visible "an"
would name the first letter.

## What is authored, and how it is gated

Two things, both small:

1. `ClozeClasses` in core/tutor — fourteen narrow classes of English function
   words, the never-blanked glue words, the same-meaning groups, the
   agreement table, the a/an exception list (*hour*, *honest*) and the Hebrew
   forms above. Every list carries its reason in the source, and
   `ClozeClassesTest` walks all of them: no word in two classes without a
   positional rule, no rule naming a non-member, and `articleFor` reproduced
   against every one of the bank's 449 articles (30 of them "an hour").
2. The pack template in `picture-packs.json` — `"cloze": {"en": "I see a
   ___.", "he": "אני רואה ___."}` on the animals and shapes packs, so the 25
   pack words the bank never uses (*tiger, zebra, rectangle…*) still get a
   turn as the answer. It rides on the packs' `review: pending` flag. Numbers
   get no template because number Hebrew agrees in gender with the counted
   noun; the maths pack (operators) is not offered in this room at all.

Everything else is derived and needs no review: the sentences are the
phrasebank's, the Hebrew is the phrasebank's, the distractors are the bank's
own words. `ClozeDeckTest` runs the deck over the real bank and packs and
pins the invariants: three honest distractors per gap, nothing from the
sentence, nothing above the learner's Level, no same-meaning pair, the a/the
proof, subject agreement, pack membership and number, the owner's own
examples, and that almost every line at every Level has a gap.

## Measured yield

Over the 36 served themes (the `idioms` theme is excluded: its Hebrew is an
equivalent saying, not a translation of the words, so the key does not
exist there):

| Level | lines with a gap | gaps per line | PACK / WORD / CLOSED |
|---|---|---|---|
| 1 | 427 of 432 | 2.4 | 114 / 560 / 373 |
| 2 | 427 of 432 | 3.2 | 64 / 556 / 771 |
| 3 | 429 of 432 | 3.8 | 57 / 677 / 923 |
| 4–7 | 431–432 of 432 | 4.8 → 6.0 | ~70 / 715–933 / 1,206–1,601 |

The lines with no gap are one-word exclamations ("Congratulations!"). Every
theme fills a six-item round at every Level. The animals pack serves 105
lines at Level 1 and 383 at Level 7; numbers 41 → 106; shapes 19 → 60.

## The round

Six items. A learner's own Level and the one below for the whole bank or a
theme (the drill's window, now one shared rule in `DrillDeck.levelWindow`);
the whole ladder below for a pack, because there the pack is the thing under
test and the sentence is only its vehicle. Distinct sentences, distinct
answers, no kind more than three times, and — for the whole bank — as many
topics as it can. Which gap a line wears is drawn by weight, content gaps
twice as often as function-word gaps, so that *the* (2,220 tokens) never
dominates; a line seen again in the same session wears a different gap.

Nothing that contains the gap is spoken before it is filled — the sentence
IS the answer. Once it is filled, Tuki reads the whole line: the completed
sentence the learner built is the reward. A wrong tap is a warm retry, never
a dead end; the option is struck through, coloured, described and disabled —
never colour alone. First try earns full XP and a star; a later find earns a
little and a flake; the third wrong tap, which leaves one option standing,
fills the gap and reads the line but pays nothing, because paying for
exhaustion would teach tapping, not reading.

## Known limits

- **Synonyms above Level 3.** The same-meaning check reads the align cues,
  which stop at Level 3, plus the pack and curriculum Hebrew. *warm/hot* is
  caught at Level 1 because both gloss to חם; *big/large* in an unaligned
  Level 5 line is not. Measured where checkable, an unfiltered draw would
  contain such a pair in 1–2% of items. Cues for higher Levels widen the net
  for free; an authored synonym list is the other route.
- **Impersonal Hebrew.** "If ___ practice every day, you get better" carries
  אם מתרגלים — no subject pronoun — so the Hebrew does not decide *you* from
  *we*; the second clause does. Rare, and English-decidable.
- **he_f.** 623 lines carry a feminine variant and no profile field selects
  it; the room shows the masculine `he` as the key, exactly as the drill does.
- **Grammar-eliminable noise.** A past form can appear where a base form is
  needed ("Did you ___ … took"). The item stays correct; it is merely less
  elegant. The audit workflow that reads generated items is the check on this.

## Adding more

A phrasebank batch or a new pack extends the room with no code: new lines
bring their own Hebrew and their own gaps, and a pack word with an icon is a
PACK gap wherever its Hebrew appears. Run `./gradlew test` — `ClozeDeckTest`
says whether the new content kept every rule.
