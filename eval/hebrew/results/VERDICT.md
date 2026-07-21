# Hebrew Eval — Verdict (runs 2026-07-21/22)

## BREAKTHROUGH: Gemma 4 E2B at Q8_0 **PASSES the gate** — the deficit was largely quantization damage

| Gemma 4 E2B | q4_0 (QAT) | **Q8_0** | Δ | Gate |
|---|---|---|---|---|
| Correctness | 3.73 | **4.20** | **+0.47** | ≥4.0 ✓ |
| Register | 4.03 | 4.35 | +0.32 | ≥3.5 ✓ |
| Pedagogy | 3.42 | 3.85 | +0.43 | — |
| Red flags | 3 | **0** | | none ✓ |

Same model, same prompts, same scorer — only the quantization differs. Q8
*eliminated* the q4_0 catastrophes: the english-leaks, the "give me the button"
misinstruction, the word-salad productions, the harsh tone. **Aggressive 4-bit
quantization disproportionately damages Hebrew**; the capability was in the
model all along.

**Product implications**
- Q8 (~4.7 GB) is too big for the base install next to the speech stack — but
  the quality curve between q4_0 (3.73) and Q8 (4.20) almost certainly crosses
  the 4.0 gate around **Q5/Q6 (~3.4–4 GB)** or with a **Hebrew-calibrated
  imatrix Q4**: both are one-command re-tests in this harness.
- The mobile artifact (LiteRT int4 QAT) sits at an unknown point on this curve
  — the on-device spot-check just became the decisive next measurement.
- Two content flaws survive at all quants and need the RAG/curated-explanations
  fix regardless: the a/an rule comes out wrong, and "I am here since Sunday"
  gets validated instead of corrected.
- **Caution**: 4.20 is a borderline pass on first-pass scores (±0.2 plausible);
  the native-speaker audit now matters doubly.



## Update: third candidate — DictaLM-3.0-1.7B-Instruct (Q4_K_M) — also FAILS, differently

| | Gemma 4 E2B | **DictaLM 1.7B** | Phi-4-mini | Gate |
|---|---|---|---|---|
| Correctness | **3.73** | 3.38 | 1.62 | ≥4.0 |
| Register (fluency) | 4.03 | **3.98 (min 3 — never breaks)** | 2.52 | ≥3.5 |
| Pedagogy | **3.42** | 2.42 | 1.68 | — |
| Faithfulness | **3.38** | 2.52 | 1.75 | — |

**The decisive insight: language competence and task competence are separable.**
Dicta's Hebrew-targeted continued-pretraining worked — DictaLM's Hebrew never
collapses into morphology soup (register floor of 3, best of all three) — but a
1.7B brain cannot do the tutoring *job*: it validated learner errors twice,
taught "She is more tall than you" as an acceptable form, hallucinated pack
content, and made semantic word-swaps (bear→duck, purple→"gray",
umbrella→"a flag named fountain"). Gemma has the opposite profile: the job
skills, with wobblier Hebrew.

Consequences:
1. **Contingency stands** — P1 ships templated Hebrew + human audio.
2. The Hebrew-sidecar idea (1.7B next to Gemma) is **dead for open generation**;
   at most a narrow template-adjacent role.
3. The adaptation plan is *strengthened*: the goal is Dicta-style Hebrew data
   inside Gemma-scale task competence — either the planned Gemma Hebrew LoRA,
   or **DictaLM-3.0-12B** (Dicta Hebrew on a Nemotron brain; ~6.5GB int4 =
   Advanced-pack-sized; NVIDIA open-model license to be reviewed) — both
   testable in this same harness. Also worth one run: Gemma 4 **E4B**.
4. Sidenote: DictaLM's *Thinking* variant is unusable at tutor token budgets —
   38/40 replies consumed the whole budget reasoning (in fluent Hebrew) without
   ever answering.

---

# Original verdict (Gemma vs Phi)

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
