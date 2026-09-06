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
