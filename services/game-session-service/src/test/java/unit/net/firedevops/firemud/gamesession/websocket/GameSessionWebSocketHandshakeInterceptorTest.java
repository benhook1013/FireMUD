package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
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

  @Test
  void preservesCanonicalProxyConnectionIdOnlyForTrustedProxyMode() {
    String proxyConnectionId = "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814";
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws/game");
    servlet.addHeader(
        GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_HEADER, "trusted_tcp_proxy");
    servlet.addHeader(
        GameSessionWebSocketHandshakeInterceptor.PROXY_CONNECTION_HEADER, proxyConnectionId);
    Map<String, Object> attributes = new HashMap<>();

    interceptor.beforeHandshake(
        new ServletServerHttpRequest(servlet),
        new ServletServerHttpResponse(new MockHttpServletResponse()),
        null,
        attributes);

    ObservedDirectTextTransport observed =
        (ObservedDirectTextTransport)
            attributes.get(
                GameSessionWebSocketHandshakeInterceptor.OBSERVED_DIRECT_TEXT_TRANSPORT_ATTR);
    assertThat(observed.proxyConnectionId()).isEqualTo(UUID.fromString(proxyConnectionId));
    assertThat(observed.transportSessionId())
        .isEqualTo(attributes.get(GameSessionWebSocketHandshakeInterceptor.SESSION_ID_ATTR));
    assertThat(observed.transportSessionId()).isNotEqualTo(proxyConnectionId);
  }

  @Test
  void omitsObservationForMalformedProxyIdOrNonProxyMode() {
    assertNoObservation("trusted_tcp_proxy", "not-a-uuid");
    assertNoObservation("first_party_web", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814");
    assertNoObservation("trusted_tcp_proxy", "9FB38C88-2F1D-42AA-8E0B-7EB69CAF8814");
    assertNoObservation("trusted_tcp_proxy", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814", true);
  }

  private void assertNoObservation(String mode, String proxyConnectionId) {
    assertNoObservation(mode, proxyConnectionId, false);
  }

  private void assertNoObservation(
      String mode, String proxyConnectionId, boolean includeConnectContext) {
    MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws/game");
    servlet.addHeader(GameSessionWebSocketHandshakeInterceptor.CONNECTION_MODE_HEADER, mode);
    servlet.addHeader(
        GameSessionWebSocketHandshakeInterceptor.PROXY_CONNECTION_HEADER, proxyConnectionId);
    if (includeConnectContext) {
      servlet.addHeader(
          GameSessionWebSocketHandshakeInterceptor.CONNECT_CONTEXT_HEADER, "untrusted-context");
    }
    Map<String, Object> attributes = new HashMap<>();

    interceptor.beforeHandshake(
        new ServletServerHttpRequest(servlet),
        new ServletServerHttpResponse(new MockHttpServletResponse()),
        null,
        attributes);

    assertThat(attributes)
        .doesNotContainKey(
            GameSessionWebSocketHandshakeInterceptor.OBSERVED_DIRECT_TEXT_TRANSPORT_ATTR);
  }
}
