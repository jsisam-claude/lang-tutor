#!/usr/bin/env python3
"""Aggregate human-scored eval CSVs into a per-category comparison.

Usage: python3 summarize.py results/*.csv
"""

import csv
import statistics
import sys
from collections import defaultdict

SCORES = ["score_correctness", "score_register", "score_pedagogy", "score_faithful"]
GATE = {"score_correctness": 4.0, "score_register": 3.5}
CATEGORY_FLOOR = 3.0


def main(paths: list[str]) -> int:
    if not paths:
        print(__doc__)
        return 1

    for path in paths:
        rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
        scored = [r for r in rows if all(r.get(s, "").strip() for s in SCORES)]
        if not scored:
            print(f"\n=== {path}: no scored rows yet ===")
            continue

        model = scored[0]["model"]
        print(f"\n=== {model} ({len(scored)}/{len(rows)} scored) ===")

        overall = {}
        for score in SCORES:
            values = [float(r[score]) for r in scored]
            overall[score] = statistics.mean(values)
            print(f"  {score:20s} mean {overall[score]:.2f}  (min {min(values):.0f})")

        by_category = defaultdict(list)
        for r in scored:
            by_category[r["category"]].append(
                statistics.mean(float(r[s]) for s in SCORES)
            )
        worst_cat, worst = min(
            ((c, statistics.mean(v)) for c, v in by_category.items()), key=lambda x: x[1]
        )
        print("  per-category means:")
        for cat, values in sorted(by_category.items()):
            print(f"    {cat:20s} {statistics.mean(values):.2f}")

        flags = [r["flags"].strip() for r in scored if r.get("flags", "").strip()]
        if flags:
            print(f"  red flags ({len(flags)}): {sorted(set(flags))}")

        gate_ok = all(overall[s] >= threshold for s, threshold in GATE.items())
        cat_ok = worst >= CATEGORY_FLOOR
        verdict = "PASS" if (gate_ok and cat_ok and not flags) else "FAIL"
        print(f"  GATE (corr≥4.0, register≥3.5, every category≥3.0, no flags): {verdict}"
              + ("" if cat_ok else f"  [worst category: {worst_cat} {worst:.2f}]"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
