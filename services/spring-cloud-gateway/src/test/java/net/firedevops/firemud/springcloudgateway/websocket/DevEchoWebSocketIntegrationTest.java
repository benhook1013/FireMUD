package net.firedevops.firemud.springcloudgateway.websocket;

import java.net.URI;
import java.time.Duration;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SpringBootTest(
    classes = net.firedevops.firemud.springcloudgateway.SpringCloudGatewayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.flyway.enabled=false",
      "firemud.database.enabled=false",
      GatewayTestProperties.SPRING_GRPC_SERVER_RANDOM_PORT,
      GatewayTestProperties.DISABLE_GATEWAY_WARNING_GRPC_SERVER_AND_SERVLET,
      GatewayTestProperties.REACTIVE_WEB_APPLICATION
    })
@ActiveProfiles("test")
@Import(NoGrpcServerTestConfiguration.class)
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

  @Test
  void forwardsClientIpHeaderDuringWebSocketUpgrade() {
    URI uri = URI.create("ws://localhost:" + port + "/dev/echo");
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Client-IP", "203.0.113.9");
    Sinks.One<String> replySink = Sinks.one();

    Mono<Void> sessionMono =
        client.execute(
            uri,
            headers,
            session ->
                session
                    .send(Mono.just(session.textMessage("client-ip")))
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

    StepVerifier.create(response)
        .expectNextMatches(ip -> "127.0.0.1".equals(ip) || "::1".equals(ip))
        .verifyComplete();
  }

  @TestConfiguration
  static class ClientConfig {
    @Bean
    ReactorNettyWebSocketClient reactorNettyWebSocketClient() {
      return new ReactorNettyWebSocketClient();
    }
  }
}
