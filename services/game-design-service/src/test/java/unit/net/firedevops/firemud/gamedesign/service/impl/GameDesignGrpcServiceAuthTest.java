package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

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
  void launchDescriptorRejectsJwtOnlyInternalServiceIdentity() {
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
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");

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
            .setCanonicalTenantId("12345678-1234-4234-8234-123456789abc")
            .setGameTemplateId(9L)
            .setControlPlaneRequestId("cp-1")
            .setWorldSlug("silver-march")
            .setAuthoredWorldSourceOperationId("22345678-1234-4234-8234-123456789abc")
            .setExpectedAuthoredWorldSourceEvidenceDigest("sha256:" + "a".repeat(64))
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
    assertEquals("PERMISSION_DENIED", descriptorRef.get().getError().getCode());
    Mockito.verifyNoInteractions(launchDescriptorService);
  }

  @Test
  void
      launchDescriptorResolveRejectsMissingWrongWorkloadAndWrongNamespacePeersBeforeServiceAccess() {
    LaunchDescriptorService launchDescriptorService = Mockito.mock(LaunchDescriptorService.class);
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            Mockito.mock(VersionService.class),
            launchDescriptorService,
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");
    SessionContext.setContext(null, List.of(), Map.of(), true, "game-session-service", "gs-1");

    assertEquals("PERMISSION_DENIED", resolve(service, null).getError().getCode());
    assertEquals(
        "PERMISSION_DENIED",
        resolve(service, "spiffe://firemud/ns/test/sa/world-management-service")
            .getError()
            .getCode());
    assertEquals(
        "PERMISSION_DENIED",
        resolve(service, "spiffe://firemud/ns/other/sa/game-session-service").getError().getCode());
    Mockito.verifyNoInteractions(launchDescriptorService);
  }

  @Test
  void launchDescriptorReadRejectsWrongWorkloadAndNamespacePeersBeforeServiceAccess() {
    LaunchDescriptorService launchDescriptorService = Mockito.mock(LaunchDescriptorService.class);
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            Mockito.mock(VersionService.class),
            launchDescriptorService,
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");
    SessionContext.setContext(null, List.of(), Map.of(), true, "world-management-service", "wms-1");

    assertEquals(
        "PERMISSION_DENIED",
        readDescriptor(service, "spiffe://firemud/ns/test/sa/game-design-service")
            .getError()
            .getCode());
    assertEquals(
        "PERMISSION_DENIED",
        readDescriptor(service, "spiffe://firemud/ns/other/sa/world-management-service")
            .getError()
            .getCode());
    Mockito.verifyNoInteractions(launchDescriptorService);
  }

  private ResolveLaunchDescriptorResponse resolve(GameDesignGrpcService service, String peerUri) {
    AtomicReference<ResolveLaunchDescriptorResponse> response = new AtomicReference<>();
    Runnable call =
        () ->
            service.resolveLaunchDescriptor(
                ResolveLaunchDescriptorRequest.newBuilder()
                    .setCanonicalTenantId("12345678-1234-4234-8234-123456789abc")
                    .setGameTemplateId(9L)
                    .setControlPlaneRequestId("cp-auth")
                    .setWorldSlug("silver-march")
                    .setAuthoredWorldSourceOperationId("22345678-1234-4234-8234-123456789abc")
                    .setExpectedAuthoredWorldSourceEvidenceDigest("sha256:" + "a".repeat(64))
                    .build(),
                observerFor(response));
    runAsPeer(peerUri, call);
    return response.get();
  }

  private GetLaunchDescriptorResponse readDescriptor(
      GameDesignGrpcService service, String peerUri) {
    AtomicReference<GetLaunchDescriptorResponse> response = new AtomicReference<>();
    Runnable call =
        () ->
            service.getLaunchDescriptor(
                GetLaunchDescriptorRequest.newBuilder()
                    .setRequestId("32345678-1234-4234-8234-123456789abc")
                    .setCanonicalTenantId("12345678-1234-4234-8234-123456789abc")
                    .setWorldSlug("silver-march")
                    .setControlPlaneRequestId("cp-auth")
                    .setExpectedRequestDigest("sha256:" + "a".repeat(64))
                    .setExpectedResultDigest("sha256:" + "b".repeat(64))
                    .build(),
                observerFor(response));
    runAsPeer(peerUri, call);
    return response.get();
  }

  private void runAsPeer(String peerUri, Runnable call) {
    if (peerUri == null) {
      call.run();
      return;
    }
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(call);
  }

  private static <T> StreamObserver<T> observerFor(AtomicReference<T> ref) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
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
