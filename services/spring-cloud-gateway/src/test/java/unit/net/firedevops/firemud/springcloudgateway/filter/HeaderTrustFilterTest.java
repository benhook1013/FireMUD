package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
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

  @Test
  void stripsSpoofedClientIpHeader() {
    HeaderTrustFilter filter = new HeaderTrustFilter(new GatewayHeaderTrustProperties());

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
  void derivesClientIpFromForwardedHeadersOnlyWhenRemoteIsTrusted() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getForwardedClientIp().setTrustedProxyCidrs(List.of("1.2.3.4/32"));
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.99");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Connection-Id"))
        .isEqualTo("conn-123");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id"))
        .isEqualTo("42");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("7");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Game-Instance-Id"))
        .isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Proxy-Tenant-Id")).isNull();
  }

  @Test
  void promotesProxyHeadersOnlyOnAuthenticatedDedicatedTlsListener() {
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    GatewayTcpProxyListenerProperties listenerProperties = developmentListenerProperties();
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), Set.of("test"));
    HeaderTrustFilter filter = new HeaderTrustFilter(headerProperties, policy);

    MockServerHttpRequest trustedRequest =
        MockServerHttpRequest.get("/ws/game/test")
            .localAddress(new InetSocketAddress("127.0.0.1", 8443))
            .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
            .sslInfo(org.mockito.Mockito.mock(SslInfo.class))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Connection-Id", "conn-123")
            .build();

    ServerWebExchange promoted =
        filterThroughChain(filter, MockServerWebExchange.from(trustedRequest));
    assertThat(promoted.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.99");
    assertThat(promoted.getRequest().getHeaders().getFirst("X-Proxy-Connection-Id"))
        .isEqualTo("conn-123");

    MockServerHttpRequest untrustedDedicatedRequest =
        MockServerHttpRequest.get("/ws/game/test")
            .localAddress(new InetSocketAddress("127.0.0.1", 8443))
            .remoteAddress(new InetSocketAddress("192.0.2.1", 50001))
            .sslInfo(org.mockito.Mockito.mock(SslInfo.class))
            .build();
    MockServerWebExchange untrustedDedicatedExchange =
        MockServerWebExchange.from(untrustedDedicatedRequest);
    filter.filter(untrustedDedicatedExchange, ignored -> Mono.empty()).block();
    assertThat(untrustedDedicatedExchange.getResponse().getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsAuthenticatedSessionWhenProxyClientIpIsMissingOrMalformed() {
    GatewayHeaderTrustProperties headerProperties = new GatewayHeaderTrustProperties();
    GatewayTcpProxyListenerProperties listenerProperties = developmentListenerProperties();
    TcpProxyTrustPolicy policy =
        new TcpProxyTrustPolicy(
            listenerProperties, headerProperties, 8080, Clock.systemUTC(), Set.of("test"));
    HeaderTrustFilter filter = new HeaderTrustFilter(headerProperties, policy);

    for (String clientIp : new String[] {null, "not-an-ip"}) {
      MockServerHttpRequest.BaseBuilder<?> requestBuilder =
          MockServerHttpRequest.get("/ws/game/test")
              .localAddress(new InetSocketAddress("127.0.0.1", 8443))
              .remoteAddress(new InetSocketAddress("127.0.0.1", 50000))
              .sslInfo(org.mockito.Mockito.mock(SslInfo.class))
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
  void doesNotEmitLegacySessionId() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

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
    HeaderTrustFilter filter = new HeaderTrustFilter(new GatewayHeaderTrustProperties());

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
    HeaderTrustFilter filter = new HeaderTrustFilter(props);

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/api/account/profile")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Client-IP", "203.0.113.99")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "7")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id")).isNull();
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isNull();
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

  private static GatewayTcpProxyListenerProperties developmentListenerProperties() {
    GatewayTcpProxyListenerProperties properties = new GatewayTcpProxyListenerProperties();
    properties.setEnabled(true);
    properties.setPort(8443);
    properties.setCertificateChainPath("server.crt");
    properties.setPrivateKeyPath("server.key");
    properties.setEnvironment("isolated-test");
    properties.setTrustProfile("development_cidr");
    properties.getDevelopmentCidr().setTrustedCidr("127.0.0.1/32");
    return properties;
  }
}
