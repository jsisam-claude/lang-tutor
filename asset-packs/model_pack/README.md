# model_pack — Play Asset Delivery (install-time)

Model weights live here in production builds and are **never committed**
(`.gitignore` blocks weight extensions and this directory's contents).

Expected layout (see docs/architecture.md and docs/feasibility.md §7 for the
~4 GB per-device Play budget):

```
src/main/assets/models/
├── gemma-4-E2B-it.litertlm            # LLM, ~2.59 GB (Gemma 4 E2B, Apache 2.0)
├── acft_whisper_small.en_10s.tflite   # English ASR, ~286 MB (docs/asr-model-eval.md)
├── model_q8f16.onnx                   # English TTS, ~86 MB (Kokoro q8f16)
├── phonikud-1.0.int8.onnx             # Hebrew nikud + G2P, ~308 MB (MIT) — currently unused: the Hebrew VOICE it fed was CC-BY-NC and removed
└── wav2vec2-phoneme-int8.onnx         # Pronunciation scorer, ~318 MB (CTC-GOP)
```

All remaining models are WIRED: the app's engines load them from `files/models` today
(installed via Parent Zone packs / import / sideload — see TESTING.md), and the
exact names and SHA-256 pins live in
`core/packs/src/main/resources/packs/catalog.json`. Voice activity detection is
not listed because that model (2.3 MB) ships inside the APK itself.

Note the total: ~3.65 GB with the E2B brain, against a ~4 GB per-device Play
budget. The E4B quality tier does not fit alongside the speech stack, which is
why it stays an on-demand pack.

Notes:
- Asset packs only materialize in **app bundles** (`:app:bundleDebug` /
  `bundleRelease`), never in `assembleDebug` APKs. Local testing with real
  models uses `bundletool --local-testing`. The fake-engine demo needs no
  assets at all.
- Install-time pack contents are served uncompressed on-device; the app reads
  them via the AssetPackManager path and mmaps files directly.
- If the total ever threatens the per-device budget, split rarely-used models
  (Hebrew ASR, E4B tier) into **on-demand** packs instead of growing this one.
