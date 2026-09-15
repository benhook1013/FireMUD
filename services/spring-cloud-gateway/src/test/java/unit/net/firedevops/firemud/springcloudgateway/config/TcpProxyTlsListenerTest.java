package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
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
  private static final String FIXTURE_FINGERPRINT =
      "d7c0609a0ded595525c734876e104ca0aa4072308c6d89fdc0e30c50f80b5c9a";

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
  void internalHandlerExposesOnlyGameplayAndHealthPaths() {
    AtomicBoolean delegated = new AtomicBoolean();
    HttpHandler delegate =
        (request, response) -> {
          delegated.set(true);
          response.setStatusCode(HttpStatus.NO_CONTENT);
          return response.setComplete();
        };
    TcpProxyTlsListener.InternalOnlyHttpHandler handler =
        new TcpProxyTlsListener.InternalOnlyHttpHandler(delegate);

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
  void stopClosesAcceptedChannelsWhenServerDisposalFails() throws Exception {
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
    assertThat(channelsField.get(listener)).isNull();
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
    properties.getBreakglassFingerprint().setExpiresAt("2100-01-01T00:00:00Z");
    return properties;
  }

  private static Path fixture(String name) {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
