package com.blockether.vispython;

/**
 * Handle a Python tool call in the host application.
 *
 * <p>Bind one implementation with {@link Interpreter#bindHost(HostFunction)}.
 * The runtime supplies the caller session; authorize against this value rather
 * than a session name in guest-controlled payloads. An empty caller is unknown
 * and should be rejected. The installed tool protocol uses JSON requests with
 * {@code args} and {@code kwargs}, and JSON replies with {@code value} or
 * {@code error}. The bridge transports those strings without parsing them.
 *
 * <p>Callbacks execute while the Python caller waits and the GIL is released.
 * Calls can arrive concurrently on different threads: protect shared state and
 * do not re-enter {@link Interpreter} from a callback.
 */
@FunctionalInterface
public interface HostFunction {
  /**
   * Handle one authorized call and return its protocol reply.
   * @param session caller session identified by the runtime, or empty if unknown
   * @param name installed tool name
   * @param payload request JSON text for installed runtime tools
   * @return reply JSON text, normally {@code {"value": ...}} or {@code {"error": "..."}}
   */
  String call(String session, String name, String payload);
}
