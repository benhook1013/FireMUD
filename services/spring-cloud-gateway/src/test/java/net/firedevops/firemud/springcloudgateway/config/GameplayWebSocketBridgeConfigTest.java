package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.filter.HeaderTrustFilter;
import net.firedevops.firemud.springcloudgateway.filter.TcpProxyTrustPolicy;
import net.firedevops.firemud.springcloudgateway.filter.VerifiedTcpProxyOrigin;
import net.firedevops.firemud.springcloudgateway.websocket.AuthenticatedGameplayWebSocketClient;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketBridgeHandler;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;

class GameplayWebSocketBridgeConfigTest {

  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withUserConfiguration(
              GameplayWebSocketBridgeConfig.class, TestRuntimeIdentityConfig.class)
          .withBean(
              GameplayWebSocketBridgeProperties.class,
              () -> new GameplayWebSocketBridgeProperties(null, 0, 0, 0))
          .withBean(
              GameplayWebSocketObservability.class,
              () -> new GameplayWebSocketObservability(new SimpleMeterRegistry()));

  @Test
  void gameplayWebSocketPathIsOwnedByGatewayCode() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(GameplayWebSocketBridgeHandler.class);
          assertThat(context).hasSingleBean(SimpleUrlHandlerMapping.class);
          assertThat(context).hasSingleBean(WebSocketHandlerAdapter.class);

          SimpleUrlHandlerMapping mapping = context.getBean(SimpleUrlHandlerMapping.class);
          assertThat(mapping.getUrlMap()).containsKeys("/ws/game", "/ws/game/**");
          assertThat(mapping.getUrlMap().get("/ws/game"))
              .isInstanceOf(WebHandler.class)
              .isSameAs(mapping.getUrlMap().get("/ws/game/**"));
        });
  }

  @Test
  void authenticatedGameplayClientIsOmittedForUnsetBlankOrExplicitFalseEndpoint() {
    contextRunner.run(
        context -> assertThat(context).doesNotHaveBean(AuthenticatedGameplayWebSocketClient.class));

    for (String endpoint : new String[] {"", " \t ", "false"}) {
      contextRunner
          .withInitializer(
              applicationContext ->
                  applicationContext
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          new MapPropertySource(
                              "test-authenticated-upstream",
                              Map.of(
                                  "firemud.gateway.gameplay.bridge.authenticated-upstream-url",
                                  endpoint))))
          .run(
              context ->
                  assertThat(context)
                      .as("endpoint=%s", endpoint)
                      .doesNotHaveBean(AuthenticatedGameplayWebSocketClient.class));
    }
  }

  @Test
  void malformedNonblankAuthenticatedEndpointFailsFast(@TempDir Path dir) throws Exception {
    Path certificate = Files.createFile(dir.resolve("client.pem"));
    Path privateKey = Files.createFile(dir.resolve("client.key"));
    Path caCertificate = Files.createFile(dir.resolve("ca.pem"));

    contextRunner
        .withPropertyValues(
            "firemud.grpc.workload-namespace=test",
            "firemud.gateway.gameplay.bridge.authenticated-upstream-url=http://game-session:8444/ws/game/tcp",
            "firemud.gateway.gameplay.bridge.tls.cert-chain=" + certificate,
            "firemud.gateway.gameplay.bridge.tls.private-key=" + privateKey,
            "firemud.gateway.gameplay.bridge.tls.ca-cert=" + caCertificate)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("Dedicated authenticated gameplay wss endpoint required"));
  }

  @Test
  void gameplayWebSocketMappingUsesPathPatternSemanticsForMatrixParameters() {
    contextRunner.run(
        context -> {
          SimpleUrlHandlerMapping mapping = context.getBean(SimpleUrlHandlerMapping.class);
          Object handler = mapping.getUrlMap().get("/ws/game");
          assertThat(handler).isInstanceOf(WebHandler.class);

          for (String path :
              new String[] {"/ws/game;probe", "/ws;probe/game", "/ws/game;probe/child"}) {
            assertThat(
                    mapping
                        .getHandler(
                            MockServerWebExchange.from(MockServerHttpRequest.get(path).build()))
                        .block())
                .as("path=%s", path)
                .isSameAs(handler);
          }

          assertThat(
                  mapping
                      .getHandler(
                          MockServerWebExchange.from(
                              MockServerHttpRequest.get("/ws/game%3Bprobe").build()))
                      .block())
              .as("encoded semicolon is not a matrix parameter accepted by PathPattern")
              .isNull();
        });
  }

  @Test
  void mappedWebHandlerBindsOnlyVerifiedTcpProxyOriginBeforeDelegatingUpgrade() throws Exception {
    GameplayWebSocketBridgeHandler handler = mock(GameplayWebSocketBridgeHandler.class);
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(new HashMap<>());
    AtomicReference<VerifiedTcpProxyOrigin> received = new AtomicReference<>();
    when(handler.handle(session))
        .thenAnswer(
            invocation -> {
              received.set(VerifiedTcpProxyOrigin.from(session));
              return Mono.empty();
            });
    X509Certificate certificate;
    try (InputStream input =
        Objects.requireNonNull(getClass().getResourceAsStream("/certs/tcp-proxy-client.pem"))) {
      certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    long notBefore = certificate.getNotBefore().getTime();
    long notAfter = certificate.getNotAfter().getTime();
    Clock clock =
        Clock.fixed(Instant.ofEpochMilli(notBefore + (notAfter - notBefore) / 2), ZoneOffset.UTC);
    GatewayTcpProxyListenerProperties listener = new GatewayTcpProxyListenerProperties();
    listener.setEnabled(true);
    listener.setPort(8443);
    listener.setCertificateChainPath("server.crt");
    listener.setPrivateKeyPath("server.key");
    listener.setTrustedClientCaPath("client-ca.crt");
    listener.setEnvironment("production");
    listener.setTrustProfile("production_uri");
    listener.getProductionUri().setUriSan("spiffe://firemud/ns/firemud/sa/tcp-proxy-service");
    GatewayHeaderTrustProperties headers = new GatewayHeaderTrustProperties();
    HeaderTrustFilter trust =
        new HeaderTrustFilter(
            headers, new TcpProxyTrustPolicy(listener, headers, 8080, clock, Set.of("test")));
    // SslInfo models the authenticated TLS peer; the actual trust policy and filter
    // qualify it and produce the private origin carried by the mapping's upgrade wrapper.
    SslInfo peer = mock(SslInfo.class);
    when(peer.getPeerCertificates()).thenReturn(new X509Certificate[] {certificate});
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game")
            .localAddress(new InetSocketAddress("127.0.0.1", 8443))
            .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
            .sslInfo(peer)
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814")
            .build();

    try (MockedConstruction<HandshakeWebSocketService> upgrades =
        mockConstruction(
            HandshakeWebSocketService.class,
            (upgrade, construction) ->
                when(upgrade.handleRequest(any(), any()))
                    .thenAnswer(
                        invocation ->
                            invocation.<WebSocketHandler>getArgument(1).handle(session)))) {
      SimpleUrlHandlerMapping mapping =
          (SimpleUrlHandlerMapping)
              new GameplayWebSocketBridgeConfig().gameplayWebSocketBridgeMapping(handler);
      WebHandler bridge = (WebHandler) mapping.getUrlMap().get("/ws/game");

      trust.filter(MockServerWebExchange.from(request), bridge::handle).block();
      assertThat(received.get()).isNotNull();
      assertThat(received.get().clientAddress()).isEqualTo("203.0.113.99");
      assertThat(received.get().proxyConnectionId().toString())
          .isEqualTo("9fb38c88-2f1d-42aa-8e0b-7eb69caf8814");

      // Identical headers without the filter's private proof cannot inherit the prior
      // upgraded session's origin, even when the same controlled session is reused.
      bridge.handle(MockServerWebExchange.from(request)).block();
      assertThat(received.get()).isNull();
      assertThat(VerifiedTcpProxyOrigin.from(session)).isNull();
      assertThat(upgrades.constructed()).hasSize(1);
      verify(upgrades.constructed().getFirst(), times(2)).handleRequest(any(), any());
      verify(handler, times(2)).handle(session);
    }
  }

  @Configuration
  static class TestRuntimeIdentityConfig {
    @Bean
    RuntimeIdentity runtimeIdentity() {
      return new RuntimeIdentity(
          "spring-cloud-gateway", "test-instance", "localhost", Instant.EPOCH, null, null, null);
    }
  }
}
