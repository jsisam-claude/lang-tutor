# Turn latency — measurements, budget, and the plan of record

Updated 2026-08-30. Sources: Pixel 9 device logs (this repo's `TukiLatency` /
`TukiAsr` / `TukiLlm` / `TukiTts` tags), the code map in the session that
built this file, and two rounds of Google-stack consultation (relayed).

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

- **Tentative endpointing with audio splicing** (standard pattern): at the
  700 ms soft endpoint, *start* ASR (and downstream work) speculatively; if
  speech resumes within a ~500 ms grace window, cancel, splice the buffered
  audio, continue capturing. Latency of the aggressive cutoff, safety of the
  long one.
- Drill rooms may close early on a target match — the expected answer is
  known there.

Do not "fix" the 700 ms number for snappiness again without re-reading this.

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
