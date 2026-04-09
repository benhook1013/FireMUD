package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** Periodically processes ticks for all running sessions. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and service are not exposed")
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public final class TickScheduler {
  private static final Logger logger = LoggerFactory.getLogger(TickScheduler.class);
  private final GameInstanceRepository repository;
  private final TickService tickService;
  private final Executor tickExecutor;
  private final Counter mergedCounter;
  private final Counter rejectedCounter;
  private final Set<String> pendingOrRunningTicks = ConcurrentHashMap.newKeySet();

  public TickScheduler(
      GameInstanceRepository repository,
      TickService tickService,
      @Qualifier("tickExecutor") Executor tickExecutor,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.tickService = tickService;
    this.tickExecutor = tickExecutor;
    this.mergedCounter = meterRegistry.counter("game_session_tick_scheduler_merged_total");
    this.rejectedCounter = meterRegistry.counter("game_session_tick_scheduler_rejected_total");
    Gauge.builder("game_session_tick_scheduler_pending_sessions", pendingOrRunningTicks, Set::size)
        .register(meterRegistry);
    if (tickExecutor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor) {
      Gauge.builder(
              "game_session_tick_scheduler_executor_queue_depth",
              threadPoolTaskExecutor,
              executor -> {
                if (executor.getThreadPoolExecutor() == null) {
                  return 0;
                }
                return executor.getThreadPoolExecutor().getQueue().size();
              })
          .register(meterRegistry);
    }
  }

  @Scheduled(fixedDelayString = "${game.tick-duration-ms:1000}")
  @Timed(value = "game_session.tick_scheduler")
  public void runTicks() {
    if (tickService.getTickStatus()
        == net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_PAUSED) {
      return;
    }
    List<GameInstance> running = repository.findByStatus("RUNNING");
    for (GameInstance instance : running) {
      scheduleTick(instance);
    }
  }

  private void scheduleTick(GameInstance instance) {
    String key = tickKey(instance.getTenantId(), instance.getId());
    if (!pendingOrRunningTicks.add(key)) {
      mergedCounter.increment();
      return;
    }
    try {
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
    }
  }

  private String tickKey(Long tenantId, Long sessionId) {
    return tenantId + ":" + sessionId;
  }
}
