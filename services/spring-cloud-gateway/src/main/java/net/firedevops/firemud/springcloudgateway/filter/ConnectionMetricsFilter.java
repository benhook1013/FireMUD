package net.firedevops.firemud.springcloudgateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Tracks connection metrics for monitoring purposes. */
@Component
public class ConnectionMetricsFilter implements WebFilter, Ordered {

  private final AtomicInteger activeConnections = new AtomicInteger();
  private final Counter connectionCounter;

  public ConnectionMetricsFilter(MeterRegistry meterRegistry) {
    this.connectionCounter = meterRegistry.counter("gateway.connections.total");
    Gauge.builder("gateway.connections.active", activeConnections, AtomicInteger::get)
        .register(meterRegistry);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    connectionCounter.increment();
    activeConnections.incrementAndGet();
    return chain.filter(exchange).doFinally(signalType -> activeConnections.decrementAndGet());
  }

  @Override
  public int getOrder() {
    return -2;
  }
}
