package net.firedevops.firemud.websocket;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SpringBootTest(
    classes = net.firedevops.firemud.SpringCloudGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.flyway.enabled=false",
        "firemud.database.enabled=false",
        "spring.cloud.gateway.default-filters[0]=",
        "grpc.server.security.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
        "spring.main.web-application-type=reactive"
    })
@ActiveProfiles("dev")
class DevEchoWebSocketIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private ReactorNettyWebSocketClient client;

  @Test
  void echoesPayloads() {
    URI uri = URI.create("ws://localhost:" + port + "/dev/echo");
    Sinks.One<String> replySink = Sinks.one();

    Mono<Void> sessionMono =
        client.execute(
            uri,
            session ->
                session
                    .send(Mono.just(session.textMessage("hello")))
                    .then(
                        session
                            .receive()
                            .take(1)
                            .map(message -> message.getPayloadAsText())
                            .doOnNext(replySink::tryEmitValue)
                            .then()));

    Mono<String> response =
        sessionMono
            .onErrorResume(
                ex -> {
                  replySink.tryEmitError(ex);
                  return Mono.empty();
                })
            .then(replySink.asMono())
            .timeout(Duration.ofSeconds(5));

    StepVerifier.create(response).expectNext("hello").verifyComplete();
  }

  @TestConfiguration
  static class ClientConfig {
    @Bean
    ReactorNettyWebSocketClient reactorNettyWebSocketClient() {
      return new ReactorNettyWebSocketClient();
    }
  }
}
