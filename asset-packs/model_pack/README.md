# model_pack — Play Asset Delivery (install-time)

Model weights live here in production builds and are **never committed**
(`.gitignore` blocks weight extensions and this directory's contents).

Expected layout (see docs/architecture.md and docs/feasibility.md §7 for the
~4 GB per-device Play budget):

```
src/main/assets/models/
├── gemma4-e2b.litertlm        # LLM, ~2.58 GB (Gemma 4 E2B int4, Apache 2.0)
├── asr-en-small.q5.bin        # English ASR, ~250 MB (Whisper-class)
├── tts-en-kokoro.int8.onnx    # English TTS, ~80 MB
├── tts-he-phonikud.int8.onnx  # Hebrew diacritization+G2P, ~30 MB (CC-BY-4.0)
├── tts-he-voice.onnx          # Hebrew Piper-class voice, ~40 MB
├── gop-phoneme-ctc.onnx       # Pronunciation scorer, ~20 MB (P3)
└── vad-silero.onnx            # Voice activity detection, ~2 MB
```

Notes:
- Asset packs only materialize in **app bundles** (`:app:bundleDebug` /
  `bundleRelease`), never in `assembleDebug` APKs. Local testing with real
  models uses `bundletool --local-testing`. The fake-engine demo needs no
  assets at all.
- Install-time pack contents are served uncompressed on-device; the app reads
  them via the AssetPackManager path and mmaps files directly.
- If the total ever threatens the per-device budget, split rarely-used models
  (Hebrew ASR, E4B tier) into **on-demand** packs instead of growing this one.
