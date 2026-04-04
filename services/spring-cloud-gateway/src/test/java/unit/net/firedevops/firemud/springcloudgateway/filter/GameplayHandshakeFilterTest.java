package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.springcloudgateway.config.GatewayHeaderTrustProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
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

  @Test
  void rejectsFirstPartyHandshakeWithoutConnectToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, null);

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/ws/game/test").build());

    filter.filter(exchange, e -> Mono.empty()).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Firemud-Handshake-Error-Class"))
        .isEqualTo(GameplayHandshakeFilter.CONNECT_TOKEN_MISSING);
  }

  @Test
  void rejectsScopeMismatchWhenRequestHeadersDisagreeWithTokenClaims() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, null);
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "accountId", "7",
                    "tenantId", "1",
                    "gameInstanceId", "42",
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
  void promotesFirstPartyHandshakeWithValidToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, null);
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "accountId", "7",
                    "tenantId", "1",
                    "gameInstanceId", "42",
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
    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Firemud-Connect-Context"))
        .isNotBlank();
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
        new GameplayHandshakeFilter(new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, null);

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
  void rejectsExpiredConnectToken() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, null);
    JwtUtil expiredJwtUtil = new JwtUtil(SECRET, -1L);
    String token =
        expiredJwtUtil.generateToken(
            "7",
            Map.of(
                "accountId", "7",
                "tenantId", "1",
                "gameInstanceId", "42",
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
            new JwtUtil(SECRET, 30_000L), TEST_RUNTIME_IDENTITY, redisTemplate);
    String token =
        new JwtUtil(SECRET, 30_000L)
            .generateToken(
                "7",
                Map.of(
                    "accountId", "7",
                    "tenantId", "1",
                    "gameInstanceId", "42",
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
