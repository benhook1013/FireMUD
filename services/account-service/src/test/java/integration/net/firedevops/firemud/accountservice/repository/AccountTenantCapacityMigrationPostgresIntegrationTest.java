package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Real forward migration with retained tenant provenance. No existing usage is guessed. */
class AccountTenantCapacityMigrationPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void existingTenantStaysUninitializedAndAPlainNewImportCannotInitializeCapacity() {
    String schema = "capacity_migration_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1038")
          .load()
          .migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var repository = new FreshTenantIdentityAssociationRepository(dsl, "prod");
      var existing = source();
      transaction.executeWithoutResult(status -> repository.importVerified(existing));
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
      var retained = transaction.execute(status -> repository.read(existing.canonicalTenantId()));
      assertThat(retained).contains(existing);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM account_fresh_tenant_identity_associations WHERE canonical_tenant_id = ?",
                          existing.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNull();
      var later = source();
      transaction.executeWithoutResult(status -> repository.importVerified(later));
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM account_fresh_tenant_identity_associations WHERE canonical_tenant_id = ?",
                          later.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNotNull();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void freshAndPopulated1040Through1041UpgradesPreserveRowsHistoryAndScopeReservationChecks() {
    verify1040Upgrade(false);
    verify1040Upgrade(true);
  }

  private static void verify1040Upgrade(boolean populated) {
    String schema = "capacity_trigger_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      configureFlyway(data, schema, "1040").migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      CapacityFixture capacity = populated ? populateCapacity(dsl, transaction) : null;
      PredecessorImage before1041 = predecessorImage(dsl);
      configureFlyway(data, schema, "1041").migrate();
      assertThat(migrationHistoryImage(dsl))
          .startsWithElementsOf(before1041.migrationHistoryImage());
      assertThat(capacityTableImages(dsl)).isEqualTo(before1041.tableImages());
      assertThat(otherCapacityTriggerImages(dsl)).isEqualTo(before1041.otherTriggerImages());
      assertThat(exactUsageFunctionImage(dsl)).isEqualTo(before1041.exactUsageFunctionImage());
      assertThat(migrationChecksum(dsl)).isEqualTo(before1041.migrationChecksum());
      assertThat(
              dsl.fetch(
                      "SELECT version FROM flyway_schema_history WHERE success AND version IN ('1040', '1041') ORDER BY installed_rank")
                  .getValues("version", String.class))
          .containsExactly("1040", "1041");
      PredecessorImage before = predecessorImage(dsl);

      configureFlyway(data, schema, "1042").migrate();

      assertThat(migrationHistoryImage(dsl)).isEqualTo(before.migrationHistoryImage());
      assertThat(migrationChecksum(dsl)).isEqualTo(before.migrationChecksum());
      assertThat(capacityTableImages(dsl)).isEqualTo(before.tableImages());
      assertThat(otherCapacityTriggerImages(dsl)).isEqualTo(before.otherTriggerImages());
      assertThat(exactUsageFunctionImage(dsl)).isEqualTo(before.exactUsageFunctionImage());
      assertThat(
              dsl.fetch(
                      "SELECT version FROM flyway_schema_history WHERE success AND version IN ('1040', '1041', '1042') ORDER BY installed_rank")
                  .getValues("version", String.class))
          .containsExactly("1040", "1041", "1042");
      assertThat(configureFlyway(data, schema, "1042").validateWithResult().validationSuccessful)
          .isTrue();
      assertReservationConstraintTriggers(dsl);

      if (populated) {
        assertCapacityTriggerBehavior(dsl, transaction, Objects.requireNonNull(capacity));
      }
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static Flyway configureFlyway(javax.sql.DataSource data, String schema, String target) {
    return Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private static PredecessorImage predecessorImage(DSLContext dsl) {
    return new PredecessorImage(
        migrationHistoryImage(dsl),
        migrationChecksum(dsl),
        capacityTableImages(dsl),
        otherCapacityTriggerImages(dsl),
        exactUsageFunctionImage(dsl));
  }

  private static List<String> migrationHistoryImage(DSLContext dsl) {
    return List.copyOf(
        dsl.fetch(
                "SELECT (to_jsonb(history) || jsonb_build_object('_original_xmin', history.xmin::text))::text AS history_image "
                    + "FROM flyway_schema_history history WHERE version IS DISTINCT FROM '1042' ORDER BY installed_rank")
            .getValues("history_image", String.class));
  }

  private static Integer migrationChecksum(DSLContext dsl) {
    return Objects.requireNonNull(
        dsl.fetchSingle("SELECT checksum FROM flyway_schema_history WHERE version = '1040'")
            .get(0, Integer.class));
  }

  private static List<String> capacityTableImages(DSLContext dsl) {
    List<String> images = new ArrayList<>();
    for (String table :
        List.of(
            "accounts",
            "account_canonical_tenant_identity_claims",
            "account_fresh_tenant_identity_associations",
            "account_tenant_creation_bootstrap_operations",
            "account_tenant_capacity_usage",
            "account_tenant_capacity_evaluations",
            "account_tenant_capacity_reservations",
            "account_tenant_capacity_attempt_history",
            "account_tenant_capacity_recovery_evidence")) {
      images.add(table);
      images.addAll(
          dsl.fetch(
                  "SELECT (to_jsonb(row_data) || jsonb_build_object('_original_xmin', row_data.xmin::text))::text AS row_image "
                      + "FROM "
                      + table
                      + " row_data ORDER BY row_image")
              .getValues("row_image", String.class));
    }
    return List.copyOf(images);
  }

  private static List<String> otherCapacityTriggerImages(DSLContext dsl) {
    return List.copyOf(
        dsl.fetch(
                "SELECT relation.relname || ':' || t.tgname || ':' || t.tgenabled::text || ':' "
                    + "|| t.tgdeferrable::text || ':' || t.tginitdeferred::text || ':' "
                    + "|| pg_get_triggerdef(t.oid) AS trigger_image "
                    + "FROM pg_trigger t JOIN pg_class relation ON relation.oid = t.tgrelid "
                    + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                    + "WHERE namespace.nspname = current_schema() AND NOT t.tgisinternal "
                    + "AND relation.relname IN ('account_tenant_capacity_usage', "
                    + "'account_tenant_capacity_evaluations', 'account_tenant_capacity_reservations', "
                    + "'account_tenant_capacity_attempt_history', 'account_tenant_capacity_recovery_evidence') "
                    + "AND t.tgname NOT LIKE 'account_tenant_capacity_reservation_complete%' "
                    + "ORDER BY relation.relname, t.tgname")
            .getValues("trigger_image", String.class));
  }

  private static String exactUsageFunctionImage(DSLContext dsl) {
    return Objects.requireNonNull(
        dsl.fetchSingle(
                "SELECT pg_get_functiondef('account_tenant_capacity_exact_usage()'::regprocedure)")
            .get(0, String.class));
  }

  private static void assertReservationConstraintTriggers(DSLContext dsl) {
    assertReservationConstraintTrigger(
        dsl, "account_tenant_capacity_reservation_complete_insert", "after insert", null);
    assertReservationConstraintTrigger(
        dsl,
        "account_tenant_capacity_reservation_complete_update",
        "after update",
        "old.state is distinct from new.state");
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM pg_trigger WHERE tgrelid = "
                        + "'account_tenant_capacity_reservations'::regclass "
                        + "AND NOT tgisinternal AND tgname LIKE "
                        + "'account_tenant_capacity_reservation_complete%'")
                .get(0, Integer.class))
        .isEqualTo(2);
  }

  private static void assertReservationConstraintTrigger(
      DSLContext dsl, String name, String event, String condition) {
    Record trigger =
        Objects.requireNonNull(
            dsl.fetchSingle(
                "SELECT tgdeferrable, tginitdeferred, pg_get_triggerdef(oid) AS definition "
                    + "FROM pg_trigger WHERE tgrelid = 'account_tenant_capacity_reservations'::regclass "
                    + "AND NOT tgisinternal AND tgname = ?",
                name));
    assertThat(trigger.get("tgdeferrable", Boolean.class)).isTrue();
    assertThat(trigger.get("tginitdeferred", Boolean.class)).isTrue();
    String definition = trigger.get("definition", String.class).toLowerCase(Locale.ROOT);
    assertThat(definition).contains(event);
    assertThat(definition).contains("execute function account_tenant_capacity_exact_usage()");
    if (condition != null) {
      assertThat(definition.replace("::text", "").replace("(", "").replace(")", ""))
          .contains(condition);
    }
  }

  private static CapacityFixture populateCapacity(DSLContext dsl, TransactionTemplate transaction) {
    UUID accountUuid =
        Objects.requireNonNull(
            transaction.execute(
                status ->
                    dsl.fetchSingle(
                            "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                                + "RETURNING account_uuid",
                            "capacity-" + UUID.randomUUID(),
                            "capacity-" + UUID.randomUUID() + "@example.test",
                            "test-password")
                        .get(0, UUID.class)));
    FreshTenantCreationEvidence tenant = source();
    UUID bootstrapRequest = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID admissionId = UUID.randomUUID();
    String evaluationIdentity = digest('a');
    String callerWorkload = "spiffe://firemud.test/account/game-session-service";

    transaction.executeWithoutResult(
        status -> {
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant);
          dsl.execute(
              "INSERT INTO account_tenant_creation_bootstrap_operations "
                  + "(request_id, schema_version, initiating_account_uuid, tenant_uuid, "
                  + "creation_request_id, creation_operation_id, account_authorization_operation_id, "
                  + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                  + "source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, "
                  + "baseline_membership_version, baseline_membership_authority_generation, "
                  + "baseline_event_sequence, status) "
                  + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
              bootstrapRequest,
              accountUuid,
              tenant.canonicalTenantId(),
              tenant.creationRequestId(),
              tenant.operationId(),
              bootstrapRequest,
              digest('d'),
              tenant.evidenceDigest(),
              new byte[] {1},
              new byte[] {2},
              digest('e'),
              new byte[] {3},
              digest('f'));
          dsl.execute(
              "UPDATE account_tenant_creation_bootstrap_operations SET "
                  + "membership_id = 1, membership_version = 2, membership_authority_generation = 1, "
                  + "membership_lifecycle_state = 'ACTIVE', gameplay_admission_allowed = FALSE, "
                  + "membership_roles_payload = convert_to('[\"tenantAdmin\"]', 'UTF8'), "
                  + "event_stream_key = ?, event_request_id = ?, event_sequence = 1, event_id = ?, "
                  + "event_digest = ?, caller_bound_authority_invalidated = FALSE, event_payload = ?, "
                  + "audit_event_id = ?, audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED', "
                  + "audit_occurred_at = CURRENT_TIMESTAMP, audit_payload_digest = ?, audit_payload = ?, "
                  + "result_payload = ?, result_digest = ?, status = 'COMMITTED' WHERE request_id = ?",
              "account:auth-authority:v1:membership/"
                  + accountUuid
                  + "/"
                  + tenant.canonicalTenantId(),
              bootstrapRequest.toString(),
              "capacity-bootstrap-" + bootstrapRequest,
              digest('1'),
              new byte[] {4},
              UUID.randomUUID(),
              digest('2'),
              new byte[] {5},
              new byte[] {6},
              digest('3'),
              bootstrapRequest);

          dsl.execute(
              "INSERT INTO account_tenant_capacity_evaluations "
                  + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, "
                  + "owner_service, canonical_evidence, evaluated_at, expires_at) "
                  + "VALUES (?, ?, ?, ?, 'game-session-service', ?, CURRENT_TIMESTAMP, "
                  + "CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
              evaluationIdentity,
              digest('b'),
              tenant.canonicalTenantId(),
              callerWorkload,
              new byte[] {7});
          dsl.execute(
              "INSERT INTO account_tenant_capacity_reservations "
                  + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, "
                  + "caller_workload, admission_attempt_id, capacity_rearm_fence, reservation_fence, "
                  + "mutation_digest, canonical_payload, evaluation_identity, capacity_delta, expires_at, "
                  + "state, execution_status, reconcile_deadline) "
                  + "VALUES (?, ?, ?, ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 3, "
                  + "CURRENT_TIMESTAMP + INTERVAL '5 minutes', 'RESERVED', "
                  + "'OWNER_EXECUTION_PENDING', CURRENT_TIMESTAMP + INTERVAL '10 minutes')",
              tenant.canonicalTenantId(),
              requestId,
              admissionId,
              UUID.randomUUID(),
              callerWorkload,
              UUID.randomUUID(),
              hex('c'),
              new byte[] {8},
              evaluationIdentity);
          dsl.execute(
              "UPDATE account_tenant_capacity_usage SET held_units = 3, usage_revision = 2 "
                  + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              tenant.canonicalTenantId());
        });
    return new CapacityFixture(
        tenant.canonicalTenantId(), requestId, admissionId, evaluationIdentity, callerWorkload);
  }

  private static void assertCapacityTriggerBehavior(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    List<String> before = capacityTableImages(dsl);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "UPDATE account_tenant_capacity_reservations SET "
                                + "capacity_delta = capacity_delta + 1 "
                                + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                            capacity.tenantUuid(),
                            capacity.requestId(),
                            capacity.admissionId())))
        .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));

    assertReservationInsertMismatchRefused(dsl, transaction, capacity);
    assertReservationStateChangeMismatchRefused(dsl, transaction, capacity);
    assertUsageWriteMismatchRefused(dsl, transaction, capacity);
    assertThat(capacityTableImages(dsl)).isEqualTo(before);

    transaction.executeWithoutResult(
        status -> {
          dsl.execute(
              "UPDATE account_tenant_capacity_usage SET held_units = held_units + 1, "
                  + "usage_revision = usage_revision + 1 "
                  + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              capacity.tenantUuid());
          dsl.execute(
              "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, "
                  + "recovery_claim_fence = recovery_claim_fence + 1, "
                  + "recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute' "
                  + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
              UUID.randomUUID(),
              capacity.tenantUuid(),
              capacity.requestId(),
              capacity.admissionId());
          dsl.execute(
              "SET CONSTRAINTS account_tenant_capacity_reservation_complete_update IMMEDIATE");
          dsl.execute(
              "UPDATE account_tenant_capacity_usage SET held_units = held_units - 1, "
                  + "usage_revision = usage_revision + 1 "
                  + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
              capacity.tenantUuid());
        });
    Record reservation =
        Objects.requireNonNull(
            dsl.fetchSingle(
                "SELECT state, recovery_claim_fence FROM account_tenant_capacity_reservations "
                    + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                capacity.tenantUuid(),
                capacity.requestId(),
                capacity.admissionId()));
    assertThat(reservation.get("state", String.class)).isEqualTo("RESERVED");
    assertThat(reservation.get("recovery_claim_fence", Long.class)).isEqualTo(1L);
    Record usage =
        Objects.requireNonNull(
            dsl.fetchSingle(
                "SELECT held_units, used_units FROM account_tenant_capacity_usage "
                    + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                capacity.tenantUuid()));
    assertThat(usage.get("held_units", Long.class)).isEqualTo(3L);
    assertThat(usage.get("used_units", Long.class)).isZero();
  }

  private static void assertReservationInsertMismatchRefused(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      dsl.execute(
                          "UPDATE account_tenant_capacity_usage SET held_units = held_units + 1, "
                              + "usage_revision = usage_revision + 1 "
                              + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                          capacity.tenantUuid());
                      dsl.execute(
                          "INSERT INTO account_tenant_capacity_reservations "
                              + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, "
                              + "caller_workload, admission_attempt_id, capacity_rearm_fence, "
                              + "reservation_fence, mutation_digest, canonical_payload, evaluation_identity, "
                              + "capacity_delta, expires_at, state, execution_status, reconcile_deadline) "
                              + "VALUES (?, ?, ?, ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 0, "
                              + "CURRENT_TIMESTAMP + INTERVAL '5 minutes', 'RESERVED', "
                              + "'OWNER_EXECUTION_PENDING', CURRENT_TIMESTAMP + INTERVAL '10 minutes')",
                          capacity.tenantUuid(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          capacity.callerWorkload(),
                          UUID.randomUUID(),
                          hex('4'),
                          new byte[] {9},
                          capacity.evaluationIdentity());
                      dsl.execute(
                          "SET CONSTRAINTS account_tenant_capacity_reservation_complete_insert IMMEDIATE");
                    }))
        .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
  }

  private static void assertReservationStateChangeMismatchRefused(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      dsl.execute(
                          "UPDATE account_tenant_capacity_usage SET held_units = held_units + 1, "
                              + "usage_revision = usage_revision + 1 "
                              + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                          capacity.tenantUuid());
                      dsl.execute(
                          "UPDATE account_tenant_capacity_reservations SET state = 'RELEASED', "
                              + "execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION', "
                              + "recovery_owner = ?, recovery_claim_fence = 1, "
                              + "recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute', "
                              + "reservation_fence = reservation_fence + 1, owner_readback = ?, "
                              + "owner_readback_digest = ? "
                              + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                          UUID.randomUUID(),
                          new byte[] {10},
                          hex('5'),
                          capacity.tenantUuid(),
                          capacity.requestId(),
                          capacity.admissionId());
                      dsl.execute(
                          "SET CONSTRAINTS account_tenant_capacity_reservation_complete_update IMMEDIATE");
                    }))
        .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
  }

  private static void assertUsageWriteMismatchRefused(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      dsl.execute(
                          "UPDATE account_tenant_capacity_usage SET used_units = used_units + 1, "
                              + "usage_revision = usage_revision + 1 "
                              + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                          capacity.tenantUuid());
                      dsl.execute(
                          "SET CONSTRAINTS account_tenant_capacity_usage_complete IMMEDIATE");
                    }))
        .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
  }

  private static void assertInventoryRefusal(Throwable failure) {
    assertThat(sqlState(failure)).isEqualTo("23514");
    assertThat(failure)
        .hasStackTraceContaining(
            "Capacity usage differs from its complete durable reservation inventory");
  }

  private static String sqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private static String digest(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }

  private static String hex(char digit) {
    return String.valueOf(digit).repeat(64);
  }

  private record PredecessorImage(
      List<String> migrationHistoryImage,
      Integer migrationChecksum,
      List<String> tableImages,
      List<String> otherTriggerImages,
      String exactUsageFunctionImage) {}

  private record CapacityFixture(
      UUID tenantUuid,
      UUID requestId,
      UUID admissionId,
      String evaluationIdentity,
      String callerWorkload) {}

  private static FreshTenantCreationEvidence source() {
    UUID request = UUID.randomUUID(), operation = UUID.randomUUID(), tenant = UUID.randomUUID();
    String slug = "capacity-" + UUID.randomUUID().toString().substring(0, 8);
    String digest = GameTenantCreationDigest.requestDigest("prod", request, slug, "Capacity", null);
    long row = Math.abs((long) tenant.hashCode()) + 1;
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        request,
        operation,
        digest,
        tenant,
        row,
        slug,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod", request, operation, digest, tenant, row, slug, "NEW_GAME_ROW"));
  }
}
