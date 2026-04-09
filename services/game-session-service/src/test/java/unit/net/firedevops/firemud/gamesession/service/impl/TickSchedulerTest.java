package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class TickSchedulerTest {

  @Test
  void duplicateScheduledTickMergesInsteadOfQueueingAgain() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    CapturingExecutor executor = new CapturingExecutor();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler = new TickScheduler(repository, tickService, executor, meterRegistry);
    GameInstance instance = runningInstance(1L, 2L);
    when(repository.findByStatus("RUNNING")).thenReturn(List.of(instance));

    scheduler.runTicks();
    scheduler.runTicks();

    assertThat(executor.queuedTasks()).isEqualTo(1);
    assertThat(meterRegistry.get("game_session_tick_scheduler_merged_total").counter().count())
        .isEqualTo(1.0);

    executor.runNext();

    verify(tickService, times(1)).processTick(1L, 2L);
  }

  @Test
  void rejectedTickSubmissionIncrementsMetric() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    Executor rejectingExecutor =
        command -> {
          throw new TaskRejectedException("queue full");
        };
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler =
        new TickScheduler(repository, tickService, rejectingExecutor, meterRegistry);
    when(repository.findByStatus("RUNNING")).thenReturn(List.of(runningInstance(1L, 2L)));

    scheduler.runTicks();

    assertThat(meterRegistry.get("game_session_tick_scheduler_rejected_total").counter().count())
        .isEqualTo(1.0);
    verify(tickService, times(0)).processTick(1L, 2L);
  }

  private static GameInstance runningInstance(Long tenantId, Long id) {
    GameInstance instance = new GameInstance();
    instance.setTenantId(tenantId);
    instance.setId(id);
    instance.setStatus("RUNNING");
    return instance;
  }

  private static final class CapturingExecutor implements Executor {
    private final Queue<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      queue.add(command);
    }

    int queuedTasks() {
      return queue.size();
    }

    void runNext() {
      Runnable runnable = queue.remove();
      runnable.run();
    }
  }
}
