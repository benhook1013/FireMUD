package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class GameDesignGrpcServiceAuthTest {
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");

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
            Mockito.mock(CompleteLaunchBindingService.class),
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
  void publishedReleaseBundleReadRequiresExactSameNamespaceWorkloadIdentity() {
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
                MANIFEST_HASH,
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                java.time.LocalDateTime.parse("2026-04-14T12:00:00"),
                CANONICAL_TENANT_ID,
                CANONICAL_VERSION_ID,
                "opaque-release-reference-from-owner",
                1,
                List.of(
                    new PublishedArtifactDigest(
                        "manifest.json",
                        "manifest",
                        "artifacts/sha256/" + "b".repeat(64),
                        "sha256:" + "b".repeat(64),
                        "application/json",
                        1))));
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            versionService,
            launchDescriptorService,
            Mockito.mock(CompleteLaunchBindingService.class),
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());

    SessionContext.setContext(null, List.of(), Map.of(), true, "game-session-service", "gs-1");
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");

    AtomicReference<GetPublishedReleaseBundleResponse> bundleRef = new AtomicReference<>();
    underGameSessionPeer(
        () ->
            service.getPublishedReleaseBundle(
                GetPublishedReleaseBundleRequest.newBuilder()
                    .setTenantId("1")
                    .setVersionId(7L)
                    .build(),
                new StreamObserver<>() {
                  @Override
                  public void onNext(GetPublishedReleaseBundleResponse value) {
                    bundleRef.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {}

                  @Override
                  public void onCompleted() {}
                }));

    assertNotNull(bundleRef.get());
    assertEquals("", bundleRef.get().getError().getCode());
    assertEquals(11L, bundleRef.get().getBundle().getId());
    Mockito.verifyNoInteractions(launchDescriptorService);
  }

  @Test
  void versionStateAndArtifactStateReadsAllowOnlySameNamespaceGameSessionAndWorldManagementPeers() {
    for (String serviceName : List.of("game-session-service", "world-management-service")) {
      ReadFixture fixture = readFixture();
      SessionContext.setContext(null, List.of(), Map.of(), true, serviceName, serviceName + "-1");
      withPeer(
          serviceName,
          "test",
          () -> {
            AtomicReference<GetVersionStateResponse> versionState = new AtomicReference<>();
            fixture.service.getVersionState(
                GetVersionStateRequest.newBuilder().setTenantId("1").setVersionId(7L).build(),
                observer(versionState));
            assertEquals("", versionState.get().getError().getCode());

            AtomicReference<GetVersionAssetArtifactStateResponse> artifactState =
                new AtomicReference<>();
            fixture.service.getVersionAssetArtifactState(
                GetVersionAssetArtifactStateRequest.newBuilder()
                    .setTenantId("1")
                    .setVersionId(7L)
                    .build(),
                observer(artifactState));
            assertEquals("", artifactState.get().getError().getCode());
          });
      Mockito.verify(fixture.versionService).getVersionState("1", 7L);
      Mockito.verify(fixture.artifactService).getState("1", 7L);
      SessionContext.clear();
    }
  }

  @Test
  void versionStateAndArtifactStateReadsDenyWrongPeerNamespaceAndAnonymousCallers() {
    for (String[] caller :
        new String[][] {
          {"account-service", "test"},
          {"game-session-service", "other"},
          {null, "test"}
        }) {
      ReadFixture fixture = readFixture();
      if (caller[0] != null) {
        SessionContext.setContext(null, List.of(), Map.of(), true, caller[0], caller[0] + "-1");
      }
      withPeer(
          caller[0],
          caller[1],
          () -> {
            AtomicReference<GetVersionStateResponse> versionState = new AtomicReference<>();
            fixture.service.getVersionState(
                GetVersionStateRequest.newBuilder().setTenantId("1").setVersionId(7L).build(),
                observer(versionState));
            assertEquals("PERMISSION_DENIED", versionState.get().getError().getCode());

            AtomicReference<GetVersionAssetArtifactStateResponse> artifactState =
                new AtomicReference<>();
            fixture.service.getVersionAssetArtifactState(
                GetVersionAssetArtifactStateRequest.newBuilder()
                    .setTenantId("1")
                    .setVersionId(7L)
                    .build(),
                observer(artifactState));
            assertEquals("PERMISSION_DENIED", artifactState.get().getError().getCode());
          });
      Mockito.verifyNoInteractions(fixture.versionService, fixture.artifactService);
      SessionContext.clear();
    }
  }

  private ReadFixture readFixture() {
    VersionService versionService = Mockito.mock(VersionService.class);
    VersionAssetArtifactService artifactService = Mockito.mock(VersionAssetArtifactService.class);
    Mockito.when(versionService.getVersionState("1", 7L))
        .thenReturn(
            new VersionStateDto(
                "1", 7L, VersionLifecycleState.PUBLISHED, 3L, java.time.LocalDateTime.now()));
    Mockito.when(artifactService.getState("1", 7L))
        .thenReturn(
            new VersionAssetArtifactStateDto(
                "1",
                7L,
                1,
                "PUBLISHED",
                2L,
                MANIFEST_HASH,
                "workflow-1",
                null,
                null,
                java.time.LocalDateTime.now(),
                List.of("manifest.json")));
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            versionService,
            Mockito.mock(LaunchDescriptorService.class),
            Mockito.mock(CompleteLaunchBindingService.class),
            Mockito.mock(TemplateRemapSetService.class),
            artifactService,
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");
    return new ReadFixture(service, versionService, artifactService);
  }

  private static <T> StreamObserver<T> observer(AtomicReference<T> response) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        response.set(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }

  private void withPeer(String serviceName, String namespace, Runnable operation) {
    if (serviceName == null) {
      operation.run();
      return;
    }
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + serviceName)
            .orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(operation);
  }

  private record ReadFixture(
      GameDesignGrpcService service,
      VersionService versionService,
      VersionAssetArtifactService artifactService) {}

  private void underGameSessionPeer(Runnable operation) {
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service").orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(operation);
  }
}
