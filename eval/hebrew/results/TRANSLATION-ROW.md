# Who should write the Hebrew translation row? — measured 2026-08-27

**Question**: the chat room now shows a Hebrew translation under Tuki's reply
(`docs/bilingual-gloss.md`). Should that come from the Gemma we already ship,
or from a Hebrew-specialist or translation-specialist model added beside it?

**Answer: the model we already ship, on the E4B tier only.** E4B got every
sentence right; every alternative tested was worse, and E2B was worst of all.

## Method

16 sentences of the kind this app actually shows — drill targets and chat
replies, weighted toward the concrete nouns and colours a beginner learns.
Each model run through the **LiteRT-LM Python binding on CPU** (4 threads),
**greedy** (`temperature 0, top_k 1`), with **no system-prompt override** so
each bundle's own embedded template and system prompt are what run.

Scored on semantics only: is the Hebrew sentence about the same things as the
English one. Fluency was not the discriminator — every model except one
produced grammatical Hebrew.

## Results

| model | source | size | semantic errors | broken tokens |
|---|---|---|---|---|
| **Gemma 4 E4B** | litert-community (shipped) | 3.66 GB | **0 / 16** | none |
| TranslateGemma 4B `dynamic_int8` | barakplasma (community) | 3.92 GB | 4 / 16 | yes |
| DictaLM 3.0 1.7B `dynamic_int8` | barakplasma (community) | 1.74 GB | ~6 / 16 | none |
| TranslateGemma 4B `int4` | barakplasma (community) | 2.01 GB | ~8 / 16 | many |
| Gemma 4 E2B | litert-community (shipped, 9a) | 2.59 GB | ~8 / 16 | none |

### What each failure looked like

**Gemma 4 E4B** — correct on every sentence, including the six that broke
everything else: `bear`→דוב, `duck`→ברווז, `purple umbrella`→המטריה הסגולה,
`his sister`→מאחותו, `soup`→מרק, and `yellow school bag` with both modifiers
intact. One gender-agreement wobble (`שלוש`/`שלושה תפוחים`) that it corrected
itself in the same reply.

Its one real flaw is dress, not content: every reply came back as
`התרגום לעברית הוא: **…**`, and two offered several options under markdown
headers. That is a parsing problem, and it is now handled — `ChatRoom.vetHebrew`
strips the preamble and the emphasis, with tests.

**Gemma 4 E2B** — `soup`→סושי (sushi), `apples`→תפירות (stitches), `lion`→שור
(ox), `yellow school bag`→התיק הישן (old bag), `duck`→ציפור שוערת (a bird that
*goaltends*), and an **Arabic word leaked mid-sentence** (`הדוב שלי بني ורך`) —
the cross-language leakage the original eval recorded. Also `הוא גבוהה`,
a feminine adjective on a masculine subject.

**DictaLM 3.0 1.7B** — the eval's finding reproduced exactly, at a much better
quantization than the original Q4_K_M run, which kills "quantization damage"
as the explanation. Fluent, natural, never-broken Hebrew with the wrong words
in it: `bear`→ארנבת (rabbit) or a collapse into its own assistant persona,
`purple umbrella`→הכובע האדום (the red hat), `duck`→ציפור, `sister`→אחיו
(brother), `soup`→ספגטי. **The same words fail here as in `VERDICT.md`** —
bear, purple, umbrella, duck — from a different harness and a different quant.

This is a **1.7B result, not a Dicta result.** Dicta's Hebrew is the good part;
1.7B is the part that cannot hold a sentence's meaning. `VERDICT.md` said this
first: "language competence and task competence are separable."

**TranslateGemma 4B** — the interesting failure. int8 fixed most of what int4
got wrong (`lion` correct, `yellow school bag` correct with both modifiers,
`apples` correct), so quantization damage was real. But **non-words survive at
int8**: `אמברת`, `ארסי`, and `מַעַפַת הַסַפְרָה` — vocalized gibberish. A 4B
translation model does not emit broken subwords; that is a conversion or
tokenizer signature, not a model-quality one. Verdict: **this artifact**, not
TranslateGemma.

## Why no second model, regardless of the scores

1. **TranslateGemma cannot replace Gemma.** It takes
   `<src>xx</src><dst>yy</dst><text>…</text>` and returns a translation. It
   cannot be Tuki, write drill lines, praise, recast, or answer a child. Any
   use is *in addition*, not *instead of*.
2. **Two models do not fit.** Pixel 9a is 8 GB total; E2B + a 2 GB translator +
   the speech stack does not survive the low-memory killer. Pixel 9 is 12 GB:
   E4B (3.66) + TranslateGemma (2.0–3.9) is 6–7.5 GB of weights before speech,
   and each model costs its own ~27 s GPU load.
3. **It would buy one line.** The translation row is the only dynamic Hebrew
   the app generates, and the incumbent already gets it right.
4. **Rebuilding Gemma ourselves would be a downgrade.** litert-community ships
   Gemma 4 as **int4 QAT** — quantization-aware *training*. The community
   recipe does post-training `dynamic_int4_block128` from fp16, which is
   strictly worse. The recipe is only worth having for models Google has not
   published — which is the path to a Hebrew-adapted Gemma, the fine-tune
   `VERDICT.md` actually recommends.
5. **Official edge builds of TranslateGemma exist but are the wrong shape.**
   `litert-community/TranslateGemma-4B-IT` ships exactly one artifact:
   `translategemma-4b-it-int8-web.task`, 3.90 GB — WebGPU, MediaPipe `.task`,
   not LiteRT-LM `.litertlm`. That is why community CPU/XNNPACK conversions
   exist at all.

## What changed in the app

- The chat room's translation is now gated on `HEBREW_CAPABLE_TIER` (E4B), the
  same gate Hebrew explanations already used, for the same recorded reason.
  **The structural gauntlet cannot substitute for this**: E2B's failures are
  fluent, well-formed, safe Hebrew that is simply about the wrong thing, and
  no structural check sees meaning.
- `vetHebrew` strips the model's preamble and markdown emphasis.
- Curriculum translations (`Activity.Vocab.translation.he`) are unaffected —
  they are authored, so no tier gate applies.

## Caveats

- 16 sentences, one prompt shape, CPU, greedy. Enough to separate "gets nouns
  right" from "does not"; not a graded eval against `rubric.md`.
- CPU here, GPU on device. Same graph and weights, different kernels.
- The app asks for a translation inside a constrained tutor prompt with an
  `HE:` marker; this test used a bare "translate to Hebrew" instruction, which
  invites more of the verbosity seen above than the app will provoke.
- The community bundles were driven through their own embedded templates, but
  the Python binding is not the Google AI Edge Gallery those cards were tested
  against. A template mismatch remains a live explanation for TranslateGemma's
  broken tokens.
