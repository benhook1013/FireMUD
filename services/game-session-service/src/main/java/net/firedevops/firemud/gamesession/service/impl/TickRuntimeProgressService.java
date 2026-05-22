package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.client.AutomationScriptingClient;
import net.firedevops.firemud.gamesession.entity.RuntimeRegionStatus;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupRuntimeService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
final class TickRuntimeProgressService {
  private static final Logger logger = LoggingUtil.getLogger(TickRuntimeProgressService.class);

  private final MeterRegistry meterRegistry;
  private final RemoteFollowupRepository remoteFollowupRepository;
  private final RemoteFollowupRuntimeService remoteFollowupRuntimeService;
  private final AutomationScriptingClient automationScriptingClient;
  private final TickQueueControlService tickQueueControlService;

  @Value("${game.tick-duration-ms:1000}")
  private long tickDurationMs;

  @Value("${game.remote-followups.max-per-tick:16}")
  private int maxRemoteFollowupsPerTick;

  private final Map<String, Long> retryQueueDepthByTarget = new ConcurrentHashMap<>();
  private final AtomicLong retryQueueDepthTotal = new AtomicLong();
  private final AtomicInteger retryQueueTargetsWithPending = new AtomicInteger();
  private final Map<String, Long> remoteFollowupDueByTarget = new ConcurrentHashMap<>();
  private final Map<String, Long> remoteFollowupDrainLagByTarget = new ConcurrentHashMap<>();
  private final Map<String, Long> remoteFollowupDuePresenceByTarget = new ConcurrentHashMap<>();
  private final AtomicInteger remoteFollowupTargetsWithDue = new AtomicInteger();
  private final AtomicLong remoteFollowupDueTotal = new AtomicLong();
  private final AtomicLong remoteFollowupDrainLagMax = new AtomicLong();
  private Counter remoteFollowupBacklogOverBudgetCounter;

  TickRuntimeProgressService(
      MeterRegistry meterRegistry,
      RemoteFollowupRepository remoteFollowupRepository,
      RemoteFollowupRuntimeService remoteFollowupRuntimeService,
      AutomationScriptingClient automationScriptingClient,
      TickQueueControlService tickQueueControlService) {
    this.meterRegistry = meterRegistry;
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteFollowupRuntimeService = remoteFollowupRuntimeService;
    this.automationScriptingClient = automationScriptingClient;
    this.tickQueueControlService = tickQueueControlService;
  }

  @PostConstruct
  void init() {
    this.remoteFollowupBacklogOverBudgetCounter =
        meterRegistry.counter("remote_followups_backlog_over_budget_total");
    meterRegistry.gauge("game_session_retry_queue_depth_total", retryQueueDepthTotal);
    meterRegistry.gauge(
        "game_session_retry_queue_targets_with_pending", retryQueueTargetsWithPending);
    meterRegistry.gauge("remote_followups_due_total", remoteFollowupDueTotal);
    meterRegistry.gauge("remote_followups_drain_lag_ms", remoteFollowupDrainLagMax);
    meterRegistry.gauge(
        "game_session_remote_followups_targets_with_due", remoteFollowupTargetsWithDue);
  }

  RuntimeRegionStatus advanceRuntimeTickProgress(
      Long tenantId, Long gameInstanceId, TickQueueControlService.OwnershipSnapshot ownership) {
    RuntimeRegionStatus status =
        tickQueueControlService.requireRuntimeOwnership(
            tenantId, gameInstanceId, ownership.regionId());
    if (status.getRegionEpoch() != ownership.regionEpoch()
        || !status.getExecutorFence().equals(ownership.executorFence())
        || status.isPaused()) {
      throw new TickQueueControlService.StaleOwnershipException(
          "Cannot advance stale runtime tick progress for tenantId=%d gameInstanceId=%d"
              .formatted(tenantId, gameInstanceId));
    }
    status.setLastCommittedTickId(status.getLastCommittedTickId() + 1L);
    status.setUpdatedAt(Instant.now());
    return tickQueueControlService.saveRuntimeOwnership(status);
  }

  void reconcileRemoteFollowupTimeouts(RuntimeRegionStatus status) {
    if (status == null) {
      return;
    }
    int resultReconciled =
        remoteFollowupRuntimeService.reconcileResults(
            status.getTenantId(), status.getRegionId(), status.getRegionEpoch());
    if (resultReconciled > 0) {
      logger.info(
          "Reconciled remote followup results tenantId={} regionId={} regionEpoch={} tickId={} count={}",
          status.getTenantId(),
          status.getRegionId(),
          status.getRegionEpoch(),
          status.getLastCommittedTickId(),
          resultReconciled);
    }
    int reconciled =
        remoteFollowupRuntimeService.reconcileTimeouts(
            status.getTenantId(),
            status.getRegionId(),
            status.getRegionEpoch(),
            status.getLastCommittedTickId());
    if (reconciled > 0) {
      logger.info(
          "Reconciled remote followup timeouts tenantId={} regionId={} regionEpoch={} tickId={} count={}",
          status.getTenantId(),
          status.getRegionId(),
          status.getRegionEpoch(),
          status.getLastCommittedTickId(),
          reconciled);
    }
  }

  void reconcilePausedRemoteFollowupResults(
      Long tenantId, TickQueueControlService.OwnershipSnapshot ownership) {
    if (ownership == null || ownership.regionId() == null || ownership.regionId().isBlank()) {
      return;
    }
    int reconciled =
        remoteFollowupRuntimeService.reconcileResults(
            tenantId, ownership.regionId(), ownership.regionEpoch());
    if (reconciled > 0) {
      logger.info(
          "Reconciled remote followup results while paused tenantId={} regionId={} regionEpoch={} count={}",
          tenantId,
          ownership.regionId(),
          ownership.regionEpoch(),
          reconciled);
    }
  }

  void publishRuntimeTickProgress(RuntimeRegionStatus status) {
    if (status == null) {
      return;
    }
    ObserveRuntimeTickProgressResponse response =
        automationScriptingClient.observeRuntimeTickProgress(
            ObserveRuntimeTickProgressRequest.newBuilder()
                .setTenantId(Long.toString(status.getTenantId()))
                .setGameInstanceId(Long.toString(status.getGameInstanceId()))
                .setRegionId(status.getRegionId())
                .setRegionEpoch(status.getRegionEpoch())
                .setTickId(status.getLastCommittedTickId())
                .setObservedAtMs(status.getUpdatedAt().toEpochMilli())
                .build());
    if (response.hasError()) {
      logger.warn(
          "Automation runtime tick progress was not observed tenantId={} gameInstanceId={} tickId={} code={} message={}",
          status.getTenantId(),
          status.getGameInstanceId(),
          status.getLastCommittedTickId(),
          response.getError().getCode(),
          response.getError().getMessage());
    }
  }

  void updateRetryQueueDepth(Long tenantId, Long queueTargetId, long depth) {
    String gaugeKey = tenantId + ":" + queueTargetId;
    retryQueueDepthByTarget.compute(
        gaugeKey,
        (ignored, previousDepth) -> {
          long prior = previousDepth != null ? previousDepth : 0L;
          retryQueueDepthTotal.addAndGet(depth - prior);
          if (prior == 0L && depth > 0L) {
            retryQueueTargetsWithPending.incrementAndGet();
          } else if (prior > 0L && depth == 0L) {
            retryQueueTargetsWithPending.decrementAndGet();
          }
          return depth > 0L ? depth : null;
        });
  }

  void observeRemoteFollowupBacklog(
      Long tenantId, TickQueueControlService.OwnershipSnapshot ownership) {
    long dueCount =
        remoteFollowupRepository.countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
            tenantId,
            ownership.regionId(),
            RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
            ownership.lastCommittedTickId() + 1L);
    long drainLagMs =
        remoteFollowupRepository
            .findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                tenantId,
                ownership.regionId(),
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                ownership.lastCommittedTickId() + 1L)
            .map(
                followup ->
                    Math.max(
                        0L,
                        (ownership.lastCommittedTickId() + 1L - followup.getDueTickId())
                            * tickDurationMs))
            .orElse(0L);
    String gaugeKey = tenantId + ":" + ownership.regionId();
    remoteFollowupDueByTarget.compute(
        gaugeKey,
        (ignored, previousDepth) -> {
          long prior = previousDepth != null ? previousDepth : 0L;
          remoteFollowupDueTotal.addAndGet(dueCount - prior);
          return dueCount > 0L ? dueCount : null;
        });
    remoteFollowupDrainLagByTarget.compute(
        gaugeKey, (ignored, previousLag) -> drainLagMs > 0L ? drainLagMs : null);
    remoteFollowupDrainLagMax.set(
        remoteFollowupDrainLagByTarget.values().stream()
            .mapToLong(Long::longValue)
            .max()
            .orElse(0L));
    remoteFollowupDuePresenceByTarget.compute(
        gaugeKey,
        (ignored, previousDepth) -> {
          long prior = previousDepth != null ? previousDepth : 0L;
          if (prior == 0L && dueCount > 0L) {
            remoteFollowupTargetsWithDue.incrementAndGet();
            return 1L;
          } else if (prior > 0L && dueCount == 0L) {
            remoteFollowupTargetsWithDue.decrementAndGet();
            return 0L;
          }
          return prior;
        });
    if (dueCount > maxRemoteFollowupsPerTick) {
      remoteFollowupBacklogOverBudgetCounter.increment();
    }
  }
}
