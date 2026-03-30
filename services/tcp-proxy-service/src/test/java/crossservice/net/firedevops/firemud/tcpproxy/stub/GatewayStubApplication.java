package net.firedevops.firemud.tcpproxy.stub;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Mono;

/**
 * Minimal WebFlux application that exposes only the /ws/game WebSocket route required by the tcp
 * proxy cross-service test. Incoming WebSocket traffic is proxied to the configured target URI so
 * the test can exercise the Telnet -> Gateway -> Game Session chain without pulling in the full
 * Spring Cloud Gateway stack.
 */
@SpringBootConfiguration
@ConditionalOnProperty("gateway.stub.target-uri")
@EnableAutoConfiguration(
    excludeName = {
      "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration",
      "org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration",
      "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration",
      "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
    })
public class GatewayStubApplication {

  @Bean
  ReactorNettyWebSocketClient gatewayStubWebSocketClient() {
    return new ReactorNettyWebSocketClient();
  }

  @Bean
  WebSocketHandler gatewayStubWebSocketHandler(
      ReactorNettyWebSocketClient client, @Value("${gateway.stub.target-uri}") String targetUri) {
    return new ProxyingWebSocketHandler(client, URI.create(targetUri));
  }

  @Bean
  SimpleUrlHandlerMapping gatewayStubHandlerMapping(WebSocketHandler handler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setOrder(-1);
    mapping.setUrlMap(Map.of("/ws/game", handler, "/ws/game/**", handler));
    return mapping;
  }

  @Bean
  WebSocketHandlerAdapter gatewayStubWebSocketHandlerAdapter() {
    return new WebSocketHandlerAdapter();
  }

  @Bean("gameplayRouteReadiness")
  HealthIndicator gameplayRouteReadinessHealthIndicator() {
    return () -> Health.up().withDetail("stub", "UP").build();
  }

  @Bean("trafficAdmissionReadiness")
  HealthIndicator trafficAdmissionReadinessHealthIndicator() {
    return () -> Health.up().withDetail("stub", "UP").build();
  }

  private static final List<String> FORWARDED_HEADER_NAMES =
      List.of("X-Game-Instance-Id", "X-Tenant-Id", "X-Client-IP");

  private static final class ProxyingWebSocketHandler implements WebSocketHandler {
    private final WebSocketClient client;
    private final URI targetUri;

    private ProxyingWebSocketHandler(WebSocketClient client, URI targetUri) {
      this.client = client;
      this.targetUri = targetUri;
    }

    @Override
    public Mono<Void> handle(WebSocketSession inbound) {
      HttpHeaders outboundHeaders = new HttpHeaders();
      HttpHeaders inboundHeaders = inbound.getHandshakeInfo().getHeaders();
      FORWARDED_HEADER_NAMES.forEach(
          headerName -> {
            var values = inboundHeaders.get(headerName);
            if (values != null) {
              values.forEach(value -> outboundHeaders.add(headerName, value));
            }
          });

      return client.execute(
          targetUri,
          outboundHeaders,
          outbound -> {
            Mono<Void> inboundToTarget =
                outbound
                    .send(
                        inbound
                            .receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .map(outbound::textMessage))
                    .then(propagateClose(inbound, outbound));

            Mono<Void> targetToInbound =
                inbound
                    .send(
                        outbound
                            .receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .map(inbound::textMessage))
                    .then(propagateClose(outbound, inbound));

            return Mono.when(inboundToTarget, targetToInbound);
          });
    }

    private Mono<Void> propagateClose(WebSocketSession from, WebSocketSession to) {
      return from.closeStatus().defaultIfEmpty(CloseStatus.NORMAL).flatMap(to::close);
    }
  }
}
