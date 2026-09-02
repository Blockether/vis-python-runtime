package com.blockether.vispython;

import java.util.Map;

/**
 * What every failure in this bridge arrives as: a message a human can read and
 * the fields a caller might branch on.
 *
 * <p>The data map is deliberately open. A failure here is either the library
 * refusing to resolve (platform, file, environment variable) or a call coming
 * back negative (symbol, status, and the message CPython already wrote into the
 * out-buffer), and a caller that only prints the message must not have to know
 * which.
 */
public final class VisPythonException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Map<String, Object> data;

  public VisPythonException(String message, Map<String, Object> data) {
    super(message);
    this.data = data == null ? Map.of() : Map.copyOf(data);
  }

  public VisPythonException(String message, Map<String, Object> data, Throwable cause) {
    super(message, cause);
    this.data = data == null ? Map.of() : Map.copyOf(data);
  }

  /** The fields behind the message: keys are the wire-plain names above. */
  public Map<String, Object> data() {
    return data;
  }

  /** One field, or null. Saves a caller the map dance for the common case. */
  public Object get(String key) {
    return data.get(key);
  }
}
