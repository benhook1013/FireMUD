package integration.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.AssetSource;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService.CandidateBinding;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL proof of V65 candidate persistence and SQL owner/finalization boundaries. The exact
 * authenticated Account/World operation and complete selected source capture are stipulated fixture
 * inputs here; this test is not a composed-authorship or whole-release proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class SelectedAssetExportCandidatePostgresIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String CONFLICT = "SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT";
  private static final byte[] ASSET_BYTES =
      "retained ordinary source bytes".getBytes(StandardCharsets.UTF_8);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void commitsCandidateBeforeIndependentExactReadbackAndExactRetryIsIdempotent() {
    Fixture fixture = fixture(VersionLifecycleState.DRAFT);
    CandidateCase candidate = candidateCase(fixture, null, "source-a", ASSET_BYTES, null);

    fixture
        .candidates()
        .recordSelectedCandidate(
            candidate.inventory(), candidate.manifest(), candidate.manifestBytes());
    CandidateBinding independentlyRead =
        fixture.candidates().readSelectedCandidate(candidate.request());

    assertThat(independentlyRead).isEqualTo(candidate.expectedBinding());
    assertThat(storedBytes(fixture, "inventory_bytes")).containsExactly(candidate.inventoryBytes());
    assertThat(storedBytes(fixture, "manifest_bytes")).containsExactly(candidate.manifestBytes());

    fixture
        .candidates()
        .recordSelectedCandidate(
            candidate.inventory(), candidate.manifest(), candidate.manifestBytes());

    assertThat(fixture.candidates().readSelectedCandidate(candidate.request()))
        .isEqualTo(candidate.expectedBinding());
    assertThat(candidateCount(fixture)).isOne();
  }

  @Test
  void changedSourceManifestOrRequestIsDeniedWithoutRewritingRetainedCandidate() {
    Fixture fixture = fixture(VersionLifecycleState.DRAFT);
    CandidateCase original = candidateCase(fixture, null, "source-a", ASSET_BYTES, null);
    fixture
        .candidates()
        .recordSelectedCandidate(
            original.inventory(), original.manifest(), original.manifestBytes());
    byte[] retainedInventory = storedBytes(fixture, "inventory_bytes");
    byte[] retainedManifest = storedBytes(fixture, "manifest_bytes");

    CandidateCase changedSource =
        candidateCase(
            fixture,
            original.request(),
            "source-b",
            "changed retained bytes".getBytes(StandardCharsets.UTF_8),
            null);
    CandidateCase changedManifest =
        candidateCase(fixture, original.request(), "source-a", ASSET_BYTES, "changed-manifest");
    CandidateCase changedRequest =
        candidateCaseWithRequest(
            fixture,
            PublicationDigestRequestBinding.full(
                original.request().tenantId(),
                original.request().versionId(),
                "different-publish-request"),
            "source-a",
            ASSET_BYTES,
            null);

    assertConflict(
        () ->
            fixture
                .candidates()
                .recordSelectedCandidate(
                    changedSource.inventory(),
                    changedSource.manifest(),
                    changedSource.manifestBytes()));
    assertConflict(
        () ->
            fixture
                .candidates()
                .recordSelectedCandidate(
                    changedManifest.inventory(),
                    changedManifest.manifest(),
                    changedManifest.manifestBytes()));
    assertConflict(
        () ->
            fixture
                .candidates()
                .recordSelectedCandidate(
                    changedRequest.inventory(),
                    changedRequest.manifest(),
                    changedRequest.manifestBytes()));

    assertThat(candidateCount(fixture)).isOne();
    assertThat(storedBytes(fixture, "inventory_bytes")).containsExactly(retainedInventory);
    assertThat(storedBytes(fixture, "manifest_bytes")).containsExactly(retainedManifest);
    assertThat(fixture.candidates().readSelectedCandidate(original.request()))
        .isEqualTo(original.expectedBinding());
  }

  @Test
  void missingCandidateAndMismatchedOwnerOrNonDraftStateAreDenied() {
    Fixture missing = fixture(VersionLifecycleState.DRAFT);
    CandidateCase valid = candidateCase(missing, null, "source-a", ASSET_BYTES, null);
    assertThatThrownBy(() -> missing.candidates().readSelectedCandidate(valid.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SELECTED_ASSET_EXPORT_CANDIDATE_NOT_FOUND");

    TargetProof wrongCanonicalVersion =
        new TargetProof(
            valid.target().canonicalTenantId(),
            UUID.randomUUID(),
            valid.target().gameDesignVersionRowId(),
            valid.target().gameDesignVersionTenantKey(),
            valid.target().sourceGameRowId(),
            valid.target().sourceGameTenantKey(),
            valid.target().sourceProvenanceKind());
    CandidateCase mismatched =
        candidateCase(
            missing, valid.request(), "source-a", ASSET_BYTES, null, wrongCanonicalVersion);
    assertConflict(
        () ->
            missing
                .candidates()
                .recordSelectedCandidate(
                    mismatched.inventory(), mismatched.manifest(), mismatched.manifestBytes()));
    assertThat(candidateCount(missing)).isZero();

    PublicationDigestRequestBinding missingVersionRequest =
        PublicationDigestRequestBinding.full(
            valid.request().tenantId(), "999999", "candidate-missing-version");
    TargetProof missingVersionTarget =
        new TargetProof(
            valid.target().canonicalTenantId(),
            UUID.randomUUID(),
            999999,
            valid.target().gameDesignVersionTenantKey(),
            valid.target().sourceGameRowId(),
            valid.target().sourceGameTenantKey(),
            valid.target().sourceProvenanceKind());
    CandidateCase missingVersion =
        candidateCase(
            missing, missingVersionRequest, "source-a", ASSET_BYTES, null, missingVersionTarget);
    assertConflict(
        () ->
            missing
                .candidates()
                .recordSelectedCandidate(
                    missingVersion.inventory(),
                    missingVersion.manifest(),
                    missingVersion.manifestBytes()));
    assertThat(candidateCount(missing)).isZero();

    Fixture failedOwner = fixture(VersionLifecycleState.FAILED);
    CandidateCase failed = candidateCase(failedOwner, null, "source-a", ASSET_BYTES, null);
    assertConflict(
        () ->
            failedOwner
                .candidates()
                .recordSelectedCandidate(
                    failed.inventory(), failed.manifest(), failed.manifestBytes()));
    assertThat(candidateCount(failedOwner)).isZero();
  }

  @Test
  void v65OwnerTriggerRejectsWrongEpochOrCanonicalVersionAndRowsCannotBeMutated() {
    Fixture fixture = fixture(VersionLifecycleState.DRAFT);
    CandidateCase candidate = candidateCase(fixture, null, "source-a", ASSET_BYTES, null);

    assertThatThrownBy(
            () ->
                insertCandidateDirectly(
                    fixture, candidate, 2L, candidate.target().canonicalVersionId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_SCOPE_CONFLICT");
    assertThatThrownBy(() -> insertCandidateDirectly(fixture, candidate, 1L, UUID.randomUUID()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_SCOPE_CONFLICT");
    assertThat(candidateCount(fixture)).isZero();

    Fixture failedOwner = fixture(VersionLifecycleState.FAILED);
    CandidateCase failedCandidate = candidateCase(failedOwner, null, "source-a", ASSET_BYTES, null);
    assertThatThrownBy(
            () ->
                insertCandidateDirectly(
                    failedOwner,
                    failedCandidate,
                    1L,
                    failedCandidate.target().canonicalVersionId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_SCOPE_CONFLICT");
    assertThat(candidateCount(failedOwner)).isZero();

    fixture
        .candidates()
        .recordSelectedCandidate(
            candidate.inventory(), candidate.manifest(), candidate.manifestBytes());
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "UPDATE version_asset_export_candidate SET manifest_hash = ? WHERE tenant_id = ? AND version_id = ?",
                        "sha256:" + "f".repeat(64),
                        fixture.owner().getTenantId(),
                        fixture.version().getId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_IMMUTABLE");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM version_asset_export_candidate WHERE tenant_id = ? AND version_id = ?",
                        fixture.owner().getTenantId(),
                        fixture.version().getId()))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_IMMUTABLE");
    assertThatThrownBy(() -> fixture.dsl().execute("TRUNCATE version_asset_export_candidate"))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("SELECTED_ASSET_EXPORT_CANDIDATE_RETENTION_REQUIRED");
    assertThat(candidateCount(fixture)).isOne();
  }

  @Test
  void finalizationComparisonUsesExactCandidateInCallerWritableReadCommittedTransaction() {
    Fixture fixture = fixture(VersionLifecycleState.DRAFT);
    CandidateCase candidate = candidateCase(fixture, null, "source-a", ASSET_BYTES, null);
    fixture
        .candidates()
        .recordSelectedCandidate(
            candidate.inventory(), candidate.manifest(), candidate.manifestBytes());

    assertThatThrownBy(
            () ->
                fixture
                    .candidates()
                    .requireSelectedCandidateForFinalization(
                        candidate.request(), candidate.expectedBinding()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caller-owned writable READ_COMMITTED transaction");

    TransactionTemplate readOnly =
        transaction(fixture, true, TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThatThrownBy(
            () ->
                readOnly.executeWithoutResult(
                    ignored ->
                        fixture
                            .candidates()
                            .requireSelectedCandidateForFinalization(
                                candidate.request(), candidate.expectedBinding())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caller-owned writable READ_COMMITTED transaction");
    TransactionTemplate repeatableRead =
        transaction(fixture, false, TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThatThrownBy(
            () ->
                repeatableRead.executeWithoutResult(
                    ignored ->
                        fixture
                            .candidates()
                            .requireSelectedCandidateForFinalization(
                                candidate.request(), candidate.expectedBinding())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caller-owned writable READ_COMMITTED transaction");

    CandidateBinding altered =
        new CandidateBinding(
            candidate.expectedBinding().requestDigest(),
            candidate.expectedBinding().operationDigest(),
            candidate.expectedBinding().selectedCommitDigest(),
            candidate.expectedBinding().inventorySchema(),
            candidate.expectedBinding().inventoryDigest(),
            candidate.expectedBinding().manifest(),
            candidate.expectedBinding().requestPreimage(),
            "altered-inventory".getBytes(StandardCharsets.UTF_8),
            candidate.expectedBinding().manifestBytes());
    assertThatThrownBy(
            () ->
                transaction(fixture, false, TransactionDefinition.ISOLATION_READ_COMMITTED)
                    .executeWithoutResult(
                        ignored ->
                            fixture
                                .candidates()
                                .requireSelectedCandidateForFinalization(
                                    candidate.request(), altered)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONFLICT);
    assertThatThrownBy(
            () ->
                transaction(fixture, false, TransactionDefinition.ISOLATION_READ_COMMITTED)
                    .executeWithoutResult(
                        ignored ->
                            fixture
                                .candidates()
                                .requireSelectedCandidateForFinalization(
                                    PublicationDigestRequestBinding.full(
                                        candidate.request().tenantId(),
                                        candidate.request().versionId(),
                                        "changed-finalization-request"),
                                    candidate.expectedBinding())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(CONFLICT);

    transaction(fixture, false, TransactionDefinition.ISOLATION_READ_COMMITTED)
        .executeWithoutResult(
            ignored -> {
              assertThat(fixture.games().findByTenantIdForUpdate(fixture.owner().getTenantId()))
                  .isNotNull();
              var beforeRow =
                  Objects.requireNonNull(
                      fixture.dsl().fetchOne("SELECT txid_current()::text"),
                      "transaction ID query must return a row");
              String before =
                  Objects.requireNonNull(
                      beforeRow.get(0, String.class), "transaction ID query must return a value");
              fixture
                  .candidates()
                  .requireSelectedCandidateForFinalization(
                      candidate.request(), candidate.expectedBinding());
              var afterRow =
                  Objects.requireNonNull(
                      fixture.dsl().fetchOne("SELECT txid_current()::text"),
                      "transaction ID query must return a row");
              String after =
                  Objects.requireNonNull(
                      afterRow.get(0, String.class), "transaction ID query must return a value");
              assertThat(after).isEqualTo(before);
            });
  }

  @Test
  void concurrentExactCandidateWritersConvergeOnOneCommittedReadback() throws Exception {
    Fixture fixture = fixture(VersionLifecycleState.DRAFT);
    CandidateCase candidate = candidateCase(fixture, null, "source-a", ASSET_BYTES, null);
    ExecutorService writers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          writers.submit(
              () -> {
                ready.countDown();
                start.await();
                fixture
                    .candidates()
                    .recordSelectedCandidate(
                        candidate.inventory(), candidate.manifest(), candidate.manifestBytes());
                return null;
              });
      var second =
          writers.submit(
              () -> {
                ready.countDown();
                start.await();
                fixture
                    .candidates()
                    .recordSelectedCandidate(
                        candidate.inventory(), candidate.manifest(), candidate.manifestBytes());
                return null;
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    } finally {
      writers.shutdownNow();
    }

    assertThat(candidateCount(fixture)).isOne();
    assertThat(fixture.candidates().readSelectedCandidate(candidate.request()))
        .isEqualTo(candidate.expectedBinding());
  }

  private static Fixture fixture(VersionLifecycleState state) {
    String schema = "asset_candidate_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource admin =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    DSL.using(admin, SQLDialect.POSTGRES).execute("CREATE SCHEMA " + schema);
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate ownerWrite = transactionManagerTemplate(transactionManager);
    GameRepository games = new GameRepository(dsl);
    VersionRepository versions = new VersionRepository(dsl);
    Game game =
        ownerWrite.execute(
            ignored -> {
              Game value = new Game();
              value.setTenantId(UUID.randomUUID().toString());
              value.setName("Selected asset candidate owner fixture");
              value.setDescription("Persisted Game owner for V65 candidate SQL proof");
              return games.save(value);
            });
    Version version =
        ownerWrite.execute(
            ignored -> {
              Version value = new Version();
              value.setTenantId(game.getTenantId());
              value.setVersionNumber(1);
              value.setVersionState(state);
              value.setVersionStateEpoch(1L);
              value.setNotes("Persisted Version owner for V65 candidate SQL proof");
              return versions.save(value);
            });
    var candidates =
        new VersionAssetExportCandidateServiceImpl(
            dsl, games, versions, transactionManager, new ObjectMapper());
    return new Fixture(
        schema, dataSource, dsl, transactionManager, games, versions, game, version, candidates);
  }

  private static TransactionTemplate transaction(Fixture fixture, boolean readOnly, int isolation) {
    TransactionTemplate template = new TransactionTemplate(fixture.transactionManager());
    template.setIsolationLevel(isolation);
    template.setReadOnly(readOnly);
    return template;
  }

  private static TransactionTemplate transactionManagerTemplate(
      DataSourceTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(manager);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return template;
  }

  private static CandidateCase candidateCase(
      Fixture fixture,
      PublicationDigestRequestBinding request,
      String sourceMarker,
      byte[] assetBytes,
      String manifestMarker) {
    return candidateCase(
        fixture, request, sourceMarker, assetBytes, manifestMarker, target(fixture));
  }

  private static CandidateCase candidateCase(
      Fixture fixture,
      PublicationDigestRequestBinding request,
      String sourceMarker,
      byte[] assetBytes,
      String manifestMarker,
      TargetProof target) {
    if (request == null) {
      request =
          PublicationDigestRequestBinding.full(
              fixture.owner().getCanonicalTenantId().toString(),
              Long.toString(fixture.version().getId()),
              "candidate-request-" + UUID.randomUUID());
    }
    String contentDigest = sha256(assetBytes);
    UUID sourceRevisionId = stableUuid(target, "source-revision:" + sourceMarker);
    UUID sourceRequestId = stableUuid(target, "source-request:" + sourceMarker);
    UUID sourceCommitId = stableUuid(target, "source-commit:" + sourceMarker);
    DraftCommitBinding sourceBinding =
        DraftCommitBinding.create(
            target,
            sourceRequestId,
            sourceCommitId,
            "genesis",
            List.of(
                new RevisionPayload("0", sourceRevisionId, Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
            List.of(
                new AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    "ASSET_REFERENCE",
                    target.canonicalVersionId().toString(),
                    "VERSION",
                    target.canonicalVersionId().toString(),
                    "0")));
    UUID assetRevisionId = stableUuid(target, "asset-revision:" + sourceMarker);
    SelectedDraftAssetInventory.Asset asset =
        new SelectedDraftAssetInventory.Asset(
            SelectedDraftAssetInventory.Family.ORDINARY,
            AssetSource.ROLE,
            "resource.bin",
            "1",
            "REQUIRED",
            sourceBinding,
            "0",
            assetRevisionId,
            "application/octet-stream",
            contentDigest,
            assetBytes);
    String commitDigest = sourceBinding.digest();
    var operationBytes =
        ("stipulated-operation:" + request.derivedWorkflowIdentity())
            .getBytes(StandardCharsets.UTF_8);
    UUID actorId = stableUuid(target, "stipulated-actor");
    AuthoredDraftPublishSelectionBinding.PublishIntent intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            request.publishRequestId(),
            "1",
            "stipulated authenticated intent",
            sourceRequestId,
            sourceBinding.commitId(),
            commitDigest);
    AuthoredDraftPublishSelectionBinding selection =
        AuthoredDraftPublishSelectionBinding.capture(
            intent,
            target,
            sourceBinding,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                sourceRequestId,
                sourceBinding.commitId(),
                commitDigest,
                "[]",
                OffsetDateTime.parse("2026-10-09T00:00:00Z")));
    AccountPublicationAuthorizationBinding account =
        new AccountPublicationAuthorizationBinding(
            stableUuid(target, "stipulated-account-operation"),
            stableUuid(target, "stipulated-account-fence"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actorId, selection),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT, actorId.toString(), "1", "1", null, null, new byte[] {1})));
    GameDesignPublicationOperation operation = mock(GameDesignPublicationOperation.class);
    when(operation.account()).thenReturn(account);
    when(operation.workflowId()).thenReturn(request.derivedWorkflowIdentity());
    when(operation.canonicalBytes()).thenReturn(operationBytes);

    byte[] inventoryBytes =
        canonicalJson(
            Map.ofEntries(
                Map.entry("schema", SelectedDraftAssetInventory.SCHEMA),
                Map.entry(
                    "requestPreimageBase64",
                    Base64.getEncoder().encodeToString(request.canonicalPreimage())),
                Map.entry("requestDigest", request.requestDigest()),
                Map.entry("operationBase64", Base64.getEncoder().encodeToString(operationBytes)),
                Map.entry("selectedCommitDigest", commitDigest),
                Map.entry("sourceCapture", Map.of("stipulatedFixtureMarker", sourceMarker)),
                Map.entry("assets", List.of(assetObject(asset)))));
    String inventoryDigest = sha256(inventoryBytes);
    List<PublishedArtifactDigest> digests =
        List.of(
            new PublishedArtifactDigest(
                asset.usageKey(),
                "BINARY",
                "artifacts/sha256/" + contentDigest.substring("sha256:".length()),
                contentDigest,
                asset.contentType(),
                1));
    Map<String, Object> manifestObject =
        new java.util.LinkedHashMap<>(
            Map.ofEntries(
                Map.entry("schema", "game-design-selected-asset-manifest/v1"),
                Map.entry("manifestSchemaVersion", 1),
                Map.entry("selectedInventorySchema", SelectedDraftAssetInventory.SCHEMA),
                Map.entry("selectedInventoryDigest", inventoryDigest),
                Map.entry("artifacts", List.of(artifactObject(asset, digests.getFirst())))));
    if (manifestMarker != null) {
      manifestObject.put("fixtureExtension", manifestMarker);
    }
    byte[] manifestBytes = canonicalJson(manifestObject);
    ExportedAssetManifest manifest =
        new ExportedAssetManifest(
            sha256(manifestBytes),
            1,
            digests.stream().map(PublishedArtifactDigest::usageKey).toList(),
            digests);
    SelectedDraftAssetInventory inventory = mock(SelectedDraftAssetInventory.class);
    when(inventory.request()).thenReturn(request);
    when(inventory.operation()).thenReturn(operation);
    when(inventory.selectedCommit()).thenReturn(sourceBinding);
    when(inventory.assets()).thenReturn(List.of(asset));
    when(inventory.digest()).thenReturn(inventoryDigest);
    when(inventory.canonicalBytes()).thenReturn(inventoryBytes.clone());
    CandidateBinding expected =
        new CandidateBinding(
            request.requestDigest(),
            sha256(operationBytes),
            commitDigest,
            SelectedDraftAssetInventory.SCHEMA,
            inventoryDigest,
            manifest,
            request.canonicalPreimage(),
            inventoryBytes,
            manifestBytes);
    return new CandidateCase(
        request, target, inventory, manifest, manifestBytes, inventoryBytes, expected);
  }

  private static CandidateCase candidateCaseWithRequest(
      Fixture fixture,
      PublicationDigestRequestBinding request,
      String sourceMarker,
      byte[] assetBytes,
      String manifestMarker) {
    return candidateCase(
        fixture, request, sourceMarker, assetBytes, manifestMarker, target(fixture));
  }

  private static TargetProof target(Fixture fixture) {
    return new TargetProof(
        fixture.owner().getCanonicalTenantId(),
        fixture.version().getCanonicalVersionId(),
        fixture.version().getId(),
        fixture.owner().getTenantId(),
        fixture.owner().getId(),
        fixture.owner().getTenantId(),
        "NEW_GAME_ROW");
  }

  private static UUID stableUuid(TargetProof target, String purpose) {
    return UUID.nameUUIDFromBytes(
        (target.canonicalVersionId() + ":" + purpose).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> assetObject(SelectedDraftAssetInventory.Asset asset) {
    return Map.ofEntries(
        Map.entry("family", asset.family().name()),
        Map.entry("role", asset.role()),
        Map.entry("usageKey", asset.usageKey()),
        Map.entry("assetRowId", asset.assetRowId()),
        Map.entry("requiredness", asset.requiredness()),
        Map.entry("sourceBindingJson", asset.sourceBinding().canonicalJson()),
        Map.entry("sourceBindingDigest", asset.sourceBinding().digest()),
        Map.entry("revisionOrder", asset.revisionOrder()),
        Map.entry("revisionId", asset.revisionId().toString()),
        Map.entry("contentType", asset.contentType()),
        Map.entry("contentDigest", asset.contentDigest()),
        Map.entry("byteSize", Long.toString(asset.contentBytes().length)),
        Map.entry("contentBase64", Base64.getEncoder().encodeToString(asset.contentBytes())));
  }

  private static Map<String, Object> artifactObject(
      SelectedDraftAssetInventory.Asset asset, PublishedArtifactDigest proof) {
    return Map.ofEntries(
        Map.entry("usageKey", asset.usageKey()),
        Map.entry("family", asset.family().name()),
        Map.entry("role", asset.role()),
        Map.entry("requiredness", asset.requiredness()),
        Map.entry("artifactKind", proof.artifactKind()),
        Map.entry("objectKey", proof.immutableObjectKey()),
        Map.entry("contentDigest", proof.contentDigest()),
        Map.entry("contentType", proof.contentType()),
        Map.entry("artifactSchemaVersion", proof.artifactSchemaVersion()),
        Map.entry("byteSize", asset.contentBytes().length));
  }

  private static void insertCandidateDirectly(
      Fixture fixture, CandidateCase candidate, long expectedEpoch, UUID canonicalVersionId) {
    fixture
        .dsl()
        .execute(
            "INSERT INTO version_asset_export_candidate (tenant_id, version_id, canonical_tenant_id, canonical_version_id, expected_version_state_epoch, workflow_id, request_digest, request_preimage, operation_digest, selected_commit_digest, inventory_schema, inventory_digest, inventory_bytes, manifest_schema_version, manifest_hash, manifest_bytes, artifact_digests_json, required_usage_keys_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            candidate.target().gameDesignVersionTenantKey(),
            candidate.target().gameDesignVersionRowId(),
            candidate.target().canonicalTenantId(),
            canonicalVersionId,
            expectedEpoch,
            candidate.request().derivedWorkflowIdentity(),
            candidate.request().requestDigest(),
            candidate.request().canonicalPreimage(),
            candidate.expectedBinding().operationDigest(),
            candidate.expectedBinding().selectedCommitDigest(),
            candidate.expectedBinding().inventorySchema(),
            candidate.expectedBinding().inventoryDigest(),
            candidate.expectedBinding().inventoryBytes(),
            candidate.manifest().manifestSchemaVersion(),
            candidate.manifest().manifestHash(),
            candidate.manifestBytes(),
            "[]",
            "[]");
  }

  private static byte[] storedBytes(Fixture fixture, String column) {
    var row =
        Objects.requireNonNull(
            fixture
                .dsl()
                .fetchOne(
                    "SELECT "
                        + column
                        + " FROM version_asset_export_candidate WHERE tenant_id = ? AND version_id = ?",
                    fixture.owner().getTenantId(),
                    fixture.version().getId()),
            "candidate bytes query must return a row");
    return Objects.requireNonNull(row.get(0, byte[].class), "candidate bytes query returned null");
  }

  private static long candidateCount(Fixture fixture) {
    var row =
        Objects.requireNonNull(
            fixture.dsl().fetchOne("SELECT count(*) FROM version_asset_export_candidate"),
            "candidate count query must return a row");
    return Objects.requireNonNull(row.get(0, Long.class), "candidate count query returned null");
  }

  private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOf(IllegalStateException.class).hasMessage(CONFLICT);
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(value));
    } catch (Exception invalid) {
      throw new IllegalStateException(
          "Candidate integration fixture did not canonicalize", invalid);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      DataSourceTransactionManager transactionManager,
      GameRepository games,
      VersionRepository versions,
      Game owner,
      Version version,
      VersionAssetExportCandidateService candidates) {}

  private record CandidateCase(
      PublicationDigestRequestBinding request,
      TargetProof target,
      SelectedDraftAssetInventory inventory,
      ExportedAssetManifest manifest,
      byte[] manifestBytes,
      byte[] inventoryBytes,
      CandidateBinding expectedBinding) {
    private CandidateCase {
      manifestBytes = manifestBytes.clone();
      inventoryBytes = inventoryBytes.clone();
    }

    @Override
    public byte[] manifestBytes() {
      return manifestBytes.clone();
    }

    @Override
    public byte[] inventoryBytes() {
      return inventoryBytes.clone();
    }
  }
}
