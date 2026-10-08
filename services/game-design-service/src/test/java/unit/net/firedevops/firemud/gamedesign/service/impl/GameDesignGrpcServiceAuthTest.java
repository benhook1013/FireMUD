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
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
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
import net.firedevops.firemud.gamedesign.v1.ListVersionsRequest;
import net.firedevops.firemud.gamedesign.v1.ListVersionsResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
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
            Mockito.mock(net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService.class),
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
  void launchAttestationReadAndCanonicalResolveRequireWorkloadIdentity() {
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
    UUID canonicalTenantId = UUID.fromString("12345678-1234-4234-8234-123456789abc");
    UUID sourceOperationId = UUID.fromString("22345678-1234-4234-8234-123456789abc");
    AuthoredWorldLaunchDescriptorEvidence.Request launchRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "test",
            "cp-1",
            canonicalTenantId,
            "silver-march",
            sourceOperationId,
            "sha256:" + "a".repeat(64),
            9L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence evidence =
        AuthoredWorldLaunchDescriptorEvidence.create(
            launchRequest,
            "ld-1",
            7L,
            false,
            null,
            "{}",
            "genrev-1",
            11L,
            11L,
            "release-bundle:" + canonicalTenantId + ":7:11",
            false,
            null);
    Mockito.when(launchDescriptorService.resolveLaunchDescriptor(Mockito.any()))
        .thenReturn(
            new ResolvedLaunchDescriptorDto(
                evidence.launchDescriptorId(),
                evidence.canonicalTenantId().toString(),
                evidence.gameTemplateId(),
                evidence.controlPlaneRequestId(),
                evidence.versionId(),
                evidence.scriptPatchVersion(),
                evidence.runtimeFlagsJson(),
                evidence.generationConfigRevision(),
                evidence.versionStateEpoch(),
                evidence.releaseBundleId(),
                evidence.publishedReleaseBundleRef(),
                evidence.remapSetId(),
                evidence));
    GameDesignGrpcService service =
        new GameDesignGrpcService(
            Mockito.mock(PingService.class),
            Mockito.mock(RevisionService.class),
            versionService,
            launchDescriptorService,
            Mockito.mock(net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService.class),
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

    AtomicReference<ResolveLaunchDescriptorResponse> descriptorRef = new AtomicReference<>();
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
            .orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                service.resolveLaunchDescriptor(
                    ResolveLaunchDescriptorRequest.newBuilder()
                        .setCanonicalTenantId(canonicalTenantId.toString())
                        .setGameTemplateId(9L)
                        .setControlPlaneRequestId("cp-1")
                        .setWorldSlug("silver-march")
                        .setAuthoredWorldSourceOperationId(sourceOperationId.toString())
                        .setExpectedAuthoredWorldSourceEvidenceDigest("sha256:" + "a".repeat(64))
                        .build(),
                    new StreamObserver<>() {
                      @Override
                      public void onNext(ResolveLaunchDescriptorResponse value) {
                        descriptorRef.set(value);
                      }

                      @Override
                      public void onError(Throwable t) {
                        throw new AssertionError(t);
                      }

                      @Override
                      public void onCompleted() {}
                    }));

    assertNotNull(descriptorRef.get());
    assertEquals("", descriptorRef.get().getError().getCode());
    assertEquals("ld-1", descriptorRef.get().getLaunchDescriptor().getLaunchDescriptorId());
    Mockito.verify(launchDescriptorService).resolveLaunchDescriptor(launchRequest);
  }

  private void underGameSessionPeer(Runnable operation) {
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service").orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(operation);
  }
}
