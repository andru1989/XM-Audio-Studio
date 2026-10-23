#!/usr/bin/env python3
"""CI check: the committed shared fixtures match what the generators produce.

Floats are compared with a relative tolerance: the last digit of a transcendental result
(sin, cos, log10) differs between libm implementations (macOS vs Linux), and the Swift and
Kotlin suites already compare these values with tolerances. Everything else must be equal.

Usage: python3 tools/fixtures/check_up_to_date.py   (run from the repository root)
"""
import json
import math
import subprocess
import sys

FILES = ["shared/protocol-fixtures/frames.json", "shared/autoeq-test-vectors/vectors.json"]
REL_TOL = 1e-9
ABS_TOL = 1e-12


def same(a, b, path="$"):
    if isinstance(a, float) or isinstance(b, float):
        if isinstance(a, (int, float)) and isinstance(b, (int, float)) and math.isclose(a, b, rel_tol=REL_TOL, abs_tol=ABS_TOL):
            return []
        return ["%s: %r != %r" % (path, a, b)]
    if isinstance(a, dict) and isinstance(b, dict):
        if a.keys() != b.keys():
            return ["%s: keys differ" % path]
        return [d for k in a for d in same(a[k], b[k], "%s.%s" % (path, k))]
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return ["%s: length %d != %d" % (path, len(a), len(b))]
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in same(x, y, "%s[%d]" % (path, i))]
    return [] if a == b else ["%s: %r != %r" % (path, a, b)]


def main():
    committed = {f: json.loads(subprocess.check_output(["git", "show", "HEAD:" + f])) for f in FILES}
    subprocess.check_call([sys.executable, "tools/fixtures/generate_frames.py"])
    subprocess.check_call([sys.executable, "tools/fixtures/generate_eq_vectors.py"])
    problems = []
    for f in FILES:
        problems += ["%s %s" % (f, d) for d in same(committed[f], json.load(open(f)))]
    subprocess.call(["git", "checkout", "--", *FILES])
    if problems:
        print("Shared fixtures are out of date:\n  " + "\n  ".join(problems[:20]))
        sys.exit(1)
    print("Shared fixtures are up to date.")


if __name__ == "__main__":
    main()
