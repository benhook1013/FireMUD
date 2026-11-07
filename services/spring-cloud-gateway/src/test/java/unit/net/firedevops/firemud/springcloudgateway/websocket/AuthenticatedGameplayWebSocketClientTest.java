package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import reactor.netty.http.server.HttpServer;

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
                    client.execute(
                        new HttpHeaders(), socket -> reactor.core.publisher.Mono.empty()))
            .isInstanceOf(IllegalStateException.class);
      }
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
