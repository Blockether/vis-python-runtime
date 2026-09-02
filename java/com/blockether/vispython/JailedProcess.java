package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** A {@link Process} backed by the descriptors and pid returned by libvisjail. */
public final class JailedProcess extends Process {
  private static final int SIGTERM = 15;
  private static final int SIGKILL = 9;
  private final int pid;
  private final NativeOutputStream stdin;
  private final InputStream stdout;
  private final InputStream stderr;
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private final AtomicBoolean masterOpen;
  JailedProcess(int pid, int input, int output, int error, boolean pty) {
    this.pid = pid;
    if (pty) {
      masterOpen = new AtomicBoolean(true);
      stdin = new NativeOutputStream(input, masterOpen, false);
      java.io.PipedInputStream pipe;
      try {
        pipe = new java.io.PipedInputStream(64 * 1024);
        java.io.PipedOutputStream sink = new java.io.PipedOutputStream(pipe);
        stdout = pipe;
        stderr = InputStream.nullInputStream();
        startPtyReader(output, sink);
      } catch (IOException exception) {
        Jail.close(input);
        throw new VisPythonException("Could not create process output pipe",
            Map.of("pid", pid), exception);
      }
    } else {
      masterOpen = null;
      stdin = new NativeOutputStream(input, new AtomicBoolean(true), true);
      stdout = new NativeInputStream(output);
      stderr = error < 0 ? InputStream.nullInputStream() : new NativeInputStream(error);
    }
    Thread.ofPlatform().daemon().name("visjail-reap-" + pid).start(this::reap);
  }

  private void reap() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment code = arena.allocate(Integer.BYTES);
      int status = Jail.waitFor(pid, false, code);
      if (status == 1) {
        exit.complete(code.get(ValueLayout.JAVA_INT, 0));
      } else {
        exit.completeExceptionally(new IOException("waitpid failed: " + status));
      }
    } catch (Throwable throwable) {
      exit.completeExceptionally(throwable);
    }
  }

  private void startPtyReader(int fd, java.io.PipedOutputStream sink) {
    Thread.ofPlatform().daemon().name("visjail-pty-read-" + pid).start(() -> {
      byte[] buffer = new byte[8192];
      try (sink) {
        while (true) {
          int ready = Jail.poll(fd, 10);
          if (ready > 0) {
            int count = Jail.read(fd, buffer);
            if (count <= 0) break;
            sink.write(buffer, 0, count);
            sink.flush();
          } else if (exit.isDone()) {
            int count = Jail.read(fd, buffer);
            if (count <= 0) break;
            sink.write(buffer, 0, count);
          }
        }
      } catch (Throwable ignored) {
        // Closing the process or its consumer races the blocking native descriptor by design.
      } finally {
        if (masterOpen.compareAndSet(true, false)) Jail.close(fd);
      }
    });
  }

  @Override public OutputStream getOutputStream() { return stdin; }
  @Override public InputStream getInputStream() { return stdout; }
  @Override public InputStream getErrorStream() { return stderr; }

  @Override
  public int waitFor() throws InterruptedException {
    try {
      return exit.get();
    } catch (java.util.concurrent.ExecutionException exception) {
      throw failure(exception.getCause());
    }
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    try {
      exit.get(timeout, unit);
      return true;
    } catch (TimeoutException exception) {
      return false;
    } catch (java.util.concurrent.ExecutionException exception) {
      throw failure(exception.getCause());
    }
  }

  @Override
  public int exitValue() {
    if (!exit.isDone()) throw new IllegalThreadStateException("process has not exited");
    try {
      return exit.join();
    } catch (java.util.concurrent.CompletionException exception) {
      throw failure(exception.getCause());
    }
  }

  @Override public boolean isAlive() { return !exit.isDone(); }
  @Override public long pid() { return pid; }
  @Override public ProcessHandle toHandle() {
    return ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("no process " + pid));
  }
  @Override public void destroy() { if (!exit.isDone()) Jail.kill(pid, SIGTERM); }
  @Override public Process destroyForcibly() { if (!exit.isDone()) Jail.kill(pid, SIGKILL); return this; }
  @Override public boolean supportsNormalTermination() { return true; }

  private static RuntimeException failure(Throwable cause) {
    return new VisPythonException("Confined process wait failed: " + cause,
        Map.of(), cause);
  }

  private static final class NativeInputStream extends InputStream {
    private final int fd;
    private final AtomicBoolean open = new AtomicBoolean(true);
    NativeInputStream(int fd) { this.fd = fd; }
    @Override public int read() throws IOException {
      byte[] one = new byte[1];
      int count = read(one, 0, 1);
      return count < 0 ? -1 : one[0] & 0xff;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      if (!open.get()) throw new IOException("stream is closed");
      if (length == 0) return 0;
      byte[] buffer = offset == 0 && length == bytes.length ? bytes : new byte[length];
      int count = Jail.read(fd, buffer);
      if (count < 0) throw new IOException("native read failed: " + count);
      if (count == 0) return -1;
      if (buffer != bytes) System.arraycopy(buffer, 0, bytes, offset, count);
      return count;
    }
    @Override public void close() {
      if (open.compareAndSet(true, false)) Jail.close(fd);
    }
  }

  private static final class NativeOutputStream extends OutputStream {
    private final int fd;
    private final AtomicBoolean open;
    private final boolean closes;
    NativeOutputStream(int fd, AtomicBoolean open, boolean closes) {
      this.fd = fd; this.open = open; this.closes = closes;
    }
    @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      if (!open.get()) throw new IOException("stream is closed");
      int at = 0;
      while (at < length) {
        int count = Jail.write(fd, bytes, offset + at, length - at);
        if (count <= 0) throw new IOException("native write failed: " + count);
        at += count;
      }
    }
    @Override public void close() {
      if (closes && open.compareAndSet(true, false)) Jail.close(fd);
    }
  }
}
