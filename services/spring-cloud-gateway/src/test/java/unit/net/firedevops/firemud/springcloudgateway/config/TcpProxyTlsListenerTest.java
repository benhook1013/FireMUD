package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;

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
    when(policy.timeUntilProfileExpiry()).thenReturn(Duration.ofMillis(100));
    HttpHandler handler = (request, response) -> response.setComplete();
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, handler);
    Connection connection = null;

    try {
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      int port = listener.boundPort();
      assertThat(port).isPositive();
      SslContext context = clientContext(true);
      connection =
          TcpClient.create()
              .host("127.0.0.1")
              .port(port)
              .secure(spec -> spec.sslContext(context))
              .connectNow();
      waitForAcceptedConnection(listener);
      assertThat(connection.isDisposed()).isFalse();

      Instant deadline = Instant.now().plusMillis(750);
      while (listener.isRunning() && Instant.now().isBefore(deadline)) {
        Thread.sleep(25);
      }

      assertThat(listener.isRunning()).isFalse();
      waitForConnectionDisposal(connection);
      assertThat(connection.isDisposed()).isTrue();
    } finally {
      if (connection != null) {
        connection.disposeNow();
      }
      listener.stop();
    }
  }

  private static void waitForAcceptedConnection(TcpProxyTlsListener listener)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (listener.acceptedConnectionCount() == 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
    assertThat(listener.acceptedConnectionCount()).isPositive();
  }

  private static void waitForConnectionDisposal(Connection connection) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (!connection.isDisposed() && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
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
