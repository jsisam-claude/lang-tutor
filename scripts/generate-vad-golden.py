#!/usr/bin/env python3
"""Regenerates core/speech/src/test/resources/vad/silero-probs.json — the
golden fixture VadGateTest endpoints against — from the PINNED VAD model.

Run it whenever the bundled model changes (scripts/fetch-vad-asset.sh), so the
gate's thresholds are always certified against the distribution the app
actually ships. The clip is the FIRST PHRASE (~2.2 s, "And so, my fellow
Americans") of samples/jfk.wav from whisper.cpp (public-domain 1961 JFK
inaugural excerpt, 16 kHz mono), committed next to the fixture as clip.wav —
the full recording's rhetorical pauses exceed the gate's hangover and split
the utterance. 1.5 s of silence is padded on both ends so the gate has a
clean silence -> speech -> silence shape to endpoint.

Usage:  scripts/fetch-vad-asset.sh && python3 scripts/generate-vad-golden.py
Needs:  pip install onnxruntime numpy
"""
import hashlib
import json
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "app/src/main/assets/vad/silero_vad.onnx"
CLIP = ROOT / "core/speech/src/test/resources/vad/clip.wav"
OUT = ROOT / "core/speech/src/test/resources/vad/silero-probs.json"

FRAME = 512          # SileroVad.FRAME
CONTEXT = 64         # SileroVad.CONTEXT — v6 prepends the previous frame's tail
SR = 16_000
PAD_S = 1.5

with wave.open(str(CLIP)) as w:
    assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2
    pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768.0
pad = np.zeros(int(PAD_S * SR), np.float32)
pcm = np.concatenate([pad, pcm, pad])

so = ort.SessionOptions()
so.intra_op_num_threads = 1
sess = ort.InferenceSession(str(MODEL), so, providers=["CPUExecutionProvider"])

state = np.zeros((2, 1, 128), np.float32)
context = np.zeros(CONTEXT, np.float32)
sr = np.array(SR, np.int64)
probs, rms = [], []
for i in range(0, len(pcm) - FRAME + 1, FRAME):
    frame = pcm[i : i + FRAME]
    window = np.concatenate([context, frame])[None, :]
    out = sess.run(None, {"input": window.astype(np.float32), "state": state, "sr": sr})
    probs.append(float(out[0].reshape(-1)[0]))
    state = out[1]
    context = frame[-CONTEXT:]
    rms.append(float(np.sqrt(np.mean(frame**2))))

# Energy truth: first/last frame with RMS above 10% of the clip's peak RMS.
rms = np.array(rms)
loud = np.flatnonzero(rms > 0.1 * rms.max())
truth = [int(loud[0]), int(loud[-1])]

OUT.write_text(
    json.dumps(
        {
            "model": "silero-vad v6.2.1",
            "modelSha256": hashlib.sha256(MODEL.read_bytes()).hexdigest(),
            "clip": "clip.wav (first phrase of whisper.cpp samples/jfk.wav, public domain) + 1.5s silence pads",
            "generator": "scripts/generate-vad-golden.py",
            "sampleRate": SR,
            "window": FRAME,
            "probs": [round(p, 5) for p in probs],
            "energyTruth": truth,
        },
        indent=None,
        separators=(",", ": "),
    )
    + "\n"
)
print(f"{len(probs)} frames, energyTruth={truth}, "
      f"in-speech mean={np.mean([p for i,p in enumerate(probs) if truth[0]<=i<=truth[1]]):.3f}")
