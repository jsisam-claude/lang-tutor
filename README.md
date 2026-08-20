# Tuki 🦜 — On-Device Language Tutor (working title)

**A fully on-device English tutor for Hebrew-speaking children (ages 4–13) on
Pixel-class Android hardware.** Complete loop: *speech in → on-device LLM → speech
out*, plus a text tutor for reading/writing. The base install (models included)
**works fully offline forever**; optional quality packs (bigger models, more
voices, more content) download **only with explicit user approval — no automatic
update checks, nothing ever uploaded**.

> **Headline feasibility verdict (July 2026): feasible today on Pixel 9/10-class
> devices.** The full stack — Gemma 4 E2B (Apache 2.0) on LiteRT-LM, kid-tuned
> Whisper-class ASR, Piper/Kokoro English TTS, Phonikud Hebrew TTS, and in-house
> pronunciation scoring — fits in **~3.4–3.9 GB**, inside Google Play's ~4 GB
> per-device delivery budget, and answers a voice turn in **~2–4 seconds**.
> The four named risks: children's-voice ASR accuracy, unbenchmarked Hebrew LLM
> output, thermal limits on session length, and Play's GenAI-for-kids policy bar.
> Details, sources, and mitigations: [docs/feasibility.md](docs/feasibility.md).

## What's in this repo

| Path | What it is |
|---|---|
| [docs/feasibility.md](docs/feasibility.md) | The full feasibility review: hardware, models, speech stack, packaging, compliance, latency/quality expectations, risks — with sources. |
| [docs/product-phases.md](docs/product-phases.md) | The feature list elaborated into **5 development phases**, with the 5-level learner curriculum (pre-A1 → A2/B1) mapped across them. |
| [docs/architecture.md](docs/architecture.md) | System architecture (diagrams): engines, turn state machine, asset packs, safety layers. |
| [docs/mockups/](docs/mockups/) | 5 interactive HTML screen mockups + [`demo.html`](docs/mockups/demo.html), the animated product walkthrough the demo video is rendered from. |
| `app/`, `core/*` | Android scaffold (Kotlin + Compose): the phase-1 architecture with **fake engines** — the app runs a full scripted tutoring turn with zero model weights. |
| `asset-packs/model_pack/` | Play Asset Delivery stub where model weights live in production (never committed). |

## Building the scaffold

Two lanes, because the full Android build needs Google's Maven/SDK servers:

```bash
# Logic modules + unit tests (pure JVM — runs anywhere, no Android SDK):
./gradlew -Plangtutor.jvmOnly=true build

# Full app (requires the Android SDK; also runs in CI):
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:bundleDebug          # AAB — exercises the model asset-pack
```

Requirements: **JDK 25** (Debian trixie: `openjdk-25-jdk`; also builds on 21). Gradle 9.5.1
via the committed wrapper — JDK 25 needs Gradle 9.x, because the Kotlin the Gradle
distribution embeds to compile our `.kts` files could not parse a "25.x" version string
until Kotlin 2.2 (Gradle 8.14.3 embeds 2.0.21 and fails). Gradle 9.6+ is NOT usable yet:
AGP 8.13 relies on a Gradle internal API removed there, so 9.5.1 is the ceiling until AGP 9.
CI (`.github/workflows/android-ci.yml`) builds both lanes on every push.

Notes:
- Network policy (scope update 2026-07-19): the app holds INTERNET **only** for
  user-initiated enhancement-pack downloads (Parent Zone → Packs, consent dialog,
  manual update checks only). All network code is confined to `core/packs`'
  implementation; no telemetry exists anywhere.
- Hebrew UI uses `values-iw/` resources (Android's canonical Hebrew qualifier) with
  BCP-47 `he` in `localeConfig`; per-app language switching works from minSdk 31 via
  AppCompat.
- Real engines (LiteRT-LM, sherpa-onnx, …) drop in behind the interfaces in
  `core/llm` and `core/speech`; `app/.../AppContainer.kt` is the single swap point.

## Status

Research + spec + executable scaffold. No model weights are included; all engines are
fakes proving the architecture. The docs are the deliverable of a feasibility study
dated **2026-07-19**; figures marked ⚠️ are medium/low-confidence (see sources in
[docs/feasibility.md](docs/feasibility.md)).
