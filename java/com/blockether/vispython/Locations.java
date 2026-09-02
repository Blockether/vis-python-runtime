package com.blockether.vispython;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every directory this runtime decides for itself, in one place.
 *
 * <p>Two of them are per-machine STATE under {@code ~/.vis/python}: what pip
 * installed and what CPython compiled. Both are caches in the sense that
 * matters - deleting the tree costs a download and a recompile, never data -
 * and neither belongs inside a shipped, hashed installation. The rest name the
 * vendored interpreter that travels beside the cdylib.
 *
 * <p>Every one of them takes an environment override, because a host that keeps
 * its state somewhere else has to be able to say so without a fork.
 */
public final class Locations {

  public static final String PYTHON_HOME_ENV = "VIS_PYTHON_HOME";
  public static final String PACKAGES_ENV = "VIS_PYTHON_PACKAGES";
  public static final String PYCACHE_PREFIX_ENV = "VIS_PYTHON_PYCACHE_PREFIX";
  public static final String SOURCE_PATH_ENV = "VIS_PYTHON_SOURCE_PATH";

  private Locations() {}

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String statePath(String... parts) {
    String home = System.getProperty("user.home");
    if (home == null || home.isBlank()) {
      return null;
    }
    Path path = Path.of(home, ".vis", "python");
    for (String part : parts) {
      path = path.resolve(part);
    }
    return path.toAbsolutePath().toString();
  }

  /**
   * The vendored CPython tree to root the interpreter at, or null to let CPython
   * search for itself.
   *
   * <p>{@code VIS_PYTHON_HOME} wins. Otherwise the tree is the {@code python/}
   * directory BESIDE the resolved cdylib, which is how a shipped platform
   * artifact is laid out: the library and the interpreter it was linked against
   * travel together, so an installation carries its own standard library instead
   * of borrowing whatever Python the machine has. A source checkout that built
   * against a system interpreter has no such directory, and gets null.
   */
  public static String pythonHome(String libraryPath) {
    String override = env(PYTHON_HOME_ENV);
    if (override != null) {
      return override;
    }
    if (libraryPath == null) {
      return null;
    }
    Path vendored = Path.of(libraryPath).toAbsolutePath().getParent().resolve("python");
    return Files.isDirectory(vendored) ? vendored.toString() : null;
  }

  /**
   * The private Linux process-jail enforcer beside the resolved cdylib.
   *
   * <p>Linux platform archives carry {@code bin/bwrap} at their root. Returning
   * null for an incomplete archive is deliberate: a host must fail closed rather
   * than resolve an unrelated executable from {@code PATH}.
   */
  public static String bubblewrap(String libraryPath) {
    if (libraryPath == null) {
      return null;
    }
    Path candidate = Path.of(libraryPath).toAbsolutePath().getParent().resolve("bin/bwrap");
    return Files.isRegularFile(candidate) && Files.isExecutable(candidate) ? candidate.toString() : null;
  }

  /**
   * Where packages installed for the sandbox live.
   *
   * <p>The artifact BUNDLES NOTHING: it is an interpreter and its standard
   * library, and everything else arrives through pip, once, into this one
   * directory that every session then imports from. A host confining the
   * interpreter puts this on the READABLE roots and nowhere near the writable
   * ones: installing is something the host does, never a block.
   */
  public static String packagesDir() {
    String override = env(PACKAGES_ENV);
    return override != null ? override : statePath("packages");
  }

  /**
   * Where the interpreter writes the bytecode it compiles.
   *
   * <p>A shipped artifact carries NO bytecode: {@code __pycache__} is
   * per-machine cache that nearly doubles a vendored tree on disk, and CPython's
   * default writes it BESIDE the source file - inside an installation that is
   * read-only, shared between projects, and hashed. So the cache goes beside the
   * packages instead: the first run compiles what it imports, every later run
   * imports at cached speed, and the tree stays exactly as it shipped.
   */
  public static String pycachePrefix() {
    String override = env(PYCACHE_PREFIX_ENV);
    return override != null ? override : statePath("pycache");
  }

  /**
   * Where a packaged artifact extracts the Python it ships, beside the packages
   * and the bytecode cache: per-machine, per-version, and rebuildable by
   * deleting it. A checkout imports from its own {@code resources/} and never
   * comes here.
   */
  public static String sourcesDir() {
    return statePath("sources");
  }

  /**
   * Where a host unpacks the platform release archive, beside the packages and
   * the bytecode cache: per-version, per-platform, and rebuildable by deleting
   * it. A cdylib and the interpreter tree it was linked against are one unit, so
   * both land here together.
   */
  public static String runtimeDir(String version, String platform) {
    return statePath("runtime", version, platform);
  }

  /** Where the JVM's trust anchors are exported for pip, beside the packages. */
  public static String certificatesFile() {
    String packages = packagesDir();
    if (packages == null) {
      return null;
    }
    return Path.of(packages).toAbsolutePath().getParent().resolve("cacert.pem").toString();
  }

  /**
   * The vendored interpreter's own executable, or null when there is no vendored
   * tree. pip is a PROGRAM: the host runs it in a process of its own rather than
   * inside the embedded interpreter, which is confined and, being one interpreter
   * for the whole process, would carry an installer's imports and monkeypatches
   * into every session after it.
   */
  public static String pythonExecutable(String pythonHome) {
    if (pythonHome == null) {
      return null;
    }
    for (String candidate : new String[] {"bin/python3", "python.exe", "bin/python"}) {
      Path path = Path.of(pythonHome).resolve(candidate);
      if (Files.isRegularFile(path)) {
        return path.toAbsolutePath().toString();
      }
    }
    return null;
  }

  /**
   * Directories CPython may import from, in order: what the caller passed, then
   * {@code VIS_PYTHON_SOURCE_PATH}, then whatever this ARTIFACT carries
   * ({@link Sources}), then this repository's own {@code resources/vispython/}
   * and {@code resources/vis-python/} in a dev checkout.
   *
   * <p>The checkout fallback demands BOTH directories, because {@code user.dir}
   * belongs to whoever started the JVM: an embedding host with a
   * {@code resources/vis-python/} of its own would otherwise have its copy
   * imported instead of this library's, silently and in preference.
   */
  public static List<String> sourceRoots(List<String> extra) {
    Set<String> roots = new LinkedHashSet<>();
    if (extra != null) {
      for (String root : extra) {
        if (root != null && !root.isBlank()) {
          roots.add(root);
        }
      }
    }
    String path = env(SOURCE_PATH_ENV);
    if (path != null) {
      for (String root : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
        if (!root.isBlank()) {
          roots.add(root);
        }
      }
    }
    roots.addAll(Sources.importRoots());
    String here = System.getProperty("user.dir");
    if (here != null) {
      List<Path> checkout = List.of(Path.of(here, "resources", "vispython"),
          Path.of(here, "resources", "vis-python"));
      if (Files.isDirectory(checkout.get(0)) && Files.isDirectory(checkout.get(1))) {
        for (Path candidate : checkout) {
          if (Files.isDirectory(candidate)) {
            roots.add(candidate.toAbsolutePath().toString());
          }
        }
      }
    }
    return List.copyOf(roots);
  }
}
