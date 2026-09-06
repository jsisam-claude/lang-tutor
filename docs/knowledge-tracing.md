# Knowledge tracing — giving practice a memory

Added 2026-09-06. The app now remembers what a learner is good at, and every
room that chooses what to practise reads it.

## What was wrong

`BktModel` and `SkillState` were written in phase 1 and sat untouched.
`LearnerProfile.skills` was declared. Outside `BktModelTest`, nothing in the
tree wrote or read any of it.

The consequence was not subtle. `DrillDeck.round` and `phraseRound` did
`.shuffled(random).take(n)`, so **practice accumulated nothing**. A learner
who had said "the dog is sleeping" forty times and never once managed "three
thin things" drew the same shuffled deck tomorrow as a learner who had done
the opposite. The scouted feature list (`docs/feature-ideas.md`) found
eighteen separate ideas — due-today, weakest-sound routing, leech lists,
fading scaffolds, an honest progress card — all waiting on this one absence.

## What a skill is

A namespaced string, spelled by `Skill` so no two rooms disagree:

| id | what it means | where it comes from |
|---|---|---|
| `theme:market` | a phrasebank topic | every banked line |
| `frame:going-to` | a grammar pattern | every banked line |
| `sound:th` | one target phoneme | the twisters, by their own key |
| `word:bee` | a word with a picture | the picture room, a pack gap, curriculum vocabulary |

The grain is chosen to be **bounded by the content, not by how much anyone
practises**: 37 themes, 334 frames, 15 sounds and a few hundred pictured
words. A profile therefore holds at most about seven hundred entries however
long the app is used, which is why there is no eviction rule to get wrong.

The frame is the satisfying one. `PhraseSentence.frame` has carried 334
distinct grammar patterns since the bank was written and **no code had ever
read it** — the second piece of dead schema the feature scan turned up. As a
skill id it is exactly the right grain: *going-to* is something a learner can
be good or bad at, where a single sentence is not.

## What counts as evidence

One function, `SkillTracker.observe`, and nothing else in the app writes
`skills`. Keeping it to one place is what stops four rooms from disagreeing
about what "correct" means.

Every room passes its own notion of clean, and every room reports failure as
well as success — a tracker fed only successes would call every learner an
expert:

| room | correct | not correct |
|---|---|---|
| drill | said right on the first try | still wrong after the last try |
| picture | card found on the first tap | found after a wrong tap |
| fill the gap | answered on the first tap | found by elimination, or shown |

A word found on the third tap is evidence *about* that word — evidence of
not knowing it — so it is recorded as such rather than dropped.

## What reads it

Rounds are drawn **weakest first**, in four coarse bands over a shuffle. The
bands matter: sorting on the raw estimate would serve the same handful of
lines in the same order every visit, which is the opposite of practice.
Sorting into bands and shuffling inside them puts what the learner is worst
at in front of them while leaving the choice within a band to chance.

A skill nobody has met scores below every skill they have. That is
deliberate: opening a topic for the first time should show new material, not
revisit the two lines already seen.

Three rooms read it today: the drill, the picture room and fill the gap. The
Parent Zone shows the weakest few sounds, patterns and topics, and
deliberately not a score — "72%" tells a parent nothing they can act on,
while *going-to* and *the zoo* is something to talk about over breakfast.

## The coach is the densest writer

The pronunciation coach already scores every phone of every line the learner
says, and `SoundSkills` maps those phones onto the fifteen taught sounds
through the IPA that `twisters.json` already authors — the table exists, and
this is only its reverse index. One spoken line therefore carries a dozen
observations where a whole twister round carries one.

Phones outside those fifteen are ignored on purpose. Hebrew already has
them, so a low score there is far likelier to be the aligner than the
learner, and recording it would bury the contrasts that matter under noise.

A sound counts as said when MOST of its instances in that line cleared the
same threshold the learner sees drawn green — a majority of instances rather
than a mean of their scores, because one bad frame among four good ones is
the aligner's bad day and averaging lets it drag the other three under. The
record and the colours therefore cannot disagree about the same attempt.

## What is deliberately not here yet

- **Scheduling.** Nothing is due at a time; weakest-first is not spaced
  repetition. Intervals need a review log with dates, which is a profile
  schema change and a separate piece of work.
- **The conversation and lesson rooms** attribute nothing. They are generated
  turns with no theme or frame to point at, and an unattributed answer is
  simply not evidence.
- **Leeches.** A skill that keeps failing is visible in the Parent Zone but
  nothing retires it from the deck, and nothing tells a person that five
  failures in a row is a teaching problem rather than a scheduling one.

## Privacy

`skills` lives in the same device-local profile as everything else: never
transmitted, and deleting the profile from the Parent Zone deletes it
completely. It is a few hundred small numbers, and it is the learner's.
