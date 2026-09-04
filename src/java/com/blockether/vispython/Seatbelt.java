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
  private static final List<String> METADATA_DIRS = List.of("/", "/Users", "/Volumes", "/private",
      "/opt", "/etc", "/var", "/tmp", "/home");

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
      out.append("(deny file-write*").append(subpaths(resolved.denyWrite())).append(')');
    }
    if (!resolved.denyRead().isEmpty()) {
      out.append("(deny file-read*").append(subpaths(resolved.denyRead())).append(')');
    }
    // A file-read deny does not stop exec of a signed binary; only process-exec* does.
    if (!resolved.denyExec().isEmpty()) {
      out.append("(deny process-exec*").append(subpaths(resolved.denyExec())).append(')');
    }
    out.append(network(policy));
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
