package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for V34.2 canonical gameplay integrity and runtime fence behavior. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayDecisionRuntimeFenceIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String RAW_SHA256 = "a".repeat(64);
  private static final long LEGACY_TENANT_ID = 41L;
  private static final long LEGACY_INSTANCE_ID = 7L;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void legacyRuntimeMutationAndDeletionEvaluateTheTypedCanonicalTenantFence() {
    String schema = "gs_runtime_fence_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();

      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var schemaReadback = dsl.fetchOne("SELECT current_schema() AS actual_schema");
      assertThat(schemaReadback.get("actual_schema", String.class)).isEqualTo(schema);
      assertSnapshotObligationIdentifiersCanonicalized(dsl, schema);
      var triggerReadback =
          dsl.fetchOne(
              "SELECT EXISTS (SELECT 1 FROM pg_trigger trigger_row"
                  + " JOIN pg_class relation ON relation.oid = trigger_row.tgrelid"
                  + " JOIN pg_namespace trigger_schema ON trigger_schema.oid = relation.relnamespace"
                  + " WHERE trigger_schema.nspname = ? AND relation.relname = ?"
                  + " AND trigger_row.tgname = ?) AS trigger_exists",
              schema,
              "game_instances",
              "game_instances_lease_bound_binding_runtime_fence");
      assertThat(triggerReadback.get("trigger_exists", Boolean.class)).isTrue();

      // This historical numeric-tenant row has no canonical launch association or candidate.
      // V34 must still evaluate its candidate query without comparing UUID tenant IDs to bigint.
      dsl.execute(
          "INSERT INTO game_instances (id, tenant_id, runtime_version, owner_account_id,"
              + " status, row_version, game_instance_uuid)"
              + " VALUES (?, ?, 'legacy-runtime', 99, 'RUNNING', 0, ?)",
          LEGACY_INSTANCE_ID,
          LEGACY_TENANT_ID,
          UUID.randomUUID());

      assertThat(
              dsl.execute(
                  "UPDATE game_instances SET status = 'STOPPED'"
                      + " WHERE tenant_id = ? AND id = ?",
                  LEGACY_TENANT_ID,
                  LEGACY_INSTANCE_ID))
          .isEqualTo(1);
      var statusReadback =
          dsl.fetchOne(
              "SELECT status AS actual_status FROM game_instances"
                  + " WHERE tenant_id = ? AND id = ?",
              LEGACY_TENANT_ID,
              LEGACY_INSTANCE_ID);
      assertThat(statusReadback.get("actual_status", String.class)).isEqualTo("STOPPED");
      assertThat(
              dsl.execute(
                  "DELETE FROM game_instances WHERE tenant_id = ? AND id = ?",
                  LEGACY_TENANT_ID,
                  LEGACY_INSTANCE_ID))
          .isEqualTo(1);
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void exactLeaseBoundCandidatePinsRuntimeAndRejectsTerminalTransition() {
    String schema = "gs_runtime_fence_exact_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
      byte[] candidateRef = seedCandidateAndPreparedTransition(dsl, target, CandidateMismatch.NONE);

      markProvisional(dsl, target, candidateRef);

      assertThat(
              dsl.execute(
                  "UPDATE game_instances SET runtime_version = 'unrelated-runtime-update',"
                      + " row_version = row_version + 1 WHERE tenant_id = ? AND id = ?",
                  target.gameSessionTenantId(),
                  target.gameInstanceId()))
          .isEqualTo(1);
      var unrelatedUpdateReadback =
          dsl.fetchOne(
              "SELECT runtime_version, row_version FROM game_instances"
                  + " WHERE tenant_id = ? AND id = ?",
              target.gameSessionTenantId(),
              target.gameInstanceId());
      assertThat(unrelatedUpdateReadback.get("runtime_version", String.class))
          .isEqualTo("unrelated-runtime-update");
      assertThat(unrelatedUpdateReadback.get("row_version", Long.class)).isEqualTo(2L);

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET status = 'STOPPED'"
                          + " WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET version_id = version_id + 1"
                          + " WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET run_owned_start_request_id = 'replacement-start'"
                          + " WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET run_owned_start_published_release_bundle_ref ="
                          + " 'replacement-release-ref' WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET run_owned_start_active_epoch ="
                          + " run_owned_start_active_epoch + 1 WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET row_version = row_version - 1"
                          + " WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "DELETE FROM game_instances WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining("runtime row is fenced");

      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_session_canonical_binding_transition"
                          + " SET status = 'ABORTED' WHERE transition_id = ("
                          + " SELECT transition_id FROM game_session_canonical_binding_transition"
                          + " WHERE candidate_binding_ref = ?)",
                      candidateRef))
          .hasMessageContaining("definitive first-NEW Account abort evidence");

      assertThat(
              java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT status FROM game_instances WHERE tenant_id = ? AND id = ?",
                          target.gameSessionTenantId(),
                          target.gameInstanceId()),
                      "runtime row")
                  .get("status", String.class))
          .isEqualTo("RUNNING");
      assertThat(
              java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT status FROM game_session_canonical_binding_transition"
                              + " WHERE candidate_binding_ref = ?",
                          candidateRef),
                      "binding transition row")
                  .get("status", String.class))
          .isEqualTo("PROVISIONAL");
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void stoppedRuntimeCannotAcquireLeaseBoundBindingFence() {
    String schema = "gs_runtime_fence_stopped_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
      byte[] candidateRef = seedCandidateAndPreparedTransition(dsl, target, CandidateMismatch.NONE);
      assertThat(
              dsl.execute(
                  "UPDATE game_instances SET status = 'STOPPED'"
                      + " WHERE tenant_id = ? AND id = ?",
                  target.gameSessionTenantId(),
                  target.gameInstanceId()))
          .isEqualTo(1);

      assertThatThrownBy(() -> markProvisional(dsl, target, candidateRef))
          .hasMessageContaining("exact current RUNNING launch row and candidate identity");
      assertThat(
              java.util.Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT status FROM game_session_canonical_binding_transition"
                              + " WHERE candidate_binding_ref = ?",
                          candidateRef),
                      "binding transition row")
                  .get("status", String.class))
          .isEqualTo("PREPARED");
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void freshSourceBoundRuntimeWithoutHumanCompletionCannotLoseRunOwnedLaunchProvenance() {
    String schema = "gs_runtime_fence_runowned_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
      byte[] candidateRef = seedCandidateAndPreparedTransition(dsl, target, CandidateMismatch.NONE);

      // A fresh source-bound RUNNING row cannot be stripped of its run-owned launch association.
      // The V26 deferred guard rejects that impossible setup before V1044's lease-bound fence runs.
      assertThatThrownBy(
              () ->
                  dsl.execute(
                      "UPDATE game_instances SET row_version = row_version + 1,"
                          + " run_owned_start_request_id = NULL,"
                          + " run_owned_start_request_digest = NULL,"
                          + " run_owned_start_published_release_bundle_ref = NULL,"
                          + " run_owned_start_preparing_epoch = NULL,"
                          + " run_owned_start_active_epoch = NULL"
                          + " WHERE tenant_id = ? AND id = ?",
                      target.gameSessionTenantId(),
                      target.gameInstanceId()))
          .hasMessageContaining(
              "Fresh Game Session instance requires one exact complete launch association");

      var runtimeReadback =
          dsl.fetchSingle(
              "SELECT status, row_version, run_owned_start_request_id,"
                  + " run_owned_start_request_digest, run_owned_start_published_release_bundle_ref,"
                  + " run_owned_start_preparing_epoch, run_owned_start_active_epoch"
                  + " FROM game_instances WHERE tenant_id = ? AND id = ?",
              target.gameSessionTenantId(),
              target.gameInstanceId());
      assertThat(runtimeReadback.get("status", String.class)).isEqualTo("RUNNING");
      assertThat(runtimeReadback.get("row_version", Long.class)).isEqualTo(1L);
      assertThat(runtimeReadback.get("run_owned_start_request_id", String.class))
          .isEqualTo(target.controlPlaneRequestId());
      assertThat(runtimeReadback.get("run_owned_start_request_digest", String.class)).isNotNull();
      assertThat(runtimeReadback.get("run_owned_start_published_release_bundle_ref", String.class))
          .isEqualTo(target.publishedReleaseBundleRef());
      assertThat(runtimeReadback.get("run_owned_start_preparing_epoch", Long.class)).isNotNull();
      assertThat(runtimeReadback.get("run_owned_start_active_epoch", Long.class)).isNotNull();
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) AS actual_count"
                          + " FROM game_session_canonical_human_runtime_completion"
                          + " WHERE target_namespace = ? AND initial_admission_request_id = ?",
                      target.targetNamespace(),
                      target.initialAdmissionRequestId())
                  .get("actual_count", Long.class))
          .isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition"
                          + " WHERE candidate_binding_ref = ?",
                      candidateRef)
                  .get("status", String.class))
          .isEqualTo("PREPARED");
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void mismatchedCandidateTenantRuntimeOrCanonicalInstanceCannotAcquireFence() {
    String schema = "gs_runtime_fence_candidate_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);

      for (CandidateMismatch mismatch : CandidateMismatch.values()) {
        if (mismatch == CandidateMismatch.NONE) {
          continue;
        }
        byte[] candidateRef = seedCandidateAndPreparedTransition(dsl, target, mismatch);
        assertThatThrownBy(() -> markProvisional(dsl, target, candidateRef))
            .as("candidate mismatch %s", mismatch)
            .hasMessageContaining("exact current RUNNING launch row and candidate identity");
      }
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void retainedTenantPayloadAndHoldCannotBeTruncatedAroundTheirRowGuards() {
    String schema = "gs_retained_truncate_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

      assertThatThrownBy(
              () -> dsl.execute("TRUNCATE game_session_retained_tenant_association_payload"))
          .hasMessageContaining("expiry cannot be bypassed");
      assertThatThrownBy(
              () -> dsl.execute("TRUNCATE game_session_retained_tenant_association_legal_hold"))
          .hasMessageContaining("audit cannot be bypassed");
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void launchPreparationMustReferenceTheFullImmutableCatalogIdentity() {
    String schema = "gs_launch_prep_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrate(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget otherSourceIntakeOwner =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(
              dataSource, UUID.randomUUID());

      assertThat(
              insertLaunchPreparation(
                  dsl,
                  target,
                  target.realmId(),
                  target.catalogCreationRequestId(),
                  target.catalogRevision(),
                  target.canonicalTenantId(),
                  target.sourceIntakeOperationId(),
                  target.sourceIntakeRequestId()))
          .isEqualTo(1);
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          target.realmId(),
          UUID.randomUUID(),
          target.catalogRevision(),
          target.canonicalTenantId(),
          target.sourceIntakeOperationId(),
          target.sourceIntakeRequestId());
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          target.realmId(),
          target.catalogCreationRequestId(),
          target.catalogRevision() + 1,
          target.canonicalTenantId(),
          target.sourceIntakeOperationId(),
          target.sourceIntakeRequestId());
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          target.realmId(),
          target.catalogCreationRequestId(),
          target.catalogRevision(),
          UUID.randomUUID(),
          target.sourceIntakeOperationId(),
          target.sourceIntakeRequestId());
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          target.realmId(),
          target.catalogCreationRequestId(),
          target.catalogRevision(),
          target.canonicalTenantId(),
          otherSourceIntakeOwner.sourceIntakeOperationId(),
          target.sourceIntakeRequestId());
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          target.realmId(),
          target.catalogCreationRequestId(),
          target.catalogRevision(),
          target.canonicalTenantId(),
          target.sourceIntakeOperationId(),
          UUID.randomUUID());
      assertLaunchPreparationCatalogMismatchRejected(
          dsl,
          target,
          UUID.randomUUID(),
          target.catalogCreationRequestId(),
          target.catalogRevision(),
          target.canonicalTenantId(),
          target.sourceIntakeOperationId(),
          target.sourceIntakeRequestId());
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void regionExecutorFenceForwardMigrationPreservesIssuedAndNullRequiredSources() {
    String schema = "gs_executor_forward_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    try {
      migrateToRegionFencePredecessor(schema, dataSource);
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      var target = CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
      long issuedFence =
          dsl.fetchSingle("SELECT nextval('game_session_region_executor_fence_seq') AS fence")
              .get("fence", Long.class);
      assertThat(issuedFence).isPositive();
      seedRequiredRegionSource(dsl, target, Long.toString(issuedFence));
      seedRequiredRegionSource(dsl, target, null);
      seedRequiredRegionSource(dsl, target, Long.toString(Long.MAX_VALUE));
      var before = requiredRegionSourceImages(dsl);

      migrate(schema, dataSource);

      assertThat(requiredRegionSourceImages(dsl)).isEqualTo(before);
      // The later created-origin extension adds a nullable field without adopting preseeded rows.
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) AS count FROM game_session_canonical_binding_region_bridge_obligation"
                          + " WHERE entity_created_origin IS NOT NULL")
                  .get("count", Long.class))
          .isZero();
      assertThat(regionExecutorFenceType(dsl, schema)).isEqualTo("bigint");
      assertThat(
              dsl.fetchSingle(
                      "SELECT count(*) AS count FROM game_session_canonical_binding_region_bridge_obligation"
                          + " WHERE status = 'ACKNOWLEDGED'")
                  .get("count", Long.class))
          .isZero();
    } finally {
      dropSchema(schema);
    }
  }

  @Test
  void regionExecutorFenceForwardMigrationRefusesFractionalOverflowAndNonfiniteRetainedSources() {
    for (String invalid : java.util.List.of("1.5", "9223372036854775808", "NaN", "Infinity")) {
      String schema = "gs_executor_refuse_" + UUID.randomUUID().toString().replace("-", "");
      DriverManagerDataSource dataSource = dataSource(schema);
      try {
        migrateToRegionFencePredecessor(schema, dataSource);
        DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        var target = CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource);
        seedRequiredRegionSource(dsl, target, invalid);
        var before = requiredRegionSourceImages(dsl);

        assertThatThrownBy(() -> migrate(schema, dataSource))
            .as("retained executor fence %s", invalid)
            .hasStackTraceContaining(
                "Retained region executor fence must be an exact positive BIGINT");

        assertThat(requiredRegionSourceImages(dsl)).isEqualTo(before);
        assertThat(regionExecutorFenceType(dsl, schema)).isEqualTo("numeric");
      } finally {
        dropSchema(schema);
      }
    }
  }

  private static void migrateToRegionFencePredecessor(
      String schema, DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target("1044.1")
        .load()
        .migrate();
  }

  private static java.util.List<String> requiredRegionSourceImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT (to_jsonb(obligation) - 'entity_created_origin')::text AS image"
                + " FROM game_session_canonical_binding_region_bridge_obligation obligation"
                + " ORDER BY transition_id")
        .getValues("image", String.class);
  }

  private static String regionExecutorFenceType(DSLContext dsl, String schema) {
    return dsl.fetchSingle(
            "SELECT data_type FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'game_session_canonical_binding_region_bridge_obligation'"
                + " AND column_name = 'executor_fence'",
            schema)
        .get("data_type", String.class);
  }

  /**
   * Controlled REQUIRED staging rows prove retained storage only; none asserts bridge ACK
   * authority.
   */
  private static void seedRequiredRegionSource(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      String fence) {
    byte[] candidateRef = seedCandidateAndPreparedTransition(dsl, target, CandidateMismatch.NONE);
    dsl.execute(
        "INSERT INTO game_session_canonical_binding_region_bridge_obligation"
            + " (transition_id, binding_ref, binding_generation, account_id, tenant_id,"
            + " game_instance_id, runtime_game_instance_id, session_id, region_id, region_epoch,"
            + " status, inventory_revision) SELECT transition_id, binding_ref, binding_generation,"
            + " account_id, tenant_id, game_instance_id, runtime_game_instance_id, session_id,"
            + " region_id, region_epoch, 'REQUIRED', inventory_revision"
            + " FROM game_session_canonical_gameplay_binding_inventory WHERE binding_ref = ?",
        candidateRef);
    if (fence != null) {
      dsl.execute(
          "UPDATE game_session_canonical_binding_region_bridge_obligation SET"
              + " bridge_operation_id = ?, bridge_preimage_state = 'ABSENT', runtime_entity_id = 1,"
              + " entity_projection_generation = 1, entity_projection_fence = 1,"
              + " entity_workload_namespace = 'controlled-storage-input', assignment_uuid = ?,"
              + " assignment_intent_digest = ?, entity_reference_digest = ?, world_source_digest = ?,"
              + " canonical_region_id = ?, world_lifecycle_epoch = 2, world_lifecycle_row_version = 0,"
              + " world_capture_id = ?, world_graph_sha256 = ?, world_preparation_input_digest = ?,"
              + " executor_fence = cast(? as numeric), lease_token_correlation = ?"
              + " WHERE binding_ref = ?",
          UUID.randomUUID(),
          UUID.randomUUID(),
          RAW_SHA256,
          RAW_SHA256,
          RAW_SHA256,
          UUID.randomUUID(),
          UUID.randomUUID(),
          RAW_SHA256,
          "sha256:" + RAW_SHA256,
          fence,
          RAW_SHA256,
          candidateRef);
    }
  }

  private static void migrate(String schema, DriverManagerDataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static void assertSnapshotObligationIdentifiersCanonicalized(
      DSLContext dsl, String schema) {
    var canonicalTables =
        dsl.fetchOne(
            "SELECT count(*) AS actual_count FROM pg_class relation"
                + " JOIN pg_namespace relation_schema ON relation_schema.oid = relation.relnamespace"
                + " WHERE relation_schema.nspname = ? AND relation.relkind = 'r'"
                + " AND relation.relname IN (?, ?)",
            schema,
            "gs_canonical_account_coverage_snapshot_issuer_obligation",
            "gs_canonical_account_coverage_snapshot_region_obligation");
    assertThat(canonicalTables.get("actual_count", Long.class)).isEqualTo(2L);

    var canonicalForeignKeys =
        dsl.fetchOne(
            "SELECT count(*) AS actual_count FROM pg_constraint constraint_row"
                + " JOIN pg_class relation ON relation.oid = constraint_row.conrelid"
                + " JOIN pg_namespace relation_schema ON relation_schema.oid = relation.relnamespace"
                + " JOIN pg_class referenced_relation ON referenced_relation.oid = constraint_row.confrelid"
                + " JOIN pg_namespace referenced_schema"
                + " ON referenced_schema.oid = referenced_relation.relnamespace"
                + " JOIN pg_attribute source_column ON source_column.attrelid = relation.oid"
                + " AND source_column.attnum = constraint_row.conkey[1]"
                + " AND source_column.attname = 'operation_id'"
                + " JOIN pg_attribute referenced_column"
                + " ON referenced_column.attrelid = referenced_relation.oid"
                + " AND referenced_column.attnum = constraint_row.confkey[1]"
                + " AND referenced_column.attname = 'operation_id'"
                + " WHERE constraint_row.contype = 'f' AND relation_schema.nspname = ?"
                + " AND referenced_schema.nspname = ?"
                + " AND array_length(constraint_row.conkey, 1) = 1"
                + " AND array_length(constraint_row.confkey, 1) = 1"
                + " AND referenced_relation.relname ="
                + " 'game_session_canonical_account_coverage_operation'"
                + " AND ((relation.relname = ? AND constraint_row.conname = ?)"
                + " OR (relation.relname = ? AND constraint_row.conname = ?))",
            schema,
            schema,
            "gs_canonical_account_coverage_snapshot_issuer_obligation",
            "fk_gs_account_coverage_snapshot_issuer_operation",
            "gs_canonical_account_coverage_snapshot_region_obligation",
            "fk_gs_account_coverage_snapshot_region_operation");
    assertThat(canonicalForeignKeys.get("actual_count", Long.class)).isEqualTo(2L);

    var legacyTables =
        dsl.fetchOne(
            "SELECT count(*) AS actual_count FROM pg_class relation"
                + " JOIN pg_namespace relation_schema ON relation_schema.oid = relation.relnamespace"
                + " WHERE relation_schema.nspname = ? AND relation.relkind = 'r'"
                + " AND relation.relname IN (?, ?)",
            schema,
            "game_session_canonical_account_coverage_snapshot_issuer_obligat",
            "game_session_canonical_account_coverage_snapshot_region_obligat");
    assertThat(legacyTables.get("actual_count", Long.class)).isZero();

    var legacyForeignKeys =
        dsl.fetchOne(
            "SELECT count(*) AS actual_count FROM pg_constraint constraint_row"
                + " JOIN pg_class relation ON relation.oid = constraint_row.conrelid"
                + " JOIN pg_namespace relation_schema ON relation_schema.oid = relation.relnamespace"
                + " WHERE relation_schema.nspname = ? AND constraint_row.contype = 'f'"
                + " AND constraint_row.conname IN (?, ?)",
            schema,
            "fk_gs_canonical_account_coverage_snapshot_issuer_obligation_ope",
            "fk_gs_canonical_account_coverage_snapshot_region_obligation_ope");
    assertThat(legacyForeignKeys.get("actual_count", Long.class)).isZero();
  }

  private static byte[] seedCandidateAndPreparedTransition(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      CandidateMismatch mismatch) {
    UUID candidateTenant =
        mismatch == CandidateMismatch.TENANT ? UUID.randomUUID() : target.canonicalTenantId();
    long candidateRuntimeId =
        mismatch == CandidateMismatch.RUNTIME_GAME_INSTANCE_ID
            ? target.gameInstanceId() + 1
            : target.gameInstanceId();
    UUID candidateGameInstanceUuid =
        mismatch == CandidateMismatch.CANONICAL_GAME_INSTANCE_UUID
            ? UUID.randomUUID()
            : target.gameInstanceUuid();
    return CanonicalGameplayBindingRuntimeTestFixtures.seedCandidateAndPreparedTransition(
        dsl,
        target.playableTarget(),
        candidateTenant,
        candidateRuntimeId,
        candidateGameInstanceUuid);
  }

  private static void markProvisional(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget runtimeTarget,
      byte[] candidateRef) {
    CanonicalGameplayBindingRuntimeTestFixtures.markProvisional(
        dsl, runtimeTarget.playableTarget(), candidateRef);
  }

  private static void assertLaunchPreparationCatalogMismatchRejected(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      UUID realmId,
      UUID catalogCreationRequestId,
      long catalogRevision,
      UUID canonicalTenantId,
      UUID sourceIntakeOperationId,
      UUID sourceIntakeRequestId) {
    assertThatThrownBy(
            () ->
                insertLaunchPreparation(
                    dsl,
                    target,
                    realmId,
                    catalogCreationRequestId,
                    catalogRevision,
                    canonicalTenantId,
                    sourceIntakeOperationId,
                    sourceIntakeRequestId))
        .hasMessageContaining("fk_gs_canonical_launch_preparation_catalog");
  }

  private static int insertLaunchPreparation(
      DSLContext dsl,
      CanonicalGameplayBindingRuntimeTestFixtures.RuntimeTarget target,
      UUID realmId,
      UUID catalogCreationRequestId,
      long catalogRevision,
      UUID canonicalTenantId,
      UUID sourceIntakeOperationId,
      UUID sourceIntakeRequestId) {
    return dsl.execute(
        "INSERT INTO game_session_canonical_launch_preparation"
            + " (target_namespace, control_plane_request_id, operation_id,"
            + " acting_account_uuid, canonical_tenant_id, realm_id,"
            + " catalog_creation_request_id, catalog_revision,"
            + " source_intake_operation_id, source_intake_request_id,"
            + " source_registration_request_id, source_operation_id,"
            + " catalog_request_digest, catalog_receipt_digest,"
            + " source_intake_request_digest, source_intake_receipt_digest,"
            + " source_evidence_digest, descriptor_request_digest,"
            + " descriptor_result_digest, request_digest, receipt_digest,"
            + " request_evidence_json, catalog_evidence_json,"
            + " source_intake_evidence_json, launch_descriptor_evidence_json)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)",
        target.targetNamespace(),
        "preparation-" + UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        canonicalTenantId,
        realmId,
        catalogCreationRequestId,
        catalogRevision,
        sourceIntakeOperationId,
        sourceIntakeRequestId,
        target.sourceRegistrationRequestId(),
        target.sourceOperationId(),
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256,
        "sha256:" + RAW_SHA256);
  }

  private enum CandidateMismatch {
    NONE,
    TENANT,
    RUNTIME_GAME_INSTANCE_ID,
    CANONICAL_GAME_INSTANCE_UUID
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = postgres.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static void dropSchema(String schema) {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to dispose runtime-fence test schema", failure);
    }
  }
}
