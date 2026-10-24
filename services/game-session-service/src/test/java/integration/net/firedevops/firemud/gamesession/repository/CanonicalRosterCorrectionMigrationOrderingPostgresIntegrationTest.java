package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL migration-history proof for the current GS and Entity roster prefixes. This
 * proves migration ordering and retained fixture bytes only; owner runtime suites prove their
 * respective retention behavior.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalRosterCorrectionMigrationOrderingPostgresIntegrationTest {
  private static final String GS_MIGRATIONS =
      filesystem(Path.of("src/main/resources/db/migration"));
  private static final String ENTITY_MIGRATIONS =
      filesystem(Path.of("../entity-management-service/src/main/resources/db/migration"));
  private static final String SAGA_MIGRATIONS =
      filesystem(Path.of("../common-saga/src/main/resources/db/migration/saga"));

  private static final MigrationSet GAME_SESSION =
      new MigrationSet("game-session", GS_MIGRATIONS, "1017", "1020", "1020", "1021");
  private static final MigrationSet ENTITY =
      new MigrationSet("entity-management", ENTITY_MIGRATIONS, "1014", "1018", "1018", "1019");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private final List<String> schemas = new ArrayList<>();

  @AfterEach
  void dropOwnedSchemas() throws java.sql.SQLException {
    try (java.sql.Connection connection =
        java.sql.DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      DSLContext admin = DSL.using(connection, SQLDialect.POSTGRES);
      for (String schema : schemas) {
        admin.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
    }
  }

  @Test
  void currentPrefixesUpgradeCleanAndRetainedPublishedHistoriesWithoutRewritingHistory() {
    for (MigrationSet migrations : List.of(GAME_SESSION, ENTITY)) {
      proveCleanInstall(migrations);
      proveRetainedParentUpgradeAndNoOp(migrations);
      provePublishedChildUpgradeAndNoOp(migrations);
    }
    proveEntityPublishedV1019TypedRecognitionUpgradeAndNoOp();
  }

  private void proveCleanInstall(MigrationSet migrations) {
    String schema = newSchema(migrations.name() + "_clean_");
    DriverManagerDataSource dataSource = dataSource(schema);

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> installed = history(dataSource);
    assertMigrationPresent(installed, migrations.childVersion(), migrations.childScript());
    assertMigrationPresent(installed, migrations.frontVersion(), migrations.frontScript());
    assertMigrationPresent(
        installed,
        migrations == GAME_SESSION ? "1022" : "1021",
        migrations == GAME_SESSION
            ? "V1022__canonical_roster_binary_safe_recovery_guard.sql"
            : "V1021__character_create_binary_safe_immutability.sql");
    assertThat(installed).allSatisfy(row -> assertThat(row).contains("\"success\": true"));
    assertEntitySealPrimaryKey(migrations, dataSource);
  }

  private void proveRetainedParentUpgradeAndNoOp(MigrationSet migrations) {
    if (migrations == ENTITY) {
      proveEntityAncestorUpgradeAndNoOp();
      return;
    }
    String schema = newSchema(migrations.name() + "_parent_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, migrations.locations(), migrations.parentVersion());
    seedRetainedOwnerRow(migrations, schema, dataSource);

    List<String> parentHistory = history(dataSource);
    List<String> retainedBytes = retainedOwnerRows(migrations, dataSource);
    assertThat(parentHistory).isNotEmpty();

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, parentHistory, migrations.name() + " retained parent");
    assertMigrationPresent(upgradedHistory, migrations.childVersion(), migrations.childScript());
    assertMigrationPresent(upgradedHistory, migrations.frontVersion(), migrations.frontScript());
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
    assertEntitySealPrimaryKey(migrations, dataSource);

    migrate(dataSource, schema, migrations.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
    assertEntitySealPrimaryKey(migrations, dataSource);
  }

  private void proveEntityAncestorUpgradeAndNoOp() {
    String schema = newSchema("entity_management_parent_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, ENTITY.locations(), ENTITY.parentVersion());
    List<String> parentHistory = history(dataSource);
    assertThat(parentHistory).isNotEmpty();

    migrate(dataSource, schema, ENTITY.locations(), ENTITY.childVersion());
    List<String> stagedHistory = history(dataSource);
    assertHistoryPrefix(stagedHistory, parentHistory, "entity retained V1014 parent");
    assertMigrationPresent(stagedHistory, "1018", ENTITY.childScript());

    List<EntitySnapshotSeed> snapshots = seedEntitySnapshots(dataSource);
    UUID sealSnapshot = snapshots.get(0).snapshotUuid();
    String sealDigest = seedEntitySeal(dataSource, sealSnapshot, snapshots.get(0).snapshotDigest());
    List<String> stagedRows = retainedOwnerRows(ENTITY, dataSource);
    List<String> stagedSourceHistory = history(dataSource);

    migrate(dataSource, schema, ENTITY.locations(), null);
    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(
        upgradedHistory, stagedSourceHistory, "entity V1018-to-V1019 retained source");
    assertMigrationPresent(upgradedHistory, "1019", ENTITY.frontScript());
    assertThat(retainedOwnerRows(ENTITY, dataSource)).containsExactlyElementsOf(stagedRows);
    assertEntitySealPrimaryKey(ENTITY, dataSource);
    assertEntitySnapshotClassification(dataSource, snapshots, sealSnapshot, sealDigest);
    List<String> classifiedMetadata = retainedEntitySnapshotTargets(dataSource);
    assertThat(retainedOwnerRows(ENTITY, dataSource)).containsExactlyElementsOf(stagedRows);

    migrate(dataSource, schema, ENTITY.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(ENTITY, dataSource)).containsExactlyElementsOf(stagedRows);
    assertThat(retainedEntitySnapshotTargets(dataSource))
        .containsExactlyElementsOf(classifiedMetadata);
    assertEntitySealPrimaryKey(ENTITY, dataSource);
  }

  private void proveEntityPublishedChildUpgradeAndNoOp() {
    proveEntityPublishedV1017UpgradeAndNoOp();
    String schema = newSchema("entity_management_child_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, ENTITY.locations(), ENTITY.publishedChildVersion());
    List<EntitySnapshotSeed> snapshots = seedEntitySnapshots(dataSource);
    UUID sealSnapshot = snapshots.get(0).snapshotUuid();
    String sealDigest = seedEntitySeal(dataSource, sealSnapshot, snapshots.get(0).snapshotDigest());

    List<String> childHistory = history(dataSource);
    List<String> retainedRows = retainedOwnerRows(ENTITY, dataSource);
    assertMigrationPresent(childHistory, "1018", ENTITY.childScript());

    migrate(dataSource, schema, ENTITY.locations(), null);
    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, childHistory, "entity already-applied V1018 child");
    assertMigrationPresent(upgradedHistory, "1019", ENTITY.frontScript());
    assertThat(retainedOwnerRows(ENTITY, dataSource)).containsExactlyElementsOf(retainedRows);
    assertEntitySealPrimaryKey(ENTITY, dataSource);
    assertEntitySnapshotClassification(dataSource, snapshots, sealSnapshot, sealDigest);
    List<String> classifiedMetadata = retainedEntitySnapshotTargets(dataSource);

    migrate(dataSource, schema, ENTITY.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(ENTITY, dataSource)).containsExactlyElementsOf(retainedRows);
    assertThat(retainedEntitySnapshotTargets(dataSource))
        .containsExactlyElementsOf(classifiedMetadata);
    assertEntitySealPrimaryKey(ENTITY, dataSource);
  }

  private void proveEntityPublishedV1017UpgradeAndNoOp() {
    String schema = newSchema("entity_management_v1017_child_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, ENTITY.locations(), "1017");
    List<EntitySnapshotSeed> snapshots = seedEntitySnapshots(dataSource);
    UUID operation = UUID.randomUUID();
    byte[] requestBinding = new byte[] {19, 20, 21};
    DSL.using(dataSource, SQLDialect.POSTGRES)
        .execute(
            "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?)",
            operation,
            UUID.randomUUID(),
            requestBinding);

    List<String> sourceRowsAt1017 = retainedEntitySnapshotAndAdmissionRows(dataSource);
    List<String> historyAt1017 = history(dataSource);
    assertThat(historyAt1017).isNotEmpty();

    migrate(dataSource, schema, ENTITY.locations(), "1018");
    List<String> historyAt1018 = history(dataSource);
    assertHistoryPrefix(historyAt1018, historyAt1017, "entity published V1017 source");
    assertMigrationPresent(historyAt1018, "1018", ENTITY.childScript());
    assertThat(retainedEntitySnapshotAndAdmissionRows(dataSource))
        .containsExactlyElementsOf(sourceRowsAt1017);

    migrate(dataSource, schema, ENTITY.locations(), null);
    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, historyAt1018, "entity published V1017 through V1019");
    assertMigrationPresent(upgradedHistory, "1019", ENTITY.frontScript());
    assertThat(retainedEntitySnapshotAndAdmissionRows(dataSource))
        .containsExactlyElementsOf(sourceRowsAt1017);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .isTrue();
    for (int index = 0; index < snapshots.size(); index++) {
      assertThat(
              dsl.fetchSingle("SELECT entity_roster_initialize_snapshot_targets(?)", 1)
                  .get(0, Integer.class))
          .isEqualTo(1);
    }
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_initialize_snapshot_targets(?)", 1)
                .get(0, Integer.class))
        .isZero();
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .as("the retained raw admission remains unclassified after snapshot inventory progresses")
        .isTrue();
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE source_kind = 'SNAPSHOT' AND classification_state = 'KNOWN'")
                .get(0, Long.class))
        .isEqualTo(3L);
    assertThat(dsl.fetchCount(DSL.table("entity_canonical_roster_target_classifications")))
        .isZero();
    List<String> classifiedSnapshotTargets = retainedEntitySnapshotTargets(dataSource);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_admissions "
                        + "WHERE operation_uuid = ? AND request_binding = ?",
                    operation,
                    requestBinding)
                .get(0, Long.class))
        .isEqualTo(1L);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
                    operation))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);

    migrate(dataSource, schema, ENTITY.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedEntitySnapshotAndAdmissionRows(dataSource))
        .containsExactlyElementsOf(sourceRowsAt1017);
    assertThat(retainedEntitySnapshotTargets(dataSource))
        .containsExactlyElementsOf(classifiedSnapshotTargets);
  }

  private void proveEntityPublishedV1019TypedRecognitionUpgradeAndNoOp() {
    String schema = newSchema("entity_management_v1019_typed_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, ENTITY.locations(), "1019");
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    EntitySnapshotSeed valid = seedCreatedSnapshot(dataSource, validPlayerCreatedSourceIdentity());
    EntitySnapshotSeed malformed = seedCreatedSnapshot(dataSource, new byte[] {(byte) 0x80});
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE source_kind = 'SNAPSHOT'")
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(
            dsl.fetch(
                    "SELECT classification_state FROM entity_canonical_roster_snapshot_targets WHERE source_kind = 'SNAPSHOT' ORDER BY snapshot_uuid")
                .getValues(0, String.class))
        .containsExactlyInAnyOrder("KNOWN", "KNOWN");

    List<String> sourceRowsAt1019 = retainedEntitySnapshotRows(dataSource);
    List<String> targetRowsAt1019 = retainedEntitySnapshotTargets(dataSource);
    List<String> historyAt1019 = history(dataSource);
    assertThat(historyAt1019)
        .anySatisfy(
            row ->
                assertThat(row)
                    .contains(
                        "1019",
                        "V1019__canonical_roster_snapshot_retirement_seals.sql",
                        "\"success\": true"));

    migrate(dataSource, schema, ENTITY.locations(), null);
    List<String> historyAt1020 = history(dataSource);
    assertHistoryPrefix(
        historyAt1020, historyAt1019, "entity published V1019 typed-created source");
    assertMigrationPresent(
        historyAt1020, "1020", "V1020__canonical_roster_created_retirement_recognition.sql");
    assertThat(retainedEntitySnapshotRows(dataSource)).containsExactlyElementsOf(sourceRowsAt1019);
    assertThat(retainedEntitySnapshotTargetsWithoutRecognitionVersion(dataSource))
        .containsExactlyElementsOf(targetRowsAt1019);
    assertThat(typedSnapshotTargetCount(dataSource)).isZero();

    classifyRetainedDependencies(dataSource, 1);
    assertThat(typedSnapshotTargetCount(dataSource)).isEqualTo(1L);
    classifyRetainedDependencies(dataSource, 1);
    assertThat(typedSnapshotTargetCount(dataSource)).isEqualTo(2L);
    assertTypedCreatedTarget(dataSource, valid, "KNOWN");
    assertTypedCreatedTarget(dataSource, malformed, "UNKNOWN");
    assertThat(
            DSL.using(dataSource, SQLDialect.POSTGRES)
                .fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .as("malformed retained created source stays globally protective")
        .isTrue();
    assertThat(retainedEntitySnapshotRows(dataSource)).containsExactlyElementsOf(sourceRowsAt1019);
    assertThat(retainedEntitySnapshotTargetsWithoutRecognitionVersion(dataSource))
        .containsExactlyElementsOf(targetRowsAt1019);
    List<String> typedRows = retainedTypedEntitySnapshotTargets(dataSource);

    migrate(dataSource, schema, ENTITY.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(historyAt1020);
    assertThat(retainedEntitySnapshotRows(dataSource)).containsExactlyElementsOf(sourceRowsAt1019);
    assertThat(retainedEntitySnapshotTargetsWithoutRecognitionVersion(dataSource))
        .containsExactlyElementsOf(targetRowsAt1019);
    assertThat(retainedTypedEntitySnapshotTargets(dataSource)).containsExactlyElementsOf(typedRows);
  }

  private EntitySnapshotSeed seedCreatedSnapshot(
      DriverManagerDataSource dataSource, byte[] sourceIdentity) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    UUID account = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID realm = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID canonicalVersion = UUID.randomUUID();
    UUID snapshot = UUID.randomUUID();
    String digest = UUID.randomUUID().toString().replace("-", "").repeat(2);
    dsl.execute("INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)", tenant);
    dsl.execute(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, 'PLAYABLE_STATE_SCOPE_SHARED')",
        tenant,
        namespace);
    dsl.execute(
        "INSERT INTO entity_canonical_gameplay_roster_snapshots "
            + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, "
            + "world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, "
            + "pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, "
            + "admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, "
            + "playable_state_scope, entry_policy, canonical_player_created_source_identity, "
            + "publication_evidence_format, roster_count, construction_state) "
            + "VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 7, ?, 9, 10, ?, 'bundle-ref', ?, ?, ?, "
            + "'PLAYABLE_STATE_SCOPE_SHARED', 'PLAYER_CREATED', ?, 'CANONICAL_UUID_V2', 0, 'BUILDING')",
        snapshot,
        digest,
        account,
        tenant,
        realm,
        instance,
        canonicalVersion,
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        namespace,
        sourceIdentity);
    dsl.execute(
        "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' WHERE snapshot_uuid = ?",
        snapshot);
    return new EntitySnapshotSeed(snapshot, digest, true);
  }

  private void classifyRetainedDependencies(DriverManagerDataSource dataSource, int limit) {
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .execute(
            status -> {
              new net.firedevops.firemud.entitymanagement.repository
                      .CanonicalGameplayRosterRetentionRepository(
                      DSL.using(
                          new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(
                              dataSource),
                          SQLDialect.POSTGRES))
                  .classifyRetainedDependencies(limit);
              return null;
            });
  }

  private void assertTypedCreatedTarget(
      DriverManagerDataSource dataSource, EntitySnapshotSeed seed, String expectedState) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle(
                    "SELECT classification_state FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE snapshot_uuid = ? AND source_kind = 'SNAPSHOT' AND recognition_version = 2",
                    seed.snapshotUuid())
                .get(0, String.class))
        .isEqualTo(expectedState);
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE snapshot_uuid = ? AND source_kind = 'SNAPSHOT' AND recognition_version = 1",
                    seed.snapshotUuid())
                .get(0, Long.class))
        .isEqualTo(1L);
    boolean exactProjection =
        dsl.fetchSingle(
                "SELECT m.source_snapshot_projection = entity_roster_snapshot_target_projection(s) "
                    + "AND ((m.classification_state = 'KNOWN' AND m.target_projection = entity_roster_snapshot_target_projection(s)) "
                    + "OR (m.classification_state = 'UNKNOWN' AND m.target_projection IS NULL)) "
                    + "FROM entity_canonical_roster_snapshot_targets m "
                    + "JOIN entity_canonical_gameplay_roster_snapshots s USING (snapshot_uuid, snapshot_digest) "
                    + "WHERE m.snapshot_uuid = ? AND m.source_kind = 'SNAPSHOT' AND m.recognition_version = 2",
                seed.snapshotUuid())
            .get(0, Boolean.class);
    assertThat(exactProjection).isTrue();
  }

  private long typedSnapshotTargetCount(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetchSingle(
            "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                + "WHERE recognition_version = 2 AND source_kind = 'SNAPSHOT'")
        .get(0, Long.class);
  }

  private List<String> retainedEntitySnapshotRows(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_gameplay_roster_snapshots t ORDER BY snapshot_uuid")
        .getValues(0, String.class);
  }

  private List<String> retainedEntitySnapshotTargetsWithoutRecognitionVersion(
      DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT (to_jsonb(t) - 'recognition_version')::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_snapshot_targets t "
                + "WHERE recognition_version = 1 ORDER BY snapshot_uuid, source_kind")
        .getValues(0, String.class);
  }

  private List<String> retainedTypedEntitySnapshotTargets(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_snapshot_targets t "
                + "ORDER BY snapshot_uuid, source_kind, recognition_version")
        .getValues(0, String.class);
  }

  private byte[] validPlayerCreatedSourceIdentity() {
    return CanonicalPlayerCreatedRosterSourceIdentity.newBuilder()
        .setSchemaVersion(1)
        .setDescriptorUuid(UUID.randomUUID().toString())
        .setDescriptorRevisionUuid(UUID.randomUUID().toString())
        .setDescriptorDigest("sha256:" + "1".repeat(64))
        .setAuthoredBindingUuid(UUID.randomUUID().toString())
        .setAuthoredBindingDigest("sha256:" + "2".repeat(64))
        .build()
        .toByteArray();
  }

  private List<EntitySnapshotSeed> seedEntitySnapshots(DriverManagerDataSource dataSource) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    UUID account = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID realm = UUID.randomUUID();
    UUID instance = UUID.randomUUID();
    UUID namespace = UUID.randomUUID();
    UUID canonicalVersion = UUID.randomUUID();
    dsl.execute("INSERT INTO entity_tenant_identities (canonical_tenant_uuid) VALUES (?)", tenant);
    dsl.execute(
        "INSERT INTO entity_playable_state_namespace_scopes "
            + "(tenant_uuid, playable_state_namespace_id, playable_state_scope) VALUES (?, ?, 'PLAYABLE_STATE_SCOPE_SHARED')",
        tenant,
        namespace);

    List<EntitySnapshotSeed> seeds = new ArrayList<>();
    for (int index = 0; index < 3; index++) {
      UUID snapshot = UUID.randomUUID();
      String snapshotDigest = String.valueOf((char) ('a' + index)).repeat(64);
      boolean createdSource = index == 2;
      byte[] sourceIdentity = createdSource ? validPlayerCreatedSourceIdentity() : null;
      String entryPolicy = createdSource ? "PLAYER_CREATED" : "PRESEEDED_ONLY";
      dsl.execute(
          "INSERT INTO entity_canonical_gameplay_roster_snapshots "
              + "(snapshot_uuid, snapshot_digest, canonical_account_uuid, tenant_uuid, realm_uuid, "
              + "world_slug, realm_slug, game_instance_uuid, catalog_revision, canonical_version_uuid, "
              + "pointer_version, active_world_epoch, published_policy_digest, published_release_bundle_ref, "
              + "admission_pointer_snapshot_digest, published_owner_proof_digest, playable_state_namespace_uuid, "
              + "playable_state_scope, entry_policy, canonical_player_created_source_identity, "
              + "publication_evidence_format, roster_count, construction_state) "
              + "VALUES (?, ?, ?, ?, ?, 'world', 'realm', ?, 7, ?, 9, 10, ?, 'bundle-ref', ?, ?, ?, "
              + "'PLAYABLE_STATE_SCOPE_SHARED', ?, ?, 'CANONICAL_UUID_V2', 0, 'BUILDING')",
          snapshot,
          snapshotDigest,
          account,
          tenant,
          realm,
          instance,
          canonicalVersion,
          "b".repeat(64),
          "c".repeat(64),
          "d".repeat(64),
          namespace,
          entryPolicy,
          sourceIdentity);
      dsl.execute(
          "UPDATE entity_canonical_gameplay_roster_snapshots SET construction_state = 'SEALED' "
              + "WHERE snapshot_uuid = ?",
          snapshot);
      seeds.add(new EntitySnapshotSeed(snapshot, snapshotDigest, createdSource));
    }
    return List.copyOf(seeds);
  }

  private String seedEntitySeal(
      DriverManagerDataSource dataSource, UUID snapshot, String snapshotDigest) {
    byte[] targetBinding = new byte[] {6, 7};
    DSL.using(dataSource, SQLDialect.POSTGRES)
        .execute(
            "INSERT INTO entity_canonical_roster_retired_targets "
                + "(target_digest, target_binding, snapshot_uuid, snapshot_digest, "
                + "owner_retirement_proof, minimum_retention_millis) "
                + "SELECT encode(sha256(?), 'hex'), ?, ?, ?, ?, 1000",
            targetBinding,
            targetBinding,
            snapshot,
            snapshotDigest,
            new byte[] {8, 9});
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetchSingle(
            "SELECT target_digest FROM entity_canonical_roster_retired_targets WHERE snapshot_uuid = ?",
            snapshot)
        .get(0, String.class);
  }

  private void assertEntitySnapshotClassification(
      DriverManagerDataSource dataSource,
      List<EntitySnapshotSeed> snapshots,
      UUID sealSnapshot,
      String sealDigest) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                .get(0, Boolean.class))
        .isTrue();
    assertThat(retainedEntitySnapshotTargets(dataSource)).isEmpty();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_roster_retired_targets WHERE snapshot_uuid = ?",
                    sealSnapshot))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(entitySealPurgeable(dsl, sealDigest)).isFalse();

    for (int index = 0; index < snapshots.size(); index++) {
      int initialized =
          dsl.fetchSingle("SELECT entity_roster_initialize_snapshot_targets(?)", 1)
              .get(0, Integer.class);
      assertThat(initialized).isEqualTo(1);
      assertThat(
              dsl.fetchSingle("SELECT entity_roster_target_classification_pending()")
                  .get(0, Boolean.class))
          .as("the retained raw created-source fixture still requires exact typed recognition")
          .isTrue();
    }
    assertThat(
            dsl.fetchSingle("SELECT entity_roster_initialize_snapshot_targets(?)", 1)
                .get(0, Integer.class))
        .isZero();

    for (EntitySnapshotSeed seed : snapshots) {
      boolean projectionMatches =
          dsl.fetchSingle(
                  "SELECT m.classification_state = 'KNOWN' "
                      + "AND m.target_projection = entity_roster_snapshot_target_projection(s) "
                      + "AND m.source_snapshot_projection = entity_roster_snapshot_target_projection(s) "
                      + "AND (m.target_projection -> 'canonical_player_created_source_identity') "
                      + "= COALESCE(to_jsonb(s.canonical_player_created_source_identity), 'null'::jsonb) "
                      + "FROM entity_canonical_roster_snapshot_targets m "
                      + "JOIN entity_canonical_gameplay_roster_snapshots s USING (snapshot_uuid, snapshot_digest) "
                      + "WHERE m.snapshot_uuid = ? AND m.source_kind = 'SNAPSHOT'",
                  seed.snapshotUuid())
              .get(0, Boolean.class);
      assertThat(projectionMatches).isTrue();
      boolean createdSourceIsNull =
          dsl.fetchSingle(
                  "SELECT canonical_player_created_source_identity IS NULL "
                      + "FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?",
                  seed.snapshotUuid())
              .get(0, Boolean.class);
      assertThat(createdSourceIsNull).isEqualTo(!seed.createdSourceIdentityPresent());
    }
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM entity_canonical_roster_snapshot_targets "
                        + "WHERE source_kind = 'SNAPSHOT' AND target_projection = ("
                        + "SELECT target_projection FROM entity_canonical_roster_snapshot_targets WHERE snapshot_uuid = ? AND source_kind = 'SNAPSHOT')",
                    snapshots.get(0).snapshotUuid())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThat(entitySealPurgeable(dsl, sealDigest)).isFalse();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM entity_canonical_roster_retired_targets WHERE snapshot_uuid = ?",
                    sealSnapshot))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  private boolean entitySealPurgeable(DSLContext dsl, String targetDigest) {
    return dsl.fetchSingle("SELECT entity_roster_retired_target_purgeable(?)", targetDigest)
        .get(0, Boolean.class);
  }

  private List<String> retainedEntitySnapshotAndAdmissionRows(DriverManagerDataSource dataSource) {
    return DSL
        .using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT retained_row FROM ("
                + "SELECT 'snapshot:' || to_jsonb(t)::TEXT || ':' || t.xmin::TEXT AS retained_row "
                + "FROM entity_canonical_gameplay_roster_snapshots t UNION ALL "
                + "SELECT 'admission:' || to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_admissions t) retained ORDER BY retained_row")
        .getValues(0, String.class)
        .stream()
        .toList();
  }

  private List<String> retainedEntitySnapshotTargets(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_snapshot_targets t ORDER BY snapshot_uuid, source_kind")
        .getValues(0, String.class);
  }

  private record EntitySnapshotSeed(
      UUID snapshotUuid, String snapshotDigest, boolean createdSourceIdentityPresent) {}

  private void provePublishedChildUpgradeAndNoOp(MigrationSet migrations) {
    if (migrations == ENTITY) {
      proveEntityPublishedChildUpgradeAndNoOp();
      return;
    }
    String schema = newSchema(migrations.name() + "_child_");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, migrations.locations(), migrations.publishedChildVersion());
    seedRetainedOwnerRow(migrations, schema, dataSource);

    List<String> childHistory = history(dataSource);
    List<String> retainedBytes = retainedOwnerRows(migrations, dataSource);
    assertMigrationPresent(childHistory, migrations.childVersion(), migrations.childScript());

    migrate(dataSource, schema, migrations.locations(), null);

    List<String> upgradedHistory = history(dataSource);
    assertHistoryPrefix(upgradedHistory, childHistory, migrations.name() + " published child");
    assertMigrationPresent(upgradedHistory, migrations.frontVersion(), migrations.frontScript());
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
    assertEntitySealPrimaryKey(migrations, dataSource);

    migrate(dataSource, schema, migrations.locations(), null);
    assertThat(history(dataSource)).containsExactlyElementsOf(upgradedHistory);
    assertThat(retainedOwnerRows(migrations, dataSource)).containsExactlyElementsOf(retainedBytes);
    assertEntitySealPrimaryKey(migrations, dataSource);
  }

  private void seedRetainedOwnerRow(
      MigrationSet migrations, String schema, DriverManagerDataSource dataSource) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (migrations == GAME_SESSION) {
      UUID fence = UUID.randomUUID();
      dsl.execute(
          "INSERT INTO game_session_canonical_roster_target_fences "
              + "(target_fence_uuid, target_binding_digest, target_binding, "
              + "minimum_retention_millis, replay_horizon_millis) VALUES (?, ?, ?, 1000, 500)",
          fence,
          "a".repeat(64),
          new byte[] {1});
      dsl.execute(
          "INSERT INTO game_session_canonical_roster_selection "
              + "(operation_uuid, target_fence_uuid, original_request) VALUES (?, ?, ?)",
          UUID.randomUUID(),
          fence,
          new byte[] {2, 3});
      return;
    }
    throw new IllegalStateException("Entity fixtures use populated snapshot cohorts");
  }

  private List<String> retainedOwnerRows(
      MigrationSet migrations, DriverManagerDataSource dataSource) {
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (migrations == GAME_SESSION) {
      return dsl
          .fetch(
              "SELECT retained_row FROM ("
                  + "SELECT jsonb_build_array('fence', target_fence_uuid::text, target_binding_digest, "
                  + "encode(target_binding, 'hex'), owner_fence, fence_state, minimum_retention_millis, "
                  + "replay_horizon_millis)::text AS retained_row "
                  + "FROM game_session_canonical_roster_target_fences UNION ALL "
                  + "SELECT jsonb_build_array('selection', operation_uuid::text, target_fence_uuid::text, "
                  + "owner_fence, encode(original_request, 'hex'), recorded_at::text)::text "
                  + "FROM game_session_canonical_roster_selection) retained ORDER BY retained_row")
          .getValues(0, String.class)
          .stream()
          .toList();
    }
    return dsl
        .fetch(
            "SELECT retained_row FROM ("
                + "SELECT 'snapshot:' || to_jsonb(t)::TEXT || ':' || t.xmin::TEXT AS retained_row "
                + "FROM entity_canonical_gameplay_roster_snapshots t UNION ALL "
                + "SELECT 'admission:' || to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_admissions t UNION ALL "
                + "SELECT 'retired_target:' || to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM entity_canonical_roster_retired_targets t) retained ORDER BY retained_row")
        .getValues(0, String.class)
        .stream()
        .toList();
  }

  private void assertEntitySealPrimaryKey(
      MigrationSet migrations, DriverManagerDataSource dataSource) {
    if (migrations != ENTITY) {
      return;
    }
    String definition =
        DSL.using(dataSource, SQLDialect.POSTGRES)
            .fetchSingle(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid = 'entity_canonical_roster_retired_targets'::regclass "
                    + "AND contype = 'p'")
            .get(0, String.class);
    assertThat(definition).contains("PRIMARY KEY (target_digest, snapshot_uuid)");
  }

  private void migrate(
      DriverManagerDataSource dataSource, String schema, List<String> locations, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(locations.toArray(String[]::new))
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .placeholders(Map.of("serviceSchema", schema));
    if (target != null) {
      configuration.target(MigrationVersion.fromVersion(target));
    }
    configuration
        .getPluginRegister()
        .getPlugin(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.load().migrate();
  }

  private List<String> history(DriverManagerDataSource dataSource) {
    return DSL.using(dataSource, SQLDialect.POSTGRES)
        .fetch(
            "SELECT to_jsonb(t)::TEXT || ':' || t.xmin::TEXT "
                + "FROM flyway_schema_history t ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private void assertHistoryPrefix(List<String> current, List<String> original, String cohort) {
    assertThat(current)
        .as("%s keeps every installed rank, script, checksum, and success flag", cohort)
        .hasSizeGreaterThan(original.size());
    assertThat(current.subList(0, original.size())).containsExactlyElementsOf(original);
  }

  private void assertMigrationPresent(List<String> history, String version, String script) {
    assertThat(history)
        .anySatisfy(
            row ->
                assertThat(row)
                    .contains(
                        "\"version\": \"" + version + "\"",
                        "\"script\": \"" + script + "\"",
                        "\"success\": true"));
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private String newSchema(String prefix) {
    String schema = prefix.replace('-', '_') + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    return schema;
  }

  private static String filesystem(Path path) {
    return "filesystem:" + path.toAbsolutePath().normalize();
  }

  private static List<String> locations(String serviceMigrations) {
    return List.of(serviceMigrations, SAGA_MIGRATIONS);
  }

  private record MigrationSet(
      String name,
      String migrationLocation,
      String parentVersion,
      String publishedChildVersion,
      String childVersion,
      String frontVersion) {
    private List<String> locations() {
      return CanonicalRosterCorrectionMigrationOrderingPostgresIntegrationTest.locations(
          migrationLocation);
    }

    private String childScript() {
      return this == GAME_SESSION
          ? "V1020__player_created_roster_reference.sql"
          : "V1018__canonical_roster_staged_retirement.sql";
    }

    private String frontScript() {
      return this == GAME_SESSION
          ? "V1021__canonical_roster_recovery_attempt_scheduling.sql"
          : "V1019__canonical_roster_snapshot_retirement_seals.sql";
    }
  }
}
