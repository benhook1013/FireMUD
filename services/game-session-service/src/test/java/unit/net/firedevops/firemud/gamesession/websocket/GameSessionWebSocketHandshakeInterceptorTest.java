package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GameSessionWebSocketHandshakeInterceptorTest {

  private final GameSessionWebSocketHandshakeInterceptor interceptor =
      new GameSessionWebSocketHandshakeInterceptor();

  @Test
  void explicitNumericTransportSessionIdIsPreserved() {
    Map<String, Object> attributes = handshakeAttributes("12345");

    assertThat(attributes.get(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR))
        .isEqualTo("12345");
  }

  @Test
  void explicitOpaqueTransportSessionIdIsNormalizedToStableNumericSessionId() {
    Map<String, Object> first = handshakeAttributes("web-session-alpha");
    Map<String, Object> second = handshakeAttributes("web-session-alpha");

    String firstSessionId =
        (String) first.get(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR);
    String secondSessionId =
        (String) second.get(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR);

    assertThat(firstSessionId).matches("\\d+");
    assertThat(secondSessionId).isEqualTo(firstSessionId);
  }

  private Map<String, Object> handshakeAttributes(String transportSessionId) {
    MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws/game");
    servletRequest.addHeader(
        GameSessionWebSocketHandshakeInterceptor.TRANSPORT_SESSION_HEADER, transportSessionId);
    ServletServerHttpRequest request = new ServletServerHttpRequest(servletRequest);
    ServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();

    interceptor.beforeHandshake(request, response, null, attributes);
    return attributes;
  }
}
