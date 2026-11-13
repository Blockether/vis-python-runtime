package com.blockether.vispython;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * A private directory for ONE confined child, where it may bind and dial Unix sockets
 * (Vis #474). {@link Jail#spawn} makes it under {@code parent} (null: {@code /tmp}), grants
 * it as a {@link JailPolicy#unixSocketDirs} entry, gives its path to the child in the
 * environment variable {@code variable} and deletes it when the child exits. A host
 * program that the child starts reads the variable and puts its control sockets there.
 *
 * <p>The directory has mode 0700 and a real path: Seatbelt matches resolved paths, and the
 * macOS {@code /tmp} is a symbolic link. Its name holds the policy owner, so
 * {@link Jail#endConfined(String, SocketDirectory)} also deletes what a killed spawner left.
 */
public record SocketDirectory(String variable, String parent) {
  private static final String PREFIX = "vis-run-";
  /** Not an owner character, so one owner is never the prefix of another. */
  private static final char OWNER_END = '@';

  public SocketDirectory {
    if (variable == null || variable.isBlank() || variable.indexOf('=') >= 0
        || variable.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("variable must be an environment variable name: " + variable);
    }
    parent = parent == null || parent.isBlank() ? null : parent;
  }

  /** A directory under {@code /tmp}: its path is short, and a socket path holds about 100 bytes. */
  public SocketDirectory(String variable) {
    this(variable, null);
  }

  private Path root() throws IOException {
    return Path.of(parent == null ? "/tmp" : parent).toRealPath();
  }

  private static String prefix(String owner) {
    return owner == null ? PREFIX : PREFIX + owner + OWNER_END;
  }

  /** A new empty directory for one child of a policy with {@code owner} (null: no owner). */
  Path create(String owner) {
    try {
      return Files.createTempDirectory(root(), prefix(owner),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (IOException | InvalidPathException | UnsupportedOperationException exception) {
      throw new VisPythonException("Could not create a socket directory: " + exception,
          Map.of("parent", parent == null ? "/tmp" : parent), exception);
    }
  }

  /**
   * Delete {@code dir} and its contents, and return whether it is gone. The walk does not
   * follow a symbolic link that the child made, so it deletes nothing outside {@code dir}.
   */
  static boolean delete(Path dir) {
    try {
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
            throws IOException {
          Files.deleteIfExists(file);
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure)
            throws IOException {
          Files.deleteIfExists(directory);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException | SecurityException exception) {
      // The owner sweep tries again.
    }
    return !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
  }

  /** Delete each directory that {@link #create} made for {@code owner}; return how many. */
  int sweep(String owner) {
    int deleted = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root(), prefix(owner) + "*")) {
      for (Path entry : entries) {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && delete(entry)) {
          deleted++;
        }
      }
    } catch (IOException | InvalidPathException | SecurityException exception) {
      // No parent, no directories.
    }
    return deleted;
  }
}
