package net.firedevops.firemud.common.saga;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.slf4j.MDC;

/** Executes short synchronous sagas with correlation ID handling and metrics. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Repositories and metrics are injected and immutable")
public class SagaRunner {
  private final SagaMetrics metrics;
  private final SagaInstanceRepository instanceRepository;
  private final SagaStepRepository stepRepository;

  public SagaRunner(
      SagaMetrics metrics,
      SagaInstanceRepository instanceRepository,
      SagaStepRepository stepRepository) {
    this.metrics = metrics;
    this.instanceRepository = instanceRepository;
    this.stepRepository = stepRepository;
  }

  public void run(Saga saga) throws SagaException {
    String correlationId = UUID.randomUUID().toString();
    metrics.increment();
    SagaInstance instance = new SagaInstance();
    instance.setSagaName(saga.getName());
    instance.setState("RUNNING");
    instance.setCreatedAt(Instant.now());
    instance.setUpdatedAt(Instant.now());
    instance = instanceRepository.save(instance);

    try (var ignored = MDC.putCloseable("correlationId", correlationId)) {
      java.util.List<net.firedevops.firemud.common.saga.SagaStep> executed = new ArrayList<>();
      for (net.firedevops.firemud.common.saga.SagaStep step : saga.getSteps()) {
        SagaStep record = new SagaStep();
        record.setInstanceId(instance.getId());
        record.setName(step.getName());
        record.setStatus("RUNNING");
        record.setAttempt(0);
        record.setCreatedAt(Instant.now());
        record.setUpdatedAt(Instant.now());
        record = stepRepository.save(record);
        try {
          step.execute();
          record.setStatus("COMPLETED");
          record.setUpdatedAt(Instant.now());
          stepRepository.save(record);
          executed.add(step);
        } catch (Exception e) {
          record.setStatus("FAILED");
          record.setUpdatedAt(Instant.now());
          stepRepository.save(record);
          Collections.reverse(executed);
          // compensate failing step first if it wasn't successful
          try {
            step.compensate();
          } catch (Exception ex) {
            // ignore
          }
          for (net.firedevops.firemud.common.saga.SagaStep s : executed) {
            try {
              s.compensate();
            } catch (Exception ex) {
              // ignore
            }
          }
          instance.setState("FAILED");
          instance.setUpdatedAt(Instant.now());
          instanceRepository.save(instance);
          throw new SagaException("Saga failed at step: " + step.getName(), e);
        }
      }
      instance.setState("COMPLETED");
      instance.setUpdatedAt(Instant.now());
      instanceRepository.save(instance);
    } finally {
      metrics.decrement();
    }
  }
}
