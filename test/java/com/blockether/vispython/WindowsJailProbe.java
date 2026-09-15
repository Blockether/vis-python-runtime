package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only main: run on the JVM and as a native-image FFM launcher, never ship in the jar. */
public final class WindowsJailProbe {
  private static final int LIMIT = 2 * 1024 * 1024;
  private static int checks;

  private WindowsJailProbe() {}

  private record Result(int exit, byte[] output, byte[] error) {
    String out() { return new String(output, StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    String err() { return new String(error, StandardCharsets.UTF_8).replace("\r\n", "\n"); }
  }

  @FunctionalInterface private interface Checked { void run() throws Exception; }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }

  private static void stage(String name, Checked action) throws Exception {
    System.out.println("START WindowsJail " + name);
    try { action.run(); } catch (Exception | AssertionError failed) {
      System.err.println("FAIL WindowsJail " + name);
      failed.printStackTrace(System.err);
      throw failed;
    }
  }

  private static void denied(Checked action, String message) throws Exception {
    try { action.run(); } catch (Exception expected) { checks++; return; }
    throw new AssertionError(message);
  }

  private static CompletableFuture<byte[]> drain(InputStream stream) {
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    Thread.ofPlatform().daemon().start(() -> {
      try (stream) {
        byte[] bytes = stream.readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) throw new IOException("probe output exceeded bounded limit");
        result.complete(bytes);
      } catch (Throwable failure) { result.completeExceptionally(failure); }
    });
    return result;
  }

  private static Result finish(Process process, byte[] input) throws Exception {
    return finish(process, input, drain(process.getInputStream()));
  }

  private static Result finish(Process process, byte[] input, CompletableFuture<byte[]> out) throws Exception {
    CompletableFuture<byte[]> err = drain(process.getErrorStream());
    CompletableFuture<Void> sent = new CompletableFuture<>();
    Thread.ofPlatform().daemon().start(() -> {
      try (var stream = process.getOutputStream()) {
        stream.write(input);
        sent.complete(null);
      } catch (Throwable failure) { sent.completeExceptionally(failure); }
    });
    try {
      if (!process.waitFor(20, TimeUnit.SECONDS)) {
        AssertionError timeout = new AssertionError("process watchdog expired");
        process.destroyForcibly();
        try {
          check(process.waitFor(5, TimeUnit.SECONDS), "timed-out process terminates after kill");
          Result captured = new Result(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS));
          System.err.println("Timed-out child stdout=" + captured.out() + " stderr=" + captured.err());
        } catch (Exception | AssertionError captureFailure) { timeout.addSuppressed(captureFailure); }
        throw timeout;
      }
      checks++;
      sent.get(5, TimeUnit.SECONDS);
      return new Result(process.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      process.getOutputStream().close();
      process.getInputStream().close();
      process.getErrorStream().close();
    }
  }

  private static Result run(WindowsJail jail, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(jail.applicationDirectory().resolve("guest.exe").toString());
    command.addAll(List.of(arguments));
    return finish(jail.spawn(command, Map.of(), null, false, false, 0, 0), new byte[0]);
  }

  private static void passed(Result result, String label) {
    check(result.exit == 0 && result.out().contains("PASS") && !result.err().contains("FAIL"),
        label + ": exit=" + result.exit + " stdout=" + result.out() + " stderr=" + result.err());
  }

  private static String field(String output, String key) {
    return output.lines().filter(line -> line.startsWith(key + "="))
        .map(line -> line.substring(key.length() + 1)).findFirst().orElseThrow();
  }

  private static WindowsJail prepare(Path parent, Path guest) throws Exception {
    WindowsJail jail = WindowsJail.create(parent);
    try { jail.stage(guest, "guest.exe"); return jail; }
    catch (Throwable failure) { jail.close(); throw failure; }
  }

  private static String encoded(String value) {
    StringBuilder text = new StringBuilder("ARG=");
    for (int index = 0; index < value.length(); index++) text.append(String.format("%04X", (int) value.charAt(index)));
    return text.append('\n').toString();
  }

  private static Path profilePath(Path guest, String sid) throws Exception {
    Result result = finish(new ProcessBuilder(guest.toString(), "profile-path", sid).start(), new byte[0]);
    passed(result, "host queries exact token profile");
    String hex = field(result.out(), "PROFILE_PATH");
    check(!hex.isEmpty() && hex.length() % 4 == 0, "profile path preserves UTF-16 code units");
    StringBuilder path = new StringBuilder();
    for (int index = 0; index < hex.length(); index += 4) {
      path.append((char) Integer.parseInt(hex.substring(index, index + 4), 16));
    }
    return Path.of(path.toString());
  }

  private static Path privateAppData(WindowsJail jail, Path profile) {
    // GetAppContainerFolderPath returns local app data, not the profile root.
    check(profile.getFileName().toString().equalsIgnoreCase("AC"), "profile app-data path: " + profile);
    Path name = profile.getParent().getFileName();
    check(name.toString().matches("visjail\\.[0-9a-f]{32}"), "owned profile name: " + profile);
    return jail.temporaryDirectory().resolve("Packages").resolve(name).resolve("AC");
  }

  private static void validation(Path parent, Path guest) throws Exception {
    Path source = Files.writeString(parent.resolve("input.txt"), "source-data");
    var sourceAcl = Files.getFileAttributeView(source, AclFileAttributeView.class).getAcl();
    try (WindowsJail jail = prepare(parent, guest)) {
      check(Files.isDirectory(jail.directory()), "private root exists");
      check(!jail.directory().equals(parent), "context creates a new root");
      // CI 34876244765: the profile-expanded temporary path must exist before any spawn.
      try (var profiles = Files.list(jail.temporaryDirectory().resolve("Packages"))) {
        List<Path> paths = profiles.toList();
        check(paths.size() == 1, "one private application-data profile");
        check(Files.isDirectory(paths.getFirst().resolve("AC/Temp")),
            "Windows-required temporary directory is created before launch");
      }
      Path stagedGuest = jail.applicationDirectory().resolve("guest.exe");
      check(Files.isRegularFile(stagedGuest) && Files.size(stagedGuest) == Files.size(guest),
          "guest executable is staged completely");
      for (String destination : List.of("../escape", "C:\\escape", "file:stream", "NUL", "")) {
        denied(() -> jail.stage(source, destination), "unsafe destination accepted: " + destination);
      }
      jail.stage(source, "data/input.txt");
      check(Files.readString(jail.applicationDirectory().resolve("data/input.txt")).equals("source-data"),
          "staged bytes are copied");
      try {
        jail.spawn(List.of(jail.applicationDirectory().resolve("missing.exe").toString()),
            Map.of(), null, false, false, 0, 0);
        throw new AssertionError("missing executable was accepted");
      } catch (VisPythonException expected) {
        check(expected.getMessage().contains("Pin Windows launch paths")
            && expected.getMessage().contains("Windows error 2"),
            "missing executable preserves the failing operation and native error: " + expected.getMessage());
      }
      passed(run(jail, "token"), "token");
      denied(() -> jail.stage(source, "late.txt"), "staging remains open after spawn");
      denied(() -> jail.spawn(List.of(guest.toString(), "token"), Map.of(), null, false, false, 0, 0),
          "host executable outside staged app accepted");
      denied(() -> jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "token"),
          Map.of(), "../app", false, false, 0, 0), "cwd escaped work directory");
      check(Files.getFileAttributeView(source, AclFileAttributeView.class).getAcl().equals(sourceAcl),
          "staging did not edit source ACL");
    }
    Path hardlink = Files.createLink(parent.resolve("hardlink.txt"), source);
    try (WindowsJail jail = prepare(parent, guest)) {
      denied(() -> jail.stage(hardlink, "linked.txt"), "hardlinked source accepted");
      denied(() -> run(jail, "token"), "failed staging left a usable context");
    } finally { Files.delete(hardlink); }
    check(Files.readString(source).equals("source-data"), "source content unchanged");
  }

  @SuppressWarnings("try") // Explicit early close is the behavior under test; automatic close checks idempotency.
  private static void filesystem(Path parent, Path guest) throws Exception {
    Path secret = Files.writeString(parent.resolve("private-host.txt"), "host-private");
    Path input = Files.writeString(parent.resolve("readonly.txt"), "read-only");
    passed(finish(new ProcessBuilder(guest.toString(), "protect", secret.toString()).start(), new byte[0]),
        "protected explicit user-only host DACL fixture");
    try (WindowsJail first = prepare(parent, guest); WindowsJail second = prepare(parent, guest)) {
      first.stage(input, "readonly.txt");
      Path sibling = Files.writeString(second.workDirectory().resolve("sibling-private.txt"), "sibling-private");
      Result token1 = run(first, "token");
      Result token2 = run(second, "token");
      passed(token1, "first token");
      passed(token2, "second token");
      check(!field(token1.out(), "SID").equals(field(token2.out(), "SID")), "siblings have unique AppContainer SIDs");
      Path profile1 = profilePath(guest, field(token1.out(), "SID"));
      Path profile2 = profilePath(guest, field(token2.out(), "SID"));
      check(Files.isDirectory(profile1) && Files.isDirectory(profile2), "owned profile storage exists");
      check(!profile1.equals(profile2), "contexts have separate profile storage");
      Path profileSecret = Files.writeString(profile2.resolve("sibling-private.txt"), "profile-private");
      passed(run(first, "denied-file", profileSecret.toString()), "sibling profile read and write denied");
      // CI 34876244765: Windows rewrites TEMP/TMP/LOCALAPPDATA into this profile subtree.
      Path data1 = privateAppData(first, profile1);
      Path data2 = privateAppData(second, profile2);
      check(Files.isDirectory(data1.resolve("Temp")) && Files.isDirectory(data2.resolve("Temp")),
          "effective private temporary directories exist");
      Path temporarySecret = Files.writeString(data2.resolve("Temp/sibling-private.txt"), "temporary-private");
      passed(run(first, "denied-file", temporarySecret.toString()), "sibling temporary read and write denied");
      Path junction = Files.createDirectory(first.workDirectory().resolve("outside-junction"));
      try {
        passed(finish(new ProcessBuilder(guest.toString(), "junction", junction.toString(), parent.toString()).start(), new byte[0]),
            "mandatory real junction fixture");
        passed(run(first, "security", secret.toString(), first.applicationDirectory().resolve("readonly.txt").toString(),
            sibling.toString(), first.applicationDirectory().toString(), junction.resolve("private-host.txt").toString(),
            data1.resolve("Temp").toString()),
            "filesystem kernel boundary");
      } finally { Files.deleteIfExists(junction); }
      passed(run(first, "descendant"), "descendant confinement and breakaway denial");
      List<String> command = List.of(first.applicationDirectory().resolve("guest.exe").toString(), "token");
      passed(finish(first.spawn(command, Map.of("VIS_SEATBELT_ACTIVE", "1"), null, false, false, 0, 0), new byte[0]),
          "forged environment marker cannot bypass confinement");
      Files.writeString(first.workDirectory().resolve("retained.txt"), "retained");
      Path output = first.workDirectory().resolve("retained.txt");
      first.close();
      check(Files.readString(output).equals("retained"), "outputs retained on close");
      check(Files.notExists(profile1), "close removes owned profile storage");
      check(Files.isDirectory(profile2) && Files.readString(profileSecret).equals("profile-private"),
          "closing a context preserves its sibling profile");
      denied(() -> first.spawn(command, Map.of(), null, false, false, 0, 0), "closed context launched a process");
      passed(run(second, "token"), "closing one context does not kill another");
      second.close();
      check(Files.notExists(profile2), "second context removes its own profile storage");
    }
    check(Files.readString(secret).equals("host-private"), "host secret unchanged");
    check(Files.readString(input).equals("read-only"), "host staged source unchanged");
  }

  private static void argumentsAndStreams(Path parent, Path guest) throws Exception {
    try (WindowsJail jail = prepare(parent, guest)) {
      List<String> values = List.of("", "plain", "two words", "quote\"inside", "trailing\\", "space trail \\", "Zażółć 🐍");
      List<String> command = new ArrayList<>();
      command.add(jail.applicationDirectory().resolve("guest.exe").toString());
      command.add("args");
      command.addAll(values);
      Result args = finish(jail.spawn(command, Map.of(), null, false, false, 0, 0), new byte[0]);
      passed(args, "argument quoting");
      String expected = values.stream().map(WindowsJailProbe::encoded).reduce("", String::concat) + "PASS\n";
      check(args.out().equals(expected), "UTF-16 arguments, empty strings, quotes and trailing backslashes roundtrip");
      Path nested = Files.createDirectory(jail.workDirectory().resolve("nested"));
      Result token = run(jail, "token");
      passed(token, "environment token");
      Path data = privateAppData(jail, profilePath(guest, field(token.out(), "SID")));
      command = List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "environment",
          nested.toString(), data.resolve("Temp").toString(), data.toString());
      passed(finish(jail.spawn(command, Map.of("VIS_JAIL_TEST", "expected", "TEMP", parent.toString(),
          "TMP", parent.toString(), "sYsTeMrOoT", parent.toString(), "localappdata", parent.toString()),
          "nested", false, false, 0, 0), new byte[0]), "environment, forced private values and cwd");
      byte[] input = new byte[262144];
      Arrays.fill(input, (byte) 'i');
      command = List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "streams");
      Result split = finish(jail.spawn(command, Map.of(), null, false, false, 0, 0), input);
      check(split.exit == 0, "split streams guest succeeded: " + split.err());
      check(split.output.length == input.length && split.error.length == input.length, "stream backpressure, exact counts and EOF");
      for (byte value : split.output) if (value != 'o') throw new AssertionError("stdout bytes corrupted");
      for (byte value : split.error) if (value != 'e') throw new AssertionError("stderr bytes corrupted");
      Result merged = finish(jail.spawn(command, Map.of(), null, false, true, 0, 0), input);
      check(merged.exit == 0 && merged.error.length == 0 && merged.output.length == input.length * 2,
          "merged stderr has one complete output stream");
    }
  }

  private static void network(Path parent, Path guest) throws Exception {
    var ipv4 = java.net.InetAddress.getByName("127.0.0.1");
    var ipv6 = java.net.InetAddress.getByName("::1");
    try (var tcp4 = new java.net.ServerSocket(0, 8, ipv4);
         var tcp6 = new java.net.ServerSocket(tcp4.getLocalPort(), 8, ipv6);
         var udp4 = new java.net.DatagramSocket(new java.net.InetSocketAddress(ipv4, tcp4.getLocalPort()));
         var udp6 = new java.net.DatagramSocket(new java.net.InetSocketAddress(ipv6, tcp4.getLocalPort()));
         WindowsJail jail = prepare(parent, guest)) {
      // Positive controls prove both families and transports reach the host receivers.
      for (var server : List.of(tcp4, tcp6)) {
        server.setSoTimeout(2000);
        try (var client = new java.net.Socket()) {
          client.connect(new java.net.InetSocketAddress(server.getInetAddress(), server.getLocalPort()), 2000);
          client.getOutputStream().write(0x63);
          try (var accepted = server.accept()) {
            accepted.setSoTimeout(2000);
            check(accepted.getInputStream().read() == 0x63, "host TCP control reaches listener");
          }
        }
      }
      for (var socket : List.of(udp4, udp6)) {
        socket.setSoTimeout(2000);
        try (var client = new java.net.DatagramSocket(new java.net.InetSocketAddress(socket.getLocalAddress(), 0))) {
          client.send(new java.net.DatagramPacket(new byte[] {0x63}, 1, socket.getLocalSocketAddress()));
          var packet = new java.net.DatagramPacket(new byte[16], 16);
          socket.receive(packet);
          check(packet.getLength() == 1 && packet.getData()[0] == 0x63, "host UDP control reaches receiver");
        }
      }
      passed(run(jail, "network", Integer.toString(tcp4.getLocalPort())), "TCP UDP IPv4 IPv6 network denied");
      for (var server : List.of(tcp4, tcp6)) {
        server.setSoTimeout(200);
        try (var unexpected = server.accept()) {
          throw new AssertionError("network-off child reached host listener: " + unexpected.getRemoteSocketAddress());
        } catch (java.net.SocketTimeoutException expected) { checks++; }
      }
      for (var socket : List.of(udp4, udp6)) {
        socket.setSoTimeout(200);
        try {
          socket.receive(new java.net.DatagramPacket(new byte[64], 64));
          throw new AssertionError("network-off guest delivered UDP to host");
        } catch (java.net.SocketTimeoutException expected) { checks++; }
      }
    }
  }

  private static long childPid(Process process) throws Exception {
    CompletableFuture<Long> answer = new CompletableFuture<>();
    Thread.ofPlatform().daemon().start(() -> {
      try {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("CHILD=")) { answer.complete(Long.parseLong(line.substring(6))); return; }
        }
        throw new IOException("guest exited without descendant PID");
      } catch (Throwable failure) { answer.completeExceptionally(failure); }
    });
    return answer.get(10, TimeUnit.SECONDS);
  }

  private static void gone(long pid) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) { checks++; return; }
      Thread.sleep(20);
    }
    throw new AssertionError("descendant survived job teardown: " + pid);
  }

  private static int handleCount(Path guest) throws Exception {
    Result result = finish(new ProcessBuilder(guest.toString(), "handles", Long.toString(ProcessHandle.current().pid())).start(), new byte[0]);
    passed(result, "host handle counter");
    return Integer.parseInt(field(result.out(), "HANDLES"));
  }

  @SuppressWarnings("try") // Context close must kill a live process before leaving this scope.
  private static void lifetime(Path parent, Path guest) throws Exception {
    try (WindowsJail jail = prepare(parent, guest)) {
      passed(run(jail, "token"), "lifetime warmup");
      int before = handleCount(guest);
      for (int attempt = 0; attempt < 64; attempt++) {
        Process process = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "sleep"),
            Map.of(), null, false, false, 0, 0);
        check(process.pid() > 0 && process.toHandle().pid() == process.pid(), "real Windows PID");
        process.destroyForcibly();
        check(process.waitFor(5, TimeUnit.SECONDS), "immediate job kill completed");
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
      }
      int after = handleCount(guest);
      check(after <= before + 24, "native handle leak after 64 launches: before=" + before + " after=" + after);
      Process tree = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "tree"),
          Map.of(), null, false, false, 0, 0);
      long descendant = childPid(tree);
      jail.close();
      check(tree.waitFor(5, TimeUnit.SECONDS), "context close kills primary process");
      gone(descendant);
      tree.getOutputStream().close();
      tree.getInputStream().close();
      tree.getErrorStream().close();
    }
  }

  private static void terminal(Path parent, Path guest) throws Exception {
    try (WindowsJail jail = prepare(parent, guest)) {
      Process process = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "pty"),
          Map.of(), null, true, true, 31, 97);
      Result result = finish(process, "ping\r\n".getBytes(StandardCharsets.UTF_8));
      passed(result, "ConPTY dimensions, roundtrip and EOF");
      check(result.out().contains("PTY=31x97"), "ConPTY expected dimensions printed");
      for (int attempt = 0; attempt < 8; attempt++) {
        Process sleeping = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "sleep"),
            Map.of(), null, true, true, 31, 97);
        sleeping.destroyForcibly();
        check(sleeping.waitFor(5, TimeUnit.SECONDS), "ConPTY immediate kill");
        sleeping.getOutputStream().close();
        sleeping.getInputStream().close();
        sleeping.getErrorStream().close();
      }
    }
  }

  private static CompletableFuture<Void> background(Checked action) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    Thread.ofPlatform().daemon().start(() -> {
      try { action.run(); result.complete(null); }
      catch (Throwable failure) { result.completeExceptionally(failure); }
    });
    return result;
  }

  private static void terminalBackpressure(Path parent, Path guest) throws Exception {
    System.out.println("START WindowsJail ConPTY preparation");
    WindowsJail jail = prepare(parent, guest);
    System.out.println("START WindowsJail ConPTY flood spawn");
    Process process = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "pty-flood"),
        Map.of(), null, true, true, 31, 97);
    // Keep the consumer alive: PipedInputStream treats a dead reader as a broken pipe.
    CompletableFuture<Void> outputReady = new CompletableFuture<>();
    CompletableFuture<Void> releaseReader = new CompletableFuture<>();
    AtomicInteger outputBytes = new AtomicInteger();
    CompletableFuture<Void> read = background(() -> {
      try {
        byte[] block = new byte[8192];
        while (outputBytes.get() < 131072) {
          int count = process.getInputStream().read(block, 0, Math.min(block.length, 131072 - outputBytes.get()));
          if (count < 0) throw new IOException("ConPTY output ended before 128KiB");
          outputBytes.addAndGet(count);
        }
        outputReady.complete(null);
        releaseReader.get(); // Stay alive without draining until the context-close test finishes.
      } catch (Exception | AssertionError failure) {
        outputReady.completeExceptionally(failure);
        throw failure;
      }
    });
    CompletableFuture<Void> writerStarted = new CompletableFuture<>();
    CompletableFuture<Void> write = background(() -> {
      try {
        byte[] block = new byte[1048576];
        writerStarted.complete(null);
        for (int count = 0; count < 16; count++) process.getOutputStream().write(block);
      }
      catch (IOException expectedOnClose) { /* Closing a blocked writer is expected. */ }
    });
    Throwable failure = null;
    try {
      stage("ConPTY output readiness", () -> outputReady.get(10, TimeUnit.SECONDS));
      stage("ConPTY writer startup", () -> writerStarted.get(5, TimeUnit.SECONDS));
      check(!read.isDone(), "ConPTY consumer stays alive without draining at close");
      check(process.isAlive(), "flood guest remains active before context close");
      check(!write.isDone(), "ConPTY stdin is active under backpressure at close");
      stage("ConPTY context close", () -> background(jail::close).get(10, TimeUnit.SECONDS));
      stage("ConPTY process exit", () ->
          check(process.waitFor(5, TimeUnit.SECONDS), "ConPTY backpressure close kills process"));
      stage("ConPTY blocked writer release", () -> write.get(5, TimeUnit.SECONDS));
    } catch (Exception | AssertionError caught) {
      failure = caught;
      System.err.println("ConPTY output bytes=" + outputBytes.get()
          + " readerDone=" + read.isDone() + " writerDone=" + write.isDone());
      caught.printStackTrace(System.err);
      Thread.getAllStackTraces().forEach((thread, stack) -> {
        System.err.println("ConPTY thread " + thread.getName() + " " + thread.getState());
        for (StackTraceElement frame : stack) System.err.println("  at " + frame);
      });
      throw caught;
    } finally {
      releaseReader.complete(null);
      // Cleanup itself is bounded: a native lock bug must fail, not hang the runner.
      try {
        stage("ConPTY cleanup", () -> background(() -> {
          process.destroyForcibly();
          process.getInputStream().close();
          process.getOutputStream().close();
          process.getErrorStream().close();
          jail.close();
          read.get(5, TimeUnit.SECONDS);
        }).get(10, TimeUnit.SECONDS));
      } catch (Exception | AssertionError cleanup) {
        if (failure == null) throw cleanup;
        failure.addSuppressed(cleanup);
      }
    }
  }

  private static void stagingStress(Path parent, Path guest) throws Exception {
    Path deep = Files.createDirectory(parent.resolve("deep-source"));
    Path leaf = deep;
    for (int depth = 0; depth < 256; depth++) leaf = Files.createDirectory(leaf.resolve("d"));
    Files.writeString(leaf.resolve("leaf.txt"), "deep-safe");
    try (WindowsJail jail = prepare(parent, guest)) {
      try {
        Path copied = jail.stage(deep, "deep");
        check(Files.readString(copied.resolve(deep.relativize(leaf)).resolve("leaf.txt")).equals("deep-safe"),
            "deep staging copies leaf without native stack overflow");
      } catch (VisPythonException rejected) {
        check(rejected.getMessage() != null && !rejected.getMessage().isBlank(), "deep staging explicitly rejected");
      }
    }
    Path ancestor = Files.createDirectory(parent.resolve("mutable-ancestor"));
    Path source = Files.createDirectory(ancestor.resolve("source"));
    for (int index = 0; index < 128; index++) Files.writeString(source.resolve("file-" + index), "safe");
    Path outside = Files.createDirectory(parent.resolve("replacement"));
    Path outsideSource = Files.createDirectory(outside.resolve("source"));
    Files.writeString(outsideSource.resolve("forbidden.txt"), "must-not-copy");
    Path parked = parent.resolve("parked-ancestor");
    for (int attempt = 0; attempt < 8; attempt++) {
      try (WindowsJail jail = prepare(parent, guest)) {
        CompletableFuture<Void> stage = background(() -> {
          try { jail.stage(source, "snapshot"); }
          catch (VisPythonException rejected) { /* Mutation must be safely rejected or pinned. */ }
        });
        boolean moved = false;
        try {
          try { Files.move(ancestor, parked); moved = true; }
          catch (java.nio.file.FileSystemException pinned) { /* A pinned ancestor correctly prevents rename. */ }
          if (moved) {
            Files.createDirectory(ancestor);
            passed(finish(new ProcessBuilder(guest.toString(), "junction", ancestor.toString(), outside.toString()).start(),
                new byte[0]), "mutable ancestor junction fixture");
          }
          stage.get(10, TimeUnit.SECONDS);
          check(!Files.exists(jail.applicationDirectory().resolve("snapshot/forbidden.txt")),
              "staging never follows a swapped ancestor junction");
        } finally {
          if (moved) { Files.delete(ancestor); Files.move(parked, ancestor); }
        }
      }
    }
    // Deterministic ancestor-reparse case prevents a race that never wins from hiding a gap.
    Files.move(ancestor, parked);
    Files.createDirectory(ancestor);
    try {
      passed(finish(new ProcessBuilder(guest.toString(), "junction", ancestor.toString(), outside.toString()).start(),
          new byte[0]), "ancestor junction fixture");
      try (WindowsJail jail = prepare(parent, guest)) {
        denied(() -> jail.stage(source, "reparse"), "source ancestor junction accepted");
      }
    } finally { Files.delete(ancestor); Files.move(parked, ancestor); }
    System.out.println("PASS WindowsJail deep and mutable ancestor staging");
  }

  private static void crashChild(Path parent, Path guest) throws Exception {
    try (WindowsJail jail = prepare(parent, guest)) {
      Result token = run(jail, "token");
      passed(token, "crash fixture token");
      Process process = jail.spawn(List.of(jail.applicationDirectory().resolve("guest.exe").toString(), "tree"),
          Map.of(), null, false, false, 0, 0);
      long descendant = childPid(process);
      Files.writeString(parent.resolve("crash-profile-sid"), field(token.out(), "SID"));
      System.out.println("PRIMARY=" + process.pid());
      System.out.println("DESCENDANT=" + descendant);
      System.out.flush();
      Runtime.getRuntime().halt(0);
    }
  }

  private static List<String> self(String mode, Path parent, Path guest) {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElseThrow());
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
      command.addAll(List.of("--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
          WindowsJailProbe.class.getName()));
    }
    command.addAll(List.of(mode, guest.toString(), parent.toString()));
    return command;
  }

  private static void inheritedChild(Path parent, Path guest) throws Exception {
    String handle = System.getenv("VIS_JAIL_LEAK_HANDLE");
    check(handle != null, "host injected inheritable handle identity");
    passed(finish(new ProcessBuilder(guest.toString(), "host-handle", Long.toString(ProcessHandle.current().pid()), handle).start(),
        new byte[0]), "the launcher really holds the inheritable secret");
    try (WindowsJail jail = prepare(parent, guest)) {
      passed(run(jail, "secret-handle", handle), "explicit handle inheritance prevents secret leak");
    }
    System.out.println("PASS inherited handle isolation");
  }

  private static String registryLine(InputStream stream) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int count = 0; count < 1024; count++) {
      int value = stream.read();
      if (value < 0) throw new IOException("registry fixture host exited before readiness");
      if (value == '\n') return line.toString();
      if (value != '\r') line.append((char) value);
    }
    throw new IOException("registry fixture metadata exceeded its bound");
  }

  private static void registry(Path parent, Path guest, boolean machine) throws Exception {
    Process host = new ProcessBuilder(guest.toString(), "registry-host", machine ? "machine" : "user").start();
    Throwable failure = null;
    CompletableFuture<List<String>> ready = new CompletableFuture<>();
    // Hand stdout to the normal drainer only after the metadata reader has finished,
    // including readiness failures; two readers must never race on the same pipe.
    CompletableFuture<byte[]> output = ready.handle((paths, failed) -> drain(host.getInputStream()))
        .thenCompose(result -> result);
    try {
      Thread.ofPlatform().daemon().start(() -> {
        try {
          List<String> paths = new ArrayList<>();
          for (int i = 0; i < (machine ? 3 : 2); i++) {
            String line = registryLine(host.getInputStream());
            if (!line.startsWith("REGISTRY_PATH=")) throw new IOException("invalid registry fixture metadata");
            paths.add(line.substring("REGISTRY_PATH=".length()));
          }
          if (!registryLine(host.getInputStream()).equals("REGISTRY_READY"))
            throw new IOException("registry fixture readiness missing");
          ready.complete(paths);
        } catch (Throwable caught) { ready.completeExceptionally(caught); }
      });
      List<String> command = new ArrayList<>(List.of("registry"));
      command.addAll(ready.get(10, TimeUnit.SECONDS));
      try (WindowsJail jail = prepare(parent, guest)) {
        passed(run(jail, command.toArray(String[]::new)), "real registry capability and private-host isolation");
      }
    } catch (Exception | AssertionError caught) {
      failure = caught;
      throw caught;
    } finally {
      try {
        passed(finish(host, new byte[] {'q'}, output), "host verifies and removes its exact registry fixtures");
      } catch (Exception | AssertionError cleanup) {
        if (failure == null) throw cleanup;
        failure.addSuppressed(cleanup);
      }
    }
  }

  private static void standardChild(Path parent, Path guest) throws Exception {
    passed(finish(new ProcessBuilder(guest.toString(), "standard-token").start(), new byte[0]), "standard host token");
    try (WindowsJail jail = prepare(parent, guest)) {
      passed(run(jail, "token"), "standard user creates real LPAC jail");
    }
    registry(parent, guest, false);
    check(System.getenv("VIS_JAIL_SECRET") != null, "standard parent has host-only environment fixture");
    argumentsAndStreams(parent, guest);
    System.out.println("PASS standard-user jail");
  }

  private static void restrictedHosts(Path parent, Path guest) throws Exception {
    Path secret = Files.writeString(parent.resolve("handle-secret.txt"), "inheritable-secret");
    List<String> leak = new ArrayList<>(List.of(guest.toString(), "leak-host", secret.toString()));
    leak.addAll(self("--inherited-child", parent, guest));
    passed(finish(new ProcessBuilder(leak).start(), new byte[0]), "inherited ambient handle test host");
    // Exercise the primary token before Java needs to create its own default-security pipes.
    passed(finish(new ProcessBuilder(guest.toString(), "standard-host", guest.toString(), "standard-token").start(),
        new byte[0]), "standard host token and Win32 pipe control");
    List<String> standard = new ArrayList<>(List.of(guest.toString(), "standard-host"));
    standard.addAll(self("--standard-child", parent, guest));
    passed(finish(new ProcessBuilder(standard).start(), new byte[0]), "standard user host without elevation");
  }

  private static void parentCrash(Path parent, Path guest) throws Exception {
    Path recordedProfile = parent.resolve("crash-profile-sid");
    try {
      List<String> command = self("--crash-child", parent, guest);
      Result result = finish(new ProcessBuilder(command).start(), new byte[0]);
      check(result.exit == 0, "crash helper exited: " + result.err());
      gone(Long.parseLong(field(result.out(), "PRIMARY")));
      gone(Long.parseLong(field(result.out(), "DESCENDANT")));
      check(Files.isRegularFile(recordedProfile), "crash fixture records its exact owned profile SID");
    } finally {
      if (Files.exists(recordedProfile)) {
        String sid = Files.readString(recordedProfile);
        Path profile = profilePath(guest, sid);
        passed(finish(new ProcessBuilder(guest.toString(), "profile-delete", sid).start(), new byte[0]),
            "remove only the recorded crash fixture profile");
        check(Files.notExists(profile), "crash fixture profile storage cleaned up");
        Files.delete(recordedProfile);
      }
    }
  }

  private static void nativeWorker(WindowsJail jail, Path staged) throws Exception {
    Path socket = jail.workDirectory().resolve("c.sock");
    try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
      server.bind(java.net.UnixDomainSocketAddress.of(socket));
      server.configureBlocking(false);
      Process process = jail.spawn(List.of(staged.resolve("vis-python-worker.exe").toString(),
          "-Duser.home=" + jail.workDirectory(), socket.toString()),
          Map.of("VIS_PYTHON_NATIVE_PATH", staged.toString()),
          null, false, false, 0, 0);
      CompletableFuture<byte[]> out = drain(process.getInputStream());
      CompletableFuture<byte[]> err = drain(process.getErrorStream());
      java.nio.channels.SocketChannel accepted = null;
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      try {
        while (accepted == null && process.isAlive() && System.nanoTime() < deadline) {
          accepted = server.accept();
          if (accepted == null) Thread.sleep(20);
        }
        if (accepted == null) {
          check(!process.isAlive(), "native worker control connection watchdog expired");
          String error = new String(err.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
          check(process.exitValue() != 0 && error.contains("UnixDomainSockets.connect")
              && (error.contains("Access is denied") || error.contains("Permission denied")),
              "native worker failed for a reason other than explicit AF_UNIX denial: " + error);
          System.out.println("UNSUPPORTED WindowsJail native worker: Windows denied AF_UNIX control connection; no exemption added");
          return;
        }
        try (var channel = accepted) {
          CompletableFuture<Void> exchange = new CompletableFuture<>();
          Thread.ofPlatform().daemon().start(() -> {
            try {
              var reader = new java.io.BufferedReader(java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8));
              var writer = new java.io.BufferedWriter(java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8));
              for (Map<String, Object> request : List.of(
                  Map.<String, Object>of("id", 1, "op", "install-runtime", "session", "jail-probe"),
                  Map.<String, Object>of("id", 2, "op", "run", "session", "jail-probe", "code", "6 * 7"))) {
                writer.write(Json.write(request));
                writer.write('\n');
                writer.flush();
                String line = reader.readLine();
                check(line != null && line.length() < LIMIT, "native worker bounded reply");
                Map<String, Object> response = Json.object(line);
                check(!response.containsKey("error"), "native worker request failed: " + response);
                check(((Number) response.get("id")).intValue() == ((Number) request.get("id")).intValue(),
                    "native worker response ID");
                if (request.get("op").equals("run")) check(response.get("value").equals("42"), "native worker executed Python");
              }
              exchange.complete(null);
            } catch (Throwable failure) { exchange.completeExceptionally(failure); }
          });
          exchange.get(20, TimeUnit.SECONDS);
        }
        check(process.waitFor(10, TimeUnit.SECONDS), "native worker exits on control disconnect");
        check(process.exitValue() == 0, "native worker exit code");
        System.out.println("PASS WindowsJail native worker AF_UNIX protocol");
      } finally {
        if (accepted != null) accepted.close();
        process.destroyForcibly();
        process.getOutputStream().close();
        out.get(5, TimeUnit.SECONDS);
        err.get(5, TimeUnit.SECONDS);
      }
    } finally { Files.deleteIfExists(socket); }
  }

  private static void python(Path parent, Path guest, Path nativeDirectory) throws Exception {
    try (WindowsJail jail = prepare(parent, guest)) {
      Path staged = jail.stage(nativeDirectory, "runtime");
      Path executable = staged.resolve("python/python.exe");
      Result result = finish(jail.spawn(List.of(executable.toString(), "-I", "-B", "-c",
          "from pathlib import Path; import os; p=Path('result.txt'); p.write_text('42'); print(p.read_text()); print(os.environ['TEMP'])"),
          Map.of(), null, false, false, 0, 0), new byte[0]);
      check(result.exit == 0 && result.out().contains("42"), "staged stock CPython executes: " + result.err());
      check(Files.readString(jail.workDirectory().resolve("result.txt")).equals("42"), "real Python output retained");
      nativeWorker(jail, staged);
    }
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length == 3 && arguments[0].equals("--staging-child")) {
      stagingStress(Path.of(arguments[2]), Path.of(arguments[1]));
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--terminal-roundtrip-child")) {
      terminal(Path.of(arguments[2]), Path.of(arguments[1]));
      System.out.println("PASS WindowsJail ConPTY with redirected host handles");
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--terminal-child")) {
      terminalBackpressure(Path.of(arguments[2]), Path.of(arguments[1]));
      System.out.println("PASS WindowsJail active ConPTY close");
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--inherited-child")) {
      inheritedChild(Path.of(arguments[2]), Path.of(arguments[1]));
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--standard-child")) {
      standardChild(Path.of(arguments[2]), Path.of(arguments[1]));
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--crash-child")) {
      crashChild(Path.of(arguments[2]), Path.of(arguments[1]));
      return;
    }
    if (arguments.length < 1 || arguments.length > 2) {
      throw new IllegalArgumentException("usage: WindowsJailProbe <windows-jail-guest.exe> [native-directory]");
    }
    check(System.getProperty("os.name").startsWith("Windows"), "Windows E2E must run on Windows");
    Path guest = Path.of(arguments[0]).toRealPath();
    Path nativeDirectory = arguments.length == 2 ? Path.of(arguments[1]).toRealPath() : null;
    Path parent = Files.createTempDirectory("vj-").toRealPath();
    Throwable failure = null;
    try {
      // Report the first failure before a later native wait can hide its diagnostics.
      if (nativeDirectory != null) {
        stage("runtime selection", () -> check(Files.isSameFile(nativeDirectory.resolve(Native.libraryName()),
            Path.of(Native.library().path())), "launcher uses the requested external runtime"));
      }
      stage("validation", () -> validation(parent, guest));
      stage("staging stress", () -> passed(
          finish(new ProcessBuilder(self("--staging-child", parent, guest)).start(), new byte[0]), "bounded staging stress"));
      stage("registry", () -> registry(parent, guest, true));
      stage("filesystem", () -> filesystem(parent, guest));
      stage("streams", () -> argumentsAndStreams(parent, guest));
      stage("network", () -> network(parent, guest));
      stage("lifetime", () -> lifetime(parent, guest));
      // microsoft/terminal#11276: force redirected parent handles even outside CI.
      stage("terminal", () -> passed(
          finish(new ProcessBuilder(self("--terminal-roundtrip-child", parent, guest)).start(), new byte[0]),
          "ConPTY with redirected host stdio"));
      for (int attempt = 0; attempt < 4; attempt++) {
        stage("active ConPTY close " + (attempt + 1), () -> passed(
            finish(new ProcessBuilder(self("--terminal-child", parent, guest)).start(), new byte[0]), "bounded active ConPTY close"));
      }
      stage("parent crash", () -> parentCrash(parent, guest));
      stage("inherited handles and standard user", () -> restrictedHosts(parent, guest));
      if (nativeDirectory != null) {
        stage("stock Python and native worker", () -> python(parent, guest, nativeDirectory));
      }
    } catch (Throwable caught) {
      failure = caught;
      throw caught;
    } finally {
      try (var paths = Files.walk(parent)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      } catch (IOException cleanup) {
        if (failure == null) throw cleanup;
        failure.addSuppressed(cleanup);
      }
    }
    System.out.println("PASS WindowsJail checks=" + checks);
  }
}
