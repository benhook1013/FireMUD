package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class GameplayHandshakeFilterTest {
  private static final String SECRET = "testsecretkeytestsecretkeytest1234";
  private static final RuntimeIdentity TEST_RUNTIME_IDENTITY =
      new RuntimeIdentity(
          "spring-cloud-gateway", "gateway-test", "localhost", Instant.EPOCH, null, null, null);

  private static MockEnvironment environmentWithProfiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  void rejectsFirstPartyHandshakeWithoutConnectToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/ws/game/test").build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_MISSING);
  }

  @Test
  void rejectsFirstPartyHandshakeWithMalformedNumericClaim() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "not-a-number",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "17",
                    "connectScopeId", "scope-1",
                    "requestId", "req-1",
                    "jti", "jti-1"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
  }

  @Test
  void rejectsFirstPartyHandshakeWithZeroPointerVersion() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "0",
                    "connectScopeId", "scope-1",
                    "requestId", "req-1",
                    "jti", "jti-1"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
  }

  @Test
  void rejectsFirstPartyHandshakeWithMissingRequiredClaim() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "17",
                    "connectScopeId", "scope-1",
                    "requestId", "req-1",
                    "jti", "jti-1"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
  }

  @Test
  void firstPartyHandshakeDoesNotMaskUnexpectedJwtParserRuntimeFailure() {
    JwtUtil jwtUtil = mock(JwtUtil.class);
    when(jwtUtil.parseToken("boom-token")).thenThrow(new IllegalStateException("boom"));
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            jwtUtil, TEST_RUNTIME_IDENTITY, null, environmentWithProfiles("test"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, "boom-token")
                .build());

    IllegalStateException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> filter.filter(exchange, e -> Mono.empty()).block());

    assertThat(ex).hasMessage("boom");
  }

  @Test
  void rejectsScopeMismatchWhenRequestHeadersDisagreeWithTokenClaims() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "17",
                    "connectScopeId", "scope-1",
                    "requestId", "req-1",
                    "jti", "jti-1"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
            .header("X-Tenant-Id", "2")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void promotesFirstPartyHandshakeWithCookieCarrier() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "18",
                    "connectScopeId", "scope-cookie",
                    "requestId", "req-cookie",
                    "jti", "jti-cookie"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .cookie(new HttpCookie(GameplayHandshakeFilter.CONNECT_TOKEN_COOKIE, token))
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connection-Mode"))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_FIRST_PARTY_WEB);
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("1");
  }

  @Test
  void rejectsHandshakeWithBothCookieAndHeaderCarrier() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "18",
                    "connectScopeId", "scope-both",
                    "requestId", "req-both",
                    "jti", "jti-both"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
            .cookie(new HttpCookie(GameplayHandshakeFilter.CONNECT_TOKEN_COOKIE, token))
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REJECTED);
  }

  @Test
  void promotesFirstPartyHandshakeWithValidToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "18",
                    "connectScopeId", "scope-2",
                    "requestId", "req-2",
                    "jti", "jti-2"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(filter, MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connection-Mode"))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_FIRST_PARTY_WEB);
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("1");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Game-Instance-Id"))
        .isEqualTo("42");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.WORLD_SLUG_HEADER))
        .isEqualTo("demo");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.REALM_SLUG_HEADER))
        .isEqualTo("production");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.POINTER_VERSION_HEADER))
        .isEqualTo("18");
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connect-Context"))
        .isNotBlank();
    Claims connectContextClaims =
        new JwtUtil(SECRET, 30_000L)
            .parseToken(
                mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connect-Context"))
            .getPayload();
    assertThat(connectContextClaims.get("worldSlug")).isEqualTo("demo");
    assertThat(connectContextClaims.get("realmSlug")).isEqualTo("production");
    assertThat(connectContextClaims.get("pointerVersion")).isEqualTo("18");
    assertThat(connectContextClaims.get("connectScopeId")).isEqualTo("scope-2");
    assertThat(connectContextClaims.get("connectRequestId")).isEqualTo("req-2");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.TRANSPORT_SESSION_HEADER))
        .matches("\\d+");
  }

  @Test
  void trustedTcpProxyHandshakeBypassesConnectTokenRequirement() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(java.util.List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(props);
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "1")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(
            filter, filterThroughChain(headerTrustFilter, MockServerWebExchange.from(request)));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connection-Mode"))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_TRUSTED_TCP_PROXY);
    assertThat(mutatedExchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void trustedTcpProxyHandshakePreservesCompleteRoutingBundle() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(java.util.List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(props);
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
            .build();

    ServerWebExchange mutatedExchange =
        filterThroughChain(
            filter, filterThroughChain(headerTrustFilter, MockServerWebExchange.from(request)));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connection-Mode"))
        .isEqualTo(GameplayHandshakeFilter.CONNECTION_MODE_TRUSTED_TCP_PROXY);
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.WORLD_SLUG_HEADER))
        .isEqualTo("demo");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.REALM_SLUG_HEADER))
        .isEqualTo("production");
    assertThat(
            mutatedExchange
                .getRequest()
                .getHeaders()
                .getFirst(GameplayHandshakeFilter.POINTER_VERSION_HEADER))
        .isEqualTo("17");
    assertThat(mutatedExchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void trustedTcpProxyHandshakeRejectsPartialRoutingBundle() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(java.util.List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(props);
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    ServerWebExchange trustedExchange = filterThroughChain(headerTrustFilter, exchange);
    filter.filter(trustedExchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyHandshakeRejectsMalformedPointerVersion() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(java.util.List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(props);
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "v17")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    ServerWebExchange trustedExchange = filterThroughChain(headerTrustFilter, exchange);
    filter.filter(trustedExchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyHandshakeRejectsBlankWorldSlugInRoutingBundle() {
    GatewayHeaderTrustProperties props = new GatewayHeaderTrustProperties();
    props.getTcpProxy().setAllowInsecureHeadersFromTrustedCidrs(true);
    props.getTcpProxy().setInsecureTrustedCidrs(java.util.List.of("10.0.0.0/8"));
    HeaderTrustFilter headerTrustFilter = new HeaderTrustFilter(props);
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header("X-Proxy-Connection-Id", "conn-123")
            .header("X-Proxy-Game-Instance-Id", "42")
            .header("X-Proxy-Tenant-Id", "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, " ")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    ServerWebExchange trustedExchange = filterThroughChain(headerTrustFilter, exchange);
    filter.filter(trustedExchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyHandshakeRejectsMalformedTenantId() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-123")
            .header(GameplayHandshakeFilter.GAME_INSTANCE_ID_HEADER, "42")
            .header(GameplayHandshakeFilter.TENANT_ID_HEADER, "bad-id")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyHandshakeRejectsNonPositiveGameInstanceId() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-123")
            .header(GameplayHandshakeFilter.GAME_INSTANCE_ID_HEADER, "0")
            .header(GameplayHandshakeFilter.TENANT_ID_HEADER, "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void trustedTcpProxyHandshakeRejectsPartialProxyIdentityBundle() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));

    MockServerHttpRequest request =
        MockServerHttpRequest.get("/ws/game/test")
            .remoteAddress(new InetSocketAddress("10.1.2.3", 0))
            .header(GameplayHandshakeFilter.PROXY_CONNECTION_ID_HEADER, "conn-123")
            .header(GameplayHandshakeFilter.TENANT_ID_HEADER, "1")
            .header(GameplayHandshakeFilter.WORLD_SLUG_HEADER, "demo")
            .header(GameplayHandshakeFilter.REALM_SLUG_HEADER, "production")
            .header(GameplayHandshakeFilter.POINTER_VERSION_HEADER, "17")
            .build();

    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_SCOPE_MISMATCH);
  }

  @Test
  void rejectsExpiredConnectToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("test"));
    JwtUtil expiredJwtUtil = new JwtUtil(SECRET, -1L);
    String token =
        expiredJwtUtil.generateToken(
            "7",
            Map.of(
                "aud", "gameplay-connect",
                "accountId", "7",
                "tenantId", "1",
                "worldSlug", "demo",
                "realmSlug", "production",
                "gameInstanceId", "42",
                "pointerVersion", "17",
                "connectScopeId", "scope-expired",
                "requestId", "req-expired",
                "jti", "jti-expired"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_EXPIRED);
  }

  @Test
  void rejectsReplayedConnectToken() {
    ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(
            eq("gateway:connect-token:jti:jti-replay"), eq("1"), any(java.time.Duration.class)))
        .thenReturn(Mono.just(true), Mono.just(false));
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            redisTemplate,
            environmentWithProfiles("test"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "17",
                    "connectScopeId", "scope-replay",
                    "requestId", "req-replay",
                    "jti", "jti-replay"));

    MockServerWebExchange first =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());
    MockServerWebExchange second =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(first, e -> Mono.empty()).block();
    filter.filter(second, e -> Mono.empty()).block();

    assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(second.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_REPLAYED);
  }

  @Test
  void rejectsWhenReplayProtectionStorageIsMissingOutsideDevOrTest() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            TEST_RUNTIME_IDENTITY,
            null,
            environmentWithProfiles("prod"));
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "aud", "gameplay-connect",
                    "accountId", "7",
                    "tenantId", "1",
                    "worldSlug", "demo",
                    "realmSlug", "production",
                    "gameInstanceId", "42",
                    "pointerVersion", "17",
                    "connectScopeId", "scope-no-redis",
                    "requestId", "req-no-redis",
                    "jti", "jti-no-redis"));

    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.CONNECT_TOKEN_HEADER, token)
                .build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_REPLAY_PROTECTION_UNAVAILABLE);
  }

  private ServerWebExchange filterThroughChain(WebFilter filter, ServerWebExchange exchange) {
    ServerWebExchange[] holder = new ServerWebExchange[1];
    WebFilterChain chain =
        e -> {
          holder[0] = e;
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    return holder[0] == null ? exchange : holder[0];
  }
}
