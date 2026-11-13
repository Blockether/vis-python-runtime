package com.blockether.vispython;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
 *
 * <p>The host deletes with its own rights, and a process in the jail can change the
 * directory during the delete. Each step of the delete is thus relative to an open
 * directory and follows no symbolic link: it removes nothing outside the directory.
 */
public record SocketDirectory(String variable, String parent) {
  private static final String PREFIX = "vis-";
  /** Not an owner character, so one owner is never the prefix of another. */
  private static final char OWNER_END = '@';
  private static final int NAME_ATTEMPTS = 100;
  /** A worker socket is two levels down. Deeper trees stay: each level holds open files. */
  private static final int MAX_DEPTH = 32;
  /** A process can add entries during the delete; the owner sweep takes what stays. */
  private static final int PASSES = 3;
  /** The directories of the live children of this process. Their exit deletes them; a sweep does not. */
  private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

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
    return PREFIX + (owner == null ? "" : owner) + OWNER_END;
  }

  /** A new empty directory for one child of a policy with {@code owner} (null: no owner). */
  Path create(String owner) {
    try {
      Path root = root();
      FileAttribute<?> mode =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
      for (int attempt = 0; attempt < NAME_ATTEMPTS; attempt++) {
        // A short name, not the 19 digits of a temporary name: the socket path has a limit.
        Path dir = root.resolve(prefix(owner)
            + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24)));
        try {
          Files.createDirectory(dir, mode);
          LIVE.add(dir);
          return dir;
        } catch (FileAlreadyExistsException taken) {
          // Try another name.
        }
      }
      throw new IOException("no free name under " + root);
    } catch (IOException | InvalidPathException | UnsupportedOperationException exception) {
      throw new VisPythonException("Could not create a socket directory: " + exception,
          Map.of("parent", parent == null ? "/tmp" : parent), exception);
    }
  }

  /** Delete the directory of a child that ended, and return whether it is gone. */
  static boolean delete(Path dir) {
    try (DirectoryStream<Path> parent = Files.newDirectoryStream(dir.getParent())) {
      if (parent instanceof SecureDirectoryStream<Path> secure) {
        remove(secure, dir.getFileName(), 0);
      }
    } catch (IOException | RuntimeException exception) {
      // It stays until an owner sweep.
    } finally {
      LIVE.remove(dir);
    }
    return !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean isDirectory(SecureDirectoryStream<Path> parent, Path name) {
    try {
      return parent.getFileAttributeView(name, BasicFileAttributeView.class,
          LinkOption.NOFOLLOW_LINKS).readAttributes().isDirectory();
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  /**
   * Delete the entry {@code name} of the open directory {@code parent}: a directory with
   * its contents, any other entry as it is. An entry that cannot go stays, and then its
   * directories stay too.
   */
  private static void remove(SecureDirectoryStream<Path> parent, Path name, int depth)
      throws IOException {
    if (!isDirectory(parent, name)) {
      parent.deleteFile(name);
      return;
    }
    if (depth > MAX_DEPTH) {
      throw new IOException("too deep: " + name);
    }
    for (int pass = 0; pass < PASSES; pass++) {
      try (SecureDirectoryStream<Path> dir =
          parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
        // Read the names first: a delete during the read can hide an entry.
        List<Path> entries = new ArrayList<>();
        for (Path entry : dir) {
          entries.add(entry.getFileName());
        }
        if (entries.isEmpty()) {
          break;
        }
        for (Path entry : entries) {
          try {
            remove(dir, entry, depth + 1);
          } catch (IOException | RuntimeException exception) {
            // It stays.
          }
        }
      }
    }
    parent.deleteDirectory(name);
  }

  /**
   * Delete each directory that {@link #create} made for {@code owner} and that no live
   * child of this process has; return how many.
   */
  int sweep(String owner) {
    int deleted = 0;
    try {
      Path root = root();
      try (DirectoryStream<Path> parent = Files.newDirectoryStream(root)) {
        if (!(parent instanceof SecureDirectoryStream<Path> secure)) {
          return 0;
        }
        List<Path> left = new ArrayList<>();
        for (Path entry : parent) {
          Path name = entry.getFileName();
          if (name.toString().startsWith(prefix(owner)) && !LIVE.contains(root.resolve(name))) {
            left.add(name);
          }
        }
        for (Path name : left) {
          try {
            if (isDirectory(secure, name)) {
              remove(secure, name, 0);
              deleted++;
            }
          } catch (IOException | RuntimeException exception) {
            // It stays.
          }
        }
      }
    } catch (IOException | RuntimeException exception) {
      // No parent, no directories.
    }
    return deleted;
  }
}
