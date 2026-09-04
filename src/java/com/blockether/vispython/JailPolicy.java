package com.blockether.vispython;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One confinement policy for a spawned child: the same value compiles to a
 * Seatbelt profile on macOS and to bubblewrap arguments on Linux.
 *
 * <p>Paths may start with {@code ~}. Writes are allowed only under
 * {@code readWrite}; reads under {@code readWrite}, {@code readOnly} and the
 * platform's own code and configuration; a deny list always wins. The temp
 * directories and the platform read roots are the compiler's to add, so a
 * caller names only the session's directories. {@code unixConnect} contains exact
 * local control sockets a child may dial. {@code inbound} ports are additionally
 * reachable on every interface; loopback listeners are always allowed.
 * {@code keychain} opens the OS credential store: the Security services and
 * keychain databases on macOS, the D-Bus session bus on Linux.
 */
public record JailPolicy(List<String> readWrite, List<String> readOnly, List<String> denyRead,
    List<String> denyWrite, List<String> denyExec, List<String> unixConnect, Egress egress,
    List<Integer> inbound, boolean keychain) {

  /** Where a child's outbound sockets may go: nowhere, one loopback proxy port, or anywhere. */
  public record Egress(Kind kind, int proxyPort) {
    public enum Kind { OFF, PROXY, OPEN }

    public static final Egress OFF = new Egress(Kind.OFF, 0);
    public static final Egress OPEN = new Egress(Kind.OPEN, 0);

    public static Egress proxy(int port) {
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("proxy port must be between 1 and 65535: " + port);
      }
      return new Egress(Kind.PROXY, port);
    }
  }

  public JailPolicy {
    readWrite = strings(readWrite);
    readOnly = strings(readOnly);
    denyRead = strings(denyRead);
    denyWrite = strings(denyWrite);
    denyExec = strings(denyExec);
    unixConnect = strings(unixConnect);
    egress = egress == null ? Egress.OFF : egress;
    inbound = ports(inbound);
  }

  private static List<String> strings(List<String> values) {
    if (values == null) {
      return List.of();
    }
    Set<String> distinct = new LinkedHashSet<>();
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        distinct.add(value);
      }
    }
    return List.copyOf(distinct);
  }

  private static List<Integer> ports(List<Integer> values) {
    if (values == null) {
      return List.of();
    }
    Set<Integer> distinct = new LinkedHashSet<>();
    for (Integer port : values) {
      if (port == null || port < 1 || port > 65535) {
        throw new IllegalArgumentException("inbound port must be between 1 and 65535: " + port);
      }
      distinct.add(port);
    }
    return List.copyOf(distinct);
  }

  /** {@code ~} and {@code ~/…} against {@code user.home}; anything else verbatim. */
  static String expandHome(String path) {
    String home = System.getProperty("user.home");
    if (path.equals("~")) {
      return home;
    }
    if (path.startsWith("~/")) {
      return home + path.substring(1);
    }
    return path;
  }

  /** The canonical real path, or null when it does not resolve. Both enforcers match resolved paths. */
  static String realPath(String path) {
    try {
      return Path.of(expandHome(path)).toRealPath().toString();
    } catch (java.io.IOException | InvalidPathException | SecurityException e) {
      return null;
    }
  }

  /** A deny target fails safe: the real path when it resolves, else the expanded string. */
  static String denyPath(String path) {
    String real = realPath(path);
    return real == null ? expandHome(path) : real;
  }

  static List<String> realPaths(List<String> paths) {
    Set<String> distinct = new LinkedHashSet<>();
    for (String path : paths) {
      String real = realPath(path);
      if (real != null) {
        distinct.add(real);
      }
    }
    return List.copyOf(distinct);
  }

  static List<String> denyPaths(List<String> paths) {
    Set<String> distinct = new LinkedHashSet<>();
    for (String path : paths) {
      distinct.add(denyPath(path));
    }
    return List.copyOf(distinct);
  }

  /** The always-writable temp directories a child cannot run without. */
  static List<String> tempDirs() {
    List<String> dirs = new ArrayList<>();
    dirs.add(System.getProperty("java.io.tmpdir"));
    dirs.add("/tmp");
    return dirs;
  }

  /** The policy with every path resolved and the temp directories granted. */
  Resolved resolve() {
    List<String> rw = new ArrayList<>(readWrite);
    rw.addAll(tempDirs());
    return new Resolved(realPaths(rw), realPaths(readOnly), denyPaths(denyRead),
        denyPaths(denyWrite), denyPaths(denyExec));
  }

  /** Real paths only; a deny target that does not exist keeps its expanded spelling. */
  record Resolved(List<String> readWrite, List<String> readOnly, List<String> denyRead,
      List<String> denyWrite, List<String> denyExec) {}

  static boolean isDirectory(String path) {
    return Files.isDirectory(Path.of(path));
  }
}
