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

## The review

Six readers were set on the fixes with one job — break them — then two
independent lenses per finding, one to refute and one to judge the fix.
Thirty findings; twenty-six with a demonstrating input, every one of
which is now a test beside its fix. What the second pass found in the
*first* pass's fixes, which is the reason for a second pass:

- The numeral guard covered the wrong range. Twenty-plus digits were
  safe; 13–19 digits (≥ 2·10¹²) still indexed a twenty-entry table at
  twenty — and through the judge's *hearing* path, which re-enters the
  normaliser unwrapped, on the early close, in a coroutine with no
  handler. Now: the number table recurses past 999 of a scale, "$" never
  throws, and the key path swallows a front-end failure as "no key".
- The added-negation rule failed a learner's honest restart — "I don't…
  I don't like peas" — and a preface — "no, I like peas" — while a
  doubled negation inside the line ("I never never eat fish") slipped
  through as a stutter. Whisper keeps restarts and prefaces verbatim
  (measured: "I don't, I don't like peas."), so this was live. Now: an
  added negation counts only inside the matched span, a doubled one
  counts once, and a one- or two-word item counts it anywhere.
- "its" and "it's" are one sound, and the `'s → is` map broke that; "It
  has been raining" written out against Whisper's "It's been" was a
  substitution. The transcript's "X's" is now opened in the light of the
  target: "has" where the target says so, closed where the target holds
  the homophone, "is" otherwise.

Still open, by decision rather than oversight: a self-correction with
"no" in the middle of a line ("I see a, no, a red ball") fails — Whisper
garbles it anyway ("I see you now, a red ball") — and none of this was
measured on a child's voice.

### The six that were never judged

The review is easy to misremember as leaving a large tail open. It did not, and
the exact number matters because it was being carried in the backlog as
"18 unjudged findings".

Recovered from the review run's own record: six area readers raised **30**
findings and **24** verdicts came back, so **6 were never judged** — seven
distinct items once the two readers who independently raised the CMU-dictionary
load are counted once. Judged here against the tree as it stands, not as the
review saw it:

| finding | verdict |
|---|---|
| The 140k-line dictionary loads on the main thread at the first verdict when the gloss row is off | **already closed** — `AppContainer.kt` pays it on `Dispatchers.IO`, ahead of the voice warm-up |
| `join()` crashes when a middle part is digital silence | **already closed** — a part is claimed from both sides and can give up only what it has |
| A word whose start lands in the trimmed tail never highlights | **already closed** — the start is pinned inside what remains of its piece |
| Typed `...` and `--` do not earn the beat | **already closed** — `endsWithMark` reads them as the normaliser would |
| A later group's piece keys the cache differently for its leading space | **already closed** — the piece text is trimmed before the cache key |
| An en dash joining a compound becomes a pause token | **already closed** — `–` between letters stays a hyphen |
| Re-armed endpoints are silent, and their frame numbers restart | **half closed, now closed** |

Six of the seven were fixed by `c60161d` and `895ae94` — the two commits the
backlog assumed had left them open — each with a comment naming the case. That
is worth recording plainly: the tail was already handled, and the backlog item
was measuring the review's paperwork rather than the code.

The seventh was half done. `c60161d` made the second and later endpoints log as
`endpoint (again)`, closing the silent half. The other half stayed open:
`VadGate.reset()` zeroes `frame`, so a re-armed endpoint's frame numbers are
counted from the reset and two endpoints in one turn can print the same numbers
meaning different moments. Nothing computes a wrong answer from it — the slices
are taken inside one armed window — but a captured log misreads, and that is a
trap for whoever reads the first device traces (#74, #76). Documented at both
ends rather than papered over with a frame base nothing yet needs.

## 5. Two improvements, measured — one shipped, one not

Both use models already on disk; both were run through the kit before any
Kotlin was written. 738 clips: the census (perfect, two voices), the
minimal-pair control (wrong), the F6 populations (wrong and correct), and
the accent arm (perfect words in a Hebrew-accented rendering).

### Tell the recogniser what to listen for — NOT shipped

The drill hands the engine its expected line on every turn and the engine
threw it away. Whisper's decoder takes a text prompt; the line was fed as
one (`<|startofprev|>`, the line's tokens, then the usual prefix). A greedy
longest-match encoder over the bundled vocabulary produces the reference
tokenizer's ids for 94% of the bank's lines and the same text for the rest,
and the two were indistinguishable in the numbers.

| set | | no prompt | prompted with the line |
|---|---|---|---|
| census, perfect (324) | rejects | 13 | **0** |
| accent, mild (49) | rejects | 21 | **6** |
| accent, strong (49) | rejects | 38 | **7** |
| minimal pairs, WRONG (116) | passes | 48 | **110** — 110 of them the target verbatim |
| F6 substitutions, WRONG (33) | passes | 20 | **32** |
| F6 dropped negation, WRONG (35) | passes | 3 | **7** |
| F6 un-inverted question, WRONG (32) | passes | 0 | 0 |

The false-reject side is everything one could ask: every remaining
mishearing on the census is gone, and two thirds of the accented rejects.
The false-accept side is the same effect seen from the other end: shown
the line, the decoder writes the line — "three thin sings" becomes *Three
thin things*, "tree cookies" becomes *Three cookies*, "a shitty" becomes *A
city!* — for 110 of 116 mispronounced minimal pairs, which is every contrast
the twisters room teaches, and it credits four more reversed negations.
A prompt made of the line's words shuffled into a list, to bias spelling
without the sentence, broke the decode instead (117 rejects on the census).

So the prompt is gated off (`WhisperAsrEngine.PROMPT_WITH_HINT`), with the
encoder, the prefix-aware decoder and their tests kept. What it shows is
sharper than what it fixes: **no text-level trick can tell "misheard and
correct" from "mispronounced and wrong"** — the transcript is the same in
both cases. Only the acoustic model can, which is the case for the A/B
below.

### Let the coach confirm a reject — shipped, narrowly

On a text reject with audio, the drill now waits up to three seconds for
the coach it has already started, and accepts if the coach can vouch for
the attempt. Measured on 75 correct-but-rejected clips and 145
wrong-and-rejected ones, whole-line rule (every sound at least *x*):

| every sound at least | correct rejects rescued | WRONG rejects rescued |
|---|---|---|
| CLOSE (no sound wrong) | 20 / 75 | 10 / 145 |
| between | 17 / 75 | 3 / 145 |
| **GOOD** | **10 / 75** | **2 / 145** |

A rule that checked only the *missed word's* sounds was measured too and
was worse at every floor (it rescued un-inverted questions and dropped
negations — the coach aligns sounds in the target's order and cannot see
either). So the shipped rule (`CoachRescue`) is: only a reject for a word
left out or misheard, never a moved word or a negation; every sound GOOD by
the coach's own line; the line ≥ 0.8. The two wrong rescues left are a
vowel the coach cannot separate (*dead* for *Dad*). On a real voice the
coach scores lower, so it rescues less — the safe direction. It does
nothing for accented speech: the coach marks the accent's sounds wrong,
which is its job.

### What that leaves

The acoustic A/B for the contrast word — align the audio against the target
*and* against the declared Hebrew collapse, compare — remains the only
design that addresses the 38–58% of mispronunciations Whisper repairs. This
run confirmed why: both text-side levers that would have caught them also
catch the correct attempts, in the same proportion.

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
