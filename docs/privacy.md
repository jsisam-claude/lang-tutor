# What the microphone hears, and where it goes

Added 2026-09-06, after an audit of every log statement in the app.

## The promise

Tuki runs offline: no network permission, no analytics, no crash reporter,
no account. That covers the obvious ways a spoken sentence could leave a
device, and it is the reason the app can be pointed at a microphone at all.

It does not cover **logcat**. Logcat is on every Android device, is readable
over adb without root, outlives the process, and is where a developer's
debugging line ends up by default. On a device that is otherwise sealed it
is the one remaining exit, so the standing rule is narrower than "offline":

> Nothing the microphone hears is written down — not to a log, not to a
> file, not to the profile. Only measurements of it are.

A measurement is a duration, a sample count, a character count, a
confidence, a real-time factor. What was actually *said* never appears.

## What the audit found

One line, live since the streaming preview landed:

```kotlin
runCatching { live.finish() }
    .onSuccess { if (it.isNotBlank()) Log.i(TAG, "stream preview ended: \"$it\"") }
```

`it` is the Zipformer preview's final text — the learner's sentence,
printed verbatim to logcat at the end of every turn. It now logs
`(${it.length} chars)`, which is what the line was actually for: proof the
preview decoded anything at all.

The other 83 log statements in the app were read individually. They carry
model file names, tensor shapes, thread counts, load times, endpoint frame
indices, VAD probabilities, decoder confidences and thermal headroom —
nothing with content in it. `core/llm`, `core/safety`, `core/profile`,
`core/packs`, `core/content` and `core/speech` log nothing at all.

Beyond logs, checked and clean:

- **No audio is persisted.** `AudioClip` is built in exactly one place, held
  for the length of one turn so the pronunciation coach can score the same
  audio that was transcribed, and dropped. Nothing writes a `.wav`.
- **The saved profile holds no speech.** Levels, XP, stickers, per-skill
  counters, parent settings — all numbers and ids.
- **The report file holds Tuki's words, never the learner's.** Long-pressing
  a generated reply flags it for the Parent Zone; the two call sites pass the
  tutor's line and are guarded so a learner turn cannot reach it.
- **The pronunciation gloss** never sees a transcript: its six call sites
  pass authored text, and the conversation room's is reachable only for
  `Speaker.TUTOR`.

## The gate

`MicPrivacyTest` in `core/speech` scans every `src/main` Kotlin file in the
repo and enforces two rules:

1. **No log anywhere interpolates a variable named after recognised speech**
   — `transcript`, `utterance`, `heard`, `spoken`, `hypothesis` and friends.
2. **No log in a file that touches the microphone interpolates an unnamed
   value.** A bare `$it` is what the leak above looked like: in a file that
   runs the recogniser, the value a lambda just produced is usually the
   sentence it just decoded, and an interpolation with no name is an
   interpolation nobody audited. `${it.message}` and `${it.length}` pass —
   they say what they take.

A third test checks that the scanner still matches things, because a source
scan that silently finds nothing passes forever.

Rule 2 is the one that would have caught the real bug, and it is deliberately
blunt: naming the value is a one-word cost, and it forces the question.

## Known limits

- `${it.message}` on a caught exception is allowed. Nothing in the ASR path
  puts a transcript in an exception message today, but that is a fact about
  the current code, not something the gate enforces.
- The scanner is a regex over source, not a compiler plugin. It reads
  `Log.*` and `println` calls; a transcript reaching logcat by some other
  route — `System.out` bound elsewhere, a third-party library's own logging —
  is outside its reach.
