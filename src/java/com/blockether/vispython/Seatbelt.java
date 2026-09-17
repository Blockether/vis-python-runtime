package com.blockether.vispython;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a {@link JailPolicy} to the SBPL profile {@code libvisjail} hands to
 * {@code sandbox_init}. Rules are emitted in Seatbelt's last-match-wins order:
 * allows first, the deny lists after them.
 */
public final class Seatbelt {
  /** Read-only code and configuration every Mach-O binary needs before {@code main}. */
  static final List<String> SYSTEM_READ_ROOTS = List.of("/usr", "/bin", "/sbin", "/System",
      "/Library", "/private/var/db/dyld", "/private/var/select", "/private/etc", "/opt/homebrew",
      "/usr/local", "/opt/local");
  /** What a Keychain client process talks to and opens. */
  static final List<String> KEYCHAIN_SERVICES = List.of("com.apple.SecurityServer",
      "com.apple.ocspd", "com.apple.trustd.agent");
  static final List<String> KEYCHAIN_READ_ROOTS = List.of("~/Library/Keychains", "/Library/Keychains");
  // The JDK canonicalizes entropy-device paths before opening the allowed device files.
  private static final List<String> METADATA_DIRS = List.of("/", "/Users", "/Volumes", "/private",
      "/opt", "/etc", "/var", "/tmp", "/home", "/dev");
  /** Characters a path may contain that a regex would otherwise read as syntax. */
  private static final String REGEX_METACHARACTERS = "\\.+^$|()";

  private Seatbelt() {}

  static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String subpaths(Iterable<String> paths) {
    StringBuilder out = new StringBuilder();
    for (String path : paths) {
      out.append("(subpath ").append(quote(path)).append(')');
    }
    return out.toString();
  }

  /** What a deny glob becomes: an SBPL regex literal, whose text is the regex itself. */
  private static String regexLiteral(String regex) {
    return "#\"" + regex.replace("\"", "\\\"") + "\"";
  }

  /**
   * A deny glob as an anchored regex over absolute paths: {@code *} matches inside one path
   * segment, {@code **} crosses directories, and a match closes the subtree below it. A
   * pattern keeps denying files that appear after the profile was compiled, which an
   * enumerated list of paths cannot do.
   */
  static String globRegex(String glob) {
    StringBuilder out = new StringBuilder("^");
    int braces = 0;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        boolean crossing = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
        out.append(crossing ? ".*" : "[^/]*");
        i += crossing ? 1 : 0;
      } else if (c == '?') {
        out.append("[^/]");
      } else if (c == '[') {
        int close = glob.indexOf(']', i + 1);
        if (close < 0) {
          out.append("\\[");
        } else {
          String body = glob.substring(i + 1, close);
          out.append('[').append(body.startsWith("!") ? "^" + body.substring(1) : body).append(']');
          i = close;
        }
      } else if (c == '{') {
        braces++;
        out.append('(');
      } else if (c == '}' && braces > 0) {
        braces--;
        out.append(')');
      } else if (c == ',' && braces > 0) {
        out.append('|');
      } else if (REGEX_METACHARACTERS.indexOf(c) >= 0) {
        out.append('\\').append(c);
      } else {
        out.append(c);
      }
    }
    return out.append("($|/)").toString();
  }

  /**
   * One deny list as SBPL filters: an exact path closes its own subtree, a glob pattern
   * becomes the equivalent regex, so a matching file created later is denied as well.
   */
  private static String denyTargets(Iterable<String> paths) {
    StringBuilder out = new StringBuilder();
    for (String path : paths) {
      if (JailPolicy.isPattern(path)) {
        out.append("(regex ").append(regexLiteral(globRegex(path))).append(')');
      } else {
        out.append("(subpath ").append(quote(path)).append(')');
      }
    }
    return out.toString();
  }

  private static String literals(Iterable<String> paths) {
    StringBuilder out = new StringBuilder();
    for (String path : paths) {
      out.append("(literal ").append(quote(path)).append(')');
    }
    return out.toString();
  }

  /** Every ancestor directory: a path under a root is canonicalised component by component. */
  private static void ancestors(String path, Set<String> into) {
    Path parent = Path.of(path).getParent();
    while (parent != null) {
      into.add(parent.toString());
      parent = parent.getParent();
    }
  }

  public static String compile(JailPolicy policy) {
    JailPolicy.Resolved resolved = policy.resolve();
    List<String> readOnly = new ArrayList<>(resolved.readOnly());
    if (policy.keychain()) {
      readOnly.addAll(JailPolicy.realPaths(KEYCHAIN_READ_ROOTS));
    }
    Set<String> metadata = new LinkedHashSet<>(METADATA_DIRS);
    metadata.add(System.getProperty("user.home"));
    metadata.add(System.getProperty("java.io.tmpdir"));
    for (String root : resolved.readWrite()) {
      ancestors(root, metadata);
    }
    for (String root : readOnly) {
      ancestors(root, metadata);
    }
    Set<String> metadataSubpaths = new LinkedHashSet<>(SYSTEM_READ_ROOTS);
    metadataSubpaths.addAll(resolved.readWrite());
    metadataSubpaths.addAll(readOnly);

    StringBuilder out = new StringBuilder();
    out.append("(version 1)(import \"system.sb\")(deny default)");
    out.append("(allow process-fork process-exec)(allow sysctl-read)");
    // GraalVM native images signal through a named POSIX semaphore at startup.
    out.append("(allow ipc-posix-sem)");
    // A pty slave (/dev/ttysNNN) is inherited already open; termios and window-size
    // ioctls on it are still path-checked, so an interactive child could not ask
    // its own terminal size without this rule.
    out.append("(allow file-ioctl (literal \"/dev/tty\")(regex #\"^/dev/ttys[0-9]+$\"))");
    if (policy.keychain()) {
      out.append("(allow mach-lookup");
      for (String service : KEYCHAIN_SERVICES) {
        out.append("(global-name ").append(quote(service)).append(')');
      }
      out.append(')');
    }
    out.append("(allow file-read-metadata").append(literals(metadata))
        .append(subpaths(metadataSubpaths)).append(')');
    out.append("(allow file-read*").append(subpaths(SYSTEM_READ_ROOTS))
        .append("(literal \"/dev/null\")(literal \"/dev/zero\")(literal \"/dev/random\")")
        .append("(literal \"/dev/urandom\"))");
    if (!readOnly.isEmpty()) {
      out.append("(allow file-read*").append(subpaths(readOnly)).append(')');
    }
    out.append("(allow file-read* file-write*")
        .append("(literal \"/dev/null\")(literal \"/dev/tty\")(literal \"/dev/stdout\")")
        .append("(literal \"/dev/stderr\")").append(subpaths(resolved.readWrite())).append(')');
    if (!resolved.denyWrite().isEmpty()) {
      out.append("(deny file-write*").append(denyTargets(resolved.denyWrite())).append(')');
    }
    if (!resolved.denyRead().isEmpty()) {
      out.append("(deny file-read*").append(denyTargets(resolved.denyRead())).append(')');
    }
    // A file-read deny does not stop exec of a signed binary; only process-exec* does.
    if (!resolved.denyExec().isEmpty()) {
      out.append("(deny process-exec*").append(denyTargets(resolved.denyExec())).append(')');
    }
    out.append(network(policy));
    List<String> sockets = JailPolicy.realPaths(policy.unixConnect());
    if (!sockets.isEmpty()) {
      out.append("(allow network-outbound (remote unix-socket");
      for (String socket : sockets) {
        out.append("(path ").append(quote(socket)).append(')');
      }
      out.append("))");
    }
    return out.toString();
  }

  /**
   * Binding is broad (any local address, any port); accepting is the gated
   * capability: every loopback port, plus each listed port on every interface.
   */
  private static String network(JailPolicy policy) {
    StringBuilder inbound = new StringBuilder();
    inbound.append("(allow network-bind (local ip))");
    inbound.append("(allow network-inbound (local ip \"localhost:*\"))");
    for (Integer port : policy.inbound()) {
      inbound.append("(allow network-inbound (local ip \"*:").append(port).append("\"))");
    }
    return switch (policy.egress().kind()) {
      case OPEN -> "(allow network*)";
      case OFF -> "(deny network*)" + inbound;
      case PROXY -> "(deny network*)" + inbound + "(allow network-outbound (remote ip \"localhost:"
          + policy.egress().proxyPort() + "\"))";
    };
  }
}
