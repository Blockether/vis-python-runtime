package com.blockether.vispython;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one wire dialect, read and written here because this library declares no
 * dependency: a JSON value becomes {@link Map} (insertion order kept),
 * {@link List}, {@link String}, {@link Long}, {@link Double}, {@link Boolean}
 * or null, and the same shapes render back.
 *
 * <p>Complete rather than clever - objects, arrays, every escape, the surrogate
 * pairs a {@code \\u} escape can spell - because the parent process writes
 * these lines with a real JSON library and the worker has to read every one of
 * them. Numbers without a fraction or exponent are longs; the rest are doubles;
 * a NaN or an infinity, which JSON cannot carry, is written as null.
 */
public final class Json {

  private Json() {}

  /** The value {@code text} spells, refusing anything but one complete value. */
  public static Object parse(String text) {
    Parser parser = new Parser(text);
    parser.whitespace();
    Object value = parser.value();
    parser.whitespace();
    if (parser.at < text.length()) {
      throw parser.error("trailing characters after the value");
    }
    return value;
  }

  /** {@code text} as an object, or a refusal when it spells anything else. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(String text) {
    Object value = parse(text);
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new VisPythonException("vis-python: expected a JSON object", Map.of("json", text));
  }

  /** {@code value} as one line of JSON. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  private static void append(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("null");
      case String s -> string(out, s);
      case Boolean b -> out.append(b.booleanValue());
      case Integer i -> out.append(i.intValue());
      case Long l -> out.append(l.longValue());
      case Short s -> out.append(s.shortValue());
      case Byte b -> out.append(b.byteValue());
      case Double d -> out.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
      case Float f -> out.append(f.isNaN() || f.isInfinite() ? "null" : f.toString());
      case Number n -> out.append(n);
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          string(out, String.valueOf(entry.getKey()));
          out.append(':');
          append(out, entry.getValue());
        }
        out.append('}');
      }
      case Iterable<?> items -> {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
          if (!first) {
            out.append(',');
          }
          first = false;
          append(out, item);
        }
        out.append(']');
      }
      case Object[] items -> append(out, List.of(items));
      default -> string(out, value.toString());
    }
  }

  private static void string(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** A recursive-descent reader over one string. */
  private static final class Parser {
    private final String text;
    private int at;

    Parser(String text) {
      this.text = text;
    }

    VisPythonException error(String reason) {
      return new VisPythonException("vis-python: malformed JSON, " + reason + " at " + at,
          Map.of("offset", at));
    }

    void whitespace() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
          at++;
        } else {
          return;
        }
      }
    }

    private char peek() {
      if (at >= text.length()) {
        throw error("the text ended");
      }
      return text.charAt(at);
    }

    private void expect(char c) {
      if (peek() != c) {
        throw error("expected '" + c + "'");
      }
      at++;
    }

    private void word(String word) {
      if (!text.startsWith(word, at)) {
        throw error("unknown literal");
      }
      at += word.length();
    }

    Object value() {
      char c = peek();
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> {
          word("true");
          yield Boolean.TRUE;
        }
        case 'f' -> {
          word("false");
          yield Boolean.FALSE;
        }
        case 'n' -> {
          word("null");
          yield null;
        }
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield number();
          }
          throw error("unexpected character '" + c + "'");
        }
      };
    }

    private Map<String, Object> object() {
      expect('{');
      Map<String, Object> map = new LinkedHashMap<>();
      whitespace();
      if (peek() == '}') {
        at++;
        return map;
      }
      while (true) {
        whitespace();
        String key = string();
        whitespace();
        expect(':');
        whitespace();
        map.put(key, value());
        whitespace();
        char c = peek();
        at++;
        if (c == '}') {
          return map;
        }
        if (c != ',') {
          throw error("expected ',' or '}'");
        }
      }
    }

    private List<Object> array() {
      expect('[');
      List<Object> list = new ArrayList<>();
      whitespace();
      if (peek() == ']') {
        at++;
        return list;
      }
      while (true) {
        whitespace();
        list.add(value());
        whitespace();
        char c = peek();
        at++;
        if (c == ']') {
          return list;
        }
        if (c != ',') {
          throw error("expected ',' or ']'");
        }
      }
    }

    private String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = peek();
        at++;
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        char escaped = peek();
        at++;
        switch (escaped) {
          case '"' -> out.append('"');
          case '\\' -> out.append('\\');
          case '/' -> out.append('/');
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (at + 4 > text.length()) {
              throw error("a truncated unicode escape");
            }
            try {
              out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
            } catch (NumberFormatException e) {
              throw error("a malformed unicode escape");
            }
            at += 4;
          }
          default -> throw error("unknown escape '\\" + escaped + "'");
        }
      }
    }

    private Number number() {
      int start = at;
      boolean integral = true;
      if (peek() == '-') {
        at++;
      }
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c >= '0' && c <= '9') {
          at++;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          integral = false;
          at++;
        } else {
          break;
        }
      }
      String token = text.substring(start, at);
      try {
        if (integral) {
          return Long.valueOf(token);
        }
        return Double.valueOf(token);
      } catch (NumberFormatException e) {
        throw error("a malformed number '" + token + "'");
      }
    }
  }
}
