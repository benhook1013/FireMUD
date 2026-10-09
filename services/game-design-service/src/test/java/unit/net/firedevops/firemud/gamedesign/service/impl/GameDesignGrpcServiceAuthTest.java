package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
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
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID VERSION_ID = UUID.fromString("82345678-1234-4234-8234-123456789abc");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("22345678-1234-4234-8234-123456789abc");
  private static final UUID READ_REQUEST_ID =
      UUID.fromString("32345678-1234-4234-8234-123456789abc");
  private static final String WORLD_SLUG = "copper-coast";
  private static final String CONTROL_PLANE_REQUEST_ID = "launch-operation-7";

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
                "ld-1",
                "12345678-1234-4234-8234-123456789abc",
                9L,
                "cp-1",
                7L,
                null,
                "{}",
                "genrev-1",
                11L,
                11L,
                "prb:1:7:11",
                null,
                null));

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

  @Test
  void completeLaunchBindingAllowsOnlySameNamespaceWorldManagementAndReturnsExactPair() {
    CompleteLaunchBindingService completeService = Mockito.mock(CompleteLaunchBindingService.class);
    CompleteLaunchBindingDto binding = completeBinding(CONTROL_PLANE_REQUEST_ID);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            sourceRequest(CONTROL_PLANE_REQUEST_ID),
            "launch-descriptor-7",
            7L,
            false,
            null,
            "{}",
            "generation-revision-1",
            3L,
            11L,
            "release-ref-11",
            false,
            null);
    when(completeService.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest()))
        .thenReturn(binding);
    GameDesignGrpcService grpcService = service(completeService);
    GetLaunchDescriptorRequest request = readRequest(descriptor);
    AtomicReference<GetCompleteLaunchBindingResponse> response = new AtomicReference<>();

    withPeer(
        "world-management-service",
        NAMESPACE,
        () -> grpcService.getCompleteLaunchBinding(request, observer(response)));

    assertNotNull(response.get());
    assertEquals(false, response.get().hasError());
    assertEquals(
        "launch-descriptor-7", response.get().getLaunchDescriptor().getLaunchDescriptorId());
    assertEquals(
        "release-ref-11", response.get().getReleaseAttestation().getPublishedReleaseBundleRef());
    verify(completeService)
        .getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            descriptor.requestDigest(),
            descriptor.resultDigest());
  }

  @Test
  void completeLaunchBindingDeniesBeforeParsingOrReadingForWrongOrMissingPeer() {
    CompleteLaunchBindingService completeService = Mockito.mock(CompleteLaunchBindingService.class);
    GameDesignGrpcService grpcService = service(completeService);
    GetLaunchDescriptorRequest malformed = GetLaunchDescriptorRequest.getDefaultInstance();

    AtomicReference<GetCompleteLaunchBindingResponse> wrongPeer = new AtomicReference<>();
    withPeer(
        "game-session-service",
        NAMESPACE,
        () -> grpcService.getCompleteLaunchBinding(malformed, observer(wrongPeer)));
    assertEquals("PERMISSION_DENIED", wrongPeer.get().getError().getCode());

    AtomicReference<GetCompleteLaunchBindingResponse> wrongNamespace = new AtomicReference<>();
    withPeer(
        "world-management-service",
        "another-namespace",
        () -> grpcService.getCompleteLaunchBinding(malformed, observer(wrongNamespace)));
    assertEquals("PERMISSION_DENIED", wrongNamespace.get().getError().getCode());

    AtomicReference<GetCompleteLaunchBindingResponse> noPeer = new AtomicReference<>();
    grpcService.getCompleteLaunchBinding(malformed, observer(noPeer));
    assertEquals("PERMISSION_DENIED", noPeer.get().getError().getCode());
    verifyNoInteractions(completeService);

    AtomicReference<GetCompleteLaunchBindingResponse> malformedRequest = new AtomicReference<>();
    withPeer(
        "world-management-service",
        NAMESPACE,
        () -> grpcService.getCompleteLaunchBinding(malformed, observer(malformedRequest)));
    assertEquals("INVALID_ARGUMENT", malformedRequest.get().getError().getCode());
    verifyNoInteractions(completeService);
  }

  @Test
  void completeLaunchBindingRejectsSubstitutedDescriptorEvidence() {
    CompleteLaunchBindingService completeService = Mockito.mock(CompleteLaunchBindingService.class);
    CompleteLaunchBindingDto substituted = completeBinding("different-launch-operation");
    AuthoredWorldLaunchDescriptorEvidence requestedDescriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            sourceRequest(CONTROL_PLANE_REQUEST_ID),
            "launch-descriptor-7",
            7L,
            false,
            null,
            "{}",
            "generation-revision-1",
            3L,
            11L,
            "release-ref-11",
            false,
            null);
    when(completeService.getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            requestedDescriptor.requestDigest(),
            requestedDescriptor.resultDigest()))
        .thenReturn(substituted);
    GameDesignGrpcService grpcService = service(completeService);
    AtomicReference<GetCompleteLaunchBindingResponse> response = new AtomicReference<>();

    withPeer(
        "world-management-service",
        NAMESPACE,
        () ->
            grpcService.getCompleteLaunchBinding(
                readRequest(requestedDescriptor), observer(response)));

    assertEquals("FAILED_PRECONDITION", response.get().getError().getCode());
    verify(completeService)
        .getCompleteLaunchBinding(
            READ_REQUEST_ID,
            TENANT_ID,
            WORLD_SLUG,
            CONTROL_PLANE_REQUEST_ID,
            requestedDescriptor.requestDigest(),
            requestedDescriptor.resultDigest());
  }

  private static GameDesignGrpcService service(CompleteLaunchBindingService completeService) {
    GameDesignGrpcService grpcService =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            Mockito.mock(VersionService.class),
            Mockito.mock(LaunchDescriptorService.class),
            completeService,
            Mockito.mock(TemplateRemapSetService.class),
            Mockito.mock(VersionAssetArtifactService.class),
            Mockito.mock(SettingsAuthorityService.class),
            Mockito.mock(GameAuthoredHelpTopicService.class),
            new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(grpcService, "workloadNamespace", NAMESPACE);
    return grpcService;
  }

  private static CompleteLaunchBindingDto completeBinding(String controlPlaneRequestId) {
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            sourceRequest(controlPlaneRequestId),
            "launch-descriptor-7",
            7L,
            false,
            null,
            "{}",
            "generation-revision-1",
            3L,
            11L,
            "release-ref-11",
            false,
            null);
    String commitId = "commit-7";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                participantKey ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        participantKey,
                        "7",
                        false,
                        null,
                        commitId,
                        "d".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            participantKey),
                        "GAME_LOGIC".equals(participantKey),
                        "GAME_LOGIC".equals(participantKey) ? "sha256:" + "e".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence releaseAttestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT_ID,
            VERSION_ID,
            WORLD_SLUG,
            SOURCE_OPERATION_ID,
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish-workflow-7",
            commitId,
            participants,
            "sha256:" + "c".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of(),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingDto(descriptor, releaseAttestation);
  }

  private static AuthoredWorldLaunchDescriptorEvidence.Request sourceRequest(
      String controlPlaneRequestId) {
    return new AuthoredWorldLaunchDescriptorEvidence.Request(
        NAMESPACE,
        controlPlaneRequestId,
        TENANT_ID,
        WORLD_SLUG,
        SOURCE_OPERATION_ID,
        "sha256:" + "a".repeat(64),
        19L,
        false,
        null,
        false,
        null,
        false,
        null,
        false,
        null);
  }

  private static GetLaunchDescriptorRequest readRequest(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return GetLaunchDescriptorRequest.newBuilder()
        .setRequestId(READ_REQUEST_ID.toString())
        .setCanonicalTenantId(TENANT_ID.toString())
        .setWorldSlug(WORLD_SLUG)
        .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
        .setExpectedRequestDigest(descriptor.requestDigest())
        .setExpectedResultDigest(descriptor.resultDigest())
        .build();
  }

  private static <T> StreamObserver<T> observer(AtomicReference<T> reference) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        reference.set(value);
      }

      @Override
      public void onError(Throwable throwable) {
        throw new AssertionError(throwable);
      }

      @Override
      public void onCompleted() {}
    };
  }

  private static void withPeer(String service, String namespace, Runnable action) {
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
            .orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(action);
  }
}
