package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.config.GatewayTcpProxyListenerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class HeaderTrustFilterTest {
  private static final String TCP_PROXY_URI = "spiffe://firemud/ns/firemud/sa/tcp-proxy-service";

  @Test
  void stripsSpoofedClientIpHeader() {
    HeaderTrustFilter filter = legacyFilter(new GatewayHeaderTrustProperties());

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/")
            .header("X-Client-IP", "203.0.113.10")
            .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("1.2.3.4");
  }

  @Test
  void stripsSpoofedRoutingBundleFromPublicGameplayIngress() {
    HeaderTrustFilter filter = legacyFilter(new GatewayHeaderTrustProperties());

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
            .header("X-World-Slug", "spoofed-world")
            .header("X-Realm-Slug", "spoofed-realm")
            .header("X-Pointer-Version", "999")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-World-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Realm-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Pointer-Version")).isNull();
  }

  @Test
  void derivesClientIpFromForwardedHeadersOnlyWhenRemoteIsTrusted() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getForwardedClientIp().setTrustedProxyCidrs(List.of("1.2.3.4/32"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/")
            .header("X-Forwarded-For", "198.51.100.7, 203.0.113.10")
            .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void doesNotTrustForwardedHeadersWhenRemoteIsNotTrusted() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getForwardedClientIp().setTrustedProxyCidrs(List.of("5.6.7.8/32"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/")
            .header("X-Forwarded-For", "198.51.100.7")
            .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("1.2.3.4");
  }

  @Test
  void promotesTcpProxyHeadersWhenInsecureTrustEnabledAndRemoteMatches() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "7")
            .header("X-World-Slug", "demo")
            .header("X-Realm-Slug", "production")
            .header("X-Pointer-Version", "17")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.99");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Connection-Id"))
        .isEqualTo("conn-123");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id"))
        .isEqualTo("42");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("7");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-World-Slug"))
        .isEqualTo("demo");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Realm-Slug"))
        .isEqualTo("production");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Pointer-Version"))
        .isEqualTo("17");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Game-Instance-Id"))
        .isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Tenant-Id")).isNull();
  }

  @Test
  void promotesProxyHeadersOnlyOnAuthenticatedDedicatedTlsListener() throws Exception {
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    GatewayTcpProxyListenerProperties listenerProperties = certificateListenerProperties();
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), Set.of("test"));
    HeaderTrustFilter filter = new HeaderTrustFilter(headerProperties, policy);
    SslInfo authenticatedPeer = authenticatedTcpProxyPeer();

    MockServerHttpRequest trustedRequest =
        MockServerHttpRequest.get("/ws/game/test")
            .localAddress(new InetSocketAddress("127.0.0.1", 8443))
            .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
            .sslInfo(authenticatedPeer)
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "conn-123")
            .build();

    ServerWebExchange promoted =
        filterThroughChain(filter, MockServerWebExchange.from(trustedRequest));
    assertThat(promoted.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.99");
    assertThat(promoted.getRequest().getHeaders().getFirst("X-Proxy-Connection-Id"))
        .isEqualTo("conn-123");

    MockServerHttpRequest untrustedListenerRequest =
        MockServerHttpRequest.get("/ws/game/test")
            .localAddress(new InetSocketAddress("127.0.0.1", 8080))
            .remoteAddress(new InetSocketAddress("192.0.2.1", 50001))
            .sslInfo(authenticatedPeer)
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "conn-123")
            .build();
    MockServerWebExchange untrustedListenerExchange =
        MockServerWebExchange.from(untrustedListenerRequest);
    filter.filter(untrustedListenerExchange, ignored -> Mono.empty()).block();
    assertThat(untrustedListenerExchange.getResponse().getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsAuthenticatedSessionWhenProxyClientIpIsMissingOrMalformed() throws Exception {
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    GatewayTcpProxyListenerProperties listenerProperties = certificateListenerProperties();
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), Set.of("test"));
    HeaderTrustFilter filter = new HeaderTrustFilter(headerProperties, policy);
    SslInfo authenticatedPeer = authenticatedTcpProxyPeer();

    for (String clientIp : new String[] {null, "not-an-ip"}) {
      MockServerHttpRequest.BaseBuilder<?> requestBuilder =
          MockServerHttpRequest.get("/ws/game/test")
              .localAddress(new InetSocketAddress("127.0.0.1", 8443))
              .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
              .sslInfo(authenticatedPeer)
              .header("X-Proxy-Connection-Id", "conn-123");
      if (clientIp != null) {
        requestBuilder.header("X-Proxy-Client-IP", clientIp);
      }
      MockServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
      AtomicReference<ServerWebExchange> delegated = new AtomicReference<>();

      filter
          .filter(
              exchange,
              candidate -> {
                delegated.set(candidate);
                return Mono.empty();
              })
          .block();

      assertThat(delegated.get()).isNull();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void rejectsLegacyTrustedProxyWhenProxyClientIpIsMissingOrMalformed() {
    GatewayHeaderTrustProperties properties = new GatewayHeaderTrustProperties();
    properties.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    properties.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(properties);

    for (String clientIp : new String[] {null, "not-an-ip"}) {
      MockServerHttpRequest.BaseBuilder<?> requestBuilder =
          MockServerHttpRequest.get("/ws/game/test")
              .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
              .header("X-Proxy-Connection-Id", "conn-123")
              .header("X-Proxy-Game-Instance-Id", "42")
              .header("X-Proxy-Tenant-Id", "7");
      if (clientIp != null) {
        requestBuilder.header("X-Proxy-Client-IP", clientIp);
      }
      MockServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
      AtomicReference<ServerWebExchange> delegated = new AtomicReference<>();

      filter
          .filter(
              exchange,
              candidate -> {
                delegated.set(candidate);
                return Mono.empty();
              })
          .block();

      assertThat(delegated.get()).isNull();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void rejectsLegacyTrustedProxySessionWithoutAnyProxyHeaders() {
    GatewayHeaderTrustProperties properties = new GatewayHeaderTrustProperties();
    properties.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    properties.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(properties);

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
                .build());
    AtomicReference<ServerWebExchange> delegated = new AtomicReference<>();

    filter
        .filter(
            exchange,
            candidate -> {
              delegated.set(candidate);
              return Mono.empty();
            })
        .block();

    assertThat(delegated.get()).isNull();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void doesNotEmitLegacySessionId() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "7")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id"))
        .isEqualTo("42");
  }

  @Test
  void rejectsSessionRouteWhenTrustedProxyTenantIdIsMalformed() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "bad-id")
            .build();

    ServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id")).isNull();
  }

  @Test
  void rejectsSessionRouteWhenTrustedProxyGameInstanceIdIsNonPositive() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "0")
            .header("X-Proxy-Tenant-Id", "1")
            .build();

    ServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isNull();
  }

  @Test
  void rejectsSessionRouteWhenTrustedProxyIdentityIsPartial() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Tenant-Id", "1")
            .build();

    ServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsSessionRouteWhenProxyHeadersPresentButUpstreamNotTrusted() {
    HeaderTrustFilter filter = legacyFilter(new GatewayHeaderTrustProperties());

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("1.2.3.4", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    AtomicReference<ServerWebExchange> ref = new AtomicReference<>();
    WebFilterChain chain =
        e -> {
          ref.set(e);
          return Mono.empty();
        };

    filter.filter(exchange, chain).block();

    assertThat(ref.get()).isNull();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void doesNotPromoteTcpProxyHeadersOutsideSessionRoutes() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/account/profile")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "7")
            .header("X-World-Slug", "demo")
            .header("X-Realm-Slug", "production")
            .header("X-Pointer-Version", "17")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-World-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Realm-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Pointer-Version")).isNull();
  }

  @Test
  void acceptsAbsentRoutingBundleFromTrustedTcpProxy() {
    ServerWebExchange mutatedExchange = filterTrustedTcpProxyRoutingBundle(null, null, null);

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-World-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Realm-Slug")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Pointer-Version")).isNull();
  }

  @Test
  void rejectsBlankRoutingBundleFromTrustedTcpProxy() {
    assertTrustedTcpProxyRoutingBundleRejected(" ", " ", " ");
  }

  @Test
  void rejectsPartialRoutingBundleFromTrustedTcpProxy() {
    assertTrustedTcpProxyRoutingBundleRejected("demo", null, "17");
  }

  @Test
  void rejectsMalformedRoutingBundleFromTrustedTcpProxy() {
    assertTrustedTcpProxyRoutingBundleRejected("demo", "production", "not-a-number");
  }

  @Test
  void rejectsNonPositiveRoutingBundleFromTrustedTcpProxy() {
    assertTrustedTcpProxyRoutingBundleRejected("demo", "production", "0");
  }

  private ServerWebExchange filterTrustedTcpProxyRoutingBundle(
      String worldSlug, String realmSlug, String pointerVersion) {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest.BaseBuilder<?> requestBuilder =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99");
    if (worldSlug != null) {
      requestBuilder.header("X-World-Slug", worldSlug);
    }
    if (realmSlug != null) {
      requestBuilder.header("X-Realm-Slug", realmSlug);
    }
    if (pointerVersion != null) {
      requestBuilder.header("X-Pointer-Version", pointerVersion);
    }
    return filterThroughChain(filter, MockServerWebExchange.from(requestBuilder.build()));
  }

  private void assertTrustedTcpProxyRoutingBundleRejected(
      String worldSlug, String realmSlug, String pointerVersion) {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = legacyFilter(props);

    MockServerHttpRequest.BaseBuilder<?> requestBuilder =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99");
    if (worldSlug != null) {
      requestBuilder.header("X-World-Slug", worldSlug);
    }
    if (realmSlug != null) {
      requestBuilder.header("X-Realm-Slug", realmSlug);
    }
    if (pointerVersion != null) {
      requestBuilder.header("X-Pointer-Version", pointerVersion);
    }
    MockServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
    AtomicReference<ServerWebExchange> delegated = new AtomicReference<>();

    filter
        .filter(
            exchange,
            candidate -> {
              delegated.set(candidate);
              return Mono.empty();
            })
        .block();

    assertThat(delegated.get()).isNull();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private ServerWebExchange filterThroughChain(
      HeaderTrustFilter filter, ServerWebExchange exchange) {
    AtomicReference<ServerWebExchange> ref = new AtomicReference<>();
    WebFilterChain chain =
        e -> {
          ref.set(e);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    return ref.get();
  }

  static HeaderTrustFilter legacyFilter(GatewayHeaderTrustProperties properties) {
    return new HeaderTrustFilter(
        properties,
        new TcpProxyTrustPolicy(
            new GatewayTcpProxyListenerProperties(),
            properties,
            8080,
            Clock.systemUTC(),
            Set.of("test")));
  }

  private static GatewayTcpProxyListenerProperties certificateListenerProperties() {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setPort(8443);
    properties.setCertificateChainPath("server.crt");
    properties.setPrivateKeyPath("server.key");
    properties.setTrustedClientCaPath("client-ca.crt");
    properties.setEnvironment("production");
    properties.setTrustProfile("production_uri");
    properties.getProductionUri().setUriSan(TCP_PROXY_URI);
    return properties;
  }

  private static SslInfo authenticatedTcpProxyPeer() throws Exception {
    try (InputStream certificateStream =
        Objects.requireNonNull(
            HeaderTrustFilterTest.class.getResourceAsStream("/certs/tcp-proxy-client.pem"),
            "missing TCP Proxy client certificate fixture")) {
      X509Certificate certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509").generateCertificate(certificateStream);
      SslInfo sslInfo = mock(SslInfo.class);
      when(sslInfo.getPeerCertificates()).thenReturn(new X509Certificate[] {certificate});
      return sslInfo;
    }
  }
}
