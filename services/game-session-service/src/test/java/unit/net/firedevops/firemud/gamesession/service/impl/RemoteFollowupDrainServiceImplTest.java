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
import org.springframework.data.domain.PageRequest;
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
    first.setTargetEntityId("entity-1");
    first.setFailureCode("REMOTE_DRAIN_ABORTED");
    first.setFailureMessage("stale");
    RemoteFollowup second = followup("rf-2", 11L);
    second.setTargetEntityId("entity-2");
    when(remoteFollowupRepository
            .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
                1L,
                "region-b",
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                12L,
                Pageable.ofSize(8)))
        .thenReturn(List.of(first, second));

    RemoteFollowupDrainService.ClaimOutcome outcome =
        service.claimDueFollowups(1L, "region-b", 12L, "tb-1", 2);

    assertEquals(2, outcome.claimedCount());
    assertEquals(List.of("rf-1", "rf-2"), outcome.followupIds());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, first.getStatus());
    assertEquals("tb-1", first.getClaimedTickBatchId());
    assertEquals(1L, first.getClaimOrdinal());
    assertEquals(null, first.getFailureCode());
    assertEquals(null, first.getFailureMessage());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, second.getStatus());
    assertEquals(2L, second.getClaimOrdinal());
  }

  @Test
  void claimDueFollowupsPagesPastDuplicateHeavyWindowToFillFairBatch() {
    RemoteFollowup first = followup("rf-1", 10L);
    first.setTargetEntityId("entity-1");
    RemoteFollowup second = followup("rf-2", 11L);
    second.setTargetEntityId("entity-1");
    RemoteFollowup third = followup("rf-3", 12L);
    third.setTargetEntityId("entity-1");
    RemoteFollowup fourth = followup("rf-4", 13L);
    fourth.setTargetEntityId("entity-1");
    RemoteFollowup fifth = followup("rf-5", 14L);
    fifth.setTargetEntityId("entity-1");
    RemoteFollowup sixth = followup("rf-6", 15L);
    sixth.setTargetEntityId("entity-1");
    RemoteFollowup seventh = followup("rf-7", 16L);
    seventh.setTargetEntityId("entity-1");
    RemoteFollowup eighth = followup("rf-8", 17L);
    eighth.setTargetEntityId("entity-1");
    RemoteFollowup ninth = followup("rf-9", 18L);
    ninth.setTargetEntityId("entity-2");
    when(remoteFollowupRepository
            .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
                1L,
                "region-b",
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                18L,
                Pageable.ofSize(8)))
        .thenReturn(List.of(first, second, third, fourth, fifth, sixth, seventh, eighth));
    when(remoteFollowupRepository
            .findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
                1L,
                "region-b",
                RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED,
                18L,
                PageRequest.of(1, 8)))
        .thenReturn(List.of(ninth));

    RemoteFollowupDrainService.ClaimOutcome outcome =
        service.claimDueFollowups(1L, "region-b", 18L, "tb-1", 2);

    assertEquals(2, outcome.claimedCount());
    assertEquals(List.of("rf-1", "rf-9"), outcome.followupIds());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, first.getStatus());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, second.getStatus());
    assertEquals(RemoteFollowupRuntimeServiceImpl.FOLLOWUP_SCHEDULED, eighth.getStatus());
    assertEquals(RemoteFollowupDrainServiceImpl.FOLLOWUP_CLAIMED, ninth.getStatus());
    assertEquals(1L, first.getClaimOrdinal());
    assertEquals(2L, ninth.getClaimOrdinal());
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
    assertEquals(null, first.getClaimOrdinal());
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
