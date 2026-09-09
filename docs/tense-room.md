# The tense room

One sentence at a time, three fixed tap destinations laid out as a timeline.
The learner reads a sentence with no time word in it and says where on the
timeline it belongs. `docs/mockups/tense-room.html` is the specimen sheet —
every item kind, every state, both layout directions.

## Why the room exists

Hebrew has three tenses, no grammatical aspect and no perfect. One Hebrew word
covers four English forms: the corpus has `חלב` serving past simple, past
progressive, past perfect and past perfect progressive alike. A Hebrew speaker
learning English is not learning new vocabulary for time, they are learning that
time is carried in the verb at all — and that it is carried in more places than
their own language has words for.

The room is Processing Instruction in VanPatten's sense: the learner interprets
before they produce, and the grammatical form is made the **only** cue to
meaning. English says *when* twice — once in the verb and once in a time word —
and a learner who can read the time word never has to read the verb. So the deck
takes the time word out. `We visited the doctor yesterday.` goes on screen as
`We visited the doctor.`

## Why not connect-lines, and why not sorting

The user's first sketch was connect-the-line: a column of sentences, a column of
times, draw between them. Two things rule it out.

A drag across a phone screen is expensive to aim and there is no sane TalkBack
story for a line. That alone would be survivable.

What is not survivable is **elimination**. Give a learner six cards and three
piles and the sixth card is free: Yesterday is full, Today is full, so this one
is Tomorrow — placed correctly, with the verb unread. That is exactly the failure
`docs/fill-the-gap.md` was written to stop, and it would arrive here in a worse
form, because in this room the verb is the entire point.

So: one sentence at a time, three fixed destinations, nothing carried over from
one item to the next. What survives from the sorting idea is its payoff — the
timeline stays on screen, placed sentences pile up under their heading, and a
sorted timeline ends the round.

## The three item families

**Clean.** The bank line carries no time word to begin with, so nothing is
removed. `The doctor listened to my heart.` → Yesterday.

**Stripped.** The bank line does carry one, so the deck lifts it out and holds it
back. On a wrong tap it comes back — `we hid a word, here it is` — which is the
explanation and the answer key at the same time, and needs no grammar lecture.
Only a leading or trailing phrase is lifted: one in the middle of a clause cannot
come out and leave a line anyone would say.

**Aspect.** From the point where the learner has met the perfect, the three
destinations stop being clock times and become *before that / finished / still
going*. This is the stage Hebrew cannot help with, so the Hebrew line is shown
**after** the answer — to make the missing distinction visible, never to hint at
it. (In the time stage the Hebrew is withheld during the ask for the opposite
reason: it would give four of the six forms away.)

## What the deck refuses

- A sentence whose clauses sit at two stops at once. `She said she will come.`
  is past and future together and no single tap is right, whatever the bank's
  `tense` field calls it.
- A second time word left behind after the first came out — the verb has to be
  the only thing worth reading.
- A time word the deck cannot lift out of a clause without leaving a line the
  learner would never say.
- Any line whose tense-carrying form the deck cannot point at. A reveal that
  cannot underline anything has nothing to say.

Frequency words are deliberately **not** stripped. *Always*, *never*, *every day*
say how often, not when; `I always brush my teeth` is as true of last year as of
today, so they are no giveaway — and they are what a habitual present simple is
made of.

## The level window is the whole ladder

Every other room draws from `DrillDeck.levelWindow(n)`, which is two Levels wide.
This room cannot.

The bank introduces one tense per Level — present simple at 1, progressive at 2,
past simple at 3, future at 4, the perfects from 5 — so **the Level ladder is the
tense ladder**, and a two-Level window can hold at most two tenses. Measured
before the fix, a Level 3 round was six Todays and a Level 6 round had no
Tomorrow in it at all. A contrast room has to reach back over the whole ladder,
because the ladder is the thing it is contrasting.

The consequence is that the room shows **the destinations the learner has
actually met**, which is not always three. At Level 2–3 the timeline has two
stops, Yesterday and Today — which is the first contrast a Hebrew speaker needs
anyway. Three stops from Level 4. A destination the learner has never been taught
to reach is not a choice, it is a decoy.

## What the bank can serve today

Measured by `TenseDeckTest`, which prints the census on every run so a content
batch moves the number where anyone can see it:

| Level | Yesterday | Today | Tomorrow | stops |
|---|---|---|---|---|
| 1 | 0 | 300 | 0 | 1 — room not offered |
| 2 | 3 | 574 | 0 | 2 |
| 3 | 347 | 650 | 0 | 2 |
| 4 | 347 | 759 | 183 | 3 |
| 7 | 657 | 803 | 184 | 3 |

Aspect reaches three stops at Level 6 (`before that` = 37 items) and 7 (83).

**The gap.** Of the 803 Today items, **456 are the copula** — `The doctor is
kind`, not `The doctor listens`. Only 180 present-simple items have a lexical
verb, and most of those are statives (*see*, *want*, *like*) that do not contrast
with the progressive at all. So the column a Hebrew speaker most needs — present
simple against present progressive — is the one the bank is thinnest at. A round
holds at most one copula line (`MAX_COPULA`) so it always drills a form, but that
is a rationing rule, not a fix. The fix is task #71.

## Tracing

`TenseDeck.skillId(tense)` is `tense:<tense>`, one skill id per tense, so the
tracer can weight a round toward the forms a learner keeps misplacing
(`docs/knowledge-tracing.md`). Wiring it is task #69.

## No lexicon

There is no part-of-speech tagger and no authored verb list, the same way the
fill-the-gap room refuses one. A verb is a word the bank itself attests as one:
the stem inside an `is Ving`, the base after a `did` or a `to`, the participle
after a `has` — plus the irregular families `ClozeClasses` already carries. The
set is built once from one pass over the bank, so a content batch that brings new
verbs brings their forms with it.
