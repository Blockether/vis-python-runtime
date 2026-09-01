# Baseline: Vis on GraalPy, before CPython

Every number an embedded CPython has to beat, measured on the shipped engine so
the comparison later is a subtraction, not an argument. Produced by
`bench/run.py`; rerun it against the new runtime and diff the two tables.

## Environment

| | |
|---|---|
| machine | Apple M4 Max, 36 GiB, macOS 26.6.2, arm64 |
| binary | `~/vis/target/vis`, `vis-agent 0.1.40`, native image |
| engine | GraalPy 25.1.3 on GraalVM CE 25.1.3 (`.graalvm-version` pin) |
| date | 2026-08 |

## What GraalPy costs on disk

| artifact | size |
|---|---|
| `target/vis` (native image) | **612 MiB** (641,972,064 B) |
| `vis.jar` | 181 MB |
| `python-language` 25.1.3 jar | 95.1 MB |
| `icu4j-shadowed` | 18.6 MB |
| `truffle-api` | 16.8 MB |
| `regex` | 3.9 MB |
| `python-resources` (stdlib, already outside the image) | 14.4 MB |

## What it costs at runtime

`vis --version` — the whole engine with no Python — is **0.01 s and 27 MB RSS**.
That is the reference: everything below is what the language adds.

`bench/run.py --engine "$HOME/vis/target/vis python" -n 4`:

| case | cold s | warm s | peak RSS MB |
|---|---|---|---|
| boot (`pass`) | 1.087 | 0.629 | 271.6 |
| numpy+pandas | 0.922 | 0.929 | 298.7 |
| bs4+PIL+httpx | 1.119 | 1.094 | 318.8 |
| throughput (json+regex, no warmup) | 2.393 | 2.441 | 278.4 |

A separately timed first-of-the-morning run, with the 612 MB binary not yet in
the page cache, took **4.11 s and 528 MB**.

Read from that:

- A Python session is **~270-320 MB RSS and 0.6-1.1 s** before it runs a line of
  user code. The engine around it is 27 MB. Python is an order of magnitude
  heavier than the whole rest of the agent.
- Shims are not the cost. `pass` against `import numpy, pandas` differs by
  ~0.3 s and ~27 MB; the ~1.5 MB of `resources/vis-shims/` loads cheaply and
  will carry over unchanged.
- Short-lived throughput is bad: 2.4 s for a json+regex micro that CPython does
  in a fraction of that, because a one-shot process never reaches the JIT.
  GraalPy wins this case only in a long, warm session.

## Targets for the replacement

| axis | GraalPy today | embedded CPython target |
|---|---|---|
| boot to first eval | 0.6-1.1 s warm | < 50 ms |
| session RSS | ~270-320 MB | < 60 MB |
| engine artifact | ~300 MB inside the image | ~20 MB cdylib beside it |
| one-shot throughput | slowest case here | at least 3x faster |
| long warm session throughput | JIT after warmup | may be slower — measure, do not assume |

The one axis that is a test rather than a benchmark: memory must come back.
Twenty dropped `Image.new` handles stay live for the life of the JVM under
GraalPy, which is the whole reason `resources/vis-python/async_runtime.py`
maintains an ownership registry by hand. Under real refcounting the same
scenario has to return to baseline.
