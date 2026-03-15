package net.firedevops.firemud.springcloudgateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ConnectionMetricsFilterTest {

  @Test
  void incrementsConnectionMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ConnectionMetricsFilter filter = new ConnectionMetricsFilter(registry);

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
    filter.filter(exchange, e -> Mono.empty()).block();

    assertEquals(1.0, registry.get("gateway.connections.total").counter().count(), 0.001);
    assertEquals(0.0, registry.get("gateway.connections.active").gauge().value(), 0.001);
  }
}
