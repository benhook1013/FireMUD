package net.firedevops.firemud.springcloudgateway.filter;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import net.firedevops.firemud.springcloudgateway.config.TcpProxyTlsListener;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.http.client.HttpClient;

class TcpProxyTlsListenerAdmissionIntegrationTest {
  private static final String TCP_PROXY_URI = "spiffe://firemud/ns/firemud/sa/tcp-proxy-service";

  @Test
  void productionConfigurationMapsHealthAndReadinessWhenInternalTlsIsDisabled() {
    SpringApplication application = new SpringApplication(SpringCloudGatewayApplication.class);
    application.setWebApplicationType(WebApplicationType.REACTIVE);

    try (ConfigurableApplicationContext context =
        application.run(
            "--server.port=0",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.flyway.enabled=false",
            "--spring.cloud.gateway.server.webflux.default-filters=",
            "--firemud.database.enabled=false",
            "--firemud.gateway.gameplay.bridge.upstream-url=ws://127.0.0.1:0/ws/game",
            "--firemud.gateway.tcp-proxy-listener.enabled=false",
            "--management.endpoint.health.show-details=always",
            "--firemud.auth.jwt-secret=test-secret-for-gateway-health-mapping",
            "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration")) {
      int port = requireNonNull(((WebServerApplicationContext) context).getWebServer()).getPort();
      WebTestClient client =
          WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();

      client
          .get()
          .uri("/actuator/health")
          .accept(MediaType.APPLICATION_JSON)
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
          .expectBody()
          .jsonPath("$.status")
          .exists();
      client
          .get()
          .uri("/actuator/health/readiness")
          .accept(MediaType.APPLICATION_JSON)
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
          .expectBody()
          .jsonPath("$.status")
          .isEqualTo("OUT_OF_SERVICE")
          .jsonPath("$.components.gameplayRouteReadiness.status")
          .isEqualTo("OUT_OF_SERVICE")
          .jsonPath("$.components.tcpProxyTlsListenerReadiness.status")
          .isEqualTo("UP")
          .jsonPath("$.components.tcpProxyTlsListenerReadiness.details.listener")
          .isEqualTo("disabled");
    }
  }

  @Test
  void websocketUpgradePromotesOnlyTheConfiguredClientWorkload() throws Exception {
    int port = freePort();
    GatewayTcpProxyListenerProperties listenerProperties = listenerProperties(port);
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    TcpProxyTrustPolicy trustPolicy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), Set.of("prod"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(headerProperties, trustPolicy);
    GameplayHandshakeFilter handshakeFilter =
        new GameplayHandshakeFilter(
            mock(JwtUtil.class),
            mock(RuntimeIdentity.class),
            null,
            new MockEnvironment().withProperty("spring.profiles.active", "test"),
            GameplayWebSocketObservability.disabled());
    AtomicReference<HttpHeaders> admittedHeaders = new AtomicReference<>();
    AtomicInteger admittedConnections = new AtomicInteger();

    WebSocketHandler gameplayHandler =
        session -> {
          admittedHeaders.set(session.getHandshakeInfo().getHeaders());
          admittedConnections.incrementAndGet();
          return session.send(reactor.core.publisher.Mono.just(session.textMessage("admitted")));
        };
    GenericApplicationContext context = new GenericApplicationContext();
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    mapping.setUrlMap(Map.of("/ws/game", gameplayHandler));
    context.getBeanFactory().registerSingleton("gameplayMapping", mapping);
    context
        .getBeanFactory()
        .registerSingleton("gameplayWebSocketHandlerAdapter", new WebSocketHandlerAdapter());
    context.refresh();
    mapping.setApplicationContext(context);

    HttpHandler handler =
        WebHttpHandlerBuilder.webHandler(new DispatcherHandler(context))
            .filter(headerTrustFilter, handshakeFilter)
            .build();
    TcpProxyTlsListener listener =
        new TcpProxyTlsListener(listenerProperties, trustPolicy, handler);

    try {
      listener.start();
      HttpHeaders bridgeHeaders = bridgeHeaders();

      assertThat(
              connect(
                  port,
                  bridgeHeaders,
                  clientContext("tcp-proxy-client.pem", "tcp-proxy-client-key.pem")))
          .isEqualTo("admitted");
      assertThat(admittedConnections).hasValue(1);
      assertThat(admittedHeaders.get().getFirst("X-Client-IP")).isEqualTo("203.0.113.99");
      assertThat(admittedHeaders.get().getFirst("X-Proxy-Client-IP")).isNull();
      assertThat(admittedHeaders.get().getFirst("X-Proxy-Connection-Id")).isEqualTo("conn-123");
      assertThat(admittedHeaders.get().getFirst("X-Tenant-Id")).isEqualTo("7");
      assertThat(admittedHeaders.get().getFirst("X-Game-Instance-Id")).isEqualTo("42");
      assertThat(admittedHeaders.get().getFirst("X-Firemud-Connection-Mode"))
          .isEqualTo("trusted_tcp_proxy");

      assertThatThrownBy(
              () ->
                  connect(
                      port,
                      bridgeHeaders,
                      clientContext("wrong-client.pem", "wrong-client-key.pem")))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("403 Forbidden");
      assertThat(admittedConnections).hasValue(1);
    } finally {
      listener.stop();
      context.close();
    }
  }

  private static HttpHeaders bridgeHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Proxy-Client-IP", "203.0.113.99");
    headers.set("X-Proxy-Connection-Id", "conn-123");
    headers.set("X-Proxy-Tenant-Id", "7");
    headers.set("X-Proxy-Game-Instance-Id", "42");
    return headers;
  }

  private static String connect(int port, HttpHeaders headers, SslContext sslContext) {
    AtomicReference<String> response = new AtomicReference<>();
    ReactorNettyWebSocketClient client =
        new ReactorNettyWebSocketClient(
            HttpClient.create().secure(spec -> spec.sslContext(sslContext)));
    client
        .execute(
            URI.create("wss://127.0.0.1:" + port + "/ws/game"),
            headers,
            session ->
                session
                    .receive()
                    .next()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(response::set)
                    .then())
        .block(Duration.ofSeconds(5));
    return response.get();
  }

  private static SslContext clientContext(String certificate, String privateKey) throws Exception {
    return SslContextBuilder.forClient()
        .trustManager(commonFixture("dev-ca.pem").toFile())
        .keyManager(gatewayFixture(certificate).toFile(), gatewayFixture(privateKey).toFile())
        .build();
  }

  private static GatewayTcpProxyListenerProperties listenerProperties(int port) {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setBindAddress("127.0.0.1");
    properties.setPort(port);
    properties.setCertificateChainPath(commonFixture("dev-cert.pem").toString());
    properties.setPrivateKeyPath(commonFixture("dev-key.pem").toString());
    properties.setTrustedClientCaPath(gatewayFixture("tcp-proxy-client-ca.pem").toString());
    properties.setEnvironment("production");
    properties.setTrustProfile("production_uri");
    properties.getProductionUri().setUriSan(TCP_PROXY_URI);
    return properties;
  }

  private static Path gatewayFixture(String name) {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path moduleLocal =
        workingDirectory.resolve("src/test/resources/certs").resolve(name).normalize();
    if (Files.isRegularFile(moduleLocal)) {
      return moduleLocal;
    }
    return workingDirectory
        .resolve("services/spring-cloud-gateway/src/test/resources/certs")
        .resolve(name)
        .normalize();
  }

  private static Path commonFixture(String name) {
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
