package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayWebSocketClientTest {
  private final List<GatewayWebSocketClient> clients = new ArrayList<>();
  private final List<MockWebServer> servers = new ArrayList<>();
  @TempDir Path materialDirectory;
  private Path certificate;
  private Path privateKey;
  private Path caCertificate;

  @BeforeEach
  void copyTlsMaterial() throws Exception {
    certificate = copyResource("/certs/client.crt", "client.crt");
    privateKey = copyResource("/certs/client.key", "client.key");
    caCertificate = copyResource("/certs/ca.crt", "ca.crt");
  }

  @AfterEach
  void closeResources() throws Exception {
    for (GatewayWebSocketClient client : clients) {
      client.close();
    }
    for (MockWebServer server : servers) {
      server.close();
    }
  }

  @Test
  void oneMutualTlsClientPerformsWebSocketHandshakeAndReadinessRequest() throws Exception {
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new okhttp3.WebSocketListener() {
                  @Override
                  public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                    // The successful upgrade is the assertion boundary.
                  }
                }));
    server.enqueue(new MockResponse().setResponseCode(200));
    GatewayWebSocketClient client = newClient("localhost", server.getPort(), caCertificate);
    Object configuredClient = client.clientIdentity();

    WebSocket webSocket =
        client
            .connect(
                "127.0.0.1",
                "connection-1",
                "instance-1",
                "tenant-1",
                "demo",
                "main",
                "1",
                new WebSocket.Listener() {})
            .get(5, TimeUnit.SECONDS);

    assertTrue(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertSame(configuredClient, client.clientIdentity());
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS).getHandshake());
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS).getHandshake());
    webSocket.abort();
  }

  @Test
  void non2xxReadinessResponseFailsClosed() throws Exception {
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(new MockResponse().setResponseCode(503));
    GatewayWebSocketClient client = newClient("localhost", server.getPort(), caCertificate);

    assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS).getHandshake());
  }

  @Test
  void missingClientMaterialFailsClosedWithCanonicalReason(@TempDir Path tempDir) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "wss://localhost:8443/ws/game",
                    tempDir.resolve("missing.crt").toString(),
                    tempDir.resolve("missing.key").toString(),
                    caCertificate.toString(),
                    certificate.toString(),
                    false,
                    "",
                    new String[] {"dev"},
                    registry,
                    false));

    assertTrue(failure.getMessage().contains("reason=client_cert_missing"));
    assertEquals(
        1.0,
        registry
            .counter("tcpproxy.gateway.handshake.failures", "reason", "client_cert_missing")
            .count());
  }

  @Test
  void invalidClientMaterialPathUsesCanonicalDiagnostic() {
    String invalidPath = "invalid" + (char) 0 + "path";

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "wss://localhost:8443/ws/game",
                    invalidPath,
                    privateKey.toString(),
                    caCertificate.toString(),
                    certificate.toString(),
                    false,
                    "",
                    new String[] {"dev"},
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("reason=client_cert_missing"));
  }

  @Test
  void nonCaTrustMaterialIsRejectedAsWrongCa() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "wss://localhost:8443/ws/game",
                    certificate.toString(),
                    privateKey.toString(),
                    certificate.toString(),
                    certificate.toString(),
                    false,
                    "",
                    new String[] {"dev"},
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("reason=cert_validation"));
  }

  @Test
  void invalidClientPrivateKeyFailsClosed(@TempDir Path tempDir) throws Exception {
    Path invalidKey = tempDir.resolve("invalid.key");
    Files.writeString(invalidKey, "not a private key");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "wss://localhost:8443/ws/game",
                    certificate.toString(),
                    invalidKey.toString(),
                    caCertificate.toString(),
                    certificate.toString(),
                    false,
                    "",
                    new String[] {"dev"},
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("reason=client_cert_invalid"));
  }

  @Test
  void sharedEnvironmentRejectsReusedGrpcIdentity() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "wss://gateway.internal:8443/ws/game",
                    certificate.toString(),
                    privateKey.toString(),
                    caCertificate.toString(),
                    certificate.toString(),
                    false,
                    "",
                    new String[] {"prod"},
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("must be distinct from the gRPC server identity"));
    assertTrue(failure.getMessage().contains("reason=client_cert_invalid"));
  }

  @Test
  void hostnameMismatchFailsWithoutPlaintextFallback() throws Exception {
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.2"));
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new okhttp3.WebSocketListener() {
                  @Override
                  public void onOpen(okhttp3.WebSocket webSocket, Response response) {}
                }));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    GatewayWebSocketClient client =
        newClient("127.0.0.2", server.getPort(), caCertificate, registry);

    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .connect(null, null, null, null, null, null, null, new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS));

    assertNotNull(failure.getCause());
    assertEquals(
        1.0,
        registry
            .counter("tcpproxy.gateway.handshake.failures", "reason", "cert_validation")
            .count());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void plaintextGatewayIsRejectedInProduction() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "ws://gateway.internal:8080/ws/game",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    new String[] {"prod"},
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("reason=bad_url"));
  }

  @Test
  void plaintextGatewayIsRejectedWithoutAnExplicitLocalProfile() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new GatewayWebSocketClient(
                    "ws://gateway.internal:8080/ws/game",
                    "",
                    "",
                    "",
                    "",
                    false,
                    "",
                    new String[0],
                    new SimpleMeterRegistry(),
                    false));

    assertTrue(failure.getMessage().contains("reason=bad_url"));
  }

  @Test
  void transportSpecificLeavesMustUseDistinctKeyMaterial() {
    X509Certificate websocketCertificate = mock(X509Certificate.class);
    X509Certificate grpcCertificate = mock(X509Certificate.class);
    PublicKey websocketKey = mock(PublicKey.class);
    PublicKey grpcKey = mock(PublicKey.class);
    when(websocketKey.getEncoded()).thenReturn(new byte[] {1, 2, 3});
    when(grpcKey.getEncoded()).thenReturn(new byte[] {4, 5, 6});
    when(websocketCertificate.getPublicKey()).thenReturn(websocketKey);
    when(grpcCertificate.getPublicKey()).thenReturn(grpcKey);

    assertFalse(GatewayWebSocketClient.samePublicKey(websocketCertificate, grpcCertificate));
  }

  @Test
  void tlsProtocolNegotiationFailureUsesHandshakeProtocolReason() {
    assertEquals(
        "handshake_protocol",
        GatewayWebSocketClient.classifyFailure(
            new SSLHandshakeException("Received fatal alert: protocol_version")));
    assertEquals(
        "handshake_protocol",
        GatewayWebSocketClient.classifyFailure(
            new SSLHandshakeException("no cipher suites in common")));
    assertEquals(
        "cert_validation",
        GatewayWebSocketClient.classifyFailure(
            new SSLHandshakeException("PKIX path building failed")));
  }

  @Test
  void retiredGenerationClosesOnlyAfterEstablishedBridgeFinishes() throws Exception {
    AtomicReference<okhttp3.WebSocket> serverWebSocket = new AtomicReference<>();
    CountDownLatch clientClosed = new CountDownLatch(1);
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new okhttp3.WebSocketListener() {
                  @Override
                  public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                    serverWebSocket.set(webSocket);
                  }
                }));
    GatewayWebSocketClient client = newClient("localhost", server.getPort(), caCertificate);
    HttpClient oldGeneration = (HttpClient) client.clientIdentity();
    client
        .connect(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new WebSocket.Listener() {
              @Override
              public java.util.concurrent.CompletionStage<?> onClose(
                  WebSocket webSocket, int statusCode, String reason) {
                clientClosed.countDown();
                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                clientClosed.countDown();
              }
            })
        .get(5, TimeUnit.SECONDS);

    assertTrue(client.reloadNow());
    assertEquals(2, client.generationCount());
    assertFalse(oldGeneration.isTerminated());
    assertTrue(serverWebSocket.get().close(1000, "rotation complete"));
    assertTrue(clientClosed.await(5, TimeUnit.SECONDS));
    awaitTermination(oldGeneration);
    awaitGenerationCount(client, 1);
  }

  @Test
  void localBridgeCloseReleasesGenerationWithoutWaitingForPeerClose() {
    AtomicInteger releases = new AtomicInteger();
    WebSocket delegate = mock(WebSocket.class);
    CompletableFuture<WebSocket> closeWrite = new CompletableFuture<>();
    when(delegate.sendClose(WebSocket.NORMAL_CLOSURE, "bye")).thenReturn(closeWrite);
    GatewayWebSocketClient.ReleasingWebSocketListener listener =
        new GatewayWebSocketClient.ReleasingWebSocketListener(
            new WebSocket.Listener() {}, releases::incrementAndGet);
    WebSocket bridge = listener.wrap(delegate);

    CompletableFuture<WebSocket> close = bridge.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    assertEquals(0, releases.get());

    closeWrite.complete(delegate);

    assertSame(bridge, close.join());
    assertEquals(1, releases.get());

    listener.onClose(delegate, WebSocket.NORMAL_CLOSURE, "peer close arrived late");
    listener.onError(delegate, new IllegalStateException("late transport callback"));
    assertEquals(1, releases.get());
  }

  @Test
  void beanShutdownTerminatesCurrentAndRetiredGenerations() throws Exception {
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:8443/ws/game",
            certificate.toString(),
            privateKey.toString(),
            caCertificate.toString(),
            certificate.toString(),
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            false);
    HttpClient initialGeneration = (HttpClient) client.clientIdentity();
    assertTrue(client.reloadNow());
    HttpClient currentGeneration = (HttpClient) client.clientIdentity();

    client.close();

    awaitTermination(initialGeneration);
    awaitTermination(currentGeneration);
    assertEquals(0, client.generationCount());
  }

  @Test
  void failedReloadDisablesReadinessAndNewConnectionsUntilMaterialRecovers(@TempDir Path tempDir)
      throws Exception {
    Path rotatedCertificate = Files.copy(certificate, tempDir.resolve("client.crt"));
    Path rotatedPrivateKey = Files.copy(privateKey, tempDir.resolve("client.key"));
    Path rotatedCaCertificate = Files.copy(caCertificate, tempDir.resolve("ca.crt"));
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:8443/ws/game",
            rotatedCertificate.toString(),
            rotatedPrivateKey.toString(),
            rotatedCaCertificate.toString(),
            rotatedCertificate.toString(),
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            false);
    clients.add(client);
    HttpClient initialClient = (HttpClient) client.clientIdentity();

    Files.copy(
        certificate, rotatedCaCertificate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertFalse(client.reloadNow());
    assertNull(client.clientIdentity());
    assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    awaitTermination(initialClient);
    awaitGenerationCount(client, 0);
    assertTrue(
        client
            .connect(null, null, null, null, null, null, null, new WebSocket.Listener() {})
            .isCompletedExceptionally());

    Files.copy(
        caCertificate, rotatedCaCertificate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertTrue(client.reloadNow());
    assertNotSame(initialClient, client.clientIdentity());
    assertEquals(1, client.generationCount());
  }

  @Test
  void kubernetesProjectedSecretSwapReloadsNewCredentialsWithoutManualReload() throws Exception {
    Path projection = Files.createDirectory(materialDirectory.resolve("projected-secret"));
    Path firstGeneration =
        createProjectedGeneration(
            projection, "..2026_09_07_12_00_00.000000001", certificate, privateKey);
    Files.createSymbolicLink(projection.resolve("..data"), firstGeneration.getFileName());
    Files.createSymbolicLink(projection.resolve("tls.crt"), Path.of("..data/tls.crt"));
    Files.createSymbolicLink(projection.resolve("tls.key"), Path.of("..data/tls.key"));
    Files.createSymbolicLink(projection.resolve("ca.crt"), Path.of("..data/ca.crt"));

    TlsMaterial rotatedMaterial = copyDistinctClasspathTlsMaterial();
    Path rotatedCertificate = rotatedMaterial.certificate();
    Path rotatedPrivateKey = rotatedMaterial.privateKey();
    Path rotatedClientCa = rotatedMaterial.caCertificate();
    assertFalse(
        GatewayWebSocketClient.samePublicKey(
            readCertificate(certificate), readCertificate(rotatedCertificate)));
    Path serverTrust = materialDirectory.resolve("server-trust.pem");
    Files.writeString(
        serverTrust,
        Files.readString(caCertificate)
            + System.lineSeparator()
            + Files.readString(rotatedClientCa));
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"), serverTrust);
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new okhttp3.WebSocketListener() {
                  @Override
                  public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                    // The peer certificate assertion below proves the reloaded identity.
                  }
                }));

    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:" + server.getPort() + "/ws/game",
            projection.resolve("tls.crt").toString(),
            projection.resolve("tls.key").toString(),
            projection.resolve("ca.crt").toString(),
            projection.resolve("tls.crt").toString(),
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            true);
    clients.add(client);
    Object initialClient = client.clientIdentity();

    Path secondGeneration =
        createProjectedGeneration(
            projection, "..2026_09_07_12_05_00.000000002", rotatedCertificate, rotatedPrivateKey);
    Path temporaryDataLink = projection.resolve("..data_tmp");
    Files.createSymbolicLink(temporaryDataLink, secondGeneration.getFileName());
    Files.move(
        temporaryDataLink,
        projection.resolve("..data"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);

    Object reloadedClient = awaitClientIdentityChange(client, initialClient);
    Thread.sleep(300);
    assertSame(reloadedClient, client.clientIdentity());

    WebSocket webSocket =
        client
            .connect(null, null, null, null, null, null, null, new WebSocket.Listener() {})
            .get(5, TimeUnit.SECONDS);
    var request = Objects.requireNonNull(server.takeRequest(5, TimeUnit.SECONDS));
    var handshake = Objects.requireNonNull(request.getHandshake());
    X509Certificate peerCertificate = (X509Certificate) handshake.peerCertificates().getFirst();
    assertTrue(
        GatewayWebSocketClient.samePublicKey(readCertificate(rotatedCertificate), peerCertificate));
    webSocket.abort();
  }

  private GatewayWebSocketClient newClient(String host, int port, Path caCertificate) {
    return newClient(host, port, caCertificate, new SimpleMeterRegistry());
  }

  private GatewayWebSocketClient newClient(
      String host, int port, Path caCertificate, SimpleMeterRegistry registry) {
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://" + host + ":" + port + "/ws/game",
            certificate.toString(),
            privateKey.toString(),
            caCertificate.toString(),
            certificate.toString(),
            false,
            "",
            new String[] {"dev"},
            registry,
            false);
    clients.add(client);
    return client;
  }

  private MockWebServer startMutualTlsServer(InetAddress address) throws Exception {
    return startMutualTlsServer(address, caCertificate);
  }

  private MockWebServer startMutualTlsServer(InetAddress address, Path trustedClientCa)
      throws Exception {
    SslContext nettyContext =
        SslContextBuilder.forServer(certificate.toFile(), privateKey.toFile())
            .sslProvider(SslProvider.JDK)
            .trustManager(trustedClientCa.toFile())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    MockWebServer server = new MockWebServer();
    server.useHttps(((JdkSslContext) nettyContext).context().getSocketFactory(), false);
    server.requireClientAuth();
    server.start(address, 0);
    servers.add(server);
    return server;
  }

  private Path createProjectedGeneration(
      Path projection, String generationName, Path generationCertificate, Path generationKey)
      throws Exception {
    Path generation = Files.createDirectory(projection.resolve(generationName));
    Files.copy(generationCertificate, generation.resolve("tls.crt"));
    Files.copy(generationKey, generation.resolve("tls.key"));
    Files.copy(caCertificate, generation.resolve("ca.crt"));
    return generation;
  }

  private TlsMaterial copyDistinctClasspathTlsMaterial() throws Exception {
    X509Certificate initialCertificate = readCertificate(certificate);
    var resources = getClass().getClassLoader().getResources("certs/dev-cert.pem");
    while (resources.hasMoreElements()) {
      URL certificateResource = resources.nextElement();
      X509Certificate candidate;
      try (var input = certificateResource.openStream()) {
        candidate =
            (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      }
      if (GatewayWebSocketClient.samePublicKey(initialCertificate, candidate)) {
        continue;
      }
      return new TlsMaterial(
          copyResource(certificateResource, "rotated-client.crt"),
          copyResource(siblingResource(certificateResource, "dev-key.pem"), "rotated-client.key"),
          copyResource(siblingResource(certificateResource, "dev-ca.pem"), "rotated-ca.crt"));
    }
    throw new IllegalStateException("Distinct common test-support TLS material not found");
  }

  private Path copyResource(String resourceName, String fileName) throws Exception {
    Path target = materialDirectory.resolve(fileName);
    try (var input = getClass().getResourceAsStream(resourceName)) {
      assertNotNull(input);
      Files.copy(input, target);
    }
    return target;
  }

  private Path copyResource(URL resource, String fileName) throws Exception {
    Path target = materialDirectory.resolve(fileName);
    try (var input = resource.openStream()) {
      Files.copy(input, target);
    }
    return target;
  }

  private static URL siblingResource(URL resource, String fileName) throws Exception {
    String location = resource.toExternalForm();
    return URI.create(location.substring(0, location.lastIndexOf('/') + 1) + fileName).toURL();
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }

  private static void awaitTermination(HttpClient client) throws Exception {
    assertTrue(client.awaitTermination(java.time.Duration.ofSeconds(5)));
    assertTrue(client.isTerminated());
  }

  private static void awaitGenerationCount(GatewayWebSocketClient client, int expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (client.generationCount() != expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(expected, client.generationCount());
  }

  private static Object awaitClientIdentityChange(
      GatewayWebSocketClient client, Object initialIdentity) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Object currentIdentity = client.clientIdentity();
    while (currentIdentity == initialIdentity && System.nanoTime() < deadline) {
      Thread.sleep(10);
      currentIdentity = client.clientIdentity();
    }
    assertNotNull(currentIdentity);
    assertNotSame(initialIdentity, currentIdentity);
    return currentIdentity;
  }

  private record TlsMaterial(Path certificate, Path privateKey, Path caCertificate) {}
}
