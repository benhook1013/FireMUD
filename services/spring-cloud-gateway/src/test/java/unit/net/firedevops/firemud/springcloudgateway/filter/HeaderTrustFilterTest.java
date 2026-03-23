package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
}
