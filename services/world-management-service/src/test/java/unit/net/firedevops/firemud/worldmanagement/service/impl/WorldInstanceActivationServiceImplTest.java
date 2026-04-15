package net.firedevops.firemud.worldmanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.config.WorldProperties;
import net.firedevops.firemud.worldmanagement.dto.PreparedWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldInstanceActivationServiceImplTest {
  private WorldInstanceRepository worldInstanceRepository;
  private RegionInstanceRepository regionInstanceRepository;
  private GameDesignClient gameDesignClient;
  private WorldInstanceActivationServiceImpl service;

  @BeforeEach
  void setUp() {
    worldInstanceRepository = mock(WorldInstanceRepository.class);
    regionInstanceRepository = mock(RegionInstanceRepository.class);
    gameDesignClient = mock(GameDesignClient.class);
    WorldProperties worldProperties = new WorldProperties();
    worldProperties.setLocalShardId(7);
    service =
        new WorldInstanceActivationServiceImpl(
            worldInstanceRepository,
            regionInstanceRepository,
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
                        .setGenerationConfigRevision("genrev-11")
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
}
