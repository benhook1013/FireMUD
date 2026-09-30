package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class WorldLifecycleCommandServiceImplTest {
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
                "prb:42:11:77",
                77L));

    assertEquals("PREPARING", snapshot.status());
    assertEquals(1L, snapshot.lifecycleEpoch());
    verify(regionInstanceRepository).save(any());
    verify(zoneInstanceRepository).save(any());
    verify(roomInstanceRepository).save(any());
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
    instance.setPublishedReleaseBundleRef("prb:42:11:77");
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
    instance.setPublishedReleaseBundleRef("prb:42:11:77");
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
    instance.setPublishedReleaseBundleRef("prb:42:11:77");
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
                        "prb:42:11:77",
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
                        "prb:42:11:77",
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
                        "prb:42:11:77",
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
    instance.setPublishedReleaseBundleRef("prb:42:11:77");
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

  private Zone templateZone(long tenantId, long zoneId) {
    Zone zone = new Zone();
    zone.setId(zoneId);
    zone.setTenantId(tenantId);
    zone.setVersionId(11L);
    zone.setName("Starter Zone");
    return zone;
  }
}
