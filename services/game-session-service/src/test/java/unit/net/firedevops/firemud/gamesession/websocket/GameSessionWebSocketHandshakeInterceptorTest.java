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

  @Test
  void firstPartyHandoffDoesNotTreatRawRoutingHeadersAsLogicalAuthority() {
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws/game");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.TRANSPORT_SESSION_HEADER, "12345");
    servlet.addHeader(
        GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_HEADER, "signed-context");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.TENANT_HEADER, "999");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.GAME_INSTANCE_HEADER, "888");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.WORLD_SLUG_HEADER, "untrusted");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_HEADER, "untrusted");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_HEADER, "777");
    Map<String, Object> attributes = new HashMap<>();
    interceptor.beforeHandshake(
        new ServletServerHttpRequest(servlet),
        new ServletServerHttpResponse(new MockHttpServletResponse()),
        null,
        attributes);
    assertThat(attributes)
        .containsEntry(
            GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_ATTR, "first_party_web");
    assertThat(attributes)
        .doesNotContainKeys(
            GameSessionWebSocketHandshakeInterceptor.TENANT_ID_ATTR,
            GameSessionWebSocketHandshakeInterceptor.BOOTSTRAP_GAME_INSTANCE_ATTR,
            GameSessionWebSocketHandshakeInterceptor.WORLD_SLUG_ATTR,
            GameSessionWebSocketHandshakeInterceptor.REALM_SLUG_ATTR,
            GameSessionWebSocketHandshakeInterceptor.POINTER_VERSION_ATTR);
  }
}
