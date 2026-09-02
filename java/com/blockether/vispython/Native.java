package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Where the cdylib is, and what this library calls itself.
 *
 * <p>Nothing links at build time. The library is resolved when it is first
 * needed, in this order: a path a host {@link #use(String) named} outright,
 * {@code VIS_PYTHON_NATIVE_PATH} (a file, or a directory holding the platform's
 * file), then the classpath resource {@code prebuilds/<platform>/<file>} that
 * the per-platform artifact
 * {@code com.blockether/vis-python-runtime-native-<platform>} carries. A
 * checkout with no build simply throws from here.
 *
 * <p>A platform artifact is a DIRECTORY, not a file: the cdylib plus the
 * vendored interpreter beside it, because {@link Locations#pythonHome} resolves
 * {@code python/} next to the library. So a prebuild that arrives inside a jar
 * is materialized WHOLE, once per version, under {@link Locations#runtimeDir} —
 * extracting the library alone gives a runtime whose first import fails for a
 * standard library that was never unpacked.
 */
public final class Native {

  /** Name of the environment variable that overrides library resolution. */
  public static final String NATIVE_PATH_ENV = "VIS_PYTHON_NATIVE_PATH";

  /** A path a host named outright, ahead of the environment and the classpath. */
  private static volatile String configured;

  private Native() {}

  /**
   * Use this cdylib (or the directory holding it) instead of resolving one, for
   * a host that fetched a platform artifact itself — the JVM cannot set its own
   * environment, so a resolved path has to arrive as a call. Passing null
   * restores ordinary resolution. Takes effect for the NEXT resolution: the
   * interpreter loads its library once per process.
   */
  public static void use(String path) {
    configured = path == null || path.isBlank() ? null : path.trim();
  }

  /** Where a resolved cdylib came from, and the absolute path FFM will open. */
  public record Library(String source, String path) {}

  /**
   * This library's version: the {@code vis-python-runtime/VERSION} resource the
   * build writes from the repo-root VIS_PYTHON_VERSION file, verbatim, else
   * "dev" in a source checkout where no build has run.
   */
  public static String version() {
    URL url = Native.class.getClassLoader().getResource("vis-python-runtime/VERSION");
    if (url == null) {
      return "dev";
    }
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String osTag(String osName) {
    String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (name.contains("mac") || name.contains("darwin")) {
      return "darwin";
    }
    if (name.contains("linux")) {
      return "linux";
    }
    if (name.contains("windows")) {
      return "windows";
    }
    throw new VisPythonException("Unsupported operating system: " + osName,
        Map.of("os-name", String.valueOf(osName)));
  }

  private static String archTag(String osArch) {
    String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
    return switch (arch) {
      case "aarch64", "arm64" -> "arm64";
      case "x86_64", "amd64", "x64" -> "x64";
      default -> throw new VisPythonException("Unsupported architecture: " + osArch,
          Map.of("os-arch", String.valueOf(osArch)));
    };
  }

  /**
   * The platform tag prebuilt artifacts are named by, {@code <os>-<arch>}, for
   * example {@code darwin-arm64}. Throws for an OS or architecture we do not
   * build, because a guessed tag resolves an artifact that cannot exist.
   */
  public static String platform(String osName, String osArch) {
    return osTag(osName) + "-" + archTag(osArch);
  }

  public static String platform() {
    return platform(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  /** The cdylib file name for a platform tag. */
  public static String libraryName(String platformTag) {
    String os = platformTag == null ? "" : platformTag.split("-")[0];
    return switch (os) {
      case "darwin" -> "libvispython.dylib";
      case "linux" -> "libvispython.so";
      case "windows" -> "vispython.dll";
      default -> throw new VisPythonException("Unknown platform tag: " + platformTag,
          Map.of("platform", String.valueOf(platformTag)));
    };
  }

  public static String libraryName() {
    return libraryName(platform());
  }

  private static Library fromConfigured(String platformTag, String fileName) {
    String raw = configured;
    String source = "configured";
    if (raw == null || raw.isBlank()) {
      raw = System.getenv(NATIVE_PATH_ENV);
      source = "env";
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    Path path = Path.of(raw.trim());
    if (Files.isDirectory(path)) {
      path = path.resolve(fileName);
    }
    if (!Files.isRegularFile(path)) {
      throw new VisPythonException(
          "A runtime library was named but is not there: " + path,
          Map.of("env", NATIVE_PATH_ENV, "path", path.toString(), "platform", platformTag,
              "source", source));
    }
    return new Library(source, path.toAbsolutePath().toString());
  }

  /**
   * Unpack {@code prebuilds/<platform>/} out of a platform jar into
   * {@link Locations#runtimeDir}, and answer the library inside it. The WHOLE
   * directory travels: the interpreter is resolved by adjacency, so a library
   * without the tree beside it is a runtime that cannot import.
   *
   * <p>Extraction lands in a sibling temporary directory and is MOVED into
   * place, so a second process either sees a complete installation or none —
   * never a half-written standard library. A jar carries no file mode, so what
   * has to be executable ({@code bin/}, the shared libraries) is made so here.
   */
  public static Library materialize(Path jarFile, String platformTag) {
    return materialize(jarFile, platformTag, Path.of(Locations.runtimeDir(version(), platformTag)));
  }

  /** As {@link #materialize(Path, String)}, into a directory the caller names. */
  public static Library materialize(Path jarFile, String platformTag, Path home) {
    String fileName = libraryName(platformTag);
    Path library = home.resolve(fileName);
    if (Files.isRegularFile(library)) {
      return new Library("materialized", library.toAbsolutePath().toString());
    }
    String prefix = "prebuilds/" + platformTag + "/";
    Path staging = home.resolveSibling(home.getFileName() + ".tmp." + ProcessHandle.current().pid());
    try (JarFile jar = new JarFile(jarFile.toFile())) {
      Files.createDirectories(staging);
      Enumeration<JarEntry> entries = jar.entries();
      boolean any = false;
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.startsWith(prefix) || entry.isDirectory()) {
          continue;
        }
        Path out = staging.resolve(name.substring(prefix.length())).normalize();
        if (!out.startsWith(staging)) {
          throw new VisPythonException("Refusing a jar entry that escapes its directory: " + name,
              Map.of("jar", jarFile.toString(), "entry", name));
        }
        Files.createDirectories(out.getParent());
        try (InputStream in = jar.getInputStream(entry)) {
          Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        any = true;
      }
      if (!any) {
        throw new VisPythonException("Jar carries no prebuild for " + platformTag + ": " + jarFile,
            Map.of("jar", jarFile.toString(), "platform", platformTag, "prefix", prefix));
      }
      makeExecutable(staging);
      Files.createDirectories(home.getParent());
      try {
        Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException race) {
        // Another process finished first; its installation is as good as ours.
        deleteTree(staging);
      }
      if (!Files.isRegularFile(library)) {
        throw new VisPythonException("Extracted prebuild holds no " + fileName + ": " + home,
            Map.of("jar", jarFile.toString(), "platform", platformTag, "home", home.toString()));
      }
      return new Library("materialized", library.toAbsolutePath().toString());
    } catch (IOException e) {
      deleteTree(staging);
      throw new VisPythonException("Could not unpack " + jarFile + ": " + e.getMessage(),
          Map.of("jar", jarFile.toString(), "platform", platformTag), e);
    }
  }

  private static void makeExecutable(Path root) throws IOException {
    try (Stream<Path> tree = Files.walk(root)) {
      tree.filter(Files::isRegularFile)
          .filter(path -> {
            Path parent = path.getParent();
            String name = path.getFileName().toString();
            return (parent != null && parent.getFileName().toString().equals("bin"))
                || name.endsWith(".dylib") || name.endsWith(".dll") || name.contains(".so");
          })
          .forEach(path -> path.toFile().setExecutable(true, false));
    }
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> tree = Files.walk(root)) {
      tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // A leftover staging directory costs disk, never correctness.
    }
  }

  private static Library fromClasspath(String platformTag, String fileName) {
    String resource = "prebuilds/" + platformTag + "/" + fileName;
    URL url = Native.class.getClassLoader().getResource(resource);
    if (url == null) {
      return null;
    }
    try {
      if ("file".equals(url.getProtocol())) {
        return new Library("resource", Path.of(url.toURI()).toAbsolutePath().toString());
      }
      URL jarUrl = ((JarURLConnection) url.openConnection()).getJarFileURL();
      return materialize(Path.of(jarUrl.toURI()), platformTag);
    } catch (IOException | java.net.URISyntaxException | ClassCastException e) {
      throw new VisPythonException("Could not extract " + resource + ": " + e.getMessage(),
          Map.of("resource", resource, "platform", platformTag), e);
    }
  }

  /**
   * The cdylib for a platform tag: a named path first, then the environment,
   * then the bundled classpath resource, and a refusal naming them when none
   * answers.
   */
  public static Library library(String platformTag) {
    String fileName = libraryName(platformTag);
    Library found = fromConfigured(platformTag, fileName);
    if (found == null) {
      found = fromClasspath(platformTag, fileName);
    }
    if (found == null) {
      throw new VisPythonException(
          "No vis-python runtime library for " + platformTag + " - set " + NATIVE_PATH_ENV
              + " or add the com.blockether/vis-python-runtime-native-" + platformTag
              + " artifact to the classpath.",
          Map.of("platform", platformTag,
              "file", fileName,
              "env", NATIVE_PATH_ENV,
              "resource", "prebuilds/" + platformTag + "/" + fileName));
    }
    return found;
  }

  public static Library library() {
    return library(platform());
  }
}
