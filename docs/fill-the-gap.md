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
  without nikud, and that is roughly a fifth of the bank's "the";
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
no icon. Numbers and a few animals change their written stem with gender or
number, so their forms are the one Hebrew the table authors.

When the word before a noun gap is *a/an* and the pack cannot field three
same-article words, the article **joins the gap** and every option brings its
own: *an elephant | a lion, a giraffe, an owl*. Otherwise the visible "an"
would name the first letter.

## What is authored, and how it is gated

Three things, all small:

1. `ClozeClasses` in core/tutor — fourteen narrow classes of English function
   words, the never-blanked glue words, the same-meaning groups, the
   agreement table, the a/an exception list (*hour*, *honest*) and the Hebrew
   forms above. Every list carries its reason in the source, and
   `ClozeClassesTest` walks all of them: no word in two classes without a
   positional rule, no rule naming a non-member, and `articleFor` reproduced
   against every one of the bank's 449 articles (30 of them "an hour").
2. The pack template in `picture-packs.json` — `"cloze": {"en": "I see a
   ___.", "he": "אני רואה ___."}` on the animals and shapes packs, so the
   pack words the bank never uses (*tiger, zebra, rectangle…* — about
   twenty) still get a turn as the answer. It rides on the packs' `review: pending` flag. Numbers
   get no template because number Hebrew agrees in gender with the counted
   noun; the maths pack (operators) is not offered in this room at all.
3. The irregular verb families in `ClozeClasses` — the 56 the bank uses
   (*see/saw/seen, go/went/gone…*). No rule derives *went* from *go*, and
   the table does two jobs: it keeps the owner's own example honest ("I ___
   a bee" offers *see* against *saw*, decided by the Hebrew tense — one
   family member at most, never all three), and it says which forms are
   never a base form, so *fell* is not offered after "I" in a present line.

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
| 1 | 416 of 432 | 2.4 | 112 / 586 / 335 |
| 2 | 420 of 432 | 3.1 | 62 / 539 / 753 |
| 3 | 428 of 432 | 3.6 | 54 / 661 / 847 |
| 4–7 | 431–432 of 432 | 4.3 → 5.3 | ~68 / 695–907 / 1,052–1,317 |

The lines with no gap are one- and two-word exclamations ("Snow!", "Hot
soup!") and questions whose only function word is decided by agreement
("Are you drinking tea?"). Every theme fills a six-item round at every
Level; the thinnest window is food-and-cooking at Level 1 with ten lines.
The animals pack serves 103 lines at Level 1 and 380 at Level 7; numbers
41 → 106; shapes 21 → 53.

## What the item audit found, and what changed

Before the room shipped, 1,123 generated items were read one by one as a
bilingual teacher would, with one question: given the gapped English and
the Hebrew, is the marked answer the only option that fits? Ten were not
(0.9%), 45 more had a distractor that grammar alone could rule out, and 99
were cosmetic. Every one of the ten traced to a rule, not to a sentence,
and the rules are now in the deck:

- **Bare ב.** "Grandpa pushed me on the swing" is בנדנדה; the Hebrew shows
  nothing that separates *on* from *in* or *at*. When the answer is one of
  in/at/on/into/through/inside and its marker (על, לתוך, דרך, בתוך) is
  absent from the line, nothing in that family is offered against it.
- **Impersonal Hebrew.** "If ___ mix flour and butter" over אם מערבבים
  names nobody, so *you*, *we* and *they* all fit. A subject after אם/כש
  whose Hebrew is a bare present plural gets no gap.
- **Compound modifiers.** "the ___ bowl" over הקערה: the Hebrew is one word
  for the whole compound and says nothing about *mixing*. The first half of
  a compound is a gap only when the Hebrew mentions it.
- **Requests.** "___ you hand me the towel?" over תיתן לי: *Will* and *Can*
  are one request in Hebrew, so the request modals never face each other.
- **Two open-class pairs** the cues stop short of — *little/small*,
  *big/large* — joined the same-meaning list, with *bird/duck*, where a
  duck is also a bird.

The 45 grammar leaks closed the same way: a verb's attested forms (from the
line's tense field) keep a past out of a present gap; a verb before an
object must be one that takes an object; a subject after *did* draws
subjects; a time adverb agrees with the tense; a sentence-initial gap draws
only words the bank attests as subjects; *this* before a time noun,
*more/most* before a singular, *much* before a count noun and *the* inside
"so ___ foam" are not asked at all. The cost was eleven Level 1 lines,
every one a one-word exclamation or an agreement-decided question.

## The round

Up to six items (a thin pack at Level 1 can run short of distinct answers).
A learner's own Level and the one below for the whole bank or a theme (the
drill's window, now one shared rule in `DrillDeck.levelWindow`); the whole
ladder below for a pack, because there the pack is the thing under test and
the sentence is only its vehicle. Distinct sentences, distinct answers, no
kind more than three times, and — for the whole bank — six topics. Which gap
a line wears is drawn by weight: where a line offers both, a content gap is
chosen twice as often as a function-word gap, so that *the* (2,220 tokens)
never dominates; over many rounds function-word gaps come out at a little
under half, because many lines offer nothing else. A line seen again in the
same session wears a different gap.

Nothing that contains the gap is spoken before it is filled — the sentence
IS the answer. Once it is filled, Tuki reads the whole line: the completed
sentence the learner built is the reward. A wrong tap is a warm retry, never
a dead end; the option is struck through, coloured, described and disabled —
never colour alone. First try earns full XP and a star; a later find earns a
little and a flake; the third wrong tap, which leaves one option standing,
fills the gap and reads the line but pays nothing, because paying for
exhaustion would teach tapping, not reading.

## Verification status

The room was reviewed across five lenses (the deck against the corpus, the
state machine, the screen, the tests, the content and docs), which raised 31
findings, each then handed to three independent verifiers told to refute it.
That pass was cut short twice by session limits, so the state is partial and
worth writing down rather than re-deriving:

| | count |
|---|---|
| findings with a full three-vote verdict | 19 |
| of those, fixed and confirmed fixed | 11 |
| of those, still open at HEAD | 8 |
| findings never judged | 12 |

**Still open**, in the verifiers' words, none of them wrong answers — the
Hebrew still keys every item — but each one a rule that could be tighter:

- a WORD gap can offer a distractor the line's Hebrew happens to name
  (*bed/sleep*, *tub/bath*, *store/shop*): the same-gloss filter runs on the
  answer's own gloss, not on every word the line mentions;
- an impersonal Hebrew subject outside the אם/כש frame ("If ___ splash, the
  floor gets wet") still keys *you* from the English alone;
- `lemma` misses *-e* plurals, so the singular of a word already in the line
  can appear as a distractor;
- a name that only ever opens a line can enter a context pool and be shown
  lowercase inside another;
- a round can come back with five items rather than six: always for the
  numbers pack at Levels 1–2, and for three Level 1 themes on some seeds;
- the PACK compound rule fires when the next word is a verb, so a natural
  animal line loses its icon and falls to a poorer WORD pool;
- a chip change silences the outgoing room but a resolve already past its
  praise can still start one more line;
- `ClozeDeckTest` pins the same-class promise for the three kinds, but six
  mutants that break it in narrower ways still leave the suite green.

**One fix was worse than the bug it cured.** The first attempt at silencing
an outgoing room called `shutdown()` from `onDispose`. That does stop the
voice, but it is terminal, and nothing restarts a round for a room the
ViewModel store still holds — so a rotation, a chip tapped twice, or the
sticker detour that Level 1 earns would each have returned the learner to an
empty pane with no control to recover. Two verifiers found it independently.
`ClozeOrchestrator.silence()` replaced it: the voice is cut and any line
queued behind it is dropped, while the round stays exactly where it was.

## Known limits

- **Synonyms above Level 3.** The same-meaning check reads the align cues,
  which stop at Level 3, plus the pack and curriculum Hebrew. *warm/hot* is
  caught at Level 1 because both gloss to חם; *big/large* in an unaligned
  Level 5 line is not. Measured where checkable, an unfiltered draw would
  contain such a pair in 1–2% of items. Cues for higher Levels widen the net
  for free; an authored synonym list is the other route.
- **Impersonal Hebrew elsewhere.** The אם/כש rule above catches the
  conditional frame; an impersonal plural in another position ("___ must
  listen when the teacher speaks" over חייבים להקשיב) still reads as a
  *you* gap decided by the English alone. Rare.
- **he_f.** 623 lines carry a feminine variant and no profile field selects
  it; the room shows the masculine `he` as the key, exactly as the drill does.
- **Grammar-eliminable noise.** What is left after the audit is the
  irregular tail — a word the bank attests in one odd context — and it is
  cosmetic: the item stays correct. Re-running the audit after a content
  batch is the check on it.

## Adding more

A phrasebank batch or a new pack extends the room with no code: new lines
bring their own Hebrew and their own gaps, and a pack word with an icon is a
PACK gap wherever its Hebrew appears. Run `./gradlew test` — `ClozeDeckTest`
says whether the new content kept every rule.
