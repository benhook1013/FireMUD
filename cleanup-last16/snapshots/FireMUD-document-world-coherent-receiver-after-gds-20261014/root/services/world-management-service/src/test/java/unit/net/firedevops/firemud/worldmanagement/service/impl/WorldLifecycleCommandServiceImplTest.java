package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.entitymanagement.v1.CleanupRuntimeInstanceResponse;
import net.firedevops.firemud.gamedesign.v1.ArtifactState;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldFixedPresetResources;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
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
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldLifecycleCommandServiceImplTest {
  private static final String PUBLISHED_RELEASE_BUNDLE_REF = "8ce42642-53ae-4d07-aab8-37c43ce66e0c";

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
            transactionOperations);
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
                        .setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF)
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
                templateExit(42L, 11L, starterRoom, secondaryRoom, "NORTH"),
                templateExit(42L, 11L, secondaryRoom, starterRoom, "SOUTH")));
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
                PUBLISHED_RELEASE_BUNDLE_REF,
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
  void activatePreparedWorldInstancePromotesPreparingRow() {
    WorldInstance instance = new WorldInstance();
    instance.setTenantId(42L);
    instance.setGameInstanceId(101L);
    instance.setGameTemplateId(7L);
    instance.setControlPlaneRequestId("cp-1");
    instance.setLaunchDescriptorId("ld-1");
    instance.setVersionId(11L);
    instance.setRuntimeFlagsJson("{}");
    instance.setGenerationConfigRevision("genrev-11");
    instance.setReleaseBundleId(77L);
    instance.setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF);
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
    instance.setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF);
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
    instance.setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF);
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
                        PUBLISHED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: world activation request does not match the published release bundle",
        error.getMessage());
  }

  @Test
  void prepareWorldInstanceRejectsEmptyPublishedReleaseBundleRef() {
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
                        PUBLISHED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch", error.getMessage());
  }

  @Test
  void prepareWorldInstanceRejectsMismatchedPublishedReleaseBundleRef() {
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
                        "different-opaque-reference",
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch", error.getMessage());
  }

  @Test
  void prepareWorldInstanceRejectsSynthesizedPublishedReleaseBundleRef() {
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
                        "prb:42:11:77",
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published release bundle ref mismatch", error.getMessage());
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
                        PUBLISHED_RELEASE_BUNDLE_REF,
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
                        PUBLISHED_RELEASE_BUNDLE_REF,
                        77L)));

    assertEquals(
        "RELEASE_ATTESTATION_MISMATCH: published asset artifact state is missing required manifest asset keys",
        error.getMessage());
  }

  @Test
  void fixturePrepareBindsOriginalPreparingEpochAndExactRetryDoesNotRepeatMaterialization() {
    var fixture = new ControlledFixture();
    AtomicReference<WorldInstance> persisted = new AtomicReference<>();
    when(fixture.associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
            fixture.evidence, fixture.request, fixture.source, fixture.version))
        .thenAnswer(
            ignored -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              if (persisted.get() == null) {
                persisted.set(fixture.row("PREPARING", 1L));
              }
              return fixture.association;
            });
    when(worldInstanceRepository.findById(1001L))
        .thenAnswer(ignored -> Optional.of(persisted.get()));
    var fixtureService = fixtureService(fixture);

    var prepared = fixtureService.prepareWorldInstance(fixture.request);

    assertEquals("PREPARING", prepared.status());
    assertEquals(1L, prepared.lifecycleEpoch());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    assertEquals(1L, persisted.get().getRunOwnedFixturePreparingLifecycleEpoch());
    assertEquals(142L, prepared.tenantId());
    assertEquals(1001L, prepared.gameInstanceId());
    var retry = fixtureService.prepareWorldInstance(fixture.request);
    assertEquals(prepared, retry);
    persisted.get().setStatus("ACTIVE");
    persisted.get().setLifecycleEpoch(2L);
    var activeReplay = fixtureService.prepareWorldInstance(fixture.request);
    assertEquals("ACTIVE", activeReplay.status());
    assertEquals(2L, activeReplay.lifecycleEpoch());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    assertEquals(1L, persisted.get().getRunOwnedFixturePreparingLifecycleEpoch());
    assertEquals(1, fixture.materializedWrites.get());
    assertEquals(3, fixture.committedReads.get());
    verify(fixture.evidence, times(6)).requireMatches(fixture.request);
    var calls =
        inOrder(
            fixture.sourceIntake,
            fixture.versionIdentity,
            fixture.fence,
            fixture.associationRepository,
            fixture.dsl,
            worldInstanceRepository);
    for (int attempt = 0; attempt < 3; attempt++) {
      calls.verify(fixture.sourceIntake).intakeRunOwnedFixture(fixture.evidence);
      calls
          .verify(fixture.versionIdentity)
          .associateRunOwnedFixture(fixture.evidence, fixture.source);
      calls.verify(fixture.fence).lockOpen(fixture.ownerBinding);
      calls
          .verify(fixture.associationRepository)
          .allocateRunOwnedFixtureInOwnerTransaction(
              fixture.evidence, fixture.request, fixture.source, fixture.version);
      calls
          .verify(fixture.dsl)
          .fetchOne(
              "SELECT * FROM world_materialize_run_owned_fixed_preset(?, ?, ?, ?)",
              fixture.plan.planJson(),
              fixture.plan.expectedContentDigest(),
              1001L,
              1001L);
      calls
          .verify(fixture.associationRepository)
          .readRunOwnedFixtureAssociation(fixture.evidence, fixture.request);
      calls
          .verify(fixture.dsl)
          .fetchOne(
              "SELECT * FROM world_read_run_owned_fixed_preset(?, ?, ?, ?)",
              fixture.plan.planJson(),
              fixture.plan.expectedContentDigest(),
              1001L,
              1001L);
      calls.verify(worldInstanceRepository).findById(1001L);
    }
    verifyNoLegacyFixtureWrites();
  }

  @Test
  void fixturePrepareRejectsChangedBindingForExistingActiveRow() {
    var fixture = new ControlledFixture();
    WorldInstance active = fixture.row("ACTIVE", 2L);
    when(worldInstanceRepository.findById(1001L)).thenReturn(Optional.of(active));
    var changed =
        new WorldCanonicalInstanceAssociation(
            fixture.association.identity(),
            1001L,
            null,
            fixture.version,
            null,
            new WorldCanonicalInstanceAssociation.RunOwnedFixture(
                fixture.association.worldPrivateKeys(),
                42L,
                101L,
                17L,
                UUID.fromString(fixture.evidence.capability().operationId()),
                fixture.evidence.fixtureProvenanceDigest(),
                "sha256:" + "d".repeat(64),
                1L,
                fixture.association.runOwnedFixture().gameSessionAssociationResponseBytes(),
                fixture.evidence.gameSessionAssociation(),
                fixture.source));
    when(fixture.associationRepository.readRunOwnedFixtureAssociation(
            fixture.evidence, fixture.request))
        .thenReturn(changed);

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService(fixture).prepareWorldInstance(fixture.request));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:"));
    assertEquals(0, fixture.committedReads.get());
    assertEquals("ACTIVE", active.getStatus());
    assertEquals(2L, active.getLifecycleEpoch());
    assertEquals("c".repeat(64), active.getRunOwnedFixtureBindingSha256());
    verify(worldInstanceRepository, never()).findById(anyLong());
    verifyNoLegacyFixtureWrites();
  }

  @Test
  void fixtureOperationCannotBeAssignedToASecondWorldInstance() {
    var fixture = new ControlledFixture();
    var otherRequest =
        new PreparedWorldInstanceRequest(
            42L,
            202L,
            fixture.request.gameTemplateId(),
            fixture.request.controlPlaneRequestId(),
            fixture.request.launchDescriptorId(),
            fixture.request.versionId(),
            null,
            "{}",
            fixture.request.generationConfigRevision(),
            fixture.request.releaseBundleId(),
            fixture.request.publishedReleaseBundleRef(),
            fixture.request.versionStateEpoch());
    // The controlled allocation owner positively rejects the second GS assignment.
    when(fixture.associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
            fixture.evidence, otherRequest, fixture.source, fixture.version))
        .thenThrow(
            new IllegalArgumentException(
                "RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT: fixture operation is bound to another instance"));

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService(fixture).prepareWorldInstance(otherRequest));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT:"));
    var calls = inOrder(fixture.fence, fixture.associationRepository);
    calls.verify(fixture.fence).lockOpen(fixture.ownerBinding);
    calls
        .verify(fixture.associationRepository)
        .allocateRunOwnedFixtureInOwnerTransaction(
            fixture.evidence, otherRequest, fixture.source, fixture.version);
    org.mockito.Mockito.verifyNoInteractions(fixture.dsl);
    verifyNoLegacyFixtureWrites();
  }

  @Test
  void fixtureActivationVerifiesBindingThenAllowsOnlyOriginalEpochActiveReplayWithoutWrite() {
    var fixture = new ControlledFixture();
    AtomicReference<WorldInstance> persisted = new AtomicReference<>(fixture.row("PREPARING", 1L));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L))
        .thenAnswer(ignored -> Optional.of(copyWorldInstance(persisted.get())));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance submitted = invocation.getArgument(0);
              submitted.setRowVersion(submitted.getRowVersion() + 1L);
              persisted.set(copyWorldInstance(submitted));
              return copyWorldInstance(submitted);
            });
    var fixtureService = fixtureService(fixture);

    var active = fixtureService.activatePreparedWorldInstance(42L, 101L, 1L);
    assertEquals("ACTIVE", active.status());
    assertEquals(2L, active.lifecycleEpoch());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    var replay = fixtureService.activatePreparedWorldInstance(42L, 101L, 1L);
    assertEquals(active, replay);
    verify(worldInstanceRepository, times(1)).save(any(WorldInstance.class));
    verify(fixture.provider, times(2)).requireAuthenticatedEvidence();
    verify(fixture.evidence, times(2)).requireGameSessionAllocation(42L, 101L);
    assertEquals(2, fixture.committedReads.get());
    var calls = inOrder(fixture.provider, fixture.associationRepository, worldInstanceRepository);
    for (int attempt = 0; attempt < 2; attempt++) {
      calls.verify(fixture.provider).requireAuthenticatedEvidence();
      calls
          .verify(fixture.associationRepository)
          .readRunOwnedFixtureAssociation(fixture.evidence, fixture.request);
      calls
          .verify(fixture.associationRepository)
          .readRunOwnedFixtureAssociationInActivationTransaction(fixture.evidence, fixture.request);
      calls.verify(worldInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L);
    }
    verify(fixture.associationRepository, never())
        .allocateRunOwnedFixtureInOwnerTransaction(any(), any(), any(), any());
    verify(gameDesignClient, never()).getPublishedReleaseBundle(anyLong(), anyLong());
  }

  @Test
  void fixtureActivationRejectsMissingOrChangedExternalVersionBeforeMaterializationRead() {
    var fixture = new ControlledFixture();
    var fixtureService = fixtureService(fixture);
    for (Long externalVersion : new Long[] {null, 0L, 12L, 211L}) {
      WorldInstance row = fixture.row("PREPARING", 1L);
      row.setGameDesignVersionId(externalVersion);
      when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L))
          .thenReturn(Optional.of(row));

      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> fixtureService.activatePreparedWorldInstance(42L, 101L, 1L));

      assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:"));
      assertEquals(211L, row.getVersionId());
      assertEquals("PREPARING", row.getStatus());
      assertEquals(1L, row.getLifecycleEpoch());
    }
    assertEquals(0, fixture.committedReads.get());
    org.mockito.Mockito.verifyNoInteractions(fixture.dsl);
    verifyNoLegacyFixtureWrites();
  }

  @Test
  void configuredFixtureRejectsUnboundExistingRowsAndStaleActiveReplayEpoch() {
    var fixture = new ControlledFixture();
    WorldInstance unbound = fixture.row("ACTIVE", 2L);
    unbound.setRunOwnedFixtureBindingSha256(null);
    unbound.setRunOwnedFixturePreparingLifecycleEpoch(null);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L))
        .thenReturn(Optional.of(unbound));
    var fixtureService = fixtureService(fixture);

    var unboundError =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService.activatePreparedWorldInstance(42L, 101L, 2L));
    assertTrue(unboundError.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:"));
    assertEquals(0, fixture.committedReads.get());
    WorldInstance active = fixture.row("ACTIVE", 2L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L))
        .thenReturn(Optional.of(active));
    var stale =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService.activatePreparedWorldInstance(42L, 101L, 0L));
    assertTrue(stale.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));
    assertEquals("ACTIVE", active.getStatus());
    assertEquals(2L, active.getLifecycleEpoch());
    assertEquals(1, fixture.committedReads.get());
    verify(fixture.associationRepository, times(2))
        .readRunOwnedFixtureAssociationInActivationTransaction(fixture.evidence, fixture.request);
    verifyNoLegacyFixtureWrites();
  }

  @Test
  void fixtureActivationRejectsOriginalEpochForNonActiveRowsWithoutWrites() {
    var fixture = new ControlledFixture();
    var fixtureService = fixtureService(fixture);
    AtomicReference<WorldInstance> currentRow = new AtomicReference<>();
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(142L, 1001L))
        .thenAnswer(ignored -> Optional.of(currentRow.get()));
    String[] statuses = {"FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED"};
    long[] lifecycleEpochs = {2L, 3L, 4L};
    for (int index = 0; index < statuses.length; index++) {
      WorldInstance row = fixture.row(statuses[index], lifecycleEpochs[index]);
      currentRow.set(row);
      var stale =
          assertThrows(
              IllegalArgumentException.class,
              () -> fixtureService.activatePreparedWorldInstance(42L, 101L, 1L));
      assertTrue(stale.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));
      assertEquals(statuses[index], row.getStatus());
      assertEquals(lifecycleEpochs[index], row.getLifecycleEpoch());
    }
    assertEquals(3, fixture.committedReads.get());
    verifyNoLegacyFixtureWrites();
    verify(regionInstanceRepository, never())
        .deleteByTenantIdAndGameInstanceId(anyLong(), anyLong());
    verify(zoneInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(anyLong(), anyLong());
    verify(roomInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(anyLong(), anyLong());
    verify(roomInstanceExitRepository, never())
        .deleteByTenantIdAndGameInstanceId(anyLong(), anyLong());
    verify(worldEventRepository, never()).deleteByTenantIdAndGameInstanceId(anyLong(), anyLong());
  }

  private void verifyNoLegacyFixtureWrites() {
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(regionInstanceRepository, never()).save(any());
    verify(zoneInstanceRepository, never()).save(any());
    verify(roomInstanceRepository, never()).save(any());
    verify(roomInstanceExitRepository, never()).save(any());
    verify(worldEventRepository, never()).save(any());
    verify(worldInstanceRepository, never()).lockRunOwnedFixtureOperation(anyLong(), anyString());
    verify(gameDesignClient, never()).getPublishedReleaseBundle(anyLong(), anyLong());
    verify(gameDesignClient, never()).getVersionAssetArtifactState(anyLong(), anyLong());
    verify(gameDesignClient, never()).getVersionState(anyLong(), anyLong());
  }

  private WorldLifecycleCommandServiceImpl fixtureService(ControlledFixture fixture) {
    var worldProperties = new WorldProperties();
    worldProperties.setLocalShardId(7);
    TransactionOperations transactions =
        new TransactionOperations() {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
              return action.doInTransaction(null);
            } finally {
              TransactionSynchronizationManager.clear();
            }
          }
        };
    var fixtureService =
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
            transactions,
            fixture.provider,
            fixture.dsl,
            new WorldLifecycleCommandServiceImpl.RunOwnedWorldFixtureOwners(
                mock(AuthoredWorldSourceClient.class),
                mock(AuthoredWorldVersionStateClient.class),
                fixture.sourceIntake,
                fixture.versionIdentity,
                fixture.associationRepository,
                fixture.fence));
    fixtureService.initMetrics();
    return fixtureService;
  }

  /** Controlled owner/SQL responses only: no genuine upstream, transport, or physical SQL proof. */
  private static final class ControlledFixture {
    final RunOwnedWorldLaunchEvidence evidence = controlledFixtureEvidence();
    final RunOwnedWorldLaunchEvidenceProvider provider =
        mock(RunOwnedWorldLaunchEvidenceProvider.class);
    final WorldAuthoredSourceIntakeService sourceIntake =
        mock(WorldAuthoredSourceIntakeService.class);
    final WorldAuthoredVersionIdentityService versionIdentity =
        mock(WorldAuthoredVersionIdentityService.class);
    final WorldCanonicalInstanceAssociationRepository associationRepository =
        mock(WorldCanonicalInstanceAssociationRepository.class);
    final WorldDesignPublicationFenceRepository fence =
        mock(WorldDesignPublicationFenceRepository.class);
    final DSLContext dsl = mock(DSLContext.class);
    final WorldAuthoredSourceIntakeReceipt source;
    final WorldAuthoredVersionIdentityReceipt version;
    final WorldCanonicalInstanceAssociation association;
    final OwnerBinding ownerBinding;
    final PreparedWorldInstanceRequest request;
    final RunOwnedWorldFixedPresetResources.FixedPresetPlan plan;
    final AtomicInteger materializedWrites = new AtomicInteger();
    final AtomicInteger committedReads = new AtomicInteger();
    private final AtomicBoolean resourcesExist = new AtomicBoolean();

    ControlledFixture() {
      var sourceEvidence = evidence.sourceReceipt().sourceEvidence();
      UUID intakeRequest = UUID.randomUUID();
      UUID intakeOperation = UUID.randomUUID();
      String requestDigest =
          WorldAuthoredSourceIntakeDigest.requestDigest("dev", intakeRequest, sourceEvidence);
      source =
          new WorldAuthoredSourceIntakeReceipt(
              1,
              "dev",
              intakeRequest,
              intakeOperation,
              sourceEvidence.canonicalTenantId(),
              sourceEvidence.worldSlug(),
              sourceEvidence.operationId(),
              sourceEvidence.evidenceDigest(),
              requestDigest,
              WorldAuthoredSourceIntakeDigest.receiptDigest(
                  "dev", intakeOperation, requestDigest, sourceEvidence, 142L),
              142L,
              sourceEvidence);
      var gs = evidence.gameSessionAssociation();
      var descriptor = evidence.launchDescriptor();
      var versionState =
          AuthoredWorldVersionStateEvidence.create(
              new AuthoredWorldVersionStateEvidence.Request(
                  1,
                  "dev",
                  UUID.randomUUID(),
                  source.canonicalTenantId(),
                  source.worldSlug(),
                  source.sourceOperationId(),
                  source.sourceEvidenceDigest(),
                  descriptor.versionId()),
              sourceEvidence,
              gs.canonicalVersionId(),
              VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
              descriptor.versionStateEpoch());
      // World's private Version allocation is deliberately different from GDS's Version ID 11.
      version =
          new WorldAuthoredVersionIdentityReceipt(1, UUID.randomUUID(), 211L, source, versionState);
      ownerBinding =
          new OwnerBinding(
              source.targetNamespace(),
              source.canonicalTenantId(),
              version.canonicalVersionId(),
              version.operationId(),
              version.gameDesignVersionId(),
              source.intakeRequestId(),
              source.operationId(),
              source.requestDigest(),
              source.sourceOperationId(),
              source.sourceEvidenceDigest(),
              source.receiptDigest());
      request =
          new PreparedWorldInstanceRequest(
              42L,
              101L,
              descriptor.gameTemplateId(),
              evidence.capability().operationId(),
              descriptor.launchDescriptorId(),
              descriptor.versionId(),
              null,
              descriptor.runtimeFlagsJson(),
              descriptor.generationConfigRevision(),
              descriptor.releaseBundleId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch());
      var identity =
          new WorldCanonicalInstanceAssociation.CanonicalIdentity(
              gs.gameInstanceUuid(),
              "dev",
              gs.canonicalTenantId(),
              source.worldSlug(),
              gs.playableStateNamespaceId(),
              "SHARED",
              true,
              gs.canonicalRealmId(),
              gs.catalogRevision(),
              evidence.capability().operationId());
      var fixture =
          new WorldCanonicalInstanceAssociation.RunOwnedFixture(
              new WorldCanonicalInstanceAssociation.WorldPrivateKeys(142L, 1001L, 211L),
              42L,
              101L,
              descriptor.gameTemplateId(),
              UUID.fromString(evidence.capability().operationId()),
              evidence.fixtureProvenanceDigest(),
              "sha256:" + "c".repeat(64),
              1L,
              CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toFixtureResponse(gs)
                  .toByteArray(),
              gs,
              source);
      association =
          new WorldCanonicalInstanceAssociation(identity, 1001L, null, version, null, fixture);
      plan = RunOwnedWorldFixedPresetResources.plan(evidence, source, version);
      when(provider.isEnabled()).thenReturn(true);
      when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
      when(sourceIntake.intakeRunOwnedFixture(evidence)).thenReturn(source);
      when(versionIdentity.associateRunOwnedFixture(evidence, source)).thenReturn(version);
      when(associationRepository.allocateRunOwnedFixtureInOwnerTransaction(
              evidence, request, source, version))
          .thenReturn(association);
      when(associationRepository.readRunOwnedFixtureAssociation(evidence, request))
          .thenReturn(association);
      when(associationRepository.readRunOwnedFixtureAssociationInActivationTransaction(
              evidence, request))
          .thenAnswer(
              ignored -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return association;
              });
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                Object[] bindings = (Object[]) invocation.getRawArguments()[1];
                assertEquals(4, bindings.length);
                assertEquals(plan.planJson(), bindings[0]);
                assertEquals(plan.expectedContentDigest(), bindings[1]);
                assertEquals(1001L, bindings[2]);
                assertEquals(1001L, bindings[3]);
                if (sql.equals(
                    "SELECT * FROM world_materialize_run_owned_fixed_preset(?, ?, ?, ?)")) {
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  // Stipulated retained SQL replay response; the native PostgreSQL suite proves SQL
                  // writes.
                  if (resourcesExist.compareAndSet(false, true)) {
                    materializedWrites.incrementAndGet();
                  }
                } else {
                  assertEquals("SELECT * FROM world_read_run_owned_fixed_preset(?, ?, ?, ?)", sql);
                  committedReads.incrementAndGet();
                }
                return resultRecord();
              });
      // Plan construction calls this once during fixture setup; proof counts only lifecycle calls.
      org.mockito.Mockito.clearInvocations(evidence);
    }

    WorldInstance row(String status, long epoch) {
      WorldInstance row = new WorldInstance();
      row.setId(1001L);
      row.setTenantId(142L);
      row.setGameInstanceId(1001L);
      row.setVersionId(211L);
      row.setGameDesignVersionId(request.versionId());
      row.setGameTemplateId(request.gameTemplateId());
      row.setControlPlaneRequestId(request.controlPlaneRequestId());
      row.setLaunchDescriptorId(request.launchDescriptorId());
      row.setRuntimeFlagsJson(request.runtimeFlagsJson());
      row.setGenerationConfigRevision(request.generationConfigRevision());
      row.setReleaseBundleId(request.releaseBundleId());
      row.setPublishedReleaseBundleRef(request.publishedReleaseBundleRef());
      row.setVersionStateEpoch(request.versionStateEpoch());
      row.setRunOwnedFixtureBindingSha256("c".repeat(64));
      row.setRunOwnedFixturePreparingLifecycleEpoch(1L);
      row.setStatus(status);
      row.setLifecycleEpoch(epoch);
      row.setRowVersion(0L);
      return row;
    }

    private Record resultRecord() {
      String[] names = {
        "world_instance_id",
        "tenant_id",
        "private_game_instance_key",
        "version_id",
        "preset_id",
        "preset_content_digest",
        "preset_binding_digest",
        "graph_sha256",
        "region_count",
        "zone_count",
        "room_count",
        "exit_count"
      };
      var fields = java.util.Arrays.stream(names).map(DSL::field).toArray(org.jooq.Field[]::new);
      Record result = DSL.using(SQLDialect.POSTGRES).newRecord(fields);
      Object[] values = {
        1001L,
        142L,
        1001L,
        211L,
        plan.presetId(),
        plan.expectedContentDigest(),
        plan.presetSealDigest(),
        plan.expectedGraphSha256(),
        1,
        1,
        2,
        2
      };
      for (int index = 0; index < values.length; index++) {
        result.set(DSL.field(names[index]), values[index]);
      }
      return result;
    }
  }

  private static RunOwnedWorldLaunchEvidence controlledFixtureEvidence() {
    String claimDigest =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    String operationId =
        RunOwnedInitialAdmissionFixtureWireCodec.fixtureOperationId(
                claimDigest, "fixture-run", "firemud-fixture-run", "a".repeat(64), "b".repeat(64))
            .toString();
    String releaseRef = UUID.randomUUID().toString();
    UUID registrationId = UUID.fromString(operationId);
    UUID sourceOperationId = UUID.randomUUID();
    UUID canonicalTenantId = UUID.randomUUID();
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            "dev", registrationId, canonicalTenantId, "fixture-tenant", "demo", "Demo World");
    String sourceEvidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            "dev",
            registrationId,
            sourceOperationId,
            requestDigest,
            canonicalTenantId,
            "fixture-tenant",
            "demo",
            "Demo World",
            29L,
            "42",
            "NEW_GAME_ROW",
            sourceEvidenceDigest);
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "dev",
            operationId,
            canonicalTenantId,
            "demo",
            sourceOperationId,
            sourceEvidenceDigest,
            17L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "launch-descriptor-fixture-11",
            11L,
            false,
            null,
            "{}",
            "genrev-11",
            77L,
            77L,
            releaseRef,
            false,
            null);
    String manifestHash = "sha256:" + "c".repeat(64);
    var bundle =
        PublishedReleaseBundle.newBuilder()
            .setId(77L)
            .setVersionId(11L)
            .setVersionNumber(1)
            .setAttestationSchemaVersion("v1")
            .setCanonicalTenantId(canonicalTenantId.toString())
            .setCanonicalVersionId("10000000-0000-4000-8000-000000000011")
            .setPublishedReleaseBundleRef(releaseRef)
            .setGenerationConfigRevision("genrev-11")
            .setManifestHash(manifestHash)
            .build();
    var artifact =
        VersionAssetArtifactState.newBuilder()
            .setTenantId("42")
            .setVersionId(11L)
            .setStateEpoch(2L)
            .setExportedVersionNumber(1)
            .setArtifactState(ArtifactState.ARTIFACT_STATE_PUBLISHED)
            .setManifestHash(manifestHash)
            .build();
    var version =
        VersionStateSnapshot.newBuilder()
            .setTenantId("42")
            .setVersionId(11L)
            .setVersionState(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)
            .setVersionStateEpoch(77L)
            .setUpdatedAt("2026-10-11T10:00:00Z")
            .build();
    RunOwnedInitialAdmissionFixtureWireCodec.Capability capability =
        new RunOwnedInitialAdmissionFixtureWireCodec.Capability(
            RunOwnedInitialAdmissionFixtureWireCodec.CAPABILITY_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            42L,
            17L,
            9L,
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "a".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            claimDigest);
    // Controlled constructor data remains untrusted; these commitments do not prove owner writes.
    UUID creationRequestId = UUID.fromString("1b3fdbda-a9f6-4cf5-b21e-b43f0e855f65");
    UUID creationOperationId = UUID.fromString("31e36104-3336-4cf5-899a-cb419f2f361e");
    String creationRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "dev", creationRequestId, "42", "Fixture Game", null);
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "dev",
            creationRequestId,
            creationOperationId,
            creationRequestDigest,
            canonicalTenantId,
            29L,
            "42",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "dev",
                creationRequestId,
                creationOperationId,
                creationRequestDigest,
                canonicalTenantId,
                29L,
                "42",
                "NEW_GAME_ROW"));
    var launchRead =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            17L,
            bundle,
            artifact,
            version,
            net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec
                .toLaunchDescriptorMessage(descriptor));
    String policyRequestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability, creation, source, launchRead);
    var policySeal =
        new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
            RunOwnedInitialAdmissionFixtureWireCodec.FIXTURE_POLICY_SEAL_SCHEMA,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            UUID.fromString(operationId),
            policyRequestDigest,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                UUID.fromString(operationId), policyRequestDigest));
    var receipt =
        new RunOwnedInitialAdmissionFixtureWireCodec.SourceReceipt(
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_SCHEMA,
            "fixture-run",
            "firemud-fixture-run",
            operationId,
            RunOwnedInitialAdmissionFixtureWireCodec.SOURCE_RECEIPT_OWNER_SERVICE,
            RunOwnedInitialAdmissionFixtureWireCodec.GAME_SESSION_URI_SAN,
            "a".repeat(64),
            "b".repeat(64),
            RunOwnedInitialAdmissionFixtureWireCodec.WORLD_MANAGEMENT_URI_SAN,
            "c".repeat(64),
            source,
            launchRead,
            creation,
            policySeal);
    var gsRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureRequest(
            UUID.randomUUID(), "dev", UUID.fromString(operationId), policySeal.evidenceDigest());
    var gs =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.FixtureResult(
            gsRequest,
            42L,
            101L,
            canonicalTenantId,
            UUID.randomUUID(),
            UUID.fromString(bundle.getCanonicalVersionId()),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1L,
            CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING,
            0L,
            RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
                capability, creation, source, launchRead),
            policySeal);
    // Only the provider-verified carrier is stipulated; all enclosed typed values are controlled.
    var evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(evidence.capability()).thenReturn(capability);
    when(evidence.sourceReceipt()).thenReturn(receipt);
    when(evidence.launchDescriptor()).thenReturn(descriptor);
    when(evidence.gameSessionAssociation()).thenReturn(gs);
    when(evidence.fixtureProvenanceDigest()).thenReturn(policySeal.evidenceDigest());
    when(evidence.worldManagementUriSan()).thenReturn(capability.worldManagementUriSan());
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    return evidence;
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
    instance.setRuntimeFlagsJson("{}");
    instance.setReleaseBundleId(77L);
    instance.setGenerationConfigRevision("genrev-11");
    instance.setPublishedReleaseBundleRef(PUBLISHED_RELEASE_BUNDLE_REF);
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
    copy.setGameDesignVersionId(source.getGameDesignVersionId());
    copy.setRunOwnedFixtureBindingSha256(source.getRunOwnedFixtureBindingSha256());
    copy.setRunOwnedFixturePreparingLifecycleEpoch(
        source.getRunOwnedFixturePreparingLifecycleEpoch());
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
      long tenantId, long versionId, Room fromRoom, Room toRoom, String direction) {
    RoomExit exit = new RoomExit();
    exit.setTenantId(tenantId);
    exit.setVersionId(versionId);
    exit.setFromRoom(fromRoom);
    exit.setToRoom(toRoom);
    exit.setDirection(direction);
    return exit;
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
