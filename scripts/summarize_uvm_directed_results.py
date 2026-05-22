#!/usr/bin/env python3
"""Summarize Chisel runs of original Ibex UVM directed binaries."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from pathlib import Path


RESULTS = ("PASS", "FAIL", "TIMEOUT", "ERROR", "NO_PASS")


def classify(test_name: str) -> str:
    if test_name.startswith(("test_pmp_ok_1", "test_pmp_ok_share_1", "test_pmp_csr_1")):
        return "epmp_generated"
    if test_name.startswith("pmp_mseccfg_test"):
        return "handwritten_mseccfg"
    if test_name.startswith("zicntr."):
        return "zicntr_counter_alias"
    return "other_original_directed"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path, help="Directory containing per-test result files")
    parser.add_argument("--show-failures", action="store_true")
    args = parser.parse_args()

    grouped: dict[str, Counter[str]] = defaultdict(Counter)
    failures: list[tuple[str, str, str]] = []
    for result_file in sorted(args.run_dir.glob("*/result")):
        test_name = result_file.parent.name
        result = result_file.read_text(encoding="ascii").strip()
        group = classify(test_name)
        grouped[group][result] += 1
        if result != "PASS":
            failures.append((group, result, test_name))

    for group in sorted(grouped):
        counts = grouped[group]
        total = sum(counts.values())
        fields = " ".join(f"{result} {counts[result]}" for result in RESULTS)
        print(f"{group} total {total} {fields}")

    if args.show_failures:
        for group, result, test_name in failures:
            print(f"{group} {result} {test_name}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
