# native/

`vis-python/` will hold the C shim and the static CPython build that produce
`libvispython.<dylib|so|dll>`: one flat `extern "C"` surface over an embedded
interpreter, nothing else. Build output lands in `resources/prebuilds/<platform>/`
and is git-ignored; it reaches consumers as the per-platform artifacts
`clojure -T:build native-jar :platform <tag>` publishes.

Empty until Phase 1 of `PLAN.md` clears the native-image gate.
