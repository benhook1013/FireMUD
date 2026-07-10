package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameDesignGrpcServiceAuthTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void adminMethodsReturnPermissionDeniedErrorDetail() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            Mockito.mock(VersionService.class),
            Mockito.mock(LaunchDescriptorService.class),
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());

    AtomicReference<ListVersionsResponse> ref = new AtomicReference<>();
    service.listVersions(
        ListVersionsRequest.newBuilder().setTenantId("1").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListVersionsResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(ref.get());
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    assertEquals("Admin role required", ref.get().getError().getMessage());
  }

  @Test
  void launchAttestationReadMethodsAllowInternalServiceIdentity() {
    VersionService versionService = Mockito.mock(VersionService.class);
    LaunchDescriptorService launchDescriptorService = Mockito.mock(LaunchDescriptorService.class);
    Mockito.when(versionService.getPublishedReleaseBundle("1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                11L,
                "1",
                7L,
                8,
                "v1",
                "workflow-1",
                "hash-1",
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                java.time.LocalDateTime.parse("2026-04-14T12:00:00")));
    Mockito.when(
            launchDescriptorService.resolveLaunchDescriptor(
                "1", 9L, "cp-1", null, null, null, null))
        .thenReturn(
            new ResolvedLaunchDescriptorDto(
                "ld-1", "1", 9L, "cp-1", 7L, null, "{}", "genrev-1", 11L, 11L, "prb:1:7:11", null));

    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            versionService,
            launchDescriptorService,
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());

    SessionContext.setContext(null, List.of(), Map.of(), true, "game-session-service", "gs-1");

    AtomicReference<GetPublishedReleaseBundleResponse> bundleRef = new AtomicReference<>();
    service.getPublishedReleaseBundle(
        GetPublishedReleaseBundleRequest.newBuilder().setTenantId("1").setVersionId(7L).build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetPublishedReleaseBundleResponse value) {
            bundleRef.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    AtomicReference<ResolveLaunchDescriptorResponse> descriptorRef = new AtomicReference<>();
    service.resolveLaunchDescriptor(
        ResolveLaunchDescriptorRequest.newBuilder()
            .setTenantId("1")
            .setGameTemplateId(9L)
            .setControlPlaneRequestId("cp-1")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ResolveLaunchDescriptorResponse value) {
            descriptorRef.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    assertNotNull(bundleRef.get());
    assertEquals("", bundleRef.get().getError().getCode());
    assertEquals(11L, bundleRef.get().getBundle().getId());
    assertNotNull(descriptorRef.get());
    assertEquals("", descriptorRef.get().getError().getCode());
    assertEquals("ld-1", descriptorRef.get().getLaunchDescriptor().getLaunchDescriptorId());
  }
}
