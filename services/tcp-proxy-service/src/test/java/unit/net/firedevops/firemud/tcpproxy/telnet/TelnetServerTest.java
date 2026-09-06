package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.tcpproxy.health.GatewayGameplayReadinessProbe;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class TelnetServerTest {
  private static final int TLS_CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int TLS_READ_TIMEOUT_MILLIS = 5_000;

  private TelnetServer server;

  @AfterEach
  void cleanup() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void serverStartsAndStops() throws Exception {
    server =
        new TelnetServer(
            0,
            "ws://localhost/ws",
            false,
            "",
            "",
            false,
            0,
            0,
            4096,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class),
            readyProbe());
    server.start();
    server.stop();
    assertTrue(true); // no exception means success
  }

  @Test
  void tlsMisconfigurationFailsFastAndIncrementsMetric(@TempDir Path tempDir) {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    String cert = tempDir.resolve("missing-cert.pem").toString();
    String key = tempDir.resolve("missing-key.pem").toString();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                new TelnetServer(
                    0,
                    "ws://localhost/ws",
                    true,
                    cert,
                    key,
                    false,
                    0,
                    0,
                    4096,
                    registry,
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe()));

    assertTrue(ex.getMessage().contains("TLS"));
    assertEquals(1.0, registry.counter("tcpproxy.tls.misconfig").count());
  }

  @Test
  void configuredTlsCertificateAcceptsTlsHandshake(@TempDir Path tempDir) throws Exception {
    Path certificatePath = tempDir.resolve("dev-cert.pem");
    Path keyPath = tempDir.resolve("dev-key.pem");
    try (InputStream certificate = getClass().getResourceAsStream("/certs/dev-cert.pem");
        InputStream key = getClass().getResourceAsStream("/certs/dev-key.pem")) {
      assertTrue(certificate != null);
      assertTrue(key != null);
      Files.copy(certificate, certificatePath);
      Files.copy(key, keyPath);
    }

    server =
        new TelnetServer(
            0,
            "ws://localhost/ws",
            true,
            certificatePath.toString(),
            keyPath.toString(),
            false,
            0,
            0,
            4096,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class),
            readyProbe());
    server.start();

    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (InputStream certificate = getClass().getResourceAsStream("/certs/dev-cert.pem")) {
      assertTrue(certificate != null);
      trustStore.setCertificateEntry(
          "telnet-server",
          CertificateFactory.getInstance("X.509").generateCertificate(certificate));
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustManagers.getTrustManagers(), null);

    try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
      socket.setSoTimeout(TLS_READ_TIMEOUT_MILLIS);
      socket.connect(
          new InetSocketAddress("localhost", server.getPort()), TLS_CONNECT_TIMEOUT_MILLIS);
      SSLParameters sslParameters = socket.getSSLParameters();
      sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
      socket.setSSLParameters(sslParameters);
      socket.startHandshake();
      assertTrue(socket.getSession().isValid());
    }
  }

  private GatewayGameplayReadinessProbe readyProbe() {
    GatewayGameplayReadinessProbe probe = Mockito.mock(GatewayGameplayReadinessProbe.class);
    Mockito.when(probe.isReady()).thenReturn(true);
    return probe;
  }
}
