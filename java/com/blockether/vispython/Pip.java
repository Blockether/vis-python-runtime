package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Installing packages: the only way the sandbox ever gets one.
 *
 * <p>The artifact BUNDLES NOTHING. It is an interpreter, its standard library
 * and pip; every real distribution arrives here, into one directory beside the
 * bytecode cache, and every session imports from it. So a shipped tree is the
 * same bytes on every machine, a package the user chose survives the next
 * release, and there is no requirements file anybody has to re-decide.
 *
 * <p>Two things make this the HOST's job and never a block's. pip runs in a
 * process of its own - the embedded interpreter is confined, and being one
 * interpreter for the whole process it would carry an installer's imports and
 * monkeypatches into every session after it. And installing reaches an index,
 * which is precisely what a sandbox is for refusing: a block that could install
 * could write its own next payload.
 *
 * <p>Trust comes from the JVM. pip would otherwise verify TLS against the CA
 * bundle vendored inside it, so a machine whose operator added a corporate root
 * to the Java trust store - the only store this product's own HTTP client reads
 * - would have a runtime that trusts two different sets of certificates and
 * fails on one of them. {@link #certificatesPem} exports what the JVM trusts and
 * pip is pointed at that file, so there is one trust decision on the machine.
 */
public final class Pip {

  private Pip() {}

  /** What an install did: pip's own verdict, its output, and the argv that ran. */
  public record Result(int exit, String out, List<String> command) {}

  /**
   * Every certificate the JVM trusts, from the DEFAULT trust manager - so a root
   * an operator added to {@code cacerts}, or pointed at with
   * {@code javax.net.ssl.trustStore}, is included exactly as it is for the rest
   * of the process.
   */
  public static List<X509Certificate> trustAnchors() {
    try {
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      // A null KeyStore is what asks for the process's default trust store.
      factory.init((KeyStore) null);
      List<X509Certificate> anchors = new ArrayList<>();
      for (TrustManager manager : factory.getTrustManagers()) {
        if (manager instanceof X509TrustManager x509) {
          anchors.addAll(List.of(x509.getAcceptedIssuers()));
        }
      }
      return List.copyOf(anchors);
    } catch (GeneralSecurityException e) {
      throw new VisPythonException("could not read the JVM trust store", Map.of(), e);
    }
  }

  private static String pem(X509Certificate certificate) {
    try {
      Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
      return "-----BEGIN CERTIFICATE-----\n"
          + encoder.encodeToString(certificate.getEncoded())
          + "\n-----END CERTIFICATE-----\n";
    } catch (CertificateEncodingException e) {
      throw new VisPythonException("could not encode a trusted certificate", Map.of(), e);
    }
  }

  /**
   * Write the JVM's trust anchors to {@code path} in PEM and answer it.
   *
   * <p>Rewritten only when the anchors changed, because the path is handed to a
   * subprocess and a file being rewritten under one is worth avoiding for
   * nothing.
   */
  public static String certificatesPem(String path) {
    StringBuilder bundle = new StringBuilder();
    for (X509Certificate certificate : trustAnchors()) {
      bundle.append(pem(certificate));
    }
    String wanted = bundle.toString();
    Path file = Path.of(path).toAbsolutePath();
    try {
      if (!Files.isRegularFile(file) || !wanted.equals(Files.readString(file))) {
        Files.createDirectories(file.getParent());
        Files.writeString(file, wanted, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return file.toString();
  }

  public static String certificatesPem() {
    return certificatesPem(Locations.certificatesFile());
  }

  /**
   * The argv that installs {@code specs} into {@code target}.
   *
   * <p>{@code --only-binary=:all:} is not a preference: an sdist runs its own
   * {@code setup.py} at install time, on the host, outside every boundary this
   * project has. A wheel is data that gets unpacked.
   */
  public static List<String> installCommand(String python, String target, String cert,
      boolean upgrade, List<String> specs) {
    List<String> command = new ArrayList<>(List.of(python, "-m", "pip", "install",
        "--target", target, "--only-binary=:all:", "--disable-pip-version-check", "--no-input"));
    if (cert != null) {
      command.add("--cert");
      command.add(cert);
    }
    if (upgrade) {
      command.add("--upgrade");
    }
    command.addAll(specs);
    return List.copyOf(command);
  }

  /**
   * Install {@code specs} for the sandbox. A non-zero exit comes back as data
   * with pip's own output, because the caller is a CLI that has to print it.
   *
   * <p>Nulls take the runtime's own answers: the vendored interpreter, the
   * packages directory, the bytecode cache prefix, and the JVM's certificates
   * exported to a file.
   */
  public static Result install(String python, String target, String cert, String pycachePrefix,
      boolean upgrade, long timeoutMs, List<String> specs) {
    String interpreter = python != null ? python
        : Locations.pythonExecutable(Locations.pythonHome(Interpreter.library().path()));
    String directory = target != null ? target : Locations.packagesDir();
    String certificates = cert != null ? cert : certificatesPem();
    String cache = pycachePrefix != null ? pycachePrefix : Locations.pycachePrefix();
    if (interpreter == null) {
      throw new VisPythonException("no interpreter to run pip with",
          Map.of("target", String.valueOf(directory)));
    }
    if (directory == null) {
      throw new VisPythonException("no directory to install into",
          Map.of("python", interpreter));
    }
    List<String> command = installCommand(interpreter, directory, certificates, upgrade, specs);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    // pip reads the target as an ordinary path, so it needs the same directory
    // on sys.path to see what is already installed - and nothing of the
    // machine's own Python may leak into it.
    environment.put("PYTHONPATH", directory);
    environment.put("PYTHONNOUSERSITE", "1");
    if (certificates != null) {
      environment.put("PIP_CERT", certificates);
      environment.put("SSL_CERT_FILE", certificates);
    }
    if (cache != null) {
      environment.put("PYTHONPYCACHEPREFIX", cache);
    }
    try {
      Process process = builder.start();
      String out;
      try (InputStream in = process.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      boolean done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!done) {
        process.destroyForcibly();
        return new Result(-1, out, command);
      }
      return new Result(process.exitValue(), out, command);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new VisPythonException("interrupted waiting for pip", Map.of(), e);
    }
  }
}
