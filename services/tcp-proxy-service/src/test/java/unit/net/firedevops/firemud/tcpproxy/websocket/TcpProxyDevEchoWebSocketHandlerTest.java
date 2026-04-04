package net.firedevops.firemud.tcpproxy.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.socket.WebSocketSession;

class TcpProxyDevEchoWebSocketHandlerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void openLoggingContextAddsRuntimeIdentityAndSessionCorrelation() {
    TcpProxyDevEchoWebSocketHandler handler =
        new TcpProxyDevEchoWebSocketHandler(
            new RuntimeIdentity(
                "tcp-proxy-service", "tcp-proxy-test", null, Instant.EPOCH, null, null, null));
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("tcp-session-7");

    try (var ignored = handler.openLoggingContext(session)) {
      assertThat(MDC.get("service")).isEqualTo("tcp-proxy-service");
      assertThat(MDC.get("serviceInstanceId")).isEqualTo("tcp-proxy-test");
      assertThat(MDC.get("correlationId")).isEqualTo("tcp-session-7");
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
