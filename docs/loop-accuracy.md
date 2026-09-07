# The speech loop, measured — false rejects, false accepts, the missing beat

Added 2026-09-07. The report the user filed, in their words: *"they sometimes
say it correctly and it would ask to repeat"*; *"sometimes it's wrong and
they would get stars"*; *"'almost,…' is missing a small pause"*. This file
is what was found, with the numbers, and what changed.

## How it was measured

Every model the app ships is on disk in the repo — Kokoro, Whisper,
Zipformer, wav2vec2, Silero — so the loop can be **run**, not reasoned
about. A Python kit mirrors the Kotlin stage for stage (the Kotlin is the
source of truth; every knowing divergence is listed at the top of the kit)
and drives the real weights. The verdicts below come from the **compiled**
`WordMatch`, called from a Java runner over the same transcripts.

The audio is Kokoro's own voice played into Whisper — the friendliest input
either model will ever get. Every rate here is therefore a **floor**. A real
Hebrew-accented speaker is worse: the run's accent arm (a phonology
transform on the synthesised speech, not a person) measured 12% rejects
with a mild accent and 61% with a strong one, against ~3% clean. Nothing in
this file touches that; see *What needs a real speaker* at the end.

## 1. "Said it right, told to repeat" — false reject

**Cause.** The judge compared *spelling*. `WordMatch.allowedMisses(words) =
words / 4` is zero for every 1–3 word item — a third of what a Level-1
learner meets, and 100% of a WORDS round — and the comparison was
lowercased string equality against a transcript nothing normalised. On
those items one of Whisper's spelling choices *was* the verdict:

| said (Kokoro, perfectly) | Whisper wrote | verdict before |
|---|---|---|
| Are you OK? | Are you okay? (12/12 voices × speeds) | Almost! Listen again. ×3, then failed |
| Ten fingers. | 10 fingers | failed |
| I see cereal. | Icy cereal. / IC serial. | failed |
| Three pinecones. | Three pine cones. | failed |

Census, every allowance-0 item in the bank × 2 voices = 324 trials: **30
false rejects (9.3%)**, 9 items failing in both voices — items no speaker
could pass. The app's own coach disagreed with its own judge on that audio:
re-scoring the rejected clips with wav2vec2 against the target phonemes gave
0.916 (4.2 stars).

**Fix** (`WordMatch`, `Pronunciation`). Both sides go through the voice's
own spoken-form normaliser first, so "10" is "ten". Then two tokens are the
same word when they are spelled the same **or sound the same**: the judge
phonemises each with the app's own front end and compares stress-stripped
phonemes for *exact* equality. One word written as two, or two as one
("pine cones", "Icy", "goodnight"), joins when the letters or the sounds
concatenate exactly.

**Measured after.** Census **30 → 13** rejects; the level-balanced sweep
**4 → 1**; **0 regressions** across 1,125 previously-passing trials. Every
remaining reject is Whisper mishearing ("Assured" for *A shirt*, "That is"
for *Dad is*, "in Apple" for *An apple*) — the model floor.

**Why exact, not close.** The run also measured a phonetic edit-distance
fallback. At any slack that rescued "OK" it also passed "three ship" for
*three sheep*, "tree thin things" for *three thin things*, "sin and swing"
for *sing and swing* — 80–92% of the minimal-pair control — which is most of
the fifteen contrasts the twisters room exists to teach. Slack is zero.

**The other cause, code-read and unambiguous but never driven on a device**
(`WhisperAsrEngine`). The drill is push-to-talk; it never awaits the VAD's
endpoint. But once the firm endpoint fired (700 ms of quiet) the capture
loop skipped the detector, so `lastSpeechSample` froze at the pause. The
adoption test at `stopCapture` — "did they talk past the speculation?" —
compared against a snapshot taken *after* that and was therefore always
satisfied: the transcript of the first clause was adopted and everything
said after the pause was dropped, while the coach was handed the full clip.
The doc that set 700 ms says these learners hesitate 600–1,500 ms
mid-sentence. The detector now runs for the whole hold, and the gate re-arms
after an endpoint so a resumed clause gets its own speculation covering both
halves. Separately, `VadGate.hangoverMs` was rounding *down* to whole
frames, firing at 672 ms; it now rounds up (704).

## 2. "Said it wrong, got a star" — false accept

**The dominant channel is the recogniser, and it is not a bug.** Building
the control the app never had — the same sentence with one sound wrong,
using the contrasts `twisters.json` itself declares, synthesised and run
through the shipped Whisper — **44 of 116 (38%) came back spelled as the
target.** Whisper is a language model; it repairs the mispronunciation
before the judge sees it. No transcript-based judge can catch those. What
would: for an item whose whole point is one contrast, an acoustic A/B —
force-align the audio against the target *and* against the declared Hebrew
collapse, and compare. The wav2vec2 model on disk can answer "which of these
two did they say"; it is a far easier question than "what did they say",
and `RecognitionHint.ConstrainedVocab` — passed by the drill on every turn
and read by nothing — is the seam where that was clearly meant to live.

**The judge's own channel was structural, and is closed.** `allowedMisses`
was a budget of *missing* target words and extra words cost nothing, so a
*wrong* word cost exactly one — the target word went missing, the wrong
one was an extra. On every 4+ word target (93% of the bank) one word could
be wrong or absent and the room said "Great job!". Counted against the
corpus with the compiled judge: a real-word substitution built from the
contrasts `twisters.json` declares passed **740 of 759** lines; deleting
the negation passed **155 of 156** negative lines; saying a question as a
statement passed **174 of 175** — the LCS that *marks* the moved word for
the karaoke cost one miss, which the allowance paid.

The allowance now forgives a word **left out** and nothing else. The judge
sorts what was not said by kind — omitted, substituted (a different word
in the target word's place), moved (present, out of order), or a dropped
negation — and the last three fail the line. Contractions are opened on
both sides first so "don't"/"do not" cannot register as a substitution.
Measured: the three populations go to **0 of 759, 0 of 156, 0 of 175**.

**Through audio** — 100 lines of each kind, the wrong utterance *and* the
correct one, synthesised and run through the shipped Whisper into the
compiled judge:

| said wrong | Whisper wrote the target anyway | of the rest, passed before → now |
|---|---|---|
| one word substituted | 60 of 104 (58%) | 39 → **2** |
| negation dropped | 10 of 100 | 90 → **0** |
| question said as a statement | 0 of 100 | 96 → **0** |

The first column is the model floor from §2 again, larger still on 4+ word
lines: a substitution inside a sentence gives the language model more
context to repair it with. Nothing below the judge can reach those.

**What it costs**, on perfect utterances: nothing on the short-item census
(13 → 13); **6 new rejects in 300** on the long-sentence sweep; and on the
correct utterances of the 304 lines above, **+6 (2.0%)** — every one a
mid-sentence word Whisper misheard on Kokoro's own audio: *toy* → "tie",
*bread* → "brat", *chickens* → "check-ins", *presents* → "presence", and
*mouth* → "mouse", which is the very θ→s collapse the twisters room
teaches, so no text judge can tell that learner from one who really said
"mouse". Under the old rule those were the one forgiven miss. Under the new
one the learner hears "Almost! Listen again" with the word marked, and
after three tries moves on regardless — the teaching behaviour, not the
failing one. On a real accented speaker the number will be higher; it is
the price of a judge that can fail the one word a line exists to teach,
and it is recorded here so it can be re-weighed when device data exists.

## 3. The missing beat

Reproduced. Kokoro's only pause is a punctuation token, and how long it
lasts depends on the whole utterance. Measured on bank lines (longest quiet
within a quarter-second of the comma, −25 dB re peak): a comma buys ~196 ms
on an 8–9 word line, ~149 on 6–7, and on lines of five words or fewer it
buys nothing — "One cake, please." 55 ms with the comma, 55 without; "This
ship, these sheep." 55 versus 50. Eleven twister lines carry a comma, and
there the comma *is* the lesson.

Three things were tried; two are recorded because they failed:

- **A heavier token.** `…`, `—`, `;`, `:`, `,…` — measured on thirteen short
  lines, means of 118–149 ms; "One cake, please." never above 120.
- **Splicing silence into the render** at the karaoke's estimated word
  boundary. The estimate is proportional and was **150–350 ms off** on seven
  of thirteen lines; a search around it found a *different* gap — a stop
  closure inside a word — and put the silence there. Withdrawn.
- **Cut the line at its marks, render each piece, join with a beat**
  (`CommaBeat`). Kokoro pads every render with ~400 ms in front and ~500
  behind; the seam is trimmed down to the target from that padding, farthest
  from the speech, and only ever below −40 dB of the render's peak. Measured
  through the compiled code on all fourteen short comma lines: **every seam
  185–235 ms, mean 199**. Cost: the intonation across the comma, which on
  these lines the model was not delivering anyway. Long lines are left to
  the model. Needs a listening check on the twister lines — the numbers say
  the beat is there; whether the two halves *sound* like one line is not a
  number.

Also: `…` and `—` are in Kokoro's vocabulary (its two longest mid-line
pauses) and were being dropped before encoding, so an ellipsis rendered
bit-identical to no punctuation. Restored, and `...` / `--` map to them.
Zero authored lines use either; the chat room's model writes both.

## Also found on the way

- **The -ed skill was fed by every /t/ and /d/.** `SoundSkills` claimed the
  phones of "t d ɪd" for the *-ed* sound, so every "cat" and "dog" was
  evidence about past-tense endings and the skill read as mastered after
  three lines. A suffix is not a phone; it is now not recorded.
- **The streaming preview's frontend saturates the encoder.** `KaldiFbank`
  scales samples by 32768; the shipped Zipformer export expects [−1, 1], and
  its conv-embed writes SwooshR as `log(1+exp(x−c))`, which overflows — every
  frame comes back NaN and the transducer emits only blank. With
  `tryStreamingAsr` on, the preview is empty *and* the Whisper speculation
  is suppressed, so the early close never fires. Established with
  sherpa-onnx on the same files and probe models reading the encoder's
  input back out. Off by default. Fixed: sample scale 1,
  `snip_edges=false`, `high_freq=−400`, an online frame rule for the
  stream, the golden regenerated at the recipe's settings — the Kotlin
  frames now decode identically to the reference.
- **The coach cannot see aspiration** — `logp(pʰ)` sits ~10 nats below
  `logp(p)` on *correct* audio — and GOP as written is a single-frame
  measure (81% of frames decode blank, so Viterbi gives 112 of 118 phones
  exactly one frame). Both are scorer limits, not thresholds.

## What needs a real speaker

Every number above is the app listening to its own voice. To re-ground the
false-reject rate, the false-accept rate, the GOP thresholds and the skill
attribution at once: 20–30 learners at Levels 1–3, ~30 items each, recorded
on the phone through the real `AudioRecord` path, each attempt labelled by a
person. Nothing else here is worth doing twice before that exists. The
capture-path fixes (endpoint tracking, hangover) are code-read and pinned by
unit tests, and were never driven against a microphone.

Repro: the kit and every experiment live in the session scratch dir under
`loop/`; `loop/fix/Judge.java` drives the compiled judge and joiner.
