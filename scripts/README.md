# scripts/

Everything here is optional tooling — the app's source builds without running
any of it. What the scripts do is fetch the large binary pieces that are
deliberately **not committed** (model weights, native libraries, voice data),
and regenerate a few committed data files from their upstream sources.

Two invariants hold across every script that touches the network:

- **Every downloaded byte is SHA-256-pinned.** A hash mismatch is a hard
  failure with the temporary file deleted — never a partial or unverified write.
  Changing an upstream version means changing a pin here, deliberately.
- **Re-running is safe and cheap.** A file already present and matching its pin
  prints `OK (cached)` and is not re-downloaded, so these are fine to put in a
  loop, a Makefile, or CI.

## Which one do I need?

| I want to… | Run |
|---|---|
| Build an APK with GPU, voice and hands-free working | the three `fetch-*` scripts |
| Put models on a phone | `download-sideload.sh` |
| Refresh a pinned upstream data file | a `gen-*` script |
| Re-certify the VAD test fixture after changing the model | `generate-vad-golden.py` |

---

## Build-time asset fetchers

Run before assembling the APK. CI runs all three (`.github/workflows/android-ci.yml`);
a local build that skips them still succeeds, it just loses the feature.

```bash
scripts/fetch-gpu-libs.sh && scripts/fetch-voice-assets.sh && scripts/fetch-vad-asset.sh
```

| Script | Writes to | Size | Skip it and… |
|---|---|---|---|
| `fetch-gpu-libs.sh` | `app/src/main/jniLibs/arm64-v8a/` | ~23 MB | GPU generation fails on devices without OpenCL (GrapheneOS) and the engine falls back to CPU |
| `fetch-voice-assets.sh` | `app/src/main/assets/kokoro/` | 522 KB | Tuki has no bundled voice; the app falls back to the platform TTS shim |
| `fetch-vad-asset.sh` | `app/src/main/assets/vad/` | 2.3 MB | The hands-free toggle is hidden and the mic stays push-to-talk |

Each is safe to run from any directory — they resolve the repo root from their
own path. Each script's header explains *why* its file is pinned where it is;
`fetch-gpu-libs.sh` in particular documents the LiteRT-LM dynamic-library
layout, and `fetch-vad-asset.sh` documents the Silero v6 input contract that
`SileroVad.kt` depends on.

## Getting models onto a phone

```bash
scripts/download-sideload.sh                 # all three device profiles
scripts/download-sideload.sh pixel-9a        # just one
scripts/download-sideload.sh --no-speech     # LLM only, skip ASR/TTS/coach
scripts/download-sideload.sh --apk           # also grab the newest green CI APK
```

**Run this one from the repo root** — its output path is relative, unlike the
fetchers. It produces one directory per device, sized to that device's RAM:

```
sideload/
├── _cache/                  fetched once, shared by hard-link
├── pixel-9a/                 8 GB  -> Gemma 4 E2B
├── pixel-9/                 12 GB  -> E4B + E2B (app picks per session)
└── pixel-10-pro-xl/         16 GB  -> Gemma 4 E4B
```

Each device directory gets the model(s) for that tier, a `speech/` folder, and a
generated `push.sh` holding the exact `adb` commands for it:

```bash
cd sideload/pixel-9a && ./push.sh
```

This is also the answer when a proxy or filter is intercepting TLS on the phone:
the download happens here, on your workstation, where the CA is already trusted,
and the files reach the device over USB. See
[docs/building-on-debian.md](../docs/building-on-debian.md) for that discussion.

`--apk` pulls the rolling `debug-latest` release asset with plain `curl` (the
repo is public, so no login is needed) and falls back to `gh` if that fails.

## Regenerating committed data (rarely)

These three write files that **are committed**, so that ordinary builds and
tests never need the network. Run them only to move a pin to a newer upstream
version — then review and commit the diff.

| Script | Regenerates | Notes |
|---|---|---|
| `gen-kokoro-frontend-data.sh` | `core/speech/…/resources/kokoro/` — CMUdict + the Kokoro phoneme vocab | Feeds the English G2P front-end |
| `gen-phonikud-frontend-data.sh` | `core/speech/…/resources/phonikud/` — Hebrew tokenizer vocab + phoneme map | **Currently unused** — see below |
| `generate-vad-golden.py` | `core/speech/src/test/resources/vad/silero-probs.json` | Needs `pip install onnxruntime numpy` |

`gen-phonikud-frontend-data.sh` still works and its data is MIT-licensed, but
nothing consumes it right now: the Hebrew *voice* it fed was CC-BY-NC and was
removed, so Tuki speaks English only
([docs/feasibility.md](../docs/feasibility.md) §6 has the licensing story). The
script is kept because the engine code is still in the tree, waiting on a
commercially licensed Hebrew voice.

`generate-vad-golden.py` is the one script that touches no network. It replays
a committed public-domain speech clip through the *currently pinned* VAD model
and records the per-frame probabilities that `VadGateTest` asserts against.
**Re-run it whenever `fetch-vad-asset.sh` changes the model**, or the gate's
thresholds end up certified against a distribution the app no longer produces:

```bash
scripts/fetch-vad-asset.sh && python3 scripts/generate-vad-golden.py
```

## Network reference

Useful when working behind a restrictive egress policy:

| Script | Hosts |
|---|---|
| `fetch-gpu-libs.sh` | `media.githubusercontent.com` (Git LFS) |
| `fetch-vad-asset.sh` | `raw.githubusercontent.com` |
| `fetch-voice-assets.sh` | `huggingface.co` |
| `download-sideload.sh` | `huggingface.co`, `github.com` (only with `--apk`) |
| `gen-kokoro-frontend-data.sh` | `huggingface.co`, `raw.githubusercontent.com` |
| `gen-phonikud-frontend-data.sh` | `huggingface.co` |
| `generate-vad-golden.py` | none — fully local |

Note that `dl.google.com` is absent: the Android SDK is a prerequisite of the
build, not something these scripts fetch. See
[docs/building-on-debian.md](../docs/building-on-debian.md) for SDK setup.
