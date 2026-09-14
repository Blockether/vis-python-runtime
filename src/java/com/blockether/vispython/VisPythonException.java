package com.blockether.vispython;

import java.util.Map;

/**
 * A native-library loading or execution failure with structured diagnostics.
 *
 * <p>Use {@link #getMessage()} for a human-readable description and {@link #data()}
 * for available context. A loading failure may include {@code platform} or
 * {@code path}; a native call failure may include {@code symbol} and {@code status}.
 * Keys depend on the failure, so check for their presence rather than assuming
 * a fixed schema. Block-level Python errors from {@link Interpreter#runBlock}
 * are returned in its JSON result instead of being thrown as this exception.
 */
public final class VisPythonException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Map<String, Object> data;

  /**
   * Create a failure with immutable diagnostic fields.
   * @param message human-readable explanation
   * @param data diagnostic fields; null becomes an empty map
   */
  public VisPythonException(String message, Map<String, Object> data) {
    super(message);
    this.data = data == null ? Map.of() : Map.copyOf(data);
  }

  /**
   * Create a failure while preserving its underlying cause.
   * @param message human-readable explanation
   * @param data diagnostic fields; null becomes an empty map
   * @param cause underlying failure
   */
  public VisPythonException(String message, Map<String, Object> data, Throwable cause) {
    super(message, cause);
    this.data = data == null ? Map.of() : Map.copyOf(data);
  }

  /** @return immutable diagnostic fields available for this failure */
  public Map<String, Object> data() {
    return data;
  }

  /**
   * Look up one diagnostic field without assuming that it exists.
   * @param key diagnostic field name
   * @return the field value, or null when absent
   */
  public Object get(String key) {
    return data.get(key);
  }
}
