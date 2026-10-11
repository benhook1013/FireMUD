package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.CompareAndSetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.CompareAndSetVersionStateResponse;
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
  private static final String WORKLOAD_NAMESPACE = "test";

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void versionStateMutationDeniesPlayerBeforeOwnerRead() {
    VersionService versionService = Mockito.mock(VersionService.class);
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            versionService,
            Mockito.mock(LaunchDescriptorService.class),
            Mockito.mock(CompleteLaunchBindingService.class),
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    SessionContext.setContext("1", List.of("player"), Map.of());
    AtomicReference<CompareAndSetVersionStateResponse> response = new AtomicReference<>();
    service.compareAndSetVersionState(
        CompareAndSetVersionStateRequest.newBuilder()
            .setTenantId("1")
            .setVersionId(7L)
            .setExpectedVersionStateEpoch(1L)
            .setNewState(
                net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                    .VERSION_LIFECYCLE_STATE_RETIRED)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CompareAndSetVersionStateResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable throwable) {}

          @Override
          public void onCompleted() {}
        });
    assertEquals("PERMISSION_DENIED", response.get().getError().getCode());
    Mockito.verifyNoInteractions(versionService);
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
  void publishedReleaseBundleReadDeniesEveryIdentityBeforeOwnerServiceAccess() {
    VersionService versionService = Mockito.mock(VersionService.class);
    GameDesignGrpcService service =
        newService(versionService, Mockito.mock(VersionAssetArtifactService.class));
    ReflectionTestUtils.setField(service, "workloadNamespace", WORKLOAD_NAMESPACE);

    this.<GetPublishedReleaseBundleResponse>assertDeniedMatrix(
        observer ->
            service.getPublishedReleaseBundle(
                GetPublishedReleaseBundleRequest.newBuilder()
                    .setTenantId("1")
                    .setVersionId(7L)
                    .build(),
                observer),
        response -> response.getError().getCode(),
        "game-session-service",
        "world-management-service",
        "automation-scripting-service");
    verifyNoInteractions(versionService);
  }

  @Test
  void versionStateReadDeniesEveryIdentityBeforeOwnerServiceAccess() {
    VersionService versionService = Mockito.mock(VersionService.class);
    GameDesignGrpcService service =
        newService(versionService, Mockito.mock(VersionAssetArtifactService.class));
    ReflectionTestUtils.setField(service, "workloadNamespace", WORKLOAD_NAMESPACE);

    this.<GetVersionStateResponse>assertDeniedMatrix(
        observer ->
            service.getVersionState(
                GetVersionStateRequest.newBuilder().setTenantId("1").setVersionId(7L).build(),
                observer),
        response -> response.getError().getCode(),
        "game-session-service",
        "world-management-service");
    verifyNoInteractions(versionService);
  }

  @Test
  void versionAssetArtifactStateReadDeniesEveryIdentityBeforeOwnerServiceAccess() {
    VersionAssetArtifactService artifactService = Mockito.mock(VersionAssetArtifactService.class);
    GameDesignGrpcService service = newService(Mockito.mock(VersionService.class), artifactService);
    ReflectionTestUtils.setField(service, "workloadNamespace", WORKLOAD_NAMESPACE);

    this.<GetVersionAssetArtifactStateResponse>assertDeniedMatrix(
        observer ->
            service.getVersionAssetArtifactState(
                GetVersionAssetArtifactStateRequest.newBuilder()
                    .setTenantId("1")
                    .setVersionId(7L)
                    .build(),
                observer),
        response -> response.getError().getCode(),
        "game-session-service",
        "world-management-service");
    verifyNoInteractions(artifactService);
  }

  private GameDesignGrpcService newService(
      VersionService versionService, VersionAssetArtifactService artifactService) {
    return new GameDesignGrpcService(
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
  }

  private <R> void assertDeniedMatrix(
      RpcCall<R> call, Function<R, String> errorCode, String... candidateServices) {
    for (String candidate : candidateServices) {
      assertDenied(call, errorCode, candidate, WORKLOAD_NAMESPACE, Identity.PEER);
      assertDenied(call, errorCode, candidate, "other-namespace", Identity.PEER);
    }
    assertDenied(call, errorCode, "logging-admin-service", WORKLOAD_NAMESPACE, Identity.PEER);
    assertDenied(call, errorCode, null, null, Identity.ADMIN);
    assertDenied(call, errorCode, null, null, Identity.OPERATOR);
    assertDenied(call, errorCode, null, null, Identity.BEARER_WITHOUT_PEER);
    assertDenied(call, errorCode, null, null, Identity.ANONYMOUS);
  }

  private <R> void assertDenied(
      RpcCall<R> call,
      Function<R, String> errorCode,
      String service,
      String namespace,
      Identity identity) {
    SessionContext.clear();
    switch (identity) {
      case ADMIN -> SessionContext.setContext("1", List.of("admin"), Map.of());
      case OPERATOR -> SessionContext.setContext("1", List.of("operator"), Map.of());
      case BEARER_WITHOUT_PEER ->
          SessionContext.setContext(
              null, List.of(), Map.of(), true, "game-session-service", "gs-bearer");
      case PEER, ANONYMOUS -> {}
    }

    AtomicReference<R> response = new AtomicReference<>();
    Runnable operation = () -> call.invoke(responseObserver(response));
    if (identity == Identity.PEER) {
      GrpcPeerIdentity peer =
          GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
              .orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(operation);
    } else {
      Context.ROOT.run(operation);
    }

    assertNotNull(response.get());
    assertEquals("PERMISSION_DENIED", errorCode.apply(response.get()));
  }

  private <R> StreamObserver<R> responseObserver(AtomicReference<R> response) {
    return new StreamObserver<>() {
      @Override
      public void onNext(R value) {
        response.set(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }

  @FunctionalInterface
  private interface RpcCall<R> {
    void invoke(StreamObserver<R> observer);
  }

  private enum Identity {
    PEER,
    ADMIN,
    OPERATOR,
    BEARER_WITHOUT_PEER,
    ANONYMOUS
  }
}
