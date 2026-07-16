package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GameplayWebSocketBridgeHandlerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void openLoggingContextPrefersTransportSessionHeader() {
    GameplayWebSocketBridgeHandler handler =
        new GameplayWebSocketBridgeHandler(
            mock(ReactorNettyWebSocketClient.class),
            new GameplayWebSocketBridgeProperties(
                "ws://game-session-service:8080/ws/game", 2, 50L, 128),
            new RuntimeIdentity(
                "spring-cloud-gateway", "gateway-test", null, Instant.EPOCH, null, null, null));
    WebSocketSession session = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Firemud-Transport-Session-Id", "9001");
    when(session.getId()).thenReturn("session-fallback");
    when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);

    try (var ignored = handler.openLoggingContext(session)) {
      assertThat(MDC.get("service")).isEqualTo("spring-cloud-gateway");
      assertThat(MDC.get("serviceInstanceId")).isEqualTo("gateway-test");
      assertThat(MDC.get("correlationId")).isEqualTo("9001");
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void actualBridgeCloseRecordsPlannedDrainAttribution() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();
    CloseStatus plannedDrain = new CloseStatus(1000, "logout;subreason=gateway_restart");

    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any())).thenReturn(Mono.never());
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(Mono.empty());
    when(upstream.send(any())).thenReturn(Mono.empty());
    when(upstream.receive()).thenReturn(Flux.empty());
    when(upstream.closeStatus()).thenReturn(Mono.just(plannedDrain));
    when(client.execute(any(URI.class), any(HttpHeaders.class), any(WebSocketHandler.class)))
        .thenAnswer(
            invocation -> {
              WebSocketHandler upstreamHandler = invocation.getArgument(2);
              return upstreamHandler.handle(upstream);
            });

    GameplayWebSocketBridgeHandler handler =
        new GameplayWebSocketBridgeHandler(
            client,
            new GameplayWebSocketBridgeProperties(
                "ws://game-session-service:8080/ws/game", 0, 50L, 128),
            new RuntimeIdentity(
                "spring-cloud-gateway", "gateway-test", null, Instant.EPOCH, null, null, null),
            observability);

    StepVerifier.create(handler.handle(downstream))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .thenCancel()
        .verify();

    ArgumentCaptor<CloseStatus> closeStatus = ArgumentCaptor.forClass(CloseStatus.class);
    verify(downstream).close(closeStatus.capture());
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1000);
    assertThat(closeStatus.getValue().getReason()).isEqualTo("logout;subreason=gateway_restart");
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "logout",
                    "subreason",
                    "gateway_restart",
                    "bridge_shutdown_class",
                    "planned_drain")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
