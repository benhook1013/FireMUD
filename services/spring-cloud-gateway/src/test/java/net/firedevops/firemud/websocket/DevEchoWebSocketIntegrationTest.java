package net.firedevops.firemud.websocket;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
      "grpc.server.security.enabled=false",
      "grpc.server.port=0",
      "spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration,org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration,org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
      "spring.main.web-application-type=reactive"
    })
@ActiveProfiles("test")
class DevEchoWebSocketIntegrationTest {

  @LocalServerPort private int port;

  @MockitoBean private org.lognet.springboot.grpc.GRpcServerRunner grpcServerRunner;
  @MockitoBean private org.lognet.springboot.grpc.GRpcServicesRegistry grpcServicesRegistry;

  @MockitoBean
  private org.lognet.springboot.grpc.health.ManagedHealthStatusService managedHealthStatusService;

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
