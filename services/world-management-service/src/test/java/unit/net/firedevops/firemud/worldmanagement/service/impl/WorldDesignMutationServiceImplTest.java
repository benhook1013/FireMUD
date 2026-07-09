package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignRevisionLedger;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignScopeEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignAggregateEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignRevisionLedgerRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignScopeEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
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
  @Mock private WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository;
  @Mock private WorldDesignRevisionLedgerRepository ledgerRepository;
  @Mock private WorldDesignAggregateEpochRepository aggregateEpochRepository;
  @Mock private WorldDesignScopeEpochRepository scopeEpochRepository;
  @Mock private GameDesignClient gameDesignClient;
  @Mock private EntityManagementClient entityManagementClient;

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
            worldEntitySpawnBindingRepository,
            ledgerRepository,
            aggregateEpochRepository,
            scopeEpochRepository,
            gameDesignClient,
            entityManagementClient);
    when(gameDesignClient.getVersionState(1L, 7L))
        .thenReturn(versionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-1", "revision-1", "UPSERT", "REGION", ""))
        .thenReturn(Optional.empty());
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "REGION", 44L))
        .thenReturn(Optional.empty());
    when(entityManagementClient.validateEntityTemplateReference(1L, 7L, "NPC", 55L))
        .thenReturn(true);
    when(regionRepository.save(any(Region.class)))
        .thenAnswer(
            invocation -> {
              Region region = invocation.getArgument(0);
              region.setId(44L);
              return region;
            });
    when(aggregateEpochRepository.save(any(WorldDesignAggregateEpoch.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(scopeEpochRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
            any(Long.class), any(Long.class), any(String.class), any(String.class)))
        .thenReturn(Optional.empty());
    when(scopeEpochRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
    verify(regionRepository, never()).save(any(Region.class));
  }

  @Test
  void staleScopeEpochFailsBeforeReplaceScopeMutation() {
    WorldDesignScopeEpoch scopeEpoch = new WorldDesignScopeEpoch();
    scopeEpoch.setTenantId(1L);
    scopeEpoch.setVersionId(7L);
    scopeEpoch.setScopeType("ZONE_SUBTREE");
    scopeEpoch.setScopeId("12");
    scopeEpoch.setDraftScopeRevisionEpoch(2L);
    when(scopeEpochRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
            1L, 7L, "ZONE_SUBTREE", "12"))
        .thenReturn(Optional.of(scopeEpoch));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.applyMutation(
                    spawnBindingRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE")));

    assertEquals(true, ex.getMessage().startsWith("DRAFT_WRITE_CONFLICT:"));
    verify(roomRepository, never()).findByTenantIdAndVersionIdAndId(1L, 7L, 12L);
    verify(worldEntitySpawnBindingRepository, never()).deleteAll(any());
    verify(worldEntitySpawnBindingRepository, never()).save(any(WorldEntitySpawnBinding.class));
  }

  @Test
  void scopedRegionMutationRejectsZeroDeclaredScopeIdBeforeSave() {
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-region-scope", "revision-region-scope", "UPSERT", "REGION", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(regionCreateRequestWithScope("REGION_SUBTREE", "0")));

    assertEquals("INVALID_ARGUMENT: scope_id must be positive", ex.getMessage());
    verify(regionRepository, never()).save(any(Region.class));
  }

  @Test
  void scopedZoneMutationRejectsZeroDeclaredScopeIdBeforeSave() {
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-zone-scope", "revision-zone-scope", "UPSERT", "ZONE", ""))
        .thenReturn(Optional.empty());
    when(regionRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 44L))
        .thenReturn(Optional.of(region(44L)));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(zoneCreateRequestWithScope("ZONE_SUBTREE", "0")));

    assertEquals("INVALID_ARGUMENT: scope_id must be positive", ex.getMessage());
    verify(zoneRepository, never()).save(any(Zone.class));
  }

  @Test
  void staleNaturalKeySpawnBindingEpochFailsBeforeReplaceScopeDelete() {
    Zone zone = zone(12L, 99L);
    Room targetRoom = room(12L, zone);
    WorldEntitySpawnBinding existing = binding(102L, targetRoom);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L))
        .thenReturn(Optional.of(targetRoom));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                1L, 7L, 12L, "NPC", 55L))
        .thenReturn(Optional.of(existing));
    WorldDesignAggregateEpoch epoch = new WorldDesignAggregateEpoch();
    epoch.setTenantId(1L);
    epoch.setVersionId(7L);
    epoch.setAggregateType("WORLD_ENTITY_SPAWN_BINDING");
    epoch.setAggregateId(102L);
    epoch.setDraftRevisionEpoch(2L);
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "WORLD_ENTITY_SPAWN_BINDING", 102L))
        .thenReturn(Optional.of(epoch));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.applyMutation(
                    spawnBindingRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE")));

    assertEquals(true, ex.getMessage().startsWith("DRAFT_WRITE_CONFLICT:"));
    verify(worldEntitySpawnBindingRepository, never()).deleteAll(any());
    verify(worldEntitySpawnBindingRepository, never()).save(any(WorldEntitySpawnBinding.class));
  }

  @Test
  void newAggregateWithNonZeroExpectedEpochFailsBeforeMutation() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(regionCreateRequestWithExpectedEpoch(1L)));

    assertEquals(
        "DRAFT_WRITE_CONFLICT: expected Draft aggregate epoch 1 but found 0", ex.getMessage());
    verify(regionRepository, never()).save(any(Region.class));
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

  @Test
  void spawnBindingRejectsMissingEntityTemplate() {
    Room room = new Room();
    room.setId(12L);
    room.setTenantId(1L);
    room.setVersionId(7L);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L)).thenReturn(Optional.of(room));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());
    when(entityManagementClient.validateEntityTemplateReference(1L, 7L, "NPC", 55L))
        .thenReturn(false);

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> service.applyMutation(spawnBindingRequest()));

    assertEquals(true, ex.getMessage().startsWith("UNRESOLVED_REFERENCE:"));
  }

  @Test
  void seedAppendOnlyRejectsAggregateRewrite() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(regionUpdateRequestWithPolicy("SEED_APPEND_ONLY")));

    assertEquals(
        "OUT_OF_SYNC: SEED_APPEND_ONLY cannot rewrite an existing aggregate", ex.getMessage());
    verify(regionRepository, never()).save(any(Region.class));
  }

  @Test
  void seedAppendOnlyRejectsDelete() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(regionDeleteRequestWithPolicy("SEED_APPEND_ONLY")));

    assertEquals("OUT_OF_SYNC: SEED_APPEND_ONLY cannot delete existing rows", ex.getMessage());
    verify(regionRepository, never()).delete(any(Region.class));
  }

  @Test
  void replaceScopeClearsExistingSpawnBindingsWithinZoneSubtree() {
    Zone zone = zone(12L, 99L);
    Zone otherZone = zone(13L, 99L);
    Room targetRoom = room(12L, zone);
    Room inScopeRoom = room(13L, zone);
    Room outOfScopeRoom = room(14L, otherZone);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L))
        .thenReturn(Optional.of(targetRoom));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(binding(100L, inScopeRoom), binding(101L, outOfScopeRoom)));
    when(worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                1L, 7L, 12L, "NPC", 55L))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository.save(any(WorldEntitySpawnBinding.class)))
        .thenAnswer(
            invocation -> {
              WorldEntitySpawnBinding binding = invocation.getArgument(0);
              binding.setId(102L);
              return binding;
            });
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "WORLD_ENTITY_SPAWN_BINDING", 102L))
        .thenReturn(Optional.empty());

    var result =
        service.applyMutation(spawnBindingRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE"));

    assertEquals("APPLIED", result.result());
    verify(worldEntitySpawnBindingRepository)
        .deleteAll(
            org.mockito.ArgumentMatchers.argThat(
                bindings -> {
                  java.util.Iterator<? extends WorldEntitySpawnBinding> iterator =
                      bindings.iterator();
                  if (!iterator.hasNext()) {
                    return false;
                  }
                  WorldEntitySpawnBinding binding = iterator.next();
                  return !iterator.hasNext()
                      && binding.getId().equals(100L)
                      && binding.getRoom().getId().equals(13L);
                }));
  }

  @Test
  void replaceScopeRejectsSpawnBindingOutsideDeclaredZoneSubtree() {
    Zone otherZone = zone(13L, 99L);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L))
        .thenReturn(Optional.of(room(12L, otherZone)));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.applyMutation(
                    spawnBindingRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE")));

    assertEquals("OUT_OF_SYNC: spawn binding room is outside the declared scope", ex.getMessage());
    verify(worldEntitySpawnBindingRepository, never()).deleteAll(any());
  }

  @Test
  void replaceScopeClearsExistingSpawnBindingsWithinRegionSubtree() {
    Zone targetZone = zone(12L, 99L);
    Zone sameRegionOtherZone = zone(13L, 99L);
    Zone otherRegionZone = zone(14L, 100L);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L))
        .thenReturn(Optional.of(room(12L, targetZone)));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(
            List.of(
                binding(100L, room(13L, sameRegionOtherZone)),
                binding(101L, room(14L, otherRegionZone))));
    when(worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                1L, 7L, 12L, "NPC", 55L))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository.save(any(WorldEntitySpawnBinding.class)))
        .thenAnswer(
            invocation -> {
              WorldEntitySpawnBinding binding = invocation.getArgument(0);
              binding.setId(102L);
              return binding;
            });
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "WORLD_ENTITY_SPAWN_BINDING", 102L))
        .thenReturn(Optional.empty());

    var result =
        service.applyMutation(
            spawnBindingRequestWithScope("REGION_SUBTREE", "99", "REPLACE_SCOPE"));

    assertEquals("APPLIED", result.result());
    verify(worldEntitySpawnBindingRepository)
        .deleteAll(
            org.mockito.ArgumentMatchers.argThat(
                bindings -> {
                  java.util.Iterator<? extends WorldEntitySpawnBinding> iterator =
                      bindings.iterator();
                  if (!iterator.hasNext()) {
                    return false;
                  }
                  WorldEntitySpawnBinding binding = iterator.next();
                  return !iterator.hasNext()
                      && binding.getId().equals(100L)
                      && binding.getRoom().getId().equals(13L);
                }));
  }

  @Test
  void replaceScopeRejectsNewEmptyRegionForSpawnBindings() {
    Zone zone = zone(12L, 99L);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L))
        .thenReturn(Optional.of(room(12L, zone)));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-2", "revision-2", "UPSERT", "WORLD_ENTITY_SPAWN_BINDING", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.applyMutation(
                    spawnBindingRequestWithScope("NEW_EMPTY_REGION", "12", "REPLACE_SCOPE")));

    assertEquals(
        "UNSUPPORTED_SCOPE: REPLACE_SCOPE for world entity spawn bindings does not support NEW_EMPTY_REGION",
        ex.getMessage());
    verify(worldEntitySpawnBindingRepository, never()).deleteAll(any());
  }

  @Test
  void scopedRoomMutationRejectsRoomOutsideDeclaredZoneSubtree() {
    Zone zone = zone(13L, 99L);
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 13L)).thenReturn(Optional.of(zone));
    when(roomRepository.save(any(Room.class)))
        .thenAnswer(
            invocation -> {
              Room room = invocation.getArgument(0);
              room.setId(88L);
              return room;
            });
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-room", "revision-room", "UPSERT", "ROOM", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(roomCreateRequestWithScope("ZONE_SUBTREE", "12")));

    assertEquals("OUT_OF_SYNC: room mutation is outside the declared scope", ex.getMessage());
    verify(ledgerRepository, never()).save(any(WorldDesignRevisionLedger.class));
  }

  @Test
  void scopedRoomMutationRejectsZeroDeclaredScopeIdBeforeSave() {
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 13L))
        .thenReturn(Optional.of(zone(13L, 99L)));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-room", "revision-room", "UPSERT", "ROOM", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(roomCreateRequestWithScope("ZONE_SUBTREE", "0")));

    assertEquals("INVALID_ARGUMENT: scope_id must be positive", ex.getMessage());
    verify(roomRepository, never()).save(any(Room.class));
  }

  @Test
  void scopedRoomMutationAllowsRoomInsideDeclaredRegionSubtree() {
    Zone zone = zone(13L, 99L);
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 13L)).thenReturn(Optional.of(zone));
    when(roomRepository.save(any(Room.class)))
        .thenAnswer(
            invocation -> {
              Room room = invocation.getArgument(0);
              room.setId(88L);
              return room;
            });
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-room", "revision-room", "UPSERT", "ROOM", ""))
        .thenReturn(Optional.empty());
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "ROOM", 88L))
        .thenReturn(Optional.empty());

    var result = service.applyMutation(roomCreateRequestWithScope("REGION_SUBTREE", "99"));

    assertEquals("APPLIED", result.result());
    assertEquals(1L, result.draftScopeRevisionEpoch());
  }

  @Test
  void scopedRoomExitRejectsExitCrossingOutsideDeclaredZoneSubtree() {
    Zone zone = zone(12L, 99L);
    Zone otherZone = zone(13L, 99L);
    Room fromRoom = room(21L, zone);
    Room toRoom = room(22L, otherZone);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 21L))
        .thenReturn(Optional.of(fromRoom));
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 22L))
        .thenReturn(Optional.of(toRoom));
    when(roomExitRepository.save(any(RoomExit.class)))
        .thenAnswer(
            invocation -> {
              RoomExit exit = invocation.getArgument(0);
              exit.setId(31L);
              return exit;
            });
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-exit", "revision-exit", "UPSERT", "ROOM_EXIT", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(roomExitCreateRequestWithScope("ZONE_SUBTREE", "12")));

    assertEquals("OUT_OF_SYNC: room exit mutation is outside the declared scope", ex.getMessage());
    verify(ledgerRepository, never()).save(any(WorldDesignRevisionLedger.class));
  }

  @Test
  void scopedRoomExitRejectsZeroDeclaredScopeIdBeforeSave() {
    Zone zone = zone(12L, 99L);
    Room fromRoom = room(21L, zone);
    Room toRoom = room(22L, zone);
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 21L))
        .thenReturn(Optional.of(fromRoom));
    when(roomRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 22L))
        .thenReturn(Optional.of(toRoom));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L, 7L, "commit-exit", "revision-exit", "UPSERT", "ROOM_EXIT", ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.applyMutation(roomExitCreateRequestWithScope("ZONE_SUBTREE", "0")));

    assertEquals("INVALID_ARGUMENT: scope_id must be positive", ex.getMessage());
    verify(roomExitRepository, never()).save(any(RoomExit.class));
  }

  @Test
  void replaceScopeClearsExistingGenerationRulesWithinDeclaredZoneScope() {
    Zone zone = zone(12L, 99L);
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L)).thenReturn(Optional.of(zone));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L,
                7L,
                "commit-generation",
                "revision-generation",
                "UPSERT",
                "GENERATION_RULE",
                ""))
        .thenReturn(Optional.empty());
    GenerationRule existing = generationRule(70L, "ZONE_SUBTREE", "12", "density");
    when(generationRuleRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeIdOrderByIdAsc(
            1L, 7L, "ZONE_SUBTREE", "12"))
        .thenReturn(List.of(existing));
    when(generationRuleRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
            1L, 7L, "ZONE_SUBTREE", "12", "population"))
        .thenReturn(Optional.empty());
    when(generationRuleRepository.save(any(GenerationRule.class)))
        .thenAnswer(
            invocation -> {
              GenerationRule rule = invocation.getArgument(0);
              rule.setId(71L);
              return rule;
            });
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "GENERATION_RULE", 71L))
        .thenReturn(Optional.empty());

    var result =
        service.applyMutation(
            generationRuleRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE"));

    assertEquals("APPLIED", result.result());
    verify(generationRuleRepository)
        .deleteAll(
            org.mockito.ArgumentMatchers.argThat(
                rules -> {
                  java.util.Iterator<? extends GenerationRule> iterator = rules.iterator();
                  return iterator.hasNext()
                      && iterator.next().getId().equals(70L)
                      && !iterator.hasNext();
                }));
  }

  @Test
  void scopedGenerationRuleRejectsMissingDeclaredScope() {
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L)).thenReturn(Optional.empty());
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L,
                7L,
                "commit-generation",
                "revision-generation",
                "UPSERT",
                "GENERATION_RULE",
                ""))
        .thenReturn(Optional.empty());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.applyMutation(
                    generationRuleRequestWithScope("ZONE_SUBTREE", "12", "REPLACE_SCOPE")));

    assertEquals("UNRESOLVED_REFERENCE: generation rule zone scope not found", ex.getMessage());
    verify(generationRuleRepository, never()).save(any(GenerationRule.class));
  }

  @Test
  void replaceScopeAppliesGeneratedSubtreeInOneScopedMutation() {
    Zone zone = zone(12L, 99L);
    Room staleRoom = room(40L, zone);
    Room targetRoom = room(41L, zone);
    when(zoneRepository.findByTenantIdAndVersionIdAndId(1L, 7L, 12L)).thenReturn(Optional.of(zone));
    when(ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                1L,
                7L,
                "commit-subtree",
                "revision-subtree",
                "UPSERT",
                "WORLD_GENERATION_SUBTREE",
                ""))
        .thenReturn(Optional.empty());
    when(worldEntitySpawnBindingRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(binding(80L, staleRoom)));
    RoomExit staleExit = new RoomExit();
    staleExit.setId(81L);
    staleExit.setFromRoom(staleRoom);
    staleExit.setToRoom(targetRoom);
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(staleExit));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 7L))
        .thenReturn(List.of(staleRoom));
    when(generationRuleRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeIdOrderByIdAsc(
            1L, 7L, "ZONE_SUBTREE", "12"))
        .thenReturn(List.of(generationRule(82L, "ZONE_SUBTREE", "12", "old-density")));
    when(generationRuleRepository.findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
            1L, 7L, "ZONE_SUBTREE", "12", "population"))
        .thenReturn(Optional.empty());
    when(roomRepository.save(any(Room.class)))
        .thenAnswer(
            invocation -> {
              Room room = invocation.getArgument(0);
              room.setId("room-a".equals(room.getName()) ? 90L : 91L);
              return room;
            });
    when(entityManagementClient.validateEntityTemplateReference(1L, 7L, "NPC", 55L))
        .thenReturn(true);
    when(worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                1L, 7L, 90L, "NPC", 55L))
        .thenReturn(Optional.empty());
    when(aggregateEpochRepository.findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
            1L, 7L, "WORLD_GENERATION_SUBTREE", 2_000_000_000_012L))
        .thenReturn(Optional.empty());

    var result = service.applyMutation(generationSubtreeRequest("REPLACE_SCOPE"));

    assertEquals("APPLIED", result.result());
    assertEquals(2_000_000_000_012L, result.aggregateId());
    assertEquals(1L, result.draftScopeRevisionEpoch());
    verify(worldEntitySpawnBindingRepository).deleteAll(any());
    verify(roomExitRepository).deleteAll(any());
    verify(roomRepository).deleteAll(any());
    verify(generationRuleRepository).deleteAll(any());
    verify(roomExitRepository).save(any(RoomExit.class));
    verify(worldEntitySpawnBindingRepository).save(any(WorldEntitySpawnBinding.class));
  }

  private WorldDesignMutationRequestDto regionCreateRequest() {
    return regionCreateRequestWithExpectedEpoch(0L);
  }

  private WorldDesignMutationRequestDto regionCreateRequestWithExpectedEpoch(long expectedEpoch) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-1",
        "revision-1",
        "UPSERT",
        "REGION",
        "",
        expectedEpoch,
        "",
        "",
        0L,
        "",
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
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
        "",
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto spawnBindingRequest() {
    return spawnBindingRequestWithScope("", "", "");
  }

  private WorldDesignMutationRequestDto spawnBindingRequestWithScope(
      String scopeType, String scopeId, String scopeMutationPolicy) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-2",
        "revision-2",
        "UPSERT",
        "WORLD_ENTITY_SPAWN_BINDING",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        scopeMutationPolicy,
        null,
        null,
        null,
        null,
        null,
        new WorldDesignMutationRequestDto.WorldEntitySpawnBindingMutationDto(
            "12", "NPC", "55", 2, 30),
        null);
  }

  private WorldDesignMutationRequestDto regionUpdateRequestWithPolicy(String scopeMutationPolicy) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-3",
        "revision-3",
        "UPSERT",
        "REGION",
        "44",
        0L,
        "ZONE_SUBTREE",
        "12",
        0L,
        scopeMutationPolicy,
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto regionCreateRequestWithScope(
      String scopeType, String scopeId) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-region-scope",
        "revision-region-scope",
        "UPSERT",
        "REGION",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        "REPLACE_SCOPE",
        new WorldDesignMutationRequestDto.RegionMutationDto(
            "North", "rain", 0, 123L, "ROOM_GRAPH", "{}", 1.0d),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto regionDeleteRequestWithPolicy(String scopeMutationPolicy) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-4",
        "revision-4",
        "DELETE",
        "REGION",
        "44",
        0L,
        "ZONE_SUBTREE",
        "12",
        0L,
        scopeMutationPolicy,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto zoneCreateRequestWithScope(
      String scopeType, String scopeId) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-zone-scope",
        "revision-zone-scope",
        "UPSERT",
        "ZONE",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        "REPLACE_SCOPE",
        null,
        new WorldDesignMutationRequestDto.ZoneMutationDto("Market", "44"),
        null,
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto roomCreateRequestWithScope(
      String scopeType, String scopeId) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-room",
        "revision-room",
        "UPSERT",
        "ROOM",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        "REPLACE_SCOPE",
        null,
        null,
        new WorldDesignMutationRequestDto.RoomMutationDto(
            "North Gate", "A gate room", "13", null, null),
        null,
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto roomExitCreateRequestWithScope(
      String scopeType, String scopeId) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-exit",
        "revision-exit",
        "UPSERT",
        "ROOM_EXIT",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        "REPLACE_SCOPE",
        null,
        null,
        null,
        new WorldDesignMutationRequestDto.RoomExitMutationDto("21", "22", "north", 1),
        null,
        null,
        null);
  }

  private WorldDesignMutationRequestDto generationRuleRequestWithScope(
      String scopeType, String scopeId, String scopeMutationPolicy) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-generation",
        "revision-generation",
        "UPSERT",
        "GENERATION_RULE",
        "",
        0L,
        scopeType,
        scopeId,
        0L,
        scopeMutationPolicy,
        null,
        null,
        null,
        null,
        new WorldDesignMutationRequestDto.GenerationRuleMutationDto("population", "dense"),
        null,
        null);
  }

  private WorldDesignMutationRequestDto generationSubtreeRequest(String scopeMutationPolicy) {
    return new WorldDesignMutationRequestDto(
        1L,
        7L,
        "commit-subtree",
        "revision-subtree",
        "UPSERT",
        "WORLD_GENERATION_SUBTREE",
        "",
        0L,
        "ZONE_SUBTREE",
        "12",
        0L,
        scopeMutationPolicy,
        null,
        null,
        null,
        null,
        null,
        null,
        new WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto(
            List.of(
                new WorldDesignMutationRequestDto.GenerationRuleMutationDto("population", "dense")),
            List.of(
                new WorldDesignMutationRequestDto.GeneratedRoomMutationDto(
                    "a", "room-a", "A generated room", "12", null, null),
                new WorldDesignMutationRequestDto.GeneratedRoomMutationDto(
                    "b", "room-b", "Another generated room", "12", null, null)),
            List.of(
                new WorldDesignMutationRequestDto.GeneratedRoomExitMutationDto(
                    "a", "b", "north", 1)),
            List.of(
                new WorldDesignMutationRequestDto.GeneratedWorldEntitySpawnBindingMutationDto(
                    "a", "NPC", "55", 2, 30))));
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

  private Region region(long regionId) {
    Region region = new Region();
    region.setId(regionId);
    region.setTenantId(1L);
    region.setVersionId(7L);
    return region;
  }

  private Zone zone(long zoneId, long regionId) {
    Region region = new Region();
    region.setId(regionId);
    Zone zone = new Zone();
    zone.setId(zoneId);
    zone.setRegion(region);
    return zone;
  }

  private Room room(long roomId, Zone zone) {
    Room room = new Room();
    room.setId(roomId);
    room.setTenantId(1L);
    room.setVersionId(7L);
    room.setZone(zone);
    return room;
  }

  private WorldEntitySpawnBinding binding(long bindingId, Room room) {
    WorldEntitySpawnBinding binding = new WorldEntitySpawnBinding();
    binding.setId(bindingId);
    binding.setTenantId(1L);
    binding.setVersionId(7L);
    binding.setRoom(room);
    binding.setEntityTemplateType("NPC");
    binding.setEntityTemplateId(55L);
    return binding;
  }

  private GenerationRule generationRule(
      long ruleId, String scopeType, String scopeId, String name) {
    GenerationRule rule = new GenerationRule();
    rule.setId(ruleId);
    rule.setTenantId(1L);
    rule.setVersionId(7L);
    rule.setScopeType(scopeType);
    rule.setScopeId(scopeId);
    rule.setName(name);
    rule.setValue("value");
    return rule;
  }
}
