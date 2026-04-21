package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignRevisionLedger;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignAggregateEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignRevisionLedgerRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignScopeEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class WorldDesignMutationServiceImplTest {
  @Mock private RegionRepository regionRepository;
  @Mock private ZoneRepository zoneRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private RoomExitRepository roomExitRepository;
  @Mock private GenerationRuleRepository generationRuleRepository;
  @Mock private WorldDesignRevisionLedgerRepository ledgerRepository;
  @Mock private WorldDesignAggregateEpochRepository aggregateEpochRepository;
  @Mock private WorldDesignScopeEpochRepository scopeEpochRepository;
  @Mock private GameDesignClient gameDesignClient;

  private WorldDesignMutationServiceImpl service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service =
        new WorldDesignMutationServiceImpl(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            generationRuleRepository,
            ledgerRepository,
            aggregateEpochRepository,
            scopeEpochRepository,
            gameDesignClient);
    when(gameDesignClient.getVersionState(1L, 7L))
        .thenReturn(versionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-1", "revision-1", "UPSERT", "REGION", ""))
        .thenReturn(Optional.empty());
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "REGION", 44L))
        .thenReturn(Optional.empty());
    when(regionRepository.save(any(Region.class)))
        .thenAnswer(
            invocation -> {
              Region region = invocation.getArgument(0);
              region.setId(44L);
              return region;
            });
    when(aggregateEpochRepository.save(any(WorldDesignAggregateEpoch.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(ledgerRepository.save(any(WorldDesignRevisionLedger.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void appliesRegionMutationAndAdvancesAggregateEpoch() {
    var result = service.applyMutation(regionCreateRequest());

    assertEquals("APPLIED", result.result());
    assertEquals(44L, result.aggregateId());
    assertEquals(1L, result.draftRevisionEpoch());
    verify(regionRepository).save(any(Region.class));
    verify(ledgerRepository).save(any(WorldDesignRevisionLedger.class));
  }

  @Test
  void duplicateRevisionReturnsNoopFromLedger() {
    WorldDesignRevisionLedger ledger = new WorldDesignRevisionLedger();
    ledger.setTenantId(1L);
    ledger.setVersionId(7L);
    ledger.setAppliedAggregateId(44L);
    ledger.setAggregateEpochAfter(1L);
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-1", "revision-1", "UPSERT", "REGION", ""))
        .thenReturn(Optional.of(ledger));

    var result = service.applyMutation(regionCreateRequest());

    assertEquals("NO_OP_ALREADY_APPLIED", result.result());
    assertEquals(44L, result.aggregateId());
    verify(regionRepository, never()).save(any(Region.class));
  }

  @Test
  void staleAggregateEpochFailsClosed() {
    Region existing = new Region();
    existing.setId(44L);
    existing.setTenantId(1L);
    existing.setVersionId(7L);
    when(regionRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 44L))
        .thenReturn(Optional.of(existing));
    WorldDesignAggregateEpoch epoch = new WorldDesignAggregateEpoch();
    epoch.setTenantId(1L);
    epoch.setVersionId(7L);
    epoch.setAggregateType("REGION");
    epoch.setAggregateId(44L);
    epoch.setDraftRevisionEpoch(2L);
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "REGION", 44L))
        .thenReturn(Optional.of(epoch));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> service.applyMutation(regionUpdateRequest()));

    assertEquals(true, ex.getMessage().startsWith("DRAFT_WRITE_CONFLICT:"));
  }

  @Test
  void nonDraftVersionIsRejectedBeforeMutation() {
    when(gameDesignClient.getVersionState(1L, 7L))
        .thenReturn(versionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> service.applyMutation(regionCreateRequest()));

    assertEquals(true, ex.getMessage().startsWith("INVALID_VERSION_STATE:"));
    verify(regionRepository, never()).save(any(Region.class));
  }

  private WorldDesignMutationRequestDto regionCreateRequest() {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-1",
        "revision-1",
        "UPSERT",
        "REGION",
        "",
        0L,
        "",
        "",
        0L,
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto regionUpdateRequest() {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-1",
        "revision-1",
        "UPSERT",
        "REGION",
        "44",
        0L,
        "",
        "",
        0L,
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null);
  }

  private GetVersionStateResponse versionState(VersionLifecycleState state) {
    return GetVersionStateResponse.newBuilder()
        .setVersionState(
            VersionStateSnapshot.newBuilder()
                .setTenantId("1")
                .setVersionId(7L)
                .setVersionState(state)
                .setVersionStateEpoch(1L)
                .build())
        .build();
  }
}
