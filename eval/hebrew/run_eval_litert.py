#!/usr/bin/env python3
"""Run the Hebrew tutor-domain eval on the NATIVE LiteRT-LM runtime.

This is the same eval as run_eval.py, but instead of talking to an
OpenAI-compatible HTTP server (llama.cpp / Ollama), it drives the exact
mobile artifact (a `.litertlm` file) through the LiteRT-LM Python binding
(pip: litert-lm) on the CPU backend — the same runtime that ships on the
Pixel. Desktop CPU inference is still not bit-identical to the on-device
TPU/GPU path, but the graph, tokenizer, weight layout, and quantization are
the shipping ones — a much closer proxy than llama.cpp's GGUF requantization.

Usage:
  python3 run_eval_litert.py \
      --model-path scratchpad/models/gemma-4-E4B-it.litertlm \
      --model gemma-4-e4b-litertlm \
      --out results/gemma-4-e4b-litertlm.csv

Reuses SYSTEM_PROMPT / SCORE_COLUMNS from run_eval.py so every column lines
up with the existing grid.
"""

import argparse
import csv
import pathlib
import sys
import time

# Reuse the exact system prompt + score schema from the HTTP runner.
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from run_eval import SYSTEM_PROMPT, SCORE_COLUMNS  # noqa: E402

import json  # noqa: E402
import litert_lm as L  # noqa: E402


def build_engine(model_path: str, threads: int):
    return L.Engine(
        model_path=model_path,
        backend=L.Backend.CPU(thread_count=threads),
        max_num_tokens=2048,
    )


def generate(engine, prompt: str, max_tokens: int, system_prompt: str = SYSTEM_PROMPT) -> str:
    # A fresh conversation per prompt = no cross-prompt state leakage,
    # matching the stateless single-shot calls of the HTTP runner.
    conv = engine.create_conversation(
        system_message=system_prompt,
        sampler_config=L.SamplerConfig(temperature=0.7, top_k=64, top_p=0.95, seed=42),
        max_output_tokens=max_tokens,
    )
    try:
        resp = conv.send_message(L.Message.user(prompt))
        # send_message returns a Mapping; the assistant text lives under the
        # model channel. Be defensive about the exact shape across versions.
        return _extract_text(resp)
    finally:
        conv.close()


def _extract_text(resp) -> str:
    """Pull the assistant text out of a LiteRT-LM send_message() response.

    The binding returns {"role": "assistant", "content": [{"type": "text",
    "text": "..."}], ...} (content is a LIST of typed parts). Handle that
    first; fall back defensively for other shapes / versions.
    """
    if resp is None:
        return ""
    if isinstance(resp, str):
        return resp.strip()
    if isinstance(resp, dict):
        content = resp.get("content")
        parts = []
        if isinstance(content, list):
            for part in content:
                if isinstance(part, dict):
                    if part.get("type") in (None, "text") and isinstance(part.get("text"), str):
                        parts.append(part["text"])
                elif isinstance(part, str):
                    parts.append(part)
        elif isinstance(content, str):
            parts.append(content)
        if parts:
            return "".join(parts).strip()
        # fallbacks for alternative shapes
        for key in ("text", "output", "message"):
            if isinstance(resp.get(key), str) and resp[key].strip():
                return resp[key].strip()
    return str(resp).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--model", required=True, help="label for the CSV")
    parser.add_argument("--out", required=True)
    parser.add_argument("--prompts", default=str(pathlib.Path(__file__).parent / "prompts.jsonl"))
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--max-tokens", type=int, default=220)
    parser.add_argument("--system-prompt-file", default=None,
                        help="override the tutor system prompt (e.g. the English persona)")
    args = parser.parse_args()

    system_prompt = SYSTEM_PROMPT
    if args.system_prompt_file:
        system_prompt = pathlib.Path(args.system_prompt_file).read_text(encoding="utf-8").strip()

    prompts = [json.loads(line) for line in open(args.prompts, encoding="utf-8") if line.strip()]
    out_path = pathlib.Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"Loading {args.model_path} on LiteRT-LM CPU ({args.threads} threads)...")
    t0 = time.time()
    engine = build_engine(args.model_path, args.threads)
    print(f"Engine ready in {round(time.time() - t0, 1)}s")

    with open(out_path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "category", "model", "prompt", "response", "latency_s"] + SCORE_COLUMNS)
        for i, item in enumerate(prompts, 1):
            start = time.time()
            try:
                response = generate(engine, item["prompt"], args.max_tokens, system_prompt)
            except Exception as exc:  # noqa: BLE001 — record and continue
                response = f"<ERROR: {exc}>"
            latency = round(time.time() - start, 2)
            writer.writerow(
                [item["id"], item["category"], args.model, item["prompt"], response, latency]
                + [""] * len(SCORE_COLUMNS)
            )
            f.flush()  # per-row flush so progress is inspectable during long runs
            preview = response.replace("\n", " ")[:60]
            print(f"[{i}/{len(prompts)}] {item['id']} ({latency}s) {preview}")

    engine.close()
    print(f"\nWrote {out_path} — fill the score columns per rubric.md, then run summarize.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
