#!/usr/bin/env python3
"""Run the Hebrew tutor-domain eval against an OpenAI-compatible endpoint.

Works with llama.cpp's llama-server, Ollama (/v1), and LM Studio.
Stdlib only — no pip installs needed.

Usage:
  python3 run_eval.py --base-url http://localhost:11434/v1 \
      --model gemma-4-e2b --out results/gemma-4-e2b.csv
"""

import argparse
import csv
import json
import pathlib
import sys
import time
import urllib.request

SYSTEM_PROMPT = (
    "אתה תוקי (Tuki), תוכי חם וסבלני שמלמד ילדים דוברי עברית אנגלית. "
    "אתה עונה קצר, פשוט ובגובה העיניים של ילדים. "
    "לעולם אינך אומר לילד שהוא טעה — אתה מדגים את הצורה הנכונה. "
    "You keep English parts simple and child-appropriate. "
    "Follow the task instructions exactly; no meta commentary."
)

SCORE_COLUMNS = ["score_correctness", "score_register", "score_pedagogy", "score_faithful", "flags", "notes"]


def chat(base_url: str, model: str, prompt: str, timeout: int) -> str:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.7,
        "max_tokens": 220,
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = json.load(resp)
    return body["choices"][0]["message"]["content"].strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--prompts", default=str(pathlib.Path(__file__).parent / "prompts.jsonl"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    prompts = [json.loads(line) for line in open(args.prompts, encoding="utf-8") if line.strip()]
    out_path = pathlib.Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with open(out_path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow(["id", "category", "model", "prompt", "response", "latency_s"] + SCORE_COLUMNS)
        for i, item in enumerate(prompts, 1):
            start = time.time()
            try:
                response = chat(args.base_url, args.model, item["prompt"], args.timeout)
            except Exception as exc:  # noqa: BLE001 — record and continue
                response = f"<ERROR: {exc}>"
            latency = round(time.time() - start, 2)
            writer.writerow([item["id"], item["category"], args.model, item["prompt"], response, latency] + [""] * len(SCORE_COLUMNS))
            print(f"[{i}/{len(prompts)}] {item['id']} ({latency}s)")

    print(f"\nWrote {out_path} — fill the score columns per rubric.md, then run summarize.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
