package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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
  void actualLocalShutdownClosesEstablishedBridgeWithPlannedDrain() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();

    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any())).thenReturn(Mono.never());
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(Mono.empty());
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.never());
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
    AtomicBoolean shutdownComplete = new AtomicBoolean();

    StepVerifier.create(handler.handle(downstream))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .then(() -> handler.stop(() -> shutdownComplete.set(true)))
        .thenCancel()
        .verify();

    assertThat(shutdownComplete).isTrue();
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

  @Test
  void closeWriteFailureRecordsUnattributedClassificationAndCompletesStopCallback() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();

    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any())).thenReturn(Mono.never());
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class)))
        .thenReturn(Mono.error(new IllegalStateException("close write failed")));
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.never());
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
    AtomicBoolean shutdownComplete = new AtomicBoolean();

    StepVerifier.create(handler.handle(downstream))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .then(() -> handler.stop(() -> shutdownComplete.set(true)))
        .thenCancel()
        .verify();

    assertThat(shutdownComplete).isTrue();
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "logout",
                    "subreason",
                    "gateway_restart",
                    "bridge_shutdown_class",
                    "unattributed_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("gateway.websocket.closes").counters()).hasSize(1);
    verify(downstream).close(any(CloseStatus.class));
  }

  @Test
  void neverCompletingCloseIsBoundedAndRecordsUnattributedClassification() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();

    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any())).thenReturn(Mono.never());
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(Mono.never());
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.never());
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
    AtomicBoolean shutdownComplete = new AtomicBoolean();

    StepVerifier.create(handler.handle(downstream))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .then(() -> handler.stop(() -> shutdownComplete.set(true)))
        .thenAwait(Duration.ofSeconds(2))
        .thenCancel()
        .verify();

    assertThat(shutdownComplete).isTrue();
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "logout",
                    "subreason",
                    "gateway_restart",
                    "bridge_shutdown_class",
                    "unattributed_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("gateway.websocket.closes").counters()).hasSize(1);
    verify(downstream).close(any(CloseStatus.class));
  }

  @Test
  void repeatedStopCallbacksWaitForTheSamePendingCloseWrite() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();
    Sinks.One<Void> closeWrite = Sinks.one();

    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any())).thenReturn(Mono.never());
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(closeWrite.asMono());
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.never());
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
    AtomicBoolean firstShutdownComplete = new AtomicBoolean();
    AtomicBoolean secondShutdownComplete = new AtomicBoolean();

    StepVerifier.create(handler.handle(downstream))
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .then(
            () -> {
              handler.stop(() -> firstShutdownComplete.set(true));
              handler.stop(() -> secondShutdownComplete.set(true));
              assertThat(firstShutdownComplete).isFalse();
              assertThat(secondShutdownComplete).isFalse();
              closeWrite.tryEmitEmpty();
            })
        .then(
            () -> {
              assertThat(firstShutdownComplete).isTrue();
              assertThat(secondShutdownComplete).isTrue();
            })
        .thenCancel()
        .verify();

    verify(downstream, times(1)).close(any(CloseStatus.class));
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

  @Test
  void actualDownstreamBufferOverflowRecordsSlowClientClassification() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();
    WebSocketMessage firstPayload = mock(WebSocketMessage.class);
    WebSocketMessage secondPayload = mock(WebSocketMessage.class);

    when(firstPayload.getPayloadAsText()).thenReturn("first");
    when(secondPayload.getPayloadAsText()).thenReturn("second");
    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any()))
        .thenAnswer(
            invocation -> {
              Publisher<WebSocketMessage> outbound = invocation.getArgument(0);
              Flux.from(outbound)
                  .subscribe(
                      new BaseSubscriber<>() {
                        @Override
                        protected void hookOnSubscribe(Subscription subscription) {
                          // Keep the outbound sink subscribed without consuming its first item.
                        }
                      });
              return Mono.never();
            });
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(Mono.empty());
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.just(firstPayload, secondPayload));
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
                "ws://game-session-service:8080/ws/game", 0, 50L, 1),
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
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1008);
    assertThat(closeStatus.getValue().getReason())
        .isEqualTo("policy_violation;subreason=edge_backpressure");
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "policy_violation",
                    "subreason",
                    "edge_backpressure",
                    "bridge_shutdown_class",
                    "unattributed_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.get("gateway.websocket.slow_client_closes").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void liveNonOverflowDownstreamEmissionUsesInternalClassification() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayWebSocketObservability observability =
        new GameplayWebSocketObservability(meterRegistry);
    ReactorNettyWebSocketClient client = mock(ReactorNettyWebSocketClient.class);
    WebSocketSession downstream = mock(WebSocketSession.class);
    WebSocketSession upstream = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    HttpHeaders headers = new HttpHeaders();
    WebSocketMessage payload = mock(WebSocketMessage.class);

    when(payload.getPayloadAsText()).thenReturn("payload");
    when(downstream.getId()).thenReturn("downstream");
    when(downstream.getHandshakeInfo()).thenReturn(handshakeInfo);
    when(handshakeInfo.getHeaders()).thenReturn(headers);
    when(downstream.send(any()))
        .thenAnswer(
            invocation -> {
              Publisher<WebSocketMessage> outbound = invocation.getArgument(0);
              Flux.from(outbound)
                  .subscribe(
                      new BaseSubscriber<>() {
                        @Override
                        protected void hookOnSubscribe(Subscription subscription) {
                          subscription.cancel();
                        }
                      });
              return Mono.never();
            });
    when(downstream.receive()).thenReturn(Flux.never());
    when(downstream.close(any(CloseStatus.class))).thenReturn(Mono.empty());
    when(upstream.send(any())).thenReturn(Mono.never());
    when(upstream.receive()).thenReturn(Flux.just(payload));
    when(upstream.closeStatus()).thenReturn(Mono.empty());
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
    assertThat(closeStatus.getValue().getCode()).isEqualTo(1011);
    assertThat(closeStatus.getValue().getReason()).isEqualTo("internal_error");
    assertThat(
            meterRegistry
                .get("gateway.websocket.closes")
                .tags(
                    "reason",
                    "internal_error",
                    "subreason",
                    "none",
                    "bridge_shutdown_class",
                    "unattributed_failure")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("gateway.websocket.slow_client_closes").counter()).isNull();
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
