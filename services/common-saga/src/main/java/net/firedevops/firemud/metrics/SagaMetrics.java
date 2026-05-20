package net.firedevops.firemud.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Tracks active short synchronous saga executions. */
@Component
public class SagaMetrics {
  private final AtomicInteger active = new AtomicInteger();

  public SagaMetrics(MeterRegistry registry) {
    Gauge.builder("sagas.active", active, AtomicInteger::get).register(registry);
  }

  public void increment() {
    active.incrementAndGet();
  }

  public void decrement() {
    active.decrementAndGet();
  }

  public void setActive(int count) {
    active.set(count);
  }
}
