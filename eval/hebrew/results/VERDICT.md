# Hebrew Eval — Verdict (run 2026-07-21)

**Setup**: the exact ship-grade quantizations — Google's QAT q4_0 GGUF of
**Gemma 4 E2B** (3.2 GB) and **Phi-4-mini-instruct Q4_K_M** (2.4 GB) — served
via llama.cpp on CPU, 40 tutor-domain prompts each, temperature 0.7.
Scores: first-pass by Claude (native-level Hebrew reading), **pending
confirmation by a human native speaker** — every row is annotated in the CSVs.

## Result: both models FAIL the pre-registered gate → contingency adopted

| | Gemma 4 E2B q4_0 | Phi-4-mini Q4_K_M | Gate |
|---|---|---|---|
| Correctness | **3.73** | 1.62 | ≥ 4.0 |
| Child register | 4.03 ✓ | 2.52 | ≥ 3.5 |
| Pedagogy | 3.42 | 1.68 | — |
| Faithfulness | 3.38 | 1.75 | — |
| Worst category | recast 2.95 | recast 1.65 | every ≥ 3.0 |
| Red flags | 3 (english-leak ×2, harsh-tone ×1) | 5 (incl. **wrong-language**: Russian/German/French leakage) | none |

**Phi-4-mini is disqualified outright** — despite being the only small model
that *documents* Hebrew support, its generated Hebrew is largely word salad
with cross-language leakage and hallucinated content. The docs' a-priori
guess ("Phi as Hebrew fallback") is falsified.

**Gemma 4 E2B is clearly the better Hebrew model but not good enough to speak
Hebrew unsupervised to children**: two grammar explanations were factually
wrong, one validated the child's error, one reply opened with "תפסיקי"
("stop it!"), and several productions were broken Hebrew.

## Decision (per the pre-registered rule)

**P1 ships ZERO dynamic Hebrew.** All Hebrew is templated strings +
pre-recorded human audio. The tutor's conversational voice is English (never
gated here — Gemma's English lines in this run were consistently clean).
Product impact is modest by design: the architecture already routed most
Hebrew through templates/human audio; this makes it total.

## The path to flipping the gate (P3/P4)

Gemma's failure is *category-shaped*, which is encouraging:
strong — translate-scaffold **4.60**, bilingual-turn **4.15**, praise **4.10**;
weak — recast 2.95, error-explain 3.20, vocab-hint 3.15.
A Hebrew-targeted LoRA on curated tutor dialogues (the P4 fine-tune, with
Hebrew data added) is the obvious lever; re-run this harness after it, and
consider unlocking **translate-scaffold only** first — it already passes every
threshold today.

## Caveats

- Desktop llama.cpp q4_0 ≈ but ≠ the mobile LiteRT build; spot-check on-device.
- One sample per prompt at temp 0.7; borderline categories (±0.3) could move.
- Scores await native-speaker confirmation (~30 min): read the two CSVs'
  response column against rubric.md and adjust any score you disagree with,
  then re-run `python3 eval/hebrew/summarize.py results/*.csv`.
