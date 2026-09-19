package net.firedevops.firemud.springcloudgateway.filter;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import net.firedevops.firemud.springcloudgateway.config.TcpProxyTlsListener;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import net.firedevops.firemud.test.TlsTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.Disposable;
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
            "--spring.config.additional-location=" + mainApplicationConfig().toUri(),
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
          .jsonPath("$.components.tcpProxyTlsListenerReadiness")
          .doesNotExist();
      client
          .get()
          .uri("/actuator/health/tcpProxyTlsListenerReadiness")
          .accept(MediaType.APPLICATION_JSON)
          .exchange()
          .expectStatus()
          .is2xxSuccessful()
          .expectBody()
          .jsonPath("$.status")
          .isEqualTo("UP")
          .jsonPath("$.details.listener")
          .isEqualTo("disabled");
    }
  }

  @Test
  void websocketUpgradePromotesOnlyTheConfiguredClientWorkload() throws Exception {
    GatewayTcpProxyListenerProperties listenerProperties = listenerProperties(8443);
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    AtomicReference<HttpHeaders> admittedHeaders = new AtomicReference<>();
    AtomicInteger admittedConnections = new AtomicInteger();
    AtomicInteger applicationRequests = new AtomicInteger();

    ListenerFixture fixture =
        listenerFixture(
            listenerProperties,
            headerProperties,
            Set.of("prod"),
            applicationRequests,
            session -> {
              admittedHeaders.set(session.getHandshakeInfo().getHeaders());
              admittedConnections.incrementAndGet();
              if ("no-frame"
                  .equals(
                      session.getHandshakeInfo().getHeaders().getFirst("X-Proxy-Connection-Id"))) {
                return reactor.core.publisher.Mono.never();
              }
              return session.send(
                  reactor.core.publisher.Mono.just(session.textMessage("admitted")));
            });
    try (fixture) {
      TcpProxyTlsListener listener = fixture.listener();
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      int port = listener.boundPort();
      listenerProperties.setPort(port);
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
      assertThat(admittedHeaders.get().getFirst("X-Firemud-Connect-Context")).isNull();
      assertThat(admittedHeaders.get().getFirst("X-Firemud-Connect-Token")).isNull();
      assertThat(admittedHeaders.get().getFirst("X-Firemud-Transport-Session-Id")).isNull();

      assertThatThrownBy(
              () ->
                  connect(
                      port,
                      bridgeHeaders,
                      clientContext("wrong-client.pem", "wrong-client-key.pem")))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("403 Forbidden");
      assertThat(admittedConnections).hasValue(1);
      assertThat(applicationRequests)
          .as(
              "wrong-workload certificate reaches the application handler before admission rejection")
          .hasValue(2);

      HttpHeaders noFrameHeaders = bridgeHeaders();
      noFrameHeaders.set("X-Proxy-Connection-Id", "no-frame");
      assertThatThrownBy(
              () ->
                  connect(
                      port,
                      noFrameHeaders,
                      clientContext("tcp-proxy-client.pem", "tcp-proxy-client-key.pem"),
                      Duration.ofSeconds(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Timeout on blocking read");
    }
  }

  @ParameterizedTest(name = "{0} in {1} requiresClientCertificate={2}")
  @MethodSource("enabledTrustProfiles")
  void enabledTrustProfilesConfigureTheExpectedListenerClientAuthentication(
      String profile, String environment, boolean requiresClientCertificate) throws Exception {
    GatewayTcpProxyListenerProperties listenerProperties =
        listenerProperties(8443, profile, environment);
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    Set<String> activeProfiles =
        profile.equals("development_cidr") ? Set.of("test") : Set.of("prod");
    AtomicInteger applicationRequests = new AtomicInteger();
    ListenerFixture fixture =
        listenerFixture(
            listenerProperties,
            headerProperties,
            activeProfiles,
            applicationRequests,
            session ->
                session.send(reactor.core.publisher.Mono.just(session.textMessage("admitted"))));
    try (fixture) {
      TcpProxyTlsListener listener = fixture.listener();
      listener.start();
      assertThat(listener.isRunning()).isTrue();
      int port = listener.boundPort();
      listenerProperties.setPort(port);
      assertThat(fixture.trustPolicy().requiresClientCertificate())
          .isEqualTo(requiresClientCertificate);
      if (requiresClientCertificate) {
        Throwable handshakeFailure =
            catchThrowable(() -> connect(port, bridgeHeaders(), clientContextWithoutIdentity()));
        assertThat(handshakeFailure).as("TLS handshake without a client certificate").isNotNull();
        assertThat(TlsTestSupport.isTlsHandshakeRejection(handshakeFailure))
            .as("failure must be a TLS/client-certificate handshake rejection")
            .isTrue();
        assertThat(applicationRequests)
            .as("missing client certificate must be rejected before HTTP filtering")
            .hasValue(0);
      } else {
        assertThat(connect(port, bridgeHeaders(), clientContextWithoutIdentity()))
            .isEqualTo("admitted");
        assertThat(applicationRequests).hasValue(1);
      }
    }
  }

  private static ListenerFixture listenerFixture(
      GatewayTcpProxyListenerProperties listenerProperties,
      GatewayHeaderTrustProperties headerProperties,
      Set<String> activeProfiles,
      AtomicInteger applicationRequests,
      Function<WebSocketSession, reactor.core.publisher.Mono<Void>> responseBehavior) {
    TcpProxyTrustPolicy trustPolicy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), activeProfiles);
    // Preserve the same validation-before-bind order used by both listener tests.
    listenerProperties.setPort(0);
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(headerProperties, trustPolicy);
    GameplayHandshakeFilter handshakeFilter =
        new GameplayHandshakeFilter(
            mock(JwtUtil.class),
            mock(RuntimeIdentity.class),
            null,
            new MockEnvironment().withProperty("spring.profiles.active", "test"),
            GameplayWebSocketObservability.disabled());

    WebSocketHandler gameplayHandler = responseBehavior::apply;
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

    HttpHandler filteredHandler =
        WebHttpHandlerBuilder.webHandler(new DispatcherHandler(context))
            .filter(headerTrustFilter, handshakeFilter)
            .build();
    HttpHandler handler =
        (request, response) -> {
          applicationRequests.incrementAndGet();
          return filteredHandler.handle(request, response);
        };
    return new ListenerFixture(
        new TcpProxyTlsListener(listenerProperties, trustPolicy, handler), trustPolicy, context);
  }

  private static Stream<Arguments> enabledTrustProfiles() {
    return Stream.of(
        Arguments.of("production_uri", "production", true),
        Arguments.of("migration_dns", "pr-preview", true),
        Arguments.of("breakglass_fingerprint", "dev-demo-cluster", true),
        Arguments.of("development_cidr", "isolated-test", false));
  }

  private static HttpHeaders bridgeHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Proxy-Client-IP", "203.0.113.99");
    headers.set("X-Proxy-Connection-Id", "conn-123");
    headers.set("X-Proxy-Tenant-Id", "7");
    headers.set("X-Proxy-Game-Instance-Id", "42");
    headers.set("X-Firemud-Connection-Mode", "first_party_web");
    headers.set("X-Firemud-Connect-Context", "spoofed-context");
    headers.set("X-Firemud-Connect-Token", "spoofed-token");
    headers.set("X-Firemud-Transport-Session-Id", "9001");
    return headers;
  }

  private static String connect(int port, HttpHeaders headers, SslContext sslContext) {
    return connect(port, headers, sslContext, Duration.ofSeconds(5));
  }

  private static String connect(
      int port, HttpHeaders headers, SslContext sslContext, Duration responseTimeout) {
    reactor.core.publisher.Sinks.One<String> response = reactor.core.publisher.Sinks.one();
    ReactorNettyWebSocketClient client =
        new ReactorNettyWebSocketClient(
            HttpClient.create().secure(spec -> spec.sslContext(sslContext)));
    Disposable execution =
        client
            .execute(
                URI.create("wss://127.0.0.1:" + port + "/ws/game"),
                headers,
                session ->
                    session
                        .receive()
                        .next()
                        .map(WebSocketMessage::getPayloadAsText)
                        .doOnNext(response::tryEmitValue)
                        .then())
            .subscribe(ignored -> {}, response::tryEmitError);
    try {
      return response.asMono().block(responseTimeout);
    } finally {
      execution.dispose();
    }
  }

  private static SslContext clientContext(String certificate, String privateKey) throws Exception {
    return SslContextBuilder.forClient()
        .trustManager(commonFixture("dev-ca.pem").toFile())
        .keyManager(gatewayFixture(certificate).toFile(), gatewayFixture(privateKey).toFile())
        .build();
  }

  private static SslContext clientContextWithoutIdentity() throws Exception {
    return SslContextBuilder.forClient().trustManager(commonFixture("dev-ca.pem").toFile()).build();
  }

  private static GatewayTcpProxyListenerProperties listenerProperties(int port) {
    return listenerProperties(port, "production_uri", "production");
  }

  private static GatewayTcpProxyListenerProperties listenerProperties(
      int port, String profile, String environment) {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setBindAddress("127.0.0.1");
    properties.setPort(port);
    properties.setCertificateChainPath(commonFixture("dev-cert.pem").toString());
    properties.setPrivateKeyPath(commonFixture("dev-key.pem").toString());
    properties.setTrustedClientCaPath(gatewayFixture("tcp-proxy-client-ca.pem").toString());
    properties.setEnvironment(environment);
    properties.setTrustProfile(profile);
    switch (profile) {
      case "production_uri" -> properties.getProductionUri().setUriSan(TCP_PROXY_URI);
      case "migration_dns" -> {
        properties.getMigrationDns().setDnsSan("tcp-proxy.internal");
        properties.getMigrationDns().setOwner("platform");
        properties.getMigrationDns().setReason("listener client-auth integration proof");
        properties.getMigrationDns().setExpiresAt(Instant.parse("2999-01-01T00:00:00Z"));
      }
      case "breakglass_fingerprint" -> {
        properties.getBreakglassFingerprint().setSha256("0".repeat(64));
        properties.getBreakglassFingerprint().setIncidentReference("INC-TEST");
        properties.getBreakglassFingerprint().setExpiresAt(Instant.parse("2999-01-01T00:00:00Z"));
      }
      case "development_cidr" -> {
        properties.setTrustedClientCaPath(null);
        properties.getDevelopmentCidr().setTrustedCidr("127.0.0.1/32");
      }
      default -> throw new IllegalArgumentException("unsupported test profile " + profile);
    }
    return properties;
  }

  private record ListenerFixture(
      TcpProxyTlsListener listener,
      TcpProxyTrustPolicy trustPolicy,
      GenericApplicationContext context)
      implements AutoCloseable {
    @Override
    public void close() {
      listener.stop();
      context.close();
    }
  }

  private static Path gatewayFixture(String name) {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path moduleLocal =
        workingDirectory.resolve("src/test/resources/certs").resolve(name).normalize();
    if (Files.isRegularFile(moduleLocal)) {
      return moduleLocal;
    }
    Path repositoryFixture =
        workingDirectory
            .resolve("services/spring-cloud-gateway/src/test/resources/certs")
            .resolve(name)
            .normalize();
    assertThat(repositoryFixture).as("Gateway TLS fixture %s", name).isRegularFile();
    return repositoryFixture;
  }

  private static Path mainApplicationConfig() {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    Path moduleLocal = workingDirectory.resolve("src/main/resources/application.yml").normalize();
    if (Files.isRegularFile(moduleLocal)) {
      return moduleLocal;
    }
    return workingDirectory
        .resolve("services/spring-cloud-gateway/src/main/resources/application.yml")
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
}
