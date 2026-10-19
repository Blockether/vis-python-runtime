package com.blockether.vispython;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>A deny entry may be a glob pattern ({@code *} inside a segment, {@code **} across
 * directories, {@code ?}, {@code [] } and {@code {}}). Seatbelt enforces a pattern as
 * written, so files that appear later are denied too; bubblewrap mounts paths and skips
 * what it cannot express, so a policy that must hold on Linux also names exact paths.
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
    List<String> granted = new ArrayList<>(rw);
    granted.addAll(readOnly);
    return new Resolved(realPaths(rw), realPaths(readOnly), denyPaths(denyRead),
        denyPaths(denyWrite), denyPaths(denyExec), links(granted));
  }

  /**
   * Real paths only; a deny target that does not exist keeps its expanded spelling.
   * {@code links} maps each symbolic link on the way to a granted path to its target.
   */
  record Resolved(List<String> readWrite, List<String> readOnly, List<String> denyRead,
      List<String> denyWrite, List<String> denyExec, Map<String, String> links) {}

  /** More links than this on one path is a loop; the kernel gives up near the same depth. */
  private static final int MAX_LINKS = 40;

  /**
   * Every symbolic link that a lookup of a granted path walks through, as the link's own
   * path (under its real parent) mapped to its stored target. A grant resolves to a real
   * path, so without these a child that runs {@code java/current/bin/java} is refused at
   * the link {@code current}, although the directory behind it is granted.
   */
  static Map<String, String> links(List<String> paths) {
    Map<String, String> links = new LinkedHashMap<>();
    for (String path : paths) {
      try {
        follow(Path.of(expandHome(path)).toAbsolutePath(), links, new int[] {0});
      } catch (java.io.IOException | InvalidPathException | SecurityException e) {
        // A path that does not resolve is not granted either: it has no links to keep.
      }
    }
    return Collections.unmodifiableMap(links);
  }

  private static Path follow(Path path, Map<String, String> links, int[] hops)
      throws java.io.IOException {
    Path current = path.getRoot();
    for (Path name : path) {
      String part = name.toString();
      if (part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        Path parent = current.getParent();
        current = parent == null ? current : parent;
        continue;
      }
      Path next = current.resolve(part);
      if (!Files.isSymbolicLink(next)) {
        current = next;
        continue;
      }
      if (++hops[0] > MAX_LINKS) {
        throw new java.io.IOException("Too many symbolic links: " + path);
      }
      Path target = Files.readSymbolicLink(next);
      links.putIfAbsent(next.toString(), target.toString());
      current = follow(current.resolve(target), links, hops);
    }
    return current;
  }

  /** True when a deny entry is a PATTERN rather than one path. */
  static boolean isPattern(String value) {
    return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0
        || value.indexOf('{') >= 0;
  }

  static boolean isDirectory(String path) {
    return Files.isDirectory(Path.of(path));
  }
}
