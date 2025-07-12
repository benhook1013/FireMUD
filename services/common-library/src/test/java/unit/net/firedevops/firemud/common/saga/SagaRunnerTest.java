package net.firedevops.firemud.common.saga;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import net.firedevops.firemud.metrics.SagaMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SagaRunnerTest {
  private SagaInstanceRepository instanceRepository;
  private SagaStepRepository stepRepository;
  private SagaRunner runner;

  @BeforeEach
  void setup() {
    instanceRepository = Mockito.mock(SagaInstanceRepository.class);
    stepRepository = Mockito.mock(SagaStepRepository.class);
    Mockito.when(instanceRepository.save(any()))
        .thenAnswer(
            inv -> {
              net.firedevops.firemud.common.saga.persistence.SagaInstance inst = inv.getArgument(0);
              inst.setId(1L);
              return inst;
            });
    Mockito.when(stepRepository.save(any()))
        .thenAnswer(
            inv -> {
              net.firedevops.firemud.common.saga.persistence.SagaStep step = inv.getArgument(0);
              step.setId(1L);
              return step;
            });
    runner =
        new SagaRunner(
            new SagaMetrics(new SimpleMeterRegistry()), instanceRepository, stepRepository);
  }

  @Test
  void runPersistsSagaRecords() throws SagaException {
    Saga saga = new SagaBuilder("test").step("step", () -> {}).build();
    runner.run(saga);
    verify(instanceRepository, Mockito.atLeastOnce()).save(any());
    verify(stepRepository, Mockito.atLeastOnce()).save(any());
  }
}
