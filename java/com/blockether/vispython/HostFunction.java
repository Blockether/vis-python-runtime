package com.blockether.vispython;

/**
 * The host a guest calls back into: a name, a text payload, text in answer.
 *
 * <p>This interface is the whole dialect. The bridge carries text and reads
 * none of it, so the caller decides whether that text is JSON, EDN or a
 * sentence — the sandbox runtime speaks JSON, and nothing here knows that.
 *
 * <p>Where an implementation RUNS is the constraint that matters: inside the
 * call the guest is blocked on, so it must not re-enter the interpreter, and on
 * any thread, because the GIL is released for its duration and a second guest
 * thread can arrive while the first is still here.
 */
@FunctionalInterface
public interface HostFunction {
  String call(String name, String payload);
}
