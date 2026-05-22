package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteFollowupDrainServiceImpl implements RemoteFollowupDrainService {
  private static final Logger logger = LoggingUtil.getLogger(RemoteFollowupDrainServiceImpl.class);

  static final String FOLLOWUP_CLAIMED = "CLAIMED";

  private final RemoteFollowupRepository remoteFollowupRepository;
  private final Counter remoteFollowupClaimedCounter;
  private final Counter remoteFollowupReleasedCounter;
  private final Clock clock;

  @Autowired
  public RemoteFollowupDrainServiceImpl(
      RemoteFollowupRepository remoteFollowupRepository, MeterRegistry meterRegistry) {
    this(
        remoteFollowupRepository,
        meterRegistry.counter("gamesession_remote_followup_claimed_total"),
        meterRegistry.counter("gamesession_remote_followup_released_total"),
        Clock.systemUTC());
  }

  RemoteFollowupDrainServiceImpl(
      RemoteFollowupRepository remoteFollowupRepository,
      Counter remoteFollowupClaimedCounter,
      Counter remoteFollowupReleasedCounter,
      Clock clock) {
    this.remoteFollowupRepository = remoteFollowupRepository;
    this.remoteFollowupClaimedCounter = remoteFollowupClaimedCounter;
    this.remoteFollowupReleasedCounter = remoteFollowupReleasedCounter;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ClaimOutcome claimDueFollowups(
      long tenantId,
      String targetRegionId,
      long dueTickIdInclusive,
      String tickBatchId,
      int limit) {
    requirePositive(tenantId, "tenant_id");
    requireNotBlank(targetRegionId, "target_region_id");
    requireNotBlank(tickBatchId, "tick_batch_id");
    requirePositive(limit, "limit");

    List<RemoteFollowup> followups =
        fairSelectedCandidates(tenantId, targetRegionId, dueTickIdInclusive, limit);
    if (followups.isEmpty()) {
      return new ClaimOutcome(List.of(), 0);
    }

    Instant now = Instant.now(clock);
    for (int index = 0; index < followups.size(); index++) {
      RemoteFollowup followup = followups.get(index);
      followup.setStatus(FOLLOWUP_CLAIMED);
      followup.setClaimedTickBatchId(tickBatchId);
      followup.setClaimOrdinal((long) index + 1L);
      followup.setQueueSourceKind(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_KIND);
      followup.setQueueSourceState(
          RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_STATE_CLAIMED);
      followup.setQueueSourceOrdinal((long) index + 1L);
      followup.setQueueSourceDueTickId(followup.getDueTickId());
      followup.setFailureCode(null);
      followup.setFailureMessage(null);
      followup.setUpdatedAt(now);
    }
    remoteFollowupRepository.saveAll(followups);
    remoteFollowupClaimedCounter.increment(followups.size());
    logger.info(
        "Claimed remote followups tenantId={} targetRegionId={} tickBatchId={} count={}",
        tenantId,
        targetRegionId,
        tickBatchId,
        followups.size());
    return new ClaimOutcome(
        followups.stream().map(RemoteFollowup::getFollowupId).toList(), followups.size());
  }

  @Override
  @Transactional
  public int releaseClaimedFollowups(
      String tickBatchId, String failureCode, String failureMessage) {
    requireNotBlank(tickBatchId, "tick_batch_id");
    List<RemoteFollowup> followups =
        remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc(tickBatchId);
    if (followups.isEmpty()) {
      return 0;
    }

    Instant now = Instant.now(clock);
    for (RemoteFollowup followup : followups) {
      followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED);
      followup.setClaimedTickBatchId(null);
      followup.setClaimOrdinal(null);
      followup.setQueueSourceKind(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_KIND);
      followup.setQueueSourceState(
          RemoteFollowupRuntimeServiceImpl.FOLLOWUP_QUEUE_SOURCE_STATE_SCHEDULED);
      followup.setQueueSourceOrdinal(null);
      followup.setQueueSourceDueTickId(followup.getDueTickId());
      followup.setFailureCode(failureCode);
      followup.setFailureMessage(truncate(failureMessage));
      followup.setUpdatedAt(now);
    }
    remoteFollowupRepository.saveAll(followups);
    remoteFollowupReleasedCounter.increment(followups.size());
    logger.warn(
        "Released claimed remote followups tickBatchId={} count={} failureCode={}",
        tickBatchId,
        followups.size(),
        failureCode);
    return followups.size();
  }

  private static void requirePositive(long value, String fieldName) {
    if (value <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
  }

  private static void requireNotBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static String truncate(String value) {
    if (value == null || value.length() <= 500) {
      return value;
    }
    return value.substring(0, 500);
  }

  private static int candidateWindow(int limit) {
    return Math.max(limit, limit * 4);
  }

  private List<RemoteFollowup> fairSelectedCandidates(
      long tenantId, String targetRegionId, long dueTickIdInclusive, int limit) {
    int pageSize = candidateWindow(limit);
    ArrayList<RemoteFollowup> selected = new ArrayList<>(limit);
    Set<String> claimedEntityKeys = new LinkedHashSet<>();
    for (int page = 0; selected.size() < limit; page++) {
      List<RemoteFollowup> candidates =
          remoteFollowupRepository
              .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
                  tenantId,
                  targetRegionId,
                  RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                  dueTickIdInclusive,
                  PageRequest.of(page, pageSize));
      if (candidates.isEmpty()) {
        break;
      }
      for (RemoteFollowup candidate : candidates) {
        String entityKey = claimEntityKey(candidate);
        if (!claimedEntityKeys.add(entityKey)) {
          continue;
        }
        selected.add(candidate);
        if (selected.size() >= limit) {
          break;
        }
      }
      if (candidates.size() < pageSize) {
        break;
      }
    }
    return List.copyOf(selected);
  }

  private static String claimEntityKey(RemoteFollowup followup) {
    if (followup.getClaimTargetAggregate() != null
        && !followup.getClaimTargetAggregate().isBlank()) {
      return followup.getClaimTargetAggregate();
    }
    if (followup.getTargetEntityId() != null && !followup.getTargetEntityId().isBlank()) {
      return "entity:" + followup.getTargetEntityId();
    }
    return "game-instance:" + followup.getTargetGameInstanceId();
  }
}
