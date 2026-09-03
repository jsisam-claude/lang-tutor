# Character voices — how Tuki can be somebody

Added 2026-09-03, starting with one character: the Captain.

## What a Kokoro voice actually is

Not a model. A **510×256 float conditioning table**, 522,240 bytes, and the
one 86 MB model synthesizes all of them. That is why the APK can carry all 28
English voices for ~14 MB and why switching is instant. It is also why a new
voice does not have to be trained or downloaded.

Style tables are embeddings, and embeddings interpolate. The weighted sum of
two tables is a real voice sitting between them — not a crossfade of two
recordings, and not an effect. `VoiceBlend.mix` is that sum, and a blended
voice declares its recipe in `TukiVoices.ALL` instead of shipping a file. It
is usable exactly when both source tables are in the build, which
`availableVoices` now checks per source rather than per id.

One property worth knowing before it surprises someone: a blend's RMS is
lower than either source's, because two unrelated embeddings partially
cancel. Measured on the Captain's own pair, 0.188 and 0.204 give 0.166. That
is normal and is what makes the result an intermediate timbre rather than
either original shouted louder.

## Accent is not in the voice table

This is the thing to get right before adding another character. A Kokoro
style vector carries **timbre and delivery** — who is speaking, how gruff,
how fast. The **phonemes** come from our own front end, CMUdict through
`KokoroPhonemizer`, and the model says whatever string it is handed. Blending
two English voices therefore gives a third English voice, and no weighting of
them will ever produce an accent.

An accent has to be written into the phoneme string, which is why `Phonology`
exists and why a voice declares one alongside its blend. The Scottish rewrite
is four substitutions, each a real and audible feature of Scottish Standard
English:

| | rewrite | what it is |
|---|---|---|
| FACE | `/eɪ/` → `/e/` | monophthong, not a diphthong |
| GOAT | `/oʊ/` → `/o/` | monophthong, not a diphthong |
| r | `/ɹ/` → `/ɾ/` | tapped, and the cue an ear catches first |
| LOT/THOUGHT | `/ɔ/` → `/ɒ/` | cot and caught merged |

    Take care of my gold.
      GA    tˈAk kˈɛɹ ˈʌv mˈI ɡˈOld.
      SSE   tˈek kˈɛɾ ˈʌv mˈI ɡˈold.

What it is not: a native accent. The Scottish vowel length rule and the
intonation are not phoneme substitutions, and the speaker underneath is still
English. It reads as someone doing an accent, which for a character is the
point and for anything claiming authenticity is not enough. A real burr needs
a model trained on Scottish speech — Piper is the candidate, its models are
30–60 MB ONNX, they run on device, and VCTK labels its speakers by accent so
a Scottish one can be chosen rather than guessed. That is a second TTS engine
and has not been added.

### Which accents may exist here at all

**No accent in this app destroys an English phonemic contrast.** That single
rule decided most of what shipped. A rewrite that merges think with sink, ship
with sheep or three with tree is accurate description and a terrible teacher,
and it is worse here than almost anywhere: Hebrew shares those exact gaps, so
the app would be modelling the learner's own error back at them and then
scoring it as correct.

That rules out most of what makes a second-language accent recognisable — the
tense–lax mergers, TH-stopping, final devoicing, p→b — which is why the sets
are short. `PhonologyTest` enforces the rule over a list of minimal pairs, so
a future accent cannot quietly reintroduce one.

### Native accents teach; second-language accents only speak

`Phonology.Scope` is the difference, and it is not cosmetic.

- **EVERYWHERE** — Scottish, Irish. A native accent is a legitimate model of
  English, so the coach and the gloss follow it: the learner hears one accent,
  reads letters that match it, and is scored against it.
- **VOICE_ONLY** — Italian, French, Spanish, Hebrew, Arabic, Mandarin. These
  describe someone still learning English. The voice may sound like that; what
  is TAUGHT does not move. The coach keeps expecting standard English and the
  gloss keeps showing it, or the app would grade a learner against a learner's
  approximation.

The Hebrew accent is worth having for precisely the reason it needs the most
care: it is the learners' own. What it does NOT contain is the list of
Hebrew-L1 substitutions this app exists to correct — θ→s, ð→d, æ→ɛ, w→v. It
carries the uvular resh and a low central TRAP, and nothing that would teach
the error.

### Three things have to agree, or the app contradicts itself

The accent is not only what the child hears. Two other places derive from the
same phoneme string, and both now take the voice's phonology:

- **The pronunciation coach.** It builds its expected phones from
  `phonemizeToIpa`. Left alone, Tuki would say a tapped r while the coach
  still expected the American approximant, and a child who copied perfectly
  would be marked down for it.
- **The Hebrew gloss.** It spells the same IPA in Hebrew letters under each
  word. Left alone, the letters would teach a pronunciation the child is not
  hearing.

- **The Hebrew gloss again, as a hard invariant.** The first accent shipped
  emitting `e`, `o`, `ɾ` and `ɒ`, none of which `HebrewTransliteration` knew.
  `ofIpa` drops what it does not recognise — deliberately, since a guessed
  letter is worse than a missing one — so "red" was glossed אֶד and "bird"
  came out identical to "bed". Every rhotic and every flat vowel silently
  vanished from the column the learner reads. The test that was meant to catch
  it only asserted that the accented gloss equalled `ofIpa` of the accented
  string and differed from the plain one: both stayed true the whole time
  sounds were being deleted. Consistency is not correctness.

Three vocabularies are now checked rather than assumed: every symbol a
`Phonology` emits must be in Kokoro's 114-token vocabulary, scorable in the
coach's 392-phone vocabulary, AND renderable by the Hebrew gloss. The check
runs over each accent's declared alphabet, so it covers accents nobody has
written yet — `encode` drops what it does not
recognise silently, so an off-vocabulary symbol would not fail, it would
delete a sound from the middle of a word. Each substitution is one symbol for
one symbol, so the token count never moves and neither the style row nor the
karaoke timings shift. The synth cache keys on the voice id, so accented and
plain renderings of the same line cannot collide.

## The Captain

The ask was a Scrooge McDuck voice. That specific voice is a Disney character
performance, so it is a rights question rather than a technical one and this
project will not clone it. What this is instead is an original character in
the same register: an old, gruff, unhurried sea captain.

Kokoro ships **no Scottish voice**, and no mix of the ones it does ship will
invent one — a blend can only land between its sources. So this is the nearest
the bundled set reaches, not a Scottish accent:

    bm_lewis × 0.65  +  bm_george × 0.35

plus the Scottish phonology above, and a character treatment on personality
lines: pitch down to 0.90, rate to 0.94, and a slow shallow waver at 3 Hz
instead of the parrot's 6.5 Hz bird flutter, with the "brrp!" trill turned
off. The blend is who is speaking; the phonology is where they are from.

He is an ordinary entry in the voice picker, not a separate personality
setting: pick him and Tuki speaks as him everywhere, which is why the coach
and the gloss had to move with him.

**The weights were chosen by construction, not by ear** — nothing in this
repository can listen. They are meant to be tuned on a device using the
picker's own preview button, and the only place to change them is the
`VoiceBlend` in `TukiVoices.CAPTAIN_ID`'s entry. If the result wants a real
Scottish burr rather than a near one, that needs a second TTS: Piper has
Scottish English voices, its models are 30–60 MB ONNX, they run on device, and
the licence fits. That is a bigger change than this one and has not been made.

## What a character may and may not touch

The doctrine from `ParrotEffect` is unchanged and now has teeth in one more
place: **an effect never touches a teaching line.** A line the child is meant
to copy has to be the most intelligible audio in the app.

- The **blend** is a voice, not an effect. Its output is clean Kokoro speech,
  so a blended voice is safe to teach with and appears in the ordinary picker.
- The **phonology** is a voice too, in the same sense: it changes which sounds
  are made, not what is done to the waveform afterwards, and it is applied
  consistently to everything that voice touches. It is safe to teach with for
  exactly that reason — a child hears one accent and is scored against it. It
  would not be safe applied to only half of what the voice says.
- The **character** — pitch, rate, waver, trill — applies only to personality
  lines, the praise and encouragement whose exact phonetics nobody is learning
  from. It is reached through `ParrotVoice`, which orchestrators hold as their
  personality voice, so core code cannot route a teaching line through it.

Before this, the parrot's pitch was a constant. Now `ParrotVoice` asks the
selected voice who it is, because a sea captain given the parrot's treatment
comes out a parrot. Every voice but the characters returns
`ParrotEffect.PARROT`, which is exactly the old constant, so nothing else
changed.

## Adding another character

Add a `TukiVoice` with `accent = CHARACTER`, a `VoiceBlend` over two bundled
tables, a `VoiceCharacter`, and a `Phonology` if they are from somewhere. It
needs no asset, no fetch-script change and no download; `TukiVoicesTest` and
`PhonologyTest` will hold you to the invariants — both sources
shipped, an ordinary voice never quietly growing a character of its own.
