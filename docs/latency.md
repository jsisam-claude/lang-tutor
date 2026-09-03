# Turn latency — measurements, budget, and the plan of record

Updated 2026-08-30. Sources: Pixel 9 device logs (this repo's `TukiLatency` /
`TukiAsr` / `TukiLlm` / `TukiTts` tags), the code map in the session that
built this file, and two rounds of Google-stack consultation (relayed).

## The validation path — what the child waits for after speaking

Measured on the code, not on a device, and the fix is ordering rather than
speed.

Between the finger lifting and "Great job!" the room used to do three things
in series: stop the capture and transcribe, run the pronunciation coach, then
judge and praise. Only the first and third are load-bearing. The verdict is
`WordMatch` over the transcript — a string comparison — and the coach's
per-sound colours gate nothing; the orchestrator's own class doc has always
said so. Awaiting the coach put a full inference of a ~320 MB wav2vec2 model
in front of the praise, plus a cold ONNX session build whenever a memory trim
had released it, for a result the verdict never reads.

Scoring now runs beside the turn instead of in front of it. The cost of
decoupling is that a slow score can arrive after the child has moved on, so a
result is published only if the attempt it describes is still the one on
screen; an epoch counter bumped on every new attempt and every item change is
what enforces that. Three tests pin the behaviour: a ten-second coach does not
delay the verdict, a fast coach still colours its own attempt, and a late one
never colours a later one.

What remains on that path, in order of size:

- **The praise itself.** `cheer()` synthesizes "Great job!" — measured at
  2,977 ms on device — and `SynthCache.get` returns null until three variants
  of a line exist, so the first three times a child gets something right they
  wait out a full synthesis each time. The cache is doing its job for the
  fourth onward and nothing for the moment that matters most, which is the
  beginning. The fix is to fill those variants during the dead time the room
  already has: while the state is `AwaitingChild`, both engines are idle and
  the child is deciding. One clip per idle window, cancelled the moment the
  mic opens, is enough to have the praise set warm before it is first needed.
- **`stopCapture` joining the capture thread**, up to 2 s in the worst case,
  though the speculative transcript usually makes that moot.
- **A full Whisper pass** when the speculation did not cover everything said.

## Measured (Pixel 9, E4B, warm)

| stage | number |
|---|---|
| ASR, 10 s ACFT Whisper | ~0.5–2 s per utterance (batch, after endpoint) |
| LLM decode, GPU+MTP | 7–8 est-tok/s (CPU+MTP 4–7, CPU 1.6); MTP acceptance 46–49 % |
| TTS RTF | 0.94 cool → 1.7–2.3 throttled; "Great job!" = 2,977 ms; one 53-phoneme group = 7,558 ms |
| Cold LLM load | 26.6–28.3 s, of which 22.5–23.8 s OpenCL kernel compile |
| End-to-end today (chat, throttled) | ~8–20 s of dead air before first audio |

The per-turn metric is `TukiLatency: first audio {ms}ms after {mark}` —
honest in all rooms since the chat mark moved to mic release.

## Targets

| metric | target |
|---|---|
| Acknowledgement sound | ~0.2 s (perceived response) |
| Words on screen | 1–2.5 s |
| First real audio | 2.5–4 s cool · ~4–5 s throttled |
| Inter-sentence gaps | 0 cool; roughly halved throttled |
| Cold load | a few seconds (compile cache; pushed, device-unverified) |

## Plan of record

1. **Synth-ahead of playback.** `PcmPlayer.play()` drains (deliberately — the
   mic opens after), so sentence N+1's synthesis waits for N to finish
   *sounding*. One-group lookahead via a producer/consumer removes every
   inter-sentence gap when RTF ≤ 1 and halves them throttled. Helps both rooms.
2. **Chat gets the lesson room's streaming.** ChatRoom collects the whole
   reply then batch-speaks; TutorOrchestrator already streams tokens →
   SentenceChunker → per-sentence safety → `speakStream` (first sentence
   synthesized alone). Same shape for chat; the `HE:` translation trails the
   reply and attaches when it arrives.
3. **Instant acknowledgement.** Pre-decoded PCM ack clips (a few variants)
   played via `AudioTrack MODE_STATIC` (<20 ms start; SoundPool adds 20–50 ms;
   a pre-warmed low-latency stream drains battery by keeping the DSP awake).
   Fire at mic release. Humans read a fast backchannel as "heard you", which
   buys the pipeline 1–2 s of natural-feeling time.
4. **`THREAD_PRIORITY_URGENT_AUDIO`** on the thread feeding the AudioTrack.
5. **Thermal-aware reply budget.** `Thermal.headroom` already exists; shrink
   reply tokens / first-sentence length as throttling rises so synth time
   cannot balloon past the budget.
6. **SynthCache in the streaming path** (currently bypassed): repeated praise
   and openers become ~0.2 s.
7. **Compile-cache corruption recovery.** The OpenCL cache can corrupt if the
   process dies while the driver serializes it (first load). On a
   GPU-with-cache load *failure*, wipe the cache dir and retry once before
   the latch demotes anything.
8. **Prompt nudge**: ask the model to open with a short phrase; the first
   sentence is the unit that gates audio.

## Endpointing — a reversal worth remembering

First consultation round (framed around responsiveness) said cut VAD trailing
silence 700 → 250–300 ms. The second round, asked properly about **low-
proficiency L2 speakers**, reversed it: within-utterance hesitations for
A1–B1 learners run **600–1,500 ms** (median ~700–900 ms) against 100–250 ms
for natives. A 300 ms cutoff would cut our actual users off mid-thought
constantly. **`VadGate.hangoverMs` stays at 700** (its original calibration),
and the responsiveness comes from overlap instead:

- **Tentative endpointing — BUILT** (`VadGate.softHangoverMs = 250` +
  `WhisperAsrEngine.maybeSpeculate`): at 250 ms of quiet a `SpeechSoftEnd`
  fires and transcription starts speculatively while the mic keeps
  listening; the 700 ms firm endpoint still decides the turn. No splicing
  was ever needed — capture is continuous, so a resumed word just makes the
  speculation wasted CPU (capped at latest + one draining). At the firm
  endpoint a speculation that covered every detected speech sample is
  adopted (~450 ms saved); one the child talked past is ignored. Works for
  push-to-talk too, since the VAD runs whenever installed.
- Drill rooms may close early on a target match — the expected answer is
  known there. (Still open; rides on the soft-endpoint machinery above:
  at `SpeechSoftEnd`, match the speculative transcript against the drill
  target and accept without waiting out the hangover.)

External sanity check (consultation round 3, unverifiable but low-stakes —
nothing here depends on it): production voice stacks reportedly speculate
at 200–300 ms of silence and hold L2 firm endpoints near ours, with L2
intra-utterance hesitations quoted at 600–1,200 ms — the same band round 2
gave. The 250/700 pair shipped on our own reasoning and stays on it.

Do not "fix" the 700 ms number for snappiness again without re-reading this.

### Streaming ASR — weights chosen and pinned (2026-08-31)

The next step past speculation is decoding DURING speech. Approved with a
bundling constraint (the weights must ship inside the APK/assets, no
download step), and the artifact is chosen:

- **k2/icefall streaming Zipformer transducer**, sherpa-onnx export
  `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`, Apache-2.0.
  Int8 encoder + fp32 decoder + int8 joiner ≈ **73 MB** (the fp32 encoder is
  262 MB — not bundleable). Chunk-16/left-128 variant: explicit cached-state
  tensors, so our existing ONNX Runtime drives it with no sherpa native lib.
- `scripts/fetch-asr-stream-assets.sh` fetches it SHA-256-pinned into
  `app/src/main/assets/asr-stream/` (gitignored, like kokoro/ and vad/).
  **Deliberately not wired into CI yet** — that happens with the engine, so
  no dead 73 MB rides the APK meanwhile.
- Division of labor when built: the streaming model feeds the heard-so-far
  preview, endpointing, and drill early-close with REAL partials; **Whisper
  stays the judged-transcript engine** until child/accent accuracy is
  measured.

Consultation round 4 (2026-08-31), validated against the actual encoder
graph before adoption — two of its central claims were wrong and one model
it recommended does not exist, so these are the checked facts:

- **Graph truth beats notes**: the int8 encoder has **99 inputs — `x`
  [N,45,80] plus 98 cached-state tensors** (consultation said "7–9"), and
  metadata `T=45`, `decode_chunk_len=32`: every call feeds a 45-frame fbank
  window and advances **32 frames = 320 ms = 5,120 new samples** at 16 kHz
  (consultation's 160 ms/2,560 was wrong for this export). The engine must
  read input names/shapes from the session at load, never hardcode them.
- **Features** (agrees with kaldi-native-fbank defaults; verify snip_edges
  against sherpa-onnx source when coding): 80 mel, 25/10 ms, povey window,
  dither 0 at inference, pre-emphasis 0.97, remove_dc_offset true. Keep a
  240-sample tail so chunk N+1's first frame windows correctly.
- **Hotword biasing is runtime-side, not in the graph** (confirmed: no such
  inputs). Greedy search cannot bias; sherpa does it with modified beam
  search + an Aho-Corasick trie over BPE tokens. v1 ships greedy without
  biasing — drills judge on Whisper anyway — and beam+trie is the later
  upgrade if constrained-vocab hit-rate wants it.
- **Endpointing**: sherpa's real defaults are 2.4 s trailing silence (rule
  1) and 1.2 s after any decoded token (rule 2); the consultation's "rule 3
  drill fast-cut" is not a sherpa rule. Irrelevant either way: our VadGate
  (250 ms soft / 700 ms firm) remains the endpointer; the streamer only
  feeds it.
- **Accuracy expectation**: LibriSpeech training means degraded WER on
  child and Hebrew-accented speech (direction credible, its numbers are
  guesses). The recommended "gigaspeech-2023-04-16" streaming export is
  **phantom** — no such public repo; the real alternatives are en-2023-02-21
  (127 MB int8 encoder — over the bundle budget) and a 20M-param small
  (43 MB, weaker). The 2023-06-26 pick stands; Whisper-as-judge is the
  accuracy hedge, and an icefall fine-tune on child corpora is the far
  option if the probe disappoints.
- **Co-residency**: dual-ASR residence is fine — Zipformer int8 ~85 MB +
  Whisper small.en ~240 MB beside the LLM (the consultation's table put
  E4B on the 8 GB tier; our policy runs E2B there, so headroom is better
  than its arithmetic). PCM crosses from the audio thread over a queue;
  inference on 2 XNNPACK threads at default priority; expected RTF well
  under 0.1. Drop Whisper only after measured parity.

## Dead ends, so they are not retried

- **Speculative LLM prefill from partial ASR** — Conversation API has no
  rollback (and no session clone in the 0.16.1 Kotlin AAR: only
  `nativeCreateSession`/`nativeDeleteSession`; verified by javap). Any
  divergence forces a full KV rebuild. Anti-pattern; dropped.
- **Two-stage generation** (tiny `maxOutputTokens` opener, then continue the
  same assistant turn) — not supported; every generate finalizes a turn.
  The supported shape is one streaming call chunked early, i.e. item 2.
- **ORT spinning knobs for TTS** — `session.intra_op.allow_spinning` (and
  possibly `spin_duration_us`/`spin_backoff_max`; reports conflict) govern
  ORT's own pool, but Kokoro's compute runs in **XNNPACK's pthreadpool**
  (ORT intra-op is already 1), which ignores session options. A
  `PTHREADPOOL_SPIN_WAIT` env-var was suggested but is unverified; worth one
  low-risk device experiment (set before session init), nothing more. Never
  touch Whisper's threading — it is an accuracy calibration
  (docs/asr-model-eval.md).

## Consultation validated against the binary (2026-08-30)

Two external models were consulted (relayed); both fabricated at least once,
so every claim below carries how it was checked:

- **The ORT spin keys exist** — `strings` on our shipped
  `libonnxruntime.so` (1.29.0) contains `session.intra_op.spin_duration_us`,
  `spin_backoff_max`, `allow_spinning`. Round 1's vagueness and my own doubt
  were wrong; round 2 was right.
- **ORT's own binary describes our exact situation**, embedded as a
  diagnostic: "The XNNPACK EP utilizes an internal pthread-based thread
  pool... Please set either intra_op_param.allow_spinning to 0 ... or the
  ORT intra-op threadpool size to 1." **We already run intra-op=1**
  (OnnxTuning), so the documented contention is already avoided and the
  spinning experiment is deprioritized.
- **`PTHREADPOOL_SPIN_WAIT` env var: no such string in the binary** (getenv
  names appear in strings; none do). Treated as fabricated.
- Round 1 fabricated a LiteRT-LM "Session Cloning" API (javap: no such JNI
  export) and claimed 3-word Kokoro synthesis at ~50 ms (we measured
  2,977 ms throttled). Round 2 corrected round 1 twice (endpointing,
  two-stage generation). Neither is citable without a check like these.

## Found by consultation, verified in code: the lesson room's KV leak

`TutorOrchestrator.buildRequest` sent the policy's per-turn guidance as a
leading `Role.SYSTEM` message; `LiteRtLmEngine` folds leading SYSTEM messages
into the conversation's system text; `ConvoReuse` requires that text to be
identical to reuse the KV cache. Consequence: **whenever the guidance changed
between turns (different move, Hebrew help), the lesson room re-prefilled the
entire conversation** — the same defect removed from the chat room with the
second parrot.

**FIXED**: the guidance now rides inside the user turn
(`TutorOrchestrator.guideWrap`), the system text is constant per session, and
the orchestrator keeps a ledger (`sentHistory`) of exactly what was sent —
the transcript cannot serve, because the conversation recorded the *wrapped*
user turns. Bonus from the ledger: a scripted `AskRepeat` between LLM turns no
longer breaks reuse either (the transcript-based window used to gain entries
the conversation had never seen). `KvReuseTest` drives real turns against the
real `ConvoReuse` rule and pins all of it: 3 turns with guidance changing
twice = 1 build + 2 reuses.

## Scorecard on the five follow-up recommendations

| rec | verdict |
|---|---|
| Static-prefix prompt topography | Misread our engine (ConvoReuse already keeps the conversation prefilled) — but led straight to the lesson-room KV leak above. Adopt as that bug fix. |
| 85 Hz HPF + peak normalize on raw PCM | Sensible; park with the barge-in / `UNPROCESSED` work. |
| Foreground service (`microphone`) for OOM priority | **Rejected.** Contradicts the deliberate no-services battery/privacy doctrine; in active use the activity is foreground anyway, and `onTrimMemory` is already implemented. |
| Hesitation-gap highlighting as fluency feedback | Nice product idea; needs streaming-ASR timestamps → parked with the P3 zipformer work. |
| Cap refresh to 60 Hz during decode | Plausible (UI composition shares the Mali with decode); cheap device A/B via `Surface.setFrameRate`. Experiment list. |

## Barge-in (full duplex) — design notes for later

Real conversation allows interrupting Tuki. What consultation says, recorded
for when we build it:

- Hardware AEC needs the echo *reference*, which hooks the voice path:
  playback must be `USAGE_VOICE_COMMUNICATION` — but that moves Tuki onto
  the **call volume stream**, a real UX cost (separate slider, different
  routing). The alternative is software AEC (WebRTC AEC3 / SpeexDSP) over
  `AudioSource.UNPROCESSED`.
- `VOICE_COMMUNICATION`'s AGC/NS degrade Whisper (clipped unvoiced
  consonants, skewed formants — exactly the sounds a pronunciation app
  cares about). So: AEC-filtered audio for **VAD only** (barge detection),
  raw audio for ASR. Concurrent-capture limits on one mic need a device
  check before committing to the split-stream design.

Consultation round 3 (2026-08-30) on the split-stream question — all of it
is device-behavior we cannot verify from a container, so every claim ships
as DEVICE-VERIFY, not fact (this round's sibling claim about NGSL's license
was already wrong, see docs/phrasebank.md):

- **Same-UID concurrent capture**: one app may hold two AudioRecords at
  once (`VOICE_COMMUNICATION` + `UNPROCESSED`); Android's cross-app capture
  policy restricts *apps*, not streams within one. Plausible and consistent
  with the documented sharing policy. DEVICE-VERIFY on Pixel 9: both
  streams actually deliver, at our rates, with the LLM loaded.
- **Digital isolation**: `UNPROCESSED` requests the raw input path, so AEC/
  NS attached to the other stream should not touch it. Check
  `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` before trusting the source
  exists at all.
- **Shared analog gain caveat**: the claim is that AGC on the voice stream
  can move the shared mic pre-amp and amplitude-modulate the "raw" stream.
  The suggested mitigation is a real API (`AutomaticGainControl.create(
  sessionId)` + `setEnabled(false)`) — but whether Pixel AGC is analog at
  all, and whether the platform honors the disable on a voice stream, is
  unverifiable here. Record levels on both streams side by side on device.
- **Post-firm grace window** (resume within 300–500 ms *after* the firm
  endpoint, splice, continue): considered and DECLINED. Our firm endpoint
  is already the L2-calibrated 700 ms; delaying commitment past it re-adds
  the exact latency the soft endpoint just removed. Barge-in is the right
  home for late resumption.

## Policy actions that fell out (not latency, but load-bearing)

On-device generation is policied identically to cloud; enforcement looks at
the output. Concretely for us: an **in-app report/feedback mechanism** for AI
output is required (already anticipated as the "report flow" in
`docs/feasibility.md` risk #5 — still unbuilt); local input/output safety
filtering must cover the standard categories (BlocklistSafetyFilter + the
per-room gauntlets are the start, and should be documented as such); and the
IARC questionnaire forces a choice — if generated output cannot be
guaranteed safe, the realistic rating is 12+, unless the young-child tracks
are constrained to scripted/templated content (which the track system could
express). That is a product decision, not an engineering one.
