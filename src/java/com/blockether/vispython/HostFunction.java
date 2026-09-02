package com.blockether.vispython;

/**
 * The host a guest calls back into: WHO is calling, a name, a text payload, and
 * text in answer.
 *
 * <p>{@code session} is the namespace the call was made from, and the
 * INTERPRETER says what it is - the nearest calling frame whose globals is a
 * session this library created. It is not read out of the payload, because a
 * payload is written by the guest: a block that named a neighbour's session
 * reached that session's tools. Empty means the call came from no session at
 * all, which a host should refuse rather than guess about.
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
  String call(String session, String name, String payload);
}
