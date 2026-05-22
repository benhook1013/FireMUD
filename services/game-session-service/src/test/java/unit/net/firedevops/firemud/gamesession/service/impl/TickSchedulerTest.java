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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    assertThat(meterRegistry.get("game_session_tick_scheduler_scheduled_total").counter().count())
        .isEqualTo(1.0);
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
    assertThat(meterRegistry.get("game_session_tick_scheduler_scheduled_total").counter().count())
        .isEqualTo(1.0);
    verify(tickService, times(0)).processTick(1L, 2L);
  }

  @Test
  void pausedSchedulerIncrementsPausedMetric() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    Executor executor = command -> {};
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler = new TickScheduler(repository, tickService, executor, meterRegistry);
    when(tickService.getTickStatus())
        .thenReturn(net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_PAUSED);

    scheduler.runTicks();

    assertThat(meterRegistry.get("game_session_tick_scheduler_paused_total").counter().count())
        .isEqualTo(1.0);
    verify(repository, times(0)).findByStatus("RUNNING");
  }

  @Test
  void sustainedRejectionsRaiseAlertCounter() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    Executor rejectingExecutor =
        command -> {
          throw new TaskRejectedException("queue full");
        };
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler =
        new TickScheduler(
            repository, tickService, rejectingExecutor, meterRegistry, () -> -1, 2, 1, 5, 75, 3);
    when(repository.findByStatus("RUNNING")).thenReturn(List.of(runningInstance(1L, 2L)));

    scheduler.runTicks();
    scheduler.runTicks();

    assertThat(
            meterRegistry
                .get("game_session_tick_scheduler_rejection_alert_total")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("game_session_tick_scheduler_rejection_consecutive_cycles")
                .gauge()
                .value())
        .isEqualTo(2.0);
  }

  @Test
  void sustainedMergeCyclesRaiseAlertCounter() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    CapturingExecutor executor = new CapturingExecutor();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler =
        new TickScheduler(
            repository, tickService, executor, meterRegistry, () -> -1, 3, 1, 2, 75, 3);
    GameInstance instance = runningInstance(1L, 2L);
    when(repository.findByStatus("RUNNING")).thenReturn(List.of(instance));

    scheduler.runTicks();
    scheduler.runTicks();
    scheduler.runTicks();

    assertThat(meterRegistry.get("game_session_tick_scheduler_merge_alert_total").counter().count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("game_session_tick_scheduler_merge_consecutive_cycles")
                .gauge()
                .value())
        .isEqualTo(2.0);
  }

  @Test
  void sustainedHighQueueDepthRaisesAlertCounter() {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    TickService tickService = mock(TickService.class);
    Executor executor = command -> {};
    AtomicInteger queueDepth = new AtomicInteger(80);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler =
        new TickScheduler(
            repository, tickService, executor, meterRegistry, queueDepth::get, 3, 1, 5, 75, 2);
    when(repository.findByStatus("RUNNING")).thenReturn(List.of(runningInstance(1L, 2L)));

    scheduler.runTicks();
    scheduler.runTicks();

    assertThat(
            meterRegistry
                .get("game_session_tick_scheduler_queue_depth_alert_total")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("game_session_tick_scheduler_queue_depth_consecutive_cycles")
                .gauge()
                .value())
        .isEqualTo(2.0);
  }

  @Test
  void realExecutorPressureRaisesThresholdAlertsAndThenRecovers() throws Exception {
    GameInstanceRepository repository = mock(GameInstanceRepository.class);
    BlockingTickService tickService = new BlockingTickService();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("tick-scheduler-test-");
    executor.initialize();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TickScheduler scheduler =
        new TickScheduler(repository, tickService, executor, meterRegistry, 2, 1, 2, 1, 2);
    when(repository.findByStatus("RUNNING"))
        .thenReturn(
            List.of(runningInstance(1L, 2L), runningInstance(1L, 3L), runningInstance(1L, 4L)))
        .thenReturn(
            List.of(runningInstance(1L, 2L), runningInstance(1L, 3L), runningInstance(1L, 4L)))
        .thenReturn(
            List.of(runningInstance(1L, 2L), runningInstance(1L, 3L), runningInstance(1L, 4L)))
        .thenReturn(List.of());

    try {
      scheduler.runTicks();
      assertThat(tickService.awaitFirstTickStarted()).isTrue();
      awaitQueueDepth(executor, 1);

      scheduler.runTicks();
      scheduler.runTicks();

      assertThat(
              meterRegistry.get("game_session_tick_scheduler_executor_queue_depth").gauge().value())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_rejection_alert_threshold_cycles")
                  .gauge()
                  .value())
          .isEqualTo(2.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_merge_alert_threshold_count")
                  .gauge()
                  .value())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_merge_alert_threshold_cycles")
                  .gauge()
                  .value())
          .isEqualTo(2.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_queue_depth_alert_threshold_count")
                  .gauge()
                  .value())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_queue_depth_alert_threshold_cycles")
                  .gauge()
                  .value())
          .isEqualTo(2.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_rejection_alert_total")
                  .counter()
                  .count())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry.get("game_session_tick_scheduler_merge_alert_total").counter().count())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_queue_depth_alert_total")
                  .counter()
                  .count())
          .isEqualTo(1.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_rejection_consecutive_cycles")
                  .gauge()
                  .value())
          .isEqualTo(3.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_merge_consecutive_cycles")
                  .gauge()
                  .value())
          .isEqualTo(2.0);
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_queue_depth_consecutive_cycles")
                  .gauge()
                  .value())
          .isEqualTo(3.0);

      tickService.releaseTicks();
      executor.shutdown();
      assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();

      scheduler.runTicks();

      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_rejection_consecutive_cycles")
                  .gauge()
                  .value())
          .isZero();
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_merge_consecutive_cycles")
                  .gauge()
                  .value())
          .isZero();
      assertThat(
              meterRegistry
                  .get("game_session_tick_scheduler_queue_depth_consecutive_cycles")
                  .gauge()
                  .value())
          .isZero();
      assertThat(
              meterRegistry.get("game_session_tick_scheduler_executor_queue_depth").gauge().value())
          .isZero();
    } finally {
      tickService.releaseTicks();
      executor.shutdown();
    }
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

  private static void awaitQueueDepth(ThreadPoolTaskExecutor executor, int expectedDepth)
      throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadlineNanos) {
      if (executor.getThreadPoolExecutor() != null
          && executor.getThreadPoolExecutor().getQueue().size() == expectedDepth) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Timed out waiting for executor queue depth " + expectedDepth);
  }

  private static final class BlockingTickService implements TickService {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void enqueueCommand(
        Long tenantId,
        Long queueTargetId,
        String commandId,
        String command,
        boolean requiresSoloTick) {}

    @Override
    public void processTick(Long tenantId, Long queueTargetId) {
      started.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting to release blocked tick", ex);
      }
    }

    @Override
    public String queryState(Long sessionId) {
      return "unknown";
    }

    @Override
    public void pauseTicks(String reason) {}

    @Override
    public void resumeTicks(String reason) {}

    @Override
    public void pauseTicksForGameInstance(Long gameInstanceId, String reason) {}

    @Override
    public void resumeTicksForGameInstance(Long gameInstanceId, String reason) {}

    @Override
    public long purgeQueuedAutomationCommandsForScriptPatch(
        Long tenantId,
        Long gameInstanceId,
        String regionId,
        String scriptPatchVersion,
        String reason) {
      return 0;
    }

    @Override
    public long purgeQueuedAutomationCommandsForPluginVersion(
        Long tenantId,
        Long gameInstanceId,
        String regionId,
        String pluginId,
        String pluginVersionId,
        String reason) {
      return 0;
    }

    @Override
    public net.firedevops.firemud.gamesession.v1.TickStatus getTickStatus() {
      return net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_RUNNING;
    }

    boolean awaitFirstTickStarted() throws InterruptedException {
      return started.await(5, TimeUnit.SECONDS);
    }

    void releaseTicks() {
      release.countDown();
    }
  }
}
