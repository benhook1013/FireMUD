package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import net.firedevops.firemud.springcloudgateway.websocket.GameplayWebSocketObservability;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

class GameplayHandshakeFilterTest {
  private static final RuntimeIdentity TEST_RUNTIME_IDENTITY =
      new RuntimeIdentity(
          "spring-cloud-gateway", "gateway-test", "localhost", Instant.EPOCH, null, null, null);

  @Test
  void nonGameplayRoutePassesWithoutHandshakeProcessing() {
    GameplayHandshakeFilter filter = filter();
    AtomicBoolean delegated = new AtomicBoolean();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/session/ping").build());

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void missingFirstPartyCookieIsRejectedAsMissing() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            TEST_RUNTIME_IDENTITY, new GameplayWebSocketObservability(registry));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/ws/game/test").build());
    AtomicBoolean delegated = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_MISSING);
    assertThat(
            registry
                .get("gateway.websocket.handshake.rejected")
                .tags(
                    "route",
                    GameplayWebSocketObservability.GAMEPLAY_ROUTE,
                    "status",
                    "403",
                    "error_class",
                    GameplayHandshakeFilter.CONNECT_TOKEN_MISSING)
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void firstPartyCookieFailsClosedBeforeParsingMutationOrForwarding() {
    GameplayHandshakeFilter filter = filter();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .cookie(
                    new HttpCookie(
                        GameplayHandshakeFilter.CONNECT_TOKEN_COOKIE, "legacy-hmac-token"))
                .build());
    AtomicBoolean delegated = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_REPLAY_PROTECTION_UNAVAILABLE);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Reason"))
        .isNull();
    assertThat(
            exchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.CONNECT_CONTEXT_HEADER))
        .isNull();
    assertThat(
            exchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.CONNECTION_MODE_HEADER))
        .isNull();
  }

  @Test
  void publicHeaderCarrierRemainsUnsupportedEvenWhenCookieIsPresent() {
    GameplayHandshakeFilter filter = filter();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, "header-token")
                .cookie(
                    new HttpCookie(GameplayHandshakeFilter.CONNECT_TOKEN_COOKIE, "cookie-token"))
                .build());
    AtomicBoolean delegated = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Reason"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_UNSUPPORTED_CARRIER_OR_ROUTE);
  }

  @Test
  void matrixParameterRouteIsRejectedBeforeTrustedProxyBypass() {
    GameplayHandshakeFilter filter = filter();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game;probe")
                .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-matrix")
                .header(GameplayHandshakeFilter.GAME_INSTANCE_ID_HEADER, "42")
                .header(GameplayHandshakeFilter.TENANT_ID_HEADER, "1")
                .build());
    AtomicBoolean delegated = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Reason"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_UNSUPPORTED_CARRIER_OR_ROUTE);
  }

  @Test
  void trustedTcpProxyHandshakeRemainsForwardableAndRemovesConnectCookie() {
    GatewayHeaderTrustProperties properties = new GatewayHeaderTrustProperties();
    properties.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    properties.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = HeaderTrustFilterTest.legacyFilter(properties);
    GameplayHandshakeFilter filter = filter();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
                .header("X-Proxy-Client-IP", "203.0.113.99")
                .header("X-Proxy-Connection-Id", "conn-123")
                .header("X-Proxy-Game-Instance-Id", "42")
                .header("X-Proxy-Tenant-Id", "1")
                .header(HttpHeaders.COOKIE, "Firemud-Connect-Token=legacy-token; session=abc")
                .build());
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    headerTrustFilter
        .filter(
            exchange,
            trustedExchange ->
                filter.filter(
                    trustedExchange,
                    forwardedExchange -> {
                      forwarded.set(forwardedExchange);
                      return Mono.empty();
                    }))
        .block();

    assertThat(forwarded.get()).isNotNull();
    assertThat(
            forwarded
                .get()
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.CONNECTION_MODE_HEADER))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_TRUSTED_TCP_PROXY);
    assertThat(forwarded.get().getRequest().getHeaders().get(HttpHeaders.COOKIE))
        .containsExactly("session=abc");
    assertThat(
            forwarded
                .get()
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.CONNECT_CONTEXT_HEADER))
        .isNull();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void trustedTcpProxyHandshakePreservesCompleteRoutingBundle() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
                .header("X-Proxy-Client-IP", "203.0.113.99")
                .header("X-Proxy-Connection-Id", "conn-123")
                .header("X-Proxy-Game-Instance-Id", "42")
                .header("X-Proxy-Tenant-Id", "1")
                .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
                .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
                .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
                .build());
    ServerWebExchange trusted = filterThroughChain(trustedHeaderFilter(), exchange);
    ServerWebExchange forwarded = filterThroughChain(filter(), trusted);

    assertThat(
            forwarded
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.CONNECTION_MODE_HEADER))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_TRUSTED_TCP_PROXY);
    assertThat(
            forwarded.getRequest().getHeaders().getFirst(GameplayHandshakeFilter.WORLD_SLUG_HEADER))
        .isEqualTo("demo");
    assertThat(
            forwarded.getRequest().getHeaders().getFirst(GameplayHandshakeFilter.REALM_SLUG_HEADER))
        .isEqualTo("production");
    assertThat(
            forwarded
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.POINTER_VERSION_HEADER))
        .isEqualTo("17");
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void trustedTcpProxyRejectsPartialRoutingBundle() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
                .header("X-Proxy-Client-IP", "203.0.113.99")
                .header("X-Proxy-Connection-Id", "conn-123")
                .header("X-Proxy-Game-Instance-Id", "42")
                .header("X-Proxy-Tenant-Id", "1")
                .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
                .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
                .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
                .build());
    ServerWebExchange trusted = filterThroughChain(trustedHeaderFilter(), exchange);
    ServerWebExchange partial =
        withRequestHeader(trusted, GameplayHandshakeFilter.POINTER_VERSION_HEADER, null);

    filter().filter(partial, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyRejectsMalformedPointerVersion() {
    MockServerWebExchange exchange = trustedProxyExchange("demo", "production", "v17", "1", "42");

    filter().filter(exchange, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyRejectsBlankWorldSlugInRoutingBundle() {
    MockServerWebExchange exchange = trustedProxyExchange(" ", "production", "17", "1", "42");

    filter().filter(exchange, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyRejectsMalformedTenantId() {
    MockServerWebExchange exchange =
        trustedProxyExchange("demo", "production", "17", "bad-id", "42");

    filter().filter(exchange, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyRejectsNonPositiveGameInstanceId() {
    MockServerWebExchange exchange = trustedProxyExchange("demo", "production", "17", "1", "0");

    filter().filter(exchange, ignored -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyMalformedIdentityBundleIsDenied() {
    GameplayHandshakeFilter filter = filter();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-123")
                .header(GameplayHandshakeFilter.TENANT_ID_HEADER, "1")
                .build());
    AtomicBoolean delegated = new AtomicBoolean();

    filter
        .filter(
            exchange,
            ignored -> {
              delegated.set(true);
              return Mono.empty();
            })
        .block();

    assertThat(delegated).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  private static GameplayHandshakeFilter filter() {
    return new GameplayHandshakeFilter(
        TEST_RUNTIME_IDENTITY, GameplayWebSocketObservability.disabled());
  }

  private static HeaderTrustFilter trustedHeaderFilter() {
    GatewayHeaderTrustProperties properties = new GatewayHeaderTrustProperties();
    properties.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    properties.getTcpProxy().setInsecureTrustedCidrs(List.of("10.0.0.0/8"));
    return HeaderTrustFilterTest.legacyFilter(properties);
  }

  private static MockServerWebExchange trustedProxyExchange(
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String tenantId,
      String gameInstanceId) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.get("/ws/game/test")
            .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-123")
            .header(GameplayHandshakeFilter.TENANT_ID_HEADER, tenantId)
            .header(GameplayHandshakeFilter.GAME_INSTANCE_ID_HEADER, gameInstanceId)
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, worldSlug)
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, realmSlug)
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, pointerVersion)
            .build());
  }

  private static ServerWebExchange filterThroughChain(
      WebFilter filter, ServerWebExchange exchange) {
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    filter
        .filter(
            exchange,
            forwardedExchange -> {
              forwarded.set(forwardedExchange);
              return Mono.empty();
            })
        .block();
    return forwarded.get() == null ? exchange : forwarded.get();
  }

  private static ServerWebExchange withRequestHeader(
      ServerWebExchange exchange, String headerName, String headerValue) {
    return exchange
        .mutate()
        .request(
            request ->
                request.headers(
                    headers -> {
                      headers.remove(headerName);
                      if (headerValue != null) {
                        headers.set(headerName, headerValue);
                      }
                    }))
        .build();
  }
}
