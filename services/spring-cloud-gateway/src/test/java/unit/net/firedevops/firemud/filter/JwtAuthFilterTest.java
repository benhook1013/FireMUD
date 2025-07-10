package net.firedevops.firemud.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000);
  private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil);

  @Test
  void rejectsRequestWithoutToken() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/test").build());
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void allowsRequestWithValidRole() {
    String token = jwtUtil.generateToken("user", Map.of("globalRoles", List.of("platformAdmin")));
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/api/admin/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    // no status set implies success (200)
    assertEquals(null, exchange.getResponse().getStatusCode());
  }
}
