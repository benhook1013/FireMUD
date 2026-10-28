package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Controlled V1038 predecessor storage, with installed guards and actual filesystem migrations.
 * Lease, terminal Account outcome and member readback are stipulated historical SQL inputs, never
 * fresh admission, issuer OPEN control, grants or evidence that a historical runtime owner ran. The
 * Account owner-composition suite separately proves authenticated abort and physical cleanup.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayRetainedFirstNewAbortAccountControlMigrationIntegrationTest {
  private static final String PREFIX = "game_session_canonical_";
  private static final List<String> RETAINED_TABLES =
      List.of(
          "binding_transition",
          "gameplay_binding_inventory",
          "issuer_partition_reservation",
          "binding_account_index_obligation",
          "binding_issuer_index_obligation",
          "binding_region_bridge_obligation",
          "account_member_repair_operation");
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID NAMESPACE = UUID.randomUUID();
  private static final UUID CHARACTER = UUID.randomUUID();
  private static final UUID TRANSITION = UUID.randomUUID();
  private static final UUID RESERVATION = UUID.randomUUID();
  private static final UUID ACCOUNT_FENCE = UUID.randomUUID();
  private static final UUID INSTANCE = UUID.randomUUID();
  private static final UUID REGION = UUID.randomUUID();
  private static final byte[] BINDING_REF = new byte[] {1, 2, 3};
  private static final String MEMBER = "accountIndexMember/v1:AQID:1:" + ACCOUNT_FENCE;
  private static final String RAW_DIGEST = "a".repeat(64);
  private static final String DIGEST = "sha256:" + RAW_DIGEST;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void v1038Point1PreservesAcknowledgedAbortAndHistoryThenAllowsLaterControlCleanup() {
    String schema = "gs_retained_abort_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dataSource.setSchema(schema);
    try {
      Flyway predecessor = flyway(dataSource, schema, "1038");
      predecessor.migrate();
      predecessor.validate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      seedControlledAcknowledgedAbort(dsl);
      // Validate the complete terminal predecessor under its original NO_ACTIVE control. All
      // installed immediate and deferred guards remain enabled; no current prepare owner is used.
      dsl.execute(
          "UPDATE " + PREFIX + "binding_transition SET inventory_revision = inventory_revision");
      seedLaterControlledAcquisitionIntent(dsl);

      Map<String, List<String>> originalRows = rows(dsl, false);
      originalRows.values().forEach(images -> assertThat(images).hasSize(1));
      Map<String, List<String>> immutableRows = rows(dsl, true);
      assertThat(
              dsl.fetchSingle("SELECT state FROM " + PREFIX + "account_coverage_control")
                  .get(0, String.class))
          .isEqualTo("ACQUISITION_INTENT");
      List<String> historyBefore = history(dsl);
      assertThat(historyBefore)
          .anyMatch(row -> row.contains("V1000__create_saga_schema.sql"))
          .anyMatch(row -> row.contains("V1001__saga_instance_table.sql"))
          .anyMatch(row -> row.contains("V1002__saga_step_table.sql"));
      assertThat(
              dsl.fetchSingle(
                      "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                  .get(0, String.class))
          .isEqualTo("1038");

      // This is the V1038 defect: a retained abort's exact original ACK must not require the
      // account to remain forever at its old NO_ACTIVE epoch. The failed transaction rolls back
      // every affected row, including the singleton inventory clock.
      assertThatThrownBy(() -> advanceRetainedRevision(dsl))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .hasMessageContaining("First-NEW Account abort requires its exact retained candidate");
      assertThat(rows(dsl, false)).isEqualTo(originalRows);
      assertThat(
              dsl.fetchSingle(
                      "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                  .get(0, Integer.class))
          .isEqualTo(5);

      Flyway upgraded = flyway(dataSource, schema, "1038.1");
      assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
      upgraded.validate();
      List<String> historyAfter = history(dsl);
      assertThat(historyAfter).hasSize(historyBefore.size() + 1);
      assertThat(historyAfter.subList(0, historyBefore.size()))
          .containsExactlyElementsOf(historyBefore);
      assertThat(historyAfter.get(historyAfter.size() - 1))
          .contains("1038.1", "V1038.1__retained_first_new_abort_cleanup_account_control.sql");
      assertThat(rows(dsl, false)).isEqualTo(originalRows);
      assertThat(
              dsl.fetchSingle(
                      "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                  .get(0, Integer.class))
          .isEqualTo(5);

      advanceRetainedRevision(dsl);
      assertThat(rows(dsl, true)).isEqualTo(immutableRows);
      for (String table : RETAINED_TABLES.subList(0, 6)) {
        assertThat(
                dsl.fetchSingle("SELECT inventory_revision FROM " + PREFIX + table)
                    .get(0, Integer.class))
            .isEqualTo(6);
      }
      assertThat(
              dsl.fetchSingle(
                      "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                  .get(0, Integer.class))
          .isEqualTo(6);
      assertThat(history(dsl)).containsExactlyElementsOf(historyAfter);
      assertThat(upgraded.migrate().migrationsExecuted).isZero();
      assertThat(history(dsl)).containsExactlyElementsOf(historyAfter);
    } finally {
      DSL.using(dataSource, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void seedControlledAcknowledgedAbort(DSLContext dsl) {
    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute("UPDATE " + PREFIX + "binding_inventory_clock SET inventory_revision = 5");
          insert(
              tx,
              "account_coverage_control",
              Map.of(
                  "account_id",
                  ACCOUNT,
                  "state",
                  "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
                  "last_operation_fence",
                  0));
          insert(
              tx,
              "account_member_repair_control",
              Map.of(
                  "account_id", ACCOUNT,
                  "last_operation_fence", 1,
                  "last_coverage_generation", 3));
          Map<String, Object> candidate = new LinkedHashMap<>();
          candidate.put("binding_ref", BINDING_REF);
          candidate.put("account_id", ACCOUNT);
          candidate.put("tenant_id", TENANT);
          candidate.put("playable_state_namespace_id", NAMESPACE);
          candidate.put("character_id", CHARACTER);
          candidate.put("playable_state_scope", "SHARED");
          candidate.put("game_instance_id", INSTANCE);
          candidate.put("runtime_game_instance_id", 1L);
          candidate.put("session_id", UUID.randomUUID().toString());
          candidate.put("binding_generation", 1);
          candidate.put("region_id", REGION);
          candidate.put("region_epoch", 1);
          candidate.put("issuer_id", "firemud-account-service");
          candidate.put("issuer_auth_generation", 1);
          candidate.put("issuer_index_layout_version", 1);
          candidate.put("issuer_index_partition_count", 1);
          candidate.put("issuer_index_partition_capacity", 3);
          candidate.put("issuer_partition_id", 0);
          candidate.put("account_index_fence", ACCOUNT_FENCE);
          candidate.put("issuer_reservation_id", RESERVATION);
          candidate.put("transition_id", TRANSITION);
          candidate.put("lifecycle", "TERMINAL_UNRESOLVED");
          candidate.put("account_index_state", "ACKNOWLEDGED");
          candidate.put("issuer_index_state", "REPAIR_REQUIRED");
          candidate.put("inventory_revision", 5);
          insert(tx, "gameplay_binding_inventory", candidate);

          Map<String, Object> transition = new LinkedHashMap<>();
          transition.put("transition_id", TRANSITION);
          transition.put("tenant_id", TENANT);
          transition.put("playable_state_namespace_id", NAMESPACE);
          transition.put("character_id", CHARACTER);
          transition.put("candidate_binding_ref", BINDING_REF);
          transition.put("candidate_binding_generation", 1);
          transition.put("candidate_account_index_fence", ACCOUNT_FENCE);
          transition.put("issuer_reservation_id", RESERVATION);
          transition.put("status", "ABORTED");
          transition.put("inventory_revision", 5);
          transition.put("admission_request_id", UUID.randomUUID());
          transition.put("admission_lease_id", UUID.randomUUID());
          transition.put("admission_lease_fence", 1);
          transition.put("admission_lease_kind", "NEW_BINDING");
          transition.put("admission_lease_digest", RAW_DIGEST);
          transition.put("admission_lease_evidence", "{\"controlledHistoricalAccountAbort\":true}");
          transition.put("admission_lease_expires_at", 1);
          transition.put("admission_orphan_cleanup_id", UUID.randomUUID());
          transition.put("admission_target_namespace", "test");
          transition.put("admission_target_tenant_slug", "historical-tenant");
          transition.put("admission_target_tenant_id", TENANT);
          transition.put("admission_target_world_slug", "historical-world");
          transition.put("admission_target_world_display_name", "Historical world");
          transition.put("admission_target_realm_id", UUID.randomUUID());
          transition.put("admission_target_realm_slug", "historical-realm");
          transition.put("admission_target_realm_display_name", "Historical realm");
          transition.put("admission_target_game_session_tenant_id", 1L);
          transition.put("admission_target_game_instance_id", 1L);
          transition.put("admission_target_playable_state_namespace_id", NAMESPACE);
          transition.put("admission_target_playable_state_scope", "SHARED");
          transition.put("admission_target_canonical_game_instance_id", INSTANCE);
          transition.put("admission_target_canonical_version_id", UUID.randomUUID());
          transition.put("admission_target_runtime_version_id", 1L);
          transition.put("admission_target_catalog_revision", 1L);
          transition.put("admission_target_pointer_version", 1L);
          transition.put("admission_target_pointer_snapshot_digest", RAW_DIGEST);
          transition.put("admission_target_active_world_epoch", 1L);
          transition.put(
              "admission_target_initial_admission_request_id", "controlled-historical-request");
          transition.put("admission_target_initial_admission_request_digest", RAW_DIGEST);
          transition.put("admission_target_origin_kind", "NO_PRIOR_POINTER");
          transition.put("admission_target_hold_id", UUID.randomUUID());
          transition.put("admission_target_hold_fence", UUID.randomUUID());
          transition.put("admission_target_hold_binding_digest", DIGEST);
          transition.put("admission_target_audit_event_id", 1L);
          transition.put("admission_target_owner_proof_digest", DIGEST);
          transition.put("admission_target_owner_proof_outcome", "COMMITTED");
          transition.put("admission_target_positive_durable_abort", false);
          transition.put(
              "admission_target_terminal_at", OffsetDateTime.parse("2026-10-26T00:00:00Z"));
          transition.put("admission_target_character_creation_policy", "{}");
          insert(tx, "binding_transition", transition);

          Map<String, Object> reservation = new LinkedHashMap<>(candidate);
          reservation.remove("issuer_partition_id");
          reservation.remove("account_index_fence");
          reservation.remove("issuer_reservation_id");
          reservation.remove("account_index_state");
          reservation.remove("issuer_index_state");
          reservation.put("reservation_id", RESERVATION);
          reservation.put("partition_id", 0);
          reservation.put("lifecycle", "RELEASE_PENDING");
          reservation.put("reservation_fence", UUID.randomUUID());
          reservation.put("issuer_coverage_operation_id", UUID.randomUUID());
          reservation.put("issuer_coverage_operation_fence", 1);
          reservation.put("coverage_fence", 1);
          reservation.put("inventory_snapshot_revision", 1);
          insert(tx, "issuer_partition_reservation", reservation);

          Map<String, Object> accountObligation = new LinkedHashMap<>();
          accountObligation.put("transition_id", TRANSITION);
          accountObligation.put("obligation_ordinal", 0);
          accountObligation.put("binding_ref", BINDING_REF);
          accountObligation.put("account_id", ACCOUNT);
          accountObligation.put("tenant_id", TENANT);
          accountObligation.put("action", "ADD_OR_RETAIN");
          accountObligation.put("binding_generation", 1);
          accountObligation.put("account_index_fence", ACCOUNT_FENCE);
          accountObligation.put("execution_phase", "BEFORE_FINAL_CAS");
          accountObligation.put("status", "ACKNOWLEDGED");
          accountObligation.put("inventory_revision", 5);
          accountObligation.put("projection_state", "PRESENT_AWAITING_COVERAGE");
          accountObligation.put("projection_member", MEMBER);
          accountObligation.put("projection_inventory_revision", 4);
          insert(tx, "binding_account_index_obligation", accountObligation);
          insert(
              tx,
              "binding_issuer_index_obligation",
              Map.of(
                  "transition_id",
                  TRANSITION,
                  "binding_ref",
                  BINDING_REF,
                  "binding_generation",
                  1,
                  "reservation_id",
                  RESERVATION,
                  "status",
                  "REQUIRED",
                  "inventory_revision",
                  5));
          Map<String, Object> region = new LinkedHashMap<>();
          for (String key :
              List.of(
                  "transition_id",
                  "binding_ref",
                  "binding_generation",
                  "account_id",
                  "tenant_id",
                  "game_instance_id",
                  "runtime_game_instance_id",
                  "session_id",
                  "region_id",
                  "region_epoch",
                  "inventory_revision")) {
            region.put(key, candidate.get(key));
          }
          region.put("status", "REQUIRED");
          insert(tx, "binding_region_bridge_obligation", region);

          Map<String, Object> repair = new LinkedHashMap<>();
          repair.put("operation_id", UUID.randomUUID());
          repair.put("transition_id", TRANSITION);
          repair.put("obligation_ordinal", 0);
          repair.put("operation_fence", 1);
          repair.put("coverage_fence", UUID.randomUUID());
          repair.put("inventory_snapshot_revision", 2);
          repair.put("binding_ref", BINDING_REF);
          repair.put("account_id", ACCOUNT);
          repair.put("tenant_id", TENANT);
          repair.put("binding_generation", 1);
          repair.put("account_index_fence", ACCOUNT_FENCE);
          repair.put("original_action", "ADD_OR_RETAIN");
          repair.put("original_execution_phase", "BEFORE_FINAL_CAS");
          repair.put("original_status", "REQUIRED");
          repair.put("original_inventory_revision", 1);
          repair.put("original_projection_state", "REQUIRED");
          repair.put("desired_action", "ADD_OR_RETAIN");
          repair.put("member", MEMBER);
          repair.put("account_control_state", "NO_ACTIVE_ACCOUNT_WIDE_FLOW");
          repair.put("account_control_epoch", 0);
          repair.put("status", "ACKNOWLEDGED");
          repair.put("coverage_generation", 3);
          repair.put("readback_state", "PRESENT");
          repair.put("readback_member", MEMBER);
          repair.put("completed_inventory_revision", 4);
          insert(tx, "account_member_repair_operation", repair);
        });
  }

  private static void seedLaterControlledAcquisitionIntent(DSLContext dsl) {
    UUID intent = UUID.randomUUID();
    UUID fence = UUID.randomUUID();
    Map<String, Object> operation = new LinkedHashMap<>();
    operation.put("operation_id", intent);
    operation.put("request_id", UUID.randomUUID());
    operation.put("account_id", ACCOUNT);
    operation.put("operation_fence", 1);
    operation.put("planned_account_admission_fence", fence);
    operation.put("inventory_snapshot_revision", 5);
    operation.put("legacy_cohort_id", UUID.randomUUID());
    operation.put("legacy_owner_operation_id", UUID.randomUUID());
    operation.put("legacy_writer_fence", UUID.randomUUID());
    operation.put("legacy_source_snapshot_revision", 1);
    operation.put("legacy_canonical_inventory_revision", 1);
    operation.put("legacy_namespace_index_readback_revision", 1);
    operation.put("legacy_evidence_digest", DIGEST);
    operation.put("intent_state", "ACQUISITION_INTENT");
    operation.put("cohort_row_count", 0L);
    operation.put("cohort_digest", DIGEST);
    operation.put("barrier_target_count", 0L);
    operation.put("barrier_target_digest", DIGEST);
    insert(dsl, "account_admission_fence_intent", operation);
    dsl.execute(
        "UPDATE "
            + PREFIX
            + "account_coverage_control SET state = 'ACQUISITION_INTENT',"
            + " last_operation_fence = 1, current_operation_fence = 1,"
            + " current_fence_intent_id = ?, account_admission_fence = ?, inventory_snapshot_revision = 5"
            + " WHERE account_id = ?",
        intent,
        fence,
        ACCOUNT);
  }

  private static void advanceRetainedRevision(DSLContext dsl) {
    dsl.transaction(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          tx.execute("UPDATE " + PREFIX + "binding_inventory_clock SET inventory_revision = 6");
          for (String table : RETAINED_TABLES.subList(0, 6)) {
            tx.execute("UPDATE " + PREFIX + table + " SET inventory_revision = 6");
          }
        });
  }

  private static Map<String, List<String>> rows(DSLContext dsl, boolean immutableOnly) {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table : RETAINED_TABLES) {
      String expression = "to_jsonb(retained)";
      if (immutableOnly && !table.equals("account_member_repair_operation")) {
        expression += " - 'inventory_revision'";
      }
      rows.put(
          table,
          dsl.fetch(
                  "SELECT ("
                      + expression
                      + ")::text AS image FROM "
                      + PREFIX
                      + table
                      + " retained ORDER BY 1")
              .getValues("image", String.class));
    }
    for (String table :
        List.of(
            "account_coverage_control",
            "account_admission_fence_intent",
            "account_member_repair_control")) {
      rows.put(
          table,
          dsl.fetch(
                  "SELECT to_jsonb(retained)::text AS image FROM "
                      + PREFIX
                      + table
                      + " retained ORDER BY 1")
              .getValues("image", String.class));
    }
    return rows;
  }

  private static void insert(DSLContext dsl, String table, Map<String, Object> values) {
    Map<Field<?>, Object> fields = new LinkedHashMap<>();
    values.forEach((column, value) -> fields.put(DSL.field(DSL.name(column)), value));
    dsl.insertInto(DSL.table(DSL.name(PREFIX + table))).set(fields).execute();
  }

  private static List<String> history(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS image FROM flyway_schema_history history_row"
                + " ORDER BY installed_rank")
        .getValues("image", String.class);
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize(),
            "filesystem:"
                + Path.of("../common-saga/src/main/resources/db/migration/saga")
                    .toAbsolutePath()
                    .normalize())
        .target(MigrationVersion.fromVersion(target))
        .load();
  }
}
