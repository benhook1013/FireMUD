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
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceProvider;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
  private TransactionOperations lifecycleTransactionOperations;
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
    lifecycleTransactionOperations =
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
            lifecycleTransactionOperations);
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
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot = service.activatePreparedWorldInstance(42L, 101L, 1L);

    assertEquals("ACTIVE", snapshot.status());
    assertEquals(2L, snapshot.lifecycleEpoch());
  }

  @Test
  void ordinaryActivationReplaysOriginalPreparingEpochAndCurrentActiveEpochWithoutEffects() {
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

    var activated = service.activatePreparedWorldInstance(42L, 101L, 1L);
    var originalEpochReplay = service.activatePreparedWorldInstance(42L, 101L, 1L);
    var currentEpochReplay = service.activatePreparedWorldInstance(42L, 101L, 2L);

    assertEquals("ACTIVE", activated.status());
    assertEquals(2L, activated.lifecycleEpoch());
    assertEquals(activated, originalEpochReplay);
    assertEquals(activated, currentEpochReplay);
    verify(worldInstanceRepository, times(1)).save(any(WorldInstance.class));
    verify(gameDesignClient, times(1)).getPublishedReleaseBundle(42L, 11L);
    verify(gameDesignClient, times(1)).getVersionAssetArtifactState(42L, 11L);
    verify(gameDesignClient, times(1)).getVersionState(42L, 11L);
  }

  @Test
  void ordinaryActiveReplayRejectsStaleUnrelatedInvalidAndOverflowEpochsWithoutEffects() {
    WorldInstance instance = activeWorldInstance();
    instance.setLifecycleEpoch(3L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance));

    for (long expectedEpoch : new long[] {1L, 4L, 0L, -1L, Long.MAX_VALUE}) {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class,
              () -> service.activatePreparedWorldInstance(42L, 101L, expectedEpoch));
      assertTrue(exception.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));
    }

    instance.setLifecycleEpoch(Long.MIN_VALUE);
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.activatePreparedWorldInstance(42L, 101L, Long.MAX_VALUE));
    assertTrue(exception.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));

    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(gameDesignClient, never()).getPublishedReleaseBundle(anyLong(), anyLong());
    verify(gameDesignClient, never()).getVersionAssetArtifactState(anyLong(), anyLong());
    verify(gameDesignClient, never()).getVersionState(anyLong(), anyLong());
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
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot = service.failPreparedWorldInstance(42L, 101L, 1L, "boom");

    assertEquals("FAILED_PRE_ACTIVATION", snapshot.status());
    assertEquals(2L, snapshot.lifecycleEpoch());
  }

  @Test
  void fixtureFailureRequiresExactOwnerAndUsesLockedLegacyRow() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence())
        .thenAnswer(
            invocation -> {
              assertFalse(localTransactionActive.get());
              return evidence;
            });
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance instance = boundFixtureInstance("PREPARING", 1L);
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);

    var snapshot =
        fixtureService(provider, lifecycleTransactionOperations)
            .failPreparedWorldInstance(42L, 101L, 1L, "source failed");

    assertEquals("FAILED_PRE_ACTIVATION", snapshot.status());
    assertEquals(2L, snapshot.lifecycleEpoch());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    assertEquals(1L, persisted.get().getRunOwnedFixturePreparingLifecycleEpoch());
    assertEquals(1L, persisted.get().getRowVersion());
    verify(provider).requireAuthenticatedEvidence();
    verify(worldInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(42L, 101L);
  }

  @Test
  void fixtureFailureRejectsChangedBindingBeforeSaving() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("d".repeat(64));
    WorldInstance instance = boundFixtureInstance("PREPARING", 1L);
    stubPersistedWorldInstance(instance);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                fixtureService(provider, lifecycleTransactionOperations)
                    .failPreparedWorldInstance(42L, 101L, 1L, "source failed"));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:"));
    assertEquals("PREPARING", instance.getStatus());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
  }

  @Test
  void fixtureFailureRejectsAmbientTransactionBeforeLoadingOwnerEvidence() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    when(provider.isEnabled()).thenReturn(true);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  fixtureService(provider, lifecycleTransactionOperations)
                      .failPreparedWorldInstance(42L, 101L, 1L, "source failed"));

      assertTrue(error.getMessage().contains("no ambient transaction"));
      verify(provider, never()).requireAuthenticatedEvidence();
      verify(worldInstanceRepository, never()).findByTenantIdAndGameInstanceIdForUpdate(42L, 101L);
      verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
      verify(gameDesignClient, never()).getPublishedReleaseBundle(42L, 11L);
      verify(entityManagementClient, never())
          .cleanupRuntimeInstance(anyLong(), anyLong(), anyString());
    } finally {
      TransactionSynchronizationManager.clear();
    }
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
  void terminatedWorldInstanceReturnsStoredSnapshotForExactRetryWithoutEffects() {
    WorldInstance instance = activeWorldInstance();
    instance.setStatus("TERMINATED");
    instance.setTerminationRequestId("term-1");
    instance.setLifecycleEpoch(4L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    assertEquals(4L, snapshot.lifecycleEpoch());
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

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "term-1")
  void terminatedWorldInstanceRejectsDifferentOrMissingRetainedRequestIdWithoutEffects(
      String retainedRequestId) {
    WorldInstance instance = activeWorldInstance();
    instance.setStatus("TERMINATED");
    instance.setTerminationRequestId(retainedRequestId);
    instance.setLifecycleEpoch(4L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(instance));
    String requestId = retainedRequestId == null ? "term-1" : "term-2";

    assertTerminationConflictWithoutEffects(instance, retainedRequestId, requestId);
  }

  private void assertTerminationConflictWithoutEffects(
      WorldInstance instance, String retainedRequestId, String requestId) {
    IllegalArgumentException conflict =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.terminateWorldInstance(42L, 101L, 2L, requestId, "stop"));
    assertTrue(conflict.getMessage().startsWith("IDEMPOTENCY_CONFLICT:"));
    assertEquals("TERMINATED", instance.getStatus());
    assertEquals(4L, instance.getLifecycleEpoch());
    assertEquals(retainedRequestId, instance.getTerminationRequestId());
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
  void fixtureTerminationKeepsActiveRowAndSkipsEntityWhenOwnerHoldIsUnresolved() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance instance = boundFixtureInstance("ACTIVE", 2L);
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
            () ->
                fixtureService(provider, lifecycleTransactionOperations)
                    .terminateWorldInstance(42L, 101L, 2L, "term-fixture", "stop"));

    assertTrue(error.getMessage().startsWith("INITIAL_ADMISSION_BIND_HOLD_ACTIVE:"));
    assertEquals("ACTIVE", instance.getStatus());
    verify(provider).requireAuthenticatedEvidence();
    verify(entityManagementClient, never())
        .cleanupRuntimeInstance(anyLong(), anyLong(), anyString());
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(worldEventRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void fixtureEntityCleanupFailureRetainsTerminatingOwnerRowForSameRequestRetry() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance instance = boundFixtureInstance("ACTIVE", 2L);
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);
    doThrow(new IllegalStateException("Entity cleanup unavailable"))
        .when(entityManagementClient)
        .cleanupRuntimeInstance(42L, 101L, "term-fixture");

    assertThrows(
        IllegalStateException.class,
        () ->
            fixtureService(provider, lifecycleTransactionOperations)
                .terminateWorldInstance(42L, 101L, 2L, "term-fixture", "stop"));

    assertEquals("TERMINATING", persisted.get().getStatus());
    assertEquals(3L, persisted.get().getLifecycleEpoch());
    assertEquals("term-fixture", persisted.get().getTerminationRequestId());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    verify(worldInstanceRepository, times(1)).save(any(WorldInstance.class));
    verify(worldEventRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  @Test
  void fixtureTerminatedReplayRequiresTheOriginalRequestIdAndDoesNotRepeatEffects() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance instance = boundFixtureInstance("ACTIVE", 2L);
    AtomicReference<WorldInstance> persisted = stubPersistedWorldInstance(instance);
    when(entityManagementClient.cleanupRuntimeInstance(42L, 101L, "term-fixture"))
        .thenReturn(CleanupRuntimeInstanceResponse.newBuilder().build());
    WorldLifecycleCommandServiceImpl fixtureService =
        fixtureService(provider, lifecycleTransactionOperations);

    var terminated = fixtureService.terminateWorldInstance(42L, 101L, 2L, "term-fixture", "stop");
    long terminalRowVersion = persisted.get().getRowVersion();
    var replay = fixtureService.terminateWorldInstance(42L, 101L, 2L, "term-fixture", "stop");

    assertEquals("TERMINATED", terminated.status());
    assertEquals(4L, terminated.lifecycleEpoch());
    assertEquals(terminated, replay);
    assertEquals(terminalRowVersion, persisted.get().getRowVersion());
    verify(entityManagementClient, times(1)).cleanupRuntimeInstance(42L, 101L, "term-fixture");
    verify(worldInstanceRepository, times(2)).save(any(WorldInstance.class));

    WorldInstance retainedTerminal = copyWorldInstance(persisted.get());
    IllegalArgumentException conflict =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService.terminateWorldInstance(42L, 101L, 2L, "term-other", "stop"));

    assertTrue(conflict.getMessage().startsWith("IDEMPOTENCY_CONFLICT:"));
    assertEquals("TERMINATED", persisted.get().getStatus());
    assertEquals(retainedTerminal.getLifecycleEpoch(), persisted.get().getLifecycleEpoch());
    assertEquals(
        retainedTerminal.getTerminationRequestId(), persisted.get().getTerminationRequestId());
    assertEquals(terminalRowVersion, persisted.get().getRowVersion());
    verify(entityManagementClient, times(1)).cleanupRuntimeInstance(42L, 101L, "term-fixture");
    verify(worldInstanceRepository, times(2)).save(any(WorldInstance.class));
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
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("a".repeat(64));
    AtomicReference<WorldInstance> persisted = new AtomicReference<>();
    when(worldInstanceRepository.findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
            42L, "cp-1"))
        .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance submitted = invocation.getArgument(0);
              submitted.setId(101L);
              persisted.set(copyWorldInstance(submitted));
              return submitted;
            });
    WorldLifecycleCommandServiceImpl fixtureService = fixtureService(provider);
    PreparedWorldInstanceRequest request = ordinaryPreparedRequest();

    var prepared = fixtureService.prepareWorldInstance(request);

    assertEquals("PREPARING", prepared.status());
    assertEquals(1L, prepared.lifecycleEpoch());
    assertEquals("a".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    assertEquals(1L, persisted.get().getRunOwnedFixturePreparingLifecycleEpoch());
    verify(evidence).requireMatches(request);
    verify(worldInstanceRepository).lockRunOwnedFixtureOperation(42L, "cp-1");
    verify(gameDesignClient, never()).getPublishedReleaseBundle(42L, 11L);
    verify(gameDesignClient, never()).getVersionAssetArtifactState(42L, 11L);
    verify(gameDesignClient, never()).getVersionState(42L, 11L);

    var retry = fixtureService.prepareWorldInstance(request);

    assertEquals(prepared, retry);
    persisted.get().setStatus("ACTIVE");
    persisted.get().setLifecycleEpoch(2L);

    var activeReplay = fixtureService.prepareWorldInstance(request);

    assertEquals("ACTIVE", activeReplay.status());
    assertEquals(2L, activeReplay.lifecycleEpoch());
    assertEquals("a".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());
    assertEquals(1L, persisted.get().getRunOwnedFixturePreparingLifecycleEpoch());
    verify(worldInstanceRepository, org.mockito.Mockito.times(1)).save(any(WorldInstance.class));
    verify(regionInstanceRepository, org.mockito.Mockito.times(1)).save(any(RegionInstance.class));
    verify(roomInstanceRepository, org.mockito.Mockito.times(1)).save(any(RoomInstance.class));
    verify(evidence, org.mockito.Mockito.times(3)).requireMatches(request);
  }

  @Test
  void fixturePrepareRejectsChangedBindingForExistingActiveRow() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("d".repeat(64));
    WorldInstance active = boundFixtureInstance("ACTIVE", 2L);
    when(worldInstanceRepository.findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
            42L, "cp-1"))
        .thenReturn(Optional.of(active));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(active));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService(provider).prepareWorldInstance(ordinaryPreparedRequest()));

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_EVIDENCE_MISMATCH:"));
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
  }

  @Test
  void fixtureOperationCannotBeAssignedToASecondWorldInstance() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("b".repeat(64));
    WorldInstance operationInstance = new WorldInstance();
    operationInstance.setGameInstanceId(101L);
    when(worldInstanceRepository.findRunOwnedFixtureByTenantIdAndControlPlaneRequestIdForUpdate(
            42L, "cp-1"))
        .thenReturn(Optional.of(operationInstance));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                fixtureService(provider)
                    .prepareWorldInstance(
                        new PreparedWorldInstanceRequest(
                            42L,
                            202L,
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

    assertTrue(error.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT:"));
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(gameDesignClient, never()).getPublishedReleaseBundle(42L, 11L);
  }

  @Test
  void fixtureActivationVerifiesBindingThenAllowsOnlyOriginalEpochActiveReplayWithoutWrite() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance preparing = boundFixtureInstance("PREPARING", 1L);
    AtomicReference<WorldInstance> persisted = new AtomicReference<>(copyWorldInstance(preparing));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenAnswer(invocation -> Optional.of(copyWorldInstance(persisted.get())));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenAnswer(invocation -> Optional.of(copyWorldInstance(persisted.get())));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(
            invocation -> {
              WorldInstance submitted = invocation.getArgument(0);
              submitted.setRowVersion(submitted.getRowVersion() + 1L);
              persisted.set(copyWorldInstance(submitted));
              return copyWorldInstance(submitted);
            });
    WorldLifecycleCommandServiceImpl fixtureService = fixtureService(provider);

    var active = fixtureService.activatePreparedWorldInstance(42L, 101L, 1L);
    assertEquals("ACTIVE", active.status());
    assertEquals(2L, active.lifecycleEpoch());
    assertEquals("c".repeat(64), persisted.get().getRunOwnedFixtureBindingSha256());

    var replay = fixtureService.activatePreparedWorldInstance(42L, 101L, 1L);
    assertEquals(active, replay);
    verify(worldInstanceRepository, org.mockito.Mockito.times(1)).save(any(WorldInstance.class));
    verify(provider, org.mockito.Mockito.times(2)).requireAuthenticatedEvidence();
    verify(gameDesignClient, never()).getPublishedReleaseBundle(42L, 11L);

    var inOrder = inOrder(provider, worldInstanceRepository);
    inOrder.verify(provider).requireAuthenticatedEvidence();
    inOrder.verify(worldInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(42L, 101L);
    inOrder.verify(provider).requireAuthenticatedEvidence();
    inOrder.verify(worldInstanceRepository).findByTenantIdAndGameInstanceIdForUpdate(42L, 101L);
  }

  @Test
  void configuredFixtureRejectsUnboundExistingRowsAndStaleActiveReplayEpoch() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldInstance unbound = activeWorldInstance();
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(unbound));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(unbound));

    IllegalArgumentException unboundError =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService(provider).activatePreparedWorldInstance(42L, 101L, 2L));
    assertTrue(unboundError.getMessage().startsWith("RUN_OWNED_WORLD_LAUNCH_IDENTITY_CONFLICT:"));
    verify(gameDesignClient, never()).getPublishedReleaseBundle(42L, 11L);

    WorldInstance active = boundFixtureInstance("ACTIVE", 2L);
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(active));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenReturn(Optional.of(active));
    IllegalArgumentException stale =
        assertThrows(
            IllegalArgumentException.class,
            () -> fixtureService(provider).activatePreparedWorldInstance(42L, 101L, 0L));
    assertTrue(stale.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));
    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
  }

  @Test
  void fixtureActivationRejectsOriginalEpochForNonActiveRowsWithoutWrites() {
    RunOwnedWorldLaunchEvidenceProvider provider = mock(RunOwnedWorldLaunchEvidenceProvider.class);
    RunOwnedWorldLaunchEvidence evidence = mock(RunOwnedWorldLaunchEvidence.class);
    when(provider.isEnabled()).thenReturn(true);
    when(provider.requireAuthenticatedEvidence()).thenReturn(evidence);
    when(evidence.bindingDigest(any(PreparedWorldInstanceRequest.class), anyLong()))
        .thenReturn("c".repeat(64));
    WorldLifecycleCommandServiceImpl fixtureService = fixtureService(provider);
    AtomicReference<WorldInstance> currentRow = new AtomicReference<>();
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenAnswer(invocation -> Optional.of(currentRow.get()));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceIdForUpdate(42L, 101L))
        .thenAnswer(invocation -> Optional.of(currentRow.get()));

    String[] statuses = {"FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED"};
    long[] lifecycleEpochs = {2L, 3L, 4L};
    for (int index = 0; index < statuses.length; index++) {
      WorldInstance row = boundFixtureInstance(statuses[index], lifecycleEpochs[index]);
      currentRow.set(row);

      IllegalArgumentException stale =
          assertThrows(
              IllegalArgumentException.class,
              () -> fixtureService.activatePreparedWorldInstance(42L, 101L, 1L));

      assertTrue(stale.getMessage().startsWith("WORLD_INSTANCE_LIFECYCLE_STALE:"));
      assertEquals(statuses[index], row.getStatus());
      assertEquals(lifecycleEpochs[index], row.getLifecycleEpoch());
    }

    verify(worldInstanceRepository, never()).save(any(WorldInstance.class));
    verify(regionInstanceRepository, never()).save(any());
    verify(zoneInstanceRepository, never()).save(any());
    verify(roomInstanceRepository, never()).save(any());
    verify(roomInstanceExitRepository, never()).save(any());
    verify(worldEventRepository, never()).save(any());
    verify(regionInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(zoneInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(worldEventRepository, never()).deleteByTenantIdAndGameInstanceId(42L, 101L);
  }

  private WorldLifecycleCommandServiceImpl fixtureService(
      RunOwnedWorldLaunchEvidenceProvider provider) {
    return fixtureService(provider, null);
  }

  private WorldLifecycleCommandServiceImpl fixtureService(
      RunOwnedWorldLaunchEvidenceProvider provider, TransactionOperations transactionOperations) {
    WorldProperties worldProperties = new WorldProperties();
    worldProperties.setLocalShardId(7);
    WorldLifecycleCommandServiceImpl fixtureService =
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
            provider);
    fixtureService.initMetrics();
    return fixtureService;
  }

  private static PreparedWorldInstanceRequest ordinaryPreparedRequest() {
    return new PreparedWorldInstanceRequest(
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
        77L);
  }

  private WorldInstance boundFixtureInstance(String status, long lifecycleEpoch) {
    WorldInstance instance = activeWorldInstance();
    instance.setLifecycleEpoch(lifecycleEpoch);
    instance.setStatus(status);
    instance.setRunOwnedFixtureBindingSha256("c".repeat(64));
    instance.setRunOwnedFixturePreparingLifecycleEpoch(1L);
    return instance;
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
