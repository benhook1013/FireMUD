package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.ApproveTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.ApproveTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.CreateTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.CreateTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestRequest;
import net.firedevops.firemud.gamedesign.v1.GetDesignControlPlaneDigestResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionRequest;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionResponse;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsResponse;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class GameDesignGrpcServiceTest {
  private final PingService pingService = Mockito.mock(PingService.class);
  private final RevisionService revisionService = Mockito.mock(RevisionService.class);
  private final VersionService versionService = Mockito.mock(VersionService.class);
  private final LaunchDescriptorService launchDescriptorService =
      Mockito.mock(LaunchDescriptorService.class);
  private final TemplateRemapSetService templateRemapSetService =
      Mockito.mock(TemplateRemapSetService.class);
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
          templateRemapSetService,
          versionAssetArtifactService,
          settingsAuthorityService,
          new SimpleMeterRegistry());

  @Test
  void saveRevisionReturnsAppliedWorldMutation() {
    Mockito.when(revisionService.saveRevision(Mockito.any()))
        .thenReturn(
            new RevisionDto(
                21L,
                "tenant-1",
                7L,
                9L,
                "{\"kind\":\"world\"}",
                "WORLD_DESIGN_MUTATION",
                "rev-1",
                null,
                new AppliedWorldDesignMutationDto(
                    "WORLD_DESIGN_MUTATION_RESULT_APPLIED", "44", 2L, 5L),
                LocalDateTime.parse("2026-04-22T09:00:00")));

    AtomicReference<SaveRevisionResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.saveRevision(
          SaveRevisionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .setAuthorAccountId(9L)
              .setRevisionKind("WORLD_DESIGN_MUTATION")
              .setData("{\"kind\":\"world\"}")
              .setWorldDesignMutation(
                  net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision.newBuilder()
                      .setLogicalRevisionId("rev-1")
                      .setCommitId("commit-1")
                      .setOperation(
                          WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
                      .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                      .setAggregateId("44")
                      .setExpectedDraftRevisionEpoch(1L)
                      .setRegion(RegionDesignMutation.newBuilder().setName("Region A").build())
                      .build())
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(21L, ref.get().getRevisionId());
    assertEquals(
        WorldDesignMutationResult.WORLD_DESIGN_MUTATION_RESULT_APPLIED,
        ref.get().getAppliedWorldDesignMutation().getResult());
    assertEquals("44", ref.get().getAppliedWorldDesignMutation().getAggregateId());
  }

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
  void getPublishedReleaseBundleReturnsNotFoundWhenAttestationIsAbsent() {
    Mockito.when(versionService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenThrow(new PublishedReleaseBundleNotFoundException("tenant-1", 7L));

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getPublishedReleaseBundle(
          GetPublishedReleaseBundleRequest.newBuilder()
              .setTenantId("tenant-1")
              .setVersionId(7L)
              .build(),
          observerFor(ref));
    }

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void getPublishedReleaseBundleRejectsUnsupportedSchema() {
    Mockito.when(versionService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenReturn(
            new PublishedReleaseBundleDto(
                11L,
                "tenant-1",
                7L,
                8,
                "v999",
                "workflow-1",
                "abc123",
                List.of("manifest.json"),
                List.of(),
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

    assertEquals("SCHEMA_VERSION_UNSUPPORTED", ref.get().getError().getCode());
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
                "prb:tenant-1:7:11",
                ""));

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
  void resolveLaunchDescriptorSurfacesTypedReleaseBundleNotFoundError() {
    Mockito.when(
            launchDescriptorService.resolveLaunchDescriptor(
                "tenant-1", 9L, "cp-2", null, null, null, null))
        .thenThrow(
            new IllegalArgumentException(
                "RELEASE_BUNDLE_NOT_FOUND: no published release bundle for the resolved version"));

    AtomicReference<ResolveLaunchDescriptorResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.resolveLaunchDescriptor(
          ResolveLaunchDescriptorRequest.newBuilder()
              .setTenantId("tenant-1")
              .setGameTemplateId(9L)
              .setControlPlaneRequestId("cp-2")
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

    assertEquals("RELEASE_BUNDLE_NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void resolveLaunchDescriptorSurfacesTypedRemapRequiredError() {
    Mockito.when(
            launchDescriptorService.resolveLaunchDescriptor(
                "tenant-1", 9L, "cp-3", null, null, null, null))
        .thenThrow(
            new IllegalArgumentException(
                "LAUNCH_REMAP_REQUIRED: replacement-instance launch requires an approved remapSetId"));

    AtomicReference<ResolveLaunchDescriptorResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.resolveLaunchDescriptor(
          ResolveLaunchDescriptorRequest.newBuilder()
              .setTenantId("tenant-1")
              .setGameTemplateId(9L)
              .setControlPlaneRequestId("cp-3")
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

    assertEquals("LAUNCH_REMAP_REQUIRED", ref.get().getError().getCode());
  }

  @Test
  void createTemplateRemapSetReturnsCreatedSet() {
    Mockito.when(
            templateRemapSetService.createTemplateRemapSet(
                Mockito.eq("tenant-1"),
                Mockito.eq(7L),
                Mockito.eq(8L),
                Mockito.eq("cutover prep"),
                Mockito.anyList()))
        .thenReturn(sampleRemapSetDto(TemplateRemapSetStatus.DRAFT, null, null));

    AtomicReference<CreateTemplateRemapSetResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.createTemplateRemapSet(
          CreateTemplateRemapSetRequest.newBuilder()
              .setTenantId("tenant-1")
              .setSourceVersionId(7L)
              .setTargetVersionId(8L)
              .setCreatedReason("cutover prep")
              .addRemapEntries(
                  net.firedevops.firemud.gamedesign.v1.TemplateRemapEntry.newBuilder()
                      .setMappingDomain("ENTITY")
                      .setMappingType("CLASS_ASSIGNMENT")
                      .setSourceTemplateKey("class:warrior")
                      .setTargetTemplateKey("class:guardian")
                      .build())
              .build(),
          new GenericObserver<>(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals("remap-1", ref.get().getRemapSet().getRemapSetId());
    assertEquals(1, ref.get().getRemapSet().getRemapEntriesCount());
  }

  @Test
  void approveTemplateRemapSetReturnsApprovedSet() {
    Mockito.when(
            templateRemapSetService.approveTemplateRemapSet(
                "tenant-1", "remap-1", "validated for cutover"))
        .thenReturn(
            sampleRemapSetDto(
                TemplateRemapSetStatus.APPROVED,
                "validated for cutover",
                LocalDateTime.parse("2026-04-20T10:15:00")));

    AtomicReference<ApproveTemplateRemapSetResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.approveTemplateRemapSet(
          ApproveTemplateRemapSetRequest.newBuilder()
              .setTenantId("tenant-1")
              .setRemapSetId("remap-1")
              .setApprovalReason("validated for cutover")
              .build(),
          new GenericObserver<>(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(
        net.firedevops.firemud.gamedesign.v1.TemplateRemapSetStatus
            .TEMPLATE_REMAP_SET_STATUS_APPROVED,
        ref.get().getRemapSet().getStatus());
  }

  @Test
  void getTemplateRemapSetReturnsNotFound() {
    Mockito.when(templateRemapSetService.getTemplateRemapSet("tenant-1", "remap-missing"))
        .thenThrow(new IllegalArgumentException("NOT_FOUND: template remap set not found"));

    AtomicReference<GetTemplateRemapSetResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getTemplateRemapSet(
          GetTemplateRemapSetRequest.newBuilder()
              .setTenantId("tenant-1")
              .setRemapSetId("remap-missing")
              .build(),
          new GenericObserver<>(ref));
    }

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void getVersionStateReturnsCanonicalStateSnapshot() {
    Mockito.when(versionService.getVersionState("tenant-1", 7L))
        .thenReturn(
            new VersionStateDto(
                "tenant-1",
                7L,
                VersionLifecycleState.PUBLISHED,
                17L,
                LocalDateTime.parse("2026-04-15T12:00:00")));

    AtomicReference<GetVersionStateResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getVersionState(
          GetVersionStateRequest.newBuilder().setTenantId("tenant-1").setVersionId(7L).build(),
          new StreamObserver<>() {
            @Override
            public void onNext(GetVersionStateResponse value) {
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

    assertEquals("", ref.get().getError().getCode());
    assertEquals(17L, ref.get().getVersionState().getVersionStateEpoch());
    assertEquals(
        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
            .VERSION_LIFECYCLE_STATE_PUBLISHED,
        ref.get().getVersionState().getVersionState());
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
                8,
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
    assertEquals(8, ref.get().getArtifactState().getExportedVersionNumber());
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
                8,
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

  private static <T> StreamObserver<T> observerFor(AtomicReference<T> ref) {
    return new GenericObserver<>(ref);
  }

  private TemplateRemapSetDto sampleRemapSetDto(
      TemplateRemapSetStatus status, String approvalReason, LocalDateTime approvedAt) {
    return new TemplateRemapSetDto(
        "remap-1",
        "tenant-1",
        7L,
        8L,
        status,
        "cutover prep",
        approvalReason,
        LocalDateTime.parse("2026-04-20T10:00:00"),
        approvedAt,
        List.of(
            new TemplateRemapEntryDto(
                "ENTITY", "CLASS_ASSIGNMENT", "class:warrior", "class:guardian")));
  }

  private static final class GenericObserver<T> implements StreamObserver<T> {
    private final AtomicReference<T> ref;

    private GenericObserver(AtomicReference<T> ref) {
      this.ref = ref;
    }

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
  }
}
