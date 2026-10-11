package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.service.impl.PublishedWorldSelectorFixtures;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL proof for the additive selected-v2 participant schema correction. */
class PublishedWorldSelectorSchemaMigrationIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final int SELECTOR_SCHEMA_VERSION =
      AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION;
  private static final String MANIFEST_HASH = "sha256:" + "a".repeat(64);
  private static final String RETAINED_PARTICIPANT_BYTES =
      "[{\"participantKey\":\"legacy-retained\",\"contentDigest\":\"retained-bytes\"}]";

  private final GameDesignPostgresIntegrationFixture postgres =
      new GameDesignPostgresIntegrationFixture();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void startPostgres() {
    postgres.start();
  }

  @AfterEach
  void stopPostgres() {
    postgres.stop();
  }

  @Test
  void freshInstallAcceptsSelectedV2OnlyWithControlPlaneDigestSchemaTwo() throws Exception {
    String schema = newSchema("fresh");
    DriverManagerDataSource dataSource = dataSource(schema);
    // Exercise the selected-schema guard before V50 requires an operation-backed release insert.
    migrate(dataSource, schema, MigrationVersion.fromVersion("49.1"));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    PersistedTarget accepted = persistTarget(jdbc, 1);
    WorldPublishedStartLocationEvidence acceptedEvidence = evidence(accepted);
    long acceptedReleaseId =
        insertRelease(
            jdbc, accepted, acceptedEvidence, selectedV2Participants(accepted, acceptedEvidence));
    assertThat(releaseCount(jdbc, accepted.versionId())).isEqualTo(1L);
    String acceptedRowBeforeLatest = retainedBundleRow(jdbc, acceptedReleaseId);
    Map<String, Object> functionBeforeLatest = selectorFunctionIdentity(jdbc);
    Map<String, Object> triggerBeforeLatest = selectorTriggerIdentity(jdbc);
    String functionBodyBeforeLatest = selectorFunctionBody(jdbc);

    PersistedTarget rejected = persistTarget(jdbc, 2);
    WorldPublishedStartLocationEvidence rejectedEvidence = evidence(rejected);
    assertThatThrownBy(
            () ->
                insertRelease(
                    jdbc,
                    rejected,
                    rejectedEvidence,
                    withRetainedControlPlaneSchema(
                        selectedV2Participants(rejected, rejectedEvidence))))
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .satisfies(
            cause -> {
              PSQLException postgresException = (PSQLException) cause;
              assertThat(postgresException.getSQLState()).isEqualTo("23514");
              assertThat(postgresException.getServerErrorMessage().getMessage())
                  .isEqualTo("release v2 participant differs from selected commit/digest/schema");
            });
    assertThat(releaseCount(jdbc, rejected.versionId())).isEqualTo(0L);

    migrate(dataSource, schema, null);

    assertThat(retainedBundleRow(jdbc, acceptedReleaseId)).isEqualTo(acceptedRowBeforeLatest);
    assertThat(selectorFunctionIdentity(jdbc)).isEqualTo(functionBeforeLatest);
    assertThat(selectorTriggerIdentity(jdbc)).isEqualTo(triggerBeforeLatest);
    assertThat(selectorFunctionBody(jdbc)).isEqualTo(functionBodyBeforeLatest);
    assertLatestGuardRejectsReleaseWithoutOwnerAttempt(jdbc, 3);
  }

  @Test
  void additiveUpgradeRetainsV1BytesAndGuardIdentityThenAppliesSelectedV2Schema() throws Exception {
    String schema = newSchema("upgrade");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("49"));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    PersistedTarget retainedTarget = persistTarget(jdbc, 1);
    long retainedBundleId = insertRetainedV1Release(jdbc, retainedTarget);
    String retainedRowBefore = retainedBundleRow(jdbc, retainedBundleId);
    PersistedTarget legacyV2Target = persistTarget(jdbc, 1);
    WorldPublishedStartLocationEvidence legacyV2Evidence = evidence(legacyV2Target);
    long legacyV2BundleId =
        insertRelease(
            jdbc,
            legacyV2Target,
            legacyV2Evidence,
            withRetainedControlPlaneSchema(
                selectedV2Participants(legacyV2Target, legacyV2Evidence)));
    String legacyV2RowBefore = retainedBundleRow(jdbc, legacyV2BundleId);
    Map<String, Object> functionBefore = selectorFunctionIdentity(jdbc);
    Map<String, Object> triggerBefore = selectorTriggerIdentity(jdbc);
    String functionBodyBefore = selectorFunctionBody(jdbc);
    assertThat(functionBodyBefore).contains("('GAME_DESIGN_CONTROL_PLANE',1)");

    migrate(dataSource, schema, MigrationVersion.fromVersion("49.1"));

    assertThat(retainedBundleRow(jdbc, retainedBundleId)).isEqualTo(retainedRowBefore);
    assertThat(retainedBundleRow(jdbc, legacyV2BundleId)).isEqualTo(legacyV2RowBefore);
    assertThat(selectorFunctionIdentity(jdbc)).isEqualTo(functionBefore);
    assertThat(selectorTriggerIdentity(jdbc)).isEqualTo(triggerBefore);
    assertThat(selectorFunctionBody(jdbc))
        .isEqualTo(
            functionBodyBefore.replace(
                "('GAME_DESIGN_CONTROL_PLANE',1)", "('GAME_DESIGN_CONTROL_PLANE',2)"));

    PersistedTarget accepted = persistTarget(jdbc, 2);
    WorldPublishedStartLocationEvidence acceptedEvidence = evidence(accepted);
    long acceptedReleaseId =
        insertRelease(
            jdbc, accepted, acceptedEvidence, selectedV2Participants(accepted, acceptedEvidence));
    assertThat(releaseCount(jdbc, accepted.versionId())).isEqualTo(1L);
    String acceptedRowBeforeLatest = retainedBundleRow(jdbc, acceptedReleaseId);

    PersistedTarget rejected = persistTarget(jdbc, 3);
    WorldPublishedStartLocationEvidence rejectedEvidence = evidence(rejected);
    assertThatThrownBy(
            () ->
                insertRelease(
                    jdbc,
                    rejected,
                    rejectedEvidence,
                    withRetainedControlPlaneSchema(
                        selectedV2Participants(rejected, rejectedEvidence))))
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .satisfies(
            cause -> {
              PSQLException postgresException = (PSQLException) cause;
              assertThat(postgresException.getSQLState()).isEqualTo("23514");
              assertThat(postgresException.getServerErrorMessage().getMessage())
                  .isEqualTo("release v2 participant differs from selected commit/digest/schema");
            });
    assertThat(releaseCount(jdbc, rejected.versionId())).isEqualTo(0L);

    String functionBodyAfterSelectorMigration = selectorFunctionBody(jdbc);
    migrate(dataSource, schema, null);

    assertThat(retainedBundleRow(jdbc, retainedBundleId)).isEqualTo(retainedRowBefore);
    assertThat(retainedBundleRow(jdbc, legacyV2BundleId)).isEqualTo(legacyV2RowBefore);
    assertThat(retainedBundleRow(jdbc, acceptedReleaseId)).isEqualTo(acceptedRowBeforeLatest);
    assertThat(selectorFunctionIdentity(jdbc)).isEqualTo(functionBefore);
    assertThat(selectorTriggerIdentity(jdbc)).isEqualTo(triggerBefore);
    assertThat(selectorFunctionBody(jdbc)).isEqualTo(functionBodyAfterSelectorMigration);
    assertLatestGuardRejectsReleaseWithoutOwnerAttempt(jdbc, 4);
  }

  private void assertLatestGuardRejectsReleaseWithoutOwnerAttempt(
      JdbcTemplate jdbc, int versionNumber) throws Exception {
    // A schema-valid v2 row still needs a genuine publication attempt under the latest guard.
    PersistedTarget target = persistTarget(jdbc, versionNumber);
    WorldPublishedStartLocationEvidence targetEvidence = evidence(target);
    assertThatThrownBy(
            () ->
                insertRelease(
                    jdbc, target, targetEvidence, selectedV2Participants(target, targetEvidence)))
        .rootCause()
        .isInstanceOf(PSQLException.class)
        .satisfies(cause -> assertThat(((PSQLException) cause).getSQLState()).isEqualTo("P0002"));
    assertThat(releaseCount(jdbc, target.versionId())).isEqualTo(0L);
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = postgres.dataSource();
    dataSource.setSchema(schema);
    return dataSource;
  }

  private String newSchema(String suffix) {
    return "selected_v2_" + suffix + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private PersistedTarget persistTarget(JdbcTemplate jdbc, int versionNumber) {
    String tenantKey = "selector-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> game =
        jdbc.queryForMap(
            "INSERT INTO game (tenant_id, name, description) VALUES (?, ?, ?) "
                + "RETURNING id, canonical_tenant_id, tenant_identity_provenance_kind",
            tenantKey,
            "Selector schema test " + versionNumber,
            "Persisted source for selected release evidence");
    long gameId = ((Number) game.get("id")).longValue();
    UUID canonicalTenantId = (UUID) game.get("canonical_tenant_id");
    String provenanceKind = (String) game.get("tenant_identity_provenance_kind");
    UUID canonicalVersionId = UUID.randomUUID();
    long versionId =
        Objects.requireNonNull(
                jdbc.queryForObject(
                    "INSERT INTO version (tenant_id, version_number, canonical_version_id, "
                        + "canonical_tenant_id, identity_source_game_row_id, identity_source_game_tenant_key, "
                        + "identity_source_provenance_kind) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class,
                    tenantKey,
                    versionNumber,
                    canonicalVersionId,
                    canonicalTenantId,
                    gameId,
                    tenantKey,
                    provenanceKind),
                "Expected inserted version row ID")
            .longValue();
    return new PersistedTarget(
        tenantKey,
        gameId,
        canonicalTenantId,
        canonicalVersionId,
        versionId,
        versionNumber,
        provenanceKind);
  }

  private WorldPublishedStartLocationEvidence evidence(PersistedTarget target) throws Exception {
    return PublishedWorldSelectorFixtures.evidence(target.proof());
  }

  private List<PublishParticipantDigestDto> selectedV2Participants(
      PersistedTarget target, WorldPublishedStartLocationEvidence evidence) {
    return PublishedWorldSelectorFixtures.participants(
        target.versionId(), evidence, SELECTOR_SCHEMA_VERSION);
  }

  private List<PublishParticipantDigestDto> withRetainedControlPlaneSchema(
      List<PublishParticipantDigestDto> participants) {
    return participants.stream()
        .map(
            participant ->
                "GAME_DESIGN_CONTROL_PLANE".equals(participant.participantKey())
                    ? new PublishParticipantDigestDto(
                        participant.participantKey(),
                        participant.scopeValue(),
                        participant.baseVersionId(),
                        participant.appliedCommitId(),
                        participant.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION,
                        participant.abilitySchemaDigest(),
                        participant.errorCode(),
                        participant.errorMessage())
                    : participant)
        .toList();
  }

  private long insertRetainedV1Release(JdbcTemplate jdbc, PersistedTarget target) {
    return Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, "
                    + "attestation_schema_version, publish_workflow_id, manifest_hash, "
                    + "required_manifest_asset_keys_json, participant_digests_json, "
                    + "generation_config_revision, published_release_bundle_ref, canonical_tenant_id, "
                    + "canonical_version_id) VALUES (?, ?, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                target.tenantKey(),
                target.versionId(),
                target.versionNumber(),
                "retained-v1-workflow-" + target.versionNumber(),
                "retained-v1-manifest-" + target.versionNumber(),
                "[\"retained-key\"]",
                RETAINED_PARTICIPANT_BYTES,
                "retained-generation-config",
                "retained-v1-reference-" + target.versionNumber(),
                target.canonicalTenantId(),
                target.canonicalVersionId()),
            "Expected inserted retained v1 release row ID")
        .longValue();
  }

  private long insertRelease(
      JdbcTemplate jdbc,
      PersistedTarget target,
      WorldPublishedStartLocationEvidence evidence,
      List<PublishParticipantDigestDto> participants)
      throws Exception {
    String participantJson = objectMapper.writeValueAsString(participants);
    String evidenceJson = new String(evidence.canonicalBytes(), StandardCharsets.UTF_8);
    return Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO published_release_bundle (tenant_id, version_id, version_number, "
                    + "attestation_schema_version, publish_workflow_id, manifest_hash, "
                    + "required_manifest_asset_keys_json, participant_digests_json, "
                    + "generation_config_revision, manifest_schema_version, artifact_digests_json, "
                    + "published_release_bundle_ref, canonical_tenant_id, canonical_version_id, "
                    + "world_published_start_location_evidence_json) "
                    + "VALUES (?, ?, ?, 'v2', ?, ?, '[]', ?, 'selector-generation-config', 1, '[]', ?, ?, ?, ?) "
                    + "RETURNING id",
                Long.class,
                target.tenantKey(),
                target.versionId(),
                target.versionNumber(),
                evidence.request().publishWorkflowId(),
                MANIFEST_HASH,
                participantJson,
                "selector-release-reference-" + target.versionNumber(),
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                evidenceJson),
            "Expected inserted v2 release row ID")
        .longValue();
  }

  private long releaseCount(JdbcTemplate jdbc, long versionId) {
    return Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM published_release_bundle WHERE version_id = ?",
                Long.class,
                versionId),
            "Expected release row count")
        .longValue();
  }

  private String retainedBundleRow(JdbcTemplate jdbc, long bundleId) {
    return jdbc.queryForObject(
        "SELECT row_to_json(bundle)::text FROM published_release_bundle bundle WHERE id = ?",
        String.class,
        bundleId);
  }

  private Map<String, Object> selectorFunctionIdentity(JdbcTemplate jdbc) {
    return jdbc.queryForMap(
        "SELECT p.oid::text AS oid, p.proowner::regrole::text AS owner, "
            + "p.proacl::text AS acl, p.proname AS name, p.proargtypes::text AS arguments "
            + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
            + "WHERE n.nspname = current_schema() "
            + "AND p.proname = 'enforce_published_world_start_location_evidence'");
  }

  private String selectorFunctionBody(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "SELECT p.prosrc FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
            + "WHERE n.nspname = current_schema() "
            + "AND p.proname = 'enforce_published_world_start_location_evidence'",
        String.class);
  }

  private Map<String, Object> selectorTriggerIdentity(JdbcTemplate jdbc) {
    return jdbc.queryForMap(
        "SELECT t.oid::text AS oid, t.tgname AS name, t.tgfoid::text AS function_oid, "
            + "t.tgenabled AS enabled, t.tgtype::text AS trigger_type "
            + "FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = current_schema() "
            + "AND c.relname = 'published_release_bundle' "
            + "AND t.tgname = 'trg_published_world_start_location_evidence'");
  }

  private record PersistedTarget(
      String tenantKey,
      long gameId,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      long versionId,
      int versionNumber,
      String provenanceKind) {
    TargetProof proof() {
      return new TargetProof(
          canonicalTenantId,
          canonicalVersionId,
          versionId,
          tenantKey,
          gameId,
          tenantKey,
          provenanceKind);
    }
  }
}
