package com.blockether.vispython;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Python this runtime executes, found wherever the artifact happens to be.
 *
 * <p>The cdylib is resolved at runtime and so are the sources: a consumer that
 * took this library as a jar has no {@code resources/} directory to point at,
 * and a native image has no directory at all. The jar therefore carries a
 * {@code vis-python-runtime/SOURCES} manifest naming every file it ships, and
 * this class turns that manifest into DIRECTORIES CPython can import from -
 * because {@code sys.path} takes paths, not classpath entries.
 *
 * <p>Two shapes, one rule: when a listed resource already IS a file on disk -
 * a checkout, a {@code :local/root} dependency, an exploded classpath - its own
 * directory is used and nothing is copied. Otherwise every listed file is
 * extracted once per version under {@code ~/.vis/python/sources}, the same way
 * the cdylib is extracted, and a marker file makes the second start free.
 *
 * <p>A native image must be told to embed these resources; the manifest is the
 * list to register, and it is the reason the list exists as data rather than as
 * a directory walk, which no image can perform.
 */
public final class Sources {

  /** Classpath resource naming every Python file the artifact ships. */
  public static final String MANIFEST = "vis-python-runtime/SOURCES";

  private Sources() {}

  /** Every directory this artifact provides, empty when it ships none. */
  public static List<String> roots() {
    return roots(Sources.class.getClassLoader(), cache());
  }

  /** Where extraction lands: {@code ~/.vis/python/sources/<version>}. */
  public static Path cache() {
    String dir = Locations.sourcesDir();
    return dir == null ? null : Path.of(dir, Native.version());
  }

  /** The same, for a host that resolves the library through a classloader of its own. */
  public static List<String> roots(ClassLoader loader, Path cache) {
    URL anchor = loader.getResource(MANIFEST);
    if (anchor == null) {
      return List.of();
    }
    Map<String, List<String>> listed = manifest(anchor);
    List<String> roots = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : listed.entrySet()) {
      String onDisk = directory(anchor, entry.getKey(), entry.getValue().get(0));
      roots.add(onDisk != null ? onDisk : extract(anchor, cache, entry.getKey(), entry.getValue()));
    }
    return List.copyOf(roots);
  }

  /**
   * A file this artifact ships, addressed from the MANIFEST's own URL rather
   * than looked up by name.
   *
   * <p>The lookup is what cannot be used: an embedding host with a directory of
   * the same name earlier on the classpath answers first, and the runtime then
   * imports the host's files while believing they are its own. The manifest is
   * unique to this library, so everything beside it is addressed relative to it
   * - which works the same for a directory, a jar and an image resource.
   */
  private static URL beside(URL anchor, String entry) {
    String text = anchor.toString();
    int at = text.lastIndexOf(MANIFEST);
    if (at < 0) {
      return null;
    }
    try {
      return java.net.URI.create(text.substring(0, at) + entry).toURL();
    } catch (Exception e) {
      return null;
    }
  }



  /** The manifest's entries grouped by their first path segment, order kept. */
  private static Map<String, List<String>> manifest(URL anchor) {
    Map<String, List<String>> grouped = new LinkedHashMap<>();
    try (InputStream in = anchor.openStream()) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : text.split("\n")) {
        String entry = line.trim();
        int slash = entry.indexOf('/');
        if (entry.isEmpty() || slash <= 0) {
          continue;
        }
        grouped.computeIfAbsent(entry.substring(0, slash), root -> new ArrayList<>()).add(entry);
      }
    } catch (Exception e) {
      throw new VisPythonException("vis-python: the source manifest could not be read",
          Map.of("resource", MANIFEST), e);
    }
    return grouped;
  }

  /**
   * The directory holding {@code entry} when the resource is a plain file, else
   * null - which is every packaged shape, jar and image alike.
   */
  private static String directory(URL anchor, String root, String entry) {
    URL url = beside(anchor, entry);
    if (url == null || !"file".equals(url.getProtocol())) {
      return null;
    }
    try {
      Path file = Path.of(url.toURI());
      Path directory = file;
      // The entry is relative to the classpath root, so climbing one parent per
      // segment lands on the classpath entry; the root's own directory is one
      // below that.
      for (int i = entry.split("/").length - 1; i > 1; i--) {
        directory = directory.getParent();
      }
      directory = directory.getParent();
      return Files.isDirectory(directory) && directory.endsWith(root)
          ? directory.toAbsolutePath().toString()
          : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Copy one root's files out of the artifact, once per version. */
  private static String extract(URL anchor, Path cache, String root, List<String> entries) {
    if (cache == null) {
      throw new VisPythonException("vis-python: no home directory to extract Python sources into",
          Map.of("root", root));
    }
    Path directory = cache.resolve(root);
    Path marker = cache.resolve(root + ".complete");
    if (Files.isRegularFile(marker)) {
      return directory.toAbsolutePath().toString();
    }
    try {
      for (String entry : entries) {
        URL source = beside(anchor, entry);
        try (InputStream in = source == null ? null : source.openStream()) {
          if (in == null) {
            throw new VisPythonException("vis-python: the artifact does not carry a listed source",
                Map.of("resource", entry, "resource-manifest", MANIFEST));
          }
          Path out = cache.resolve(entry);
          Files.createDirectories(out.getParent());
          Files.write(out, in.readAllBytes());
        }
      }
      // Written last: a start interrupted halfway must extract again rather
      // than import a tree missing whatever had not been copied yet.
      Files.write(marker, (entries.size() + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (VisPythonException e) {
      throw e;
    } catch (Exception e) {
      throw new VisPythonException("vis-python: the Python sources could not be extracted",
          Map.of("root", root, "path", directory.toString()), e);
    }
    return directory.toAbsolutePath().toString();
  }
}
