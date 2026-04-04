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

/** Tracks HTTP request activity for gateway observability. */
@Component
public class RequestMetricsFilter implements WebFilter, Ordered {

  private final AtomicInteger inFlightRequests = new AtomicInteger();
  private final Counter requestCounter;

  public RequestMetricsFilter(MeterRegistry meterRegistry) {
    this.requestCounter = meterRegistry.counter("gateway.http.requests.total");
    Gauge.builder("gateway.http.requests.in_flight", inFlightRequests, AtomicInteger::get)
        .register(meterRegistry);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    requestCounter.increment();
    inFlightRequests.incrementAndGet();
    return chain.filter(exchange).doFinally(signalType -> inFlightRequests.decrementAndGet());
  }

  @Override
  public int getOrder() {
    return -2;
  }
}
