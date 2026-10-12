package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

class VersionAssetExportSnapshotIntegrationTest {
  private static final String USAGE_KEY = "logo.bin";
  private static final String CONTENT_TYPE = "application/octet-stream";
  private static final byte[] SOURCE_BYTES = {0, 1, (byte) 0x80, (byte) 0xff};

  private static final GameDesignPostgresIntegrationFixture postgres =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void rejectsWellFormedDigestThatDoesNotMatchMappedSourceBytes() {
    Fixture fixture = fixture();
    String wrongDigest = "sha256:" + "0".repeat(64);

    assertThatThrownBy(() -> insertItem(fixture, wrongDigest, CONTENT_TYPE, SOURCE_BYTES.length))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("source metadata does not match game_assets");
  }

  @Test
  void acceptsDigestOfExactMappedSourceBytes() {
    Fixture fixture = fixture();

    insertItemAndSnapshot(fixture);
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table("version_asset_export_item"),
                DSL.field("tenant_id").eq(fixture.tenantId),
                DSL.field("version_id").eq(fixture.versionId)))
        .isEqualTo(1);
  }

  @Test
  void retainsSourceTypeSizeAndExactMappingChecks() {
    Fixture wrongType = fixture();
    assertThatThrownBy(
            () -> insertItem(wrongType, sha256(SOURCE_BYTES), "image/png", SOURCE_BYTES.length))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("source metadata does not match game_assets");

    Fixture wrongSize = fixture();
    assertThatThrownBy(
            () ->
                insertItem(wrongSize, sha256(SOURCE_BYTES), CONTENT_TYPE, SOURCE_BYTES.length + 1L))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("source metadata does not match game_assets");

    Fixture wrongMapping = fixture();
    assertThatThrownBy(
            () ->
                wrongMapping.dsl.execute(
                    "INSERT INTO version_asset_export_item "
                        + "(tenant_id, version_id, usage_key, asset_id, content_type, content_hash, byte_size) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    wrongMapping.tenantId,
                    wrongMapping.versionId,
                    "other.bin",
                    wrongMapping.assetId,
                    CONTENT_TYPE,
                    sha256(SOURCE_BYTES),
                    SOURCE_BYTES.length))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("source metadata does not match game_assets");
  }

  @Test
  void requiresDraftVersionAndKeepsSnapshotItemsImmutable() {
    Fixture published = fixture();
    published.dsl.execute(
        "UPDATE version SET version_state = 'PUBLISHED' WHERE tenant_id = ? AND id = ?",
        published.tenantId,
        published.versionId);
    assertThatThrownBy(
            () -> insertItem(published, sha256(SOURCE_BYTES), CONTENT_TYPE, SOURCE_BYTES.length))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("require the exact Draft Version row");

    Fixture frozen = fixture();
    insertItemAndSnapshot(frozen);
    assertThatThrownBy(
            () ->
                frozen.dsl.execute(
                    "UPDATE version_asset_export_item SET byte_size = byte_size "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    frozen.tenantId,
                    frozen.versionId))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("snapshot items are immutable");
  }

  @Test
  void snapshotReadbackCarriesMetadataAndReadsOneExactFrozenPayload() {
    Fixture fixture = fixture();
    insertItemAndSnapshot(fixture);
    VersionAssetPublicationRepository repository =
        new VersionAssetPublicationRepository(fixture.dsl);

    VersionAssetPublicationRepository.ExportSnapshot snapshot =
        repository.readFrozenSnapshot(fixture.tenantId, 1);
    VersionAssetPublicationRepository.AssetSelection selection = snapshot.items().get(0);
    assertThat(selection.byteSize()).isEqualTo(SOURCE_BYTES.length);
    assertThat(selection.contentDigest()).isEqualTo(sha256(SOURCE_BYTES));
    assertThat(repository.readFrozenAssetBytes(fixture.tenantId, 1, selection))
        .containsExactly(SOURCE_BYTES);
  }

  @Test
  void rejectsChangedSourceBytesAfterMetadataSnapshotRead() {
    Fixture fixture = fixture();
    insertItemAndSnapshot(fixture);
    VersionAssetPublicationRepository repository =
        new VersionAssetPublicationRepository(fixture.dsl);
    VersionAssetPublicationRepository.AssetSelection selection =
        repository.readFrozenSnapshot(fixture.tenantId, 1).items().get(0);
    fixture.dsl.execute(
        "UPDATE game_assets SET data = ? WHERE tenant_id = ? AND id = ?",
        new byte[] {9, 8, 7, 6},
        fixture.tenantId,
        fixture.assetId);
    assertThatThrownBy(() -> repository.readFrozenSnapshot(fixture.tenantId, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source bytes or mapping failed verification");
    assertThatThrownBy(() -> repository.readFrozenAssetBytes(fixture.tenantId, 1, selection))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source metadata or mapping failed verification");
  }

  @Test
  void rejectsOversizedFrozenSourceFromMetadataBeforePayloadRead() {
    Fixture fixture = fixture();
    byte[] oversized = new byte[(int) VersionAssetPublicationRepository.MAX_SOURCE_ASSET_BYTES + 1];
    fixture.dsl.transaction(
        configuration -> {
          DSLContext transactionDsl = DSL.using(configuration);
          transactionDsl.execute(
              "UPDATE game_assets SET data = ? WHERE tenant_id = ? AND id = ?",
              oversized,
              fixture.tenantId,
              fixture.assetId);
          insertItem(transactionDsl, fixture, sha256(oversized), CONTENT_TYPE, oversized.length);
          transactionDsl.execute(
              "INSERT INTO version_asset_export_snapshot "
                  + "(tenant_id, version_id, canonical_tenant_id, canonical_version_id, version_number, "
                  + "captured_version_state_epoch, item_count) "
                  + "SELECT tenant_id, id, canonical_tenant_id, canonical_version_id, version_number, "
                  + "version_state_epoch, 1 FROM version WHERE tenant_id = ? AND id = ?",
              fixture.tenantId,
              fixture.versionId);
        });

    assertThatThrownBy(
            () ->
                new VersionAssetPublicationRepository(fixture.dsl)
                    .readFrozenSnapshot(fixture.tenantId, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ASSET_TOO_LARGE");
  }

  @Test
  void requiresPublicOriginWhenInsertingFirstCandidateEvidence() {
    Fixture fixture = fixture();
    insertItemAndSnapshot(fixture);

    assertThatThrownBy(() -> insertCandidate(fixture, null))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("ASSET_EXPORT_CANDIDATE_ORIGIN_REQUIRED");
  }

  @Test
  void requiresPublicOriginWhenFirstRecordingCandidateEvidenceOnArtifact() {
    Fixture fixture = fixture();
    insertItemAndSnapshot(fixture);
    insertArtifactWithoutCandidate(fixture);

    assertThatThrownBy(() -> updateCandidateEvidence(fixture, null))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("ASSET_EXPORT_CANDIDATE_ORIGIN_REQUIRED");
  }

  @Test
  void freezesCandidateOriginWhileAllowingArtifactLifecycleEpochUpdates() {
    Fixture fixture = fixture();
    insertItemAndSnapshot(fixture);
    String origin = "https://assets.example.invalid/assets";
    insertCandidate(fixture, origin);

    assertThat(
            fixture.dsl.execute(
                "UPDATE version_asset_artifact SET artifact_state = 'FAILED', "
                    + "state_epoch = state_epoch + 1 WHERE tenant_id = ? AND version_id = ?",
                fixture.tenantId,
                fixture.versionId))
        .isEqualTo(1);
    assertThat(
            Objects.requireNonNull(
                    fixture.dsl.fetchOne(
                        "SELECT candidate_public_base_url FROM version_asset_artifact "
                            + "WHERE tenant_id = ? AND version_id = ?",
                        fixture.tenantId,
                        fixture.versionId),
                    "candidate origin readback returned no row")
                .get("candidate_public_base_url", String.class))
        .isEqualTo(origin);

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE version_asset_artifact SET candidate_public_base_url = ? "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    "https://replacement.example.invalid/assets",
                    fixture.tenantId,
                    fixture.versionId))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("ASSET_EXPORT_CANDIDATE_IMMUTABLE");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE version_asset_artifact SET candidate_public_base_url = NULL "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    fixture.tenantId,
                    fixture.versionId))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("ASSET_EXPORT_CANDIDATE_IMMUTABLE");
  }

  @Test
  void retainedPreV50CandidateWithoutOriginAllowsLifecycleUpdateButCannotBeBackfilled() {
    Fixture fixture = fixture(MigrationVersion.fromVersion("49.1"));
    insertItemAndSnapshot(fixture);
    insertLegacyCandidateWithoutOrigin(fixture);
    migrateToLatest(fixture);

    assertThat(
            fixture.dsl.execute(
                "UPDATE version_asset_artifact SET artifact_state = 'FAILED', "
                    + "state_epoch = state_epoch + 1 WHERE tenant_id = ? AND version_id = ?",
                fixture.tenantId,
                fixture.versionId))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE version_asset_artifact SET candidate_public_base_url = ? "
                        + "WHERE tenant_id = ? AND version_id = ?",
                    "https://assets.example.invalid/assets",
                    fixture.tenantId,
                    fixture.versionId))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("ASSET_EXPORT_CANDIDATE_IMMUTABLE");
  }

  private Fixture fixture() {
    return fixture(null);
  }

  private Fixture fixture(MigrationVersion target) {
    String schema = "game_design_export_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource();
    dataSource.setSchema(schema);
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);

    String tenantId = UUID.randomUUID().toString();
    Long gameId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO game (tenant_id, name) VALUES (?, ?) RETURNING id",
                    tenantId,
                    "Export snapshot test"),
                "game insert returned no row")
            .get(0, Long.class);
    UUID canonicalTenantId =
        Objects.requireNonNull(
                dsl.fetchOne("SELECT canonical_tenant_id FROM game WHERE id = ?", gameId),
                "canonical game tenant lookup returned no row")
            .get(0, UUID.class);
    UUID canonicalVersionId = UUID.randomUUID();
    Long versionId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO version "
                        + "(tenant_id, version_number, canonical_version_id, canonical_tenant_id, "
                        + "identity_source_game_row_id, identity_source_game_tenant_key, "
                        + "identity_source_provenance_kind) "
                        + "SELECT ?, 1, ?, ?, id, tenant_id, tenant_identity_provenance_kind "
                        + "FROM game WHERE id = ? RETURNING id",
                    tenantId,
                    canonicalVersionId,
                    canonicalTenantId,
                    gameId),
                "canonical version insert returned no row")
            .get(0, Long.class);
    Long assetId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO game_assets (tenant_id, file_name, content_type, data) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                    tenantId,
                    USAGE_KEY,
                    CONTENT_TYPE,
                    SOURCE_BYTES),
                "asset insert returned no row")
            .get(0, Long.class);
    dsl.execute(
        "INSERT INTO version_asset (tenant_id, version_id, asset_id, usage_key) "
            + "VALUES (?, ?, ?, ?)",
        tenantId,
        versionId,
        assetId,
        USAGE_KEY);
    return new Fixture(dsl, tenantId, versionId, assetId, dataSource, schema);
  }

  private static int insertItem(Fixture fixture, String hash, String type, long byteSize) {
    return insertItem(fixture.dsl, fixture, hash, type, byteSize);
  }

  private static int insertItem(
      DSLContext dsl, Fixture fixture, String hash, String type, long byteSize) {
    return dsl.execute(
        "INSERT INTO version_asset_export_item "
            + "(tenant_id, version_id, usage_key, asset_id, content_type, content_hash, byte_size) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        fixture.tenantId,
        fixture.versionId,
        USAGE_KEY,
        fixture.assetId,
        type,
        hash,
        byteSize);
  }

  private static void insertItemAndSnapshot(Fixture fixture) {
    fixture.dsl.transaction(
        configuration -> {
          DSLContext transactionDsl = DSL.using(configuration);
          insertItem(
              transactionDsl, fixture, sha256(SOURCE_BYTES), CONTENT_TYPE, SOURCE_BYTES.length);
          transactionDsl.execute(
              "INSERT INTO version_asset_export_snapshot "
                  + "(tenant_id, version_id, canonical_tenant_id, canonical_version_id, version_number, "
                  + "captured_version_state_epoch, item_count) "
                  + "SELECT tenant_id, id, canonical_tenant_id, canonical_version_id, version_number, "
                  + "version_state_epoch, 1 FROM version WHERE tenant_id = ? AND id = ?",
              fixture.tenantId,
              fixture.versionId);
        });
  }

  private static void insertArtifactWithoutCandidate(Fixture fixture) {
    fixture.dsl.execute(
        "INSERT INTO version_asset_artifact "
            + "(tenant_id, version_id, artifact_state, state_epoch, exported_version_number) "
            + "VALUES (?, ?, 'STAGED', 1, 1)",
        fixture.tenantId,
        fixture.versionId);
  }

  private static void insertCandidate(Fixture fixture, String publicBaseUrl) {
    fixture.dsl.execute(
        "INSERT INTO version_asset_artifact "
            + "(tenant_id, version_id, artifact_state, state_epoch, exported_version_number, "
            + "manifest_hash, manifest_schema_version, artifact_digests_json, "
            + "published_object_proofs_json, candidate_snapshot_version_id, "
            + "exported_manifest_asset_keys_json, candidate_public_base_url) "
            + "VALUES (?, ?, 'STAGED', 1, 1, ?, 1, '[]', '[]', ?, '[]', ?)",
        fixture.tenantId,
        fixture.versionId,
        sha256(SOURCE_BYTES),
        fixture.versionId,
        publicBaseUrl);
  }

  private static void updateCandidateEvidence(Fixture fixture, String publicBaseUrl) {
    fixture.dsl.execute(
        "UPDATE version_asset_artifact SET manifest_hash = ?, manifest_schema_version = 1, "
            + "artifact_digests_json = '[]', published_object_proofs_json = '[]', "
            + "candidate_snapshot_version_id = ?, exported_manifest_asset_keys_json = '[]', "
            + "candidate_public_base_url = ? WHERE tenant_id = ? AND version_id = ?",
        sha256(SOURCE_BYTES),
        fixture.versionId,
        publicBaseUrl,
        fixture.tenantId,
        fixture.versionId);
  }

  private static void insertLegacyCandidateWithoutOrigin(Fixture fixture) {
    fixture.dsl.execute(
        "INSERT INTO version_asset_artifact "
            + "(tenant_id, version_id, artifact_state, state_epoch, exported_version_number, "
            + "manifest_hash, manifest_schema_version, artifact_digests_json, "
            + "published_object_proofs_json, candidate_snapshot_version_id, "
            + "exported_manifest_asset_keys_json) "
            + "VALUES (?, ?, 'STAGED', 1, 1, ?, 1, '[]', '[]', ?, '[]')",
        fixture.tenantId,
        fixture.versionId,
        sha256(SOURCE_BYTES),
        fixture.versionId);
  }

  private static void migrateToLatest(Fixture fixture) {
    Flyway.configure()
        .dataSource(fixture.dataSource)
        .schemas(fixture.schema)
        .defaultSchema(fixture.schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", fixture.schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record Fixture(
      DSLContext dsl,
      String tenantId,
      Long versionId,
      Long assetId,
      DriverManagerDataSource dataSource,
      String schema) {}
}
