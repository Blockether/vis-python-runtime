package com.blockether.vispython;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Loads the Windows bundle without adding writable directories to the process DLL search path. */
final class WindowsLibrary {
  private static final int SEARCH_DLL_LOAD_DIR = 0x00000100;
  private static final int SEARCH_SYSTEM32 = 0x00000800;
  private static final Map<String, FunctionDescriptor> SIGNATURES = Map.of(
      "LoadLibraryExW", FunctionDescriptor.of(ValueLayout.ADDRESS,
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

  private WindowsLibrary() {}

  static void preload(String library) {
    Path bridge = Path.of(library).toAbsolutePath();
    Path home = bridge.getParent().resolve("python");
    List<Path> candidates;
    try (Stream<Path> files = Files.list(home)) {
      candidates = files.filter(path -> path.getFileName().toString().matches("python[0-9]{2,}\\.dll"))
          .filter(Files::isRegularFile).toList();
    } catch (IOException error) {
      throw new UncheckedIOException("Could not read the bundled Windows Python directory: " + home,
          error);
    }
    if (candidates.size() != 1) {
      throw new VisPythonException("Windows runtime must contain exactly one versioned Python DLL",
          Map.of("directory", home.toString(), "matches", candidates.size()));
    }
    // kernel32 is a Windows KnownDLL, not an application or PATH lookup. Both
    // loaded modules remain referenced for the interpreter's process lifetime.
    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    MethodHandle load = Linker.nativeLinker().downcallHandle(
        kernel.find("LoadLibraryExW").orElseThrow(), SIGNATURES.get("LoadLibraryExW"));
    load(load, candidates.getFirst());
    load(load, bridge);
  }

  private static void load(MethodHandle loader, Path path) {
    try (Arena arena = Arena.ofConfined()) {
      byte[] bytes = (path.toString() + '\0').getBytes(StandardCharsets.UTF_16LE);
      MemorySegment name = arena.allocate(bytes.length, 2);
      name.copyFrom(MemorySegment.ofArray(bytes));
      MemorySegment module;
      try {
        module = (MemorySegment) loader.invokeExact(name, MemorySegment.NULL,
            SEARCH_DLL_LOAD_DIR | SEARCH_SYSTEM32);
      } catch (Throwable error) {
        throw new VisPythonException("Could not load Windows runtime library: " + path,
            Map.of("library", path.toString()), error);
      }
      if (MemorySegment.NULL.equals(module)) {
        throw new VisPythonException("Windows refused to load runtime library or its bundled dependencies: "
            + path, Map.of("library", path.toString()));
      }
    }
  }
}
