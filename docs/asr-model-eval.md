# ASR model evaluation — why the bundled recognizer is a 10-second export

**Verdict:** the bundled English ASR is
`acft_whisper_small.en_10s.tflite` (286 MB, litert-community/whisper-acft).
It replaces `whisper_medium_30s_i4.tflite` (664 MB), which the app used before.
On our test set it is **~12× faster and 2.3× smaller at the same accuracy**, and
it degrades more gracefully when the CPU is busy.

Measured in-container (4-core x86 container, LiteRT `ai_edge_litert` interpreter
— *not* a Pixel). Treat the absolute milliseconds as a lower bound on quality of
information about the phone; the *ratios* between models are what transfers.

## Method

Three child-length phrases from unit 001, synthesized once with our own shipped
Kokoro voice, cached as 16 kHz PCM, and replayed to **every** model from the
same `.npz` — so the models see byte-identical audio. Decode is our own greedy
loop with the same causal mask and the same argmax bound the app uses
(`content tokens + EOT` only), so the harness exercises the exact binding the
Kotlin engine implements. Token layout is derived from each model's own vocab
size, which is how `WhisperAsrEngine` picks it too.

Three passes per model. "Correct" means the transcript matches the reference
ignoring case, final period, and the Noa/Noah spelling (Whisper has no way to
know which spelling a Hebrew-speaking family uses).

Harness: `ab_eval.py` in the eval scratch dir (not committed — it depends on
`transformers`, `ai_edge_litert` and a Kokoro checkpoint, none of which belong
in the app repo). The phrases are the ARPABET forms already in
`core/content/.../unit-001.json`.

## Results — 2 threads on a 4-core host

| model | size | window | correct | latency (median) | RTF |
|---|---|---|---|---|---|
| **acft_whisper_small.en_10s** | **286 MB** | 10 s | **9/9** | **0.60 s** | 0.29–0.84 |
| acft_whisper_base.en_10s | 101 MB | 10 s | 8/9 | 0.27 s | 0.14–0.26 |
| whisper_medium_30s_i4 *(previous)* | 664 MB | 30 s | 9/9 | 7.1 s | 4.0–8.4 |

The 30 s medium export is **accurate** — on a quiet CPU it got all nine right.
It is simply far too slow: RTF 4–8 means a 1.7-second answer costs ~7 seconds of
compute, which is exactly the "each utterance takes several seconds" behaviour
testers reported. The 10 s small.en export runs the same three phrases at
RTF ≈ 0.3 with no accuracy cost, because it encodes 1000 mel frames instead of
3000 and carries a 768-wide encoder instead of 1024.

`base.en` is another 2× faster and 100 MB smaller, but it broke once in nine
(`".(I see a red ball)"`) and in an earlier run answered the same phrase with a
single unrelated word. On adult-clear synthetic speech that is already the
margin; real children in real rooms have less of it. Not chosen — but it is the
obvious fallback if the 9a turns out to be memory-constrained, and the engine
would load it unchanged.

## The 30-second padding failure is real but intermittent

Whisper's 30 s exports pad a 1.7-second utterance with 28 seconds of silence,
and the decoder sometimes keeps generating into that emptiness. We saw it:

```
ref="I see a red ball"   got="I總是看到了一顆紅色的球"
ref="My name is Noa"     got="merrily.� Starting today. My name is Noah."
ref="The bear is blue"   got="The bear is blue Heh, I'm정신이 없네 Against the bear is blue 타지"
```

**Correction to an earlier note in this repo:** those transcripts were recorded
while the container ran 4 interpreter threads on 4 cores, i.e. saturated. They
were described at the time as the model's normal behaviour on short child
speech. That was wrong. Unsaturated, the same model and the same audio
transcribe cleanly (9/9 above); the drift showed up in 1 of 9 samples at
2 threads and 4 of 6 at 4 threads. The padding failure mode is genuine, and the
10 s window removes it by construction, but it is a tail risk under load, not
the common case. The speed and size numbers, not this, are the reason for the
swap.

## Thread count changes the transcript, not just the speed

The unexpected finding. These are dynamic-range-quantized graphs; XNNPACK
partitions the reductions by thread count and, under contention, not always the
same way twice. Different summation order → slightly different logits →
a flipped argmax on a marginal frame → a different word. Same model, same audio,
same process:

| threads (of 4 cores) | acft small.en correct | whisper medium correct |
|---|---|---|
| 1 | 6/6 | — |
| 2 | 17/18 | 8/9 |
| 4 (saturated) | 9/12 | 2/6 |

Consequence for the app: `WhisperAsrEngine.THREADS` was **6**, chosen for the
664 MB model when latency was the only problem worth solving. On a Pixel 9a
(8 cores, 4 of them small A520s) six threads spans both clusters and oversubscribes
the big ones. It is now **4** — half the cores, the ratio that was stable here —
which the ACFT model can afford, since it has ~10× the latency headroom it needs.

**Confirmed on device, the hard way (2026-08-27).** `THREADS` was changed from
4 to 3 as part of a *thermal* thread budget shared with the ONNX engines —
without reading the paragraph above, which sits directly beside it in the same
file. Recognition degraded noticeably in ordinary use, reported by the user
before any log showed it, and restored by putting the 4 back. The count is an
accuracy calibration wearing a performance-looking name; it does not belong to
whichever budget happens to be passing. It is now a plain constant again with
that stated in the comment.

**DEVICE-VERIFY:** this is the one conclusion drawn from a container that may
not transfer. A phone's big/little split is not a busy x86 box. If `TukiAsr`
timings on the 9a show plenty of headroom, 6 threads may be both fast and
stable there; if transcripts wobble between identical attempts, that is this
effect and the number should go down, not up.

## Honest limits of this evaluation

- **The test signal is synthesized speech, not children.** Kokoro at default
  speed is clearer than any 6-year-old. This set can rule a model *out*; it
  cannot certify one for the actual users. The real evaluation is the 9a with
  real kids, which is why `TukiAsr` logs mel/encode/decode timings per turn.
- Nine samples per model. Enough to see a 12× latency gap; not enough for a WER
  number, and no WER is claimed here.
- Kokoro's ONNX synthesis is itself not bit-reproducible across runs, which is
  why the audio is cached once and shared. Earlier numbers in this repo that
  predate the cache compared models on *slightly different* audio.
- x86 container, not Tensor. No GPU/NPU path was exercised for ASR at all.

## What ships

- Catalog pack `asr-en-acft-small` → `models/acft_whisper_small.en_10s.tflite`.
- `scripts/download-sideload.sh` fetches it for all three device dirs.
- `AppContainer.ASR_CANDIDATES` prefers it but still recognizes
  `whisper_large_v3_turbo_30s_i4.tflite` and `whisper_medium_30s_i4.tflite`, so
  a device that already has a 30 s model keeps working after an app update —
  the engine reads window size, encoder shape and token layout from the model's
  own signatures (`WhisperAsrEngine.describe`), so both generations run on one
  code path.
