# English-quality eval — Verdict (native LiteRT-LM, E4B)

The tutor's **primary** output language is English; the Hebrew evals never
measured it. Ran the 24-prompt English tutoring set on the native
`gemma-4-E4B-it.litertlm` artifact (LiteRT-LM CPU, same harness).

| Dimension | Score | Gate |
|---|---|---|
| Correctness | **4.96** | ≥4.0 ✓ |
| Register (child-appropriate) | **5.00** | ≥3.5 ✓ |
| Pedagogy | 4.92 | — |
| Faithfulness | 4.88 | — |
| Red flags | 0 | none ✓ |
| **Gate** | | **PASS ✓✓** |

Every prompt category averages ≥4.67. Scores are first-pass (Claude), pending a
human audit, but the margin is large and unambiguous.

## What this settles
- **The core product — teaching English — is not in question.** The tutor's
  English is warm, correct, and pitched exactly right for young children:
  clean recasts (no "wrong"), effort-praise, one-question turns, simple stories,
  gentle encouragement. This is the language the child hears most, and it's
  essentially flawless.
- **The a/an rule is Hebrew-specific, not a knowledge gap.** In Hebrew this
  explanation came out muddled/backwards at every precision (VERDICT.md,
  explain-02). In **English it is explained correctly** — "we say *a* before a
  'buh' sound like banana, *an* before an 'ah' sound like apple." So the fix for
  the Hebrew content flaws is about Hebrew *generation* (curated explanations /
  Hebrew fine-tune), not teaching the model the rule.
- Net: English (4.96) ≫ Hebrew E4B (4.45) ≫ Hebrew E2B (4.03). The gap is the
  model's Hebrew generation, exactly as the adaptation plan targets.

## Caveats
- First-pass scores by Claude; the margin makes a human audit low-stakes here
  (unlike the borderline Hebrew rows).
- Native LiteRT-LM on desktop CPU — not the Pixel TPU path (docs/bench.md).
- Prompts are single-shot at temp 0.7; multi-turn conversational coherence is a
  separate P3 measurement.
