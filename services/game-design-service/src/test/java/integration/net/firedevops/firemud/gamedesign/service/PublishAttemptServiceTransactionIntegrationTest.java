package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
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
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationOwner;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflow;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for full-version publication transactions and failure retention. */
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

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private GameRepository gameRepository;
  @Autowired private PublishAttemptServiceImpl publishAttemptService;
  @Autowired private VersionPublishCommandServiceImpl versionPublishCommandService;
  @Autowired private PublishAttemptRepository publishAttemptRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private VersionAssetArtifactRepository versionAssetArtifactRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private VersionTemplateRemapSetRepository templateRemapSetRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;
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
    assertThat(savedAttempt.getRevision()).isEqualTo(1L);

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
    assertThat(backfilled.getRevision()).isEqualTo(2L);
    PublishAttempt retryReadback =
        publishAttemptRepository
            .backfillFullVersionRequestDigestIfAbsent(
                savedAttempt.getId(),
                tenantId,
                publishWorkflowId,
                savedVersion.getId(),
                savedVersion.getVersionNumber(),
                "exact-identity-digest")
            .orElseThrow();
    assertThat(retryReadback.getRevision()).isEqualTo(2L);
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

    String terminalWorkflowId = "terminal-digest-backfill-denial-integration-test";
    PublishAttempt terminalAttempt = new PublishAttempt();
    terminalAttempt.setTenantId(tenantId);
    terminalAttempt.setPublishWorkflowId(terminalWorkflowId);
    terminalAttempt.setPublishType(PublishType.FULL_VERSION);
    terminalAttempt.setVersionId(savedVersion.getId());
    terminalAttempt.setVersionNumber(savedVersion.getVersionNumber());
    publishAttemptRepository.save(terminalAttempt);
    terminalAttempt =
        publishAttemptRepository.findByPublishWorkflowId(terminalWorkflowId).orElseThrow();
    terminalAttempt.setStatus(PublishAttemptStatus.FAILED);
    terminalAttempt.setFailureCode("fixture-terminal");
    terminalAttempt.setCompletedAt(LocalDateTime.now());
    terminalAttempt = publishAttemptRepository.save(terminalAttempt);
    assertThat(terminalAttempt.getId()).isNotNull();
    assertThat(terminalAttempt.getRevision()).isEqualTo(2L);
    assertThat(terminalAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(terminalAttempt.getFailureCode()).isEqualTo("fixture-terminal");
    assertThat(
            publishAttemptRepository.backfillFullVersionRequestDigestIfAbsent(
                terminalAttempt.getId(),
                tenantId,
                terminalWorkflowId,
                savedVersion.getId(),
                savedVersion.getVersionNumber(),
                "terminal-must-not-install-digest"))
        .isEmpty();
    PublishAttempt retainedTerminalAttempt =
        publishAttemptRepository.findByPublishWorkflowId(terminalWorkflowId).orElseThrow();
    assertThat(retainedTerminalAttempt.getId()).isEqualTo(terminalAttempt.getId());
    assertThat(retainedTerminalAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(retainedTerminalAttempt.getFailureCode()).isEqualTo("fixture-terminal");
    assertThat(retainedTerminalAttempt.getRequestDigest()).isNull();
    assertThat(retainedTerminalAttempt.getRevision()).isEqualTo(2L);
  }

  @Test
  void publishAttemptSaveUsesRevisionCasAndRejectsStaleAndTerminalUpdates() {
    String tenantId = "9005";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("publish-attempt-revision-proof-game");
    gameRepository.save(game);

    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    Version savedVersion = versionRepository.save(version);

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(tenantId);
    attempt.setPublishWorkflowId("publish-attempt-revision-cas-integration-test");
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setVersionId(savedVersion.getId());
    attempt.setVersionNumber(savedVersion.getVersionNumber());
    PublishAttempt inserted = publishAttemptRepository.save(attempt);
    assertThat(inserted).isNotNull();
    assertThat(inserted.getId()).isNotNull();
    assertThat(inserted.getRevision()).isEqualTo(1L);
    assertThat(inserted.getTenantId()).isEqualTo(tenantId);
    assertThat(inserted.getPublishWorkflowId()).isEqualTo(attempt.getPublishWorkflowId());
    assertThat(inserted.getVersionId()).isEqualTo(savedVersion.getId());
    assertThat(inserted.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    PublishAttempt insertedReadback =
        publishAttemptRepository
            .findByPublishWorkflowId(attempt.getPublishWorkflowId())
            .orElseThrow();
    assertThat(insertedReadback.getId()).isEqualTo(inserted.getId());
    assertThat(insertedReadback.getRevision()).isEqualTo(1L);
    assertThat(insertedReadback.getTenantId()).isEqualTo(tenantId);
    assertThat(insertedReadback.getVersionId()).isEqualTo(savedVersion.getId());
    assertThat(insertedReadback.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);

    PublishAttempt firstWriter =
        publishAttemptRepository
            .findByPublishWorkflowId(attempt.getPublishWorkflowId())
            .orElseThrow();
    PublishAttempt staleWriter =
        publishAttemptRepository
            .findByPublishWorkflowId(attempt.getPublishWorkflowId())
            .orElseThrow();
    firstWriter.setFailureMessage("first revision writer");
    firstWriter = publishAttemptRepository.save(firstWriter);
    assertThat(firstWriter.getId()).isEqualTo(inserted.getId());
    assertThat(firstWriter.getRevision()).isEqualTo(2L);
    assertThat(firstWriter.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(firstWriter.getFailureMessage()).isEqualTo("first revision writer");

    staleWriter.setFailureMessage("stale revision writer");
    assertThatThrownBy(() -> publishAttemptRepository.save(staleWriter))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or terminal");
    PublishAttempt afterStaleWrite =
        publishAttemptRepository
            .findByPublishWorkflowId(attempt.getPublishWorkflowId())
            .orElseThrow();
    assertThat(afterStaleWrite.getId()).isEqualTo(inserted.getId());
    assertThat(afterStaleWrite.getRevision()).isEqualTo(2L);
    assertThat(afterStaleWrite.getStatus()).isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(afterStaleWrite.getFailureMessage()).isEqualTo("first revision writer");
    assertThat(afterStaleWrite.getFailureCode()).isNull();
    assertThat(afterStaleWrite.getCompletedAt()).isNull();

    afterStaleWrite.setStatus(PublishAttemptStatus.FAILED);
    afterStaleWrite.setFailureCode("terminal-revision-proof");
    afterStaleWrite.setCompletedAt(LocalDateTime.now());
    PublishAttempt terminal = publishAttemptRepository.save(afterStaleWrite);
    assertThat(terminal.getId()).isEqualTo(inserted.getId());
    assertThat(terminal.getRevision()).isEqualTo(3L);
    assertThat(terminal.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(terminal.getFailureMessage()).isEqualTo("first revision writer");
    assertThat(terminal.getFailureCode()).isEqualTo("terminal-revision-proof");
    terminal.setFailureMessage("terminal rows cannot be rewritten");
    assertThatThrownBy(() -> publishAttemptRepository.save(terminal))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale or terminal");
    PublishAttempt afterTerminalWrite =
        publishAttemptRepository
            .findByPublishWorkflowId(attempt.getPublishWorkflowId())
            .orElseThrow();
    assertThat(afterTerminalWrite.getId()).isEqualTo(inserted.getId());
    assertThat(afterTerminalWrite.getRevision()).isEqualTo(3L);
    assertThat(afterTerminalWrite.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(afterTerminalWrite.getFailureMessage()).isEqualTo("first revision writer");
    assertThat(afterTerminalWrite.getFailureCode()).isEqualTo("terminal-revision-proof");
  }

  @Test
  void reconciledFullVersionPublicationCommitsAttemptVersionAndReleaseBundleTogether() {
    String tenantId = "9002";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("successful-transaction-proof-game");
    gameRepository.save(game);

    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(1);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("successful transaction proof");
    candidate = versionRepository.save(candidate);
    var operation = reserveSelectedDraft(candidate, "successful transaction proof");
    var selection =
        AuthoredDraftPublishSelection.fromStored(
            operation.account().input().selection().canonicalJson(),
            operation.account().input().selection().digest());
    var exactReservationRetry =
        inOwnerTransaction(
            () ->
                new SelectedDraftPublicationOwner(dsl)
                    .reserve(selection.intent(), operation.account(), operation.world()));
    assertThat(exactReservationRetry.selection()).isEqualTo(selection);
    assertThat(exactReservationRetry.operation().canonicalBytes())
        .containsExactly(operation.canonicalBytes());
    assertThat(exactReservationRetry.attemptId())
        .isEqualTo(
            publishAttemptRepository
                .findByPublishWorkflowId(operation.workflowId())
                .orElseThrow()
                .getId());
    String publishRequestId = operation.account().publishRequestId();
    String publishWorkflowId = operation.workflowId();
    var participantDigests =
        PublishedWorldSelectorFixtures.participants(candidate.getId(), operation.world());

    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(publishWorkflowId)))
        .thenReturn(participantDigests);
    Mockito.when(assetExportService.exportAssets(tenantId, 1)).thenReturn(immutableEmptyManifest());

    VersionDto publishedVersion =
        versionPublishCommandService.publishSelectedDraftFullVersion(
            tenantId,
            candidate.getId(),
            "successful transaction proof",
            publishRequestId,
            publishWorkflowId);
    VersionDto terminalReplay =
        versionPublishCommandService.publishSelectedDraftFullVersion(
            tenantId,
            candidate.getId(),
            "successful transaction proof",
            publishRequestId,
            publishWorkflowId);
    assertThat(terminalReplay.id()).isEqualTo(publishedVersion.id());

    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    Version storedVersion =
        versionRepository.findByTenantIdAndId(tenantId, publishedVersion.id()).orElseThrow();
    PublishedReleaseBundle bundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, publishedVersion.id())
            .orElseThrow();
    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, publishedVersion.id())
            .orElseThrow();

    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.SUCCEEDED);
    assertThat(attempt.getRevision()).isGreaterThan(1L);
    assertThat(attempt.getVersionId()).isEqualTo(publishedVersion.id());
    assertThat(storedVersion.getVersionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
    assertThat(bundle.getPublishWorkflowId()).isEqualTo(publishWorkflowId);
    assertThat(bundle.getManifestHash()).isEqualTo("sha256:" + "a".repeat(64));
    assertThat(artifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);
    var terminalOperation =
        new GameDesignPublicationOperationRepository(dsl).read(publishWorkflowId).orElseThrow();
    assertThat(terminalOperation.outcome()).isEqualTo("PUBLISHED");
    assertThat(terminalOperation.terminalEvidenceBytes()).isNotNull();
    publishAttemptRepository.requirePublishedOperation(attempt);
  }

  @Test
  void selectedDraftPublicationRemainsPendingWithoutItsOriginalOperation() throws Exception {
    String tenantId = "9005";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("missing-original-operation-proof-game");
    gameRepository.save(game);

    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(1);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("missing original operation proof");
    candidate = versionRepository.save(candidate);
    TargetProof target = targetProof(candidate);
    long candidateId = candidate.getId();
    long candidateEpoch = candidate.getVersionStateEpoch();
    int candidateNumber = candidate.getVersionNumber();
    AtomicReference<AuthoredDraftPublishSelectionRepository.SelectionSnapshot> selectedRef =
        new AtomicReference<>();
    AtomicReference<String> workflowRef = new AtomicReference<>();
    inOwnerTransaction(
        () -> {
          try {
            var selected =
                IsolatedPublicationOwnerSetup.selectSourceBackedDraft(
                    dsl, target, candidateEpoch, "missing original operation proof");
            selectedRef.set(selected);
            var intent = selected.selection().intent();
            String workflow =
                FiremudWorkflowIds.workflowId(
                    TemporalVersionPublishWorkflow.WORKFLOW_FAMILY,
                    target.gameDesignVersionTenantKey(),
                    "publish-request",
                    intent.publishRequestId());
            workflowRef.set(workflow);
            PublishAttempt attempt = new PublishAttempt();
            attempt.setTenantId(target.gameDesignVersionTenantKey());
            attempt.setPublishWorkflowId(workflow);
            attempt.setPublishType(PublishType.FULL_VERSION);
            attempt.setVersionId(target.gameDesignVersionRowId());
            attempt.setVersionNumber(candidateNumber);
            attempt.setRequestDigest(selected.selection().digest());
            publishAttemptRepository.save(attempt);
            return null;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
    var selection = selectedRef.get().selection();
    String workflow = workflowRef.get();

    assertThatThrownBy(
            () ->
                versionPublishCommandService.publishSelectedDraftFullVersion(
                    tenantId,
                    candidateId,
                    "missing original operation proof",
                    selection.intent().publishRequestId(),
                    workflow))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("selected Draft publication requires exact operation reconciliation");
    assertThat(publishAttemptRepository.findByPublishWorkflowId(workflow).orElseThrow().getStatus())
        .isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(
            versionRepository
                .findByTenantIdAndId(tenantId, candidateId)
                .orElseThrow()
                .getVersionState())
        .isEqualTo(VersionLifecycleState.DRAFT);
    assertThat(publishedReleaseBundleRepository.findByTenantIdAndVersionId(tenantId, candidateId))
        .isEmpty();
    assertThat(versionAssetArtifactRepository.findByTenantIdAndVersionId(tenantId, candidateId))
        .isEmpty();
    assertThat(new GameDesignPublicationOperationRepository(dsl).read(workflow)).isEmpty();
  }

  @Test
  void selectedDraftReservationRejectsChangedSelectionAndAccountOperationEvidence() {
    String tenantId = "9006";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("changed-original-operation-proof-game");
    gameRepository.save(game);

    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(1);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("changed original operation proof");
    candidate = versionRepository.save(candidate);
    var operation = reserveSelectedDraft(candidate, "changed original operation proof");
    var selection =
        AuthoredDraftPublishSelection.fromStored(
            operation.account().input().selection().canonicalJson(),
            operation.account().input().selection().digest());

    var changedIntent =
        new AuthoredDraftPublishSelection.PublishIntent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            selection.intent().publishRequestId(),
            selection.intent().expectedVersionStateEpoch(),
            selection.intent().notes() + " changed",
            selection.intent().selectedCommitRequestId(),
            selection.intent().selectedCommitId(),
            selection.intent().selectedCommitDigest());
    assertThatThrownBy(
            () ->
                inOwnerTransaction(
                    () ->
                        new SelectedDraftPublicationOwner(dsl)
                            .reserve(changedIntent, operation.account(), operation.world())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed notes");

    var changedAccount =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            operation.account().fenceId(),
            operation.account().input(),
            operation.account().sources());
    assertThatThrownBy(
            () ->
                inOwnerTransaction(
                    () ->
                        new SelectedDraftPublicationOwner(dsl)
                            .reserve(selection.intent(), changedAccount, operation.world())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PUBLICATION_OPERATION_IDENTITY_CONFLICT");

    var retained =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow();
    assertThat(retained.outcome()).isEqualTo("PENDING");
    assertThat(retained.operation().canonicalBytes()).containsExactly(operation.canonicalBytes());
    assertThat(
            publishAttemptRepository
                .findByPublishWorkflowId(operation.workflowId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(PublishAttemptStatus.PENDING);
  }

  @Test
  void finalizationFailureAfterExportRetainsCandidateReferencedByApprovedRemapSet() {
    String tenantId = "9003";
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
    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(2);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("failed remap proof");
    candidate = versionRepository.save(candidate);
    AtomicReference<Long> candidateVersionId = new AtomicReference<>();
    AtomicReference<Integer> candidateVersionNumber = new AtomicReference<>();
    var operation = reserveSelectedDraft(candidate, "failed remap proof");
    String publishRequestId = operation.account().publishRequestId();
    String publishWorkflowId = operation.workflowId();
    long selectedCandidateVersionId = candidate.getId();
    candidateVersionId.set(selectedCandidateVersionId);
    candidateVersionNumber.set(candidate.getVersionNumber());
    var participantDigests =
        PublishedWorldSelectorFixtures.participants(candidate.getId(), operation.world());
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
        .thenReturn(participantDigests);
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
    ExportedAssetManifest exportedManifest = immutableEmptyManifest();
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
                return exportedManifest;
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

    Throwable publishFailure =
        catchThrowable(
            () ->
                versionPublishCommandService.publishSelectedDraftFullVersion(
                    tenantId,
                    selectedCandidateVersionId,
                    "failed remap proof",
                    publishRequestId,
                    publishWorkflowId));

    assertThat(publishFailure).isInstanceOf(RuntimeException.class);
    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    String failureContext =
        "publish failure context (candidateVersionId="
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
            + ", failure="
            + publishFailure.getClass().getName()
            + ": "
            + publishFailure.getMessage()
            + ")";
    assertThat(exportCompleted.get()).as(failureContext).isTrue();
    assertThat(finalizationFailureInjected.get()).as(failureContext).isTrue();
    assertThat(exportedVersionNumber.get())
        .as("asset export uses the candidate's persisted version number")
        .isEqualTo(candidateVersionNumber.get());
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(attempt.getRevision()).isGreaterThan(1L);
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
    var terminalOperation =
        new GameDesignPublicationOperationRepository(dsl).read(publishWorkflowId).orElseThrow();
    assertThat(terminalOperation.outcome()).isEqualTo("NO_PUBLICATION");
    assertThat(terminalOperation.terminalEvidenceBytes()).isNotNull();
    publishAttemptRepository.requireNoPublicationOperation(attempt);
  }

  private static String firstStackFrame(Throwable failure) {
    if (failure == null || failure.getStackTrace().length == 0) {
      return "none";
    }
    return failure.getStackTrace()[0].toString();
  }

  private net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation
      reserveSelectedDraft(Version version, String notes) {
    TargetProof target = targetProof(version);
    return inOwnerTransaction(
        () -> {
          try {
            // Account/World values produced by this helper are isolated external fixtures; the GD
            // selection, command/policy source, attempt and publication reservation are real.
            return IsolatedPublicationOwnerSetup.retainSourceBacked(
                dsl, target, version.getVersionStateEpoch(), notes);
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static TargetProof targetProof(Version version) {
    return new TargetProof(
        version.getCanonicalTenantId(),
        version.getCanonicalVersionId(),
        version.getId(),
        version.getTenantId(),
        version.getIdentitySourceGameRowId(),
        version.getIdentitySourceGameTenantKey(),
        version.getIdentitySourceProvenanceKind());
  }

  private <T> T inOwnerTransaction(java.util.function.Supplier<T> action) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return transaction.execute(status -> action.get());
  }

  private static ExportedAssetManifest immutableEmptyManifest() {
    return new ExportedAssetManifest("sha256:" + "a".repeat(64), 1, List.of(), List.of());
  }
}
