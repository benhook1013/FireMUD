package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.temporal.FiremudWorkflowIds;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection.PublishIntent;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository.SelectionSnapshot;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.VisibilityFence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishAttemptParticipantDigest;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflow;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
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
import org.springframework.test.util.ReflectionTestUtils;
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
  private static final String TRANSACTION_MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String FINALIZATION_MANIFEST_HASH = "sha256:" + "b".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
  @Autowired private VersionMapper versionMapper;
  @Autowired private VersionAssetExportCandidateService versionAssetExportCandidateService;
  @Autowired private VersionAssetPublicationService versionAssetPublicationService;
  @Autowired private VersionTemplateRemapSetRepository templateRemapSetRepository;
  @Autowired private DSLContext dsl;
  @MockitoBean private AuthoredDraftPublishSelectionRepository authoredSelectionRepository;
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
                      bundle.setManifestHash(TRANSACTION_MANIFEST_HASH);
                      bundle.setManifestSchemaVersion(1);
                      bundle.setGenerationConfigRevision("transaction-proof-generation");
                      bundle.setRequiredManifestAssetKeysJson("[]");
                      bundle.setArtifactDigestsJson("[]");
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
                      // This manually inserted row proves transaction rollback, not export.
                      // No durable source snapshot exists in this fixture, so candidate proof
                      // remains explicitly absent rather than bypassing its required FK.
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
  void recordsAndIndependentlyReadsDedicatedAbilitySchemaDigestOnPublishAttempt() {
    String tenantId = "9010";
    String publishWorkflowId = "full-version-ability-schema-integration-test";
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("ability-schema-persistence-proof-game");
    gameRepository.save(game);

    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(17);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("ability schema participant persistence proof");
    Version savedVersion = versionRepository.save(version);
    String scopeValue = String.valueOf(savedVersion.getId());

    PublishAttempt attempt = new PublishAttempt();
    attempt.setTenantId(tenantId);
    attempt.setPublishWorkflowId(publishWorkflowId);
    attempt.setPublishType(PublishType.FULL_VERSION);
    attempt.setStatus(PublishAttemptStatus.PENDING);
    attempt.setVersionId(savedVersion.getId());
    attempt.setVersionNumber(savedVersion.getVersionNumber());
    PublishAttempt savedAttempt = publishAttemptRepository.save(attempt);

    publishAttemptService.recordFullVersionParticipantDigests(
        publishWorkflowId,
        List.of(
            new PublishParticipantDigestDto(
                "GAME_LOGIC",
                scopeValue,
                null,
                "commit-full-version-17",
                "sha256:aggregate-game-logic-1",
                1,
                "sha256:ability-schema-1",
                null,
                null),
            new PublishParticipantDigestDto(
                "GAME_DESIGN_CONTROL_PLANE",
                scopeValue,
                null,
                "commit-full-version-17",
                "sha256:aggregate-control-plane-1",
                1,
                null,
                null,
                null),
            new PublishParticipantDigestDto(
                "WORLD_MANAGEMENT",
                scopeValue,
                null,
                "commit-full-version-17",
                "sha256:aggregate-world-1",
                1,
                null,
                null,
                null),
            new PublishParticipantDigestDto(
                "ENTITY_MANAGEMENT",
                scopeValue,
                null,
                "commit-full-version-17",
                "sha256:aggregate-entity-1",
                1,
                null,
                null,
                null),
            new PublishParticipantDigestDto(
                "AUTOMATION_SCRIPTING",
                scopeValue,
                null,
                "commit-full-version-17",
                "sha256:aggregate-automation-1",
                1,
                null,
                null,
                null)));

    List<PublishAttemptParticipantDigest> persistedDigests =
        new PublishAttemptParticipantDigestRepository(dsl)
            .findByPublishAttemptId(savedAttempt.getId());

    assertThat(savedAttempt.getPublishType()).isEqualTo(PublishType.FULL_VERSION);
    assertThat(savedAttempt.getVersionId()).isEqualTo(savedVersion.getId());
    assertThat(persistedDigests).hasSize(5);
    PublishAttemptParticipantDigest gameLogicDigest =
        persistedDigests.stream()
            .filter(digest -> digest.getParticipantKey() == PublishParticipantKey.GAME_LOGIC)
            .findFirst()
            .orElseThrow();
    assertThat(gameLogicDigest.getParticipantKey()).isEqualTo(PublishParticipantKey.GAME_LOGIC);
    assertThat(gameLogicDigest.getScopeValue()).isEqualTo(scopeValue);
    assertThat(gameLogicDigest.getBaseVersionId()).isNull();
    assertThat(gameLogicDigest.getAppliedCommitId()).isEqualTo("commit-full-version-17");
    assertThat(gameLogicDigest.getDigestSchemaVersion()).isEqualTo(1);
    assertThat(gameLogicDigest.getContentDigest()).isEqualTo("sha256:aggregate-game-logic-1");
    assertThat(gameLogicDigest.getAbilitySchemaDigest()).isEqualTo("sha256:ability-schema-1");
    assertThat(gameLogicDigest.getAbilitySchemaDigest())
        .isNotEqualTo(gameLogicDigest.getContentDigest());

    assertThat(persistedDigests)
        .allSatisfy(
            digest -> {
              assertThat(digest.getScopeValue()).isEqualTo(scopeValue);
              assertThat(digest.getBaseVersionId()).isNull();
              assertThat(digest.getAppliedCommitId()).isEqualTo("commit-full-version-17");
              assertThat(digest.getDigestSchemaVersion()).isEqualTo(1);
            });
    assertThat(persistedDigests)
        .filteredOn(digest -> digest.getParticipantKey() != PublishParticipantKey.GAME_LOGIC)
        .allSatisfy(digest -> assertThat(digest.getAbilitySchemaDigest()).isNull());
  }

  @Test
  void reconciledFullVersionPublicationCommitsAttemptVersionAndReleaseBundleTogether() {
    SelectedDraftFixture fixture =
        createSelectedDraftFixture(
            "successful-transaction-proof-game", 1, "successful transaction proof");
    String tenantId = fixture.tenantId();
    String publishRequestId = fixture.publishRequestId();
    String publishWorkflowId = fixture.publishWorkflowId();

    Mockito.when(
            publishGateService.collectFullVersionParticipantDigests(
                Mockito.any(VersionDto.class),
                Mockito.eq(publishRequestId),
                Mockito.eq(publishWorkflowId)))
        .thenAnswer(
            invocation -> {
              VersionDto version = invocation.getArgument(0);
              return net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures.participants(
                  version.id(), fixture.operation().world());
            });
    Mockito.when(assetExportService.exportAssets(tenantId, 1))
        .thenAnswer(
            invocation -> {
              int versionNumber = invocation.getArgument(1);
              versionAssetPublicationService.freezeOrReadSnapshot(tenantId, versionNumber);
              return versionAssetExportCandidateService.recordExportCandidate(
                  tenantId,
                  versionNumber,
                  new ExportedAssetManifest(FINALIZATION_MANIFEST_HASH, 1, List.of(), List.of()));
            });

    Object publication = invokeSelectedPublicationMechanics(fixture);
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(publication, "isSucceeded")).isTrue();
    assertThat((Long) ReflectionTestUtils.invokeMethod(publication, "versionId"))
        .isEqualTo(fixture.version().getId());

    PublishAttempt attempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    Version storedVersion =
        versionRepository.findByTenantIdAndId(tenantId, fixture.version().getId()).orElseThrow();
    PublishedReleaseBundle bundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
            .orElseThrow();
    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
            .orElseThrow();

    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.SUCCEEDED);
    assertThat(attempt.getVersionId()).isEqualTo(fixture.version().getId());
    assertThat(attempt.getRequestDigest()).isEqualTo(fixture.selection().digest());
    assertThat(storedVersion.getVersionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
    assertThat(bundle.getPublishWorkflowId()).isEqualTo(publishWorkflowId);
    assertThat(bundle.getManifestHash()).isEqualTo(FINALIZATION_MANIFEST_HASH);
    assertThat(bundle.getPublishedReleaseBundleRef()).isNotBlank();
    assertThat(bundle.getPublishedReleaseBundleRef())
        .isNotEqualTo(
            "release-bundle:" + tenantId + ":" + fixture.version().getId() + ":" + bundle.getId());
    PublishedReleaseBundle durableBundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
            .orElseThrow();
    assertThat(durableBundle.getPublishedReleaseBundleRef())
        .isEqualTo(bundle.getPublishedReleaseBundleRef());
    PublishedReleaseBundle changedReference =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
            .orElseThrow();
    changedReference.setPublishedReleaseBundleRef("replacement-opaque-release-reference");
    assertThatThrownBy(() -> publishedReleaseBundleRepository.save(changedReference))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");
    PublishedReleaseBundle changedTuple =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
            .orElseThrow();
    changedTuple.setManifestHash("changed-manifest");
    assertThatThrownBy(() -> publishedReleaseBundleRepository.save(changedTuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Published release bundle is immutable");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE published_release_bundle "
                        + "SET published_release_bundle_ref = ? WHERE id = ?",
                    "replacement-opaque-release-reference",
                    durableBundle.getId()))
        .hasStackTraceContaining("published release bundle attestation is immutable");
    assertThat(
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
                .orElseThrow()
                .getPublishedReleaseBundleRef())
        .isEqualTo(bundle.getPublishedReleaseBundleRef());
    assertThat(
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
                .orElseThrow()
                .getManifestHash())
        .isEqualTo(FINALIZATION_MANIFEST_HASH);
    String generationConfigRevision = bundle.getGenerationConfigRevision();
    assertThat(generationConfigRevision.length()).isGreaterThan(128);
    Object replay = invokeSelectedPublicationReplay(fixture);
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(replay, "isSucceeded")).isTrue();
    assertThat(
            publishedReleaseBundleRepository
                .findByTenantIdAndVersionId(tenantId, fixture.version().getId())
                .orElseThrow()
                .getGenerationConfigRevision())
        .isEqualTo(generationConfigRevision);
    Mockito.verify(assetExportService, Mockito.times(1)).exportAssets(tenantId, 1);
    assertThat(artifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);
  }

  @Test
  void finalizationFailureAfterExportRetainsCandidateReferencedByApprovedRemapSet() {
    Game game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("failed-remap-proof-game");
    Game savedGame = gameRepository.save(game);
    String tenantId = savedGame.getTenantId();

    Version sourceVersion = new Version();
    sourceVersion.setTenantId(tenantId);
    sourceVersion.setVersionNumber(1);
    sourceVersion.setVersionState(VersionLifecycleState.PUBLISHED);
    sourceVersion.setVersionStateEpoch(2L);
    sourceVersion.setNotes("remap source");
    sourceVersion = versionRepository.save(sourceVersion);
    long sourceVersionId = sourceVersion.getId();
    SelectedDraftFixture fixture = createSelectedDraftFixture(savedGame, 2, "failed remap proof");
    String publishRequestId = fixture.publishRequestId();
    String publishWorkflowId = fixture.publishWorkflowId();
    AtomicReference<Long> candidateVersionId = new AtomicReference<>();
    AtomicReference<Integer> candidateVersionNumber = new AtomicReference<>();
    AtomicReference<Integer> exportedVersionNumber = new AtomicReference<>();
    AtomicReference<String> remapSetId = new AtomicReference<>();
    AtomicReference<Throwable> recordedDigestFailure = new AtomicReference<>();
    AtomicReference<Throwable> exportCallbackFailure = new AtomicReference<>();
    AtomicBoolean exportCompleted = new AtomicBoolean();
    AtomicBoolean finalizationFailureInjected = new AtomicBoolean();
    String longFailureMessage = "forced finalization failure " + "x".repeat(600);

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
              return net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures.participants(
                  candidate.id(), fixture.operation().world());
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
    ExportedAssetManifest exportedManifest =
        new ExportedAssetManifest(FINALIZATION_MANIFEST_HASH, 1, List.of(), List.of());
    Mockito.when(assetExportService.exportAssets(Mockito.eq(tenantId), Mockito.anyInt()))
        .thenAnswer(
            invocation -> {
              exportedVersionNumber.set(invocation.getArgument(1));
              try {
                versionAssetPublicationService.freezeOrReadSnapshot(
                    tenantId, exportedVersionNumber.get());
                ExportedAssetManifest recordedManifest =
                    versionAssetExportCandidateService.recordExportCandidate(
                        tenantId, exportedVersionNumber.get(), exportedManifest);
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
                return recordedManifest;
              } catch (Throwable failure) {
                exportCallbackFailure.set(failure);
                throw failure;
              }
            });
    Mockito.doAnswer(
            invocation -> {
              finalizationFailureInjected.set(true);
              throw new IllegalStateException(longFailureMessage);
            })
        .when(versionAssetArtifactService)
        .markPublished(
            Mockito.eq(tenantId),
            Mockito.anyLong(),
            Mockito.anyLong(),
            Mockito.eq(publishWorkflowId),
            Mockito.eq(exportedManifest.manifestHash()));

    Object failedPublication = invokeSelectedPublicationMechanics(fixture);
    assertThat((Boolean) ReflectionTestUtils.invokeMethod(failedPublication, "isSucceeded"))
        .isFalse();
    assertThat((String) ReflectionTestUtils.invokeMethod(failedPublication, "failureMessage"))
        .isEqualTo(longFailureMessage);
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
            + failedPublication.getClass().getName()
            + ": "
            + ReflectionTestUtils.invokeMethod(failedPublication, "failureMessage")
            + ")";
    assertThat(exportCompleted.get()).as(failureContext).isTrue();
    assertThat(finalizationFailureInjected.get()).as(failureContext).isTrue();
    assertThat(exportedVersionNumber.get())
        .as("asset export uses the candidate's persisted version number")
        .isEqualTo(candidateVersionNumber.get());
    assertThat(attempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(attempt.getFailureMessage()).isEqualTo(longFailureMessage);
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
    assertThat(failedArtifact.getStateEpoch()).isEqualTo(3L);
    assertThat(failedArtifact.getLastErrorMessage()).isEqualTo(longFailureMessage);
    assertThat(failedArtifact.getManifestHash()).isEqualTo(FINALIZATION_MANIFEST_HASH);
    assertThat(failedArtifact.getManifestSchemaVersion()).isEqualTo(1);
    assertThat(failedArtifact.getArtifactDigestsJson()).isEqualTo("[]");
    assertThat(failedArtifact.getCandidateSnapshotVersionId()).isEqualTo(candidateVersionId.get());
    assertThat(failedArtifact.getPublishedObjectProofsJson())
        .contains("manifests/sha256/", FINALIZATION_MANIFEST_HASH);
    VersionTemplateRemapSet retainedRemapSet =
        templateRemapSetRepository
            .findByTenantIdAndRemapSetId(tenantId, remapSetId.get())
            .orElseThrow();
    assertThat(retainedRemapSet.getStatus()).isEqualTo(TemplateRemapSetStatus.APPROVED);
    assertThat(retainedRemapSet.getApprovedAt()).isNotNull();
    assertThat(retainedRemapSet.getSourceVersionId()).isEqualTo(sourceVersionId);
    assertThat(retainedRemapSet.getTargetVersionId()).isEqualTo(candidateVersionId.get());

    assertThatThrownBy(() -> invokeSelectedPublicationCommand(fixture))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(longFailureMessage);
    PublishAttempt replayedAttempt =
        publishAttemptRepository.findByPublishWorkflowId(publishWorkflowId).orElseThrow();
    assertThat(replayedAttempt).isEqualTo(attempt);
    assertThat(replayedAttempt.getStatus()).isEqualTo(PublishAttemptStatus.FAILED);
    assertThat(replayedAttempt.getRequestDigest()).isEqualTo(fixture.selection().digest());
    assertThat(replayedAttempt.getVersionId()).isEqualTo(candidateVersionId.get());
    assertThat(replayedAttempt.getVersionNumber()).isEqualTo(candidateVersionNumber.get());
    assertThat(replayedAttempt.getFailureCode()).isEqualTo(attempt.getFailureCode());
    assertThat(replayedAttempt.getFailureMessage()).isEqualTo(longFailureMessage);
    VersionAssetArtifact replayedArtifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(tenantId, candidateVersionId.get())
            .orElseThrow();
    assertThat(replayedArtifact).isEqualTo(failedArtifact);
    assertThat(replayedArtifact.getArtifactState()).isEqualTo(VersionAssetArtifactState.FAILED);
    assertThat(replayedArtifact.getStateEpoch()).isEqualTo(3L);
    assertThat(replayedArtifact.getLastErrorMessage()).isEqualTo(longFailureMessage);
    Mockito.verify(assetExportService, Mockito.times(1))
        .exportAssets(Mockito.eq(tenantId), Mockito.anyInt());
  }

  private SelectedDraftFixture createSelectedDraftFixture(
      String gameName, int versionNumber, String notes) {
    Game game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName(gameName);
    return createSelectedDraftFixture(gameRepository.save(game), versionNumber, notes);
  }

  /**
   * ISOLATED upstream source/freeze authority; actual GD synchronized selection and immutable
   * operation rows are retained before exercising the real selected finalizer. Runtime authority
   * and authenticated upstream production remain unproved.
   */
  private SelectedDraftFixture createSelectedDraftFixture(
      Game game, int versionNumber, String notes) {
    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setVersionNumber(versionNumber);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes(notes);
    Version savedVersion = versionRepository.save(version);

    TargetProof target =
        new TargetProof(
            game.getCanonicalTenantId(),
            savedVersion.getCanonicalVersionId(),
            savedVersion.getId(),
            savedVersion.getTenantId(),
            game.getId(),
            game.getTenantId(),
            "NEW_GAME_ROW");
    var operation = publishAttemptService.executeFullVersionTransaction(() -> {
      try {
        return net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup.retain(
            dsl, target, savedVersion.getVersionStateEpoch(), notes);
      } catch (Exception failure) { throw new IllegalStateException(failure); }
    });
    var retained = operation.account().input().selection();
    AuthoredDraftPublishSelection selection = AuthoredDraftPublishSelection.fromStored(retained.canonicalJson(), retained.digest());
    String publishRequestId = selection.intent().publishRequestId();
    Mockito.when(
            authoredSelectionRepository.readByPublishRequest(
                game.getCanonicalTenantId(), publishRequestId))
        .thenReturn(
            Optional.of(new SelectionSnapshot(selection, OffsetDateTime.now(ZoneOffset.UTC))));

    String workflowId = operation.workflowId();
    return new SelectedDraftFixture(
        game.getTenantId(), notes, publishRequestId, workflowId, savedVersion, selection, operation);
  }

  private Object invokeSelectedPublicationMechanics(SelectedDraftFixture fixture) {
    return ReflectionTestUtils.invokeMethod(
        versionPublishCommandService,
        "reconcileSelectedPublicationMechanics",
        workflowRequest(fixture));
  }

  private Object invokeSelectedPublicationReplay(SelectedDraftFixture fixture) {
    return ReflectionTestUtils.invokeMethod(
        versionPublishCommandService, "reconcileFullVersionPublish", workflowRequest(fixture));
  }

  private Object invokeSelectedPublicationCommand(SelectedDraftFixture fixture) {
    return ReflectionTestUtils.invokeMethod(
        versionPublishCommandService, "publishFullVersion", workflowRequest(fixture));
  }

  private Object workflowRequest(SelectedDraftFixture fixture) {
    try {
      Class<?> requestType =
          Class.forName("net.firedevops.firemud.gamedesign.service.impl.PublishWorkflowRequest");
      Constructor<?> constructor =
          requestType.getDeclaredConstructor(
              String.class, String.class, String.class, String.class, PublishIntent.class);
      constructor.setAccessible(true);
      return constructor.newInstance(
          fixture.tenantId(),
          fixture.notes(),
          fixture.publishRequestId(),
          fixture.publishWorkflowId(),
          fixture.selection().intent());
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Internal publication request type is unavailable", exception);
    }
  }

  private record SelectedDraftFixture(
      String tenantId,
      String notes,
      String publishRequestId,
      String publishWorkflowId,
      Version version,
      AuthoredDraftPublishSelection selection,
      net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation operation) {}

  private static String firstStackFrame(Throwable failure) {
    if (failure == null || failure.getStackTrace().length == 0) {
      return "none";
    }
    return failure.getStackTrace()[0].toString();
  }
}
