# Tuki 🦜 — On-Device Language Tutor (working title)

**A fully on-device English tutor for Hebrew-speaking children (ages 4–13) on
Pixel-class Android hardware.** Complete loop: *speech in → on-device LLM → speech
out*, plus a text tutor for reading/writing. The base install (models included)
**works fully offline forever**; optional quality packs (bigger models, more
voices, more content) download **only with explicit user approval — no automatic
update checks, nothing ever uploaded**. Offline is not the whole promise:
what the microphone hears is never written down either, not even to logcat —
see [docs/privacy.md](docs/privacy.md).

> **Headline feasibility verdict (July 2026): feasible today on Pixel 9/10-class
> devices.** The full stack — Gemma 4 E2B (Apache 2.0) on LiteRT-LM, kid-tuned
> Whisper-class ASR, Kokoro English TTS, and in-house pronunciation scoring —
> fits in **~3.4–3.9 GB**, inside Google Play's ~4 GB
> per-device delivery budget, and answers a voice turn in **~2–4 seconds**.
> The named risks: children's-voice ASR accuracy, unbenchmarked Hebrew LLM
> output, thermal limits on session length, Play's GenAI-for-kids policy bar,
> and a licensing gap on Hebrew TTS (the only permissive-enough voice found so
> far is CC-BY-NC — Tuki currently speaks English only; Hebrew shows as text).
> Details, sources, and mitigations: [docs/feasibility.md](docs/feasibility.md).

## What's in this repo

| Path | What it is |
|---|---|
| [docs/feasibility.md](docs/feasibility.md) | The full feasibility review: hardware, models, speech stack, packaging, compliance, latency/quality expectations, risks — with sources. |
| [docs/product-phases.md](docs/product-phases.md) | The feature list elaborated into **5 development phases**, with the 5-level learner curriculum (pre-A1 → A2/B1) mapped across them. |
| [docs/architecture.md](docs/architecture.md) | System architecture (diagrams): engines, turn state machine, asset packs, safety layers. |
| [docs/practice-flavor.md](docs/practice-flavor.md) | The two build flavors: `full` (Pixels — conversation with the on-device model plus every practice room) and `practice` (Samsung Galaxy Tab S10 FE — the authored curriculum with no model at all, its speech models built into the APK). Device research, what is in and out of each APK, the one-folder pack import, and the first tablet test plan. |
| [docs/mockups/](docs/mockups/) | 6 interactive HTML screen mockups + [`demo.html`](docs/mockups/demo.html), the animated product walkthrough the demo video is rendered from. [`tense-room.html`](docs/mockups/tense-room.html) is the specimen sheet for the proposed tense room — every item kind, every state, both layout directions. |
| `app/`, `core/*` | Android scaffold (Kotlin + Compose): the phase-1 architecture with **fake engines** — the app runs a full scripted tutoring turn with zero model weights. |
| `asset-packs/model_pack/` | Play Asset Delivery stub where model weights live in production (never committed). |
| [docs/tongue-twisters.md](docs/tongue-twisters.md) | The "Say it fast" room: 36 authored twisters over 15 target sounds, each one an English phoneme Hebrew does not have or a contrast Hebrew speakers collapse — why they are kept out of the phrasebank's grammar ladder, and how to add more. |
| [docs/short-stories.md](docs/short-stories.md) | The reading room, and the one rule that lets it exist: a story may use only words the phrasebank already teaches at or below its Level, so "fits the learner" is an assertion in `StoriesTest` rather than a claim in a review. Why stories start at Level 2, and what the gate cannot check. |
| [docs/knowledge-tracing.md](docs/knowledge-tracing.md) | Practice with a memory: what a skill id is (theme, grammar frame, sound, pictured word — a grain bounded by the content, so a profile cannot grow without limit), what each room counts as evidence, and how rounds are drawn weakest-first. Wakes `BktModel`, dead since phase 1, and gives the phrasebank's 334 unread `frame` tags their first reader. |
| [docs/fill-the-gap.md](docs/fill-the-gap.md) | The sentence-completion room: one gap, four same-class choices, the line's own Hebrew as the key that makes the answer unique. Three kinds of gap (pack word with its icon, open word by context and shape, function word from a fourteen-class table), what is authored versus derived, the measured yield per Level, and the known limits. |
| [docs/character-voices.md](docs/character-voices.md) | Character voices: a Kokoro voice is a 522 KB style table, style tables interpolate, so a new voice is a recipe rather than a download. The Captain, what a character may and may not touch, and why there is no real Scottish accent in the bundled set. |
| [docs/feature-ideas.md](docs/feature-ideas.md) | The scouted list: 55 ideas from the reading and language-tutoring landscape, 53 verified against this code. The finding that mattered — the offline constraint rules almost nothing out, and one dead class (`BktModel`/`SkillState`, no writer since P1) is what a fifth of the list is actually waiting on. |
| [docs/loop-accuracy.md](docs/loop-accuracy.md) | The speech loop measured against its own bundled models: why a learner who said it right was told to repeat (the judge compared spelling — "OK"/"okay", "10"/"ten" — fixed by judging by sound, exactly, 30 → 13 rejects on the short-item census), why a wrong answer can still earn a star (Whisper repairs 38% of mispronunciations before the judge sees them — a model limit, with the acoustic A/B that would answer it), and why a comma on a short line had no beat (measured, and restored by cutting the line and joining with a measured seam). Two improvements measured before a line of Kotlin: prompting Whisper with the expected line (rejected — it echoes the target for 110 of 116 mispronunciations) and a coach-confirmed accept on a reject (shipped, narrowly). What still needs a real speaker. |
| [docs/privacy.md](docs/privacy.md) | Where what the microphone hears actually goes. Offline covers the network; it does not cover logcat, which is readable over adb and outlives the app — so the rule is narrower: only measurements of speech are written down, never speech. The one leak the audit found, what the other 83 log statements carry, and the two rules `MicPrivacyTest` scans every source file for. |
| [docs/learner-levels.md](docs/learner-levels.md) | The audience plan: proficiency Levels 1–7 for non-native speakers of all ages — the per-level dials (register, reply budget, Hebrew scaffolding fade), the migration from the old age-flavored tracks, and the original track reasoning. |
| [scripts/](scripts/) | Tooling to fetch the large binaries that are not committed (models, native libs, voice data) and to sideload them onto a phone — all SHA-256-pinned. [`scripts/README.md`](scripts/README.md) says which to run when. |

## Building the scaffold

Two lanes, because the full Android build needs Google's Maven/SDK servers:

```bash
# Logic modules + unit tests (pure JVM — runs anywhere, no Android SDK):
./gradlew -Plangtutor.jvmOnly=true build

# Full app (needs the Android SDK — see docs/building-on-debian.md; also runs in CI):
./gradlew :app:assembleFullDebug     # phone APK: conversation + practice (needs the model pack)
./gradlew :app:assemblePracticeDebug # tablet APK: practice rooms only, no model (docs/practice-flavor.md)
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
