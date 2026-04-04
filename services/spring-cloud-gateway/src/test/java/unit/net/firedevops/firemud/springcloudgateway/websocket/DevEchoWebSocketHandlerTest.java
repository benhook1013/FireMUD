package net.firedevops.firemud.springcloudgateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;

class DevEchoWebSocketHandlerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void openLoggingContextAddsRuntimeIdentityAndSessionCorrelation() {
    DevEchoWebSocketHandler handler =
        new DevEchoWebSocketHandler(
            new SimpleMeterRegistry(),
            new RuntimeIdentity(
                "spring-cloud-gateway", "gateway-test", null, Instant.EPOCH, null, null, null));
    WebSocketSession session = mock(WebSocketSession.class);
    HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
    when(session.getId()).thenReturn("session-42");
    when(session.getHandshakeInfo()).thenReturn(handshakeInfo);

    try (var ignored = handler.openLoggingContext(session)) {
      assertThat(MDC.get("service")).isEqualTo("spring-cloud-gateway");
      assertThat(MDC.get("serviceInstanceId")).isEqualTo("gateway-test");
      assertThat(MDC.get("correlationId")).isEqualTo("session-42");
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
