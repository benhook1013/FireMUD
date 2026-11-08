package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import net.firedevops.firemud.springcloudgateway.filter.VerifiedTcpProxyOrigin;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

class AuthenticatedGameplayWebSocketClientTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Authenticated gameplay test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "gateway", "spiffe://firemud/ns/test/sa/spring-cloud-gateway", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "session", "spiffe://firemud/ns/test/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-service", "spiffe://firemud/ns/test/sa/account-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-namespace", "spiffe://firemud/ns/other/sa/game-session-service", true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-client", "spiffe://firemud/ns/test/sa/account-service", false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-client", "spiffe://firemud/ns/other/sa/spring-cloud-gateway", false));
  }

  @Test
  void exactGameSessionPeerCompletesRealMutualTlsWebSocketUpgrade() throws Exception {
    var requests = new AtomicInteger();
    exchange("session", requests, true);
    assertThat(requests).hasValue(1);
  }

  @Test
  void wrongWorkloadOrNamespaceReceivesNoUpgradeOrOriginHeaders() throws Exception {
    for (String identity : java.util.List.of("wrong-service", "wrong-namespace")) {
      var requests = new AtomicInteger();
      exchange(identity, requests, false);
      assertThat(requests).hasValue(0);
    }
  }

  @Test
  void localClientRequiresItsDedicatedGatewayIdentityAndClientUsage() throws Exception {
    for (String identity : java.util.List.of("session", "wrong-client", "foreign-client")) {
      try (var client =
          new AuthenticatedGameplayWebSocketClient(
              pki.clientProperties(directory, pki.identity(identity)),
              "test",
              "wss://localhost:8444/ws/game/tcp")) {
        assertThatThrownBy(client::initialize).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () ->
                    client
                        .execute(new HttpHeaders(), socket -> Mono.empty())
                        .block(Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class);
      }
    }
  }

  @Test
  void executionSignalsUnavailableWhenClosedBeforeOrAfterAssembly() throws Exception {
    try (var client =
        new AuthenticatedGameplayWebSocketClient(
            pki.clientProperties(directory, pki.identity("gateway")),
            "test",
            "wss://localhost:8444/ws/game/tcp")) {
      client.initialize();
      Mono<Void> assembled = client.execute(new HttpHeaders(), socket -> Mono.empty());
      client.close();

      for (Mono<Void> execution :
          List.of(assembled, client.execute(new HttpHeaders(), socket -> Mono.empty()))) {
        StepVerifier.create(execution)
            .expectErrorMatches(
                error ->
                    error instanceof IllegalStateException
                        && "Authenticated gameplay client unavailable".equals(error.getMessage()))
            .verify(Duration.ofSeconds(5));
      }
    }
  }

  @Test
  void closedClientFollowsBridgeCloseAndReleasesActiveRegistration() throws Exception {
    try (var client =
        new AuthenticatedGameplayWebSocketClient(
            pki.clientProperties(directory, pki.identity("gateway")),
            "test",
            "wss://localhost:8444/ws/game/tcp")) {
      client.initialize();
      client.close();
      ReactorNettyWebSocketClient ordinaryClient = mock(ReactorNettyWebSocketClient.class);
      WebSocketSession downstream = mock(WebSocketSession.class);
      HandshakeInfo handshake = mock(HandshakeInfo.class);
      VerifiedTcpProxyOrigin origin = mock(VerifiedTcpProxyOrigin.class);
      when(origin.clientAddress()).thenReturn("203.0.113.99");
      when(origin.proxyConnectionId())
          .thenReturn(UUID.fromString("9fb38c88-2f1d-42aa-8e0b-7eb69caf8814"));
      when(downstream.getAttributes())
          .thenReturn(Map.of(VerifiedTcpProxyOrigin.class.getName(), origin));
      when(downstream.getId()).thenReturn("closed-client-downstream");
      when(downstream.getHandshakeInfo()).thenReturn(handshake);
      when(handshake.getHeaders()).thenReturn(new HttpHeaders());
      Sinks.Empty<Void> closed = Sinks.empty();
      when(downstream.send(any())).thenReturn(closed.asMono());
      when(downstream.receive())
          .thenReturn(closed.asMono().thenMany(Flux.<WebSocketMessage>empty()));
      when(downstream.close(any(CloseStatus.class)))
          .thenAnswer(
              invocation -> {
                closed.tryEmitEmpty();
                return Mono.empty();
              });
      GameplayWebSocketBridgeHandler bridge =
          new GameplayWebSocketBridgeHandler(
              ordinaryClient,
              new GameplayWebSocketBridgeProperties(null, 1, 1L, 128),
              new RuntimeIdentity(
                  "spring-cloud-gateway", "gateway-test", null, Instant.EPOCH, null, null, null),
              GameplayWebSocketObservability.disabled(),
              client);

      StepVerifier.withVirtualTime(() -> bridge.handle(downstream))
          .thenAwait(Duration.ofMillis(2))
          .expectComplete()
          .verify(Duration.ofSeconds(5));

      verify(downstream).close(new CloseStatus(1013, "backend_unavailable"));
      assertThat(ReflectionTestUtils.getField(bridge, "activeBridges")).isEqualTo(Set.of());
      verifyNoInteractions(ordinaryClient);
    }
  }

  private void exchange(String identity, AtomicInteger requests, boolean accepted)
      throws Exception {
    var serverIdentity = pki.identity(identity);
    var tls =
        SslContextBuilder.forServer(
                serverIdentity.privateKey(), serverIdentity.certificate(), pki.caCertificate())
            .trustManager(pki.trustManagerFactory())
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    var server =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .secure(spec -> spec.sslContext(tls))
            .handle(
                (request, response) -> {
                  requests.incrementAndGet();
                  return response.sendWebsocket(
                      (inbound, outbound) ->
                          outbound.sendString(reactor.core.publisher.Mono.just("ready")));
                })
            .bindNow();
    try (var client =
        new AuthenticatedGameplayWebSocketClient(
            pki.clientProperties(directory, pki.identity("gateway")),
            "test",
            "wss://localhost:" + server.port() + "/ws/game/tcp")) {
      client.initialize();
      var headers = new HttpHeaders();
      headers.set("X-Client-IP", "203.0.113.7");
      Runnable call =
          () ->
              client
                  .execute(headers, socket -> socket.receive().next().then())
                  .block(Duration.ofSeconds(5));
      if (accepted) call.run();
      else assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);
    } finally {
      server.disposeNow();
    }
  }
}
