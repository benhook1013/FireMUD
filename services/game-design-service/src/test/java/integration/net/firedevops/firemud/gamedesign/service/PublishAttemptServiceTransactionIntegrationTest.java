package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflow;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for private publication mechanics, retention, and public-ingress denial. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class PublishAttemptServiceTransactionIntegrationTest {
  private static final String TENANT_ID = "9001";
  private static final String WORKFLOW_ID = "full-version-transaction-integration-test";
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String ARTIFACT_DIGEST =
      "sha256:6eb74f970ab34ad786c5089af8780d7a218f91f9d6a7ed03675185e39b317e94";
  private static final byte[] FIXTURE_ASSET_BYTES =
      "publication fixture asset".getBytes(StandardCharsets.UTF_8);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private GameRepository gameRepository;
  @Autowired private GameAssetRepository gameAssetRepository;
  @Autowired private PublishAttemptServiceImpl publishAttemptService;
  @Autowired private VersionPublishCommandServiceImpl versionPublishCommandService;
  @Autowired private PublishAttemptRepository publishAttemptRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private VersionAssetArtifactRepository versionAssetArtifactRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private VersionTemplateRemapSetRepository templateRemapSetRepository;
  @Autowired private VersionAssetPublicationService versionAssetPublicationService;
  @Autowired private VersionAssetExportCandidateService exportCandidateService;
  @MockitoBean private AssetExportService assetExportService;
  @MockitoBean private PublishGateService publishGateService;
  @MockitoSpyBean private RecordedParticipantDigestService recordedParticipantDigestService;
  @MockitoSpyBean private VersionAssetArtifactService versionAssetArtifactService;

  @Test
  void fullVersionTransactionRollsBackVersionBundleArtifactAndAttemptTogether() {
    Game game = new Game();
    game.setTenantId(TENANT_ID);
    game.setName("transaction-proof-game");
    gameRepository.save(game);

    AtomicReference<Version> savedVersion = new AtomicReference<>();
    assertThatThrownBy(
            () ->
                publishAttemptService.executeFullVersionTransaction(
                    () -> {
                      Version version = new Version();
                      version.setTenantId(TENANT_ID);
                      version.setVersionNumber(1);
                      version.setVersionState(VersionLifecycleState.PUBLISHED);
                      version.setVersionStateEpoch(2L);
                      version.setNotes("transaction proof");
                      Version persistedVersion = versionRepository.save(version);
                      savedVersion.set(persistedVersion);

                      PublishAttempt attempt = new PublishAttempt();
                      attempt.setTenantId(TENANT_ID);
                      attempt.setPublishWorkflowId(WORKFLOW_ID);
                      attempt.setPublishType(PublishType.FULL_VERSION);
                      attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
                      attempt.setVersionId(persistedVersion.getId());
                      attempt.setVersionNumber(persistedVersion.getVersionNumber());
                      publishAttemptRepository.save(attempt);

                      PublishedReleaseBundle bundle = new PublishedReleaseBundle();
                      bundle.setTenantId(TENANT_ID);
                      bundle.setVersionId(persistedVersion.getId());
                      bundle.setVersionNumber(persistedVersion.getVersionNumber());
                      bundle.setAttestationSchemaVersion("v1");
                      bundle.setPublishWorkflowId(WORKFLOW_ID);
                      bundle.setManifestHash("transaction-proof-manifest");
                      bundle.setGenerationConfigRevision("transaction-proof-generation");
                      bundle.setRequiredManifestAssetKeysJson("[]");
                      bundle.setParticipantDigestsJson("[]");
                      bundle.setCommandDefinitionsJson("[]");
                      publishedReleaseBundleRepository.save(bundle);

                      VersionAssetArtifact artifact = new VersionAssetArtifact();
                      artifact.setTenantId(TENANT_ID);
                      artifact.setVersionId(persistedVersion.getId());
                      artifact.setExportedVersionNumber(persistedVersion.getVersionNumber());
                      artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
                      artifact.setStateEpoch(2L);
                      artifact.setManifestHash(bundle.getManifestHash());
                      artifact.setLastWorkflowId(WORKFLOW_ID);
                      artifact.setExportedManifestAssetKeysJson("[]");
                      versionAssetArtifactRepository.save(artifact);

                      throw new IllegalStateException("forced finalization failure");
                    }))
        .isInstanceOf(PublishAttemptService.FullVersionTransactionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);

    long versionId = savedVersion.get().getId();
    assertThat(versionRepository.findByTenantIdAndId(TENANT_ID, versionId)).isEmpty();
    assertThat(publishAttemptRepository.findByPublishWorkflowId(WORKFLOW_ID)).isEmpty();
    assertThat(publishedReleaseBundleRepository.findByTenantIdAndVersionId(TENANT_ID, versionId))
        .isEmpty();
    assertThat(versionAssetArtifactRepository.findByTenantIdAndVersionId(TENANT_ID, versionId))
        .isEmpty();
    assertThat(gameRepository.findByTenantId(TENANT_ID)).isNotNull();
  }

  @Test
  void fullVersionRequestDigestBackfillReturnsEmptyForMismatchedAttemptIdentity() {
    String tenantId = "9004";
    String publishWorkflowId = "full-version-digest-backfill-integration-test";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("digest-backfill-proof-game");
    gameRepository.save(game);

    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("digest backfill proof");
    Version savedVersion = versionRepository.save(version);

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(tenantId);
    attempt.setPublishWorkflowId(publishWorkflowId);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setStatus(PublishAttemptStatus.PENDING);
    attempt.setVersionId(savedVersion.getId());
    attempt.setVersionNumber(savedVersion.getVersionNumber());
    publishAttemptRepository.save(attempt);
    PublishAttempt savedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();

    PublishAttempt backfilled =
        publishAttemptRepository
            .backfillFullVersionRequestDigestIfAbsent(
                savedAttempt.getId(),
                tenantId,
                publishWorkflowId,
                savedVersion.getId(),
                savedVersion.getVersionNumber(),
                "exact-identity-digest")
            .orElseThrow();

    assertThat(backfilled.getRequestDigest()).isEqualTo("exact-identity-digest");
    assertThat(
            publishAttemptRepository.backfillFullVersionRequestDigestIfAbsent(
                savedAttempt.getId(),
                "wrong-tenant",
                publishWorkflowId,
                savedVersion.getId(),
                savedVersion.getVersionNumber(),
                "mismatched-identity-digest"))
        .isEmpty();
    PublishAttempt storedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    assertThat(storedAttempt.getRequestDigest()).isEqualTo("exact-identity-digest");
  }

  @Test
  void privateMechanicsHarnessCommitsAttemptVersionAndReleaseBundleTogether() {
    String tenantId = "9002";
    String publishRequestId = "successful-reconcile-request";
    String publishWorkflowId =
        FiremudWorkflowIds.workflowId(
            TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
            tenantId,
            "publish-request",
            publishRequestId);
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("successful-transaction-proof-game");
    gameRepository.save(game);

    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(publishWorkflowId)))
        .thenAnswer(
            invocation -> {
              VersionDto version = invocation.getArgument(0);
              return List.of(
                  new PublishParticipantDigestDto(
                      "GAME_DESIGN_CONTROL_PLANE",
                      String.valueOf(version.id()),
                      "version:" + version.id(),
                      "transaction-proof-design-digest",
                      1,
                      null,
                      null));
            });
    Mockito.when(assetExportService.exportAssets(tenantId, 1))
        .thenAnswer(invocation -> recordFixtureCandidate(tenantId, invocation.getArgument(1)));

    Object snapshot =
        reconcileFullVersionPublishMechanics(
            tenantId, "successful transaction proof", publishRequestId, publishWorkflowId);
    assertMechanicsSnapshot(snapshot, "SUCCEEDED", "");

    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    Version storedVersion =
        versionRepository.findByTenantIdAndId(tenantId, attempt.getVersionId()).orElseThrow();
    PublishedReleaseBundle bundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, attempt.getVersionId())
            .orElseThrow();
    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, attempt.getVersionId())
            .orElseThrow();

    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.SUCCEEDED);
    assertThat(attempt.getVersionId()).isEqualTo(storedVersion.getId());
    assertThat(storedVersion.getVersionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
    assertThat(bundle.getPublishWorkflowId()).isEqualTo(publishWorkflowId);
    assertThat(bundle.getManifestHash()).isEqualTo(MANIFEST_HASH);
    assertThat(artifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);
  }

  @Test
  void privateMechanicsHarnessRetainsCandidateAndRemapAfterFinalizationFailure() {
    String tenantId = "9003";
    String publishRequestId = "failed-remap-request";
    String publishWorkflowId =
        FiremudWorkflowIds.workflowId(
            TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
            tenantId,
            "publish-request",
            publishRequestId);
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("failed-remap-proof-game");
    gameRepository.save(game);

    Version sourceVersion = new Version();
    sourceVersion.setTenantId(tenantId);
    sourceVersion.setVersionNumber(1);
    sourceVersion.setVersionState(VersionLifecycleState.PUBLISHED);
    sourceVersion.setVersionStateEpoch(2L);
    sourceVersion.setNotes("remap source");
    sourceVersion = versionRepository.save(sourceVersion);
    long sourceVersionId = sourceVersion.getId();
    AtomicReference<Long> candidateVersionId = new AtomicReference<>();
    AtomicReference<Integer> candidateVersionNumber = new AtomicReference<>();
    AtomicReference<Integer> exportedVersionNumber = new AtomicReference<>();
    AtomicReference<String> remapSetId = new AtomicReference<>();
    AtomicReference<Throwable> recordedDigestFailure = new AtomicReference<>();
    AtomicReference<Throwable> exportCallbackFailure = new AtomicReference<>();
    AtomicBoolean exportCompleted = new AtomicBoolean();
    AtomicBoolean finalizationFailureInjected = new AtomicBoolean();

    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(publishWorkflowId)))
        .thenAnswer(
            invocation -> {
              VersionDto candidate = invocation.getArgument(0);
              candidateVersionId.set(candidate.id());
              candidateVersionNumber.set(candidate.versionNumber());
              return List.of(
                  new PublishParticipantDigestDto(
                      "GAME_DESIGN_CONTROL_PLANE",
                      String.valueOf(candidate.id()),
                      "version:" + candidate.id(),
                      "failed-remap-design-digest",
                      1,
                      null,
                      null));
            });
    Mockito.doAnswer(
            invocation -> {
              try {
                invocation.callRealMethod();
              } catch (Throwable failure) {
                recordedDigestFailure.set(failure);
                throw failure;
              }
              return null;
            })
        .when(recordedParticipantDigestService)
        .assertMatchesRecordedDigests(
            Mockito.eq(tenantId), Mockito.eq(PublishType.FULL_VERSION), Mockito.anyList());
    ExportedAssetManifest exportedManifest = exportedManifest();
    Mockito.when(assetExportService.exportAssets(Mockito.eq(tenantId), Mockito.anyInt()))
        .thenAnswer(
            invocation -> {
              exportedVersionNumber.set(invocation.getArgument(1));
              try {
                VersionTemplateRemapSet approvedRemapSet = new VersionTemplateRemapSet();
                approvedRemapSet.setRemapSetId("failed-candidate-approved-remap");
                approvedRemapSet.setTenantId(tenantId);
                approvedRemapSet.setSourceVersionId(sourceVersionId);
                approvedRemapSet.setTargetVersionId(candidateVersionId.get());
                approvedRemapSet.setStatus(TemplateRemapSetStatus.APPROVED);
                approvedRemapSet.setCreatedReason(
                    "approved remap references publication candidate");
                approvedRemapSet.setApprovalReason("approved for replacement launch");
                approvedRemapSet.setApprovedAt(LocalDateTime.now());
                templateRemapSetRepository.save(approvedRemapSet);
                remapSetId.set(approvedRemapSet.getRemapSetId());
                exportCompleted.set(true);
                return recordFixtureCandidate(
                    tenantId, invocation.getArgument(1), exportedManifest);
              } catch (Throwable failure) {
                exportCallbackFailure.set(failure);
                throw failure;
              }
            });
    Mockito.doAnswer(
            invocation -> {
              finalizationFailureInjected.set(true);
              throw new IllegalStateException("forced finalization failure");
            })
        .when(versionAssetArtifactService)
        .markPublished(
            Mockito.eq(tenantId),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.eq(publishWorkflowId),
            Mockito.eq(exportedManifest.manifestHash()));

    Object snapshot =
        reconcileFullVersionPublishMechanics(
            tenantId, "failed remap proof", publishRequestId, publishWorkflowId);
    assertMechanicsSnapshot(snapshot, "FAILED", "forced finalization failure");
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    String failureContext =
        "mechanics snapshot context (candidateVersionId="
            + candidateVersionId.get()
            + ", candidateVersionNumber="
            + candidateVersionNumber.get()
            + ", exportedVersionNumber="
            + exportedVersionNumber.get()
            + ", attemptStatus="
            + attempt.getStatus()
            + ", attemptFailureCode="
            + attempt.getFailureCode()
            + ", attemptFailureMessage="
            + attempt.getFailureMessage()
            + ", recordedDigestFailure="
            + recordedDigestFailure.get()
            + ", exportCallbackFailure="
            + exportCallbackFailure.get()
            + ", exportCallbackFailureFrame="
            + firstStackFrame(exportCallbackFailure.get())
            + ", snapshotStatus="
            + snapshotValue(snapshot, "status")
            + ", snapshotFailureMessage="
            + snapshotValue(snapshot, "failureMessage")
            + ")";
    assertThat(exportCompleted.get()).as(failureContext).isTrue();
    assertThat(finalizationFailureInjected.get()).as(failureContext).isTrue();
    assertThat(exportedVersionNumber.get())
        .as("asset export uses the candidate's persisted version number")
        .isEqualTo(candidateVersionNumber.get());
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(attempt.getFailureMessage()).isEqualTo("forced finalization failure");
    Version retainedCandidate =
        versionRepository.findByTenantIdAndId(tenantId, candidateVersionId.get()).orElseThrow();
    assertThat(retainedCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(
            publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                tenantId, candidateVersionId.get()))
        .isEmpty();
    VersionAssetArtifact failedArtifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, candidateVersionId.get())
            .orElseThrow();
    assertThat(failedArtifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.FAILED);
    VersionTemplateRemapSet retainedRemapSet =
        templateRemapSetRepository
            .findByTenantIdAndRemapSetId(tenantId, remapSetId.get())
            .orElseThrow();
    assertThat(retainedRemapSet.getStatus()).isEqualTo(TemplateRemapSetStatus.APPROVED);
    assertThat(retainedRemapSet.getApprovedAt()).isNotNull();
    assertThat(retainedRemapSet.getSourceVersionId()).isEqualTo(sourceVersionId);
    assertThat(retainedRemapSet.getTargetVersionId()).isEqualTo(candidateVersionId.get());
  }

  @Test
  void publicFreshAndPendingFullVersionPublicationAreDeniedBeforeMutation() {
    String freshTenantId = "9010";
    String freshRequestId = "fresh-public-denial";
    String freshWorkflowId =
        FiremudWorkflowIds.workflowId(
            TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
            freshTenantId,
            "publish-request",
            freshRequestId);
    Game freshGame = new Game();
    freshGame.setTenantId(freshTenantId);
    freshGame.setName("fresh-public-denial-game");
    gameRepository.save(freshGame);

    assertThatThrownBy(
            () ->
                versionPublishCommandService.publishFullVersion(
                    freshTenantId, "fresh public denial", freshRequestId, freshWorkflowId))
        .isInstanceOf(MutationOwnerProofUnavailableException.class)
        .hasMessage(
            "FULL_VERSION_PUBLICATION_UNAVAILABLE: fresh and pending full-version publication require a production Draft association");
    assertThat(publishAttemptRepository.findByPublishWorkflowId(freshWorkflowId)).isEmpty();
    assertThat(versionRepository.findByTenantIdAndVersionNumber(freshTenantId, 1)).isEmpty();

    String pendingTenantId = "9011";
    String pendingRequestId = "pending-public-denial";
    String pendingWorkflowId =
        FiremudWorkflowIds.workflowId(
            TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
            pendingTenantId,
            "publish-request",
            pendingRequestId);
    Game pendingGame = new Game();
    pendingGame.setTenantId(pendingTenantId);
    pendingGame.setName("pending-public-denial-game");
    gameRepository.save(pendingGame);
    Version draft = new Version();
    draft.setTenantId(pendingTenantId);
    draft.setVersionNumber(1);
    draft.setVersionState(VersionLifecycleState.DRAFT);
    draft.setVersionStateEpoch(1L);
    draft.setNotes("pre-existing pending attempt");
    Version savedDraft = versionRepository.save(draft);
    PublishAttempt pendingAttempt = new PublishAttempt();
    pendingAttempt.setTenantId(pendingTenantId);
    pendingAttempt.setPublishWorkflowId(pendingWorkflowId);
    pendingAttempt.setPublishType(PublishType.FULL_VERSION);
    pendingAttempt.setStatus(PublishAttemptStatus.PENDING);
    pendingAttempt.setVersionId(savedDraft.getId());
    pendingAttempt.setVersionNumber(savedDraft.getVersionNumber());
    publishAttemptRepository.save(pendingAttempt);

    assertThatThrownBy(
            () ->
                versionPublishCommandService.publishFullVersion(
                    pendingTenantId, "pending public denial", pendingRequestId, pendingWorkflowId))
        .isInstanceOf(MutationOwnerProofUnavailableException.class)
        .hasMessage(
            "FULL_VERSION_PUBLICATION_UNAVAILABLE: fresh and pending full-version publication require a production Draft association");
    PublishAttempt retainedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(pendingWorkflowId).orElseThrow();
    assertThat(retainedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(retainedAttempt.getVersionId()).isEqualTo(savedDraft.getId());
    assertThat(
            versionRepository
                .findByTenantIdAndId(pendingTenantId, savedDraft.getId())
                .orElseThrow()
                .getVersionState())
        .isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(
            versionAssetArtifactRepository.findByTenantIdAndVersionId(
                pendingTenantId, savedDraft.getId()))
        .isEmpty();
    assertThat(
            publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                pendingTenantId, savedDraft.getId()))
        .isEmpty();
    Mockito.verifyNoInteractions(assetExportService);
  }

  /** Exercises the private transaction mechanics only; this is not production ingress proof. */
  private Object reconcileFullVersionPublishMechanics(
      String tenantId, String notes, String publishRequestId, String publishWorkflowId) {
    try {
      Class<?> requestType =
          Class.forName("net.firedevops.firemud.gamedesign.service.impl.PublishWorkflowRequest");
      Constructor<?> requestConstructor =
          requestType.getDeclaredConstructor(
              String.class, String.class, String.class, String.class);
      requestConstructor.setAccessible(true);
      Object request =
          requestConstructor.newInstance(tenantId, notes, publishRequestId, publishWorkflowId);
      Method mechanics =
          VersionPublishCommandServiceImpl.class.getDeclaredMethod(
              "reconcileFullVersionPublishMechanics", requestType);
      mechanics.setAccessible(true);
      Object target = AopTestUtils.getTargetObject(versionPublishCommandService);
      return mechanics.invoke(target, request);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new AssertionError("private publication mechanics failed", cause);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("private publication mechanics harness is unavailable", exception);
    }
  }

  private static void assertMechanicsSnapshot(
      Object snapshot, String expectedStatus, String expectedFailureMessage) {
    assertThat(snapshotValue(snapshot, "status")).isEqualTo(expectedStatus);
    assertThat(snapshotValue(snapshot, "failureMessage")).isEqualTo(expectedFailureMessage);
  }

  private static String snapshotValue(Object snapshot, String accessorName) {
    try {
      Method accessor = snapshot.getClass().getDeclaredMethod(accessorName);
      accessor.setAccessible(true);
      return (String) accessor.invoke(snapshot);
    } catch (InvocationTargetException exception) {
      throw new AssertionError("publication snapshot accessor failed", exception.getCause());
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("publication snapshot accessor is unavailable", exception);
    }
  }

  private static String firstStackFrame(Throwable failure) {
    if (failure == null || failure.getStackTrace().length == 0) {
      return "none";
    }
    return failure.getStackTrace()[0].toString();
  }

  private static ExportedAssetManifest exportedManifest() {
    return new ExportedAssetManifest(
        MANIFEST_HASH,
        1,
        List.of("fixture.bin"),
        List.of(
            new PublishedArtifactDigest(
                "fixture.bin",
                "BINARY",
                "artifacts/sha256/" + ARTIFACT_DIGEST.substring("sha256:".length()),
                ARTIFACT_DIGEST,
                "application/octet-stream",
                1)));
  }

  private ExportedAssetManifest recordFixtureCandidate(String tenantId, int versionNumber) {
    return recordFixtureCandidate(tenantId, versionNumber, exportedManifest());
  }

  private ExportedAssetManifest recordFixtureCandidate(
      String tenantId, int versionNumber, ExportedAssetManifest candidate) {
    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(tenantId, versionNumber)
            .orElseThrow(() -> new IllegalStateException("Fixture Version was not persisted"));
    GameAsset asset = new GameAsset();
    asset.setTenantId(tenantId);
    asset.setFileName("fixture.bin");
    asset.setContentType("application/octet-stream");
    asset.setData(FIXTURE_ASSET_BYTES);
    GameAsset persistedAsset = gameAssetRepository.save(asset);
    versionAssetPublicationService.associateDraftAsset(
        tenantId, version.getId(), persistedAsset.getId(), "fixture.bin");
    versionAssetPublicationService.freezeOrReadSnapshot(tenantId, versionNumber);
    return exportCandidateService.recordExportCandidate(tenantId, versionNumber, candidate);
  }
}
