#!/usr/bin/env python3
"""What a Python engine costs Vis: boot time, session RSS, shim load, throughput.

One command, one engine, medians over N runs — so the number in `pre-cpython.md`
and the number a new engine prints came out of the same harness.

    bench/run.py --engine "$HOME/vis/target/vis python" -n 5
    bench/run.py --engine ./target/vispython --json > after.json

The engine is anything that runs `ENGINE -c CODE`. Wall time is measured around
the process; peak RSS comes from `/usr/bin/time` (BSD `-l`, GNU `-v`), because a
parent cannot read a child's own high-water mark any other way. The first run of
each case is reported separately: it pages the binary in and is the only cold
number that means anything.
"""

import argparse
import json
import platform
import re
import shlex
import statistics
import subprocess
import sys
import time

CASES = [
    ("boot", "pass"),
    ("numpy+pandas", "import numpy, pandas; print(numpy.array([1, 2]).sum())"),
    ("bs4+PIL+httpx", "import bs4, PIL, httpx; print(1)"),
    (
        "throughput",
        "import json, re; d = {'k': list(range(200))};"
        " s = json.dumps(d) * 50; p = re.compile(r'\\d+');"
        " print(sum(len(p.findall(s)) for _ in range(200)))",
    ),
]

RSS_PATTERNS = (
    re.compile(r"(\d+)\s+maximum resident set size"),          # BSD /usr/bin/time -l
    re.compile(r"Maximum resident set size \(kbytes\): (\d+)"),  # GNU /usr/bin/time -v
)


def peak_rss_bytes(stderr):
    """Peak RSS of the timed child in bytes, or None when time(1) said nothing."""
    for i, pattern in enumerate(RSS_PATTERNS):
        m = pattern.search(stderr)
        if m:
            return int(m.group(1)) * (1024 if i else 1)
    return None


def run_once(engine, code):
    """One (wall seconds, peak RSS bytes, exit code) sample of `engine -c code`."""
    flag = "-l" if platform.system() == "Darwin" else "-v"
    argv = ["/usr/bin/time", flag, *engine, "-c", code]
    started = time.perf_counter()
    proc = subprocess.run(argv, capture_output=True, text=True)
    return time.perf_counter() - started, peak_rss_bytes(proc.stderr), proc.returncode


def measure(engine, runs):
    """Every case timed `runs` times: cold first sample, medians of the rest."""
    results = []
    for name, code in CASES:
        samples = [run_once(engine, code) for _ in range(runs)]
        failed = [rc for _, _, rc in samples if rc != 0]
        warm = samples[1:] or samples
        rss = [r for _, r, _ in warm if r is not None]
        results.append(
            {
                "case": name,
                "cold_seconds": round(samples[0][0], 3),
                "warm_seconds": round(statistics.median(w for w, _, _ in warm), 3),
                "peak_rss_mb": round(statistics.median(rss) / 1e6, 1) if rss else None,
                "runs": len(samples),
                "failures": len(failed),
            }
        )
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", default="vis-agent python",
                        help="command that runs `-c CODE` (default: vis-agent python)")
    parser.add_argument("-n", "--runs", type=int, default=5)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    engine = shlex.split(args.engine)
    results = measure(engine, args.runs)

    if args.json:
        json.dump({"engine": args.engine, "machine": platform.platform(),
                   "results": results}, sys.stdout, indent=2)
        print()
        return

    print(f"engine: {args.engine}   machine: {platform.platform()}   runs: {args.runs}")
    print("| case | cold s | warm s | peak RSS MB |")
    print("|---|---|---|---|")
    for r in results:
        mark = "" if not r["failures"] else f"  ({r['failures']} FAILED)"
        print(f"| {r['case']}{mark} | {r['cold_seconds']} | {r['warm_seconds']} | {r['peak_rss_mb']} |")


if __name__ == "__main__":
    main()
