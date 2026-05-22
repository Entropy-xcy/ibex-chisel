#!/usr/bin/env python3
"""Compare Chisel and original-SV UVM directed proxy result directories."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path


RESULTS = ("PASS", "FAIL", "TIMEOUT", "ERROR", "NO_PASS", "MISSING")


def load_results(run_dir: Path) -> dict[str, str]:
    results: dict[str, str] = {}
    for result_file in run_dir.glob("*/result"):
        test_name = result_file.parent.name
        result = result_file.read_text(encoding="ascii").strip()
        if result not in RESULTS:
            result = "ERROR"
        results[test_name] = result
    return results


def classify(test_name: str) -> str:
    if test_name.startswith(("test_pmp_ok_1", "test_pmp_ok_share_1", "test_pmp_csr_1")):
        return "epmp_generated"
    if test_name.startswith("pmp_mseccfg_test"):
        return "handwritten_mseccfg"
    if test_name.startswith("zicntr."):
        return "zicntr_counter_alias"
    return "other_original_directed"


def print_counts(label: str, results: dict[str, str]) -> None:
    counts = Counter(results.values())
    body = " ".join(f"{result} {counts[result]}" for result in RESULTS if result != "MISSING")
    print(f"{label} total {len(results)} {body}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("chisel_run_dir", type=Path)
    parser.add_argument("original_sv_run_dir", type=Path)
    parser.add_argument("--fail-on-chisel-only-fail", action="store_true")
    args = parser.parse_args()

    chisel = load_results(args.chisel_run_dir)
    original = load_results(args.original_sv_run_dir)
    all_tests = sorted(set(chisel) | set(original))

    print_counts("chisel", chisel)
    print_counts("original_sv", original)

    missing_chisel = [test for test in all_tests if test not in chisel]
    missing_original = [test for test in all_tests if test not in original]
    print(f"missing_chisel {len(missing_chisel)}")
    print(f"missing_original_sv {len(missing_original)}")

    mismatches: list[tuple[str, str, str]] = []
    chisel_only_fail: list[str] = []
    original_only_fail: list[str] = []
    both_fail: list[str] = []
    both_pass: list[str] = []

    for test in all_tests:
        chisel_result = chisel.get(test, "MISSING")
        original_result = original.get(test, "MISSING")
        if chisel_result != original_result:
            mismatches.append((test, chisel_result, original_result))
        if chisel_result == "FAIL" and original_result != "FAIL":
            chisel_only_fail.append(test)
        if original_result == "FAIL" and chisel_result != "FAIL":
            original_only_fail.append(test)
        if chisel_result == "FAIL" and original_result == "FAIL":
            both_fail.append(test)
        if chisel_result == "PASS" and original_result == "PASS":
            both_pass.append(test)

    print(f"both_pass {len(both_pass)}")
    print(f"both_fail {len(both_fail)}")
    print(f"chisel_only_fail {len(chisel_only_fail)}")
    print(f"original_sv_only_fail {len(original_only_fail)}")
    print(f"mismatches {len(mismatches)}")

    for name, tests in (
        ("both_fail_by_group", both_fail),
        ("chisel_only_fail_by_group", chisel_only_fail),
        ("original_sv_only_fail_by_group", original_only_fail),
    ):
        counts = Counter(classify(test) for test in tests)
        parts = " ".join(f"{group} {counts[group]}" for group in sorted(counts))
        print(f"{name} {parts}".rstrip())

    if mismatches:
        print("mismatch_details")
        for test, chisel_result, original_result in mismatches:
            print(f"{test} chisel={chisel_result} original_sv={original_result} group={classify(test)}")

    if args.fail_on_chisel_only_fail and chisel_only_fail:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
