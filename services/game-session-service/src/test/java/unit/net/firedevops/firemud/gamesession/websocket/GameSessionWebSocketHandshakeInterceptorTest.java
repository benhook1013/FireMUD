package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
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
  void preservesCanonicalProxyConnectionIdOnlyForAuthenticatedGatewayOnDedicatedListener()
      throws Exception {
    String proxyConnectionId = "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814";
    MockHttpServletRequest servlet =
        authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    Map<String, Object> attributes = new HashMap<>();

    boolean accepted =
        new GameSessionWebSocketHandshakeInterceptor(true, 8444, "test")
            .beforeHandshake(
                new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);

    assertThat(accepted).isTrue();
    assertThat(attributes.get(AuthenticatedDirectTextIngress.ATTRIBUTE))
        .isInstanceOf(AuthenticatedDirectTextIngress.class);

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
  void canonicalizesLiteralIpv6WithoutAddressResolution() throws Exception {
    var request = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    request.removeHeader("X-Client-IP");
    request.addHeader("X-Client-IP", "2001:db8::7");
    Map<String, Object> attributes = new HashMap<>();
    assertThat(
            new GameSessionWebSocketHandshakeInterceptor(true, 8444, "test")
                .beforeHandshake(
                    new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(new MockHttpServletResponse()),
                    null,
                    attributes))
        .isTrue();
    var ingress =
        (AuthenticatedDirectTextIngress) attributes.get(AuthenticatedDirectTextIngress.ATTRIBUTE);
    assertThat(ingress.clientAddress()).isEqualTo("2001:db8:0:0:0:0:0:7");
  }

  @Test
  void rejectsSpoofedHeadersWrongWorkloadsNamespacesAndPublicListener() throws Exception {
    for (String peer :
        List.of(
            "spiffe://firemud/ns/test/sa/account-service",
            "spiffe://firemud/ns/other/sa/spring-cloud-gateway")) {
      var request = authenticatedRequest(peer);
      assertRejected(request);
    }
    var plain = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    plain.setSecure(false);
    assertRejected(plain);
    var publicPort = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    publicPort.setLocalPort(8080);
    assertRejected(publicPort);
    var duplicate = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    duplicate.addHeader("X-Client-IP", "198.51.100.9");
    assertRejected(duplicate);
    var duplicateMode = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    duplicateMode.addHeader("X-Firemud-Connection-Mode", "first_party_web");
    assertRejected(duplicateMode);
  }

  @Test
  void rejectsMissingCertificateClientAuthEkuAndSpiffeSan() throws Exception {
    var missingCertificate =
        authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    missingCertificate.removeAttribute("jakarta.servlet.request.X509Certificate");
    assertRejected(missingCertificate);

    var missingClientAuth =
        authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    var nonClientAuthCertificate =
        (X509Certificate[])
            missingClientAuth.getAttribute("jakarta.servlet.request.X509Certificate");
    when(nonClientAuthCertificate[0].getExtendedKeyUsage())
        .thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
    assertRejected(missingClientAuth);

    var missingSpiffeSan = authenticatedRequest("spiffe://firemud/ns/test/sa/spring-cloud-gateway");
    var certificateWithNoUriSan =
        (X509Certificate[])
            missingSpiffeSan.getAttribute("jakarta.servlet.request.X509Certificate");
    when(certificateWithNoUriSan[0].getSubjectAlternativeNames()).thenReturn(List.of());
    assertRejected(missingSpiffeSan);
  }

  private void assertRejected(MockHttpServletRequest request) {
    var attributes = new HashMap<String, Object>();
    var response = new MockHttpServletResponse();
    assertThat(
            new GameSessionWebSocketHandshakeInterceptor(true, 8444, "test")
                .beforeHandshake(
                    new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(response),
                    null,
                    attributes))
        .isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(attributes)
        .doesNotContainKeys(
            AuthenticatedDirectTextIngress.ATTRIBUTE,
            GameSessionWebSocketHandshakeInterceptor.OBSERVED_DIRECT_TEXT_TRANSPORT_ATTR);
  }

  private static MockHttpServletRequest authenticatedRequest(String peer) throws Exception {
    var request = new MockHttpServletRequest("GET", "/ws/game/tcp");
    request.setSecure(true);
    request.setLocalPort(8444);
    var certificate = mock(X509Certificate.class);
    when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, peer)));
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
    request.addHeader("X-Client-IP", "203.0.113.7");
    request.addHeader("X-Firemud-Connection-Mode", "trusted_tcp_proxy");
    request.addHeader("X-Proxy-Connection-Id", "9fb38c88-2f1d-42aa-8e0b-7eb69caf8814");
    return request;
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
