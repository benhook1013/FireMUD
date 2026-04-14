package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestRequest;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class GameDesignGrpcServiceTest {
  private final PingService pingService = Mockito.mock(PingService.class);
  private final RevisionService revisionService = Mockito.mock(RevisionService.class);
  private final VersionService versionService = Mockito.mock(VersionService.class);
  private final LaunchDescriptorService launchDescriptorService =
      Mockito.mock(LaunchDescriptorService.class);
  private final VersionAssetArtifactService versionAssetArtifactService =
      Mockito.mock(VersionAssetArtifactService.class);
  private final SettingsAuthorityService settingsAuthorityService =
      Mockito.mock(SettingsAuthorityService.class);
  private final GameDesignGrpcService service =
      new GameDesignGrpcService(
          pingService,
          revisionService,
          versionService,
          launchDescriptorService,
          versionAssetArtifactService,
          settingsAuthorityService,
          new SimpleMeterRegistry());

  @Test
  void getPublishedReleaseBundleReturnsCanonicalAttestation() {
    Mockito.when(versionService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                11L,
                "tenant-1",
                7L,
                8,
                "v1",
                "workflow-1",
                "abc123",
                List.of("logo.png", "manifest.json"),
                List.of(
                    new PublishParticipantDigestDto(
                        "GAME_DESIGN_CONTROL_PLANE", "7", "version:7", "digest-1", 1, null, null)),
                "genrev-1",
                false,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00")));

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getPublishedReleaseBundle(
          GetPublishedReleaseBundleRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(11L, ref.get().getBundle().getId());
    assertEquals("abc123", ref.get().getBundle().getManifestHash());
    assertEquals("genrev-1", ref.get().getBundle().getGenerationConfigRevision());
    assertEquals(2, ref.get().getBundle().getRequiredManifestAssetKeysCount());
    assertEquals(1, ref.get().getBundle().getParticipantDigestsCount());
  }

  @Test
  void resolveLaunchDescriptorReturnsDeterministicDescriptor() {
    Mockito.when(
            launchDescriptorService.resolveLaunchDescriptor(
                "tenant-1", 9L, "cp-1", null, null, null, null))
        .thenReturn(
            new ResolvedLaunchDescriptorDto(
                "ld-1",
                "tenant-1",
                9L,
                "cp-1",
                7L,
                "patch-1",
                "{}",
                "genrev-1",
                11L,
                11L,
                "prb:tenant-1:7:11"));

    AtomicReference<ResolveLaunchDescriptorResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.resolveLaunchDescriptor(
          ResolveLaunchDescriptorRequest.newBuilder()
              .setTenantId("tenant-1")
              .setGameTemplateId(9L)
              .setControlPlaneRequestId("cp-1")
              .build(),
          new StreamObserver<>() {
            @Override
            public void onNext(ResolveLaunchDescriptorResponse value) {
              ref.set(value);
            }

            @Override
            public void onError(Throwable t) {
              throw new AssertionError(t);
            }

            @Override
            public void onCompleted() {}
          });
    }

    assertEquals("ld-1", ref.get().getLaunchDescriptor().getLaunchDescriptorId());
    assertEquals("genrev-1", ref.get().getLaunchDescriptor().getGenerationConfigRevision());
  }

  @Test
  void getDesignControlPlaneDigestReturnsCanonicalDigest() {
    Mockito.when(versionService.getDesignControlPlaneDigest("tenant-1", 7L))
        .thenReturn(new DesignControlPlaneDigestDto("tenant-1", "7", "version:7", "digest-1", 1));

    AtomicReference<GetDesignControlPlaneDigestResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getDesignControlPlaneDigest(
          GetDesignControlPlaneDigestRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .build(),
          new StreamObserver<>() {
            @Override
            public void onNext(GetDesignControlPlaneDigestResponse value) {
              ref.set(value);
            }

            @Override
            public void onError(Throwable t) {
              throw new AssertionError(t);
            }

            @Override
            public void onCompleted() {}
          });
    }

    assertEquals("digest-1", ref.get().getDigest().getContentDigest());
  }

  @Test
  void getVersionAssetArtifactStateReturnsArtifactProof() {
    Mockito.when(versionAssetArtifactService.getState("tenant-1", 7L))
        .thenReturn(
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                7L,
                "PUBLISHED",
                2L,
                "hash-1",
                "workflow-1",
                null,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00"),
                List.of("logo.png", "manifest.json")));

    AtomicReference<GetVersionAssetArtifactStateResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getVersionAssetArtifactState(
          GetVersionAssetArtifactStateRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .build(),
          new StreamObserver<>() {
            @Override
            public void onNext(GetVersionAssetArtifactStateResponse value) {
              ref.set(value);
            }

            @Override
            public void onError(Throwable t) {
              throw new AssertionError(t);
            }

            @Override
            public void onCompleted() {}
          });
    }

    assertEquals(
        "ARTIFACT_STATE_PUBLISHED", ref.get().getArtifactState().getArtifactState().name());
    assertEquals("hash-1", ref.get().getArtifactState().getManifestHash());
    assertEquals(2, ref.get().getArtifactState().getExportedManifestAssetKeysCount());
  }

  @Test
  void tombstoneVersionAssetsReturnsStructuredStateTransition() {
    Mockito.when(versionAssetArtifactService.tombstoneVersionAssets("tenant-1", 7L, 3L, "wf-1"))
        .thenReturn(
            new net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto(
                "tenant-1",
                7L,
                "TOMBSTONED",
                4L,
                "hash-1",
                "wf-1",
                null,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00"),
                List.of("manifest.json")));

    AtomicReference<TombstoneVersionAssetsResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.tombstoneVersionAssets(
          TombstoneVersionAssetsRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .setExpectedArtifactStateEpoch(3L)
              .setTombstoneWorkflowId("wf-1")
              .build(),
          new StreamObserver<>() {
            @Override
            public void onNext(TombstoneVersionAssetsResponse value) {
              ref.set(value);
            }

            @Override
            public void onError(Throwable t) {
              throw new AssertionError(t);
            }

            @Override
            public void onCompleted() {}
          });
    }

    assertEquals(
        "ARTIFACT_STATE_TOMBSTONED", ref.get().getArtifactState().getArtifactState().name());
    assertEquals("wf-1", ref.get().getArtifactState().getLastWorkflowId());
  }

  private static StreamObserver<GetPublishedReleaseBundleResponse> observerFor(
      AtomicReference<GetPublishedReleaseBundleResponse> ref) {
    return new StreamObserver<>() {
      @Override
      public void onNext(GetPublishedReleaseBundleResponse value) {
        ref.set(value);
      }

      @Override
      public void onError(Throwable t) {
        throw new AssertionError(t);
      }

      @Override
      public void onCompleted() {}
    };
  }
}
