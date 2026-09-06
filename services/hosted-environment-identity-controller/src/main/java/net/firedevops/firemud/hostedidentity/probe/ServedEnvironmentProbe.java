package net.firedevops.firemud.hostedidentity.probe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.firedevops.firemud.hostedidentity.config.HostedIdentityProperties;
import net.firedevops.firemud.hostedidentity.model.EnvironmentIdentityPlan;
import org.springframework.stereotype.Component;

/** Probes the derived public HTTPS and TLS-Telnet endpoints without accepting arbitrary hosts. */
@Component
public class ServedEnvironmentProbe {
  private static final Pattern PREVIEW_NUMBER = Pattern.compile("pr-([1-9][0-9]*)");
  private static final Pattern HTTP_STATUS = Pattern.compile("HTTP/[^ ]+ ([0-9]{3})(?: |$)");
  private final HostedIdentityProperties properties;

  public ServedEnvironmentProbe(HostedIdentityProperties properties) {
    this.properties = properties;
  }

  public ProbeResult probe(EnvironmentIdentityPlan plan) {
    return probe(
        plan,
        telnetPort(plan.name()),
        properties.getIngressLeafSha256(),
        properties.getTelnetLeafSha256());
  }

  public ProbeResult probe(EnvironmentIdentityPlan plan, int telnetPort) {
    return probe(
        plan, telnetPort, properties.getIngressLeafSha256(), properties.getTelnetLeafSha256());
  }

  public ProbeResult probe(
      EnvironmentIdentityPlan plan,
      int telnetPort,
      String expectedIngressLeafSha256,
      String expectedTelnetLeafSha256) {
    ProbeResult https = https(plan.hostname(), 443, expectedIngressLeafSha256);
    if (!https.ready()) {
      return new ProbeResult(false, "https-" + https.reason());
    }
    ProbeResult telnet = telnet(plan.hostname(), telnetPort, expectedTelnetLeafSha256);
    return telnet.ready()
        ? new ProbeResult(true, "served")
        : new ProbeResult(false, "telnet-" + telnet.reason());
  }

  ProbeResult probe(
      EnvironmentIdentityPlan plan,
      int telnetPort,
      EndpointProbe httpsProbe,
      EndpointProbe telnetProbe) {
    ProbeResult https = httpsProbe.check(plan.hostname(), 443);
    if (!https.ready()) {
      return new ProbeResult(false, "https-" + https.reason());
    }
    ProbeResult telnet = telnetProbe.check(plan.hostname(), telnetPort);
    return telnet.ready()
        ? new ProbeResult(true, "served")
        : new ProbeResult(false, "telnet-" + telnet.reason());
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
      return 32016;
    }
    Matcher matcher = PREVIEW_NUMBER.matcher(name);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("unsupported hosted environment name");
    }
    int port = properties.getPreviewTelnetPortBase() + Integer.parseInt(matcher.group(1));
    if (port < 32000 || port > 32015) {
      throw new IllegalArgumentException(
          "preview Telnet port is outside the fixed allocation window");
    }
    return port;
  }

  @FunctionalInterface
  interface EndpointProbe {
    ProbeResult check(String hostname, int port);
  }

  public record ProbeResult(boolean ready, String reason) {}
}
