package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
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
    int port = freePort();
    GatewayTcpProxyListenerProperties properties = tlsProperties(port);
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            properties,
            new GatewayHeaderTrustProperties(),
            8080,
            Clock.systemUTC(),
            Set.of("test"));
    HttpHandler handler =
        (request, response) -> {
          response.setStatusCode(HttpStatus.NO_CONTENT);
          return response.setComplete();
        };
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, handler);

    try {
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      assertThat(listener.boundPort()).isEqualTo(port);

      assertThat(requestStatus(port, clientContext(true), "/actuator/health/liveness"))
          .isEqualTo(HttpStatus.NO_CONTENT.value());
      assertThatThrownBy(
              () -> requestStatus(port, clientContext(false), "/actuator/health/liveness"))
          .isInstanceOf(RuntimeException.class);
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
    MockServerHttpResponse publicResponse = new MockServerHttpResponse();
    handler.handle(MockServerHttpRequest.get("/ping").build(), publicResponse).block();
    assertThat(delegated).isFalse();
    assertThat(publicResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void expiringTrustProfileTerminatesListenerAndExistingBridges() throws Exception {
    int port = freePort();
    GatewayTcpProxyListenerProperties properties = tlsProperties(port);
    properties
        .getBreakglassFingerprint()
        .setExpiresAt(Instant.now().plus(Duration.ofMillis(750)).toString());
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            properties,
            new GatewayHeaderTrustProperties(),
            8080,
            Clock.systemUTC(),
            Set.of("test"));
    HttpHandler handler = (request, response) -> response.setComplete();
    TcpProxyTlsListener listener = new TcpProxyTlsListener(properties, policy, handler);
    Connection connection = null;

    try {
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      SslContext context = clientContext(true);
      connection =
          TcpClient.create()
              .host("127.0.0.1")
              .port(port)
              .secure(spec -> spec.sslContext(context))
              .connectNow();
      waitForAcceptedConnection(listener);
      assertThat(connection.isDisposed()).isFalse();

      Instant deadline = Instant.now().plusSeconds(5);
      while (listener.isRunning() && Instant.now().isBefore(deadline)) {
        Thread.sleep(25);
      }

      assertThat(listener.isRunning()).isFalse();
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
    Instant deadline = Instant.now().plusSeconds(2);
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
            .block());
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
    return workingDirectory
        .resolve("services/common-test-support/src/testFixtures/resources/certs")
        .resolve(name)
        .normalize();
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
