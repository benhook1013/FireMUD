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
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestRequest;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class GameDesignGrpcServiceTest {
  private final PingService pingService = Mockito.mock(PingService.class);
  private final RevisionService revisionService = Mockito.mock(RevisionService.class);
  private final VersionService versionService = Mockito.mock(VersionService.class);
  private final SettingsAuthorityService settingsAuthorityService =
      Mockito.mock(SettingsAuthorityService.class);
  private final GameDesignGrpcService service =
      new GameDesignGrpcService(
          pingService,
          revisionService,
          versionService,
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
    assertEquals(2, ref.get().getBundle().getRequiredManifestAssetKeysCount());
    assertEquals(1, ref.get().getBundle().getParticipantDigestsCount());
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
