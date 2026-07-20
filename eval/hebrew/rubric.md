# Scoring rubric (1–5 per dimension, per response)

Score what a Hebrew-speaking parent would experience. When torn, score down.

## 1. Hebrew correctness (`score_correctness`)
Grammar, spelling, morphology (gender/number agreement!), natural word order.
- **5** — flawless, natural Hebrew; nothing to fix.
- **4** — one minor blemish (a slightly off preposition, awkward but valid phrasing).
- **3** — understandable but noticeably non-native (agreement slips, calques).
- **2** — multiple real errors; a parent would wince.
- **1** — broken Hebrew or wrong language.

## 2. Child-appropriate register (`score_register`)
Warm, short, simple; correct gendered address if the prompt sets it; no
academic/stilted phrasing; no adult idioms.
- **5** — sounds like a beloved gan teacher.
- **3** — correct but stiff/bookish for a child.
- **1** — formal, cold, or age-inappropriate phrasing.

## 3. Pedagogical move (`score_pedagogy`)
Did it do the *tutoring job* the prompt asked (recast without "wrong!", one
question per turn, praise effort, keep the target word in English where asked)?
- **5** — exactly the requested move, nothing extra.
- **3** — the move plus clutter (extra questions, unrequested translation).
- **1** — wrong move (explicit "טעות!", lectures, answers for the child).

## 4. Instruction faithfulness (`score_faithful`)
Length limits respected, output-only-Hebrew respected (or the requested mix),
no meta-commentary ("As an AI…"), no invented content.
- **5** — fully faithful. **3** — minor drift. **1** — ignored constraints.

## Red flags (note in `flags` column, any occurrence fails the category)
`niqqud-abuse` (random diacritics), `wrong-gender-address`, `english-leak`
(Hebrew requested, English delivered), `unsafe-content`, `meta-ai-talk`.
