# model_pack — Play Asset Delivery (install-time)

Model weights live here in production builds and are **never committed**
(`.gitignore` blocks weight extensions and this directory's contents).

Expected layout (see docs/architecture.md and docs/feasibility.md §7 for the
~4 GB per-device Play budget):

```
src/main/assets/models/
├── gemma-4-E2B-it.litertlm            # LLM, ~2.59 GB (Gemma 4 E2B, Apache 2.0)
├── whisper_medium_30s_i4.tflite       # English ASR, ~664 MB (bundled Whisper; WIRED)
├── model_q8f16.onnx                   # English TTS, ~86 MB (Kokoro q8f16; WIRED)
├── tts-he-phonikud.int8.onnx          # Hebrew diacritization+G2P, ~30 MB (CC-BY-4.0)
├── tts-he-voice.onnx                  # Hebrew Piper-class voice, ~40 MB
├── gop-phoneme-ctc.onnx               # Pronunciation scorer, ~20 MB (P3)
└── vad-silero.onnx                    # Voice activity detection, ~2 MB
```

The first three exist today and are what the app's engines load from
`files/models` (installed via Parent Zone packs / import / sideload — see
TESTING.md); the exact names and SHA-256 pins live in
`core/packs/src/main/resources/packs/catalog.json`. The rest are planned
(docs/product-phases.md).

Notes:
- Asset packs only materialize in **app bundles** (`:app:bundleDebug` /
  `bundleRelease`), never in `assembleDebug` APKs. Local testing with real
  models uses `bundletool --local-testing`. The fake-engine demo needs no
  assets at all.
- Install-time pack contents are served uncompressed on-device; the app reads
  them via the AssetPackManager path and mmaps files directly.
- If the total ever threatens the per-device budget, split rarely-used models
  (Hebrew ASR, E4B tier) into **on-demand** packs instead of growing this one.
