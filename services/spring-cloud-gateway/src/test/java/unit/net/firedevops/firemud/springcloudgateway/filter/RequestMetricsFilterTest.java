package net.firedevops.firemud.springcloudgateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestMetricsFilterTest {

  @Test
  void incrementsRequestMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RequestMetricsFilter filter = new RequestMetricsFilter(registry);

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    filter.filter(exchange, e -> Mono.empty()).block();

    assertEquals(1.0, registry.get("gateway.http.requests.total").counter().count(), 0.001);
    assertEquals(0.0, registry.get("gateway.http.requests.in_flight").gauge().value(), 0.001);
  }
}
