package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL receiving proof for the forward-only canonical Account issuer storage migration. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayIssuerIdentityMigrationPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String CANONICAL_ISSUER = "firemud-account-service";
  private static final String FOREIGN_ISSUER = "foreign-account-service";
  private static final String NON_CANONICAL_ACCOUNT_ISSUER = "Firemud-account-service";
  private static final String LEGACY_ISSUER = "77777777-7777-4777-8777-777777777777";
  private static final String SESSION_ID = "abababab-abab-4bab-8bab-abababababab";
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CHARACTER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID GAME_INSTANCE_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID REGION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID LEGACY_ISSUER_ID = UUID.fromString(LEGACY_ISSUER);
  private static final UUID TRANSITION_ID = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID ACCOUNT_INDEX_FENCE =
      UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID RESERVATION_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RESERVATION_FENCE =
      UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID COVERAGE_OPERATION_ID =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final long ISSUER_COVERAGE_FENCE = 13L;
  private static final UUID COVERAGE_FENCE =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final UUID COVERAGE_REQUEST_ID =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID LEGACY_COHORT_ID =
      UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
  private static final UUID LEGACY_OWNER_OPERATION_ID =
      UUID.fromString("12121212-1212-4212-8212-121212121212");
  private static final UUID LEGACY_WRITER_FENCE =
      UUID.fromString("13131313-1313-4313-8313-131313131313");
  private static final UUID ADMISSION_FENCE =
      UUID.fromString("14141414-1414-4414-8414-141414141414");
  private static final byte[] BINDING_REF = new byte[] {0x01, 0x23, 0x45};
  private static final List<String> RECREATED_UUID_DEPENDENT_CONSTRAINTS =
      List.of(
          "chk_gs_canonical_gameplay_binding_inventory_identity",
          "chk_gs_canonical_issuer_partition_reservation",
          "chk_gs_canonical_account_coverage_snapshot_inventory",
          "chk_gs_canonical_account_coverage_snapshot_reservation");
  private static final List<String> ACCOUNT_ISSUER_CONSTRAINTS =
      List.of(
          "chk_gs_gameplay_inventory_account_issuer",
          "chk_gs_issuer_reservation_account_issuer",
          "chk_gs_snapshot_inventory_account_issuer",
          "chk_gs_snapshot_transition_account_issuer",
          "chk_gs_snapshot_reservation_account_issuer");
  private static final List<String> PRESERVED_TABLES =
      List.of(
          "game_session_canonical_binding_inventory_clock",
          "game_session_canonical_binding_legacy_disposition",
          "game_session_canonical_gameplay_binding_inventory",
          "game_session_canonical_binding_transition",
          "game_session_canonical_binding_account_index_obligation",
          "game_session_canonical_issuer_partition_reservation",
          "game_session_canonical_binding_issuer_index_obligation",
          "game_session_canonical_binding_region_bridge_obligation",
          "game_session_canonical_account_coverage_control",
          "game_session_canonical_account_coverage_operation",
          "game_session_canonical_account_coverage_snapshot_inventory",
          "game_session_canonical_account_coverage_snapshot_transition",
          "game_session_canonical_account_coverage_snapshot_obligation",
          "game_session_canonical_account_coverage_snapshot_reservation",
          "gs_canonical_account_coverage_snapshot_issuer_obligation",
          "gs_canonical_account_coverage_snapshot_region_obligation");
  private static final List<IssuerField> ISSUER_FIELDS =
      List.of(
          field(
              "game_session_canonical_gameplay_binding_inventory",
              "issuer_id",
              "binding_ref = ?",
              BINDING_REF),
          field(
              "game_session_canonical_issuer_partition_reservation",
              "issuer_id",
              "reservation_id = ?",
              RESERVATION_ID),
          field(
              "game_session_canonical_account_coverage_snapshot_inventory",
              "issuer_id",
              "operation_id = ? AND row_ordinal = ?",
              COVERAGE_OPERATION_ID,
              0L),
          field(
              "game_session_canonical_account_coverage_snapshot_transition",
              "candidate_issuer_id",
              "operation_id = ? AND row_ordinal = ?",
              COVERAGE_OPERATION_ID,
              0L),
          field(
              "game_session_canonical_account_coverage_snapshot_reservation",
              "issuer_id",
              "operation_id = ? AND row_ordinal = ?",
              COVERAGE_OPERATION_ID,
              0L));
  private static final List<String> CANONICAL_INSERT_PROBES =
      List.of(
          "INSERT INTO game_session_canonical_gameplay_binding_inventory "
              + "SELECT (jsonb_populate_record(NULL::game_session_canonical_gameplay_binding_inventory, "
              + "to_jsonb(retained) || jsonb_build_object('binding_ref', decode('02468ace', 'hex'), "
              + "'issuer_id', 'firemud-account-service', "
              + "'issuer_reservation_id', '15151515-1515-4515-8515-151515151515', "
              + "'transition_id', '16161616-1616-4616-8616-161616161616', 'inventory_revision', 18))).* "
              + "FROM game_session_canonical_gameplay_binding_inventory retained "
              + "WHERE binding_ref = decode('012345', 'hex')",
          "INSERT INTO game_session_canonical_issuer_partition_reservation "
              + "SELECT (jsonb_populate_record(NULL::game_session_canonical_issuer_partition_reservation, "
              + "to_jsonb(retained) || jsonb_build_object("
              + "'reservation_id', '17171717-1717-4717-8717-171717171717', "
              + "'issuer_id', 'firemud-account-service'))).* "
              + "FROM game_session_canonical_issuer_partition_reservation retained "
              + "WHERE reservation_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'",
          "INSERT INTO game_session_canonical_account_coverage_snapshot_inventory "
              + "SELECT (jsonb_populate_record(NULL::game_session_canonical_account_coverage_snapshot_inventory, "
              + "to_jsonb(retained) || jsonb_build_object('row_ordinal', 1, "
              + "'issuer_id', 'firemud-account-service'))).* "
              + "FROM game_session_canonical_account_coverage_snapshot_inventory retained "
              + "WHERE operation_id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' AND row_ordinal = 0",
          "INSERT INTO game_session_canonical_account_coverage_snapshot_transition "
              + "SELECT (jsonb_populate_record(NULL::game_session_canonical_account_coverage_snapshot_transition, "
              + "to_jsonb(retained) || jsonb_build_object('row_ordinal', 1, "
              + "'candidate_issuer_id', 'firemud-account-service'))).* "
              + "FROM game_session_canonical_account_coverage_snapshot_transition retained "
              + "WHERE operation_id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' AND row_ordinal = 0",
          "INSERT INTO game_session_canonical_account_coverage_snapshot_reservation "
              + "SELECT (jsonb_populate_record(NULL::game_session_canonical_account_coverage_snapshot_reservation, "
              + "to_jsonb(retained) || jsonb_build_object('row_ordinal', 1, "
              + "'reservation_id', '18181818-1818-4818-8818-181818181818', "
              + "'issuer_id', 'firemud-account-service'))).* "
              + "FROM game_session_canonical_account_coverage_snapshot_reservation retained "
              + "WHERE operation_id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' AND row_ordinal = 0");

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void convertsIssuerStorageWithoutRelabelingLegacyRowsOrChangingEvidence() throws Exception {
    String schema = "gs_account_issuer_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    try {
      Flyway oldSchema = flyway(dataSource, schema, MigrationVersion.fromVersion("1022"));
      oldSchema.migrate();
      oldSchema.validate();
      assertThat(
              dsl.fetchSingle(
                      "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                  .get(0, String.class))
          .isEqualTo("1022");

      seedLegacyUuidCohort(dsl);
      Map<String, List<String>> beforeMigration = rowSnapshots(dsl);
      List<String> historyBeforeMigration = historySnapshot(dsl);
      Map<String, String> constraintsBeforeMigration = constraintSnapshot(dsl);
      Map<String, String> indexesBeforeMigration = indexSnapshot(dsl);
      for (String table : PRESERVED_TABLES) {
        assertThat(beforeMigration.get(table)).as(table).hasSize(1);
      }

      Flyway current = flyway(dataSource, schema, MigrationVersion.fromVersion("1025"));
      current.migrate();
      current.validate();

      assertThat(
              dsl.fetchSingle("SELECT count(*) FROM flyway_schema_history WHERE version = '1025'")
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              dsl.fetchSingle("SELECT script FROM flyway_schema_history WHERE version = '1025'")
                  .get(0, String.class))
          .isEqualTo("V1025__canonical_account_issuer_identity.sql");
      assertIssuerColumnsAreBoundedText(dsl);
      assertCanonicalIssuerGuardsAreNotValidated(dsl);
      assertRecreatedIdentityChecksRemainValidated(dsl);
      assertLegacyIssuerValues(dsl);
      assertThatThrownBy(() -> new CanonicalGameplayBindingInventoryRepository(dsl).readSnapshot())
          .satisfies(
              failure -> {
                Throwable rootCause = failure;
                while (rootCause.getCause() != null) {
                  rootCause = rootCause.getCause();
                }
                assertThat(rootCause)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                        "Unsupported issuer identity; expected exact Account issuer "
                            + "firemud-account-service; legacy UUID issuer identities require "
                            + "explicit recovery");
              });
      assertThat(rowSnapshots(dsl)).isEqualTo(beforeMigration);
      assertThat(historySnapshot(dsl).subList(0, historyBeforeMigration.size()))
          .containsExactlyElementsOf(historyBeforeMigration);
      assertThat(constraintSnapshot(dsl)).isEqualTo(constraintsBeforeMigration);
      assertThat(indexSnapshot(dsl)).isEqualTo(indexesBeforeMigration);
      assertThat(
              dsl.fetchSingle(
                      "SELECT disposition_state FROM game_session_canonical_binding_legacy_disposition "
                          + "WHERE singleton_id = 1")
                  .get(0, String.class))
          .isEqualTo("REQUIRED");
      assertThat(
              dsl.fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control WHERE account_id = ?",
                      ACCOUNT_ID)
                  .get(0, String.class))
          .isEqualTo("ACTIVE");
      assertThat(
              dsl.fetchSingle(
                      "SELECT snapshot_state FROM game_session_canonical_account_coverage_operation "
                          + "WHERE operation_id = ?",
                      COVERAGE_OPERATION_ID)
                  .get(0, String.class))
          .isEqualTo("BLOCKED");

      assertExactIssuerValueIsAcceptedForNewRows(dataSource);
      assertNonCanonicalIssuerWritesAreRejected(dsl);
      assertLegacyIssuerValues(dsl);
      assertThat(rowSnapshots(dsl)).isEqualTo(beforeMigration);

      List<String> migratedHistory = historySnapshot(dsl);
      Map<String, List<String>> migratedRows = rowSnapshots(dsl);
      Flyway restarted = flyway(dataSource, schema, MigrationVersion.fromVersion("1025"));
      restarted.migrate();
      restarted.validate();
      assertThat(historySnapshot(dsl)).containsExactlyElementsOf(migratedHistory);
      assertIssuerColumnsAreBoundedText(dsl);
      assertCanonicalIssuerGuardsAreNotValidated(dsl);
      assertRecreatedIdentityChecksRemainValidated(dsl);
      assertLegacyIssuerValues(dsl);
      assertThat(rowSnapshots(dsl)).isEqualTo(migratedRows);
      assertThat(constraintSnapshot(dsl)).isEqualTo(constraintsBeforeMigration);
      assertThat(indexSnapshot(dsl)).isEqualTo(indexesBeforeMigration);
    } finally {
      dropSchema(schema);
    }
  }

  private static void seedLegacyUuidCohort(DSLContext dsl) {
    // These rows model the old UUID column encoding and retained repair evidence only. They do not
    // assert Account, membership, grant, token, lease, issuer-source, or gameplay authority.
    dsl.execute(
        "UPDATE game_session_canonical_binding_inventory_clock "
            + "SET inventory_revision = 17 WHERE singleton_id = 1");
    dsl.execute(
        "INSERT INTO game_session_canonical_gameplay_binding_inventory ("
            + "binding_ref, account_id, tenant_id, playable_state_namespace_id, character_id, "
            + "playable_state_scope, game_instance_id, runtime_game_instance_id, session_id, "
            + "binding_generation, region_id, region_epoch, issuer_id, issuer_auth_generation, "
            + "issuer_index_layout_version, issuer_index_partition_count, "
            + "issuer_index_partition_capacity, issuer_partition_id, account_index_fence, "
            + "issuer_reservation_id, transition_id, lifecycle, account_index_state, "
            + "issuer_index_state, inventory_revision) "
            + "VALUES (?, ?, ?, ?, ?, 'SHARED', ?, 23, ?, 5, ?, 2, ?, 7, 3, 4, 8, 2, ?, ?, ?, "
            + "'CANDIDATE_PREPARED', 'REPAIR_REQUIRED', 'REPAIR_REQUIRED', 17)",
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID,
        LEGACY_ISSUER_ID,
        ACCOUNT_INDEX_FENCE,
        RESERVATION_ID,
        TRANSITION_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_binding_transition ("
            + "transition_id, tenant_id, playable_state_namespace_id, character_id, "
            + "expected_prior_binding_ref, expected_prior_binding_generation, "
            + "candidate_binding_ref, candidate_binding_generation, candidate_account_index_fence, "
            + "issuer_reservation_id, status, inventory_revision) "
            + "VALUES (?, ?, ?, ?, NULL, NULL, ?, 5, ?, ?, 'PREPARED', 17)",
        TRANSITION_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        BINDING_REF,
        ACCOUNT_INDEX_FENCE,
        RESERVATION_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_issuer_partition_reservation ("
            + "reservation_id, binding_ref, account_id, tenant_id, game_instance_id, "
            + "runtime_game_instance_id, session_id, binding_generation, transition_id, issuer_id, "
            + "issuer_auth_generation, playable_state_namespace_id, character_id, "
            + "playable_state_scope, region_id, region_epoch, partition_id, "
            + "issuer_index_layout_version, issuer_index_partition_count, "
            + "issuer_index_partition_capacity, lifecycle, reservation_fence, "
            + "issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence, "
            + "inventory_snapshot_revision, inventory_revision) "
            + "VALUES (?, ?, ?, ?, ?, 23, ?, 5, ?, ?, 7, ?, ?, 'SHARED', ?, 2, 2, 3, 4, 8, "
            + "'RESERVED', ?, ?, 11, ?, 17, 17)",
        RESERVATION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        TRANSITION_ID,
        LEGACY_ISSUER_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        REGION_ID,
        RESERVATION_FENCE,
        COVERAGE_OPERATION_ID,
        ISSUER_COVERAGE_FENCE);

    dsl.execute(
        "INSERT INTO game_session_canonical_binding_account_index_obligation ("
            + "transition_id, obligation_ordinal, binding_ref, account_id, tenant_id, action, "
            + "binding_generation, expected_prior_generation, account_index_fence, execution_phase, "
            + "status, inventory_revision) "
            + "VALUES (?, 0, ?, ?, ?, 'ADD_OR_RETAIN', 5, NULL, ?, 'BEFORE_FINAL_CAS', 'REQUIRED', 17)",
        TRANSITION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        ACCOUNT_INDEX_FENCE);

    dsl.execute(
        "INSERT INTO game_session_canonical_binding_issuer_index_obligation ("
            + "transition_id, binding_ref, binding_generation, reservation_id, status, inventory_revision) "
            + "VALUES (?, ?, 5, ?, 'REQUIRED', 17)",
        TRANSITION_ID,
        BINDING_REF,
        RESERVATION_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_binding_region_bridge_obligation ("
            + "transition_id, binding_ref, binding_generation, account_id, tenant_id, game_instance_id, "
            + "runtime_game_instance_id, session_id, region_id, region_epoch, status, inventory_revision) "
            + "VALUES (?, ?, 5, ?, ?, ?, 23, ?, ?, 2, 'REQUIRED', 17)",
        TRANSITION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_control (account_id, state, last_operation_fence) "
            + "VALUES (?, 'NO_ACTIVE_ACCOUNT_WIDE_FLOW', 0)",
        ACCOUNT_ID);
    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_operation ("
            + "operation_id, request_id, account_id, legacy_cohort_id, legacy_owner_operation_id, "
            + "legacy_writer_fence, legacy_source_snapshot_revision, "
            + "legacy_canonical_inventory_revision, legacy_namespace_index_readback_revision, "
            + "legacy_evidence_digest, lifecycle, operation_fence, coverage_fence, "
            + "account_admission_fence, historical_account_admission_fence, resolved_scope_kind, "
            + "snapshot_state, blocked_reason, inventory_snapshot_revision, coverage_generation, "
            + "redis_member_count, redis_member_set_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, 13, 17, 19, ?, 'ACTIVE', 11, ?, ?, NULL, "
            + "'ACCOUNT_WIDE', 'BLOCKED', 'UNRESOLVED_OBLIGATION', 17, NULL, NULL, NULL)",
        COVERAGE_OPERATION_ID,
        COVERAGE_REQUEST_ID,
        ACCOUNT_ID,
        LEGACY_COHORT_ID,
        LEGACY_OWNER_OPERATION_ID,
        LEGACY_WRITER_FENCE,
        "sha256:" + "a".repeat(64),
        COVERAGE_FENCE,
        ADMISSION_FENCE);
    dsl.execute(
        "UPDATE game_session_canonical_account_coverage_control "
            + "SET state = 'ACTIVE', last_operation_fence = 11, current_operation_id = ?, "
            + "current_operation_fence = 11, account_admission_fence = ?, coverage_fence = ?, "
            + "inventory_snapshot_revision = 17 WHERE account_id = ?",
        COVERAGE_OPERATION_ID,
        ADMISSION_FENCE,
        COVERAGE_FENCE,
        ACCOUNT_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_snapshot_inventory ("
            + "operation_id, row_ordinal, binding_ref, account_id, tenant_id, "
            + "playable_state_namespace_id, character_id, playable_state_scope, game_instance_id, "
            + "runtime_game_instance_id, session_id, binding_generation, region_id, region_epoch, "
            + "issuer_id, issuer_auth_generation, issuer_index_layout_version, "
            + "issuer_index_partition_count, issuer_index_partition_capacity, issuer_partition_id, "
            + "account_index_fence, transition_id, member_transition_id, lifecycle, "
            + "account_index_state, issuer_index_state, inventory_revision, action, exact_member, "
            + "expected_readback_state, redis_readback_state, redis_readback_member) "
            + "VALUES (?, 0, ?, ?, ?, ?, ?, 'SHARED', ?, 23, ?, 5, ?, 2, ?, 7, 3, 4, 8, 2, ?, ?, ?, "
            + "'CANDIDATE_PREPARED', 'REPAIR_REQUIRED', 'REPAIR_REQUIRED', 17, 'BLOCKED', "
            + "'synthetic-retained-member', 'BLOCKED', 'PENDING', NULL)",
        COVERAGE_OPERATION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID,
        LEGACY_ISSUER_ID,
        ACCOUNT_INDEX_FENCE,
        TRANSITION_ID,
        TRANSITION_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_snapshot_transition ("
            + "operation_id, row_ordinal, transition_id, candidate_binding_ref, "
            + "expected_prior_binding_ref, expected_prior_binding_generation, "
            + "candidate_binding_generation, candidate_account_index_fence, issuer_reservation_id, "
            + "status, inventory_revision, candidate_account_id, candidate_tenant_id, "
            + "candidate_namespace_id, candidate_character_id, candidate_playable_state_scope, "
            + "candidate_game_instance_id, candidate_runtime_game_instance_id, candidate_session_id, "
            + "candidate_region_id, candidate_region_epoch, candidate_issuer_id, "
            + "candidate_issuer_auth_generation, candidate_issuer_index_layout_version, "
            + "candidate_issuer_index_partition_count, candidate_issuer_index_partition_capacity, "
            + "account_coverage_state, account_coverage_operation_id, account_coverage_lifecycle, "
            + "account_coverage_operation_fence, account_admission_fence_kind, account_admission_fence, "
            + "historical_account_admission_fence_kind, historical_account_admission_fence, "
            + "resolved_scope_kind, resolved_scope_account_id, coverage_fence, coverage_generation, "
            + "account_inventory_snapshot_revision) "
            + "VALUES (?, 0, ?, ?, NULL, NULL, 5, ?, ?, 'PREPARED', 17, ?, ?, ?, ?, 'SHARED', ?, 23, ?, ?, 2, ?, 7, 3, 4, 8, "
            + "'NO_ACTIVE_ACCOUNT_WIDE_FLOW', NULL, NULL, NULL, 'NOT_APPLICABLE', NULL, "
            + "'NOT_APPLICABLE', NULL, 'NOT_APPLICABLE', NULL, NULL, NULL, NULL)",
        COVERAGE_OPERATION_ID,
        TRANSITION_ID,
        BINDING_REF,
        ACCOUNT_INDEX_FENCE,
        RESERVATION_ID,
        ACCOUNT_ID,
        TENANT_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID,
        LEGACY_ISSUER_ID);

    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_snapshot_obligation ("
            + "operation_id, row_ordinal, transition_id, obligation_ordinal, binding_ref, account_id, "
            + "tenant_id, action, binding_generation, expected_prior_generation, account_index_fence, "
            + "execution_phase, status, inventory_revision, projection_state, projection_member, "
            + "projection_inventory_revision, binding_lifecycle, transition_status, "
            + "expected_readback_state, exact_member, redis_readback_state, redis_readback_member) "
            + "VALUES (?, 0, ?, 0, ?, ?, ?, 'ADD_OR_RETAIN', 5, NULL, ?, 'BEFORE_FINAL_CAS', "
            + "'REQUIRED', 17, 'REPAIR_REQUIRED', NULL, NULL, 'CANDIDATE_PREPARED', 'PREPARED', "
            + "'PRESENT', 'synthetic-retained-member', 'PENDING', NULL)",
        COVERAGE_OPERATION_ID,
        TRANSITION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        ACCOUNT_INDEX_FENCE);

    dsl.execute(
        "INSERT INTO game_session_canonical_account_coverage_snapshot_reservation ("
            + "operation_id, row_ordinal, reservation_id, binding_ref, account_id, tenant_id, "
            + "binding_generation, transition_id, issuer_id, issuer_auth_generation, "
            + "playable_state_namespace_id, character_id, playable_state_scope, game_instance_id, "
            + "runtime_game_instance_id, session_id, region_id, region_epoch, partition_id, "
            + "issuer_index_layout_version, issuer_index_partition_count, "
            + "issuer_index_partition_capacity, lifecycle, reservation_fence, "
            + "issuer_coverage_operation_id, issuer_coverage_operation_fence, coverage_fence, "
            + "inventory_snapshot_revision, inventory_revision) "
            + "VALUES (?, 0, ?, ?, ?, ?, 5, ?, ?, 7, ?, ?, 'SHARED', ?, 23, ?, ?, 2, 2, 3, 4, 8, "
            + "'RESERVED', ?, ?, 11, ?, 17, 17)",
        COVERAGE_OPERATION_ID,
        RESERVATION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        TRANSITION_ID,
        LEGACY_ISSUER_ID,
        NAMESPACE_ID,
        CHARACTER_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID,
        RESERVATION_FENCE,
        COVERAGE_OPERATION_ID,
        ISSUER_COVERAGE_FENCE);

    dsl.execute(
        "INSERT INTO gs_canonical_account_coverage_snapshot_issuer_obligation ("
            + "operation_id, row_ordinal, transition_id, binding_ref, binding_generation, "
            + "reservation_id, status, inventory_revision) "
            + "VALUES (?, 0, ?, ?, 5, ?, 'REQUIRED', 17)",
        COVERAGE_OPERATION_ID,
        TRANSITION_ID,
        BINDING_REF,
        RESERVATION_ID);

    dsl.execute(
        "INSERT INTO gs_canonical_account_coverage_snapshot_region_obligation ("
            + "operation_id, row_ordinal, transition_id, binding_ref, binding_generation, account_id, "
            + "tenant_id, game_instance_id, runtime_game_instance_id, session_id, region_id, "
            + "region_epoch, status, inventory_revision) "
            + "VALUES (?, 0, ?, ?, 5, ?, ?, ?, 23, ?, ?, 2, 'REQUIRED', 17)",
        COVERAGE_OPERATION_ID,
        TRANSITION_ID,
        BINDING_REF,
        ACCOUNT_ID,
        TENANT_ID,
        GAME_INSTANCE_ID,
        SESSION_ID,
        REGION_ID);
  }

  private static void assertIssuerColumnsAreBoundedText(DSLContext dsl) {
    for (IssuerField field : ISSUER_FIELDS) {
      var column =
          dsl.fetchOne(
              "SELECT data_type, character_maximum_length FROM information_schema.columns "
                  + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
              field.table(),
              field.column());
      assertThat(column).isNotNull();
      assertThat(column.get("data_type", String.class)).isEqualTo("character varying");
      assertThat(column.get("character_maximum_length", Integer.class)).isEqualTo(64);
    }
  }

  private static void assertCanonicalIssuerGuardsAreNotValidated(DSLContext dsl) {
    for (String constraintName : ACCOUNT_ISSUER_CONSTRAINTS) {
      var constraint =
          dsl.fetchOne(
              "SELECT convalidated, pg_get_constraintdef(oid, true) AS definition "
                  + "FROM pg_constraint "
                  + "WHERE connamespace = current_schema()::regnamespace AND conname = ?",
              constraintName);
      assertThat(constraint).as(constraintName).isNotNull();
      assertThat(constraint.get("convalidated", Boolean.class)).as(constraintName).isFalse();
      assertThat(constraint.get("definition", String.class))
          .as(constraintName)
          .contains(CANONICAL_ISSUER);
    }
  }

  private static void assertRecreatedIdentityChecksRemainValidated(DSLContext dsl) {
    for (String constraintName : RECREATED_UUID_DEPENDENT_CONSTRAINTS) {
      var constraint =
          dsl.fetchOne(
              "SELECT convalidated, pg_get_constraintdef(oid, true) AS definition "
                  + "FROM pg_constraint "
                  + "WHERE connamespace = current_schema()::regnamespace AND conname = ?",
              constraintName);
      assertThat(constraint).as(constraintName).isNotNull();
      assertThat(constraint.get("convalidated", Boolean.class)).as(constraintName).isTrue();
      assertThat(constraint.get("definition", String.class))
          .as(constraintName)
          .contains("issuer_id::text <> '00000000-0000-0000-0000-000000000000'::text")
          .contains("issuer_auth_generation > 0");
    }
  }

  private static void assertLegacyIssuerValues(DSLContext dsl) {
    for (IssuerField field : ISSUER_FIELDS) {
      String issuer =
          dsl.fetchSingle(
                  "SELECT "
                      + field.column()
                      + "::text FROM "
                      + field.table()
                      + " WHERE "
                      + field.where(),
                  field.keys().toArray())
              .get(0, String.class);
      assertThat(issuer).as(field.table() + "." + field.column()).isEqualTo(LEGACY_ISSUER);
    }
  }

  private static void assertExactIssuerValueIsAcceptedForNewRows(DriverManagerDataSource dataSource)
      throws Exception {
    // These cloned rows prove only that bounded storage accepts the exact selected string. The
    // synthetic fixtures are rolled back and are not issuer-source or admission evidence.
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        for (int index = 0; index < ISSUER_FIELDS.size(); index++) {
          IssuerField field = ISSUER_FIELDS.get(index);
          try (var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate(CANONICAL_INSERT_PROBES.get(index))).isEqualTo(1);
          }
          String read =
              "SELECT count(*) FROM " + field.table() + " WHERE " + field.column() + " = ?";
          try (PreparedStatement statement = connection.prepareStatement(read)) {
            statement.setString(1, CANONICAL_ISSUER);
            try (ResultSet result = statement.executeQuery()) {
              assertThat(result.next()).isTrue();
              assertThat(result.getLong(1)).isEqualTo(1L);
            }
          }
        }
      } finally {
        connection.rollback();
      }
    }
  }

  private static void assertNonCanonicalIssuerWritesAreRejected(DSLContext dsl) {
    for (IssuerField field : ISSUER_FIELDS) {
      assertRejectedIssuerWrite(dsl, field, LEGACY_ISSUER);
      assertRejectedIssuerWrite(dsl, field, FOREIGN_ISSUER);
      assertRejectedIssuerWrite(dsl, field, NON_CANONICAL_ACCOUNT_ISSUER);
    }
  }

  private static void assertRejectedIssuerWrite(
      DSLContext dsl, IssuerField field, String attemptedIssuer) {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE "
                        + field.table()
                        + " SET "
                        + field.column()
                        + " = ? WHERE "
                        + field.where(),
                    parameters(attemptedIssuer, field.keys())))
        .isInstanceOfSatisfying(
            DataAccessException.class,
            exception -> assertThat(exception.sqlState()).isEqualTo("23514"));
  }

  private static Object[] parameters(String first, List<Object> keys) {
    Object[] parameters = new Object[keys.size() + 1];
    parameters[0] = first;
    for (int index = 0; index < keys.size(); index++) {
      parameters[index + 1] = keys.get(index);
    }
    return parameters;
  }

  private static Map<String, List<String>> rowSnapshots(DSLContext dsl) {
    Map<String, List<String>> snapshots = new LinkedHashMap<>();
    for (String table : PRESERVED_TABLES) {
      List<String> rows = new ArrayList<>();
      for (var row :
          dsl.fetch(
              "SELECT to_jsonb(retained_row)::text AS row_json FROM "
                  + table
                  + " retained_row ORDER BY to_jsonb(retained_row)::text")) {
        rows.add(row.get("row_json", String.class));
      }
      snapshots.put(table, List.copyOf(rows));
    }
    return Map.copyOf(snapshots);
  }

  private static List<String> historySnapshot(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(history_row)::text AS row_json FROM flyway_schema_history history_row "
                + "ORDER BY installed_rank")
        .getValues("row_json", String.class);
  }

  private static Map<String, String> constraintSnapshot(DSLContext dsl) {
    Map<String, String> constraints = new LinkedHashMap<>();
    for (var row :
        dsl.fetch(
            "SELECT relation.relname AS relation_name, constraint_row.conname AS constraint_name, "
                + "constraint_row.contype::text AS constraint_type, constraint_row.convalidated, "
                + "pg_get_constraintdef(constraint_row.oid, true) AS definition "
                + "FROM pg_constraint constraint_row "
                + "JOIN pg_class relation ON relation.oid = constraint_row.conrelid "
                + "WHERE constraint_row.connamespace = current_schema()::regnamespace "
                + "AND constraint_row.conname NOT IN ("
                + sqlNames(RECREATED_UUID_DEPENDENT_CONSTRAINTS)
                + ","
                + sqlNames(ACCOUNT_ISSUER_CONSTRAINTS)
                + ") ORDER BY relation.relname, constraint_row.conname")) {
      String key =
          row.get("relation_name", String.class) + "." + row.get("constraint_name", String.class);
      String value =
          row.get("constraint_type", String.class)
              + "|"
              + row.get("convalidated", Boolean.class)
              + "|"
              + row.get("definition", String.class);
      constraints.put(key, value);
    }
    return Map.copyOf(constraints);
  }

  private static Map<String, String> indexSnapshot(DSLContext dsl) {
    Map<String, String> indexes = new LinkedHashMap<>();
    for (var row :
        dsl.fetch(
            "SELECT tablename, indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname = current_schema() ORDER BY tablename, indexname")) {
      indexes.put(
          row.get("tablename", String.class) + "." + row.get("indexname", String.class),
          row.get("indexdef", String.class));
    }
    return Map.copyOf(indexes);
  }

  private static IssuerField field(String table, String column, String where, Object... keys) {
    return new IssuerField(table, column, where, List.copyOf(Arrays.asList(keys)));
  }

  private static String sqlNames(List<String> names) {
    return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (target != null) {
      configuration.target(target);
    }
    return configuration.load();
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
    try (Connection connection =
            java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Failed to dispose canonical issuer migration schema", failure);
    }
  }

  private record IssuerField(String table, String column, String where, List<Object> keys) {}
}
