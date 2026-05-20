package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
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
  private GameDesignClient gameDesignClient;
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
    gameDesignClient = mock(GameDesignClient.class);
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
            new SimpleMeterRegistry());
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
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(42L, 101L))
        .thenReturn(Optional.of(instance));
    when(worldInstanceRepository.save(any(WorldInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var snapshot = service.terminateWorldInstance(42L, 101L, 2L, "term-1", "stop");

    assertEquals("TERMINATED", snapshot.status());
    verify(worldEventRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceExitRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(roomInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(zoneInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
    verify(regionInstanceRepository).deleteByTenantIdAndGameInstanceId(42L, 101L);
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
