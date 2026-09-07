# Short stories — reading, with the vocabulary guaranteed

Added 2026-09-06. Stories a learner can read without meeting a word nobody
has taught them yet.

## The question this file answers

"Short stories, but I am picky about which ones — they must be educational
and fit the users." Taste is not something a build can check, so the useful
move is to convert as much of it as possible into something that can be.

One rule does most of that work:

> A story may use **only words the phrasebank already teaches at or below its
> own Level.**

That is how decodable readers are built for a classroom: you do not put a
word in front of a reader before you have taught it. Here it also means
"fits the learner" stops being a claim in a review and becomes an assertion
in `StoriesTest`, which names the offending word and the Level it first
appears at, so the fix is either a different word or a higher Level and both
are one line.

The constraint is strict on purpose. **No inflection is forgiven**: the bank
teaches *walk* long before it teaches *walked*, and a Level 2 story may use
only the first. Working inside that list is what keeps the prose plain, and
plain is what a learner needs. It is also a real limit on what can be
written — see below.

## What the vocabulary actually allows

Derived from the bank, cumulative:

| Level | words available |
|---|---|
| 1 | 354 |
| 2 | 699 |
| 3 | 1,068 |
| 4 | 1,301 |
| 5 | 1,454 |
| 6 | 1,627 |
| 7 | 1,787 |

Level 1 is almost all nouns and adjectives — the bank at that Level is "A
bee!" and "The bread is warm." — so there is no verb to build a story on.
**Stories therefore start at Level 2**, which is the first Level with a
usable stock of verbs, and that is a fact about the content rather than a
decision anyone made.

## The other half, which is taste

The rule that no word is unknown does not make a story worth reading. The
brief the drafts were held to:

- **Something changes.** A small want, a small obstacle, a turn. A list of
  sentences about a bee is not a story.
- **It does not moralise.** No lesson stated at the end. The reading is the
  teaching.
- **It is ordinary and true.** A thing lost, a mistake mended, a small
  kindness, waiting for something. Sentiment is the failure mode.
- **No age framing, in either language.** This is the constraint people get
  wrong, and it is checked: the audience is Levels 1–7 at *every* age, so a
  Level 2 learner may be six or sixty. Nothing may address a child, and
  nothing may assume an adult. The register that works for both is warm,
  plain and dignified — a story that would not embarrass a grown learner
  reading it on a bus and would not bore a nine-year-old.
- **The Hebrew is Hebrew**, not a crib of the English word order, with `he_f`
  only where the written feminine actually differs.

## What is gated, and what still needs a person

`StoriesTest` checks the vocabulary rule, the page count, the scripts on
each side, that a feminine variant is never identical to the masculine, that
ids are unique and kebab-case, and that no age framing appears in either
language.

It cannot check whether the story is any good, and it cannot check that the
Hebrew reads naturally. Every story therefore ships `review: "pending"` —
the same flag the picture packs carry — and the standing rule applies: it is
shown, but it is not finished until a native speaker has read it end to end.

## Adding more

Add to `core/content/src/main/resources/stories.json` and run
`./gradlew test`. If the vocabulary gate fails it will name the word and the
Level that would admit it. Nothing else needs to change: the room reads
whatever the file holds.

## The read so far, and what is still open

2026-09-07: each draft was read twice, independently — once by a reader
judging the Hebrew (is every line something a person would say, rather
than the English in Hebrew order; is `he_f` exactly where the written form
differs), once by a reader judging the story and the English (does
something change, does it moralise, does it assume an age, is the English
the English). All sixteen verdicts were *revise*, and the real errors were
the kind a gate cannot see: מתיישב for "is sitting" (the act, not the
state), על המפה for "on the map" (an English calque; Hebrew looks *in* a
map), היה שקט for "went quiet" (a temperament, not a moment), a habitual
"every morning" in the progressive, and one page where Mom and Dad
searching for the narrator's library book placed the reader in childhood.
The two revisions per story were merged page by page, the vocabulary gate
re-run, and a third reader went over the merged set for consistency
(comma before a coordinating ו is English punctuation; זו over זאת; a sign
is looked *at*, a map *in*).

What the merge could not settle, for the native read:

- **the-book-among-the-plates** — Page 2 desk rendering: I chose השולחן שלי (the phrasebank's own rendering of 'desk') over the English reader's שולחן הכתיבה שלי. A native reader might confirm whether the bare שולחן with שלי is unambiguous enough as a desk in a story whose turn hinges on desk-versus-kitchen.
- **the-book-among-the-plates** — Page 8 Hebrew word order: 'כל בוקר אני אוכל כאן ארוחת בוקר' (as the English reader wrote it) versus 'כל בוקר אני אוכל ארוחת בוקר כאן'. Both are natural; kept the reader's.
- **sign-by-the-bench** — Page 3 word order: 'לידי יושב איש' (inverted) vs 'איש יושב לידי' — both readers' forms are grammatical; a native reader may prefer one. I chose the inversion.
- **sign-by-the-bench** — Page 5 word order: 'את הדרך במפה' vs 'במפה את הדרך' — the two readers disagreed; I chose object-first. A native reader should confirm.
- **rocket-in-the-recycling** — 'המיטה שלו' vs bound 'מיטתו' on pages 1 and 3: the phrasebank has no occurrence of either form, so the bound-possessive ruling could not be checked against corpus usage. 'המיטה שלו' was kept because 'מיטתו' reads literary in this register and both readers left it, but a native reader may want to confirm.
- **rocket-in-the-recycling** — Page 4 'הוא שתק' vs 'הוא לא אמר מילה': both are natural; the choice is register, not correctness. A native reader may prefer the longer one if 'very' should be felt more strongly.
- **peaches-and-blue-paint** — The id 'peaches-and-blue-paint' does not match the title 'One Ladder' (flagged by the story reader). Left the id alone so nothing tracking the story breaks; the maintainer should decide whether to rename.
- **peaches-and-blue-paint** — Page 6 Hebrew 'דייב תפס בו גם הוא' renders 'Dave put his hands on it too'; a native reader may prefer 'גם דייב תפס בו' — both are natural, I kept גם הוא to keep the emphasis on Dave joining in without a word.
- **plant-by-the-bus-wall** — Page 7 והצמח — היו לו מים? uses an em-dash for the thought-break; a native reader may prefer והצמח? היו לו מים? or a comma. Meaning is identical either way.
- **one-word-at-the-market** — Page 7 English 'My question was not in my mouth' — the two readers disagree on whether it is deliberate strangeness (English reader) or a non-idiom in both languages (Hebrew reader). I kept the English reader's ruling; a native English editor may want to weigh in.
- **one-word-at-the-market** — Page 2 'לך במקומי' for 'Go for me' is my own re-derivation (neither reader proposed it); a native reader should confirm it over the English reader's 'לך בשבילי'.
- **rain-on-the-window** — Page 5 'באותו צבע אפור' versus the English reader's 'באותו אפור' — a native reader may prefer a third rendering (e.g. 'היו אפורים באותה מידה'); I kept the form the Hebrew reader accepted.
- **rain-on-the-window** — Page 8 'בעיני' framing: chosen as the most natural Hebrew for 'To the waiter it was...'; a native reader might prefer 'למלצר זה היה הים' — meaning is identical either way.
- **the-dry-porch** — The id 'the-dry-porch' does not echo the title 'A Very Small Promise'. Both readers flagged it and both left it alone because the run is keyed on the id; someone should decide whether to rename the id or the title before this lands in stories.json.
- **the-dry-porch** — Page 7 'drier than yesterday' assumes a hand-sized pot on a covered porch dries out within a day of watering. Both readers accepted it (the story reader explicitly asked that page 7 not be explained). Left as is; a native reader may want to confirm it does not read as a misprint.

Every story still carries `review: "pending"`. That flag comes off one story at a time, by a person.
