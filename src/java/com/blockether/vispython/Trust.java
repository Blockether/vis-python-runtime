package com.blockether.vispython;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Host trust shared by HTTPS and pip. Session interception CAs must never be installed here.
 * The host chooses the additional PEM; defaults remain trusted. Installation belongs at startup,
 * before HTTP clients are created. Export uses exactly the manager installed by this class.
 */
public final class Trust {
  private Trust() {}
  private record Installed(SSLContext context, X509TrustManager manager) {}
  private static volatile Installed installed;

  private static X509TrustManager manager(KeyStore store) throws GeneralSecurityException {
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    for (TrustManager candidate : factory.getTrustManagers()) {
      if (candidate instanceof X509TrustManager x509) return x509;
    }
    throw new GeneralSecurityException("No X.509 trust manager is available");
  }

  public static X509TrustManager managerForPem(String path) {
    try (InputStream input = Files.newInputStream(Path.of(path))) {
      var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
      if (certificates.isEmpty()) throw new CertificateException("System CA bundle contains no certificates");
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int index = 0;
      for (var certificate : certificates) store.setCertificateEntry("host-ca-" + index++, certificate);
      X509TrustManager primary = manager(null);
      X509TrustManager additional = manager(store);
      return new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
          var anchors = new LinkedHashSet<>(List.of(primary.getAcceptedIssuers()));
          anchors.addAll(List.of(additional.getAcceptedIssuers()));
          return anchors.toArray(X509Certificate[]::new);
        }
        public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
          try { primary.checkClientTrusted(chain, type); }
          catch (CertificateException e) { additional.checkClientTrusted(chain, type); }
        }
        public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
          try { primary.checkServerTrusted(chain, type); }
          catch (CertificateException e) { additional.checkServerTrusted(chain, type); }
        }
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GeneralSecurityException e) {
      throw new VisPythonException("could not load host trust", Map.of(), e);
    }
  }

  private static SSLContext context(X509TrustManager manager) throws GeneralSecurityException {
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {manager}, null);
    return context;
  }

  public static SSLContext contextForPem(String path) {
    try { return context(managerForPem(path)); }
    catch (GeneralSecurityException e) { throw new VisPythonException("could not create host TLS context", Map.of(), e); }
  }

  public static synchronized void installPem(String path) {
    X509TrustManager manager = managerForPem(path);
    try {
      SSLContext context = context(manager);
      SSLContext.setDefault(context);
      installed = new Installed(context, manager);
    } catch (GeneralSecurityException e) {
      throw new VisPythonException("could not install host TLS context", Map.of(), e);
    }
  }

  public static List<X509Certificate> trustAnchors() {
    try {
      Installed state = installed;
      X509TrustManager effective = state != null && SSLContext.getDefault() == state.context()
          ? state.manager() : manager(null);
      return List.copyOf(new LinkedHashSet<>(List.of(effective.getAcceptedIssuers())));
    } catch (GeneralSecurityException e) {
      throw new VisPythonException("could not read host trust", Map.of(), e);
    }
  }

  /** Publish a complete bundle atomically; unchanged roots leave the file untouched. */
  public static String certificatesPem(String path) {
    StringBuilder bundle = new StringBuilder();
    try {
      var encoder = Base64.getMimeEncoder(64, new byte[] {'\n'});
      for (X509Certificate certificate : trustAnchors()) {
        bundle.append("-----BEGIN CERTIFICATE-----\n")
            .append(encoder.encodeToString(certificate.getEncoded()))
            .append("\n-----END CERTIFICATE-----\n");
      }
      Path file = Path.of(path).toAbsolutePath();
      String wanted = bundle.toString();
      if (!Files.isRegularFile(file) || !wanted.equals(Files.readString(file))) {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".ca-", ".pem");
        try {
          Files.writeString(temporary, wanted);
          Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
      }
      return file.toString();
    } catch (IOException e) { throw new UncheckedIOException(e); }
    catch (CertificateException e) { throw new VisPythonException("could not export host trust", Map.of(), e); }
  }
}
