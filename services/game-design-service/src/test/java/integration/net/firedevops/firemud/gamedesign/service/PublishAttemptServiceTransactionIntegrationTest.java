package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
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
import net.firedevops.firemud.gamedesign.model.PublishGateFailureCode;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.FrozenAssetExportCandidate;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflow;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionServiceImpl;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proof for isolated selected-publication mechanics, finalization recovery, and
 * public-ingress denial.
 */
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
  private static final String FIXTURE_PUBLIC_BASE_URL = "https://assets.example.test/assets";
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
  @Autowired private VersionServiceImpl versionService;
  @Autowired private VersionPublishCommandServiceImpl versionPublishCommandService;
  @Autowired private PublishAttemptRepository publishAttemptRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private VersionAssetArtifactRepository versionAssetArtifactRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private VersionTemplateRemapSetRepository templateRemapSetRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private VersionAssetExportCandidateService exportCandidateService;
  @Autowired private VersionAssetPublicationService versionAssetPublicationService;
  @MockitoBean private AssetExportService assetExportService;
  @MockitoBean private PublishGateService publishGateService;
  @MockitoSpyBean private ControlPlaneDigestService controlPlaneDigestService;
  @MockitoSpyBean private RecordedParticipantDigestService recordedParticipantDigestService;
  @MockitoSpyBean private VersionAssetArtifactService versionAssetArtifactService;

  @Test
  void fullVersionSealTransactionUsesExplicitReadCommittedIsolation() {
    AtomicReference<Integer> isolation = new AtomicReference<>();

    publishAttemptService.executeFullVersionTransaction(
        () -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
          isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
          return null;
        });

    assertThat(isolation.get()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

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
  void failedScriptPatchRetainsCanonicalVersionAndReplaysDurableFailure() {
    String tenantId = "9012";
    long baseVersionId =
        createGameAndPublishedBaseVersion(tenantId, "script-patch-failure-replay-game");
    String scriptPatchVersion = "patch-failed-replay";
    String publishRequestId = "script-patch-failure-replay";
    String workflowId =
        PublicationDigestRequestBinding.patch(
                tenantId, String.valueOf(baseVersionId), scriptPatchVersion, publishRequestId)
            .derivedWorkflowIdentity();
    Game savedGame = gameRepository.findByTenantId(tenantId);

    Mockito.doThrow(new IllegalStateException("script patch participant unavailable"))
        .when(publishGateService)
        .collectScriptPatchParticipantDigests(
            Mockito.any(VersionDto.class), Mockito.eq(publishRequestId), Mockito.eq(workflowId));

    assertThatThrownBy(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId, baseVersionId, scriptPatchVersion, "first notes", publishRequestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script patch participant unavailable");

    PublishAttempt failedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(workflowId).orElseThrow();
    assertThat(failedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(failedAttempt.getFailureCode()).isEqualTo("PUBLISH_FAILED");
    assertThat(failedAttempt.getFailureMessage()).isEqualTo("script patch participant unavailable");
    Version retainedVersion =
        versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion)
            .stream()
            .findFirst()
            .orElseThrow();
    assertThat(retainedVersion.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(retainedVersion.getVersionStateEpoch()).isEqualTo(1L);
    assertThat(retainedVersion.getCanonicalVersionId()).isNotNull();
    assertThat(retainedVersion.getCanonicalTenantId()).isEqualTo(savedGame.getCanonicalTenantId());

    assertThatThrownBy(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId, baseVersionId, scriptPatchVersion, "changed notes", publishRequestId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("script patch participant unavailable");

    Version replayedVersion =
        versionRepository
            .findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion)
            .stream()
            .findFirst()
            .orElseThrow();
    PublishAttempt replayedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(workflowId).orElseThrow();
    assertThat(replayedVersion.getId()).isEqualTo(retainedVersion.getId());
    assertThat(replayedVersion.getCanonicalVersionId())
        .isEqualTo(retainedVersion.getCanonicalVersionId());
    assertThat(replayedVersion.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(replayedVersion.getVersionStateEpoch()).isEqualTo(1L);
    assertThat(replayedAttempt.getVersionId()).isEqualTo(retainedVersion.getId());
    assertThat(replayedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);

    assertThatThrownBy(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId, baseVersionId, scriptPatchVersion, "new identity", "new-request-id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT");
    assertThat(
            versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion))
        .hasSize(1);
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
  void failedScriptPatchRetainsCandidateForReplayAndReservesItsTuple() {
    String tenantId = "9011";
    String publishRequestId = "script-patch-gate-failure-request";
    String scriptPatchVersion = "patch-gate-failure";
    long baseVersionId = createGameAndPublishedBaseVersion(tenantId, "script-patch-gate-proof");
    Game actualGame = gameRepository.findByTenantId(tenantId);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch(
            tenantId, String.valueOf(baseVersionId), scriptPatchVersion, publishRequestId);
    AtomicInteger participantCollectionCount = new AtomicInteger();

    Mockito.when(
            publishGateService.collectScriptPatchParticipantDigests(
                Mockito.any(VersionDto.class), Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(
            invocation -> {
              participantCollectionCount.incrementAndGet();
              throw new PublishGateFailureException(
                  PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH, "script scope mismatch");
            });

    Throwable firstFailure =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    scriptPatchVersion,
                    "gate failure notes",
                    publishRequestId));

    assertThat(firstFailure).isInstanceOf(PublishGateFailureException.class);
    PublishGateFailureException firstGateFailure = (PublishGateFailureException) firstFailure;
    assertThat(firstGateFailure.failureCode())
        .isEqualTo(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH);
    assertThat(firstGateFailure).hasMessage("script scope mismatch");

    PublishAttempt failedAttempt =
        publishAttemptRepository
            .findByPublishWorkflowId(binding.derivedWorkflowIdentity())
            .orElseThrow();
    Version retainedCandidate =
        versionRepository.findByTenantIdAndId(tenantId, failedAttempt.getVersionId()).orElseThrow();
    assertThat(failedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(failedAttempt.getFailureCode())
        .isEqualTo(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH.name());
    assertThat(failedAttempt.getFailureMessage()).isEqualTo("script scope mismatch");
    assertThat(failedAttempt.getRequestDigest()).isEqualTo(binding.requestDigest());
    assertThat(failedAttempt.getBaseVersionId()).isEqualTo(baseVersionId);
    assertThat(failedAttempt.getScriptPatchVersion()).isEqualTo(scriptPatchVersion);
    assertThat(failedAttempt.getCompletedAt()).isNotNull();
    assertThat(retainedCandidate.getVersionNumber()).isEqualTo(failedAttempt.getVersionNumber());
    assertThat(retainedCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(retainedCandidate.getVersionStateEpoch()).isEqualTo(1L);
    assertThat(retainedCandidate.getBaseVersionId()).isEqualTo(baseVersionId);
    assertThat(retainedCandidate.getScriptPatchVersion()).isEqualTo(scriptPatchVersion);
    assertThat(retainedCandidate.getCanonicalVersionId()).isNotNull();
    assertThat(retainedCandidate.getCanonicalTenantId())
        .isEqualTo(actualGame.getCanonicalTenantId());
    assertThat(retainedCandidate.isScriptOnly()).isTrue();
    assertThat(retainedCandidate.getNotes()).isEqualTo("gate failure notes");
    assertThat(
            versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion))
        .extracting(Version::getId)
        .containsExactly(retainedCandidate.getId());

    Throwable replayFailure =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    scriptPatchVersion,
                    "gate failure notes",
                    publishRequestId));
    assertThat(replayFailure).isInstanceOf(PublishGateFailureException.class);
    PublishGateFailureException replayGateFailure = (PublishGateFailureException) replayFailure;
    assertThat(replayGateFailure.failureCode())
        .isEqualTo(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH);
    assertThat(replayGateFailure).hasMessage("script scope mismatch");
    assertThat(participantCollectionCount).hasValue(1);
    Version replayCandidate =
        versionRepository.findByTenantIdAndId(tenantId, retainedCandidate.getId()).orElseThrow();
    assertThat(replayCandidate.getCanonicalVersionId())
        .isEqualTo(retainedCandidate.getCanonicalVersionId());
    assertThat(replayCandidate.getCanonicalTenantId())
        .isEqualTo(retainedCandidate.getCanonicalTenantId());
    assertThat(replayCandidate.getCanonicalTenantId()).isEqualTo(actualGame.getCanonicalTenantId());
    assertThat(
            versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion))
        .extracting(Version::getId)
        .containsExactly(retainedCandidate.getId());

    Throwable changedNotesReplayFailure =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    scriptPatchVersion,
                    "changed replay notes",
                    publishRequestId));
    assertThat(changedNotesReplayFailure).isInstanceOf(PublishGateFailureException.class);
    PublishGateFailureException changedNotesReplayGateFailure =
        (PublishGateFailureException) changedNotesReplayFailure;
    assertThat(changedNotesReplayGateFailure.failureCode())
        .isEqualTo(PublishGateFailureCode.PARTICIPANT_SCOPE_MISMATCH);
    assertThat(changedNotesReplayGateFailure).hasMessage("script scope mismatch");
    assertThat(participantCollectionCount).hasValue(1);
    PublishAttempt attemptAfterReplay =
        publishAttemptRepository
            .findByPublishWorkflowId(binding.derivedWorkflowIdentity())
            .orElseThrow();
    assertThat(attemptAfterReplay.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(attemptAfterReplay.getVersionId()).isEqualTo(retainedCandidate.getId());

    Throwable tupleConflict =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    scriptPatchVersion,
                    "new request notes",
                    "script-patch-same-tuple-new-request"));
    assertThat(tupleConflict)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PUBLISH_SCRIPT_PATCH_IDENTITY_CONFLICT");
    assertThat(
            publishAttemptRepository
                .findByPublishWorkflowId(binding.derivedWorkflowIdentity())
                .orElseThrow()
                .getStatus())
        .isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(
            versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion))
        .extracting(Version::getId)
        .containsExactly(retainedCandidate.getId());
    assertThat(participantCollectionCount).hasValue(1);

    String independentPatchVersion = "patch-independent";
    String independentRequestId = "script-patch-independent-request";
    PublicationDigestRequestBinding independentBinding =
        PublicationDigestRequestBinding.patch(
            tenantId, String.valueOf(baseVersionId), independentPatchVersion, independentRequestId);
    Throwable independentPatchFailure =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    independentPatchVersion,
                    "independent patch notes",
                    independentRequestId));
    assertThat(independentPatchFailure).isInstanceOf(PublishGateFailureException.class);
    PublishAttempt independentAttempt =
        publishAttemptRepository
            .findByPublishWorkflowId(independentBinding.derivedWorkflowIdentity())
            .orElseThrow();
    Version independentCandidate =
        versionRepository
            .findByTenantIdAndId(tenantId, independentAttempt.getVersionId())
            .orElseThrow();
    assertThat(independentAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(independentCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(independentCandidate.getBaseVersionId()).isEqualTo(baseVersionId);
    assertThat(independentCandidate.getScriptPatchVersion()).isEqualTo(independentPatchVersion);
    assertThat(independentCandidate.getId()).isNotEqualTo(retainedCandidate.getId());
    assertThat(participantCollectionCount).hasValue(2);
  }


  @Test
  void scriptPatchFinalizationFailureRollsBackToRetainedDraftAndMarksAttemptFailed() {
    String tenantId = "9012";
    String publishRequestId = "script-patch-finalization-failure-request";
    String scriptPatchVersion = "patch-finalization-failure";
    long baseVersionId =
        createGameAndPublishedBaseVersion(tenantId, "script-patch-finalization-proof");
    Game actualGame = gameRepository.findByTenantId(tenantId);
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.patch(
            tenantId, String.valueOf(baseVersionId), scriptPatchVersion, publishRequestId);
    AtomicInteger participantCollectionCount = new AtomicInteger();

    Mockito.when(
            publishGateService.collectScriptPatchParticipantDigests(
                Mockito.any(VersionDto.class), Mockito.anyString(), Mockito.anyString()))
        .thenAnswer(
            invocation -> {
              participantCollectionCount.incrementAndGet();
              return List.of();
            });
    Mockito.doThrow(new IllegalStateException("recorded digest write failed"))
        .when(recordedParticipantDigestService)
        .recordVerifiedDigests(
            Mockito.eq(tenantId),
            Mockito.eq(PublishType.SCRIPT_PATCH),
            Mockito.eq(binding.derivedWorkflowIdentity()),
            Mockito.anyList());

    Throwable publishFailure =
        catchThrowable(
            () ->
                versionService.publishScriptPatchVersion(
                    tenantId,
                    baseVersionId,
                    scriptPatchVersion,
                    "finalization failure notes",
                    publishRequestId));

    assertThat(publishFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("recorded digest write failed");
    Mockito.verify(recordedParticipantDigestService)
        .recordVerifiedDigests(
            Mockito.eq(tenantId),
            Mockito.eq(PublishType.SCRIPT_PATCH),
            Mockito.eq(binding.derivedWorkflowIdentity()),
            Mockito.anyList());
    assertThat(participantCollectionCount).hasValue(1);

    PublishAttempt failedAttempt =
        publishAttemptRepository
            .findByPublishWorkflowId(binding.derivedWorkflowIdentity())
            .orElseThrow();
    Version retainedCandidate =
        versionRepository.findByTenantIdAndId(tenantId, failedAttempt.getVersionId()).orElseThrow();
    assertThat(failedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(failedAttempt.getFailureCode()).isEqualTo("PUBLISH_FAILED");
    assertThat(failedAttempt.getFailureMessage()).isEqualTo("recorded digest write failed");
    assertThat(failedAttempt.getCompletedAt()).isNotNull();
    assertThat(retainedCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(retainedCandidate.getVersionStateEpoch()).isEqualTo(1L);
    assertThat(retainedCandidate.getBaseVersionId()).isEqualTo(baseVersionId);
    assertThat(retainedCandidate.getScriptPatchVersion()).isEqualTo(scriptPatchVersion);
    assertThat(retainedCandidate.getCanonicalVersionId()).isNotNull();
    assertThat(retainedCandidate.getCanonicalTenantId())
        .isEqualTo(actualGame.getCanonicalTenantId());
    assertThat(retainedCandidate.isScriptOnly()).isTrue();
    assertThat(
            versionRepository.findByTenantIdAndBaseVersionIdAndScriptPatchVersionAndScriptOnly(
                tenantId, baseVersionId, scriptPatchVersion))
        .extracting(Version::getId)
        .containsExactly(retainedCandidate.getId());
  }



  @Test
  void selectedMechanicsRetainsCandidatePendingCompletePublicationReadback() {
    String tenantId = "9013";
    SelectedPublicationFixture fixture =
        selectedPublicationFixture(tenantId, "pending-readback-proof-game", false);
    String publishWorkflowId = fixture.operation().workflowId();
    ExportedAssetManifest candidate = recordFixtureCandidate(fixture);
    versionAssetArtifactService.stageExport(
        tenantId,
        fixture.version().getId(),
        fixture.version().getVersionNumber(),
        publishWorkflowId);

    Throwable reconciliationFailure =
        catchThrowable(() -> reconcileSelectedFullVersionPublishMechanics(fixture));

    assertThat(reconciliationFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("published release evidence is incomplete; readback/reconciliation is required");
    assertThat(reconciliationFailure.getClass().getName())
        .isEqualTo(
            "net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl"
                + "$PendingReconciliationException");
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    Version retainedDraft =
        versionRepository
            .findByTenantIdAndId(tenantId, fixture.version().getId())
            .orElseThrow();
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(attempt.getFailureCode()).isNull();
    assertThat(attempt.getFailureMessage()).isNull();
    assertThat(retainedDraft.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(
            publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                tenantId, retainedDraft.getId()))
        .isEmpty();
    VersionAssetArtifact storedArtifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, retainedDraft.getId())
            .orElseThrow();
    assertThat(storedArtifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.STAGED);
    FrozenAssetExportCandidate retainedCandidate =
        exportCandidateService.readExportCandidate(tenantId, retainedDraft.getVersionNumber());
    assertThat(retainedCandidate)
        .isEqualTo(new FrozenAssetExportCandidate(candidate, "https://assets.example.test/assets"));
    Mockito.verify(assetExportService, Mockito.never())
        .exportAssets(Mockito.eq(tenantId), Mockito.anyInt());
    Mockito.verify(versionAssetArtifactService, Mockito.never())
        .markPublished(
            Mockito.eq(tenantId),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.eq(publishWorkflowId),
            Mockito.anyString());
  }


  @Test
  void fullVersionMechanicsLeavesPostExportFinalizationFailurePendingWithCandidateAndRemap() {
    String tenantId = "9014";
    SelectedPublicationFixture fixture =
        selectedPublicationFixture(tenantId, "failed-remap-proof-game", true);
    String publishRequestId =
        fixture.operation().account().input().selection().intent().publishRequestId();
    String publishWorkflowId = fixture.operation().workflowId();
    Version sourceVersion =
        versionRepository.findByTenantIdAndVersionNumber(tenantId, 1).orElseThrow();
    long sourceVersionId = sourceVersion.getId();
    AtomicReference<Long> candidateVersionId = new AtomicReference<>();
    AtomicReference<Integer> candidateVersionNumber = new AtomicReference<>();
    AtomicReference<Integer> exportedVersionNumber = new AtomicReference<>();
    AtomicReference<String> remapSetId = new AtomicReference<>();
    AtomicReference<Throwable> recordedDigestFailure = new AtomicReference<>();
    AtomicReference<Throwable> exportCallbackFailure = new AtomicReference<>();
    AtomicReference<Throwable> controlPlaneDigestFailure = captureControlPlaneDigestFailure();
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
              return PublishedWorldSelectorFixtures.participants(
                  candidate.id(),
                  fixture.operation().world(),
                  AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
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
                return recordFixtureCandidate(fixture, exportedManifest);
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

    Throwable reconciliationFailure =
        catchThrowable(() -> reconcileSelectedFullVersionPublishMechanics(fixture));
    assertNoControlPlaneDigestFailure(controlPlaneDigestFailure.get());
    assertThat(reconciliationFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("exported candidate requires exact retry/reconciliation");
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    String reconciliationContext =
        "mechanics reconciliation context (candidateVersionId="
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
            + ", reconciliationFailure="
            + reconciliationFailure
            + ")";
    assertThat(exportCompleted.get()).as(reconciliationContext).isTrue();
    assertThat(finalizationFailureInjected.get()).as(reconciliationContext).isTrue();
    assertThat(exportedVersionNumber.get())
        .as("asset export uses the candidate's persisted version number")
        .isEqualTo(candidateVersionNumber.get());
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(attempt.getFailureCode()).isNull();
    assertThat(attempt.getFailureMessage()).isNull();
    Version retainedCandidate =
        versionRepository.findByTenantIdAndId(tenantId, candidateVersionId.get()).orElseThrow();
    assertThat(retainedCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(retainedCandidate.getVersionStateEpoch())
        .isEqualTo(
            Long.parseLong(
                fixture
                    .operation()
                    .account()
                    .input()
                    .selection()
                    .intent()
                    .expectedVersionStateEpoch()));
    assertThat(
            publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                tenantId, candidateVersionId.get()))
        .isEmpty();
    VersionAssetArtifact retainedArtifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, candidateVersionId.get())
            .orElseThrow();
    assertThat(retainedArtifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.STAGED);
    FrozenAssetExportCandidate expectedCandidate =
        new FrozenAssetExportCandidate(exportedManifest, FIXTURE_PUBLIC_BASE_URL);
    assertThat(
            exportCandidateService.readExportCandidate(
                tenantId, retainedCandidate.getVersionNumber()))
        .isEqualTo(expectedCandidate);
    assertThat(
            exportCandidateService.recordExportCandidate(
                tenantId, retainedCandidate.getVersionNumber(), expectedCandidate))
        .isEqualTo(expectedCandidate);
    Mockito.verify(versionAssetArtifactService, Mockito.never())
        .markFailed(
            Mockito.eq(tenantId),
            Mockito.eq(candidateVersionId.get()),
            Mockito.eq(candidateVersionNumber.get()),
            Mockito.eq(publishWorkflowId),
            Mockito.any(ExportedAssetManifest.class),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(assetExportService, Mockito.never())
        .deleteExportedAssets(
            Mockito.eq(tenantId), Mockito.anyInt(), Mockito.anyList());
    VersionTemplateRemapSet retainedRemapSet =
        templateRemapSetRepository
            .findByTenantIdAndRemapSetId(tenantId, remapSetId.get())
            .orElseThrow();
    assertThat(retainedRemapSet.getStatus()).isEqualTo(TemplateRemapSetStatus.APPROVED);
    assertThat(retainedRemapSet.getApprovedAt()).isNotNull();
    assertThat(retainedRemapSet.getSourceVersionId()).isEqualTo(sourceVersionId);
    assertThat(retainedRemapSet.getTargetVersionId()).isEqualTo(candidateVersionId.get());
  }

  // These private mechanics tests use isolated Account/World fixture evidence. They exercise
  // selected publication and finalization recovery, not live authorization or ingress.
  @Test
  void privateMechanicsHarnessCommitsAttemptVersionAndReleaseBundleTogether() {
    String tenantId = "9002";
    SelectedPublicationFixture fixture =
        selectedPublicationFixture(tenantId, "successful-transaction-proof-game", false);
    String publishRequestId =
        fixture.operation().account().input().selection().intent().publishRequestId();
    String publishWorkflowId = fixture.operation().workflowId();
    AtomicReference<Throwable> controlPlaneDigestFailure = captureControlPlaneDigestFailure();

    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(publishWorkflowId)))
        .thenAnswer(
            invocation -> {
              VersionDto version = invocation.getArgument(0);
              return PublishedWorldSelectorFixtures.participants(
                  version.id(),
                  fixture.operation().world(),
                  AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
            });
    Mockito.when(assetExportService.exportAssets(tenantId, fixture.version().getVersionNumber()))
        .thenAnswer(invocation -> recordFixtureCandidate(fixture));

    Object snapshot = reconcileSelectedPublicationMechanics(fixture);
    assertNoControlPlaneDigestFailure(controlPlaneDigestFailure.get());
    assertMechanicsSnapshot(snapshot, "SUCCEEDED", "");

    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    Version storedVersion =
        versionRepository.findByTenantIdAndId(tenantId, fixture.version().getId()).orElseThrow();
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
  void selectedMechanicsLeavesPostExportFinalizationFailurePendingWithCandidateAndRemap() {
    String tenantId = "9003";
    SelectedPublicationFixture fixture =
        selectedPublicationFixture(tenantId, "failed-remap-proof-game", true);
    String publishRequestId =
        fixture.operation().account().input().selection().intent().publishRequestId();
    String publishWorkflowId = fixture.operation().workflowId();
    Version sourceVersion =
        versionRepository.findByTenantIdAndVersionNumber(tenantId, 1).orElseThrow();
    long sourceVersionId = sourceVersion.getId();
    AtomicReference<Long> candidateVersionId = new AtomicReference<>();
    AtomicReference<Integer> candidateVersionNumber = new AtomicReference<>();
    AtomicReference<Integer> exportedVersionNumber = new AtomicReference<>();
    AtomicReference<String> remapSetId = new AtomicReference<>();
    AtomicReference<Throwable> recordedDigestFailure = new AtomicReference<>();
    AtomicReference<Throwable> exportCallbackFailure = new AtomicReference<>();
    AtomicReference<Throwable> controlPlaneDigestFailure = captureControlPlaneDigestFailure();
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
              return PublishedWorldSelectorFixtures.participants(
                  candidate.id(),
                  fixture.operation().world(),
                  AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
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
                return recordFixtureCandidate(fixture, exportedManifest);
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

    Throwable reconciliationFailure =
        catchThrowable(() -> reconcileSelectedPublicationMechanics(fixture));
    assertNoControlPlaneDigestFailure(controlPlaneDigestFailure.get());
    assertThat(reconciliationFailure)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("exported candidate requires exact retry/reconciliation");
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    String reconciliationContext =
        "mechanics reconciliation context (candidateVersionId="
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
            + ", reconciliationFailure="
            + reconciliationFailure
            + ")";
    assertThat(exportCompleted.get()).as(reconciliationContext).isTrue();
    assertThat(finalizationFailureInjected.get()).as(reconciliationContext).isTrue();
    assertThat(exportedVersionNumber.get())
        .as("asset export uses the candidate's persisted version number")
        .isEqualTo(candidateVersionNumber.get());
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(attempt.getFailureCode()).isNull();
    assertThat(attempt.getFailureMessage()).isNull();
    Version retainedCandidate =
        versionRepository.findByTenantIdAndId(tenantId, candidateVersionId.get()).orElseThrow();
    assertThat(retainedCandidate.getVersionState()).isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(retainedCandidate.getVersionStateEpoch())
        .isEqualTo(
            Long.parseLong(
                fixture
                    .operation()
                    .account()
                    .input()
                    .selection()
                    .intent()
                    .expectedVersionStateEpoch()));
    assertThat(
            publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                tenantId, candidateVersionId.get()))
        .isEmpty();
    VersionAssetArtifact retainedArtifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, candidateVersionId.get())
            .orElseThrow();
    assertThat(retainedArtifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.STAGED);
    FrozenAssetExportCandidate expectedCandidate =
        new FrozenAssetExportCandidate(exportedManifest, FIXTURE_PUBLIC_BASE_URL);
    assertThat(
            exportCandidateService.readExportCandidate(
                tenantId, retainedCandidate.getVersionNumber()))
        .isEqualTo(expectedCandidate);
    assertThat(
            exportCandidateService.recordExportCandidate(
                tenantId, retainedCandidate.getVersionNumber(), expectedCandidate))
        .isEqualTo(expectedCandidate);
    Mockito.verify(versionAssetArtifactService, Mockito.never())
        .markFailed(
            Mockito.eq(tenantId),
            Mockito.eq(candidateVersionId.get()),
            Mockito.eq(candidateVersionNumber.get()),
            Mockito.eq(publishWorkflowId),
            Mockito.any(ExportedAssetManifest.class),
            Mockito.anyString(),
            Mockito.anyString());
    Mockito.verify(assetExportService, Mockito.never())
        .deleteExportedAssets(
            Mockito.eq(tenantId), Mockito.anyInt(), Mockito.anyList());
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

  /** Fixture-only Account/World inputs; this exercises GD's actual selected owner transaction. */
  private SelectedPublicationFixture selectedPublicationFixture(
      String tenantId, String gameName, boolean includePublishedSource) {
    TransactionTemplate sourceTransaction = new TransactionTemplate(transactionManager);
    sourceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return sourceTransaction.execute(
        status -> {
          Game game = new Game();
          game.setTenantId(tenantId);
          game.setName(gameName);
          gameRepository.save(game);

          if (includePublishedSource) {
            Version source = new Version();
            source.setTenantId(tenantId);
            source.setVersionNumber(1);
            source.setVersionState(VersionLifecycleState.PUBLISHED);
            source.setVersionStateEpoch(2L);
            source.setNotes("ISOLATED remap source");
            versionRepository.save(source);
          }

          Version draft = new Version();
          draft.setTenantId(tenantId);
          draft.setVersionNumber(includePublishedSource ? 2 : 1);
          draft.setVersionState(VersionLifecycleState.DRAFT);
          draft.setVersionStateEpoch(1L);
          draft.setNotes("ISOLATED selected publication");
          Version persisted = versionRepository.save(draft);
          TargetProof target =
              new TargetProof(
                  persisted.getCanonicalTenantId(),
                  persisted.getCanonicalVersionId(),
                  persisted.getId(),
                  persisted.getTenantId(),
                  persisted.getIdentitySourceGameRowId(),
                  persisted.getIdentitySourceGameTenantKey(),
                  persisted.getIdentitySourceProvenanceKind());
          try {
            GameDesignPublicationOperation operation =
                IsolatedPublicationOwnerSetup.retainFrozenSourceBacked(
                    dsl, target, persisted.getVersionStateEpoch(), "ISOLATED publication proof");
            return new SelectedPublicationFixture(persisted, operation);
          } catch (Exception failure) {
            throw new IllegalStateException("Selected publication fixture setup failed", failure);
          }
        });
  }

  private Object reconcileSelectedPublicationMechanics(SelectedPublicationFixture fixture) {
    try {
      Class<?> requestType =
          Class.forName("net.firedevops.firemud.gamedesign.service.impl.PublishWorkflowRequest");
      var selected = fixture.operation().account().input().selection();
      var selectedIntent = selected.intent();
      var selectedCommit = selected.selectedCommit();
      PublishIntent intent =
          new PublishIntent(
              selected.target().canonicalTenantId(),
              selected.target().canonicalVersionId(),
              selectedIntent.publishRequestId(),
              selectedIntent.expectedVersionStateEpoch(),
              selectedIntent.notes(),
              selectedCommit.requestId(),
              selectedCommit.commitId(),
              selectedCommit.digest());
      Constructor<?> requestConstructor =
          requestType.getDeclaredConstructor(
              String.class, String.class, String.class, String.class, PublishIntent.class);
      requestConstructor.setAccessible(true);
      Object request =
          requestConstructor.newInstance(
              fixture.version().getTenantId(),
              selectedIntent.notes(),
              selectedIntent.publishRequestId(),
              fixture.operation().workflowId(),
              intent);
      Method mechanics =
          VersionPublishCommandServiceImpl.class.getDeclaredMethod(
              "reconcileSelectedPublicationMechanics", requestType);
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

  private Object reconcileSelectedFullVersionPublishMechanics(SelectedPublicationFixture fixture) {
    try {
      Class<?> requestType =
          Class.forName("net.firedevops.firemud.gamedesign.service.impl.PublishWorkflowRequest");
      var selected = fixture.operation().account().input().selection();
      var selectedIntent = selected.intent();
      var selectedCommit = selected.selectedCommit();
      PublishIntent intent =
          new PublishIntent(
              selected.target().canonicalTenantId(),
              selected.target().canonicalVersionId(),
              selectedIntent.publishRequestId(),
              selectedIntent.expectedVersionStateEpoch(),
              selectedIntent.notes(),
              selectedCommit.requestId(),
              selectedCommit.commitId(),
              selectedCommit.digest());
      Constructor<?> requestConstructor =
          requestType.getDeclaredConstructor(
              String.class, String.class, String.class, String.class, PublishIntent.class);
      requestConstructor.setAccessible(true);
      Object request =
          requestConstructor.newInstance(
              fixture.version().getTenantId(),
              selectedIntent.notes(),
              selectedIntent.publishRequestId(),
              fixture.operation().workflowId(),
              intent);
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

  private ExportedAssetManifest recordFixtureCandidate(SelectedPublicationFixture fixture) {
    return recordFixtureCandidate(fixture, exportedManifest());
  }

  private ExportedAssetManifest recordFixtureCandidate(
      SelectedPublicationFixture fixture, ExportedAssetManifest candidate) {
    String tenantId = fixture.version().getTenantId();
    int versionNumber = fixture.version().getVersionNumber();
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
    FrozenAssetExportCandidate frozenCandidate =
        exportCandidateService.recordExportCandidate(
            tenantId,
            versionNumber,
            new FrozenAssetExportCandidate(candidate, FIXTURE_PUBLIC_BASE_URL));
    return frozenCandidate.manifest();
  }

  private record SelectedPublicationFixture(
      Version version, GameDesignPublicationOperation operation) {}

  private static void assertMechanicsSnapshot(
      Object snapshot, String expectedStatus, String expectedFailureMessage) {
    String actualStatus = snapshotValue(snapshot, "status");
    String actualFailureCode = snapshotValue(snapshot, "failureCode");
    String actualFailureMessage = snapshotValue(snapshot, "failureMessage");
    String failureContext =
        "publication mechanics snapshot: status="
            + actualStatus
            + ", failureCode="
            + actualFailureCode
            + ", failureMessage="
            + actualFailureMessage;
    assertThat(actualStatus).as(failureContext).isEqualTo(expectedStatus);
    assertThat(actualFailureMessage).as(failureContext).isEqualTo(expectedFailureMessage);
  }

  private AtomicReference<Throwable> captureControlPlaneDigestFailure() {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Mockito.doAnswer(
            invocation -> {
              try {
                return invocation.callRealMethod();
              } catch (Throwable exception) {
                failure.set(exception);
                throw exception;
              }
            })
        .when(controlPlaneDigestService)
        .getDigestForVersion(Mockito.any(VersionDto.class));
    return failure;
  }

  private static void assertNoControlPlaneDigestFailure(Throwable failure) {
    if (failure == null) {
      return;
    }
    StringWriter stackTrace = new StringWriter();
    failure.printStackTrace(new PrintWriter(stackTrace));
    assertThat(failure).as("real control-plane digest computation failed:\n" + stackTrace).isNull();
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


  private long createGameAndPublishedBaseVersion(String tenantId, String gameName) {
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName(gameName);
    gameRepository.save(game);

    Version baseVersion = new Version();
    baseVersion.setTenantId(tenantId);
    baseVersion.setVersionNumber(1);
    baseVersion.setVersionState(VersionLifecycleState.PUBLISHED);
    baseVersion.setVersionStateEpoch(2L);
    baseVersion.setNotes("published script patch base");
    return versionRepository.save(baseVersion).getId();
  }
}
