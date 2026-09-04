package com.blockether.vispython;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The process that HOLDS an interpreter for one host session: a program of its
 * own, connected to the parent over a unix socket, serving that parent's
 * requests against the embedded CPython and asking back for its host tools.
 *
 * <p>Why a process. Confinement, the thread cap and the network capability are
 * PROCESS state - one policy for everything the interpreter serves - so a host
 * that runs many sessions gives each one a worker: its own policy, its own
 * imports, and a wedged interpreter it can kill without touching anybody
 * else's work. Why a program of its own rather than the host started twice:
 * the child then carries exactly the interpreter bridge and this loop, its
 * jail has to open nothing the host is made of, and the native image it is
 * compiled into is the runtime's, built and shipped beside the cdylib.
 *
 * <p>The wire is ONE line of JSON per message, both ways. A message carrying
 * {@code op} is a request, one without is its reply, so each side numbers its
 * own requests and no id can collide. The parent asks {@code install-runtime},
 * {@code install-sync-tool}, {@code install-tool}, {@code install-module},
 * {@code exec}, {@code run}, {@code run-block}, {@code eval}, {@code confine},
 * {@code network}, {@code trust}, {@code stdin}, {@code interrupt} and
 * {@code close}, each with a {@code session} and, where one is needed, a
 * {@code code} text; a reply carries {@code value} or {@code error}. The worker
 * asks back with {@code host} - {@code session}, {@code tool}, {@code payload}
 * - because the registry that knows what a name may call lives in the parent.
 * stdout is NOT the wire: Python that prints, or a native library writing to
 * descriptor 1, would corrupt it, so the parent gives this process's stdio a
 * log file instead.
 *
 * <p>A request runs on a thread of its own, never inline in the reader: an
 * interrupt has to reach the interpreter while a block is still running, and a
 * block that calls a host tool is itself waiting on this same socket. When the
 * parent hangs up every host call still waiting fails at once, and the process
 * leaves.
 */
public final class Worker {

  /** The executable name a platform archive ships this program under. */
  public static final String EXECUTABLE = "vis-python-worker";

  private Worker() {}

  /** {@code vis-python-worker <control-socket>}, or {@code --version}. */
  public static void main(String[] args) {
    if (args.length == 1 && "--version".equals(args[0])) {
      System.out.println(Native.version());
      return;
    }
    if (args.length != 1 || args[0].isBlank()) {
      System.err.println("usage: " + EXECUTABLE + " <control-socket>");
      System.exit(2);
    }
    try {
      serve(Path.of(args[0]));
      System.exit(0);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Connect back to the parent listening on {@code socket}, start the
   * interpreter, and serve until the parent hangs up. The connection is made
   * FIRST, so a parent waiting on its accept learns at once that the process
   * came up; a start that then fails closes it, and the parent reads why in the
   * log it gave this process.
   */
  public static void serve(Path socket) throws IOException {
    try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
      Peer peer = new Peer(channel);
      Interpreter.initialize(List.of(), Interpreter.DEFAULT, Interpreter.DEFAULT,
          Interpreter.DEFAULT);
      // The caller is the interpreter's answer, forwarded whole: the parent
      // authorizes against it, and a payload naming something else is the
      // guest's word, not the interpreter's.
      Interpreter.bindHost((session, tool, payload) -> {
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("op", "host");
        ask.put("session", session);
        ask.put("tool", tool);
        ask.put("payload", payload);
        return peer.request(ask);
      });
      peer.pump();
    }
  }

  /** One request from the parent, answered against this process's interpreter. */
  static Object serve(Map<String, Object> message) {
    String op = String.valueOf(message.get("op"));
    String session = text(message.get("session"));
    String code = text(message.get("code"));
    return switch (op) {
      case "install-runtime" -> Interpreter.installRuntime(session);
      case "install-sync-tool" -> Interpreter.installSyncTool(session, code);
      case "install-tool" -> Interpreter.installTool(session, code);
      case "install-module" -> Interpreter.installModule(session, code);
      case "exec" -> {
        Interpreter.exec(session, code);
        yield null;
      }
      case "run" -> Interpreter.run(session, code);
      case "run-block" -> Interpreter.runBlock(session, code);
      case "eval" -> Interpreter.eval(session, code);
      // Policy is the PROCESS's, and this process is one session's, which is
      // the whole reason the worker exists.
      case "confine" -> {
        Map<String, Object> policy = Json.object(code);
        Interpreter.confine(strings(policy.get("read")), strings(policy.get("write")),
            text(policy.get("refusal")));
        yield null;
      }
      case "network" -> {
        Map<String, Object> policy = Json.object(code);
        Interpreter.network(Boolean.TRUE.equals(policy.get("enabled")),
            text(policy.get("refusal")));
        yield null;
      }
      case "trust" -> {
        Interpreter.trust(session, "1".equals(code));
        yield null;
      }
      case "stdin" -> {
        Interpreter.stdin(code);
        yield null;
      }
      case "interrupt" -> Interpreter.interrupt();
      case "close" -> {
        Interpreter.trust(session, false);
        yield Interpreter.closeSession(session);
      }
      default -> throw new VisPythonException("no worker op named " + op, Map.of("op", op));
    };
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> items)) {
      return List.of();
    }
    return items.stream().map(String::valueOf).toList();
  }

  /** The parent, over one live connection: what to read, what to write, who waits. */
  private static final class Peer {
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending =
        new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService requests = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "vis-python-worker");
      thread.setDaemon(true);
      return thread;
    });

    Peer(SocketChannel channel) {
      reader = new BufferedReader(
          new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
      writer = new BufferedWriter(
          new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
    }

    /** Write one message. A reply and a fresh request may race; a torn line is unparseable. */
    void send(Map<String, Object> message) {
      String line = Json.write(message);
      synchronized (writer) {
        try {
          writer.write(line);
          writer.newLine();
          writer.flush();
        } catch (IOException e) {
          throw new VisPythonException("the vis process that owns this worker is gone",
              Map.of("op", text(message.get("op"))), e);
        }
      }
    }

    /** Ask the parent and answer its reply value; its error throws here. Work has no timeout. */
    String request(Map<String, Object> message) {
      long id = sequence.incrementAndGet();
      CompletableFuture<Map<String, Object>> waiting = new CompletableFuture<>();
      pending.put(id, waiting);
      try {
        message.put("id", id);
        send(message);
        Map<String, Object> reply = waiting.join();
        if (reply.containsKey("error")) {
          throw new VisPythonException(text(reply.get("error")),
              Map.of("op", text(message.get("op"))));
        }
        return text(reply.get("value"));
      } finally {
        pending.remove(id);
      }
    }

    private void answer(Map<String, Object> message) {
      Map<String, Object> reply = new LinkedHashMap<>();
      reply.put("id", message.get("id"));
      try {
        reply.put("value", Worker.serve(message));
      } catch (Throwable t) {
        String reason = t.getMessage() == null || t.getMessage().isEmpty()
            ? t.toString() : t.getMessage();
        reply.put("error", reason);
      }
      send(reply);
    }

    /**
     * Read the parent until it closes: a reply settles whoever waits for it, a
     * request goes to a thread of its own. Afterwards every pending host call
     * fails, because a guest parked on a parent that is gone would wait forever.
     */
    void pump() throws IOException {
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          Map<String, Object> message = Json.object(line);
          if (message.containsKey("op")) {
            requests.submit(() -> answer(message));
          } else if (message.get("id") instanceof Number id) {
            CompletableFuture<Map<String, Object>> waiting = pending.get(id.longValue());
            if (waiting != null) {
              waiting.complete(message);
            }
          }
        }
      } finally {
        for (CompletableFuture<Map<String, Object>> waiting : pending.values()) {
          waiting.complete(Map.of("error", "the vis process that owns this worker is gone"));
        }
        requests.shutdownNow();
      }
    }
  }
}
