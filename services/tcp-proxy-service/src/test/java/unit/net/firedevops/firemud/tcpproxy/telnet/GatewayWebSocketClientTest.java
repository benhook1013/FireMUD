package net.firedevops.firemud.tcpproxy.telnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.net.InetAddress;
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
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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
    Exception failure = null;
    for (GatewayWebSocketClient client : clients) {
      try {
        client.close();
      } catch (Exception error) {
        failure = collectFailure(failure, error);
      }
    }
    for (MockWebServer server : servers) {
      try {
        server.close();
      } catch (Exception error) {
        failure = collectFailure(failure, error);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static Exception collectFailure(Exception collected, Exception error) {
    if (collected == null) {
      return error;
    }
    collected.addSuppressed(error);
    return collected;
  }

  @Test
  void teardownAttemptsEveryResourceAndCollectsFailures() throws Exception {
    GatewayWebSocketClient failingClient = mock(GatewayWebSocketClient.class);
    GatewayWebSocketClient succeedingClient = mock(GatewayWebSocketClient.class);
    MockWebServer failingServer = mock(MockWebServer.class);
    MockWebServer succeedingServer = mock(MockWebServer.class);
    Exception clientFailure = new java.io.IOException("client close failed");
    Exception serverFailure = new java.io.IOException("server close failed");
    doThrow(clientFailure).when(failingClient).close();
    doThrow(serverFailure).when(failingServer).close();
    clients.add(failingClient);
    clients.add(succeedingClient);
    servers.add(failingServer);
    servers.add(succeedingServer);

    Exception failure;
    try {
      failure = assertThrows(Exception.class, this::closeResources);
    } finally {
      clients.clear();
      servers.clear();
    }

    assertSame(clientFailure, failure);
    assertEquals(1, failure.getSuppressed().length);
    assertSame(serverFailure, failure.getSuppressed()[0]);
    verify(failingClient).close();
    verify(succeedingClient).close();
    verify(failingServer).close();
    verify(succeedingServer).close();
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
  void mutualTlsReadinessRequiresCertificateWatcher() throws Exception {
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(new MockResponse().setResponseCode(200));
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:" + server.getPort() + "/ws/game",
            certificate.toString(),
            privateKey.toString(),
            caCertificate.toString(),
            certificate.toString(),
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            false);
    clients.add(client);

    assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void plaintextReadinessDoesNotRequireCertificateWatcher() throws Exception {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200));
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    servers.add(server);
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "ws://localhost:" + server.getPort() + "/ws/game",
            "",
            "",
            "",
            "",
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            false);
    clients.add(client);

    assertTrue(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
  }

  @Test
  void inFlightPlaintextReadinessFailsWhenClientCloses() throws Exception {
    CountDownLatch releaseResponse = new CountDownLatch(1);
    MockWebServer server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            releaseResponse.await(15, TimeUnit.SECONDS);
            return new MockResponse().setResponseCode(200);
          }
        });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    servers.add(server);
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "ws://localhost:" + server.getPort() + "/ws/game",
            "",
            "",
            "",
            "",
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            false);
    clients.add(client);
    CompletableFuture<Boolean> readiness = client.isReadyAsync();

    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
      client.close();
    } finally {
      releaseResponse.countDown();
    }

    assertFalse(readiness.get(5, TimeUnit.SECONDS));
  }

  @Test
  void losingFinalWatchKeyFailsInFlightAndSubsequentReadiness() throws Exception {
    CountDownLatch releaseResponse = new CountDownLatch(1);
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            releaseResponse.await(15, TimeUnit.SECONDS);
            return new MockResponse().setResponseCode(200);
          }
        });
    Path watchedDirectory = Files.createDirectory(materialDirectory.resolve("watched-material"));
    Path watchedCertificate = Files.copy(certificate, watchedDirectory.resolve("client.crt"));
    Path watchedPrivateKey = Files.copy(privateKey, watchedDirectory.resolve("client.key"));
    Path watchedCa = Files.copy(caCertificate, watchedDirectory.resolve("ca.crt"));
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:" + server.getPort() + "/ws/game",
            watchedCertificate.toString(),
            watchedPrivateKey.toString(),
            watchedCa.toString(),
            certificate.toString(),
            false,
            "",
            new String[] {"dev"},
            new SimpleMeterRegistry(),
            true);
    clients.add(client);
    CompletableFuture<Boolean> readiness = client.isReadyAsync();

    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
      replaceWatchedDirectory(watchedDirectory);
      awaitCertificateWatcherStopped(client);
      assertNotNull(client.clientIdentity());
      releaseResponse.countDown();

      assertFalse(readiness.get(5, TimeUnit.SECONDS));
      int completedRequests = server.getRequestCount();
      assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
      assertEquals(completedRequests, server.getRequestCount());
    } finally {
      releaseResponse.countDown();
    }
  }

  @Test
  void losingGatewayWatchKeyFailsReadinessWhileOtherSharedProfileKeysRemainLive()
      throws Exception {
    CountDownLatch releaseResponse = new CountDownLatch(1);
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            releaseResponse.await(15, TimeUnit.SECONDS);
            return new MockResponse().setResponseCode(200);
          }
        });
    Path gatewayDirectory = Files.createDirectory(materialDirectory.resolve("gateway-material"));
    Path watchedCertificate = Files.copy(certificate, gatewayDirectory.resolve("client.crt"));
    Path watchedPrivateKey = Files.copy(privateKey, gatewayDirectory.resolve("client.key"));
    Path watchedCa = Files.copy(caCertificate, gatewayDirectory.resolve("ca.crt"));
    TlsMaterial otherIdentity = copyRotatedTlsMaterial();
    Path grpcDirectory = Files.createDirectory(materialDirectory.resolve("grpc-material"));
    Path grpcCertificate =
        Files.copy(otherIdentity.certificate(), grpcDirectory.resolve("server.crt"));
    Path telnetDirectory = Files.createDirectory(materialDirectory.resolve("telnet-material"));
    Path telnetCertificate =
        Files.copy(otherIdentity.certificate(), telnetDirectory.resolve("server.crt"));
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:" + server.getPort() + "/ws/game",
            watchedCertificate.toString(),
            watchedPrivateKey.toString(),
            watchedCa.toString(),
            grpcCertificate.toString(),
            true,
            telnetCertificate.toString(),
            new String[] {"prod"},
            new SimpleMeterRegistry(),
            true);
    clients.add(client);
    CompletableFuture<Boolean> readiness = client.isReadyAsync();

    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
      replaceWatchedDirectory(gatewayDirectory);
      awaitCertificateWatcherUnhealthy(client);
      assertTrue(client.isCertificateWatcherRunning());
      assertNotNull(client.clientIdentity());
      releaseResponse.countDown();

      assertFalse(readiness.get(5, TimeUnit.SECONDS));
      int completedRequests = server.getRequestCount();
      assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
      assertEquals(completedRequests, server.getRequestCount());
    } finally {
      releaseResponse.countDown();
    }
  }

  @Test
  void readinessCompletionKeepsGenerationAcquiredUntilObserversRun() throws Exception {
    CountDownLatch releaseResponse = new CountDownLatch(1);
    CountDownLatch completionObserverEntered = new CountDownLatch(1);
    CountDownLatch releaseCompletionObserver = new CountDownLatch(1);
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
              return new MockResponse().setResponseCode(503);
            }
            return new MockResponse().setResponseCode(200);
          }
        });
    GatewayWebSocketClient client = newClient("localhost", server.getPort(), caCertificate);
    HttpClient initialClient = (HttpClient) client.clientIdentity();
    CompletableFuture<Boolean> readiness = client.isReadyAsync();
    CompletableFuture<Void> completionObserver =
        readiness.thenAccept(
            ignored -> {
              completionObserverEntered.countDown();
              try {
                if (!releaseCompletionObserver.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("completion observer was not released");
                }
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("completion observer was interrupted", error);
              }
            });

    try {
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
      assertTrue(client.reloadNow());
      assertEquals(2, client.generationCount());
      releaseResponse.countDown();

      assertTrue(completionObserverEntered.await(5, TimeUnit.SECONDS));
      assertFalse(initialClient.awaitTermination(java.time.Duration.ofMillis(200)));
    } finally {
      releaseResponse.countDown();
      releaseCompletionObserver.countDown();
    }
    completionObserver.get(5, TimeUnit.SECONDS);
    awaitTermination(initialClient);
    awaitGenerationCount(client, 1);
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
  void cancellingConnectionFutureCancelsHandshakeAndReleasesExactlyOnce() {
    AtomicInteger releases = new AtomicInteger();
    CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
    GatewayWebSocketClient.ReleasingWebSocketListener listener =
        new GatewayWebSocketClient.ReleasingWebSocketListener(
            new WebSocket.Listener() {}, releases::incrementAndGet);
    CompletableFuture<WebSocket> connection =
        new GatewayWebSocketClient.ConnectionFuture(handshake, listener);

    assertTrue(connection.cancel(true));

    assertTrue(handshake.isCancelled());
    assertEquals(1, releases.get());
    listener.onError(mock(WebSocket.class), new IllegalStateException("late callback"));
    assertEquals(1, releases.get());
  }

  @Test
  void cancellationAbortsAHandshakeThatSucceedsAfterCancellation() {
    AtomicInteger releases = new AtomicInteger();
    AtomicInteger cancellationAttempts = new AtomicInteger();
    CompletableFuture<WebSocket> handshake =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            cancellationAttempts.incrementAndGet();
            return false;
          }
        };
    WebSocket webSocket = mock(WebSocket.class);
    GatewayWebSocketClient.ReleasingWebSocketListener listener =
        new GatewayWebSocketClient.ReleasingWebSocketListener(
            new WebSocket.Listener() {}, releases::incrementAndGet);
    CompletableFuture<WebSocket> connection =
        new GatewayWebSocketClient.ConnectionFuture(handshake, listener);

    assertTrue(connection.cancel(true));
    assertTrue(handshake.complete(webSocket));

    assertEquals(1, cancellationAttempts.get());
    verify(webSocket).abort();
    assertEquals(1, releases.get());
    listener.onError(webSocket, new IllegalStateException("late callback"));
    assertEquals(1, releases.get());
  }

  @Test
  void cancelledPendingHandshakeDoesNotPinRetiredGeneration() throws Exception {
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    GatewayWebSocketClient client = newClient("localhost", server.getPort(), caCertificate);
    HttpClient initialGeneration = (HttpClient) client.clientIdentity();
    CompletableFuture<WebSocket> connection =
        client.connect(null, null, null, null, null, null, null, new WebSocket.Listener() {});
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    assertTrue(connection.cancel(true));
    assertTrue(client.reloadNow());

    awaitTermination(initialGeneration);
    awaitGenerationCount(client, 1);
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
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MockWebServer server = startMutualTlsServer(InetAddress.getByName("127.0.0.1"));
    server.enqueue(new MockResponse().setResponseCode(200));
    GatewayWebSocketClient client =
        new GatewayWebSocketClient(
            "wss://localhost:" + server.getPort() + "/ws/game",
            rotatedCertificate.toString(),
            rotatedPrivateKey.toString(),
            rotatedCaCertificate.toString(),
            rotatedCertificate.toString(),
            false,
            "",
            new String[] {"dev"},
            registry,
            true);
    clients.add(client);
    HttpClient initialClient = (HttpClient) client.clientIdentity();
    assertTrue(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));

    Files.copy(
        certificate, rotatedCaCertificate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    awaitClientUnavailable(client);
    assertNull(client.clientIdentity());
    int completedRequests = server.getRequestCount();
    assertFalse(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertEquals(completedRequests, server.getRequestCount());
    awaitTermination(initialClient);
    awaitGenerationCount(client, 0);
    double reloadFailures =
        registry
            .counter("tcpproxy.gateway.handshake.failures", "reason", "cert_validation")
            .count();
    assertTrue(reloadFailures >= 1.0);
    ExecutionException connectionFailure =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .connect(null, null, null, null, null, null, null, new WebSocket.Listener() {})
                    .get(5, TimeUnit.SECONDS));
    assertTrue(connectionFailure.getCause().getMessage().contains("reason=cert_validation"));
    assertEquals(
        reloadFailures + 1.0,
        registry
            .counter("tcpproxy.gateway.handshake.failures", "reason", "cert_validation")
            .count());

    Files.copy(
        caCertificate, rotatedCaCertificate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Object recoveredClient = awaitClientAvailable(client);
    assertNotSame(initialClient, recoveredClient);
    assertEquals(1, client.generationCount());
    server.enqueue(new MockResponse().setResponseCode(200));
    assertTrue(client.isReadyAsync().get(5, TimeUnit.SECONDS));
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
  }

  @Test
  void reloadReasonDoesNotParseAnExceptionDiagnostic() {
    assertEquals(
        "unknown",
        GatewayWebSocketClient.reasonFrom(
            new IllegalStateException("diagnostic text; reason=client_cert_missing")));
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

    TlsMaterial rotatedMaterial = copyRotatedTlsMaterial();
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
    assertClientIdentityRemainsStable(client, reloadedClient);
    awaitGenerationCount(client, 1);

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
            true);
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

  private void replaceWatchedDirectory(Path watchedDirectory) throws Exception {
    Path replacement = Files.createDirectory(materialDirectory.resolve("replacement-material"));
    Files.copy(certificate, replacement.resolve("client.crt"));
    Files.copy(privateKey, replacement.resolve("client.key"));
    Files.copy(caCertificate, replacement.resolve("ca.crt"));
    Files.delete(watchedDirectory.resolve("client.crt"));
    Files.delete(watchedDirectory.resolve("client.key"));
    Files.delete(watchedDirectory.resolve("ca.crt"));
    Files.delete(watchedDirectory);
    Files.move(replacement, watchedDirectory);
  }

  private TlsMaterial copyRotatedTlsMaterial() throws Exception {
    return new TlsMaterial(
        copyResource("/certs/rotated-gateway-client.crt", "rotated-client.crt"),
        copyResource("/certs/rotated-gateway-client.key", "rotated-client.key"),
        copyResource("/certs/rotated-gateway-client-ca.crt", "rotated-ca.crt"));
  }

  private Path copyResource(String resourceName, String fileName) throws Exception {
    Path target = materialDirectory.resolve(fileName);
    try (var input = getClass().getResourceAsStream(resourceName)) {
      assertNotNull(input);
      Files.copy(input, target);
    }
    return target;
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

  private static void awaitCertificateWatcherStopped(GatewayWebSocketClient client)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (client.isCertificateWatcherRunning() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(client.isCertificateWatcherRunning());
  }

  private static void awaitCertificateWatcherUnhealthy(GatewayWebSocketClient client)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (client.isCertificateWatcherHealthy() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(client.isCertificateWatcherHealthy());
  }

  private static void awaitClientUnavailable(GatewayWebSocketClient client) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (client.clientIdentity() != null && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertNull(client.clientIdentity());
  }

  private static Object awaitClientAvailable(GatewayWebSocketClient client) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Object identity = client.clientIdentity();
    while (identity == null && System.nanoTime() < deadline) {
      Thread.sleep(10);
      identity = client.clientIdentity();
    }
    return Objects.requireNonNull(identity);
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

  private static void assertClientIdentityRemainsStable(
      GatewayWebSocketClient client, Object expectedIdentity) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
    do {
      assertSame(expectedIdentity, client.clientIdentity());
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    assertSame(expectedIdentity, client.clientIdentity());
  }

  private record TlsMaterial(Path certificate, Path privateKey, Path caCertificate) {}
}
