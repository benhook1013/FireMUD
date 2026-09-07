package net.firedevops.firemud.hostedidentity.probe;

import io.fabric8.kubernetes.api.model.Secret;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Probes the derived public HTTPS and TLS-Telnet endpoints without accepting arbitrary hosts. */
@Component
public class ServedEnvironmentProbe {
  private static final Pattern PREVIEW_NUMBER = Pattern.compile("pr-([1-9][0-9]*)");
  private static final Pattern HTTP_STATUS = Pattern.compile("HTTP/[^ ]+ ([0-9]{3})(?: |$)");
  private static final Pattern PRIVATE_KEY_BLOCK =
      Pattern.compile(
          "\\A\\s*-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----\\s*\\z",
          Pattern.DOTALL);
  private static final int GRPC_PORT = 6565;
  private static final String GRPC_PROBE_SERVICE = "account-service";
  private final HostedIdentityProperties properties;

  public ServedEnvironmentProbe(HostedIdentityProperties properties) {
    this.properties = properties;
  }

  public ProbeResult probe(
      EnvironmentIdentityPlan plan,
      int telnetPort,
      String expectedIngressLeafSha256,
      String expectedTelnetLeafSha256,
      Secret grpcMaterial,
      String expectedGrpcLeafSha256) {
    ProbeResult https = https(plan.hostname(), 443, expectedIngressLeafSha256);
    if (!https.ready()) {
      return new ProbeResult(false, "https-" + https.reason());
    }
    ProbeResult telnet = telnet(plan.hostname(), telnetPort, expectedTelnetLeafSha256);
    if (!telnet.ready()) {
      return new ProbeResult(false, "telnet-" + telnet.reason());
    }
    ProbeResult grpc = grpc(plan, grpcMaterial, expectedGrpcLeafSha256);
    return grpc.ready()
        ? new ProbeResult(true, "served-and-grpc-accepted")
        : new ProbeResult(false, "grpc-" + grpc.reason());
  }

  ProbeResult probe(
      EnvironmentIdentityPlan plan,
      int telnetPort,
      EndpointProbe httpsProbe,
      EndpointProbe telnetProbe,
      EndpointProbe grpcProbe) {
    ProbeResult https = httpsProbe.check(plan.hostname(), 443);
    if (!https.ready()) {
      return new ProbeResult(false, "https-" + https.reason());
    }
    ProbeResult telnet = telnetProbe.check(plan.hostname(), telnetPort);
    if (!telnet.ready()) {
      return new ProbeResult(false, "telnet-" + telnet.reason());
    }
    ProbeResult grpc = grpcProbe.check(grpcHostname(plan), GRPC_PORT);
    return grpc.ready()
        ? new ProbeResult(true, "served-and-grpc-accepted")
        : new ProbeResult(false, "grpc-" + grpc.reason());
  }

  private ProbeResult grpc(
      EnvironmentIdentityPlan plan, Secret material, String expectedFingerprint) {
    if (material == null || expectedFingerprint == null || expectedFingerprint.isBlank()) {
      return new ProbeResult(false, "material-or-leaf-fingerprint-missing");
    }
    String hostname = grpcHostname(plan);
    try (SSLSocket socket =
        openGrpcTlsSocket(
            hostname,
            hostname,
            GRPC_PORT,
            expectedFingerprint,
            material,
            properties.getGrpcTrustAnchorSha256())) {
      return socket == null
          ? new ProbeResult(false, "leaf-fingerprint-mismatch")
          : new ProbeResult(true, "mtls-handshake");
    } catch (Exception exception) {
      return new ProbeResult(false, "connection-failed");
    }
  }

  private static String grpcHostname(EnvironmentIdentityPlan plan) {
    if (!plan.grpcConsumers().contains(GRPC_PROBE_SERVICE)) {
      throw new IllegalArgumentException("fixed gRPC probe service is not a rollout consumer");
    }
    return GRPC_PROBE_SERVICE + "." + plan.runtimeNamespace() + ".svc.cluster.local";
  }

  static SSLSocket openGrpcTlsSocket(
      String connectHost,
      String identityHostname,
      int port,
      String expectedFingerprint,
      Secret material,
      String expectedTrustAnchor)
      throws Exception {
    Socket transport = new Socket();
    SSLSocket socket = null;
    boolean transferred = false;
    try {
      transport.connect(new InetSocketAddress(connectHost, port), 5000);
      socket =
          (SSLSocket)
              grpcSslContext(material, expectedTrustAnchor)
                  .getSocketFactory()
                  .createSocket(transport, identityHostname, port, true);
      socket.setSoTimeout(8000);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      parameters.setServerNames(List.of(new SNIHostName(identityHostname)));
      parameters.setApplicationProtocols(new String[] {"h2"});
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      if (!"h2".equals(socket.getApplicationProtocol())) {
        throw new IllegalStateException("gRPC endpoint did not negotiate HTTP/2");
      }
      X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      if (!normalize(expectedFingerprint).equals(normalize(fingerprint(leaf)))) {
        return null;
      }
      transferred = true;
      return socket;
    } finally {
      if (!transferred) {
        if (socket != null) {
          socket.close();
        } else {
          transport.close();
        }
      }
    }
  }

  static SSLContext grpcSslContext(Secret material, String expectedTrustAnchor) throws Exception {
    if (material == null || material.getData() == null) {
      throw new IllegalArgumentException("gRPC probe material is absent");
    }
    List<X509Certificate> chain = certificates(requiredData(material, "tls.crt"));
    PrivateKey privateKey = privateKey(requiredData(material, "tls.key"));
    List<X509Certificate> anchors = certificates(requiredData(material, "ca.crt"));
    String normalizedAnchor = normalize(expectedTrustAnchor);
    if (!normalizedAnchor.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("configured gRPC trust anchor is invalid");
    }
    X509Certificate anchor =
        anchors.stream()
            .filter(
                certificate -> {
                  try {
                    return normalizedAnchor.equals(fingerprint(certificate));
                  } catch (Exception exception) {
                    throw new IllegalArgumentException("gRPC trust anchor is invalid", exception);
                  }
                })
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("gRPC trust anchor mismatch"));

    char[] password = new char[0];
    KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
    keys.load(null, password);
    keys.setKeyEntry("client", privateKey, password, chain.toArray(Certificate[]::new));
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keys, password);

    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    trust.setCertificateEntry("fixed-grpc-ca", anchor);
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trust);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    return context;
  }

  private static String requiredData(Secret material, String key) {
    String value = material.getData().get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("gRPC probe material is incomplete");
    }
    return value;
  }

  private static List<X509Certificate> certificates(String encodedPem) throws Exception {
    byte[] pem = Base64.getDecoder().decode(encodedPem);
    Collection<? extends Certificate> parsed =
        CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem));
    if (parsed.isEmpty()) {
      throw new IllegalArgumentException("gRPC certificate material is empty");
    }
    List<X509Certificate> result = new ArrayList<>();
    for (Certificate certificate : parsed) {
      result.add((X509Certificate) certificate);
    }
    return result;
  }

  private static PrivateKey privateKey(String encodedPem) throws Exception {
    String pem = new String(Base64.getDecoder().decode(encodedPem), StandardCharsets.US_ASCII);
    Matcher matcher = PRIVATE_KEY_BLOCK.matcher(pem);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("gRPC private key PEM label is invalid");
    }
    byte[] der = Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private ProbeResult https(String hostname, int port, String expectedFingerprint) {
    if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
      return new ProbeResult(false, "leaf-fingerprint-missing");
    }
    try (SSLSocket socket = openTlsSocket(hostname, port, expectedFingerprint)) {
      if (socket == null) {
        return new ProbeResult(false, "leaf-fingerprint-mismatch");
      }
      OutputStream output = socket.getOutputStream();
      output.write(
          ("GET / HTTP/1.1\r\nHost: " + hostname + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      output.flush();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      String statusLine = reader.readLine();
      Matcher matcher = statusLine == null ? null : HTTP_STATUS.matcher(statusLine);
      if (matcher == null || !matcher.find()) {
        return new ProbeResult(false, "invalid-http-response");
      }
      int code = Integer.parseInt(matcher.group(1));
      return code < 500
          ? new ProbeResult(true, "http-" + code)
          : new ProbeResult(false, "http-" + code);
    } catch (Exception exception) {
      return new ProbeResult(false, "connection-failed");
    }
  }

  private ProbeResult telnet(String hostname, int port, String expectedFingerprint) {
    if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
      return new ProbeResult(false, "leaf-fingerprint-missing");
    }
    try (SSLSocket socket = openTlsSocket(hostname, port, expectedFingerprint)) {
      return socket == null
          ? new ProbeResult(false, "leaf-fingerprint-mismatch")
          : new ProbeResult(true, "tls-handshake");
    } catch (Exception exception) {
      return new ProbeResult(false, "connection-failed");
    }
  }

  private static SSLSocket openTlsSocket(String hostname, int port, String expectedFingerprint)
      throws Exception {
    if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
      throw new IllegalStateException("expected served leaf fingerprint is required");
    }
    SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
    return openTlsSocket(hostname, port, expectedFingerprint, socket);
  }

  private static SSLSocket openTlsSocket(
      String hostname, int port, String expectedFingerprint, SSLSocket socket) throws Exception {
    boolean transferred = false;
    try {
      socket.setSoTimeout(8000);
      socket.connect(new InetSocketAddress(hostname, port), 5000);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      parameters.setServerNames(List.of(new SNIHostName(hostname)));
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      String actual = fingerprint(leaf);
      if (!normalize(expectedFingerprint).equals(normalize(actual))) {
        return null;
      }
      transferred = true;
      return socket;
    } finally {
      if (!transferred) {
        socket.close();
      }
    }
  }

  private static String fingerprint(X509Certificate certificate) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
    StringBuilder result = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      result.append(String.format(Locale.ROOT, "%02x", value));
    }
    return result.toString();
  }

  private static String normalize(String fingerprint) {
    return fingerprint.toLowerCase(Locale.ROOT).replace(":", "").trim();
  }

  int telnetPort(String name) {
    if ("dev-demo".equals(name)) {
      int port = properties.getDevDemoTelnetPort();
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("dev-demo Telnet port is outside the valid port range");
      }
      return port;
    }
    Matcher matcher = PREVIEW_NUMBER.matcher(name);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("unsupported hosted environment name");
    }
    int base = properties.getPreviewTelnetPortBase();
    long port = (long) base + Integer.parseInt(matcher.group(1));
    if (base < 1 || base > 65520 || port < base || port > base + 15L) {
      throw new IllegalArgumentException(
          "preview Telnet port is outside the fixed allocation window");
    }
    return Math.toIntExact(port);
  }

  @FunctionalInterface
  interface EndpointProbe {
    ProbeResult check(String hostname, int port);
  }

  public record ProbeResult(boolean ready, String reason) {}
}
