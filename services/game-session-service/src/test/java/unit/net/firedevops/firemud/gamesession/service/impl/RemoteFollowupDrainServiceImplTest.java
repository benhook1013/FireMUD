package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import net.firedevops.firemud.gamesession.repository.RemoteFollowupRepository;
import net.firedevops.firemud.gamesession.service.RemoteFollowupDrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class RemoteFollowupDrainServiceImplTest {
  private static final Instant NOW = Instant.parse("2026-05-01T02:00:00Z");

  private RemoteFollowupRepository remoteFollowupRepository;
  private RemoteFollowupDrainService service;

  @BeforeEach
  void setup() {
    remoteFollowupRepository = mock(RemoteFollowupRepository.class);
    when(remoteFollowupRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    service =
        new RemoteFollowupDrainServiceImpl(
            remoteFollowupRepository,
            meterRegistry.counter("gamesession_remote_followup_claimed_total"),
            meterRegistry.counter("gamesession_remote_followup_released_total"),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void claimDueFollowupsMarksClaimedRowsInOrder() {
    RemoteFollowup first = followup("rf-1", 10L);
    RemoteFollowup second = followup("rf-2", 11L);
    when(remoteFollowupRepository
            .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
                1L,
                "region-b",
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                12L,
                Pageable.ofSize(2)))
        .thenReturn(List.of(first, second));

    RemoteFollowupDrainService.ClaimOutcome outcome =
        service.claimDueFollowups(1L, "region-b", 12L, "tb-1", 2);

    assertEquals(2, outcome.claimedCount());
    assertEquals(List.of("rf-1", "rf-2"), outcome.followupIds());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, first.getStatus());
    assertEquals("tb-1", first.getClaimedTickBatchId());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, second.getStatus());
  }

  @Test
  void releaseClaimedFollowupsRestoresScheduledState() {
    RemoteFollowup first = followup("rf-1", 10L);
    first.setStatus(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED);
    first.setClaimedTickBatchId("tb-1");
    when(remoteFollowupRepository.findByClaimedTickBatchIdOrderByIdAsc("tb-1"))
        .thenReturn(List.of(first));

    int released = service.releaseClaimedFollowups("tb-1", "REMOTE_DRAIN_ABORTED", "rollback");

    assertEquals(1, released);
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, first.getStatus());
    assertEquals(null, first.getClaimedTickBatchId());
    assertEquals("REMOTE_DRAIN_ABORTED", first.getFailureCode());
    assertEquals("rollback", first.getFailureMessage());
  }

  private static RemoteFollowup followup(String followupId, long dueTickId) {
    RemoteFollowup followup = new RemoteFollowup();
    followup.setFollowupId(followupId);
    followup.setTenantId(1L);
    followup.setTargetRegionId("region-b");
    followup.setTargetRegionEpoch(4L);
    followup.setStatus(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED);
    followup.setDueTickId(dueTickId);
    followup.setUpdatedAt(NOW);
    return followup;
  }
}
