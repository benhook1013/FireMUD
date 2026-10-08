package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminRoleGuard;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PluginVersionStatusEventDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedPluginVersionDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.PublishAttemptPendingReconciliationException;
import net.firedevops.firemud.gamedesign.service.PublishGateFailureException;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.ScriptPatchPublishFailureException;
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
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.GetPublishedScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetRequest;
import net.firedevops.firemud.gamedesign.v1.GetTemplateRemapSetResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusEventsRequest;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusEventsResponse;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusesRequest;
import net.firedevops.firemud.gamedesign.v1.ListPluginVersionStatusesResponse;
import net.firedevops.firemud.gamedesign.v1.PluginComponentPolicyDecision;
import net.firedevops.firemud.gamedesign.v1.PublishPluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishPluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishScriptPatchVersionResponse;
import net.firedevops.firemud.gamedesign.v1.PublishVersionRequest;
import net.firedevops.firemud.gamedesign.v1.PublishVersionResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse;
import net.firedevops.firemud.gamedesign.v1.RevokePluginVersionRequest;
import net.firedevops.firemud.gamedesign.v1.RevokePluginVersionResponse;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionRequest;
import net.firedevops.firemud.gamedesign.v1.SaveRevisionResponse;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsRequest;
import net.firedevops.firemud.gamedesign.v1.TombstoneVersionAssetsResponse;
import net.firedevops.firemud.gamedesign.v1.UploadPluginBundleRequest;
import net.firedevops.firemud.gamedesign.v1.UploadPluginBundleResponse;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationResult;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class GameDesignGrpcServiceTest {
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("12345678-1234-4234-8234-123456789abc");
  private static final UUID CANONICAL_VERSION_ID =
      UUID.fromString("82345678-1234-4234-8234-123456789abc");

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
  private final GameAuthoredHelpTopicService gameAuthoredHelpTopicService =
      Mockito.mock(GameAuthoredHelpTopicService.class);
  private final TemporalVersionPublishWorkflowMetadataResolver publishWorkflowMetadataResolver =
      new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty());
  private final GameDesignGrpcService service =
      new GameDesignGrpcService(
          pingService,
          revisionService,
          versionService,
          launchDescriptorService,
          templateRemapSetService,
          versionAssetArtifactService,
          settingsAuthorityService,
          gameAuthoredHelpTopicService,
          publishWorkflowMetadataResolver,
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
                      .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
                      .setScopeId("44")
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
                List.of("{\"commandId\":\"block\",\"schemaVersion\":1}"),
                "genrev-1",
                false,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00"),
                CANONICAL_TENANT_ID,
                UUID.fromString("82345678-1234-4234-8234-123456789abc"),
                "opaque-release-reference-from-owner",
                1,
                List.of(
                    new PublishedArtifactDigest(
                        "logo.png",
                        "branding-image",
                        "artifacts/sha256/" + "b".repeat(64),
                        "sha256:" + "b".repeat(64),
                        "image/png",
                        1))));

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    underLaunchPeer(
        "game-session-service",
        () ->
            service.getPublishedReleaseBundle(
                GetPublishedReleaseBundleRequest.newBuilder()
                    .setTenantId("tenant-1")
                    .setVersionId(7L)
                    .build(),
                observerFor(ref)));

    assertEquals("", ref.get().getError().getCode());
    assertEquals(11L, ref.get().getBundle().getId());
    assertEquals("abc123", ref.get().getBundle().getManifestHash());
    assertEquals("genrev-1", ref.get().getBundle().getGenerationConfigRevision());
    assertEquals(2, ref.get().getBundle().getRequiredManifestAssetKeysCount());
    assertEquals(
        "opaque-release-reference-from-owner",
        ref.get().getBundle().getPublishedReleaseBundleRef());
    assertEquals(CANONICAL_TENANT_ID.toString(), ref.get().getBundle().getCanonicalTenantId());
    assertEquals(
        "82345678-1234-4234-8234-123456789abc", ref.get().getBundle().getCanonicalVersionId());
    assertEquals(1, ref.get().getBundle().getManifestSchemaVersion());
    assertEquals(1, ref.get().getBundle().getArtifactDigestsCount());
    assertEquals("logo.png", ref.get().getBundle().getArtifactDigests(0).getUsageKey());
    assertEquals(
        List.of("{\"commandId\":\"block\",\"schemaVersion\":1}"),
        ref.get().getBundle().getCommandDefinitionsList());
    assertEquals(1, ref.get().getBundle().getParticipantDigestsCount());
    assertEquals("publish", ref.get().getBundle().getWorkflowFamily());
    assertEquals("TEMPORAL_DISABLED", ref.get().getBundle().getWorkflowStatus());
  }

  @Test
  void getPublishedScriptPatchVersionReturnsPublicationReadModel() {
    Mockito.when(versionService.getPublishedScriptPatchVersion("tenant-1", 7L, "patch-1"))
        .thenReturn(
            new VersionDto(
                9L,
                "tenant-1",
                3,
                VersionLifecycleState.PUBLISHED,
                2L,
                "patch-1",
                7L,
                true,
                "notes",
                LocalDateTime.parse("2026-04-14T11:00:00"),
                LocalDateTime.parse("2026-04-14T12:00:00")));
    Mockito.when(
            versionService.getDesignControlPlaneDigestForScriptPatch("tenant-1", 7L, "patch-1"))
        .thenReturn(
            new DesignControlPlaneDigestDto(
                "tenant-1", "patch-1", "script-patch:patch-1", "digest-1", 1));
    AtomicReference<GetPublishedScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getPublishedScriptPatchVersion(
          GetPublishedScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setScriptPatchVersion("patch-1")
              .setBaseVersionId(7L)
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals("patch-1", ref.get().getScriptPatch().getScriptPatchVersion());
    assertEquals(9L, ref.get().getScriptPatch().getVersionId());
    assertEquals(7L, ref.get().getScriptPatch().getBaseVersionId());
    assertEquals("digest-1", ref.get().getScriptPatch().getControlPlaneDigest());
  }

  @Test
  void getPublishedScriptPatchVersionRejectsMissingBaseScope() {
    AtomicReference<GetPublishedScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getPublishedScriptPatchVersion(
          GetPublishedScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setScriptPatchVersion("patch-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    Mockito.verify(versionService, Mockito.never())
        .getPublishedScriptPatchVersion(
            Mockito.anyString(), Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void publishVersionRequiresPublishRequestId() {
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(
          PublishVersionRequest.newBuilder().setTenantId("tenant-1").setNotes("notes").build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void publishVersionRejectsPartialSelectedSourceIdentity() {
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(
          PublishVersionRequest.newBuilder()
              .setTenantId(CANONICAL_TENANT_ID.toString())
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .setVersionId(CANONICAL_VERSION_ID.toString())
              .build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    Mockito.verifyNoInteractions(versionService);
  }

  @Test
  void publishVersionMapsMissingOwnerAuthorizationToExplicitDenial() throws Exception {
    Mockito.when(versionService.publishVersion(selectedIntent("publish-request-1")))
        .thenThrow(
            new IllegalStateException(
                "PUBLICATION_AUTHORIZATION_UNAVAILABLE: exact current authorization required"));
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("publish-request-1"), observerFor(ref));
    }

    assertEquals("PUBLICATION_AUTHORIZATION_UNAVAILABLE", ref.get().getError().getCode());
  }

  @Test
  void publishVersionLegacyShapeOnlyUsesRetainedTerminalReplay() throws Exception {
    Mockito.when(versionService.replayLegacyFullVersion("tenant-1", "notes", "legacy-1"))
        .thenReturn(
            new VersionDto(
                10L,
                "tenant-1",
                8,
                VersionLifecycleState.PUBLISHED,
                2L,
                null,
                null,
                false,
                "notes",
                LocalDateTime.now(),
                LocalDateTime.now()));
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(
          PublishVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setNotes("notes")
              .setPublishRequestId("legacy-1")
              .build(),
          observerFor(ref));
    }

    assertEquals(10L, ref.get().getVersionId());
    Mockito.verify(versionService).replayLegacyFullVersion("tenant-1", "notes", "legacy-1");
    Mockito.verify(versionService, Mockito.never())
        .publishVersion(Mockito.any(PublishIntent.class));
  }

  @Test
  void publishVersionMapsKnownPublishAttemptStateFailures() throws Exception {
    Mockito.when(versionService.publishVersion(selectedIntent("publish-request-1")))
        .thenThrow(
            new IllegalStateException(
                "PUBLISH_ATTEMPT_SCOPE_MISMATCH: referenced version evidence does not match"));
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("publish-request-1"), observerFor(ref));
    }

    assertEquals("PUBLISH_ATTEMPT_SCOPE_MISMATCH", ref.get().getError().getCode());
  }

  @Test
  void publishVersionMapsTemporalKnownGateFailureCode() throws Exception {
    PublishGateFailureException gateFailure =
        (PublishGateFailureException)
            TemporalVersionPublishOrchestrator.failureForSnapshot(
                new PublishWorkflowSnapshot(
                    0L,
                    0,
                    "publish:tenant-1:publish-request:publish-request-1",
                    "FAILED",
                    PublishGateFailureCode.PARTICIPANT_SET_MISMATCH.name(),
                    "participant set mismatch"));
    Mockito.when(versionService.publishVersion(selectedIntent("publish-request-1")))
        .thenThrow(gateFailure);
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("publish-request-1"), observerFor(ref));
    }

    assertEquals("PARTICIPANT_SET_MISMATCH", ref.get().getError().getCode());
  }

  @Test
  void publishVersionMapsKnownIllegalArgumentPublishAttemptCode() throws Exception {
    Mockito.when(versionService.publishVersion(selectedIntent("publish-request-1")))
        .thenThrow(
            new IllegalArgumentException(
                "PUBLISH_ATTEMPT_IDENTITY_CONFLICT: publish workflow does not match request"));
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("publish-request-1"), observerFor(ref));
    }

    assertEquals("PUBLISH_ATTEMPT_IDENTITY_CONFLICT", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionMapsKnownPublishAttemptStateFailures() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(new IllegalStateException("PUBLISH_ATTEMPT_INCONSISTENT: finalization pending"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("PUBLISH_ATTEMPT_INCONSISTENT", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionRejectsNonPositiveBaseVersionBeforeServiceCall() throws Exception {
    for (long baseVersionId : List.of(0L, -1L)) {
      AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

      try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
        service.publishScriptPatchVersion(
            PublishScriptPatchVersionRequest.newBuilder()
                .setTenantId("tenant-1")
                .setBaseVersionId(baseVersionId)
                .setScriptPatchVersion("patch-1")
                .setNotes("notes")
                .setPublishRequestId("publish-request-1")
                .build(),
            observerFor(ref));
      }

      assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
      Mockito.verifyNoInteractions(versionService);
    }
  }

  @Test
  void publishScriptPatchVersionMapsPendingReconciliationToStableCode() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(
            new VersionPublishCommandServiceImpl.PendingReconciliationException(
                "release evidence needs readback"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("PUBLISH_ATTEMPT_PENDING_RECONCILIATION_REQUIRED", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionMapsScriptPatchIdentityConflict() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(
            new IllegalArgumentException(
                "PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT: artifact belongs to another request"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionMapsUnknownIllegalArgumentToInvalidArgument() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(new IllegalArgumentException("invalid patch request"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void publishVersionKeepsUnknownIllegalStateFailuresInternal() throws Exception {
    Mockito.when(versionService.publishVersion(selectedIntent("publish-request-1")))
        .thenThrow(new IllegalStateException("unexpected internal state"));
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("publish-request-1"), observerFor(ref));
    }

    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionKeepsUnknownIllegalStateFailuresInternal() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(new IllegalStateException("unexpected internal state"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void publishScriptPatchVersionMapsReplayedFailureCodeAndMessage() throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(
            new ScriptPatchPublishFailureException(
                "LEGACY_REQUEST_IDENTITY_UNAVAILABLE", "stored identity cannot be replayed"));
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("LEGACY_REQUEST_IDENTITY_UNAVAILABLE", ref.get().getError().getCode());
    assertEquals("stored identity cannot be replayed", ref.get().getError().getMessage());
  }

  @Test
  void publishVersionMapsPendingReconciliationToStableApplicationError() throws Exception {
    Mockito.when(versionService.publishVersion(selectedIntent("request-1")))
        .thenThrow(new PublishAttemptPendingReconciliationException());
    AtomicReference<PublishVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishVersion(selectedPublishRequest("request-1"), observerFor(ref));
    }

    assertEquals(
        PublishAttemptPendingReconciliationException.ERROR_CODE, ref.get().getError().getCode());
    assertEquals(
        PublishAttemptPendingReconciliationException.SAFE_MESSAGE,
        ref.get().getError().getMessage());
  }

  @Test
  void publishScriptPatchVersionMapsPendingReconciliationToStableApplicationError()
      throws Exception {
    Mockito.when(
            versionService.publishScriptPatchVersion(
                "tenant-1", 7L, "patch-1", "notes", "publish-request-1"))
        .thenThrow(new PublishAttemptPendingReconciliationException());
    AtomicReference<PublishScriptPatchVersionResponse> ref = new AtomicReference<>();

    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishScriptPatchVersion(
          PublishScriptPatchVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .setNotes("notes")
              .setPublishRequestId("publish-request-1")
              .build(),
          observerFor(ref));
    }

    assertEquals(
        PublishAttemptPendingReconciliationException.ERROR_CODE, ref.get().getError().getCode());
    assertEquals(
        PublishAttemptPendingReconciliationException.SAFE_MESSAGE,
        ref.get().getError().getMessage());
  }

  @Test
  void uploadPluginBundleReturnsPublicationId() {
    Mockito.when(
            versionService.uploadPluginBundle(
                Mockito.eq("tenant-1"), Mockito.any(byte[].class), Mockito.eq("notes")))
        .thenReturn(
            new PublishedPluginVersionDto(
                14L,
                "tenant-1",
                "plugin-1",
                "plugin-v1",
                7L,
                VersionLifecycleState.SIGNATURE_VERIFIED,
                "ability-1",
                "bundle-1",
                1,
                "",
                "",
                "signer-1",
                false,
                "UNSPECIFIED",
                "notes",
                "",
                LocalDateTime.parse("2026-04-22T12:00:00")));

    AtomicReference<UploadPluginBundleResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.uploadPluginBundle(
          UploadPluginBundleRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBundleBytes(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3}))
              .setNotes("notes")
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(14L, ref.get().getPublicationId());
  }

  @Test
  void publishPluginVersionReturnsPublicationId() {
    Mockito.when(
            versionService.publishPluginVersion(
                "tenant-1",
                "plugin-1",
                "plugin-v1",
                7L,
                "ability-1",
                "bundle-1",
                1,
                "dist-hash",
                "dist-path",
                "signer-1",
                false,
                "ALLOWED",
                "notes"))
        .thenReturn(
            new PublishedPluginVersionDto(
                15L,
                "tenant-1",
                "plugin-1",
                "plugin-v1",
                7L,
                VersionLifecycleState.PUBLISHED,
                "ability-1",
                "bundle-1",
                1,
                "dist-hash",
                "dist-path",
                "signer-1",
                false,
                "ALLOWED",
                "notes",
                "",
                LocalDateTime.parse("2026-04-22T12:00:00")));

    AtomicReference<PublishPluginVersionResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishPluginVersion(
          PublishPluginVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPluginVersionId("plugin-v1")
              .setBaseVersionId(7L)
              .setAbilitySchemaDigest("ability-1")
              .setBundleDigest("bundle-1")
              .setManifestSchemaVersion(1)
              .setDistributionManifestHash("dist-hash")
              .setDistributionManifestPath("dist-path")
              .setSignerKeyId("signer-1")
              .setComponentPolicyDecision(
                  PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
              .setNotes("notes")
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(15L, ref.get().getPublicationId());
  }

  @Test
  void publishPluginVersionMapsImmutableTerminalConflictToTypedError() {
    Mockito.doThrow(
            new IllegalArgumentException(
                "PLUGIN_VERSION_IMMUTABLE: terminal plugin version cannot be republished; create a new plugin version"))
        .when(versionService)
        .publishPluginVersion(
            "tenant-1",
            "plugin-1",
            "plugin-v1",
            7L,
            "ability-1",
            "bundle-1",
            1,
            "dist-hash",
            "dist-path",
            "signer-1",
            false,
            "ALLOWED",
            "notes");

    AtomicReference<PublishPluginVersionResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.publishPluginVersion(
          PublishPluginVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPluginVersionId("plugin-v1")
              .setBaseVersionId(7L)
              .setAbilitySchemaDigest("ability-1")
              .setBundleDigest("bundle-1")
              .setManifestSchemaVersion(1)
              .setDistributionManifestHash("dist-hash")
              .setDistributionManifestPath("dist-path")
              .setSignerKeyId("signer-1")
              .setComponentPolicyDecision(
                  PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED)
              .setNotes("notes")
              .build(),
          observerFor(ref));
    }

    assertEquals("PLUGIN_VERSION_IMMUTABLE", ref.get().getError().getCode());
  }

  @Test
  void getPublishedPluginVersionReturnsPublicationReadModel() {
    Mockito.when(versionService.getPublishedPluginVersion("tenant-1", "plugin-1", "plugin-v1"))
        .thenReturn(
            new PublishedPluginVersionDto(
                15L,
                "tenant-1",
                "plugin-1",
                "plugin-v1",
                7L,
                VersionLifecycleState.PUBLISHED,
                "ability-1",
                "bundle-1",
                1,
                "dist-hash",
                "dist-path",
                "signer-1",
                false,
                "ALLOWED",
                "notes",
                "",
                LocalDateTime.parse("2026-04-22T12:00:00")));

    AtomicReference<GetPublishedPluginVersionResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getPublishedPluginVersion(
          GetPublishedPluginVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPluginVersionId("plugin-v1")
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals("plugin-1", ref.get().getPluginVersion().getPluginId());
    assertEquals("plugin-v1", ref.get().getPluginVersion().getPluginVersionId());
    assertEquals(7L, ref.get().getPluginVersion().getBaseVersionId());
    assertEquals("ability-1", ref.get().getPluginVersion().getAbilitySchemaDigest());
    assertEquals("signer-1", ref.get().getPluginVersion().getSignerKeyId());
    assertEquals(
        PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_ALLOWED,
        ref.get().getPluginVersion().getComponentPolicyDecision());
  }

  @Test
  void getPublishedPluginVersionReturnsHistoricalTerminalStates() {
    for (VersionLifecycleState state :
        List.of(VersionLifecycleState.SUPERSEDED, VersionLifecycleState.REVOKED_DESIGN)) {
      Mockito.when(versionService.getPublishedPluginVersion("tenant-1", "plugin-1", "plugin-v1"))
          .thenReturn(
              new PublishedPluginVersionDto(
                  15L,
                  "tenant-1",
                  "plugin-1",
                  "plugin-v1",
                  7L,
                  state,
                  "ability-1",
                  "bundle-1",
                  1,
                  "dist-hash",
                  "dist-path",
                  "signer-1",
                  false,
                  "ALLOWED",
                  "notes",
                  "reason",
                  LocalDateTime.parse("2026-04-22T12:00:00")));

      AtomicReference<GetPublishedPluginVersionResponse> ref = new AtomicReference<>();
      try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
        service.getPublishedPluginVersion(
            GetPublishedPluginVersionRequest.newBuilder()
                .setTenantId("tenant-1")
                .setPluginId("plugin-1")
                .setPluginVersionId("plugin-v1")
                .build(),
            observerFor(ref));
      }

      assertEquals("", ref.get().getError().getCode());
      assertEquals(
          state == VersionLifecycleState.SUPERSEDED
              ? net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_SUPERSEDED
              : net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                  .VERSION_LIFECYCLE_STATE_REVOKED_DESIGN,
          ref.get().getPluginVersion().getPublicationState());
    }
  }

  @Test
  void revokePluginVersionReturnsPublicationId() {
    Mockito.when(versionService.revokePluginVersion("tenant-1", "plugin-1", "plugin-v1", "reason"))
        .thenReturn(
            new PublishedPluginVersionDto(
                15L,
                "tenant-1",
                "plugin-1",
                "plugin-v1",
                7L,
                VersionLifecycleState.REVOKED_DESIGN,
                "ability-1",
                "bundle-1",
                1,
                "dist-hash",
                "dist-path",
                "signer-1",
                false,
                "ALLOWED",
                "notes",
                "reason",
                LocalDateTime.parse("2026-04-22T12:00:00")));

    AtomicReference<RevokePluginVersionResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.revokePluginVersion(
          RevokePluginVersionRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPluginVersionId("plugin-v1")
              .setReason("reason")
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(15L, ref.get().getPublicationId());
  }

  @Test
  void listPluginVersionStatusesReturnsFilteredPublicationRows() {
    Mockito.when(
            versionService.listPublishedPluginVersions(
                Mockito.eq("tenant-1"),
                Mockito.eq("plugin-1"),
                Mockito.eq(VersionLifecycleState.PUBLISHED),
                Mockito.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
                Mockito.eq(LocalDateTime.parse("2026-04-22T10:00:00")),
                Mockito.eq(25)))
        .thenReturn(
            List.of(
                new PublishedPluginVersionDto(
                    15L,
                    "tenant-1",
                    "plugin-1",
                    "plugin-v2",
                    7L,
                    VersionLifecycleState.PUBLISHED,
                    "ability-2",
                    "bundle-2",
                    1,
                    "dist-hash-2",
                    "dist-path-2",
                    "signer-2",
                    false,
                    "REPORT_ONLY",
                    "notes",
                    "",
                    LocalDateTime.parse("2026-04-22T09:00:00")),
                new PublishedPluginVersionDto(
                    14L,
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    7L,
                    VersionLifecycleState.PUBLISHED,
                    "ability-1",
                    "bundle-1",
                    1,
                    "",
                    "",
                    "signer-1",
                    true,
                    "ALLOWED",
                    "",
                    "signer_revoked",
                    LocalDateTime.parse("2026-04-21T09:00:00"))));

    AtomicReference<ListPluginVersionStatusesResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.listPluginVersionStatuses(
          ListPluginVersionStatusesRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPublicationState(
                  net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                      .VERSION_LIFECYCLE_STATE_PUBLISHED)
              .setChangedAfterMs(
                  LocalDateTime.parse("2026-04-20T10:00:00")
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toEpochMilli())
              .setChangedBeforeMs(
                  LocalDateTime.parse("2026-04-22T10:00:00")
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toEpochMilli())
              .setLimit(25)
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(2, ref.get().getPluginVersionsCount());
    assertEquals("plugin-v2", ref.get().getPluginVersions(0).getPluginVersionId());
    assertEquals(
        PluginComponentPolicyDecision.PLUGIN_COMPONENT_POLICY_DECISION_REPORT_ONLY,
        ref.get().getPluginVersions(0).getComponentPolicyDecision());
    assertEquals("plugin-v1", ref.get().getPluginVersions(1).getPluginVersionId());
    assertEquals(true, ref.get().getPluginVersions(1).getSignerRevoked());
  }

  @Test
  void listPluginVersionStatusEventsReturnsFilteredEventRows() {
    Mockito.when(
            versionService.listPluginVersionStatusEvents(
                Mockito.eq("tenant-1"),
                Mockito.eq("plugin-1"),
                Mockito.eq("plugin-v1"),
                Mockito.eq(VersionLifecycleState.PUBLISHED),
                Mockito.eq(LocalDateTime.parse("2026-04-20T10:00:00")),
                Mockito.eq(LocalDateTime.parse("2026-04-22T10:00:00")),
                Mockito.eq(25)))
        .thenReturn(
            List.of(
                new PluginVersionStatusEventDto(
                    "ppse-1",
                    "tenant-1",
                    "plugin-1",
                    "plugin-v1",
                    VersionLifecycleState.SIGNATURE_VERIFIED,
                    VersionLifecycleState.PUBLISHED,
                    "published",
                    java.time.Instant.parse("2026-04-22T09:00:00Z"))));

    AtomicReference<ListPluginVersionStatusEventsResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.listPluginVersionStatusEvents(
          ListPluginVersionStatusEventsRequest.newBuilder()
              .setTenantId("tenant-1")
              .setPluginId("plugin-1")
              .setPluginVersionId("plugin-v1")
              .setPublicationState(
                  net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                      .VERSION_LIFECYCLE_STATE_PUBLISHED)
              .setChangedAfterMs(
                  LocalDateTime.parse("2026-04-20T10:00:00")
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toEpochMilli())
              .setChangedBeforeMs(
                  LocalDateTime.parse("2026-04-22T10:00:00")
                      .toInstant(java.time.ZoneOffset.UTC)
                      .toEpochMilli())
              .setLimit(25)
              .build(),
          observerFor(ref));
    }

    assertEquals("", ref.get().getError().getCode());
    assertEquals(1, ref.get().getEventsCount());
    assertEquals("ppse-1", ref.get().getEvents(0).getEventId());
    assertEquals(
        net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
            .VERSION_LIFECYCLE_STATE_PUBLISHED,
        ref.get().getEvents(0).getNewPublicationState());
  }

  @Test
  void getPublishedReleaseBundleReturnsNotFoundWhenAttestationIsAbsent() {
    Mockito.when(versionService.getPublishedReleaseBundle("tenant-1", 7L))
        .thenThrow(new PublishedReleaseBundleNotFoundException("tenant-1", 7L));

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      underLaunchPeer(
          "game-session-service",
          () ->
              service.getPublishedReleaseBundle(
                  GetPublishedReleaseBundleRequest.newBuilder()
                      .setTenantId("tenant-1")
                      .setVersionId(7L)
                      .build(),
                  observerFor(ref)));
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
                "sha256:" + "a".repeat(64),
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00"),
                CANONICAL_TENANT_ID,
                UUID.fromString("82345678-1234-4234-8234-123456789abc"),
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

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      underLaunchPeer(
          "game-session-service",
          () ->
              service.getPublishedReleaseBundle(
                  GetPublishedReleaseBundleRequest.newBuilder()
                      .setTenantId("tenant-1")
                      .setVersionId(7L)
                      .build(),
                  observerFor(ref)));
    }

    assertEquals("SCHEMA_VERSION_UNSUPPORTED", ref.get().getError().getCode());
  }

  @Test
  void getPublishedReleaseBundleRejectsIncompleteCanonicalIdentityPair() {
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
                List.of("manifest.json"),
                List.of(),
                "genrev-1",
                false,
                null,
                LocalDateTime.parse("2026-04-14T12:00:00"),
                CANONICAL_TENANT_ID,
                null,
                "opaque-release-reference-from-owner",
                null,
                null));

    AtomicReference<GetPublishedReleaseBundleResponse> ref = new AtomicReference<>();
    underLaunchPeer(
        "game-session-service",
        () ->
            service.getPublishedReleaseBundle(
                GetPublishedReleaseBundleRequest.newBuilder()
                    .setTenantId("tenant-1")
                    .setVersionId(7L)
                    .build(),
                observerFor(ref)));

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
  }

  @Test
  void resolveLaunchDescriptorFailsClosedBeforeOwnerRead() {
    AtomicReference<ResolveLaunchDescriptorResponse> ref = new AtomicReference<>();
    service.resolveLaunchDescriptor(
        ResolveLaunchDescriptorRequest.newBuilder()
            .setTenantId("tenant-1")
            .setGameTemplateId(9L)
            .setControlPlaneRequestId("cp-legacy")
            .build(),
        observerFor(ref));

    assertEquals("AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED", ref.get().getError().getCode());
    Mockito.verifyNoInteractions(launchDescriptorService);
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
  void getDesignControlPlaneDigestRejectsScriptPatchWithoutBaseScope() {
    AtomicReference<GetDesignControlPlaneDigestResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getDesignControlPlaneDigest(
          GetDesignControlPlaneDigestRequest.newBuilder()
              .setTenantId("tenant-1")
              .setScriptPatchVersion("patch-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    Mockito.verify(versionService, Mockito.never())
        .getDesignControlPlaneDigestForScriptPatch(
            Mockito.anyString(), Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void getDesignControlPlaneDigestReturnsNotFoundForMissingScriptPatchScope() {
    Mockito.when(
            versionService.getDesignControlPlaneDigestForScriptPatch("tenant-1", 7L, "patch-1"))
        .thenThrow(new IllegalArgumentException("script patch version scope not found"));

    AtomicReference<GetDesignControlPlaneDigestResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getDesignControlPlaneDigest(
          GetDesignControlPlaneDigestRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
  }

  @Test
  void getDesignControlPlaneDigestKeepsAmbiguousScriptPatchScopeInvalidArgument() {
    Mockito.when(
            versionService.getDesignControlPlaneDigestForScriptPatch("tenant-1", 7L, "patch-1"))
        .thenThrow(new IllegalArgumentException("script patch version scope is ambiguous"));

    AtomicReference<GetDesignControlPlaneDigestResponse> ref = new AtomicReference<>();
    try (MockedStatic<AdminRoleGuard> ignored = Mockito.mockStatic(AdminRoleGuard.class)) {
      service.getDesignControlPlaneDigest(
          GetDesignControlPlaneDigestRequest.newBuilder()
              .setTenantId("tenant-1")
              .setBaseVersionId(7L)
              .setScriptPatchVersion("patch-1")
              .build(),
          observerFor(ref));
    }

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
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

  private void underLaunchPeer(String serviceName, Runnable operation) {
    ReflectionTestUtils.setField(service, "workloadNamespace", "test");
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/" + serviceName).orElseThrow();
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(operation);
  }

  private static PublishIntent selectedIntent(String requestId) {
    return new PublishIntent(
        CANONICAL_TENANT_ID,
        CANONICAL_VERSION_ID,
        requestId,
        "1",
        "notes",
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        "sha256:" + "a".repeat(64));
  }

  private static PublishVersionRequest selectedPublishRequest(String requestId) {
    return PublishVersionRequest.newBuilder()
        .setTenantId(CANONICAL_TENANT_ID.toString())
        .setVersionId(CANONICAL_VERSION_ID.toString())
        .setExpectedVersionStateEpoch("1")
        .setSelectedCommitRequestId("33333333-3333-4333-8333-333333333333")
        .setSelectedCommitId("44444444-4444-4444-8444-444444444444")
        .setSelectedCommitDigest("sha256:" + "a".repeat(64))
        .setNotes("notes")
        .setPublishRequestId(requestId)
        .build();
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
