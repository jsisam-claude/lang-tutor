# Hebrew TTS Listen Test — is Phonikud good enough for kids?

**Question**: can the Phonikud stack (diacritization+G2P → Piper-class Hebrew
voice) carry *dynamic* Hebrew lines, or must pre-recorded human audio carry
more of the load? Ears decide, not specs. (~30 minutes.)

## Setup (your workstation)

Follow the current instructions at
<https://github.com/thewh1teagle/phonikud-tts> (models on Hugging Face,
CC-BY-4.0). Synthesize every line in `lines.txt` — one WAV per line — with the
highest-quality available voice (Piper-High / SASPEECH variants).

## The listening protocol

Two Hebrew speakers (ideally one child in the target age range) listen blind
and rate each clip 1–5:

- **Intelligibility** — every word understood on first listen?
- **Nikud correctness** — any vowel/stress errors that change or mangle a word?
  (This is Phonikud's whole job — listen hard for it.)
- **Warmth for kids** — would a 6-year-old find this voice friendly, or robotic?

## Decision rule

- Mean ≥ 4 on intelligibility AND zero word-mangling nikud errors →
  Phonikud carries dynamic Hebrew; humans record only the fixed P1 lines.
- Nikud errors on names/rare words only → Phonikud stays, plus a
  pronunciation-override lexicon for the app's vocabulary.
- Below that → dynamic Hebrew TTS is demoted: humans record ALL instruction
  templates; Phonikud remains only for truly unpredictable text (rare).

The lines in `lines.txt` are drawn from the app's real strings plus
deliberately hard cases (child names, English loanwords, numbers, and
niqqud-ambiguous words).
