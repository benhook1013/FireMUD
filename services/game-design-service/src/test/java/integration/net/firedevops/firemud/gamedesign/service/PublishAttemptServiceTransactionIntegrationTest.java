package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.account.v1.AccountPublicationAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationResponse;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadGrpcCodec;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadClient;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.publication.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationGrpcCodec;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignSelectedDraftPublicationReadGrpcService;
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
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationTerminalReadService;
import net.firedevops.firemud.gamedesign.publication.IsolatedPublicationOperationFixtures;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationCommandService;
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
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeRequest;
import net.firedevops.firemud.worldmanagement.v1.BeginVersionPublicationFreezeResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedStartLocationReadServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldSelectedDraftPublicationFreezeServiceGrpc;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  private static final String NAMESPACE = "test";
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
  void reconciledFullVersionPublicationCommitsAttemptVersionAndReleaseBundleTogether(
      @TempDir Path temporary) throws Exception {
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
    var terminalPki = new SelectionReadTestPki(Files.createDirectories(temporary.resolve("pki")));
    assertTerminalOwnerReadOverMtls(operation, null, terminalPki);
    assertThat(candidate.getTenantId()).isEqualTo(tenantId);
    assertThat(candidate.getTenantId()).isNotEqualTo(candidate.getCanonicalTenantId().toString());
    assertThat(operation.workflowId())
        .isEqualTo(
            PublicationDigestRequestBinding.full(
                    candidate.getCanonicalTenantId().toString(),
                    Long.toString(candidate.getId()),
                    operation.account().publishRequestId())
                .derivedWorkflowIdentity());
    var selection =
        AuthoredDraftPublishSelection.fromStored(
            operation.account().input().selection().canonicalJson(),
            operation.account().input().selection().digest());
    var exactReservationRetry =
        inOwnerTransaction(
            () ->
                new SelectedDraftPublicationOwner(dsl)
                    .reserve(
                        selection.intent(),
                        operation.account(),
                        operation.world(),
                        operation.inventory()));
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
    Mockito.when(
            publishGateService.collectSelectedFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(
                    PublicationDigestRequestBinding.full(
                        candidate.getCanonicalTenantId().toString(),
                        Long.toString(candidate.getId()),
                        publishRequestId)),
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
    assertTerminalOwnerReadOverMtls(operation, Outcome.PUBLISHED, terminalPki);
  }

  /**
   * Exercises the real GD admission, owner reservation, and finalizer. The Account HELD response,
   * World freeze/selector, original Draft outcomes, participant digests, and empty export are
   * explicitly stipulated test fixtures; this is not genuine upstream production proof.
   */
  @Test
  void selectedDraftCompositionUsesAuthenticatedOwnerReadsAndReconcilesExactV2Release(
      @TempDir Path temporary) throws Exception {
    String tenantId = "9010";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("selected-command-composition-proof-game");
    gameRepository.save(game);

    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(1);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("selected command composition proof");
    candidate = versionRepository.save(candidate);
    TargetProof target = targetProof(candidate);
    long candidateEpoch = candidate.getVersionStateEpoch();
    var prepared =
        inOwnerTransaction(
            () -> {
              try {
                return IsolatedPublicationOwnerSetup.selectSourceBackedDraftWithWorld(
                    dsl, target, candidateEpoch, "selected command composition proof");
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var selectionSnapshot = prepared.selection();
    var selection = selectionSnapshot.selection();
    var operation =
        IsolatedPublicationOperationFixtures.forSelection(
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest()),
            prepared.world());
    assertThat(operation.account().input().selection().canonicalBytes())
        .containsExactly(selection.canonicalBytes());
    assertThat(operation.world().selectorReceiptBytes())
        .containsExactly(prepared.world().selectorReceiptBytes());
    assertThat(operation.world().originalAccountBindingBytes())
        .containsExactly(prepared.world().originalAccountBindingBytes());
    assertThat(operation.world().appliedResultBytes())
        .containsExactly(prepared.world().appliedResultBytes());
    var projectedWorldRequest = operation.world().request();
    assertThat(projectedWorldRequest.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(projectedWorldRequest.canonicalTenantId())
        .isEqualTo(selection.intent().canonicalTenantId());
    assertThat(projectedWorldRequest.canonicalVersionId())
        .isEqualTo(selection.intent().canonicalVersionId());
    assertThat(projectedWorldRequest.intakeRequestId())
        .isEqualTo(prepared.world().request().intakeRequestId());
    assertThat(projectedWorldRequest.publicationRequestId())
        .isEqualTo(selection.intent().publishRequestId());
    assertThat(projectedWorldRequest.requestDigest())
        .isEqualTo(selection.digest().substring("sha256:".length()));
    assertThat(projectedWorldRequest.versionStateEpoch())
        .isEqualTo(Long.parseLong(selection.intent().expectedVersionStateEpoch()));
    String expectedWorkflowId =
        PublicationDigestRequestBinding.full(
                selection.intent().canonicalTenantId().toString(),
                Long.toString(selection.target().gameDesignVersionRowId()),
                selection.intent().publishRequestId())
            .derivedWorkflowIdentity();
    assertThat(projectedWorldRequest.publishWorkflowId()).isEqualTo(expectedWorkflowId);
    assertThat(projectedWorldRequest.appliedCommitId())
        .isEqualTo(selection.intent().selectedCommitId().toString());
    assertThat(projectedWorldRequest.contentDigest())
        .isEqualTo(prepared.world().request().contentDigest());
    assertThat(projectedWorldRequest.worldAffectedTuples())
        .isEqualTo(prepared.world().request().worldAffectedTuples());

    String workflowId = operation.workflowId();
    String publishRequestId = operation.account().publishRequestId();
    String publishedWorldEvidenceJson =
        new String(operation.world().canonicalBytes(), StandardCharsets.UTF_8);
    var participantDigests =
        PublishedWorldSelectorFixtures.participants(candidate.getId(), operation.world());
    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(workflowId)))
        .thenReturn(participantDigests);
    Mockito.when(
            publishGateService.collectSelectedFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(
                    PublicationDigestRequestBinding.full(
                        candidate.getCanonicalTenantId().toString(),
                        Long.toString(candidate.getId()),
                        publishRequestId)),
                Mockito.eq(workflowId)))
        .thenReturn(participantDigests);
    Mockito.when(assetExportService.exportAssets(tenantId, 1)).thenReturn(immutableEmptyManifest());

    var pki = new SelectionReadTestPki(Files.createDirectories(temporary.resolve("pki")));
    var publicationReadIdentities = pki.publicationReadIdentities();
    var heldDenied = new AtomicBoolean(true);
    var accountEndpoint =
        new StipulatedAccountPublicationReadEndpoint(operation.account(), heldDenied);
    var worldEndpoint = new StipulatedWorldPublicationReadEndpoint(operation.world());
    var freezeEndpoint = new StipulatedWorldFreezeEndpoint(operation);
    Server accountServer =
        publicationOwnerServer(publicationReadIdentities.accountServer(), pki, accountEndpoint);
    Server worldServer =
        publicationOwnerServer(
            publicationReadIdentities.worldManagementServer(), pki, worldEndpoint, freezeEndpoint);
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
    endpoints.setWorldManagementService("127.0.0.1:" + worldServer.getPort());
    try {
      try (var accountClient =
              new AccountPublicationAuthorizationReadClient(
                  endpoints,
                  publicationReadIdentities.gameDesignClient().properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE);
          var freezeClient =
              new WorldSelectedDraftPublicationFreezeClient(
                  endpoints,
                  publicationReadIdentities.gameDesignClient().properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE);
          var inventoryClient =
              new net.firedevops.firemud.common.publication
                  .WorldSelectedPublicationArtifactInventoryClient(
                  endpoints,
                  publicationReadIdentities.gameDesignClient().properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE);
          var worldClient =
              new WorldPublishedStartLocationClient(
                  endpoints,
                  publicationReadIdentities.gameDesignClient().properties(pki.ca),
                  new GrpcChannelFactory(),
                  NAMESPACE)) {
        accountClient.init();
        freezeClient.init();
        inventoryClient.init();
        worldClient.init();
        var command =
            new SelectedDraftPublicationCommandService(
                dsl,
                transactionManager,
                accountClient,
                freezeClient,
                inventoryClient,
                worldClient,
                NAMESPACE,
                versionPublishCommandService);

        assertSelectionReadCode(
            Status.Code.FAILED_PRECONDITION,
            () -> command.publishSelectedDraftFullVersion(selection.intent(), operation.account()));
        assertThat(accountEndpoint.readCount()).isEqualTo(1);
        assertThat(worldEndpoint.readCount()).isZero();
        assertThat(freezeEndpoint.beginCount()).isZero();
        assertThat(publishAttemptRepository.findByPublishWorkflowId(workflowId)).isEmpty();
        assertThat(new GameDesignPublicationOperationRepository(dsl).read(workflowId)).isEmpty();
        assertThat(
                new AuthoredDraftPublishSelectionRepository(
                        dsl, new DraftCommitCoordinatorRepository(dsl))
                    .read(
                        selection.intent().canonicalTenantId(),
                        selection.intent().canonicalVersionId(),
                        selection.intent().publishRequestId())
                    .orElseThrow()
                    .selection()
                    .canonicalBytes())
            .containsExactly(selection.canonicalBytes());
        assertThat(
                versionRepository
                    .findByTenantIdAndId(tenantId, candidate.getId())
                    .orElseThrow()
                    .getVersionState())
            .isEqualTo(VersionLifecycleState.DRAFT);
        assertThat(
                publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                    tenantId, candidate.getId()))
            .isEmpty();
        assertThat(
                versionAssetArtifactRepository.findByTenantIdAndVersionId(
                    tenantId, candidate.getId()))
            .isEmpty();
        assertThat(versionCountForTenant(tenantId)).isEqualTo(1L);

        heldDenied.set(false);
        // World has acknowledged its stipulated freeze, but the subsequent selector response is
        // unavailable. No GD reservation exists; retry must send the exact same Begin request.
        worldEndpoint.unavailable.set(true);
        assertSelectionReadCode(
            Status.Code.UNAVAILABLE,
            () -> command.publishSelectedDraftFullVersion(selection.intent(), operation.account()));
        assertThat(freezeEndpoint.beginCount()).isEqualTo(1);
        assertThat(new GameDesignPublicationOperationRepository(dsl).read(workflowId)).isEmpty();
        assertThat(publishAttemptRepository.findByPublishWorkflowId(workflowId)).isEmpty();
        worldEndpoint.unavailable.set(false);
        long versionCountBeforePublish = versionCountForTenant(tenantId);
        VersionDto published =
            command.publishSelectedDraftFullVersion(selection.intent(), operation.account());
        assertThat(published.id()).isEqualTo(candidate.getId());
        assertThat(versionCountForTenant(tenantId)).isEqualTo(versionCountBeforePublish);
        assertThat(accountEndpoint.readCount()).isEqualTo(3);
        assertThat(worldEndpoint.readCount()).isEqualTo(2);
        assertThat(freezeEndpoint.beginCount()).isEqualTo(2);
        assertThat(freezeEndpoint.inventoryReadCount()).isEqualTo(2);

        PublishAttempt attempt =
            publishAttemptRepository.findByPublishWorkflowId(workflowId).orElseThrow();
        Version storedVersion =
            versionRepository.findByTenantIdAndId(tenantId, candidate.getId()).orElseThrow();
        PublishedReleaseBundle bundle =
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(tenantId, candidate.getId())
                .orElseThrow();
        VersionAssetArtifact artifact =
            versionAssetArtifactRepository
                .findByTenantIdAndVersionId(tenantId, candidate.getId())
                .orElseThrow();
        var retainedOperation =
            new GameDesignPublicationOperationRepository(dsl).read(workflowId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.SUCCEEDED);
        assertThat(attempt.getVersionId()).isEqualTo(candidate.getId());
        assertThat(storedVersion.getVersionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
        assertThat(bundle.getAttestationSchemaVersion()).isEqualTo("v2");
        assertThat(bundle.getWorldPublishedStartLocationEvidenceJson())
            .isEqualTo(publishedWorldEvidenceJson);
        assertThat(bundle.getPublishWorkflowId()).isEqualTo(workflowId);
        assertThat(artifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);
        assertThat(retainedOperation.operation().canonicalBytes())
            .containsExactly(operation.canonicalBytes());
        assertThat(retainedOperation.outcome()).isEqualTo("PUBLISHED");
        assertThat(retainedOperation.terminalEvidenceBytes()).isNotEmpty();
        publishAttemptRepository.requirePublishedOperation(attempt);

        assertTerminalOwnerReadOverMtls(operation, Outcome.PUBLISHED, pki);
        byte[] terminalBeforeChangedRetry = retainedOperation.terminalEvidenceBytes();
        long attemptIdBeforeRetry = attempt.getId();
        long attemptRevisionBeforeRetry = attempt.getRevision();
        Long bundleIdBeforeRetry = bundle.getId();
        String releaseRefBeforeRetry = bundle.getPublishedReleaseBundleRef();
        VersionDto exactRetry =
            command.publishSelectedDraftFullVersion(selection.intent(), operation.account());
        assertThat(freezeEndpoint.inventoryReadCount()).isEqualTo(2);
        assertThat(freezeEndpoint.beginCount()).isEqualTo(2);
        assertThat(accountEndpoint.readCount()).isEqualTo(3);
        assertThat(worldEndpoint.readCount()).isEqualTo(2);
        assertThat(exactRetry.id()).isEqualTo(published.id());
        assertThat(
                publishAttemptRepository.findByPublishWorkflowId(workflowId).orElseThrow().getId())
            .isEqualTo(attemptIdBeforeRetry);
        assertThat(
                publishAttemptRepository
                    .findByPublishWorkflowId(workflowId)
                    .orElseThrow()
                    .getRevision())
            .isEqualTo(attemptRevisionBeforeRetry);
        var bundleAfterExactRetry =
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(tenantId, candidate.getId())
                .orElseThrow();
        assertThat(bundleAfterExactRetry.getId()).isEqualTo(bundleIdBeforeRetry);
        assertThat(bundleAfterExactRetry.getPublishedReleaseBundleRef())
            .isEqualTo(releaseRefBeforeRetry);
        var operationAfterExactRetry =
            new GameDesignPublicationOperationRepository(dsl).read(workflowId).orElseThrow();
        assertThat(operationAfterExactRetry.operation().canonicalBytes())
            .containsExactly(operation.canonicalBytes());
        assertThat(operationAfterExactRetry.terminalEvidenceBytes())
            .containsExactly(terminalBeforeChangedRetry);
        assertThat(
                dsl.fetchSingle(
                        "SELECT COUNT(*) FROM published_release_bundle WHERE tenant_id = ?",
                        tenantId)
                    .get(0, Long.class))
            .isEqualTo(1L);
        assertThat(
                dsl.fetchSingle(
                        "SELECT COUNT(*) FROM publish_attempt WHERE publish_workflow_id = ?",
                        workflowId)
                    .get(0, Long.class))
            .isEqualTo(1L);
        assertThat(accountEndpoint.readCount()).isEqualTo(3);
        assertThat(worldEndpoint.readCount()).isEqualTo(2);
        assertThat(freezeEndpoint.beginCount()).isEqualTo(2);
        assertThat(versionCountForTenant(tenantId)).isEqualTo(versionCountBeforePublish);

        var changedAccount =
            new AccountPublicationAuthorizationBinding(
                UUID.randomUUID(), operation.account().fenceId(),
                operation.account().input(), operation.account().sources());
        assertThatThrownBy(
                () -> command.publishSelectedDraftFullVersion(selection.intent(), changedAccount))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LOCAL_IDENTITY_CONFLICT");
        var retainedAfterChangedRetry =
            new GameDesignPublicationOperationRepository(dsl).read(workflowId).orElseThrow();
        assertThat(retainedAfterChangedRetry.operation().canonicalBytes())
            .containsExactly(operation.canonicalBytes());
        assertThat(retainedAfterChangedRetry.terminalEvidenceBytes())
            .containsExactly(terminalBeforeChangedRetry);
        assertThat(accountEndpoint.readCount()).isEqualTo(3);
        assertThat(worldEndpoint.readCount()).isEqualTo(2);
        assertThat(freezeEndpoint.beginCount()).isEqualTo(2);
        assertThat(versionCountForTenant(tenantId)).isEqualTo(versionCountBeforePublish);
      }
    } finally {
      shutdownServer(accountServer);
      shutdownServer(worldServer);
    }
  }

  @Test
  void selectedDraftPublicationRemainsPendingWithoutItsOriginalOperation() throws Exception {
    String tenantId = "9007";
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
  void selectedDraftReservationRejectsChangedSelectionAndAccountOperationEvidence()
      throws Exception {
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
                            .reserve(
                                changedIntent,
                                operation.account(),
                                operation.world(),
                                operation.inventory())))
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
                            .reserve(
                                selection.intent(),
                                changedAccount,
                                operation.world(),
                                operation.inventory())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("World public inventory differs from exact selected Account order");

    var retained =
        new GameDesignPublicationOperationRepository(dsl)
            .read(operation.workflowId())
            .orElseThrow();
    assertThat(retained.outcome()).isEqualTo("PENDING");
    assertThat(retained.operation().canonicalBytes()).containsExactly(operation.canonicalBytes());
    // Real owner SQL rejects partial/unknown profiles even when the forged payload has a fresh
    // matching digest. Upstream inventory authority is still expressly stipulated in this fixture.
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    for (int mutation = 0; mutation < 5; mutation++) {
      var publicJson =
          (tools.jackson.databind.node.ObjectNode)
              mapper.readTree(operation.inventory().canonicalBytes());
      var model = (tools.jackson.databind.node.ObjectNode) publicJson.get("sourceModel");
      String expectedRejectionMessage;
      switch (mutation) {
        case 0 -> {
          ((tools.jackson.databind.node.ObjectNode) publicJson.get("ownerScope"))
              .remove("intakeReceiptDigest");
          expectedRejectionMessage = "Incomplete or unknown public inventory members";
        }
        case 1 -> {
          model.put("unknownSourceFamily", true);
          expectedRejectionMessage = "Incomplete or unknown public inventory members";
        }
        case 2 -> {
          model.putArray("familyCounts");
          expectedRejectionMessage = "Unsupported public inventory source profile";
        }
        case 3 -> {
          ((tools.jackson.databind.node.ObjectNode) model.get("regionGeneratorInputs").get(0))
              .put("generatorType", "opaque");
          expectedRejectionMessage = "Unsupported or unordered region generator input";
        }
        case 4 -> {
          ((tools.jackson.databind.node.ObjectNode) model.get("familyCounts").get(2))
              .put("rowCount", 0);
          expectedRejectionMessage = "Source inventory counts differ from explicit inputs";
        }
        default -> throw new AssertionError();
      }
      byte[] changedInventory =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              mapper.writeValueAsString(publicJson));
      var bytes = new java.io.ByteArrayOutputStream();
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          bytes, GameDesignPublicationOperationBinding.SCHEMA);
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          bytes, operation.account().canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          bytes, operation.world().canonicalBytes());
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          bytes, changedInventory);
      net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
          bytes,
          "sha256:"
              + java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256").digest(changedInventory)));
      assertThatThrownBy(
              () ->
                  dsl.fetch(
                      "SELECT require_selected_inventory_operation_v2(?)", bytes.toByteArray()))
          .rootCause()
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining(expectedRejectionMessage)
          .satisfies(
              failure ->
                  assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("23514"));
    }
    dsl.fetch("SELECT require_selected_inventory_operation_v2(?)", operation.canonicalBytes());
    // Supply the historical three-frame schema without retrofitting any retained owner rows.
    // This is actual policy-table ingress denial, not proof that a genuine v1 row was migrated.
    var historicalV1 = new java.io.ByteArrayOutputStream();
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
        historicalV1, "game-design-publication-operation/v1");
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
        historicalV1, operation.account().canonicalBytes());
    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.frame(
        historicalV1, operation.world().canonicalBytes());
    var policySetsBefore =
        dsl.fetch(
                "SELECT * FROM game_design_published_realm_policy_set ORDER BY canonical_tenant_id, canonical_version_id")
            .intoMaps();
    var policiesBefore =
        dsl.fetch(
                "SELECT * FROM game_design_published_realm_policy ORDER BY canonical_tenant_id, canonical_version_id, ordinal")
            .intoMaps();
    assertThatThrownBy(
            () ->
                inOwnerTransaction(
                    () ->
                        dsl.execute(
                            "INSERT INTO game_design_published_realm_policy_set (operation_bytes) VALUES (?)",
                            historicalV1.toByteArray())))
        .rootCause()
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("Published realm policy evidence requires immutable v2 operation")
        .satisfies(
            failure ->
                assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("23514"));
    // JDBC creates new byte[] values on readback; compare every retained value by content.
    assertThat(
            dsl.fetch(
                    "SELECT * FROM game_design_published_realm_policy_set ORDER BY canonical_tenant_id, canonical_version_id")
                .intoMaps())
        .usingRecursiveComparison()
        .isEqualTo(policySetsBefore);
    assertThat(
            dsl.fetch(
                    "SELECT * FROM game_design_published_realm_policy ORDER BY canonical_tenant_id, canonical_version_id, ordinal")
                .intoMaps())
        .usingRecursiveComparison()
        .isEqualTo(policiesBefore);
    assertThat(
            new GameDesignPublicationOperationRepository(dsl)
                .read(operation.workflowId())
                .orElseThrow()
                .operation()
                .canonicalBytes())
        .containsExactly(operation.canonicalBytes());
    assertThat(
            publishAttemptRepository
                .findByPublishWorkflowId(operation.workflowId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(PublishAttemptStatus.PENDING);
  }

  @Test
  void finalizationFailureAfterExportRetainsCandidateReferencedByApprovedRemapSet(
      @TempDir Path temporary) throws Exception {
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
    var terminalPki = new SelectionReadTestPki(Files.createDirectories(temporary.resolve("pki")));
    assertTerminalOwnerReadOverMtls(operation, null, terminalPki);
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
    Mockito.when(
            publishGateService.collectSelectedFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(
                    PublicationDigestRequestBinding.full(
                        candidate.getCanonicalTenantId().toString(),
                        Long.toString(candidate.getId()),
                        publishRequestId)),
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
    assertTerminalOwnerReadOverMtls(operation, Outcome.NO_PUBLICATION, terminalPki);
  }

  /**
   * Real PostgreSQL selection and production loopback mTLS owner read. Original Account/World
   * outcomes are stipulated by the isolated setup; this does not prove full cross-owner APPLIED
   * provenance, deployed custody, current DRAFT authority, publication, or runtime activation.
   */
  @Test
  void retainedSelectedDraftOwnerReadOverMtlsReplaysExactlyAndDeniesSubstitution(
      @TempDir Path temporary) throws Exception {
    String tenantId = "9009";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("selected-draft-mtls-proof-game");
    gameRepository.save(game);
    Version candidate = new Version();
    candidate.setTenantId(tenantId);
    candidate.setVersionNumber(1);
    candidate.setVersionState(VersionLifecycleState.DRAFT);
    candidate.setVersionStateEpoch(1L);
    candidate.setNotes("selected Draft mTLS proof");
    candidate = versionRepository.save(candidate);
    TargetProof target = targetProof(candidate);
    long epoch = candidate.getVersionStateEpoch();
    var selected =
        inOwnerTransaction(
            () -> {
              try {
                // Actual GD source/fence/selection writes; remote owner outcomes remain fixture
                // inputs.
                return IsolatedPublicationOwnerSetup.selectSourceBackedDraft(
                        dsl, target, epoch, "selected Draft mTLS proof")
                    .selection();
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var original =
        AuthoredDraftPublishSelectionBinding.fromStored(
            selected.canonicalJson(), selected.digest());
    var request = AuthoredDraftPublishSelectionReadEvidence.Request.create("test", original);
    var repository =
        new AuthoredDraftPublishSelectionRepository(dsl, new DraftCommitCoordinatorRepository(dsl));
    int selectionRowsBefore =
        dsl.fetchCount(org.jooq.impl.DSL.table("game_design_authored_draft_publish_selection"));
    var retainedBefore =
        java.util.Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT * FROM game_design_authored_draft_publish_selection"
                        + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                    target.canonicalTenantId(),
                    target.canonicalVersionId()))
            .intoMap();
    var pki = new SelectionReadTestPki(Files.createDirectories(temporary.resolve("pki")));
    var endpoint = new GameDesignSelectedDraftPublicationReadGrpcService(repository, "test");
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        pki.server.certificate().toFile(), pki.server.key().toFile())
                    .trustManager(pki.ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(endpoint, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (var identity : List.of(pki.account, pki.worldManagement)) {
        try (var client = selectionReadClient(server.getPort(), identity, pki.ca)) {
          var first = client.read(request);
          var retry = client.read(request);
          assertThat(first.request()).isEqualTo(request);
          assertThat(retry.request()).isEqualTo(first.request());
          assertThat(first.request().originalSelection())
              .containsExactly(selected.canonicalBytes());
          assertThat(first.request().selectionDigest()).isEqualTo(selected.digest());
          var freshRead =
              AuthoredDraftPublishSelectionReadEvidence.Request.create("test", original);
          assertThat(client.read(freshRead).request()).isEqualTo(freshRead);
          var intent = original.intent();
          var changed = selectionReadVariant(original, intent.publishRequestId(), "changed notes");
          var missing =
              selectionReadVariant(original, UUID.randomUUID().toString(), intent.notes());
          assertSelectionReadCode(
              Status.Code.FAILED_PRECONDITION,
              () ->
                  client.read(
                      AuthoredDraftPublishSelectionReadEvidence.Request.create("test", changed)));
          assertSelectionReadCode(
              Status.Code.NOT_FOUND,
              () ->
                  client.read(
                      AuthoredDraftPublishSelectionReadEvidence.Request.create("test", missing)));
          assertThat(client.read(request).request()).isEqualTo(request);
        }
      }
      for (var identity : List.of(pki.wrongWorkload, pki.otherNamespace, pki.otherWorldNamespace)) {
        try (var client = selectionReadClient(server.getPort(), identity, pki.ca)) {
          assertSelectionReadCode(Status.Code.PERMISSION_DENIED, () -> client.read(request));
        }
      }
      var retainedAfter =
          java.util.Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT * FROM game_design_authored_draft_publish_selection"
                          + " WHERE canonical_tenant_id = ? AND canonical_version_id = ?",
                      target.canonicalTenantId(),
                      target.canonicalVersionId()))
              .intoMap();
      assertThat(retainedAfter).isEqualTo(retainedBefore);
      assertThat(
              dsl.fetchCount(
                  org.jooq.impl.DSL.table("game_design_authored_draft_publish_selection")))
          .isEqualTo(selectionRowsBefore);
      assertThat(
              repository
                  .read(
                      target.canonicalTenantId(),
                      target.canonicalVersionId(),
                      original.intent().publishRequestId())
                  .orElseThrow()
                  .selection()
                  .canonicalBytes())
          .containsExactly(selected.canonicalBytes());
      assertThat(
              versionRepository
                  .findByTenantIdAndId(tenantId, target.gameDesignVersionRowId())
                  .orElseThrow()
                  .getVersionState())
          .isEqualTo(VersionLifecycleState.DRAFT);
      assertThat(
              publishedReleaseBundleRepository.findByTenantIdAndVersionId(
                  tenantId, target.gameDesignVersionRowId()))
          .isEmpty();
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /**
   * Real GD PostgreSQL terminal backing and production loopback mTLS transport. Upstream Account,
   * World, participant digests and asset export remain stipulated by the surrounding owner case;
   * this does not prove cross-owner issuance/APPLIED provenance, settlement, custody or activation.
   * A null expected outcome checks the actual reserved PENDING row before finalization.
   */
  private void assertTerminalOwnerReadOverMtls(
      GameDesignPublicationOperation operation, Outcome expected, SelectionReadTestPki pki)
      throws Exception {
    var repository = new GameDesignPublicationOperationRepository(dsl);
    var before = repository.read(operation.workflowId()).orElseThrow();
    assertThat(before.operation().canonicalBytes()).containsExactly(operation.canonicalBytes());
    assertThat(before.outcome()).isEqualTo(expected == null ? "PENDING" : expected.name());
    byte[] originalTerminal = before.terminalEvidenceBytes();
    if (expected != null) {
      assertThat(originalTerminal).isNotEmpty();
      // Existing immutability guards must reject backing replacement; do not disable them to
      // manufacture corrupt historical evidence. This write transaction ends before any RPC.
      assertThatThrownBy(
              () ->
                  inOwnerTransaction(
                      () ->
                          dsl.execute(
                              "UPDATE game_design_publication_operation SET terminal_evidence_bytes = ?"
                                  + " WHERE publish_workflow_id = ?",
                              new byte[] {1},
                              operation.workflowId())))
          .rootCause()
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("immutable")
          .satisfies(
              failure ->
                  assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("23514"));
    }
    var binding = GameDesignPublicationOperationBinding.fromStored(operation.canonicalBytes());
    var request = GameDesignPublicationTerminalReadEvidence.Request.create("test", binding);
    var changedAccount =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            operation.account().input(),
            operation.account().sources());
    var substituted =
        new GameDesignPublicationOperationBinding(
            changedAccount,
            operation.world(),
            net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures.stipulated(
                changedAccount, operation.world()));
    var missing =
        IsolatedPublicationOperationFixtures.fresh(
            operation.account().input().selection().target());
    assertThat(repository.read(missing.workflowId())).isEmpty();
    var endpoint =
        new GameDesignPublicationTerminalReadGrpcService(
            new GameDesignPublicationTerminalReadService(repository, transactionManager, "test"),
            "test");
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(
                        pki.server.certificate().toFile(), pki.server.key().toFile())
                    .trustManager(pki.ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(ServerInterceptors.intercept(endpoint, new GrpcPeerIdentityInterceptor()))
            .build()
            .start();
    try {
      for (var identity : List.of(pki.account, pki.worldManagement)) {
        try (var client = terminalReadClient(server.getPort(), identity, pki.ca)) {
          if (expected == null) {
            assertSelectionReadCode(Status.Code.FAILED_PRECONDITION, () -> client.read(request));
          } else {
            var first = client.read(request);
            var retry = client.read(request);
            assertThat(first.request()).isEqualTo(request);
            assertThat(retry.request()).isEqualTo(request);
            assertThat(first.request().originalOperation())
                .containsExactly(operation.canonicalBytes());
            assertThat(first.terminalEvidence().operationBytes())
                .containsExactly(operation.canonicalBytes());
            assertThat(first.terminalEvidence().canonicalBytes()).containsExactly(originalTerminal);
            assertThat(retry.terminalEvidence().canonicalBytes()).containsExactly(originalTerminal);
            assertThat(first.terminalEvidence().outcome()).isEqualTo(expected);
            var fresh = GameDesignPublicationTerminalReadEvidence.Request.create("test", binding);
            var freshResult = client.read(fresh);
            assertThat(freshResult.request()).isEqualTo(fresh);
            assertThat(freshResult.terminalEvidence().canonicalBytes())
                .containsExactly(originalTerminal);
          }
          assertSelectionReadCode(
              Status.Code.NOT_FOUND,
              () ->
                  client.read(
                      GameDesignPublicationTerminalReadEvidence.Request.create(
                          "test",
                          GameDesignPublicationOperationBinding.fromStored(
                              missing.canonicalBytes()))));
          assertSelectionReadCode(
              Status.Code.FAILED_PRECONDITION,
              () ->
                  client.read(
                      GameDesignPublicationTerminalReadEvidence.Request.create(
                          "test", substituted)));
        }
      }
      for (var identity : List.of(pki.wrongWorkload, pki.otherNamespace, pki.otherWorldNamespace)) {
        try (var client = terminalReadClient(server.getPort(), identity, pki.ca)) {
          assertSelectionReadCode(Status.Code.PERMISSION_DENIED, () -> client.read(request));
        }
      }
      // A strict client cannot construct malformed input. Use an authenticated test-only wire
      // stub to exercise the actual receiver's closed-schema rejection, without minting evidence.
      var channel =
          new GrpcChannelFactory()
              .buildChannel(
                  "127.0.0.1:" + server.getPort(), 6565, pki.account.properties(pki.ca), true);
      try {
        var stub =
            GameDesignPublicationTerminalReadServiceGrpc.newBlockingStub(channel)
                .withCallCredentials(
                    new GrpcServerPeerIdentityCallCredentials(
                        "spiffe://firemud/ns/test/sa/game-design-service"))
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        var wire = GameDesignPublicationTerminalReadGrpcCodec.toRequest(request);
        for (var invalid :
            List.of(
                wire.toBuilder()
                    .setOriginalOperation(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
                    .build(),
                wire.toBuilder()
                    .setUnknownFields(
                        com.google.protobuf.UnknownFieldSet.newBuilder()
                            .addField(
                                99,
                                com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                    .addVarint(1)
                                    .build())
                            .build())
                    .build())) {
          assertSelectionReadCode(
              Status.Code.INVALID_ARGUMENT, () -> stub.readPublicationTerminal(invalid));
        }
        assertSelectionReadCode(
            Status.Code.PERMISSION_DENIED,
            () ->
                stub.readPublicationTerminal(wire.toBuilder().setTargetNamespace("other").build()));
      } finally {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      var after = repository.read(operation.workflowId()).orElseThrow();
      assertThat(after.operation().canonicalBytes())
          .containsExactly(before.operation().canonicalBytes());
      assertThat(after.outcome()).isEqualTo(before.outcome());
      assertThat(after.receiptBytes()).isEqualTo(before.receiptBytes());
      assertThat(after.terminalEvidenceBytes()).isEqualTo(originalTerminal);
    } finally {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Server publicationOwnerServer(
      SelectionReadTestIdentity identity, SelectionReadTestPki pki, BindableService... endpoints)
      throws Exception {
    var builder =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                    .trustManager(pki.ca.toFile())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build());
    for (var endpoint : endpoints) {
      builder.addService(ServerInterceptors.intercept(endpoint, new GrpcPeerIdentityInterceptor()));
    }
    return builder.build().start();
  }

  private static boolean requireGameDesignPeer(StreamObserver<?> observer) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(
          Status.UNAUTHENTICATED
              .withDescription("Verified workload required")
              .asRuntimeException());
      return false;
    }
    if (!peer.uri().equals("spiffe://firemud/ns/test/sa/game-design-service")) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Exact Game Design peer required")
              .asRuntimeException());
      return false;
    }
    return true;
  }

  private static void shutdownServer(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private long versionCountForTenant(String tenantId) {
    return dsl.fetchSingle("SELECT COUNT(*) FROM version WHERE tenant_id = ?", tenantId)
        .get(0, Long.class);
  }

  /** Test-only HELD endpoint; it accepts only the exact stipulated Account binding. */
  private static final class StipulatedAccountPublicationReadEndpoint
      extends AccountPublicationAuthorizationReadServiceGrpc
          .AccountPublicationAuthorizationReadServiceImplBase {
    private final byte[] expectedBinding;
    private final AtomicBoolean heldDenied;
    private final AtomicInteger reads = new AtomicInteger();

    private StipulatedAccountPublicationReadEndpoint(
        AccountPublicationAuthorizationBinding expectedBinding, AtomicBoolean heldDenied) {
      this.expectedBinding = expectedBinding.canonicalBytes();
      this.heldDenied = heldDenied;
    }

    @Override
    public void readHeldPublicationAuthorization(
        ReadHeldPublicationAuthorizationRequest request,
        StreamObserver<ReadHeldPublicationAuthorizationResponse> observer) {
      if (!requireGameDesignPeer(observer)) return;
      reads.incrementAndGet();
      final AccountPublicationAuthorizationReadEvidence.Request decoded;
      try {
        decoded = AccountPublicationAuthorizationReadGrpcCodec.fromRequest(request);
      } catch (IllegalArgumentException invalid) {
        observer.onError(
            Status.INVALID_ARGUMENT
                .withDescription("Invalid stipulated read request")
                .asRuntimeException());
        return;
      }
      if (!java.util.Arrays.equals(decoded.binding().canonicalBytes(), expectedBinding)) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Different stipulated Account binding")
                .asRuntimeException());
        return;
      }
      if (!NAMESPACE.equals(decoded.targetNamespace())) {
        observer.onError(
            Status.PERMISSION_DENIED
                .withDescription("Different stipulated Account namespace")
                .asRuntimeException());
        return;
      }
      if (heldDenied.get()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Stipulated Account order is not HELD")
                .asRuntimeException());
        return;
      }
      observer.onNext(AccountPublicationAuthorizationReadGrpcCodec.toHeldResponse(decoded));
      observer.onCompleted();
    }

    int readCount() {
      return reads.get();
    }
  }

  /** Test-only freeze RPC: upstream World state and checkpoint are expressly stipulated. */
  private static final class StipulatedWorldFreezeEndpoint
      extends WorldSelectedDraftPublicationFreezeServiceGrpc
          .WorldSelectedDraftPublicationFreezeServiceImplBase {
    private final GameDesignPublicationOperation operation;
    private final AtomicInteger begins = new AtomicInteger();
    private final AtomicInteger inventoryReads = new AtomicInteger();

    private StipulatedWorldFreezeEndpoint(GameDesignPublicationOperation operation) {
      this.operation = operation;
    }

    @Override
    public void beginVersionPublicationFreeze(
        BeginVersionPublicationFreezeRequest request,
        StreamObserver<BeginVersionPublicationFreezeResponse> observer) {
      if (!requireGameDesignPeer(observer)) return;
      begins.incrementAndGet();
      try {
        var decoded = WorldSelectedDraftPublicationFreezeGrpcCodec.fromRequest(request);
        var world = operation.world().request();
        var expected =
            WorldSelectedDraftPublicationFreezeEvidence.Request.create(
                NAMESPACE,
                world.canonicalTenantId(),
                world.canonicalVersionId(),
                world.publicationRequestId(),
                world.versionStateEpoch(),
                world.requestDigest(),
                operation.account());
        if (!expected.equals(decoded)) {
          observer.onError(
              Status.FAILED_PRECONDITION
                  .withDescription("Different stipulated World freeze request")
                  .asRuntimeException());
          return;
        }
        var acknowledgement =
            new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
                decoded,
                world.intakeRequestId(),
                world.versionStateEpoch(),
                world.publicationFence(),
                WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
                world.appliedCommitId(),
                world.contentDigest(),
                world.digestSchemaVersion());
        observer.onNext(WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
        observer.onCompleted();
      } catch (IllegalArgumentException invalid) {
        observer.onError(
            Status.INVALID_ARGUMENT
                .withDescription("Invalid stipulated freeze request")
                .asRuntimeException());
      }
    }

    @Override
    public void readSelectedPublicationArtifactInventory(
        net.firedevops.firemud.worldmanagement.v1.ReadSelectedPublicationArtifactInventoryRequest
            request,
        StreamObserver<
                net.firedevops.firemud.worldmanagement.v1
                    .ReadSelectedPublicationArtifactInventoryResponse>
            observer) {
      if (!requireGameDesignPeer(observer)) return;
      inventoryReads.incrementAndGet();
      var freeze =
          net.firedevops.firemud.common.publication
              .WorldSelectedPublicationArtifactInventoryGrpcCodec.fromRequest(request);
      if (!freeze.request().equals(operation.inventory().freezeEvidence().request())
          || !freeze
              .acknowledgement()
              .equals(operation.inventory().freezeEvidence().acknowledgement())) {
        observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        return;
      }
      observer.onNext(
          net.firedevops.firemud.common.publication
              .WorldSelectedPublicationArtifactInventoryGrpcCodec.toResponse(
              operation.inventory()));
      observer.onCompleted();
    }

    int inventoryReadCount() {
      return inventoryReads.get();
    }

    int beginCount() {
      return begins.get();
    }
  }

  /** Test-only selector endpoint; it returns only the exact stipulated World evidence. */
  private static final class StipulatedWorldPublicationReadEndpoint
      extends WorldPublishedStartLocationReadServiceGrpc
          .WorldPublishedStartLocationReadServiceImplBase {
    private final WorldPublishedStartLocationEvidence expectedEvidence;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicBoolean unavailable = new AtomicBoolean(false);

    private StipulatedWorldPublicationReadEndpoint(
        WorldPublishedStartLocationEvidence expectedEvidence) {
      this.expectedEvidence = expectedEvidence;
    }

    @Override
    public void readWorldPublishedStartLocation(
        ReadWorldPublishedStartLocationRequest request,
        StreamObserver<ReadWorldPublishedStartLocationResponse> observer) {
      if (!requireGameDesignPeer(observer)) return;
      reads.incrementAndGet();
      final WorldPublishedStartLocationEvidence.Request decoded;
      try {
        decoded = WorldPublishedStartLocationGrpcCodec.fromRequest(request);
      } catch (IllegalArgumentException invalid) {
        observer.onError(
            Status.INVALID_ARGUMENT
                .withDescription("Invalid stipulated selector request")
                .asRuntimeException());
        return;
      }
      if (!expectedEvidence.request().equals(decoded)) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Different stipulated World selector")
                .asRuntimeException());
        return;
      }
      if (unavailable.get()) {
        observer.onError(
            Status.UNAVAILABLE
                .withDescription("Stipulated selector unavailable")
                .asRuntimeException());
        return;
      }
      observer.onNext(WorldPublishedStartLocationGrpcCodec.toResponse(decoded, expectedEvidence));
      observer.onCompleted();
    }

    int readCount() {
      return reads.get();
    }
  }

  private static GameDesignPublicationTerminalReadClient terminalReadClient(
      int port, SelectionReadTestIdentity identity, Path ca) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + port);
    var client =
        new GameDesignPublicationTerminalReadClient(
            endpoints, identity.properties(ca), new GrpcChannelFactory(), "test");
    try {
      client.init();
      return client;
    } catch (Exception failure) {
      client.close();
      throw failure;
    }
  }

  private static AuthoredDraftPublishSelectionBinding selectionReadVariant(
      AuthoredDraftPublishSelectionBinding original, String publishRequestId, String notes) {
    var intent = original.intent();
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            intent.canonicalTenantId(),
            intent.canonicalVersionId(),
            publishRequestId,
            intent.expectedVersionStateEpoch(),
            notes,
            intent.selectedCommitRequestId(),
            intent.selectedCommitId(),
            intent.selectedCommitDigest()),
        original.target(),
        original.selectedCommit(),
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            original.target(),
            original.fenceRequestId(),
            original.fenceCommitId(),
            original.fenceInputDigest(),
            original.fenceResultVectorJson(),
            java.time.OffsetDateTime.parse(original.fenceCreatedAt())));
  }

  private static AuthoredDraftPublishSelectionReadClient selectionReadClient(
      int port, SelectionReadTestIdentity identity, Path ca) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("127.0.0.1:" + port);
    var client =
        new AuthoredDraftPublishSelectionReadClient(
            endpoints, identity.properties(ca), new GrpcChannelFactory(), "test");
    try {
      client.init();
      return client;
    } catch (Exception failure) {
      client.close();
      throw failure;
    }
  }

  private static void assertSelectionReadCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure -> assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected));
  }

  private record SelectionReadTestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var properties = new CommonGrpcClientProperties();
      properties.setPlaintext(false);
      properties.setCertChain(certificate.toString());
      properties.setPrivateKey(key.toString());
      properties.setCaCert(ca.toString());
      return properties;
    }
  }

  /** Ephemeral keytool PKI, following Account's existing loopback test; no runtime credentials. */
  private static final class SelectionReadTestPki {
    private static final String PASSWORD = "test-only-publication-mtls-password";
    private final Path root;
    private final Path caStore;
    final Path ca;
    final SelectionReadTestIdentity server,
        account,
        worldManagement,
        wrongWorkload,
        otherNamespace,
        otherWorldNamespace;

    SelectionReadTestPki(Path root) throws Exception {
      this.root = root;
      this.caStore = root.resolve("ca.p12");
      runKeytool(
          "-genkeypair",
          "-alias",
          "test-ca",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=Game Design selected Draft read test CA",
          "-validity",
          "30",
          "-ext",
          "BC=ca:true",
          "-ext",
          "KU=keyCertSign,cRLSign",
          "-storetype",
          "PKCS12",
          "-keystore",
          caStore.toString(),
          "-storepass",
          PASSWORD,
          "-keypass",
          PASSWORD);
      ca = root.resolve("ca.crt");
      runKeytool(
          "-exportcert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          ca.toString(),
          "-rfc");
      server = issue(root, caStore, "game-design-server", "test", "game-design-service", true);
      account = issue(root, caStore, "account-client", "test", "account-service", false);
      worldManagement =
          issue(root, caStore, "world-client", "test", "world-management-service", false);
      wrongWorkload =
          issue(root, caStore, "game-session-client", "test", "game-session-service", false);
      otherNamespace =
          issue(root, caStore, "other-account-client", "other-test", "account-service", false);
      otherWorldNamespace =
          issue(
              root, caStore, "other-world-client", "other-test", "world-management-service", false);
    }

    PublicationReadIdentities publicationReadIdentities() throws Exception {
      return new PublicationReadIdentities(
          issue(root, caStore, "game-design-client", "test", "game-design-service", false),
          issue(root, caStore, "account-server", "test", "account-service", true),
          issue(
              root, caStore, "world-management-server", "test", "world-management-service", true));
    }

    private record PublicationReadIdentities(
        SelectionReadTestIdentity gameDesignClient,
        SelectionReadTestIdentity accountServer,
        SelectionReadTestIdentity worldManagementServer) {}

    private static SelectionReadTestIdentity issue(
        Path root, Path caStore, String alias, String namespace, String workload, boolean server)
        throws Exception {
      Path store = root.resolve(alias + ".p12"),
          request = root.resolve(alias + ".csr"),
          certificate = root.resolve(alias + ".crt");
      String san =
          "URI:spiffe://firemud/ns/"
              + namespace
              + "/sa/"
              + workload
              + ",DNS:localhost,IP:127.0.0.1";
      String eku = server ? "serverAuth" : "clientAuth";
      runKeytool(
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-dname",
          "CN=" + alias,
          "-validity",
          "30",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + eku,
          "-ext",
          "SAN=" + san,
          "-storetype",
          "PKCS12",
          "-keystore",
          store.toString(),
          "-storepass",
          PASSWORD,
          "-keypass",
          PASSWORD);
      runKeytool(
          "-certreq",
          "-alias",
          alias,
          "-keystore",
          store.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-file",
          request.toString(),
          "-ext",
          "SAN=" + san);
      runKeytool(
          "-gencert",
          "-alias",
          "test-ca",
          "-keystore",
          caStore.toString(),
          "-storetype",
          "PKCS12",
          "-storepass",
          PASSWORD,
          "-infile",
          request.toString(),
          "-outfile",
          certificate.toString(),
          "-validity",
          "30",
          "-rfc",
          "-ext",
          "BC=ca:false",
          "-ext",
          "KU=digitalSignature,keyEncipherment",
          "-ext",
          "EKU=" + eku,
          "-ext",
          "SAN=" + san);
      var keyStore = KeyStore.getInstance("PKCS12");
      try (var input = Files.newInputStream(store)) {
        keyStore.load(input, PASSWORD.toCharArray());
      }
      byte[] privateKey =
          java.util.Objects.requireNonNull(
              java.util.Objects.requireNonNull(
                      keyStore.getKey(alias, PASSWORD.toCharArray()),
                      "Expected ephemeral test private key")
                  .getEncoded(),
              "Expected encoded ephemeral test private key");
      Path key = root.resolve(alias + ".key");
      String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(privateKey);
      Files.writeString(
          key,
          "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
          StandardCharsets.US_ASCII);
      java.util.Arrays.fill(privateKey, (byte) 0);
      return new SelectionReadTestIdentity(certificate, key);
    }

    private static void runKeytool(String... arguments) throws Exception {
      Path keytool =
          Path.of(
              System.getProperty("java.home"),
              "bin",
              System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows")
                  ? "keytool.exe"
                  : "keytool");
      var command = new ArrayList<String>();
      command.add(keytool.toString());
      command.addAll(List.of(arguments));
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Ephemeral test certificate generation timed out");
      }
      try (var output = process.getInputStream()) {
        String details = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0)
          throw new IllegalStateException("Test keytool failed: " + details);
      }
    }
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
