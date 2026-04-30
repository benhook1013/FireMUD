package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        remoteFollowupRepository
            .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                tenantId,
                targetRegionId,
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                dueTickIdInclusive,
                PageRequest.of(0, limit));
    if (followups.isEmpty()) {
      return new ClaimOutcome(List.of(), 0);
    }

    Instant now = Instant.now(clock);
    for (RemoteFollowup followup : followups) {
      followup.setStatus(FOLLOWUP_CLAIMED);
      followup.setClaimedTickBatchId(tickBatchId);
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
}
