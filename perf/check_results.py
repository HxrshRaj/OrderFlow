#!/usr/bin/env python3
"""Fail the build if a JMeter results file (CSV .jtl) breaks the agreed limits.

Usage: check_results.py results.jtl [--max-error-rate 0.01] [--max-p95-ms 2000] [--min-samples 200]
Per-label p95 is checked too, so one slow endpoint cannot hide inside a healthy average."""
import argparse
import csv
import math
import sys
from collections import defaultdict


def percentile(values, pct):
    s = sorted(values)
    return s[max(0, math.ceil(pct / 100 * len(s)) - 1)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jtl")
    ap.add_argument("--max-error-rate", type=float, default=0.01)
    ap.add_argument("--max-p95-ms", type=int, default=2000)
    ap.add_argument("--min-samples", type=int, default=200)
    a = ap.parse_args()

    by_label = defaultdict(list)
    errors = defaultdict(int)
    with open(a.jtl, newline="") as f:
        for row in csv.DictReader(f):
            if row["label"].startswith("setUp"):
                continue
            by_label[row["label"]].append(int(row["elapsed"]))
            if row["success"].lower() != "true":
                errors[row["label"]] += 1

    total = sum(len(v) for v in by_label.values())
    bad = sum(errors.values())
    print(f"{'label':45} {'n':>6} {'err%':>6} {'p50':>6} {'p95':>6} {'max':>6}")
    failed = []
    for label, vals in sorted(by_label.items()):
        p95 = percentile(vals, 95)
        print(f"{label:45} {len(vals):>6} {100 * errors[label] / len(vals):>6.2f} {percentile(vals, 50):>6} {p95:>6} {max(vals):>6}")
        if p95 > a.max_p95_ms:
            failed.append(f"{label}: p95 {p95} ms > {a.max_p95_ms} ms")
    rate = bad / total if total else 1.0
    print(f"\nTOTAL samples={total} errors={bad} error_rate={rate:.4%}")
    if total < a.min_samples:
        failed.append(f"only {total} samples (< {a.min_samples}); the run did not generate real load")
    if rate > a.max_error_rate:
        failed.append(f"error rate {rate:.2%} > {a.max_error_rate:.2%}")
    for msg in failed:
        print("FAIL:", msg)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
