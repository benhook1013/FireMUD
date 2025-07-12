package net.firedevops.firemud.service;

import java.util.UUID;
import net.firedevops.firemud.common.saga.Saga;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Executes sagas with correlation ID handling and metrics. */
@Component
public class SagaRunner {
  private final SagaMetrics metrics;

  public SagaRunner(SagaMetrics metrics) {
    this.metrics = metrics;
  }

  public void run(Saga saga) throws SagaException {
    String correlationId = UUID.randomUUID().toString();
    metrics.increment();
    try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
      saga.run();
    } finally {
      metrics.decrement();
    }
  }
}
