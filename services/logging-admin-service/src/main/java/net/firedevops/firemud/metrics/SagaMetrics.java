package net.firedevops.firemud.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class SagaMetrics {
  private final AtomicInteger activeSagas = new AtomicInteger();

  public SagaMetrics(MeterRegistry registry) {
    Gauge.builder("sagas.active", activeSagas, AtomicInteger::get).register(registry);
  }

  public void setActive(int count) {
    activeSagas.set(count);
  }
}
