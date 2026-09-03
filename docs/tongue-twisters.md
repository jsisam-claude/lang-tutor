# Tongue twisters — the "Say it fast" room

Added 2026-09-03. 36 authored lines, 15 target sounds, one room.

## Why they are not phrasebank sentences

The phrasebank is a grammar ladder: a line earns its place by the tense and
frame it carries at a Level, and the drill deck draws from it by Level. A
tongue twister earns its place by one **sound**, and its level says how hard
it is to say, not what it teaches. Putting them in the same file would have
meant either lying about the tense of "Toy boat, toy boat, toy boat." or
poisoning the deck that every other room draws from. So they live in
`core/content/src/main/resources/twisters.json`, format `tuki-twisters-v1`,
behind their own repository.

Everything else follows the phrasebank's conventions exactly, because they are
the conventions that keep the content honest: `he` is the natural Hebrew
**meaning** rather than a word-for-word crib, `he_f` appears only where the
written feminine differs, transliteration is derived at runtime and never
authored, levels 1–3 carry hand-authored align cues, and the whole file is
gated by a test (`TwistersTest`) before it can be shown to anyone.

## What the sounds are

Each line drills one English phoneme Hebrew does not have, or one contrast
Hebrew speakers collapse. That is the whole selection rule, and it is why the
picker is organised by sound rather than by level:

| sound | why it is hard for a Hebrew speaker |
|---|---|
| θ, ð | Modern Hebrew has neither; both surface as /s/, /t/, /d/ or /z/ |
| w | Hebrew has /v/ and no /w/, so "wet" and "vet" merge |
| ɪ vs iː | One Hebrew /i/ covers both, so "ship" and "sheep" merge |
| æ | Between Hebrew's /a/ and /e/, so "bad" and "bed" merge |
| ʌ vs ɒ | Neither exists; both land on Hebrew /a/ or /o/ |
| ŋ | Not a Hebrew phoneme; becomes /n/ or /ng/ |
| h | Hebrew ה is weak and often dropped entirely |
| ɹ | Hebrew ר is uvular; the English approximant is a different gesture |
| pʰ | Hebrew /p/ is unaspirated, so English "p" sounds like "b" |
| -ed | Three endings (/t/, /d/, /ɪd/) spelled one way |
| ʊ vs uː | One Hebrew /u/ covers both: "full" and "fool" |
| final v | Hebrew devoices word-finally |
| s vs ʃ | Both exist in Hebrew, so this one is for fun and for speed |
| ɔɪ | Exists, but not in the fast alternation "toy boat" demands |

## The room

"Say it fast" is the vocabulary room's drill loop with a different pool. That
is deliberate: the karaoke, the gloss, the mic, the pronunciation coach and
the round summary are one implementation shared by both rooms
(`ui/drill/DrillPane.kt`), because a second copy of them would drift. What the
twister room changes is only where the lines come from (`DrillSource.Sound`)
and the picker in front of it.

Two things the twister round does that no other round does:

- **It is not shuffled and not capped.** A sound's lines climb from three
  words to a whole clause on that same sound, so the order IS the teaching.
- **It never asks the language model for a line.** A twister has to be hard to
  say in one specific way, which is not something a sentence writer can be
  asked for. The room therefore needs no model at all and is complete on the
  tablet build.

## Adding more

Add lines to `twisters.json`, keep the id prefix aligned to the sound, author
align cues by hand for anything at level 1–3, and run `./gradlew test` — the
gate checks ids, script, levels, cue ranges, that every line names a sound the
file defines, and that no sound is left with a single line (one line is not a
round). A new sound needs an entry in `sounds` with its IPA and an everyday
example word; the picker builds itself from that list.
