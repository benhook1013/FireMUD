package integration.net.firedevops.firemud.accountservice.repository;

import static integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.capacityTableImages;
import static integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.configureFlyway;
import static integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.hex;
import static integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.populateCapacity;
import static integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountTenantCapacityMigrationPostgresIntegrationTest.CapacityFixture;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Additive write-time freshness, without revalidating or rewriting retained historical attempts.
 */
class AccountTenantCapacityWriteFreshnessPostgresIntegrationTest {
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
  void freshAndPopulatedUpgradePreservesPriorRowsHistoryAndGuardDefinitions() {
    for (boolean populated : List.of(false, true)) {
      try (var database = database()) {
        if (populated) {
          var capacity = populateCapacity(database.dsl(), database.write());
          // The predecessor accepted an already-expired original. Preserve it byte-for-byte;
          // installing the correction must never erase its historical result or release its hold.
          addExpiredEvaluation(database, capacity);
          database
              .write()
              .executeWithoutResult(
                  ignored -> {
                    insertExpiredReservation(database.dsl(), capacity);
                    incrementUsage(database.dsl(), capacity);
                  });
        }
        var rows = capacityTableImages(database.dsl());
        var history = history(database.dsl());
        var guards = guards(database.dsl());

        configureFlyway(database.data(), database.schema(), "1046.5").migrate();

        assertThat(capacityTableImages(database.dsl())).isEqualTo(rows);
        List<String> upgradedHistory = history(database.dsl());
        assertThat(upgradedHistory.subList(0, history.size())).containsExactlyElementsOf(history);
        assertThat(upgradedHistory).hasSizeGreaterThan(history.size());
        assertThat(guards(database.dsl())).containsAll(guards);
        assertThat(
                configureFlyway(database.data(), database.schema(), "1046.5")
                    .validateWithResult()
                    .validationSuccessful)
            .isTrue();
        if (populated) {
          // Same-attempt diagnostic/recovery writes are not new authorization, even when expired.
          database
              .write()
              .executeWithoutResult(
                  ignored ->
                      database
                          .dsl()
                          .execute(
                              "UPDATE account_tenant_capacity_reservations SET next_reconcile_at = clock_timestamp() "
                                  + "WHERE evaluation_identity = ?",
                              expiredEvaluation()));
        }
      }
    }
  }

  @Test
  void expiredOriginalAndRearmWritesRollbackTheirUsageAndHistoryAtCommit() {
    try (var database = database()) {
      var capacity = populateCapacity(database.dsl(), database.write());
      addExpiredEvaluation(database, capacity);
      configureFlyway(database.data(), database.schema(), "1046.5").migrate();
      var before = capacityTableImages(database.dsl());

      assertThatThrownBy(
              () ->
                  database
                      .write()
                      .executeWithoutResult(
                          ignored -> {
                            incrementUsage(database.dsl(), capacity);
                            insertExpiredReservation(database.dsl(), capacity);
                          }))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"))
          .hasStackTraceContaining("stale at the reservation write boundary");
      assertThat(capacityTableImages(database.dsl())).isEqualTo(before);

      database
          .write()
          .executeWithoutResult(
              ignored ->
                  database
                      .dsl()
                      .execute(
                          "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, recovery_claim_fence = 1, "
                              + "recovery_claim_expires_at = clock_timestamp() + INTERVAL '1 minute', "
                              + "execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION', owner_readback = ?, owner_readback_digest = ? "
                              + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                          UUID.randomUUID(),
                          new byte[] {1},
                          hex('5'),
                          capacity.tenantUuid(),
                          capacity.requestId(),
                          capacity.admissionId()));
      var terminal = capacityTableImages(database.dsl());
      assertThatThrownBy(
              () ->
                  database
                      .write()
                      .executeWithoutResult(
                          ignored -> {
                            database
                                .dsl()
                                .execute(
                                    "INSERT INTO account_tenant_capacity_attempt_history "
                                        + "(tenant_uuid, request_id, admission_id, admission_attempt_id, capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback) "
                                        + "SELECT tenant_uuid, request_id, admission_id, admission_attempt_id, capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback "
                                        + "FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                                    capacity.tenantUuid(),
                                    capacity.requestId(),
                                    capacity.admissionId());
                            UUID nextAttemptId = UUID.randomUUID();
                            String nextMutationDigest = hex('6');
                            byte[] nextCanonicalPayload = new byte[] {2};
                            insertOriginDecision(
                                database.dsl(),
                                capacity.tenantUuid(),
                                capacity.requestId(),
                                capacity.admissionId(),
                                2,
                                nextAttemptId,
                                nextMutationDigest,
                                nextCanonicalPayload);
                            database
                                .dsl()
                                .execute(
                                    "UPDATE account_tenant_capacity_reservations SET admission_attempt_id = ?, "
                                        + "capacity_rearm_fence = capacity_rearm_fence + 1, reservation_fence = reservation_fence + 1, "
                                        + "mutation_digest = ?, canonical_payload = ?, evaluation_identity = ?, expires_at = clock_timestamp() - INTERVAL '1 minute', "
                                        + "execution_status = 'OWNER_EXECUTION_PENDING', owner_readback = NULL, owner_readback_digest = NULL "
                                        + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                                    nextAttemptId,
                                    nextMutationDigest,
                                    nextCanonicalPayload,
                                    expiredEvaluation(),
                                    capacity.tenantUuid(),
                                    capacity.requestId(),
                                    capacity.admissionId());
                          }))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"))
          .hasStackTraceContaining("stale at the reservation write boundary");
      assertThat(capacityTableImages(database.dsl())).isEqualTo(terminal);
    }
  }

  @Test
  void deferredFreshnessConstraintHasStructuredIdentityAndRollsBackTheWholeWrite() {
    try (var database = database()) {
      var capacity = populateCapacity(database.dsl(), database.write());
      addExpiredEvaluation(database, capacity);
      configureFlyway(database.data(), database.schema(), "1046.5").migrate();
      var before = capacityTableImages(database.dsl());

      assertThatThrownBy(
              () ->
                  database
                      .write()
                      .executeWithoutResult(
                          ignored -> {
                            incrementUsage(database.dsl(), capacity);
                            insertExpiredReservation(database.dsl(), capacity);
                          }))
          .satisfies(
              failure -> {
                PSQLException postgresFailure = postgresFailure(failure);
                assertThat(postgresFailure.getSQLState()).isEqualTo("23514");
                assertThat(postgresFailure.getServerErrorMessage().getConstraint())
                    .isEqualTo("account_tenant_capacity_attempt_freshness");
              });
      assertThat(capacityTableImages(database.dsl())).isEqualTo(before);
    }
  }

  @Test
  void expiryAfterInsertionStillRollsBackTheHoldUsingCommitTimeRatherThanTransactionStart() {
    try (var database = database()) {
      var capacity = populateCapacity(database.dsl(), database.write());
      configureFlyway(database.data(), database.schema(), "1046.5").migrate();
      var before = capacityTableImages(database.dsl());
      assertThatThrownBy(
              () ->
                  database
                      .write()
                      .executeWithoutResult(
                          ignored -> {
                            database
                                .dsl()
                                .execute(
                                    "INSERT INTO account_tenant_capacity_evaluations "
                                        + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, evaluated_at, expires_at) "
                                        + "SELECT ?, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, "
                                        + "clock_timestamp(), clock_timestamp() + INTERVAL '1 second' "
                                        + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                                    expiredEvaluation(),
                                    capacity.evaluationIdentity());
                            UUID requestId = UUID.randomUUID();
                            UUID admissionId = UUID.randomUUID();
                            UUID reservationId = UUID.randomUUID();
                            UUID attemptId = UUID.randomUUID();
                            String mutationDigest = hex('6');
                            byte[] canonicalPayload =
                                database
                                    .dsl()
                                    .fetchSingle(
                                        "SELECT canonical_evidence FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                                        expiredEvaluation())
                                    .get("canonical_evidence", byte[].class);
                            insertOriginDecision(
                                database.dsl(),
                                capacity.tenantUuid(),
                                requestId,
                                admissionId,
                                1,
                                attemptId,
                                mutationDigest,
                                canonicalPayload);
                            database
                                .dsl()
                                .execute(
                                    "INSERT INTO account_tenant_capacity_reservations "
                                        + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, "
                                        + "capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, "
                                        + "expires_at, reconcile_deadline, state, execution_status) "
                                        + "SELECT tenant_uuid, ?, ?, ?, owner_service, caller_workload, ?, 1, 1, ?, canonical_evidence, evaluation_identity, 1, "
                                        + "expires_at, expires_at + INTERVAL '1 minute', 'RESERVED', 'OWNER_EXECUTION_PENDING' "
                                        + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                                    requestId,
                                    admissionId,
                                    reservationId,
                                    attemptId,
                                    mutationDigest,
                                    expiredEvaluation());
                            incrementUsage(database.dsl(), capacity);
                            database
                                .dsl()
                                .fetchSingle(
                                    "SELECT pg_sleep(GREATEST(0, extract(epoch FROM (expires_at - clock_timestamp())))) "
                                        + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                                    expiredEvaluation());
                          }))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"))
          .hasStackTraceContaining("stale at the reservation write boundary");
      assertThat(capacityTableImages(database.dsl())).isEqualTo(before);
    }
  }

  private static void addExpiredEvaluation(Database database, CapacityFixture capacity) {
    database
        .write()
        .executeWithoutResult(
            ignored ->
                database
                    .dsl()
                    .execute(
                        "INSERT INTO account_tenant_capacity_evaluations "
                            + "(evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, evaluated_at, expires_at) "
                            + "SELECT ?, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, "
                            + "clock_timestamp() - INTERVAL '2 minutes', clock_timestamp() - INTERVAL '1 minute' "
                            + "FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                        expiredEvaluation(),
                        capacity.evaluationIdentity()));
  }

  private static void insertExpiredReservation(DSLContext dsl, CapacityFixture capacity) {
    var source =
        dsl.fetchSingle(
            "SELECT mutation_digest, canonical_payload FROM account_tenant_capacity_reservations "
                + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
            capacity.tenantUuid(),
            capacity.requestId(),
            capacity.admissionId());
    UUID requestId = UUID.randomUUID();
    UUID admissionId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String mutationDigest = source.get("mutation_digest", String.class);
    byte[] canonicalPayload = source.get("canonical_payload", byte[].class);
    insertOriginDecision(
        dsl,
        capacity.tenantUuid(),
        requestId,
        admissionId,
        1,
        attemptId,
        mutationDigest,
        canonicalPayload);
    dsl.execute(
        "INSERT INTO account_tenant_capacity_reservations "
            + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, "
            + "capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, "
            + "expires_at, reconcile_deadline, state, execution_status) "
            + "SELECT tenant_uuid, ?, ?, ?, owner_service, caller_workload, ?, 1, 1, ?, ?, ?, 1, "
            + "clock_timestamp() - INTERVAL '1 minute', reconcile_deadline, 'RESERVED', 'OWNER_EXECUTION_PENDING' "
            + "FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
        requestId,
        admissionId,
        UUID.randomUUID(),
        attemptId,
        mutationDigest,
        canonicalPayload,
        expiredEvaluation(),
        capacity.tenantUuid(),
        capacity.requestId(),
        capacity.admissionId());
  }

  private static void insertOriginDecision(
      DSLContext dsl,
      UUID tenantUuid,
      UUID requestId,
      UUID admissionId,
      long rearmFence,
      UUID attemptId,
      String mutationDigest,
      byte[] canonicalPayload) {
    dsl.execute(
        "INSERT INTO account_capacity_origin_decisions "
            + "(tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, "
            + "mutation_digest, canonical_payload, decision, decision_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?)",
        tenantUuid,
        requestId,
        admissionId,
        rearmFence,
        attemptId,
        mutationDigest,
        canonicalPayload,
        UUID.randomUUID());
  }

  private static void incrementUsage(DSLContext dsl, CapacityFixture capacity) {
    dsl.execute(
        "UPDATE account_tenant_capacity_usage SET held_units = held_units + 1, "
            + "usage_revision = usage_revision + 1 WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
        capacity.tenantUuid());
  }

  private static PSQLException postgresFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException postgresFailure) return postgresFailure;
    }
    throw new AssertionError("Expected PostgreSQL diagnostic", failure);
  }

  private static String expiredEvaluation() {
    return "sha256:" + hex('e');
  }

  private static List<String> history(DSLContext dsl) {
    return dsl.fetch(
            "SELECT to_jsonb(h)::text FROM flyway_schema_history h ORDER BY installed_rank")
        .getValues(0, String.class);
  }

  private static List<String> guards(DSLContext dsl) {
    return dsl.fetch(
            "SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t "
                + "JOIN pg_class relation ON relation.oid = t.tgrelid "
                + "JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace "
                + "WHERE namespace.nspname = current_schema() AND NOT t.tgisinternal "
                + "ORDER BY relation.relname, t.tgname")
        .getValues(0, String.class);
  }

  private static Database database() {
    String schema = "capacity_freshness_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    configureFlyway(data, schema, "1045").migrate();
    return new Database(
        schema,
        data,
        DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES),
        new TransactionTemplate(new DataSourceTransactionManager(data)));
  }

  private record Database(
      String schema, javax.sql.DataSource data, DSLContext dsl, TransactionTemplate write)
      implements AutoCloseable {
    @Override
    public void close() {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
