package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Periodically processes ticks for all running sessions. */
@Component
public final class TickScheduler {
  private static final Logger logger = LoggerFactory.getLogger(TickScheduler.class);
  private static final int DEFAULT_REJECTION_ALERT_CONSECUTIVE_CYCLES = 3;
  private static final int DEFAULT_MERGE_ALERT_THRESHOLD = 1;
  private static final int DEFAULT_MERGE_ALERT_CONSECUTIVE_CYCLES = 5;
  private static final int DEFAULT_QUEUE_DEPTH_ALERT_THRESHOLD = 75;
  private static final int DEFAULT_QUEUE_DEPTH_ALERT_CONSECUTIVE_CYCLES = 3;

  private final GameInstanceRepository repository;
  private final TickService tickService;
  private final Executor tickExecutor;
  private final IntSupplier executorQueueDepthSupplier;
  private final Counter mergedCounter;
  private final Counter rejectedCounter;
  private final Counter scheduledCounter;
  private final Counter pausedCounter;
  private final Counter rejectionAlertCounter;
  private final Counter mergeAlertCounter;
  private final Counter queueDepthAlertCounter;
  private final Set<String> pendingOrRunningTicks = ConcurrentHashMap.newKeySet();
  private final AtomicInteger consecutiveRejectedCycles = new AtomicInteger();
  private final AtomicInteger consecutiveMergedCycles = new AtomicInteger();
  private final AtomicInteger consecutiveHighQueueDepthCycles = new AtomicInteger();
  private final int rejectionAlertConsecutiveCycles;
  private final int mergeAlertThreshold;
  private final int mergeAlertConsecutiveCycles;
  private final int queueDepthAlertThreshold;
  private final int queueDepthAlertConsecutiveCycles;

  @Autowired
  public TickScheduler(
      GameInstanceRepository repository,
      TickService tickService,
      @Qualifier("tickExecutor") Executor tickExecutor,
      MeterRegistry meterRegistry,
      @Value("${game.tick-scheduler.rejection-alert-consecutive-cycles:3}")
          int rejectionAlertConsecutiveCycles,
      @Value("${game.tick-scheduler.merge-alert-threshold:1}") int mergeAlertThreshold,
      @Value("${game.tick-scheduler.merge-alert-consecutive-cycles:5}")
          int mergeAlertConsecutiveCycles,
      @Value("${game.tick-scheduler.queue-depth-alert-threshold:75}") int queueDepthAlertThreshold,
      @Value("${game.tick-scheduler.queue-depth-alert-consecutive-cycles:3}")
          int queueDepthAlertConsecutiveCycles) {
    this(
        repository,
        tickService,
        tickExecutor,
        meterRegistry,
        resolveExecutorQueueDepthSupplier(tickExecutor),
        rejectionAlertConsecutiveCycles,
        mergeAlertThreshold,
        mergeAlertConsecutiveCycles,
        queueDepthAlertThreshold,
        queueDepthAlertConsecutiveCycles);
  }

  TickScheduler(
      GameInstanceRepository repository,
      TickService tickService,
      Executor tickExecutor,
      MeterRegistry meterRegistry) {
    this(
        repository,
        tickService,
        tickExecutor,
        meterRegistry,
        resolveExecutorQueueDepthSupplier(tickExecutor),
        DEFAULT_REJECTION_ALERT_CONSECUTIVE_CYCLES,
        DEFAULT_MERGE_ALERT_THRESHOLD,
        DEFAULT_MERGE_ALERT_CONSECUTIVE_CYCLES,
        DEFAULT_QUEUE_DEPTH_ALERT_THRESHOLD,
        DEFAULT_QUEUE_DEPTH_ALERT_CONSECUTIVE_CYCLES);
  }

  TickScheduler(
      GameInstanceRepository repository,
      TickService tickService,
      Executor tickExecutor,
      MeterRegistry meterRegistry,
      IntSupplier executorQueueDepthSupplier,
      int rejectionAlertConsecutiveCycles,
      int mergeAlertThreshold,
      int mergeAlertConsecutiveCycles,
      int queueDepthAlertThreshold,
      int queueDepthAlertConsecutiveCycles) {
    this.repository = repository;
    this.tickService = tickService;
    this.tickExecutor = tickExecutor;
    this.executorQueueDepthSupplier = executorQueueDepthSupplier;
    this.rejectionAlertConsecutiveCycles = rejectionAlertConsecutiveCycles;
    this.mergeAlertThreshold = mergeAlertThreshold;
    this.mergeAlertConsecutiveCycles = mergeAlertConsecutiveCycles;
    this.queueDepthAlertThreshold = queueDepthAlertThreshold;
    this.queueDepthAlertConsecutiveCycles = queueDepthAlertConsecutiveCycles;
    this.scheduledCounter = meterRegistry.counter("game_session_tick_scheduler_scheduled_total");
    this.mergedCounter = meterRegistry.counter("game_session_tick_scheduler_merged_total");
    this.rejectedCounter = meterRegistry.counter("game_session_tick_scheduler_rejected_total");
    this.pausedCounter = meterRegistry.counter("game_session_tick_scheduler_paused_total");
    this.rejectionAlertCounter =
        meterRegistry.counter("game_session_tick_scheduler_rejection_alert_total");
    this.mergeAlertCounter = meterRegistry.counter("game_session_tick_scheduler_merge_alert_total");
    this.queueDepthAlertCounter =
        meterRegistry.counter("game_session_tick_scheduler_queue_depth_alert_total");
    Gauge.builder("game_session_tick_scheduler_pending_sessions", pendingOrRunningTicks, Set::size)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_executor_queue_depth",
            executorQueueDepthSupplier,
            supplier -> Math.max(supplier.getAsInt(), 0))
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_rejection_consecutive_cycles",
            consecutiveRejectedCycles,
            AtomicInteger::get)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_merge_consecutive_cycles",
            consecutiveMergedCycles,
            AtomicInteger::get)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_queue_depth_consecutive_cycles",
            consecutiveHighQueueDepthCycles,
            AtomicInteger::get)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_rejection_alert_threshold_cycles",
            this,
            scheduler -> scheduler.rejectionAlertConsecutiveCycles)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_merge_alert_threshold_count",
            this,
            scheduler -> scheduler.mergeAlertThreshold)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_merge_alert_threshold_cycles",
            this,
            scheduler -> scheduler.mergeAlertConsecutiveCycles)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_queue_depth_alert_threshold_count",
            this,
            scheduler -> scheduler.queueDepthAlertThreshold)
        .register(meterRegistry);
    Gauge.builder(
            "game_session_tick_scheduler_queue_depth_alert_threshold_cycles",
            this,
            scheduler -> scheduler.queueDepthAlertConsecutiveCycles)
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${game.tick-duration-ms:1000}")
  @Timed(value = "game_session.tick_scheduler")
  public void runTicks() {
    if (tickService.getTickStatus()
        == net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_PAUSED) {
      pausedCounter.increment();
      return;
    }
    List<GameInstance> running = repository.findByStatus("RUNNING");
    int scheduled = 0;
    int merged = 0;
    int rejected = 0;
    for (GameInstance instance : running) {
      switch (scheduleTick(instance)) {
        case SCHEDULED -> scheduled++;
        case MERGED -> merged++;
        case REJECTED -> rejected++;
      }
    }
    int queueDepth = executorQueueDepthSupplier.getAsInt();
    if (rejected > 0) {
      logger.warn(
          "Tick scheduler under pressure: runningSessions={} scheduled={} merged={} rejected={} queueDepth={}",
          running.size(),
          scheduled,
          merged,
          rejected,
          Math.max(queueDepth, 0));
    } else if (merged > 0) {
      logger.info(
          "Tick scheduler merged overlapping work: runningSessions={} scheduled={} merged={} queueDepth={}",
          running.size(),
          scheduled,
          merged,
          Math.max(queueDepth, 0));
    }
    evaluatePressureThresholds(running.size(), merged, rejected, queueDepth);
  }

  private ScheduleOutcome scheduleTick(GameInstance instance) {
    String key = tickKey(instance.getTenantId(), instance.getId());
    if (!pendingOrRunningTicks.add(key)) {
      mergedCounter.increment();
      return ScheduleOutcome.MERGED;
    }
    try {
      scheduledCounter.increment();
      tickExecutor.execute(
          () -> {
            try {
              tickService.processTick(instance.getTenantId(), instance.getId());
            } finally {
              pendingOrRunningTicks.remove(key);
            }
          });
    } catch (TaskRejectedException ex) {
      pendingOrRunningTicks.remove(key);
      rejectedCounter.increment();
      logger.warn(
          "Tick scheduling rejected for tenant {} session {}",
          instance.getTenantId(),
          instance.getId(),
          ex);
      return ScheduleOutcome.REJECTED;
    }
    return ScheduleOutcome.SCHEDULED;
  }

  private void evaluatePressureThresholds(
      int runningSessions, int merged, int rejected, int queueDepth) {
    int rejectionCycles =
        rejected > 0
            ? consecutiveRejectedCycles.incrementAndGet()
            : reset(consecutiveRejectedCycles);
    if (rejected > 0 && rejectionCycles == rejectionAlertConsecutiveCycles) {
      rejectionAlertCounter.increment();
      logger.warn(
          "Tick scheduler rejection threshold reached: cycles={} runningSessions={} rejected={} threshold={}",
          rejectionCycles,
          runningSessions,
          rejected,
          rejectionAlertConsecutiveCycles);
    }

    int mergeCycles =
        merged >= mergeAlertThreshold
            ? consecutiveMergedCycles.incrementAndGet()
            : reset(consecutiveMergedCycles);
    if (merged >= mergeAlertThreshold && mergeCycles == mergeAlertConsecutiveCycles) {
      mergeAlertCounter.increment();
      logger.warn(
          "Tick scheduler merge threshold reached: cycles={} runningSessions={} merged={} mergeThreshold={}",
          mergeCycles,
          runningSessions,
          merged,
          mergeAlertThreshold);
    }

    if (queueDepth >= 0) {
      int queueDepthCycles =
          queueDepth >= queueDepthAlertThreshold
              ? consecutiveHighQueueDepthCycles.incrementAndGet()
              : reset(consecutiveHighQueueDepthCycles);
      if (queueDepth >= queueDepthAlertThreshold
          && queueDepthCycles == queueDepthAlertConsecutiveCycles) {
        queueDepthAlertCounter.increment();
        logger.warn(
            "Tick scheduler queue-depth threshold reached: cycles={} queueDepth={} threshold={}",
            queueDepthCycles,
            queueDepth,
            queueDepthAlertThreshold);
      }
    } else {
      reset(consecutiveHighQueueDepthCycles);
    }
  }

  private int reset(AtomicInteger counter) {
    counter.set(0);
    return 0;
  }

  private String tickKey(Long tenantId, Long sessionId) {
    return tenantId + ":" + sessionId;
  }

  private static IntSupplier resolveExecutorQueueDepthSupplier(Executor tickExecutor) {
    if (tickExecutor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor) {
      return () -> {
        if (threadPoolTaskExecutor.getThreadPoolExecutor() == null) {
          return 0;
        }
        return threadPoolTaskExecutor.getThreadPoolExecutor().getQueue().size();
      };
    }
    return () -> -1;
  }

  private enum ScheduleOutcome {
    SCHEDULED,
    MERGED,
    REJECTED
  }
}
