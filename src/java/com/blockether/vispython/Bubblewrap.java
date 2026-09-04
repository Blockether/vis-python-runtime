package com.blockether.vispython;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a {@link JailPolicy} to the argument list of the bubblewrap embedded
 * in {@code libvisjail}. Only bound paths exist in the child; a later bind wins,
 * which is how the deny lists override the allows.
 */
public final class Bubblewrap {
  /** Read-only code and configuration a toolchain needs to launch; {@code -try} tolerates a distro without one. */
  static final List<String> SYSTEM_READ_ROOTS = List.of("/usr", "/bin", "/sbin", "/lib", "/lib64",
      "/lib32", "/etc", "/opt", "/nix", "/run", "/var/lib");
  private static final List<String> BIN_DIRS = List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin",
      "/usr/local/bin", "/usr/local/sbin");

  private Bubblewrap() {}

  /** The session bus socket, when the desktop exports one. */
  static String sessionBus() {
    String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
    if (runtimeDir == null || runtimeDir.isBlank()) {
      return null;
    }
    Path bus = Path.of(runtimeDir, "bus");
    return Files.exists(bus) ? bus.toString() : null;
  }

  /** Whether the child keeps the host network: a bridge owns the namespace when it has an endpoint. */
  public static boolean unshareNetwork(JailPolicy policy) {
    return policy.egress().kind() == JailPolicy.Egress.Kind.OFF && policy.inbound().isEmpty();
  }

  /** The bridged proxy port, or 0. */
  public static int proxyPort(JailPolicy policy) {
    return policy.egress().kind() == JailPolicy.Egress.Kind.PROXY ? policy.egress().proxyPort() : 0;
  }

  /** The bridged inbound port, or 0: the network bridge forwards one port, the first listed. */
  public static int inboundPort(JailPolicy policy) {
    return policy.inbound().isEmpty() ? 0 : policy.inbound().get(0);
  }

  public static List<String> compile(JailPolicy policy) {
    JailPolicy.Resolved resolved = policy.resolve();
    List<String> args = new ArrayList<>(List.of("--die-with-parent", "--proc", "/proc", "--dev", "/dev"));
    // System roots at their literal spelling: on merged-usr distros /lib64 is a symlink and
    // the ELF interpreter path is hard-coded, so a canonicalised mount point breaks every exec.
    Set<String> readOnly = new LinkedHashSet<>(SYSTEM_READ_ROOTS);
    readOnly.addAll(resolved.readOnly());
    for (String path : readOnly) {
      bind(args, "--ro-bind-try", path, path);
    }
    for (String path : resolved.readWrite()) {
      bind(args, "--bind-try", path, path);
    }
    for (String path : resolved.denyWrite()) {
      bind(args, "--ro-bind-try", path, path);
    }
    for (String path : resolved.denyRead()) {
      if (JailPolicy.isDirectory(path)) {
        args.add("--tmpfs");
        args.add(path);
      } else {
        bind(args, "--ro-bind-try", "/dev/null", path);
      }
    }
    // A denied binary is masked with a character device so execve fails, at its own path
    // and at every existing bin-dir alias of its name, since merged-usr mounts both.
    Set<String> masked = new LinkedHashSet<>();
    for (String path : resolved.denyExec()) {
      String name = Path.of(path).getFileName().toString();
      masked.add(path);
      for (String dir : BIN_DIRS) {
        masked.add(dir + "/" + name);
      }
    }
    for (String path : masked) {
      if (Files.exists(Path.of(path))) {
        bind(args, "--ro-bind-try", "/dev/null", path);
      }
    }
    String bus = policy.keychain() ? sessionBus() : null;
    if (bus != null) {
      bind(args, "--ro-bind-try", bus, bus);
    }
    if (unshareNetwork(policy)) {
      args.add("--unshare-net");
    }
    args.add("--");
    return args;
  }

  private static void bind(List<String> args, String flag, String source, String target) {
    args.add(flag);
    args.add(source);
    args.add(target);
  }
}
