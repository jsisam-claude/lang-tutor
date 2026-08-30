# Picture vocabulary room — design record

Queued 2026-08-27, not built. Requested shape: show an animal, say its name,
show the next; after a few, ask **"which animal is this?"**

Teach-then-check, which is the oldest and best-evidenced shape in vocabulary
teaching — and the one thing this app does not have. Everything it owns today
asks the learner to **produce** English (repeat a line, answer Tuki). This
asks them to **recognise** it, which comes first in every account of how
vocabulary is actually acquired: receptive knowledge precedes productive, and
a child who cannot yet say "elephant" can absolutely point at one.

It is also the room with the least new machinery in it. TTS, the reward
system, `A11y`, the die-cut sticker rendering and the drill's word-match judge
all already exist; this is mostly assembly.

## The loop

**Present** three to five items. Icon appears large, Tuki says the word, the
word appears under it. Tapping the icon repeats the word — unlimited, free,
never scored. Three to five is not arbitrary: a 4–6-year-old holds about that
many new items before the set stops being learnable.

**Check.** Show one and ask "which animal is this?" Two answer modes, and the
record deliberately does not pick one, because they test different things:

- **Tap from the set just taught** — pure recognition. Works for a shy child,
  a noisy room, a broken mic, and a child with no English production at all.
  This should be the DEFAULT and the one built first.
- **Say the name** — production, and it reuses the drill judge exactly
  (`WordMatch` over the ASR transcript, `RecognitionHint.ConstrainedVocab` of
  the taught set, which is the constrained recognizer at its very best — five
  candidate words).

The mirrored form is worth having too and costs nothing extra: Tuki *says* a
word and the child taps among icons. Same data, tests comprehension of speech
rather than of pictures.

**A wrong answer re-presents, it does not punish.** Say the right name, show
the right icon, move on, and bring that item back sooner in the next set.
Rewards ride the existing bus: `STAR` for right, nothing for wrong (the same
honesty rule as the drill — a cue that fires whatever you do teaches nothing).

## Where the pictures come from

**Emoji, at least to start.** The app already renders emoji as die-cut
stickers (`StickerBook.StickerFace`: white ring, colour fill, gloss) and they
read as real artwork at size. Zero APK bytes, no licence, full colour at any
density, and animals are the single best-covered emoji category — roughly
eighty of them, far more than the curriculum will need.

That coverage is exactly why **animals** is the right first room and not, say,
verbs: the feature is only as good as the picture set, and emoji is excellent
for animals, decent for food and clothes, and poor for abstractions. Do not
generalise the room past what the pictures support.

Bundled artwork would look better and costs APK bytes plus a licence audit —
the same trade the parrot and the stickers already declined. Revisit only if
emoji proves to read badly with real children.

## Content

A small authored deck — id, emoji, English word, Hebrew word — not derived
from the curriculum, because the curriculum's animals are four (`dog`, `cat`,
`bird`, `fish` in unit-002) and a picture room wants thirty. The curriculum
words should be *tagged* as belonging to the deck so the two agree, rather
than duplicated.

Hebrew belongs here for the same reason it belongs anywhere: it carries
meaning for a child who has none yet. Pairs naturally with the aligned gloss
work (`docs/bilingual-gloss.md`).

## Two things this unlocks

**The knowledge-tracing model finally gets fed.** `BktModel` and `SkillState`
have been in `core/profile` since P1 with **no writer** — every unit ships an
empty `skills` array, so adaptivity and spaced review are dead code. A
recognition check is the cleanest possible BKT observation: one item, one
skill, a clean right/wrong, no ASR ambiguity in the way. This room is the
natural first producer of that data, and once it produces it, "which items
come back in the next set" stops being a guess.

**It is the second room that needs no language model.** Names are authored,
so like the vocabulary room it is instant on every device and works in demo
mode. Better still, the word set is CLOSED and small — perfect for the
synthesized-line cache proposed in the response-speed work: pre-render thirty
animal names once and every presentation is instant thereafter.

## Scope notes

- Landscape wants the grid to reflow to more columns — see the landscape
  section of `docs/bilingual-gloss.md`; same pass.
- `A11y`: the icon is decoration and must shrink under `decorativeSize`; the
  tappable answer cells are targets and must not (`tapTargetDp`).
- Track-aware: this is a PRE_READER and BEGINNER room. An IMPROVER does not
  need to be told what a cat is.

## Asset assignment (2026-08-30)

The pictures/icons/emoji for this room are assigned to the OTHER models
(Opus/Sonnet sessions), per the owner's direction — not to this session's
queue. Constraints they inherit: no downloaded art without a license the
project can carry (the reward system's precedent is Canvas-drawn/procedural
art and synthesized audio precisely to avoid third-party licenses); emoji
are the zero-license path for a first cut; anything drawn must read at
`decorativeSize` floors. Word list comes from the phrasebank/curriculum, so
picture ids should key on the vocab word, not on a file path.
