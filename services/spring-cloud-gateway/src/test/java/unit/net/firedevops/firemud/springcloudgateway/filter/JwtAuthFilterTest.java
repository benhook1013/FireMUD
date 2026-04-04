package net.firedevops.firemud.springcloudgateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {
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
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("player")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/routes/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
  }

  @Test
  void allowsRequestWithAdminRole() {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/routes/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(null, exchange.getResponse().getStatusCode());
    assertTrue(
        exchange.getResponse().isCommitted() || exchange.getResponse().getStatusCode() == null);
  }
}
