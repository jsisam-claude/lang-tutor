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

## The Captain

The ask was a Scrooge McDuck voice. That specific voice is a Disney character
performance, so it is a rights question rather than a technical one and this
project will not clone it. What this is instead is an original character in
the same register: an old, gruff, unhurried sea captain.

Kokoro ships **no Scottish voice**, and no mix of the ones it does ship will
invent one — a blend can only land between its sources. So this is the nearest
the bundled set reaches, not a Scottish accent:

    bm_lewis × 0.65  +  bm_george × 0.35

plus a character treatment on personality lines: pitch down to 0.90, rate to
0.94, and a slow shallow waver at 3 Hz instead of the parrot's 6.5 Hz bird
flutter, with the "brrp!" trill turned off.

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
tables, and a `VoiceCharacter`. It needs no asset, no fetch-script change and
no download; `TukiVoicesTest` will hold you to the invariants — both sources
shipped, an ordinary voice never quietly growing a character of its own.
