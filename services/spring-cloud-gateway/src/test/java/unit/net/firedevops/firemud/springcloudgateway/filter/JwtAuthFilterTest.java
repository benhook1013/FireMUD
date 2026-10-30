package net.firedevops.firemud.springcloudgateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {
  private static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);
  private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil);

  @Test
  void rejectsRequestWithoutToken() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/routes/test").build());
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void rejectsRequestWithoutAdminRole() {
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("player")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/routes/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
  }

  @Test
  void allowsRequestWithAdminRole() {
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/routes/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(null, exchange.getResponse().getStatusCode());
    assertTrue(
        exchange.getResponse().isCommitted() || exchange.getResponse().getStatusCode() == null);
  }

  @Test
  void rejectsMalformedScopedRolesClaimShape() {
    String token =
        jwtUtil.generateToken(
            ACCOUNT_ID, Map.of("accountId", ACCOUNT_ID, "scopedRoles", List.of("tenantAdmin")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/routes/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);

    filter.filter(exchange, e -> Mono.empty()).block();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/routes/test", "/api/admin/test"})
  void rejectsDifferentSignedAccountSubjectBeforePrivilegedDispatch(String path) {
    String token =
        jwtUtil.generateToken(
            "018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f",
            Map.of("accountId", ACCOUNT_ID, "globalRoles", List.of("platformAdmin")));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build());
    AtomicBoolean forwarded = new AtomicBoolean();

    filter
        .filter(
            exchange,
            e -> {
              forwarded.set(true);
              return Mono.empty();
            })
        .block();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertFalse(forwarded.get());
  }
}
