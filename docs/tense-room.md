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
going*. This is the stage Hebrew cannot help with — one Hebrew present covers
both *waits* and *has been waiting* — which is exactly why the room shows no
Hebrew at all, in either stage. Reading it during the ask would give four of the
six time forms away; showing it after the answer, to make the missing
distinction visible, is a good idea that is **not built**, and the room renders
no Hebrew line today.

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
| 1 | 0 | 299 | 0 | 1 — room not offered |
| 2 | 3 | 592 | 0 | 2 |
| 3 | 337 | 668 | 0 | 2 |
| 4 | 337 | 775 | 183 | 3 |
| 7 | 647 | 819 | 184 | 3 |

Aspect reaches three stops at Level 6 (`before that` = 35 items) and 7 (95).

**The gap, and one batch against it.** Before the batch the Today column was 796
items of which 478 were the copula — `The doctor is kind`, not `The doctor
listens` — and only 151 present-simple items had a lexical verb, most of those
statives (*see*, *want*, *like*) that do not contrast with the progressive at
all.

28 habitual lines were authored against it, each a minimal pair with a
present-progressive line already in its own theme:

    Dad is stirring the sauce.      foo-l2-001, already in the bank
    Dad always stirs the sauce.     foo-l2-007, authored against it

That moved the Today column's inflection-marked items from 115 to **143** and its
lexical present simples from 151 to **179** — 28 lines, 28 items, one for one.
(Every figure on this page is measured with the corrected verb-finder; the ones
first reported for this batch were produced by a classifier that marked the wrong
word on 58 items, and were wrong in both directions.)

Two things about that batch are worth keeping in view. It **replaced** lines
rather than adding them, so the 37 × 7 × 12 rectangle and its test are untouched
— but replacement is not free, and seven candidates were dropped because taking
them would have removed a word (*borrow*, *weigh*, *peppers*, *pick*, *kick*)
from the bank entirely. And the copula count barely moved, 456 → 451, because
the lines that turned out to be expendable were the stative `want to` ones, not
the copulas: the real copula surplus at Level 2 sits in the `emotion-because`
lines, and those carry `he_f` and are teaching the feminine first person, so they
were protected. Getting at them is a decision about what `he_f` coverage is worth,
not a batch.

A round still holds at most one copula line (`MAX_COPULA`) so it always drills a
form. That is rationing, and it is still not a fix.

## The room

`TenseOrchestrator` is the fill-the-gap room's shape — a tap game with no
microphone, a warm retry instead of a dead end, the same ledger of five XP for a
first try and two for a later find — over a different question. Two things
differ, and both follow from what the room is for.

**It reads the sentence aloud while asking.** The fill-the-gap room cannot,
because its line contains the answer. Here nothing is hidden from the ear, and
hearing the form *is* the input the room exists to give. Tapping the sentence
repeats it, free, in either state.

**A wrong tap hands back the hidden word.** `We hid a word: yesterday.` — the
explanation and the answer key at once, with no grammar lecture. A line that
never carried a time word falls back to the plain nudge, because its verb mark
is already the whole explanation.

The destination set travels in the state as `stops`, and it is the *round's*
whole set, never the stops the remaining items happen to use. A timeline that
shrank as the round went would hand back exactly the elimination the
three-destination shape was chosen to remove.

`placed` accumulates across items, and the sorted timeline is what `Done` draws.
That is the one thing kept from the card-sorting idea the room rejected: its
payoff, without its shortcut.

### The reveal does not restore the line on screen

`TenseItem.verb` indexes into `shown`, and a time word lifted off the **front**
of a sentence shifted every one of those indices. Re-rendering `restored` on the
reveal would therefore underline the wrong word — silently, and only on
leading-lift items, which is the worst kind of wrong.

So the eye keeps `shown`, with the verb marked and the hidden word named beside
it, and the ear gets `restored` from Tuki. The deck stores only the hidden text,
not which end it came off, and that is enough for everything except redrawing
the line — which is why the room does not.

### Direction

The timeline is a plain `Row`, so it follows the ambient layout direction and
the past sits on the **right** in Hebrew: time runs the way the eye runs.
Nothing in it is authored per language. The English inside stays left-to-right
through `EnglishContent`, the same split every other room makes.

A wrong destination is struck through, dimmed and disabled, with
`stateDescription` set — three channels, never colour alone — which is
`ClozeScreen`'s treatment exactly, so the two rooms behave the same under the
finger and under the screen reader. The rail above the destinations is
decoration and yields on a cramped screen; the destinations are controls and
never do.

## Tracing

`Skill.tense(id)` is `tense:<tense>`, one skill id per tense — bounded like
every other id, since the bank uses 17 tags and a line carries exactly one, so a
profile that practises for years still holds seventeen of these and not one
more. The room writes it on every placement, beside the theme and the frame, so
a round is already drawn weakest-first over the forms a learner keeps
misplacing (`docs/knowledge-tracing.md`).

What is left of task #69 is the other rooms: the drill speaks a line that has a
tense, and the fill-the-gap room's verb-form gaps are tense evidence too.
Neither reports it yet.

## No lexicon

There is no part-of-speech tagger and no authored verb list, the same way the
fill-the-gap room refuses one. A verb is a word the bank itself attests as one:
the stem inside an `is Ving`, and the base after a `did`, a `will` or a `to` —
plus the irregular families `ClozeClasses` already carries. The set is built
once from one pass over the bank, so a content batch that brings new verbs
brings their forms with it.

What that harvest cannot do is tell a verb from a homograph, and the room has
already been burned by it once. The bank attests `water` as a verb ("Dad is
watering the plants"), `mom` because a question puts it after a *did*, and `not`
because a negation puts it after a *will* — so `The water is warm.` was
underlined at `water`, and `…because Mom is with me.` at `Mom`. The fix is not a
bigger lexicon but POSITION: what follows a be is its complement, what follows a
determiner heads a noun phrase, and a capital away from the start is a name.
`NOT_A_VERB` is the one authored list here, and it exists for the pronouns and
negations that inversion puts where a verb would sit.
