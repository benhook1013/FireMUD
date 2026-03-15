package net.firedevops.firemud.springcloudgateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class JwtAuthFilterTest {
  private final JwtAuthFilter filter = new JwtAuthFilter();

  @Test
  void rejectsRequestWithoutToken() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/test").build());
    filter.filter(exchange, e -> Mono.empty()).block();
    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void allowsRequestWithToken() {
    MockServerHttpRequest.BaseBuilder<?> builder =
        MockServerHttpRequest.get("/api/admin/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer sometoken");
    MockServerWebExchange exchange = MockServerWebExchange.from(builder);
    filter.filter(exchange, e -> Mono.empty()).block();
    // no status set implies success (200)
    assertEquals(null, exchange.getResponse().getStatusCode());
  }
}
