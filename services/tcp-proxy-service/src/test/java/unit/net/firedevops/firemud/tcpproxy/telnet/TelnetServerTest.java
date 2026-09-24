package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.grpc.TlsCertificateWatcher;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.tcpproxy.health.GatewayGameplayReadinessProbe;
import net.firedevops.firemud.tcpproxy.service.TcpProxyEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

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
            false,
            "",
            "",
            false,
            0,
            0,
            4096,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class),
            readyProbe(),
            gatewayClient());
    server.start();
    server.stop();
    assertTrue(true); // no exception means success
  }

  @Test
  void failedBindReleasesEventLoopGroupsForRetry() throws Exception {
    try (ServerSocket blocker = new ServerSocket(0)) {
      server =
          new TelnetServer(
              blocker.getLocalPort(),
              false,
              "",
              "",
              false,
              0,
              0,
              4096,
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
              Mockito.mock(TcpProxyEventService.class),
              readyProbe(),
              gatewayClient());

      assertThrows(IllegalStateException.class, () -> server.start());
    }

    server.start();
    assertTrue(server.isRunning());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void invalidGatewayBufferLimitFailsFast(int maxBufferedLines) {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new TelnetServer(
                    0,
                    false,
                    "",
                    "",
                    false,
                    0,
                    0,
                    4096,
                    maxBufferedLines,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe(),
                    gatewayClient()));

    assertEquals("TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES must be positive", exception.getMessage());
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
                    true,
                    cert,
                    key,
                    false,
                    0,
                    0,
                    4096,
                    registry,
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe(),
                    gatewayClient()));

    assertTrue(ex.getMessage().contains("TLS"));
    assertEquals(1.0, registry.counter("tcpproxy.tls.misconfig").count());
  }

  @Test
  void missingGatewayUriFailsAtConstruction() {
    GatewayWebSocketClient client = Mockito.mock(GatewayWebSocketClient.class);

    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TelnetServer(
                    0,
                    false,
                    "",
                    "",
                    false,
                    0,
                    0,
                    4096,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe(),
                    client));
    assertEquals("gatewayUri", ex.getMessage());
  }

  @Test
  void missingGatewayWebSocketClientFailsBeforeGatewayAccess() {
    NullPointerException ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new TelnetServer(
                    0,
                    false,
                    "",
                    "",
                    false,
                    0,
                    0,
                    4096,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    Mockito.mock(TcpProxyEventService.class),
                    readyProbe(),
                    null));
    assertEquals("gatewayWebSocketClient", ex.getMessage());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidConfiguredDefaultMetadata")
  void invalidConfiguredDefaultsFailBeforeAcceptingSessions(
      String label,
      String gameInstanceId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                newServerWithDefaults(
                    registry, gameInstanceId, tenantId, worldSlug, realmSlug, pointerVersion));

    assertEquals(
        "TCP proxy default bridge metadata is invalid; reason=bad_header", ex.getMessage());
    assertEquals(1.0, registry.counter("tcpproxy.bridge.metadata.misconfig").count());
  }

  private static Stream<Arguments> invalidConfiguredDefaultMetadata() {
    String invalid = "safe\r\ninjected";
    return Stream.of(
        Arguments.of("gameInstanceId rejects CRLF", invalid, "7", "world", "realm", "1"),
        Arguments.of("tenantId rejects CRLF", "42", invalid, "world", "realm", "1"),
        Arguments.of("worldSlug rejects CRLF", "42", "7", invalid, "realm", "1"),
        Arguments.of("realmSlug rejects CRLF", "42", "7", "world", invalid, "1"),
        Arguments.of("pointerVersion rejects CRLF", "42", "7", "world", "realm", invalid),
        Arguments.of("pointerVersion rejects zero", "42", "7", "world", "realm", "0"));
  }

  @Test
  void configuredTlsCertificateAcceptsTlsHandshake(@TempDir Path tempDir) throws Exception {
    Path certificatePath = tempDir.resolve("dev-cert.pem");
    Path keyPath = tempDir.resolve("dev-key.pem");
    try (InputStream certificate = getClass().getResourceAsStream("/certs/dev-cert.pem");
        InputStream key = getClass().getResourceAsStream("/certs/dev-key.pem")) {
      assertNotNull(certificate);
      assertNotNull(key);
      Files.copy(certificate, certificatePath);
      Files.copy(key, keyPath);
    }

    server =
        new TelnetServer(
            0,
            true,
            certificatePath.toString(),
            keyPath.toString(),
            false,
            0,
            0,
            4096,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Mockito.mock(TcpProxyEventService.class),
            readyProbe(),
            gatewayClient());
    server.start();

    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (InputStream certificate = getClass().getResourceAsStream("/certs/dev-cert.pem")) {
      assertNotNull(certificate);
      X509Certificate devCertificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509").generateCertificate(certificate);
      Instant minimumNotAfter = Instant.now().plus(30, ChronoUnit.DAYS);
      assertTrue(
          devCertificate.getNotAfter().toInstant().isAfter(minimumNotAfter),
          () ->
              "Generated development certificate expires at "
                  + devCertificate.getNotAfter()
                  + "; regenerate the Gradle-owned fixture with "
                  + "./gradlew :tcp-proxy-service:clean "
                  + ":tcp-proxy-service:generateTcpProxyDevCerts");
      trustStore.setCertificateEntry("telnet-server", devCertificate);
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustManagers.getTrustManagers(), null);

    try (Socket plainSocket = new Socket()) {
      plainSocket.setSoTimeout(TLS_READ_TIMEOUT_MILLIS);
      plainSocket.connect(
          new InetSocketAddress("localhost", server.getPort()), TLS_CONNECT_TIMEOUT_MILLIS);
      try (SSLSocket socket =
          (SSLSocket)
              sslContext
                  .getSocketFactory()
                  .createSocket(plainSocket, "localhost", server.getPort(), true)) {
        socket.setSoTimeout(TLS_READ_TIMEOUT_MILLIS);
        SSLParameters sslParameters = socket.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(sslParameters);
        socket.startHandshake();
        assertTrue(socket.getSession().isValid());
      }
    }
  }

  @Test
  void projectedTlsRotationIsAtomicAndLeavesExistingConnectionsOpen(@TempDir Path tempDir)
      throws Exception {
    Path mount = Files.createDirectory(tempDir.resolve("telnet-tls"));
    createProjectedGeneration(
        mount, "..2026_09_24_00_00_00", "/certs/dev-cert.pem", "/certs/dev-key.pem");
    Files.createSymbolicLink(mount.resolve("..data"), Path.of("..2026_09_24_00_00_00"));
    Files.createSymbolicLink(mount.resolve("tls.crt"), Path.of("..data/tls.crt"));
    Files.createSymbolicLink(mount.resolve("tls.key"), Path.of("..data/tls.key"));

    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    int activeWatchersBefore = activeWatcherCount(TlsCertificateWatcher.health());
    server =
        new TelnetServer(
            0,
            true,
            mount.resolve("tls.crt").toString(),
            mount.resolve("tls.key").toString(),
            false,
            0,
            0,
            4096,
            registry,
            Mockito.mock(TcpProxyEventService.class),
            readyProbe(),
            gatewayClient());
    server.start();

    SSLContext clientContext = tlsClientContext();
    try (SSLSocket existingConnection = connectTls(server, clientContext)) {
      X509Certificate initialCertificate = peerCertificate(existingConnection);

      switchProjectedGeneration(
          mount,
          "..2026_09_24_00_00_01",
          "/certs/rotated-gateway-client.crt",
          "/certs/rotated-gateway-client.key");
      assertEquals("SUCCEEDED", invokeWatcherReload(server));

      java.math.BigInteger rotatedSerial;
      try (SSLSocket rotatedConnection = connectTls(server, clientContext)) {
        X509Certificate rotatedCertificate = peerCertificate(rotatedConnection);
        assertFalse(
            initialCertificate.getSerialNumber().equals(rotatedCertificate.getSerialNumber()));
        rotatedSerial = rotatedCertificate.getSerialNumber();
      }
      assertExistingTlsSession(existingConnection, initialCertificate);

      switchProjectedGeneration(mount, "..2026_09_24_00_00_02", "/certs/dev-cert.pem", null);
      assertEquals("FAILED", invokeWatcherReload(server));
      assertEquals(Status.OUT_OF_SERVICE, TlsCertificateWatcher.health().getStatus());
      assertTrue(registry.counter("tcpproxy.tls.misconfig").count() >= 1.0);

      try (SSLSocket connectionAfterPartialUpdate = connectTls(server, clientContext)) {
        assertEquals(
            rotatedSerial, peerCertificate(connectionAfterPartialUpdate).getSerialNumber());
      }
      assertExistingTlsSession(existingConnection, initialCertificate);

      switchProjectedGeneration(
          mount, "..2026_09_24_00_00_03", "/certs/dev-cert.pem", "/certs/dev-key.pem");
      assertEquals("SUCCEEDED", invokeWatcherReload(server));
      assertEquals(Status.UP, TlsCertificateWatcher.health().getStatus());
      try (SSLSocket recoveredConnection = connectTls(server, clientContext)) {
        assertEquals(
            initialCertificate.getSerialNumber(),
            peerCertificate(recoveredConnection).getSerialNumber());
      }
      assertExistingTlsSession(existingConnection, initialCertificate);
    }

    server.stop();
    assertEquals(activeWatchersBefore, activeWatcherCount(TlsCertificateWatcher.health()));
  }

  private GatewayGameplayReadinessProbe readyProbe() {
    GatewayGameplayReadinessProbe probe = Mockito.mock(GatewayGameplayReadinessProbe.class);
    Mockito.when(probe.isReady()).thenReturn(true);
    return probe;
  }

  private GatewayWebSocketClient gatewayClient() {
    GatewayWebSocketClient client = Mockito.mock(GatewayWebSocketClient.class);
    Mockito.when(client.gatewayUri()).thenReturn(URI.create("ws://localhost/ws/game"));
    return client;
  }

  private TelnetServer newServerWithDefaults(
      io.micrometer.core.instrument.simple.SimpleMeterRegistry registry,
      String gameInstanceId,
      String tenantId,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {
    return new TelnetServer(
        0,
        false,
        "",
        "",
        false,
        0,
        0,
        4096,
        gameInstanceId,
        tenantId,
        worldSlug,
        realmSlug,
        pointerVersion,
        registry,
        Mockito.mock(TcpProxyEventService.class),
        readyProbe(),
        gatewayClient(),
        new RuntimeIdentity(
            "tcp-proxy-service", "tcp-proxy-test", null, Instant.EPOCH, null, null, null));
  }

  private static void createProjectedGeneration(
      Path mount, String generation, String certificateResource, String keyResource)
      throws IOException {
    Path generationDirectory = Files.createDirectory(mount.resolve(generation));
    copyResource(certificateResource, generationDirectory.resolve("tls.crt"));
    if (keyResource == null) {
      Files.writeString(generationDirectory.resolve("tls.key"), "partial projected private key");
    } else {
      copyResource(keyResource, generationDirectory.resolve("tls.key"));
    }
  }

  private static void copyResource(String resource, Path target) throws IOException {
    try (InputStream input = TelnetServerTest.class.getResourceAsStream(resource)) {
      assertNotNull(input, "Missing TLS test fixture " + resource);
      Files.copy(input, target);
    }
  }

  private static void switchProjectedGeneration(
      Path mount, String generation, String certificateResource, String keyResource)
      throws IOException {
    createProjectedGeneration(mount, generation, certificateResource, keyResource);
    Path nextPointer = mount.resolve("..data-next");
    Files.createSymbolicLink(nextPointer, Path.of(generation));
    Files.move(
        nextPointer,
        mount.resolve("..data"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  private static String invokeWatcherReload(TelnetServer server) throws Exception {
    Field watcherField = TelnetServer.class.getDeclaredField("tlsCertificateWatcher");
    watcherField.setAccessible(true);
    TlsCertificateWatcher watcher = (TlsCertificateWatcher) watcherField.get(server);
    assertNotNull(watcher);
    Method invokeReload =
        TlsCertificateWatcher.class.getDeclaredMethod("invokeReloadCallback", boolean.class);
    invokeReload.setAccessible(true);
    return invokeReload.invoke(watcher, false).toString();
  }

  private static SSLContext tlsClientContext() throws Exception {
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    for (String fixture :
        new String[] {"/certs/dev-cert.pem", "/certs/rotated-gateway-client.crt"}) {
      try (InputStream certificate = TelnetServerTest.class.getResourceAsStream(fixture)) {
        assertNotNull(certificate);
        X509Certificate x509Certificate =
            (X509Certificate)
                CertificateFactory.getInstance("X.509").generateCertificate(certificate);
        trustStore.setCertificateEntry(fixture, x509Certificate);
      }
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers.getTrustManagers(), null);
    return context;
  }

  private static SSLSocket connectTls(TelnetServer server, SSLContext clientContext)
      throws IOException {
    Socket plainSocket = new Socket();
    plainSocket.setSoTimeout(TLS_READ_TIMEOUT_MILLIS);
    plainSocket.connect(
        new InetSocketAddress("localhost", server.getPort()), TLS_CONNECT_TIMEOUT_MILLIS);
    SSLSocket tlsSocket =
        (SSLSocket)
            clientContext
                .getSocketFactory()
                .createSocket(plainSocket, "localhost", server.getPort(), true);
    try {
      tlsSocket.setSoTimeout(TLS_READ_TIMEOUT_MILLIS);
      tlsSocket.startHandshake();
      return tlsSocket;
    } catch (IOException e) {
      tlsSocket.close();
      throw e;
    }
  }

  private static X509Certificate peerCertificate(SSLSocket socket) throws Exception {
    return (X509Certificate) socket.getSession().getPeerCertificates()[0];
  }

  private static void assertExistingTlsSession(SSLSocket socket, X509Certificate initialCertificate)
      throws Exception {
    assertTrue(socket.isConnected());
    assertFalse(socket.isClosed());
    assertTrue(socket.getSession().isValid());
    assertEquals(initialCertificate.getSerialNumber(), peerCertificate(socket).getSerialNumber());
  }

  private static int activeWatcherCount(Health health) {
    return ((Number) health.getDetails().get("activeWatchers")).intValue();
  }
}
