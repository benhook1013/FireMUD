package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import net.firedevops.firemud.springcloudgateway.health.TcpProxyTlsListenerHealthIndicator;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.Disposable;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;

class TcpProxyTlsListenerTest {
  private static final String FIXTURE_FINGERPRINT = fixtureFingerprint();

  private static String fixtureFingerprint() {
    try (var input = Files.newInputStream(fixture("dev-cert.pem"))) {
      X509Certificate certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
      return java.util.HexFormat.of().formatHex(digest);
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Test
  void internalListenerRequiresClientCertificate() throws Exception {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.requiresClientCertificate()).thenReturn(true);
    when(policy.profileName()).thenReturn("breakglass_fingerprint");
    when(policy.timeUntilProfileExpiry()).thenReturn(null);
    HttpHandler handler =
        (request, response) -> {
          response.setStatusCode(HttpStatus.NO_CONTENT);
          return response.setComplete();
        };
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, handler);

    try {
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      int port = listener.boundPort();
      assertThat(port).isPositive();
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/readiness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());
      assertThat(requestStatus(port, clientContext(true), "/actuator/prometheus"))
          .isEqualTo(HttpStatus.NOT_FOUND.value());
      assertThat(requestStatus(port, clientContext(true), "/ws/game/../actuator/prometheus"))
          .isEqualTo(HttpStatus.NOT_FOUND.value());
      Throwable handshakeFailure =
          catchThrowable(
              () -> requestStatus(port, clientContext(false), "/actuator/health/liveness"));
      assertThat(handshakeFailure).as("TLS handshake without a client certificate").isNotNull();
      assertThat(TlsTestSupport.isTlsHandshakeRejection(handshakeFailure))
          .as("failure must be a TLS/client-certificate handshake rejection")
          .isTrue();
    } finally {
      listener.stop();
    }
    assertThat(listener.isRunning()).isFalse();
  }

  @Test
  void rejectedCredentialUpdateKeepsLastKnownGoodContextAndMakesReadinessUnhealthy(
      @TempDir Path directory) throws Exception {
    Path certificate = Files.copy(fixture("dev-cert.pem"), directory.resolve("tls.crt"));
    Path privateKey = Files.copy(fixture("dev-key.pem"), directory.resolve("tls.key"));
    Path clientCa = Files.copy(fixture("dev-ca.pem"), directory.resolve("ca.crt"));
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setCertificateChainPath(certificate.toString());
    properties.setPrivateKeyPath(privateKey.toString());
    properties.setTrustedClientCaPath(clientCa.toString());
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.requiresClientCertificate()).thenReturn(true);
    when(policy.profileName()).thenReturn("breakglass_fingerprint");
    when(policy.timeUntilProfileExpiry()).thenReturn(null);
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            properties,
            policy,
            (request, response) -> {
              response.setStatusCode(HttpStatus.NO_CONTENT);
              return response.setComplete();
            });

    try {
      listener.start();
      int port = listener.boundPort();
      assertThat(listener.isTlsMaterialHealthy()).isTrue();
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());

      Files.writeString(clientCa, "partial CA projection");
      await(() -> !listener.isTlsMaterialHealthy());

      var readiness = new TcpProxyTlsListenerHealthIndicator(properties, listener).health();
      assertThat(readiness.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
      assertThat(readiness.getDetails()).containsEntry("tlsMaterial", "unhealthy");
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .as("invalid trust material must not be installed for new no-SNI handshakes")
          .isEqualTo(HttpStatus.NO_CONTENT.value());

      Files.copy(fixture("dev-ca.pem"), clientCa, StandardCopyOption.REPLACE_EXISTING);
      await(listener::isTlsMaterialHealthy);
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());
    } finally {
      listener.stop();
    }
  }

  @Test
  void validClientCaRotationUpdatesNoSniHandshakesAndPreservesOpenBridge(@TempDir Path directory)
      throws Exception {
    Path clientCa = Files.copy(fixture("dev-ca.pem"), directory.resolve("ca.crt"));
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setTrustedClientCaPath(clientCa.toString());
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.requiresClientCertificate()).thenReturn(true);
    when(policy.profileName()).thenReturn("breakglass_fingerprint");
    when(policy.timeUntilProfileExpiry()).thenReturn(null);

    GenericApplicationContext appContext = new GenericApplicationContext();
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    AtomicBoolean websocketOpened = new AtomicBoolean();
    WebSocketHandler websocketHandler =
        session -> {
          websocketOpened.set(true);
          return session.receive().then();
        };
    mapping.setOrder(-1);
    mapping.setUrlMap(Map.of("/ws/game", websocketHandler));
    appContext.getBeanFactory().registerSingleton("gameplayMapping", mapping);
    appContext
        .getBeanFactory()
        .registerSingleton("gameplayWebSocketHandlerAdapter", new WebSocketHandlerAdapter());
    appContext.refresh();
    mapping.setApplicationContext(appContext);
    HttpHandler webHandler =
        WebHttpHandlerBuilder.webHandler(new DispatcherHandler(appContext)).build();
    HttpHandler httpHandler =
        (request, response) -> {
          if ("/actuator/health/liveness".equals(request.getURI().getPath())) {
            response.setStatusCode(HttpStatus.NO_CONTENT);
            return response.setComplete();
          }
          return webHandler.handle(request, response);
        };
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, httpHandler);
    Disposable websocket = null;

    try {
      listener.start();
      int port = listener.boundPort();
      assertThat(listener.isTlsMaterialHealthy()).isTrue();
      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());
      SslContext bridgeClientContext = clientContext(true);
      websocket =
          new ReactorNettyWebSocketClient(
                  HttpClient.create().secure(spec -> spec.sslContext(bridgeClientContext)))
              .execute(
                  URI.create("wss://127.0.0.1:" + port + "/ws/game"),
                  session -> session.receive().then())
              .subscribe();
      await(websocketOpened::get);
      assertThat(websocket.isDisposed()).isFalse();

      Files.copy(fixture("tcp-proxy-client-ca.pem"), clientCa, StandardCopyOption.REPLACE_EXISTING);
      SslContext rotatedClient = clientContext("tcp-proxy-client.pem", "tcp-proxy-client-key.pem");
      await(
          () -> {
            try {
              return requestStatus(port, rotatedClient, "/actuator/health/liveness")
                  == HttpStatus.NO_CONTENT.value();
            } catch (RuntimeException handshakePending) {
              return false;
            }
          });
      assertThat(listener.isTlsMaterialHealthy()).isTrue();
      assertThat(websocket.isDisposed())
          .as("ordinary TLS rotation leaves the established WebSocket bridge open")
          .isFalse();
      Throwable oldIdentityFailure =
          catchThrowable(
              () -> requestStatus(port, clientContext(true), "/actuator/health/liveness"));
      assertThat(oldIdentityFailure).isNotNull();
      assertThat(TlsTestSupport.isTlsHandshakeRejection(oldIdentityFailure)).isTrue();
    } finally {
      if (websocket != null) {
        websocket.dispose();
      }
      listener.stop();
      appContext.close();
    }
  }

  @Test
  void projectedCredentialSnapshotRejectsGenerationSwapDuringContextLoad(@TempDir Path directory)
      throws Exception {
    Path generationOne = Files.createDirectory(directory.resolve("..generation-one"));
    Path generationTwo = Files.createDirectory(directory.resolve("..generation-two"));
    Files.copy(fixture("dev-cert.pem"), generationOne.resolve("tls.crt"));
    Files.copy(fixture("dev-key.pem"), generationOne.resolve("tls.key"));
    Files.copy(fixture("dev-cert.pem"), generationTwo.resolve("tls.crt"));
    Files.copy(fixture("dev-key.pem"), generationTwo.resolve("tls.key"));
    Path generationPointer = directory.resolve("..data");
    Files.createSymbolicLink(generationPointer, Path.of("..generation-one"));
    Path certificate =
        Files.createSymbolicLink(directory.resolve("tls.crt"), Path.of("..data/tls.crt"));
    Path privateKey =
        Files.createSymbolicLink(directory.resolve("tls.key"), Path.of("..data/tls.key"));
    TcpProxyTlsListener.CredentialSnapshot snapshot =
        TcpProxyTlsListener.captureCredentialSnapshot(certificate, privateKey, null);

    snapshot.requireConsistentCertificateKeyGeneration();
    assertThat(snapshot.isCurrent()).isTrue();

    Files.delete(generationPointer);
    Files.createSymbolicLink(generationPointer, Path.of("..generation-two"));

    assertThat(snapshot.isCurrent())
        .as("a projection swap during context construction invalidates the captured snapshot")
        .isFalse();
  }

  @Test
  void internalListenerRejectsMissingBindAddress() {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setBindAddress(null);
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            properties, mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));

    Throwable failure = catchThrowable(listener::start);

    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TCP Proxy listener bind address must be configured and non-blank");
  }

  @Test
  void internalListenerRejectsBlankBindAddress() {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setBindAddress("  \t ");
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            properties, mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));

    Throwable failure = catchThrowable(listener::start);

    assertThat(failure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TCP Proxy listener bind address must be configured and non-blank");
  }

  @Test
  void startupPreservesRuntimeFailureWhenCleanupFails() throws Exception {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setBindAddress(" ");
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            properties, mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));
    DisposableServer server = mock(DisposableServer.class);
    RuntimeException cleanupFailure = new RuntimeException("server disposal failed");
    when(server.isDisposed()).thenReturn(true);
    doThrow(cleanupFailure)
        .when(server)
        .disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(listener, server);

    Throwable startupFailure = catchThrowable(listener::start);

    assertThat(startupFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TCP Proxy listener bind address must be configured and non-blank")
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
  }

  @Test
  void startupPreservesCheckedFailureWhenCleanupFails() throws Exception {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    properties.setCertificateChainPath(fixture("dev-cert.pem").toString());
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.requiresClientCertificate()).thenReturn(false);
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(properties, policy, mock(HttpHandler.class));
    SslContextBuilder builder = mock(SslContextBuilder.class);
    SSLException startupCause = new SSLException("context build failed");
    when(builder.clientAuth(ClientAuth.NONE)).thenReturn(builder);
    when(builder.protocols("TLSv1.3", "TLSv1.2")).thenReturn(builder);
    when(builder.build()).thenThrow(startupCause);
    DisposableServer server = mock(DisposableServer.class);
    RuntimeException cleanupFailure = new RuntimeException("server disposal failed");
    when(server.isDisposed()).thenReturn(true);
    doThrow(cleanupFailure)
        .when(server)
        .disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(listener, server);

    Throwable startupFailure;
    try (MockedStatic<SslContextBuilder> mocked = Mockito.mockStatic(SslContextBuilder.class)) {
      mocked
          .when(
              () ->
                  SslContextBuilder.forServer(
                      Path.of(properties.getCertificateChainPath()).toFile(),
                      Path.of(properties.getPrivateKeyPath()).toFile()))
          .thenReturn(builder);
      startupFailure = catchThrowable(listener::start);
    }

    assertThat(startupFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to start TCP Proxy internal TLS listener")
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isNotNull();
              assertThat(failure.getCause().getSuppressed()).containsExactly(cleanupFailure);
            });
  }

  @Test
  void internalHandlerExposesOnlyGameplayAndHealthPaths() {
    AtomicBoolean delegated = new AtomicBoolean();
    HttpHandler delegate =
        (request, response) -> {
          delegated.set(true);
          response.setStatusCode(HttpStatus.NO_CONTENT);
          return response.setComplete();
        };
    TcpProxyTlsListener.InternalOnlyHttpHandler handler =
        new TcpProxyTlsListener.InternalOnlyHttpHandler(delegate, () -> true);

    MockServerHttpResponse gameplayResponse = new MockServerHttpResponse();
    handler.handle(MockServerHttpRequest.get("/ws/game/demo").build(), gameplayResponse).block();
    assertThat(delegated).isTrue();
    assertThat(gameplayResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    delegated.set(false);
    MockServerHttpResponse normalizedGameplayResponse = new MockServerHttpResponse();
    handler
        .handle(MockServerHttpRequest.get("/ws/game/./demo").build(), normalizedGameplayResponse)
        .block();
    assertThat(delegated).isTrue();
    assertThat(normalizedGameplayResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    for (String traversalPath :
        new String[] {
          "/ws/game/../actuator/env",
          "/ws/game/%2e%2e/actuator/env",
          "/ws/game/%2E%2e/actuator/env",
          "/ws/game/%2e%2e%3bignored/actuator/env",
          "/ws/game/%2e%2e%2factuator/env",
          "/ws/game/%2e%2e/../actuator/env",
          "/ws/game/%2e/../actuator/env"
        }) {
      delegated.set(false);
      MockServerHttpResponse traversalResponse = new MockServerHttpResponse();
      handler
          .handle(
              MockServerHttpRequest.method(HttpMethod.GET, URI.create(traversalPath)).build(),
              traversalResponse)
          .block();
      assertThat(delegated).as("delegate for %s", traversalPath).isFalse();
      assertThat(traversalResponse.getStatusCode())
          .as("status for %s", traversalPath)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    delegated.set(false);
    MockServerHttpResponse nearMissResponse = new MockServerHttpResponse();
    handler.handle(MockServerHttpRequest.get("/ws/gameXYZ").build(), nearMissResponse).block();
    assertThat(delegated).isFalse();
    assertThat(nearMissResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    delegated.set(false);
    MockServerHttpResponse publicResponse = new MockServerHttpResponse();
    handler.handle(MockServerHttpRequest.get("/ping").build(), publicResponse).block();
    assertThat(delegated).isFalse();
    assertThat(publicResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    ServerHttpRequest requestWithoutPath = mock(ServerHttpRequest.class);
    when(requestWithoutPath.getURI()).thenReturn(URI.create("mailto:tcp-proxy@example.com"));
    MockServerHttpResponse pathlessResponse = new MockServerHttpResponse();
    handler.handle(requestWithoutPath, pathlessResponse).block();
    assertThat(delegated).isFalse();
    assertThat(pathlessResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void unhealthyProjectedTlsBlocksNewBridgeAndInternalReadinessButNotLiveness() {
    AtomicBoolean delegated = new AtomicBoolean();
    AtomicBoolean tlsHealthy = new AtomicBoolean(true);
    HttpHandler delegate =
        (request, response) -> {
          delegated.set(true);
          response.setStatusCode(HttpStatus.NO_CONTENT);
          return response.setComplete();
        };
    TcpProxyTlsListener.InternalOnlyHttpHandler handler =
        new TcpProxyTlsListener.InternalOnlyHttpHandler(delegate, tlsHealthy::get);

    for (String path : new String[] {"/ws/game/demo", "/actuator/health/readiness"}) {
      MockServerHttpResponse healthyResponse = new MockServerHttpResponse();
      handler.handle(MockServerHttpRequest.get(path).build(), healthyResponse).block();
      assertThat(delegated).as("healthy delegate for %s", path).isTrue();
      assertThat(healthyResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    tlsHealthy.set(false);
    for (String path : new String[] {"/ws/game/demo", "/actuator/health/readiness"}) {
      delegated.set(false);
      MockServerHttpResponse unhealthyResponse = new MockServerHttpResponse();
      handler.handle(MockServerHttpRequest.get(path).build(), unhealthyResponse).block();
      assertThat(delegated).as("unhealthy delegate for %s", path).isFalse();
      assertThat(unhealthyResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    delegated.set(false);
    MockServerHttpResponse livenessResponse = new MockServerHttpResponse();
    handler
        .handle(MockServerHttpRequest.get("/actuator/health/liveness").build(), livenessResponse)
        .block();
    assertThat(delegated).isTrue();
    assertThat(livenessResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void expiringTrustProfileTerminatesListenerAndExistingBridges() throws Exception {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.requiresClientCertificate()).thenReturn(true);
    when(policy.profileName()).thenReturn("breakglass_fingerprint");
    when(policy.timeUntilProfileExpiry()).thenReturn(Duration.ofSeconds(3));
    GenericApplicationContext appContext = new GenericApplicationContext();
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    AtomicBoolean websocketOpened = new AtomicBoolean();
    CountDownLatch serverBridgeClosed = new CountDownLatch(1);
    WebSocketHandler websocketHandler =
        session -> {
          websocketOpened.set(true);
          return session.receive().then().doFinally(signal -> serverBridgeClosed.countDown());
        };
    WebSocketHandler clientHandler = session -> session.receive().then();
    mapping.setOrder(-1);
    mapping.setUrlMap(Map.of("/ws/game", websocketHandler));
    appContext.getBeanFactory().registerSingleton("gameplayMapping", mapping);
    appContext
        .getBeanFactory()
        .registerSingleton("gameplayWebSocketHandlerAdapter", new WebSocketHandlerAdapter());
    appContext.refresh();
    mapping.setApplicationContext(appContext);
    HttpHandler handler =
        WebHttpHandlerBuilder.webHandler(new DispatcherHandler(appContext)).build();
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, handler);
    Disposable websocket = null;

    try {
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      int port = listener.boundPort();
      assertThat(port).isPositive();
      SslContext sslContext = clientContext(true);
      websocket =
          new ReactorNettyWebSocketClient(
                  HttpClient.create().secure(spec -> spec.sslContext(sslContext)))
              .execute(URI.create("wss://127.0.0.1:" + port + "/ws/game"), clientHandler)
              .subscribe();
      Instant websocketDeadline = Instant.now().plusSeconds(5);
      while (!websocketOpened.get() && Instant.now().isBefore(websocketDeadline)) {
        Thread.sleep(25);
      }
      assertThat(websocketOpened).isTrue();
      waitForAcceptedConnection(listener);
      assertThat(websocket.isDisposed()).isFalse();

      Instant deadline = Instant.now().plusSeconds(10);
      while (listener.isRunning() && Instant.now().isBefore(deadline)) {
        Thread.sleep(25);
      }

      assertThat(listener.isRunning()).isFalse();
      assertThat(serverBridgeClosed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      if (websocket != null) {
        websocket.dispose();
      }
      listener.stop();
      appContext.close();
    }
  }

  @Test
  void stopClosesAcceptedChannelsAndPreservesServerWhenDisposalFails() throws Exception {
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            tlsProperties(0), mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));
    DisposableServer server = mock(DisposableServer.class);
    ChannelGroup channels = mock(ChannelGroup.class);
    ChannelGroupFuture closeFuture = mock(ChannelGroupFuture.class);
    RuntimeException failure = new RuntimeException("server disposal failed");
    doThrow(failure).when(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    when(channels.close()).thenReturn(closeFuture);
    when(closeFuture.awaitUninterruptibly(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    Field channelsField = TcpProxyTlsListener.class.getDeclaredField("acceptedChannels");
    serverField.setAccessible(true);
    channelsField.setAccessible(true);
    serverField.set(listener, server);
    channelsField.set(listener, channels);

    Throwable thrown = catchThrowable(listener::stop);

    assertThat(thrown).isSameAs(failure);
    verify(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    verify(channels).close();
    verify(closeFuture).awaitUninterruptibly(org.mockito.ArgumentMatchers.anyLong());
    assertThat(serverField.get(listener)).isSameAs(server);
    assertThat(channelsField.get(listener)).isNull();
    assertThat(listener.isRunning()).isFalse();

    doNothing().when(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    listener.stop();
    assertThat(serverField.get(listener)).isNull();
  }

  @Test
  void alreadyExpiredTrustProfileSchedulesImmediateTermination() throws Exception {
    GatewayTcpProxyListenerProperties properties = tlsProperties(0);
    TcpProxyTrustPolicy policy = mock(TcpProxyTrustPolicy.class);
    when(policy.timeUntilProfileExpiry()).thenReturn(Duration.ZERO);
    when(policy.profileName()).thenReturn("breakglass_fingerprint");
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(properties, policy, mock(HttpHandler.class));
    DisposableServer server = mock(DisposableServer.class);
    RuntimeException failure = new RuntimeException("server disposal failed");
    doThrow(failure).when(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));

    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(listener, server);
    var expiryMethod = TcpProxyTlsListener.class.getDeclaredMethod("scheduleProfileExpiry");
    expiryMethod.setAccessible(true);

    expiryMethod.invoke(listener);
    verify(server, timeout(5_000)).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    assertThat(serverField.get(listener)).isSameAs(server);
    assertThat(listener.isRunning()).isFalse();

    doNothing().when(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    listener.stop();
    assertThat(serverField.get(listener)).isNull();
  }

  @Test
  void disposedServerIsUnavailableEvenWhenMarkedRunning() throws Exception {
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            tlsProperties(0), mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));
    DisposableServer server = mock(DisposableServer.class);
    when(server.isDisposed()).thenReturn(true);

    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    Field runningField = TcpProxyTlsListener.class.getDeclaredField("running");
    serverField.setAccessible(true);
    runningField.setAccessible(true);
    serverField.set(listener, server);
    runningField.setBoolean(listener, true);

    assertThat(listener.isRunning()).isFalse();
    assertThat(listener.boundPort()).isEqualTo(-1);
  }

  @Test
  void stopClearsServerHandleAfterSuccessfulDisposal() throws Exception {
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(
            tlsProperties(0), mock(TcpProxyTrustPolicy.class), mock(HttpHandler.class));
    DisposableServer server = mock(DisposableServer.class);

    Field serverField = TcpProxyTlsListener.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(listener, server);

    listener.stop();

    verify(server).disposeNow(org.mockito.ArgumentMatchers.any(Duration.class));
    assertThat(serverField.get(listener)).isNull();
  }

  private static void waitForAcceptedConnection(TcpProxyTlsListener listener)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (listener.acceptedConnectionCount() == 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
    assertThat(listener.acceptedConnectionCount()).isPositive();
  }

  private static int requestStatus(int port, SslContext context, String path) {
    return Objects.requireNonNull(
        HttpClient.create()
            .secure(spec -> spec.sslContext(context))
            .get()
            .uri("https://127.0.0.1:" + port + path)
            .responseSingle((response, content) -> content.thenReturn(response.status().code()))
            .block(Duration.ofSeconds(5)));
  }

  private static SslContext clientContext(boolean withCertificate) throws Exception {
    SslContextBuilder builder =
        SslContextBuilder.forClient().trustManager(fixture("dev-ca.pem").toFile());
    if (withCertificate) {
      builder.keyManager(fixture("dev-cert.pem").toFile(), fixture("dev-key.pem").toFile());
    }
    return builder.build();
  }

  private static SslContext clientContext(String certificateName, String privateKeyName)
      throws Exception {
    return SslContextBuilder.forClient()
        .trustManager(fixture("dev-ca.pem").toFile())
        .keyManager(fixture(certificateName).toFile(), fixture(privateKeyName).toFile())
        .build();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
    assertThat(condition.getAsBoolean()).as("condition within bounded wait").isTrue();
  }

  private static GatewayTcpProxyListenerProperties tlsProperties(int port) {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setBindAddress("127.0.0.1");
    properties.setPort(port);
    properties.setCertificateChainPath(fixture("dev-cert.pem").toString());
    properties.setPrivateKeyPath(fixture("dev-key.pem").toString());
    properties.setTrustedClientCaPath(fixture("dev-ca.pem").toString());
    properties.setEnvironment("isolated-test");
    properties.setTrustProfile("breakglass_fingerprint");
    properties.getBreakglassFingerprint().setSha256(FIXTURE_FINGERPRINT);
    properties.getBreakglassFingerprint().setIncidentReference("TEST-1");
    properties.getBreakglassFingerprint().setExpiresAt(Instant.parse("2100-01-01T00:00:00Z"));
    return properties;
  }

  private static Path fixture(String name) {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path moduleTestFixture =
        workingDirectory.resolve("src/test/resources/certs").resolve(name).normalize();
    if (Files.isRegularFile(moduleTestFixture)) {
      return moduleTestFixture;
    }
    Path moduleSibling =
        workingDirectory
            .resolve("../common-test-support/src/testFixtures/resources/certs")
            .resolve(name)
            .normalize();
    if (Files.isRegularFile(moduleSibling)) {
      return moduleSibling;
    }
    Path repositoryFixture =
        workingDirectory
            .resolve("services/common-test-support/src/testFixtures/resources/certs")
            .resolve(name)
            .normalize();
    assertThat(repositoryFixture).as("TLS fixture %s", name).isRegularFile();
    return repositoryFixture;
  }
}
