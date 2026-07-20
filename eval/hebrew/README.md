# Hebrew LLM Evaluation — the P1 gate

**Question this answers**: is Gemma 4 E2B's Hebrew good enough for the tutor's
Hebrew-facing moments, or do we fall back to Phi-4-mini — or cut dynamic Hebrew
entirely (templates + human audio only)? No published benchmark covers this;
we run our own (see docs/feasibility.md §3).

## What's here

- `prompts.jsonl` — 40 tutor-domain prompts across 8 categories (the *actual*
  Hebrew jobs the tutor has: instructions, gentle recasts, error explanations,
  parent-facing lines, bilingual scaffolding).
- `rubric.md` — 4 scoring dimensions, 1–5 scales, with anchors.
- `run_eval.py` — runs every prompt against any OpenAI-compatible endpoint
  (llama.cpp `llama-server`, Ollama, LM Studio — all serve Gemma/Phi locally),
  writes `results/<model>.csv` ready for human scoring.
- `summarize.py` — aggregates scored CSVs into a per-category comparison table.

## How to run (on your workstation — models don't fit this repo)

```bash
# 1. Serve each candidate locally, e.g. with Ollama:
ollama pull gemma3n:e2b        # substitute the Gemma 4 E2B tag when available
ollama pull phi4-mini

# 2. Generate outputs (repeat per model):
python3 run_eval.py --base-url http://localhost:11434/v1 \
    --model gemma-4-e2b --out results/gemma-4-e2b.csv
python3 run_eval.py --base-url http://localhost:11434/v1 \
    --model phi4-mini --out results/phi4-mini.csv

# 3. Human scoring: open each CSV, fill the four score columns per rubric.md
#    (a native Hebrew speaker, ~45 min per model; blind-score if possible —
#    shuffle rows across models before scoring).

# 4. Compare:
python3 summarize.py results/*.csv
```

## Decision rule (pre-registered so we can't rationalize later)

- **Adopt a model for dynamic Hebrew** if its mean ≥ 4.0 on *correctness* AND
  ≥ 3.5 on *child-appropriate register*, with no category below 3.0.
- If both models fail → **contingency**: all dynamic Hebrew is cut; Hebrew
  ships as templates + pre-recorded human audio only (product still ships).
- English quality is NOT gated here — the tutor speaks English by default.

Note: quantization matters — score the **int4 build you would actually ship**,
not the fp16 reference. Desktop int4 ≈ mobile int4 for output quality.
