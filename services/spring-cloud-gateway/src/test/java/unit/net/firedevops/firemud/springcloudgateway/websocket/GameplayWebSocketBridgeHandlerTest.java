package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.config.GameplayWebSocketBridgeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

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
}
