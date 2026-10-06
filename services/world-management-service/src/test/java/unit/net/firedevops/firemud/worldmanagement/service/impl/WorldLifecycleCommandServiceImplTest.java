package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class WorldLifecycleCommandServiceImplTest {
  private static final String PERSISTED_RELEASE_BUNDLE_REF = "opaque-release-ref-from-game-design";
  private WorldInstanceRepository worldInstanceRepository;
  private RegionInstanceRepository regionInstanceRepository;
  private ZoneRepository zoneRepository;
  private ZoneInstanceRepository zoneInstanceRepository;
  private RoomRepository roomRepository;
  private RoomExitRepository roomExitRepository;
  private RoomInstanceRepository roomInstanceRepository;
  private RoomInstanceExitRepository roomInstanceExitRepository;
  private WorldEventRepository worldEventRepository;
  private EntityManagementClient entityManagementClient;
  private GameDesignClient gameDesignClient;
  private DSLContext dsl;
  private AtomicBoolean localTransactionActive;
  private AtomicBoolean worldInstanceReadInLocalTransaction;
  private WorldLifecycleCommandServiceImpl service;

  @BeforeEach
  void setUp() {
    worldInstanceRepository = mock(WorldInstanceRepository.class);
    regionInstanceRepository = mock(RegionInstanceRepository.class);
    zoneRepository = mock(ZoneRepository.class);
    zoneInstanceRepository = mock(ZoneInstanceRepository.class);
    roomRepository = mock(RoomRepository.class);
    roomExitRepository = mock(RoomExitRepository.class);
    roomInstanceRepository = mock(RoomInstanceRepository.class);
    roomInstanceExitRepository = mock(RoomInstanceExitRepository.class);
    worldEventRepository = mock(WorldEventRepository.class);
    entityManagementClient = mock(EntityManagementClient.class);
    gameDesignClient = mock(GameDesignClient.class);
    dsl = mock(DSLContext.class);
    localTransactionActive = new AtomicBoolean();
    worldInstanceReadInLocalTransaction = new AtomicBoolean();
    TransactionOperations transactionOperations =
        new TransactionOperations() {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            assertFalse(localTransactionActive.get());
            localTransactionActive.set(true);
            try {
              return action.doInTransaction(null);
            } finally {
              localTransactionActive.set(false);
            }
          }
        };
    WorldProperties worldProperties = new WorldProperties();
    worldProperties.setLocalShardId(7);
    service =
        new WorldLifecycleCommandServiceImpl(
            worldInstanceRepository,
            regionInstanceRepository,
            zoneRepository,
            zoneInstanceRepository,
            roomRepository,
            roomExitRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            worldEventRepository,
            worldProperties,
            gameDesignClient,
            entityManagementClient,
            new SimpleMeterRegistry(),
            transactionOperations,
            dsl);
    service.initMetrics();
    when(gameDesignClient.getPublishedReleaseBundle(42L, 11L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v1")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef(PERSISTED_RELEASE_BUNDLE_REF)
                        .build())
                .build());
    when(gameDesignClient.getVersionAssetArtifactState(42L, 11L))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setArtifactState(
                            net.firedevops.firemud.gamedesign.v1.ArtifactState
                                .ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(3L)
                        .setManifestHash("manifest-11")
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(42L, 11L))
        .thenReturn(
            GetVersionStateResponse.newBuilder()
                .setVersionState(
                    VersionStateSnapshot.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(77L)
                        .build())
                .build());
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(templateZone(42L, 11L)));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(templateRoom(42L, 1021L)));
    when(zoneInstanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L)).thenReturn(List.of());
    when(roomInstanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roomInstanceExitRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void prepareWorldInstanceMaterializesSeededRoomIdentityAndActivatesOwnerLifecycle() {
    Room starterRoom = templateRoom(42L, 1021L);
    starterRoom.setName("Candle-lit Antechamber");
    Room secondaryRoom = templateRoom(42L, 2045L);
    secondaryRoom.setName("Smith's Annex");
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(starterRoom, secondaryRoom));
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(
            List.of(
                templateExit(42L, 11L, 1L, starterRoom, secondaryRoom, "NORTH"),
                templateExit(42L, 11L, 2L, secondaryRoom, starterRoom, "SOUTH")));
    AtomicReference<WorldInstance> storedInstance = new AtomicReference<>();
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenAnswer(invocation -> Optional.ofNullable(storedInstance.get()));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance worldInstance = invocation.getArgument(0);
              storedInstance.set(worldInstance);
              return worldInstance;
            });

    var prepared =
        service.prepareWorldInstance(
            new PreparedWorldInstanceRequest(
                42L,
                101L,
                7L,
                "cp-1",
                "ld-1",
                11L,
                "patch-1",
                "{}",
                "genrev-11",
                77L,
                PERSISTED_RELEASE_BUNDLE_REF,
                77L));

    assertEquals("PREPARING", prepared.status());
    assertEquals(1L, prepared.lifecycleEpoch());
    assertEquals(42L, storedInstance.get().getTenantId());
    assertEquals(101L, storedInstance.get().getGameInstanceId());
    assertEquals(11L, storedInstance.get().getVersionId());
    assertEquals(0L, storedInstance.get().getRowVersion());
    verify(regionInstanceRepository).save(any());
    verify(zoneInstanceRepository).save(any());
    ArgumentCaptor<RoomInstance> roomCaptor = ArgumentCaptor.forClass(RoomInstance.class);
    verify(roomInstanceRepository, times(2)).save(roomCaptor.capture());
    List<RoomInstance> rooms = roomCaptor.getAllValues();
    assertEquals(
        List.of(1021L, 2045L), rooms.stream().map(RoomInstance::getRoomInstanceRowId).toList());
    assertEquals(
        List.of(1021L, 2045L), rooms.stream().map(RoomInstance::getTemplateRoomId).toList());
    assertEquals(List.of(42L, 42L), rooms.stream().map(RoomInstance::getTenantId).toList());
    assertEquals(List.of(101L, 101L), rooms.stream().map(RoomInstance::getGameInstanceId).toList());

    ArgumentCaptor<RoomInstanceExit> exitCaptor = ArgumentCaptor.forClass(RoomInstanceExit.class);
    verify(roomInstanceExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomInstanceExit::getDirection).toList());
    assertEquals(
        List.of(1021L, 2045L),
        exitCaptor.getAllValues().stream()
            .map(exit -> exit.getFromRoomInstance().getRoomInstanceRowId())
            .toList());
    assertEquals(
        List.of(2045L, 1021L),
        exitCaptor.getAllValues().stream()
            .map(exit -> exit.getToRoomInstance().getRoomInstanceRowId())
            .toList());

    var activated = service.activatePreparedWorldInstance(42L, 101L, prepared.lifecycleEpoch());
    assertEquals("ACTIVE", activated.status());
    assertEquals(2L, activated.lifecycleEpoch());
  }

  @Test
  void prepareWorldInstancePersistsPreparingLifecycle() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot =
        service.prepareWorldInstance(
            new PreparedWorldInstanceRequest(
                42L,
                101L,
                7L,
                "cp-1",
                "ld-1",
                11L,
                "patch-1",
                "{}",
                "genrev-11",
                77L,
                PERSISTED_RELEASE_BUNDLE_REF,
                77L));

    assertEquals("PREPARING", snapshot.status());
    assertEquals(1L, snapshot.lifecycleEpoch());
    assertEquals(PERSISTED_RELEASE_BUNDLE_REF, snapshot.publishedReleaseBundleRef());
    verify(worldInstanceRepository).save(any(WorldInstance.class));
    verify(regionInstanceRepository).save(any());
    verify(zoneInstanceRepository).save(any());
    verify(roomInstanceRepository).save(any());
  }

  @Test
  void prepareWorldInstanceRejectsRoomWhoseZoneIsNotSelectedBeforeWriting() {
    Room room = templateRoom(42L, 1021L);
    room.setZone(templateZone(42L, 99L));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L)).thenReturn(List.of(room));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void prepareWorldInstanceRejectsMissingExitSourceBeforeWriting() {
    Room selectedRoom = templateRoom(42L, 1021L);
    Room missingRoom = templateRoom(42L, 2045L);
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(templateExit(42L, 11L, 1L, missingRoom, selectedRoom, "NORTH")));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void prepareWorldInstanceRejectsMissingExitDestinationBeforeWriting() {
    Room selectedRoom = templateRoom(42L, 1021L);
    Room missingRoom = templateRoom(42L, 2045L);
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(templateExit(42L, 11L, 1L, selectedRoom, missingRoom, "NORTH")));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void prepareWorldInstanceRejectsDuplicateSelectedZoneIdentityBeforeWriting() {
    Zone zone = templateZone(42L, 11L);
    Zone duplicate = templateZone(42L, 11L);
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(List.of(zone, duplicate));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void prepareWorldInstanceRejectsOutOfScopeRoomBeforeWriting() {
    Room room = templateRoom(42L, 1021L);
    room.setTenantId(43L);
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L)).thenReturn(List.of(room));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void prepareWorldInstanceRejectsDuplicateExitIdentityBeforeWriting() {
    Room room = templateRoom(42L, 1021L);
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L))
        .thenReturn(
            List.of(
                templateExit(42L, 11L, 1L, room, room, "NORTH"),
                templateExit(42L, 11L, 1L, room, room, "SOUTH")));

    assertIncompleteTopologyRejectedBeforeWrites();
  }

  @Test
  void exactRetryRetainsPersistedOpaqueReleaseBundleReference() {
    AtomicReference<WorldInstance> persisted = new AtomicReference<>();
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(persisted.get()));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance instance = invocation.getArgument(0);
              persisted.set(instance);
              return instance;
            });
    PreparedWorldInstanceRequest request =
        new PreparedWorldInstanceRequest(
            42L,
            101L,
            7L,
            "cp-1",
            "ld-1",
            11L,
            "patch-1",
            "{}",
            "genrev-11",
            77L,
            PERSISTED_RELEASE_BUNDLE_REF,
            77L);

    var first = service.prepareWorldInstance(request);
    var retry = service.prepareWorldInstance(request);

    assertEquals(PERSISTED_RELEASE_BUNDLE_REF, first.publishedReleaseBundleRef());
    assertEquals(PERSISTED_RELEASE_BUNDLE_REF, retry.publishedReleaseBundleRef());
    assertEquals(PERSISTED_RELEASE_BUNDLE_REF, persisted.get().getPublishedReleaseBundleRef());
    verify(worldInstanceRepository, times(1)).save(any(WorldInstance.class));
    verify(gameDesignClient, times(1)).getPublishedReleaseBundle(42L, 11L);
  }

  @Test
  void prepareWorldInstanceRejectsSubstitutedReleaseBundleRefBeforePersistence() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "genrev-11",
                        77L,
                        "substituted-release-reference",
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch", error.getMessage());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(regionInstanceRepository, never()).save(any());
  }

  @Test
  void prepareWorldInstanceRejectsMissingPersistedReleaseBundleRefBeforePersistence() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());
    when(gameDesignClient.getPublishedReleaseBundle(42L, 11L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v1")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .build())
                .build());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "genrev-11",
                        77L,
                        PERSISTED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch", error.getMessage());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(regionInstanceRepository, never()).save(any());
  }

  @Test
  void activatePreparedWorldInstancePromotesPreparingRow() {
    WorldInstance instance = new WorldInstance();
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setGameTemplateId(7L);
    instance.setControlPlaneRequestId("cp-1");
    instance.setLaunchDescriptorId("ld-1");
    instance.setVersionId(11L);
    instance.setGenerationConfigRevision("genrev-11");
    instance.setReleaseBundleId(77L);
    instance.setPublishedReleaseBundleRef(PERSISTED_RELEASE_BUNDLE_REF);
    instance.setVersionStateEpoch(77L);
    instance.setLifecycleEpoch(1L);
    instance.setStatus("PREPARING");
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot = service.activatePreparedWorldInstance(42L, 101L, 1L);

    assertEquals("ACTIVE", snapshot.status());
    assertEquals(2L, snapshot.lifecycleEpoch());
  }

  @Test
  void legacyNumericActivationDeniesCanonicalAssociationBeforeReleaseRpcOrMutation() {
    WorldInstance instance = new WorldInstance();
    instance.setId(201L);
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setLifecycleEpoch(1L);
    instance.setStatus("PREPARING");
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance));
    org.jooq.Record associationRow = mock(org.jooq.Record.class);
    when(dsl.fetchOne(
            "SELECT canonical_game_instance_id FROM world_canonical_instance_association "
                + "WHERE world_instance_id=?",
            201L))
        .thenReturn(associationRow);
    when(associationRow.get(0, java.util.UUID.class))
        .thenReturn(java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.activatePreparedWorldInstance(42L, 101L, 1L));

    assertTrue(error.getMessage().startsWith("CANONICAL_LIFECYCLE_OPERATION_REQUIRED:"));
    assertEquals("PREPARING", instance.getStatus());
    assertEquals(1L, instance.getLifecycleEpoch());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verifyNoInteractions(gameDesignClient);
  }

  @Test
  void failPreparedWorldInstanceMarksPreparingRowFailed() {
    WorldInstance instance = new WorldInstance();
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setGameTemplateId(7L);
    instance.setControlPlaneRequestId("cp-1");
    instance.setLaunchDescriptorId("ld-1");
    instance.setVersionId(11L);
    instance.setGenerationConfigRevision("genrev-11");
    instance.setReleaseBundleId(77L);
    instance.setPublishedReleaseBundleRef(PERSISTED_RELEASE_BUNDLE_REF);
    instance.setVersionStateEpoch(77L);
    instance.setLifecycleEpoch(1L);
    instance.setStatus("PREPARING");
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot = service.failPreparedWorldInstance(42L, 101L, 1L, "boom");

    assertEquals("FAILED_PRE_ACTIVATION", snapshot.status());
    assertEquals(2L, snapshot.lifecycleEpoch());
  }

  @Test
  void terminateWorldInstanceDeletesRuntimeWorldStateBeforeFinalizing() {
    WorldInstance instance = new WorldInstance();
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setGameTemplateId(7L);
    instance.setControlPlaneRequestId("cp-1");
    instance.setLaunchDescriptorId("ld-1");
    instance.setVersionId(11L);
    instance.setReleaseBundleId(77L);
    instance.setGenerationConfigRevision("genrev-11");
    instance.setPublishedReleaseBundleRef(PERSISTED_RELEASE_BUNDLE_REF);
    instance.setVersionStateEpoch(77L);
    instance.setLifecycleEpoch(2L);
    instance.setStatus("ACTIVE");
    stubPersistedWorldInstance(instance);

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    verify(worldEventRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(zoneInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(regionInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void terminateWorldInstanceReturnsStoredSnapshotForExactTerminalRetryWithoutMutation() {
    WorldInstance instance = activeWorldInstance();
    instance.setStatus("TERMINATED");
    instance.setTerminationRequestId("term-1");
    instance.setLifecycleEpoch(4L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    assertEquals(4L, snapshot.lifecycleEpoch());
    assertEquals("TERMINATED", instance.getStatus());
    assertEquals("term-1", instance.getTerminationRequestId());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verifyNoInteractions(
        entityManagementClient,
        worldEventRepository,
        roomInstanceExitRepository,
        roomInstanceRepository,
        zoneInstanceRepository,
        regionInstanceRepository);
  }

  @Test
  void terminateWorldInstanceRejectsChangedOrMissingTerminalRequestIdentityWithoutMutation() {
    WorldInstance instance = activeWorldInstance();
    instance.setStatus("TERMINATED");
    instance.setTerminationRequestId("term-1");
    instance.setLifecycleEpoch(4L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));

    IllegalArgumentException changedIdentity =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-2", "stop"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT: world instance was terminated under a different request id",
        changedIdentity.getMessage());
    assertEquals("term-1", instance.getTerminationRequestId());

    instance.setTerminationRequestId(null);
    IllegalArgumentException missingIdentity =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT: world instance was terminated under a different request id",
        missingIdentity.getMessage());

    assertEquals("TERMINATED", instance.getStatus());
    assertEquals(4L, instance.getLifecycleEpoch());
    assertNull(instance.getTerminationRequestId());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verifyNoInteractions(
        entityManagementClient,
        worldEventRepository,
        roomInstanceExitRepository,
        roomInstanceRepository,
        zoneInstanceRepository,
        regionInstanceRepository);
  }

  @Test
  void terminateWorldInstanceDoesNotCommitTerminationWhenWorldCleanupFails() {
    WorldInstance instance = activeWorldInstance();
    stubPersistedWorldInstance(instance);
    doThrow(new IllegalStateException("world cleanup failed"))
        .when(roomInstanceExitRepository)
        .deleteByTenantIdAndGameInstanceId(42L, 101L);

    assertThrows(
        IllegalStateException.class,
        () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    assertEquals("TERMINATING", instance.getStatus());
    verify(worldInstanceRepository).save(instance);
    verify(worldEventRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void terminationDoesNotCrossAnUnresolvedInitialAdmissionBindHold() {
    WorldInstance instance = activeWorldInstance();
    stubPersistedWorldInstance(instance);
    when(worldInstanceRepository.hasNonterminalInitialAdmissionBindHold(42L, 101L))
        .thenAnswer(
            invocation -> {
              assertTrue(localTransactionActive.get());
              return true;
            });

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    assertTrue(error.getMessage().startsWith("INITIAL_ADMISSION_BIND_HOLD_ACTIVE:"));
    assertEquals("ACTIVE", instance.getStatus());
    verify(entityManagementClient, org.mockito.Mockito.never())
        .cleanupRuntimeInstance(anyLong(), anyLong(), anyString());
    verify(worldInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(42L, 101L);
    verify(worldInstanceRepository, org.mockito.Mockito.never()).save(any(WorldInstance.class));
  }

  @Test
  void terminateWorldInstanceRetriesSameRequestAfterLocalCleanupFailure() {
    WorldInstance instance = activeWorldInstance();
    stubPersistedWorldInstance(instance);
    doThrow(new IllegalStateException("world cleanup failed"))
        .when(roomInstanceExitRepository)
        .deleteByTenantIdAndGameInstanceId(42L, 101L);

    assertThrows(
        IllegalStateException.class,
        () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertTrue(localTransactionActive.get());
              return null;
            })
        .when(roomInstanceExitRepository)
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
    var snapshot = service.terminateWorldInstance(42L, 101L, 3L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    assertEquals(4L, snapshot.lifecycleEpoch());
    verify(entityManagementClient, org.mockito.Mockito.times(2))
        .cleanupRuntimeInstance(42L, 101L, "term-1");
  }

  @Test
  void entityCleanupRunsBeforeTheLocalWorldTransaction() {
    WorldInstance instance = activeWorldInstance();
    stubPersistedWorldInstance(instance);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertTrue(localTransactionActive.get());
              return null;
            })
        .when(worldEventRepository)
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
    when(entityManagementClient.cleanupRuntimeInstance(42L, 101L, "term-1"))
        .thenAnswer(
            invocation -> {
              assertFalse(localTransactionActive.get());
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    assertEquals("TERMINATING", instance.getStatus());
    assertFalse(localTransactionActive.get());
    assertTrue(worldInstanceReadInLocalTransaction.get());
    org.mockito.ArgumentCaptor<WorldInstance> savedRows =
        org.mockito.ArgumentCaptor.forClass(WorldInstance.class);
    verify(worldInstanceRepository, org.mockito.Mockito.times(2)).save(savedRows.capture());
    assertNotSame(savedRows.getAllValues().get(0), savedRows.getAllValues().get(1));
    assertEquals("TERMINATED", savedRows.getAllValues().get(1).getStatus());
  }

  @Test
  void terminationFinalizationRejectsChangedRequestBeforeLocalCleanup() {
    WorldInstance instance = activeWorldInstance();
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);
    when(entityManagementClient.cleanupRuntimeInstance(42L, 101L, "term-1"))
        .thenAnswer(
            invocation -> {
              WorldInstance changed = copyWorldInstance(persisted.get());
              changed.setTerminationRequestId("term-2");
              persisted.set(changed);
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    assertTrue(error.getMessage().contains("termination request changed"));
    verify(worldInstanceRepository, org.mockito.Mockito.times(1)).save(any(WorldInstance.class));
    verify(worldEventRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void terminationFinalizationRejectsChangedStateBeforeLocalCleanup() {
    WorldInstance instance = activeWorldInstance();
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);
    when(entityManagementClient.cleanupRuntimeInstance(42L, 101L, "term-1"))
        .thenAnswer(
            invocation -> {
              WorldInstance changed = copyWorldInstance(persisted.get());
              changed.setStatus("ACTIVE");
              persisted.set(changed);
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    assertTrue(error.getMessage().contains("not terminating"));
    verify(worldInstanceRepository, org.mockito.Mockito.times(1)).save(any(WorldInstance.class));
    verify(worldEventRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void terminationFinalizationAcceptsSameRequestConcurrentCompletionWithoutDoubleEpoch() {
    WorldInstance instance = activeWorldInstance();
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);
    when(entityManagementClient.cleanupRuntimeInstance(42L, 101L, "term-1"))
        .thenAnswer(
            invocation -> {
              WorldInstance completed = copyWorldInstance(persisted.get());
              completed.setStatus("TERMINATED");
              completed.setLifecycleEpoch(4L);
              completed.setTerminatedAt(Instant.parse("2026-09-01T00:00:00Z"));
              persisted.set(completed);
              return CleanupRuntimeInstanceResponse.newBuilder().build();
            });

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    assertEquals(4L, snapshot.lifecycleEpoch());
    verify(worldInstanceRepository, org.mockito.Mockito.times(1)).save(any(WorldInstance.class));
    verify(worldEventRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository, org.mockito.Mockito.never())
        .deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void terminationFinalizationPropagatesOptimisticWriteFailure() {
    WorldInstance instance = activeWorldInstance();
    WorldInstance terminating = copyWorldInstance(instance);
    terminating.setStatus("TERMINATING");
    terminating.setTerminationRequestId("term-1");
    terminating.setLifecycleEpoch(3L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance), Optional.of(terminating));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance), Optional.of(terminating));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance submitted = invocation.getArgument(0);
              if ("TERMINATED".equals(submitted.getStatus())) {
                throw new IllegalStateException("stale write for world_instance");
              }
              return submitted;
            });

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop"));

    assertEquals("stale write for world_instance", error.getMessage());
    verify(worldEventRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(worldInstanceRepository, org.mockito.Mockito.times(2)).save(any(WorldInstance.class));
  }

  @Test
  void prepareWorldInstanceRejectsReleaseBundleMismatch() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "wrong-rev",
                        77L,
                        PERSISTED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: world activation request does not match the published release bundle",
        error.getMessage());
  }

  @Test
  void prepareWorldInstanceRejectsUnsupportedReleaseBundleSchema() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());
    when(gameDesignClient.getPublishedReleaseBundle(42L, 11L))
        .thenReturn(
            GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v999")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .build())
                .build());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "genrev-11",
                        77L,
                        PERSISTED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema v999",
        error.getMessage());
  }

  @Test
  void prepareWorldInstanceRejectsMissingRequiredManifestAssetKeyProof() {
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.empty());
    when(gameDesignClient.getVersionAssetArtifactState(42L, 11L))
        .thenReturn(
            GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setArtifactState(
                            net.firedevops.firemud.gamedesign.v1.ArtifactState
                                .ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(3L)
                        .setManifestHash("manifest-11")
                        .build())
                .build());

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        null,
                        "{}",
                        "genrev-11",
                        77L,
                        PERSISTED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published asset artifact state is missing required manifest asset keys",
        error.getMessage());
  }

  private WorldInstance activeWorldInstance() {
    WorldInstance instance = new WorldInstance();
    instance.setId(101L);
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setGameTemplateId(7L);
    instance.setControlPlaneRequestId("cp-1");
    instance.setLaunchDescriptorId("ld-1");
    instance.setVersionId(11L);
    instance.setReleaseBundleId(77L);
    instance.setGenerationConfigRevision("genrev-11");
    instance.setPublishedReleaseBundleRef(PERSISTED_RELEASE_BUNDLE_REF);
    instance.setVersionStateEpoch(77L);
    instance.setLifecycleEpoch(2L);
    instance.setStatus("ACTIVE");
    instance.setRowVersion(0L);
    return instance;
  }

  private AtomicReference<WorldInstance> stubPersistedWorldInstance(WorldInstance initiallyLoaded) {
    AtomicReference<WorldInstance> persisted =
        new AtomicReference<>(copyWorldInstance(initiallyLoaded));
    AtomicBoolean firstFind = new AtomicBoolean(true);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenAnswer(
            invocation -> {
              if (localTransactionActive.get()) {
                worldInstanceReadInLocalTransaction.set(true);
              }
              if (firstFind.compareAndSet(true, false)) {
                return Optional.of(initiallyLoaded);
              }
              return Optional.of(copyWorldInstance(persisted.get()));
            });
    AtomicBoolean firstLockedFind = new AtomicBoolean(true);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenAnswer(
            invocation -> {
              if (localTransactionActive.get()) {
                worldInstanceReadInLocalTransaction.set(true);
              }
              return firstLockedFind.compareAndSet(true, false)
                  ? Optional.of(initiallyLoaded)
                  : Optional.of(copyWorldInstance(persisted.get()));
            });
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance submitted = invocation.getArgument(0);
              if ("TERMINATED".equals(submitted.getStatus())) {
                assertTrue(localTransactionActive.get());
              }
              WorldInstance saved = copyWorldInstance(submitted);
              long rowVersion = submitted.getRowVersion() == null ? 0L : submitted.getRowVersion();
              saved.setRowVersion(rowVersion + 1L);
              persisted.set(saved);
              return copyWorldInstance(saved);
            });
    return persisted;
  }

  private WorldInstance copyWorldInstance(WorldInstance source) {
    WorldInstance copy = new WorldInstance();
    copy.setId(source.getId());
    copy.setTenantId(source.getTenantId());
    copy.setGameInstanceId(source.getGameInstanceId());
    copy.setGameTemplateId(source.getGameTemplateId());
    copy.setControlPlaneRequestId(source.getControlPlaneRequestId());
    copy.setLaunchDescriptorId(source.getLaunchDescriptorId());
    copy.setVersionId(source.getVersionId());
    copy.setScriptPatchVersion(source.getScriptPatchVersion());
    copy.setRuntimeFlagsJson(source.getRuntimeFlagsJson());
    copy.setGenerationConfigRevision(source.getGenerationConfigRevision());
    copy.setReleaseBundleId(source.getReleaseBundleId());
    copy.setPublishedReleaseBundleRef(source.getPublishedReleaseBundleRef());
    copy.setVersionStateEpoch(source.getVersionStateEpoch());
    copy.setRemapSetId(source.getRemapSetId());
    copy.setLifecycleEpoch(source.getLifecycleEpoch());
    copy.setStatus(source.getStatus());
    copy.setFailureReason(source.getFailureReason());
    copy.setTerminationRequestId(source.getTerminationRequestId());
    copy.setTerminatedAt(source.getTerminatedAt());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setUpdatedAt(source.getUpdatedAt());
    copy.setRowVersion(source.getRowVersion());
    return copy;
  }

  private Room templateRoom(long tenantId, long roomId) {
    Zone zone = templateZone(tenantId, 11L);
    Room room = new Room();
    room.setId(roomId);
    room.setTenantId(tenantId);
    room.setVersionId(11L);
    room.setZone(zone);
    room.setName("Login Hall");
    room.setDescription("A narrow testing hall.");
    return room;
  }

  private RoomExit templateExit(
      long tenantId, long versionId, long exitId, Room fromRoom, Room toRoom, String direction) {
    RoomExit exit = new RoomExit();
    exit.setId(exitId);
    exit.setTenantId(tenantId);
    exit.setVersionId(versionId);
    exit.setFromRoom(fromRoom);
    exit.setToRoom(toRoom);
    exit.setDirection(direction);
    return exit;
  }

  private void assertIncompleteTopologyRejectedBeforeWrites() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.prepareWorldInstance(
                    new PreparedWorldInstanceRequest(
                        42L,
                        101L,
                        7L,
                        "cp-1",
                        "ld-1",
                        11L,
                        "patch-1",
                        "{}",
                        "genrev-11",
                        77L,
                        PERSISTED_RELEASE_BUNDLE_REF,
                        77L)));

    assertTrue(error.getMessage().startsWith("FAILED_PRECONDITION: INCOMPLETE_WORLD_TOPOLOGY:"));
    verify(zoneRepository, times(1)).findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L);
    verify(roomRepository, times(1)).findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L);
    verify(roomExitRepository, times(1)).findByTenantIdAndVersionIdOrderByIdAsc(42L, 11L);
    verify(worldInstanceRepository, never()).save(any());
    verify(regionInstanceRepository, never()).save(any());
    verify(zoneInstanceRepository, never()).save(any());
    verify(roomInstanceRepository, never()).save(any());
    verify(roomInstanceExitRepository, never()).save(any());
  }

  private Zone templateZone(long tenantId, long zoneId) {
    Zone zone = new Zone();
    zone.setId(zoneId);
    zone.setTenantId(tenantId);
    zone.setVersionId(11L);
    zone.setName("Starter Zone");
    return zone;
  }
}
