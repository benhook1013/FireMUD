package integration.net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.gamedesign.draft.IsolatedPublicationOwnerSetup;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperation;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationRepository;
import net.firedevops.firemud.gamedesign.publication.GameDesignPublicationOperationService;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetPublicationServiceImpl;
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

/** Actual GD owner DB guard proof; every upstream creator/source/freeze input is ISOLATED. */
@Testcontainers(disabledWithoutDocker = true)
class GameDesignPublicationOperationPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String ASSET_FILE_NAME = "publication-operation-logo.png";
  private static final String ASSET_CONTENT_TYPE = "image/png";
  private static final byte[] ASSET_BYTES =
      "isolated publication asset bytes".getBytes(StandardCharsets.UTF_8);
  private static final String PUBLIC_BASE_URL = "https://assets.example.invalid/assets";

  @Test
  void noPublicationReceiptReplaysAfterLostResponseAndExcludesDelayedFinalizerAndRawInsert()
      throws Exception {
    var fixture = fixture();
    noPublication(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    assertThat(original.outcome()).isEqualTo("NO_PUBLICATION");
    assertThat(fixture.service().retainVerifiedOperation(fixture.operation()).receiptBytes())
        .isEqualTo(original.receiptBytes());
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status ->
                            fixture
                                .operations()
                                .requirePending(
                                    fixture.operation().tenantKey(),
                                    fixture.operation().workflowId(),
                                    fixture.operation().versionId(),
                                    fixture.operation().selectionDigest())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEALED_OR_CHANGED");
    assertThatThrownBy(
            () -> fixture.write().executeWithoutResult(status -> rawReleaseInsert(fixture)))
        .hasMessageContaining("selected publication is absent, sealed, or changed");
    assertThat(
            fixture
                .releases()
                .findByTenantIdAndVersionId(
                    fixture.operation().tenantKey(), fixture.operation().versionId()))
        .isEmpty();
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().receiptBytes())
        .isEqualTo(original.receiptBytes());
  }

  @Test
  void publishedReceiptCannotBeDemotedByStaleFailureAndRetainsOriginalFreezeEpoch()
      throws Exception {
    var fixture = fixture();
    var staleFailure =
        fixture.attempts().findByPublishWorkflowId(fixture.operation().workflowId()).orElseThrow();
    publish(fixture);
    var original = fixture.service().readExact(fixture.operation()).orElseThrow();
    assertThat(original.outcome()).isEqualTo("PUBLISHED");
    assertThat(fixture.service().retainVerifiedOperation(fixture.operation()).receiptBytes())
        .isEqualTo(original.receiptBytes());
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndId(
                    fixture.operation().tenantKey(), fixture.operation().versionId())
                .orElseThrow()
                .getVersionStateEpoch())
        .isEqualTo(fixture.operation().world().request().versionStateEpoch() + 1);
    staleFailure.setStatus(PublishAttemptStatus.FAILED);
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(status -> fixture.attempts().save(staleFailure)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CAS_CONFLICT");
    assertThatThrownBy(() -> noPublication(fixture)).isInstanceOf(IllegalStateException.class);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().receiptBytes())
        .isEqualTo(original.receiptBytes());
  }

  @Test
  void failedAtomicCommitLeavesAttemptOperationVersionAndReleasePendingForExactRetry()
      throws Exception {
    var fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture
                    .write()
                    .executeWithoutResult(
                        status -> {
                          publishInTransaction(fixture);
                          throw new IllegalStateException(
                              "ISOLATED rollback after all owner writes");
                        }))
        .hasMessageContaining("rollback after all owner writes");
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PENDING");
    assertThat(
            fixture
                .releases()
                .findByTenantIdAndVersionId(
                    fixture.operation().tenantKey(), fixture.operation().versionId()))
        .isEmpty();
    assertThat(
            fixture
                .attempts()
                .findByPublishWorkflowId(fixture.operation().workflowId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(PublishAttemptStatus.PENDING);
    assertThat(
            fixture
                .versions()
                .findByTenantIdAndId(
                    fixture.operation().tenantKey(), fixture.operation().versionId())
                .orElseThrow()
                .getVersionState())
        .isEqualTo(VersionLifecycleState.DRAFT);
    publish(fixture);
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("PUBLISHED");
  }

  @Test
  void noPublicationSerializesAgainstAnAlreadyScheduledLatePublisher() throws Exception {
    var fixture = fixture();
    var sealed = new CountDownLatch(1);
    var releaseSeal = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var winner =
          executor.submit(
              () ->
                  fixture
                      .write()
                      .executeWithoutResult(
                          status -> {
                            noPublicationInTransaction(fixture);
                            sealed.countDown();
                            await(releaseSeal);
                          }));
      assertThat(sealed.await(10, TimeUnit.SECONDS)).isTrue();
      var loser =
          executor.submit(
              () -> {
                assertThatThrownBy(() -> publish(fixture))
                    .isInstanceOf(IllegalStateException.class);
              });
      releaseSeal.countDown();
      winner.get(10, TimeUnit.SECONDS);
      loser.get(10, TimeUnit.SECONDS);
    } finally {
      releaseSeal.countDown();
    }
    assertThat(fixture.service().readExact(fixture.operation()).orElseThrow().outcome())
        .isEqualTo("NO_PUBLICATION");
  }

  private void noPublication(Fixture fixture) {
    fixture.write().executeWithoutResult(status -> noPublicationInTransaction(fixture));
  }

  private void noPublicationInTransaction(Fixture fixture) {
    var op = fixture.operation();
    fixture
        .operations()
        .requirePending(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest());
    var attempt =
        fixture.attempts().findByPublishWorkflowIdForUpdate(op.workflowId()).orElseThrow();
    attempt.setStatus(PublishAttemptStatus.FAILED);
    attempt.setFailureCode("ISOLATED_BUSINESS_DENIAL");
    fixture.attempts().save(attempt);
    fixture
        .operations()
        .seal(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest(), false);
  }

  private void publish(Fixture fixture) {
    fixture.write().executeWithoutResult(status -> publishInTransaction(fixture));
  }

  private void publishInTransaction(Fixture fixture) {
    var op = fixture.operation();
    fixture
        .operations()
        .requirePending(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest());
    fixture.releases().save(bundle(fixture));
    var version =
        fixture
            .versions()
            .findByTenantIdAndIdForUpdate(op.tenantKey(), op.versionId())
            .orElseThrow();
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(version.getVersionStateEpoch() + 1);
    fixture.versions().save(version);
    var artifact =
        fixture
            .artifacts()
            .findByTenantIdAndVersionIdForUpdate(op.tenantKey(), op.versionId())
            .orElseThrow();
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(Math.addExact(artifact.getStateEpoch(), 1L));
    fixture.artifacts().save(artifact);
    var attempt =
        fixture.attempts().findByPublishWorkflowIdForUpdate(op.workflowId()).orElseThrow();
    attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
    fixture.attempts().save(attempt);
    fixture
        .operations()
        .seal(op.tenantKey(), op.workflowId(), op.versionId(), op.selectionDigest(), true);
  }

  private PublishedReleaseBundle bundle(Fixture fixture) {
    var op = fixture.operation();
    var bundle = new PublishedReleaseBundle();
    bundle.setTenantId(op.tenantKey());
    bundle.setVersionId(op.versionId());
    bundle.setVersionNumber(1);
    bundle.setCanonicalTenantId(op.account().tenantId());
    bundle.setCanonicalVersionId(op.world().request().canonicalVersionId());
    bundle.setAttestationSchemaVersion("v2");
    bundle.setPublishWorkflowId(op.workflowId());
    bundle.setManifestHash(fixture.candidate().manifestHash());
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson(
        new ObjectMapper().writeValueAsString(fixture.candidate().artifactDigests()));
    bundle.setRequiredManifestAssetKeysJson(
        new ObjectMapper().writeValueAsString(fixture.candidate().requiredManifestAssetKeys()));
    bundle.setCommandDefinitionsJson("[]");
    bundle.setGenerationConfigRevision("generation-1");
    bundle.setParticipantDigestsJson(
        new ObjectMapper()
            .writeValueAsString(
                PublishedWorldSelectorFixtures.participants(op.versionId(), op.world())));
    bundle.setWorldPublishedStartLocationEvidenceJson(
        new String(op.world().canonicalBytes(), StandardCharsets.UTF_8));
    return bundle;
  }

  private void rawReleaseInsert(Fixture fixture) {
    var bundle = bundle(fixture);
    fixture
        .dsl()
        .execute(
            "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, canonical_tenant_id, canonical_version_id, "
                + "published_release_bundle_ref, attestation_schema_version, publish_workflow_id, manifest_hash, generation_config_revision, manifest_schema_version, "
                + "artifact_digests_json, required_manifest_asset_keys_json, participant_digests_json, command_definitions_json, script_only, world_published_start_location_evidence_json) "
                + "VALUES (?, ?, 1, ?, ?, ?, 'v2', ?, ?, ?, 1, '[]', '[]', ?, '[]', FALSE, ?)",
            bundle.getTenantId(),
            bundle.getVersionId(),
            bundle.getCanonicalTenantId(),
            bundle.getCanonicalVersionId(),
            UUID.randomUUID().toString(),
            bundle.getPublishWorkflowId(),
            bundle.getManifestHash(),
            bundle.getGenerationConfigRevision(),
            bundle.getParticipantDigestsJson(),
            bundle.getWorldPublishedStartLocationEvidenceJson());
  }

  private Fixture fixture() throws Exception {
    String schema = "gd_pub_op_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .table("flyway_schema_history_game_design_service")
        .load()
        .migrate();
    var transactions = new DataSourceTransactionManager(source);
    var write = new TransactionTemplate(transactions);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var configuration = new org.jooq.impl.DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(
        new org.jooq.impl.DataSourceConnectionProvider(
            new TransactionAwareDataSourceProxy(source)));
    configuration.set(
        new org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider(transactions));
    var dsl = DSL.using(configuration);
    var games = new GameRepository(dsl);
    var properties = new PostgresProperties();
    properties.setSchema(schema);
    var versions = new VersionRepository(dsl, properties);
    Game game =
        write.execute(
            status -> {
              var requested = new Game();
              requested.setTenantId("ISOLATED-" + UUID.randomUUID().toString().substring(0, 8));
              requested.setName("ISOLATED publication owner");
              return games.save(requested);
            });
    Version version =
        write.execute(
            status -> {
              var requested = new Version();
              requested.setTenantId(game.getTenantId());
              requested.setVersionNumber(1);
              return versions.save(requested);
            });
    var target =
        new TargetProof(
            version.getCanonicalTenantId(),
            version.getCanonicalVersionId(),
            version.getId(),
            version.getTenantId(),
            version.getIdentitySourceGameRowId(),
            version.getIdentitySourceGameTenantKey(),
            version.getIdentitySourceProvenanceKind());
    var operation =
        write.execute(
            status -> {
              try {
                return IsolatedPublicationOwnerSetup.retain(
                    dsl, target, version.getVersionStateEpoch());
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            });
    var operations = new GameDesignPublicationOperationRepository(dsl);
    var gameAssets = new GameAssetRepository(dsl);
    var asset =
        write.execute(
            status -> {
              var requested = new GameAsset();
              requested.setTenantId(game.getTenantId());
              requested.setFileName(ASSET_FILE_NAME);
              requested.setContentType(ASSET_CONTENT_TYPE);
              requested.setData(ASSET_BYTES);
              return gameAssets.save(requested);
            });
    var publicationRepository = new VersionAssetPublicationRepository(dsl);
    var publicationService =
        new VersionAssetPublicationServiceImpl(transactions, publicationRepository);
    publicationService.associateDraftAsset(
        game.getTenantId(), version.getId(), asset.getId(), "logo");
    var snapshot =
        publicationService.freezeOrReadSnapshot(game.getTenantId(), version.getVersionNumber());
    var artifactService =
        new VersionAssetArtifactServiceImpl(
            new VersionAssetArtifactRepository(dsl),
            null,
            versions,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper());
    write.execute(
        status ->
            artifactService.stageExport(
                game.getTenantId(),
                version.getId(),
                version.getVersionNumber(),
                operation.workflowId()));
    var artifacts = new VersionAssetArtifactRepository(dsl);
    var candidateService =
        new VersionAssetExportCandidateServiceImpl(
            versions,
            games,
            new PublishAttemptRepository(dsl),
            artifacts,
            publicationService,
            transactions,
            new ObjectMapper());
    var requestedCandidate = candidate(snapshot);
    var committedCandidate =
        candidateService.recordExportCandidate(
            game.getTenantId(), version.getVersionNumber(), requestedCandidate);
    var readCandidate =
        candidateService.readExportCandidate(game.getTenantId(), version.getVersionNumber());
    assertThat(committedCandidate).isEqualTo(requestedCandidate);
    assertThat(readCandidate).isEqualTo(committedCandidate);
    return new Fixture(
        dsl,
        write,
        versions,
        new PublishAttemptRepository(dsl),
        new PublishedReleaseBundleRepository(dsl),
        artifacts,
        operations,
        new GameDesignPublicationOperationService(operations, transactions),
        operation,
        readCandidate);
  }

  private ExportedAssetManifest candidate(
      VersionAssetPublicationRepository.ExportSnapshot snapshot) {
    var source = snapshot.items().get(0);
    String contentDigest = source.contentDigest();
    String objectKey = "artifacts/sha256/" + contentDigest.substring("sha256:".length());
    var digest =
        new PublishedArtifactDigest(
            source.usageKey(), "BINARY", objectKey, contentDigest, source.contentType(), 1);
    String url = PUBLIC_BASE_URL + "/" + objectKey;
    String manifestJson =
        "{\"schemaVersion\":1,\"assets\":{\""
            + source.usageKey()
            + "\":{\"usageKey\":\""
            + source.usageKey()
            + "\",\"artifactKind\":\"BINARY\",\"immutableObjectKey\":\""
            + objectKey
            + "\",\"contentDigest\":\""
            + contentDigest
            + "\",\"contentType\":\""
            + source.contentType()
            + "\",\"artifactSchemaVersion\":1,\"producerService\":\"game-design-service\",\"versionId\":\""
            + snapshot.canonicalVersionId()
            + "\",\"url\":\""
            + url
            + "\"}}}";
    return new ExportedAssetManifest(
        sha256(manifestJson.getBytes(StandardCharsets.UTF_8)),
        1,
        List.of(source.usageKey()),
        List.of(digest));
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("ISOLATED barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate write,
      VersionRepository versions,
      PublishAttemptRepository attempts,
      PublishedReleaseBundleRepository releases,
      VersionAssetArtifactRepository artifacts,
      GameDesignPublicationOperationRepository operations,
      GameDesignPublicationOperationService service,
      GameDesignPublicationOperation operation,
      ExportedAssetManifest candidate) {}
}
