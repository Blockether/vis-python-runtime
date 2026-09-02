package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where the cdylib is, and what this library calls itself.
 *
 * <p>Nothing links at build time. The library is resolved when it is first
 * needed, in this order: a path a host {@link #use(String) named} outright,
 * {@code VIS_PYTHON_NATIVE_PATH} (a file, or a directory holding the platform's
 * file), then the classpath resource {@code prebuilds/<platform>/<file>} a
 * checkout of this repository has after a native build. A checkout with no
 * build simply throws from here.
 *
 * <p>A platform artifact is a DIRECTORY, not a file: the cdylib plus the
 * vendored interpreter beside it, because {@link Locations#pythonHome} resolves
 * {@code python/} next to the library. That tree is tens of megabytes with
 * symlinks and execute bits, so it never travels inside a jar — it ships as the
 * {@code tar.gz} release asset for the platform, and the host that unpacked one
 * hands the result to {@link #use(String)}.
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

  private static Library fromClasspath(String platformTag, String fileName) {
    String resource = "prebuilds/" + platformTag + "/" + fileName;
    URL url = Native.class.getClassLoader().getResource(resource);
    if (url == null) {
      return null;
    }
    if (!"file".equals(url.getProtocol())) {
      throw new VisPythonException(
          "A runtime library must be a real directory on disk, not " + url + " - unpack the "
              + "platform release archive and call use(path).",
          Map.of("resource", resource, "platform", platformTag, "url", url.toString()));
    }
    try {
      return new Library("resource", Path.of(url.toURI()).toAbsolutePath().toString());
    } catch (java.net.URISyntaxException e) {
      throw new VisPythonException("Could not read " + resource + ": " + e.getMessage(),
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
              + ", or unpack the vis-python-runtime-" + platformTag
              + " release archive and name it with use(path).",
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
