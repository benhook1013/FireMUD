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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** Physical forward upgrade and unchanged application-style usage/reservation composition. */
class AccountTenantCapacityIncrementalConservationPostgresIntegrationTest {
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
  void emptyAndPopulated1043UpgradePreservesSourceRowsAndHistoryAndSeedsExactConservation() {
    for (boolean populated : List.of(false, true)) {
      try (Database database = database("1043")) {
        DSLContext dsl = database.dsl();
        if (populated) {
          CapacityFixture capacity = populateCapacity(dsl, database.transaction());
          addFinalizedHistory(database, capacity, 16);
          database
              .transaction()
              .executeWithoutResult(
                  status -> {
                    CapacityFixture released = insertReservation(dsl, capacity, 2);
                    changeUsage(dsl, capacity, 2, 0);
                    settle(dsl, released, "RELEASED");
                    changeUsage(dsl, capacity, -2, 0);
                    CapacityFixture quarantined = insertReservation(dsl, capacity, 4);
                    changeUsage(dsl, capacity, 4, 0);
                    quarantine(dsl, quarantined);
                    insertReservation(dsl, capacity, 0);
                  });
        }
        List<String> rows = capacityTableImages(dsl);
        List<String> history = historyImages(dsl);
        List<String> triggers = triggerImages(dsl);

        var flyway = configureFlyway(database.data(), database.schema(), "1044");
        flyway.migrate();

        assertThat(capacityTableImages(dsl)).isEqualTo(rows);
        assertThat(historyImages(dsl)).isEqualTo(history);
        assertThat(triggerImages(dsl)).containsAll(triggers);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.getConfiguration().isOutOfOrder()).isFalse();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_conservation")))
            .isEqualTo(populated ? 2 : 0);
        assertConserved(dsl);
        assertBoundedFunctions(dsl);
      }
    }
  }

  @Test
  void lockedUpgradeRefusesHeldOrUsedDriftWithoutRepairingRowsOrHistory() {
    for (String column : List.of("held_units", "used_units")) {
      try (Database database = database("1043")) {
        DSLContext dsl = database.dsl();
        CapacityFixture capacity = populateCapacity(dsl, database.transaction());
        // Deliberately corrupt only the disposable predecessor fixture. The upgrade must not
        // silently trust usage, repair history, or bless a guessed conservation baseline.
        database
            .transaction()
            .executeWithoutResult(
                status -> {
                  dsl.execute(
                      "ALTER TABLE account_tenant_capacity_usage DISABLE TRIGGER account_tenant_capacity_usage_complete");
                  dsl.execute(
                      "UPDATE account_tenant_capacity_usage SET "
                          + column
                          + " = "
                          + column
                          + " + 1, usage_revision = usage_revision + 1 WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                      capacity.tenantUuid());
                  dsl.execute(
                      "ALTER TABLE account_tenant_capacity_usage ENABLE TRIGGER account_tenant_capacity_usage_complete");
                });
        List<String> rows = capacityTableImages(dsl);
        List<String> history = historyImages(dsl);
        assertThatThrownBy(
                () -> configureFlyway(database.data(), database.schema(), "1044").migrate())
            .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"))
            .hasStackTraceContaining(
                "Capacity conservation upgrade refuses inconsistent durable reservation inventory");
        assertThat(capacityTableImages(dsl)).isEqualTo(rows);
        assertThat(historyImages(dsl)).isEqualTo(history);
        assertThat(
                dsl.fetchSingle("SELECT to_regclass('account_tenant_capacity_conservation')")
                    .get(0))
            .isNull();
        assertThat(function(dsl, "account_tenant_capacity_exact_usage"))
            .contains("sum(capacity_delta)");
        assertThat(
                configureFlyway(database.data(), database.schema(), "1043")
                    .validateWithResult()
                    .validationSuccessful)
            .isTrue();
      }
    }
  }

  @Test
  void freshBootstrapAndAllExistingTransitionKindsConserveWithoutApplicationChanges() {
    try (Database database = database("1044")) {
      DSLContext dsl = database.dsl();
      // Real existing bootstrap trigger nesting creates two zero aggregates before reservation.
      CapacityFixture capacity = populateCapacity(dsl, database.transaction());
      database
          .transaction()
          .executeWithoutResult(
              status -> {
                CapacityFixture finalized = insertReservation(dsl, capacity, 2);
                changeUsage(dsl, capacity, 2, 0);
                settle(dsl, finalized, "FINALIZED");
                changeUsage(dsl, capacity, -2, 2);
                CapacityFixture released = insertReservation(dsl, capacity, 4);
                changeUsage(dsl, capacity, 4, 0);
                settle(dsl, released, "RELEASED");
                changeUsage(dsl, capacity, -4, 0);
                settle(dsl, insertReservation(dsl, capacity, 0), "FINALIZED");
                settle(dsl, insertReservation(dsl, capacity, 0), "RELEASED");
                quarantine(dsl, insertReservation(dsl, capacity, 0));
                CapacityFixture quarantined = insertReservation(dsl, capacity, 5);
                changeUsage(dsl, capacity, 5, 0);
                quarantine(dsl, quarantined);
              });
      assertConserved(dsl);
      assertUsage(dsl, capacity, 8, 2);

      List<String> aggregate = conservationImages(dsl);
      database
          .transaction()
          .executeWithoutResult(
              status -> {
                dsl.execute(
                    "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, "
                        + "recovery_claim_fence = 1, recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute', "
                        + "execution_status = 'NOT_EXECUTED_AFTER_AUTHORIZATION', owner_readback = ?, owner_readback_digest = ? "
                        + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                    UUID.randomUUID(),
                    new byte[] {1},
                    hex('5'),
                    capacity.tenantUuid(),
                    capacity.requestId(),
                    capacity.admissionId());
                dsl.execute(
                    "INSERT INTO account_tenant_capacity_attempt_history "
                        + "(tenant_uuid, request_id, admission_id, admission_attempt_id, capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback) "
                        + "SELECT tenant_uuid, request_id, admission_id, admission_attempt_id, capacity_rearm_fence, mutation_digest, canonical_payload, owner_readback "
                        + "FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                    capacity.tenantUuid(),
                    capacity.requestId(),
                    capacity.admissionId());
                dsl.execute(
                    "UPDATE account_tenant_capacity_reservations SET admission_attempt_id = ?, "
                        + "capacity_rearm_fence = capacity_rearm_fence + 1, reservation_fence = reservation_fence + 1, "
                        + "mutation_digest = ?, canonical_payload = ?, execution_status = 'OWNER_EXECUTION_PENDING', "
                        + "owner_readback = NULL, owner_readback_digest = NULL WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                    UUID.randomUUID(),
                    hex('6'),
                    new byte[] {2},
                    capacity.tenantUuid(),
                    capacity.requestId(),
                    capacity.admissionId());
              });
      assertThat(conservationImages(dsl)).isEqualTo(aggregate);
      assertUsage(dsl, capacity, 8, 2);
      assertConserved(dsl);
    }
  }

  @Test
  void forgedUsageAndEveryDirectAggregateMutationRefuseAndRollbackIsAtomic() {
    try (Database database = database("1044")) {
      DSLContext dsl = database.dsl();
      CapacityFixture capacity = populateCapacity(dsl, database.transaction());
      List<String> rows = capacityTableImages(dsl);
      List<String> aggregate = conservationImages(dsl);
      for (String statement :
          List.of(
              "UPDATE account_tenant_capacity_conservation SET held_units = held_units + 1 WHERE tenant_uuid = ?",
              "UPDATE account_tenant_capacity_conservation SET held_units = held_units WHERE tenant_uuid = ?",
              "DELETE FROM account_tenant_capacity_conservation WHERE tenant_uuid = ?",
              "INSERT INTO account_tenant_capacity_conservation VALUES (?, 'game-session-service', 0, 0)")) {
        assertThatThrownBy(
                () ->
                    database
                        .transaction()
                        .executeWithoutResult(
                            status -> dsl.execute(statement, capacity.tenantUuid())))
            .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      }
      assertThatThrownBy(
              () ->
                  database
                      .transaction()
                      .executeWithoutResult(
                          status -> dsl.execute("TRUNCATE account_tenant_capacity_conservation")))
          .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("23514"));
      assertThatThrownBy(
              () ->
                  database
                      .transaction()
                      .executeWithoutResult(status -> changeUsage(dsl, capacity, 1, 0)))
          .satisfies(
              AccountTenantCapacityIncrementalConservationPostgresIntegrationTest
                  ::assertConservationRefusal);
      assertThatThrownBy(
              () ->
                  database
                      .transaction()
                      .executeWithoutResult(status -> insertReservation(dsl, capacity, 1)))
          .satisfies(
              AccountTenantCapacityIncrementalConservationPostgresIntegrationTest
                  ::assertConservationRefusal);
      assertThatThrownBy(
              () ->
                  database
                      .transaction()
                      .executeWithoutResult(status -> settle(dsl, capacity, "FINALIZED")))
          .satisfies(
              AccountTenantCapacityIncrementalConservationPostgresIntegrationTest
                  ::assertConservationRefusal);
      assertThatThrownBy(
              () ->
                  database
                      .transaction()
                      .executeWithoutResult(
                          status -> {
                            changeUsage(dsl, capacity, 1, 0);
                            insertReservation(dsl, capacity, 1);
                            dsl.execute("SET CONSTRAINTS ALL IMMEDIATE");
                            throw new IllegalStateException("deliberate owner rollback");
                          }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("deliberate owner rollback");
      assertThat(capacityTableImages(dsl)).isEqualTo(rows);
      assertThat(conservationImages(dsl)).isEqualTo(aggregate);
      assertConserved(dsl);
    }
  }

  @Test
  void concurrentReservationsSerializeAndAnUnbalancedWriterCannotPoisonConservation()
      throws Exception {
    try (Database database = database("1044");
        var workers = Executors.newFixedThreadPool(2)) {
      CapacityFixture capacity = populateCapacity(database.dsl(), database.transaction());
      CountDownLatch start = new CountDownLatch(1);
      var first =
          workers.submit(
              () -> {
                await(start);
                database
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          changeUsage(database.dsl(), capacity, 1, 0);
                          insertReservation(database.dsl(), capacity, 1);
                        });
              });
      var second =
          workers.submit(
              () -> {
                await(start);
                database
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          changeUsage(database.dsl(), capacity, 2, 0);
                          insertReservation(database.dsl(), capacity, 2);
                        });
              });
      start.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
      assertUsage(database.dsl(), capacity, 6, 0);
      assertConserved(database.dsl());

      CountDownLatch next = new CountDownLatch(1);
      var balanced =
          workers.submit(
              () -> {
                await(next);
                database
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          changeUsage(database.dsl(), capacity, 1, 0);
                          insertReservation(database.dsl(), capacity, 1);
                        });
              });
      var unbalanced =
          workers.submit(
              () -> {
                await(next);
                assertThatThrownBy(
                        () ->
                            database
                                .transaction()
                                .executeWithoutResult(
                                    status -> insertReservation(database.dsl(), capacity, 2)))
                    .satisfies(
                        AccountTenantCapacityIncrementalConservationPostgresIntegrationTest
                            ::assertConservationRefusal);
              });
      next.countDown();
      balanced.get(30, TimeUnit.SECONDS);
      unbalanced.get(30, TimeUnit.SECONDS);
      assertUsage(database.dsl(), capacity, 7, 0);
      assertConserved(database.dsl());
    }
  }

  @Test
  void finalizedHistoryIsNotReadByNewHoldsFinalizationOrMetadataChecks() {
    try (Database database = database("1044")) {
      DSLContext dsl = database.dsl();
      CapacityFixture capacity = populateCapacity(dsl, database.transaction());
      addFinalizedHistory(database, capacity, 4096);
      dsl.execute("ANALYZE account_tenant_capacity_reservations");
      assertBoundedFunctions(dsl);
      database
          .transaction()
          .executeWithoutResult(
              status -> {
                long before = reservationRowsRead(dsl);
                changeUsage(dsl, capacity, 1, 0);
                CapacityFixture next = insertReservation(dsl, capacity, 1);
                dsl.execute("SET CONSTRAINTS ALL IMMEDIATE");
                assertThat(reservationRowsRead(dsl) - before).isLessThanOrEqualTo(8L);
                // Restore deferred scheduling so the unchanged application may write usage first.
                dsl.execute("SET CONSTRAINTS ALL DEFERRED");
                before = reservationRowsRead(dsl);
                changeUsage(dsl, capacity, -1, 1);
                settle(dsl, next, "FINALIZED");
                dsl.execute("SET CONSTRAINTS ALL IMMEDIATE");
                assertThat(reservationRowsRead(dsl) - before).isLessThanOrEqualTo(8L);
              });
      List<String> aggregate = conservationImages(dsl);
      database
          .transaction()
          .executeWithoutResult(
              status -> {
                long before = reservationRowsRead(dsl);
                changeUsage(dsl, capacity, 1, 0);
                dsl.execute(
                    "UPDATE account_tenant_capacity_reservations SET recovery_owner = ?, recovery_claim_fence = 1, "
                        + "recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute' "
                        + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
                    UUID.randomUUID(),
                    capacity.tenantUuid(),
                    capacity.requestId(),
                    capacity.admissionId());
                dsl.execute(
                    "SET CONSTRAINTS account_tenant_capacity_reservation_complete_update IMMEDIATE");
                changeUsage(dsl, capacity, -1, 0);
                dsl.execute("SET CONSTRAINTS ALL IMMEDIATE");
                assertThat(reservationRowsRead(dsl) - before).isLessThanOrEqualTo(8L);
              });
      assertThat(conservationImages(dsl)).isEqualTo(aggregate);
      assertUsage(dsl, capacity, 3, 4097);
      assertConserved(dsl);
    }
  }

  private static void addFinalizedHistory(Database database, CapacityFixture capacity, int count) {
    DSLContext dsl = database.dsl();
    database
        .transaction()
        .executeWithoutResult(
            status -> {
              changeUsage(dsl, capacity, count, 0);
              assertThat(
                      dsl.execute(
                          "INSERT INTO account_tenant_capacity_reservations "
                              + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, "
                              + "capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, "
                              + "expires_at, state, execution_status, reconcile_deadline) "
                              + "SELECT ?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'game-session-service', ?, gen_random_uuid(), "
                              + "1, 1, ?, ?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '5 minutes', 'RESERVED', 'OWNER_EXECUTION_PENDING', "
                              + "CURRENT_TIMESTAMP + INTERVAL '10 minutes' FROM generate_series(1, ?)",
                          capacity.tenantUuid(),
                          capacity.callerWorkload(),
                          hex('9'),
                          new byte[] {9},
                          capacity.evaluationIdentity(),
                          count))
                  .isEqualTo(count);
              changeUsage(dsl, capacity, -count, count);
              assertThat(
                      dsl.execute(
                          "UPDATE account_tenant_capacity_reservations SET state = 'FINALIZED', execution_status = 'COMMITTED', "
                              + "recovery_owner = ?, recovery_claim_fence = 1, recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute', "
                              + "owner_readback = ?, owner_readback_digest = ? WHERE tenant_uuid = ? AND mutation_digest = ? AND state = 'RESERVED'",
                          UUID.randomUUID(),
                          new byte[] {9},
                          hex('9'),
                          capacity.tenantUuid(),
                          hex('9')))
                  .isEqualTo(count);
            });
  }

  private static CapacityFixture insertReservation(
      DSLContext dsl, CapacityFixture capacity, long delta) {
    UUID request = UUID.randomUUID(), admission = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO account_tenant_capacity_reservations "
            + "(tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, "
            + "capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, "
            + "expires_at, state, execution_status, reconcile_deadline) "
            + "VALUES (?, ?, ?, ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes', "
            + "'RESERVED', 'OWNER_EXECUTION_PENDING', CURRENT_TIMESTAMP - INTERVAL '1 second')",
        capacity.tenantUuid(),
        request,
        admission,
        UUID.randomUUID(),
        capacity.callerWorkload(),
        UUID.randomUUID(),
        hex('4'),
        new byte[] {4},
        capacity.evaluationIdentity(),
        delta);
    return new CapacityFixture(
        capacity.tenantUuid(),
        request,
        admission,
        capacity.evaluationIdentity(),
        capacity.callerWorkload());
  }

  private static void settle(DSLContext dsl, CapacityFixture capacity, String state) {
    dsl.execute(
        "UPDATE account_tenant_capacity_reservations SET state = ?, execution_status = ?, "
            + "recovery_owner = ?, recovery_claim_fence = 1, recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute', "
            + "reservation_fence = reservation_fence + ?, owner_readback = ?, owner_readback_digest = ? "
            + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
        state,
        state.equals("FINALIZED") ? "COMMITTED" : "NOT_EXECUTED_AFTER_AUTHORIZATION",
        UUID.randomUUID(),
        state.equals("RELEASED") ? 1 : 0,
        new byte[] {5},
        hex('5'),
        capacity.tenantUuid(),
        capacity.requestId(),
        capacity.admissionId());
  }

  private static void quarantine(DSLContext dsl, CapacityFixture capacity) {
    dsl.execute(
        "UPDATE account_tenant_capacity_reservations SET state = 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION', "
            + "recovery_owner = ?, recovery_claim_fence = 1, recovery_claim_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 minute', "
            + "recovery_escalated_at = CURRENT_TIMESTAMP, next_reconcile_at = NULL "
            + "WHERE tenant_uuid = ? AND request_id = ? AND admission_id = ?",
        UUID.randomUUID(),
        capacity.tenantUuid(),
        capacity.requestId(),
        capacity.admissionId());
  }

  private static void changeUsage(DSLContext dsl, CapacityFixture capacity, long held, long used) {
    assertThat(
            dsl.execute(
                "UPDATE account_tenant_capacity_usage SET held_units = held_units + ?, used_units = used_units + ?, "
                    + "usage_revision = usage_revision + 1 WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                held,
                used,
                capacity.tenantUuid()))
        .isEqualTo(1);
  }

  private static void assertUsage(DSLContext dsl, CapacityFixture capacity, long held, long used) {
    var usage =
        dsl.fetchSingle(
            "SELECT held_units, used_units FROM account_tenant_capacity_usage "
                + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
            capacity.tenantUuid());
    assertThat(usage.get("held_units", Long.class)).isEqualTo(held);
    assertThat(usage.get("used_units", Long.class)).isEqualTo(used);
  }

  private static void assertConserved(DSLContext dsl) {
    assertThat(
            dsl.fetchSingle(
                    "SELECT count(*) FROM account_tenant_capacity_usage usage "
                        + "FULL JOIN account_tenant_capacity_conservation conserved USING (tenant_uuid, owner_service) "
                        + "FULL JOIN (SELECT tenant_uuid, owner_service, COALESCE(sum(capacity_delta) FILTER "
                        + "(WHERE state IN ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')), 0) AS held_units, "
                        + "COALESCE(sum(capacity_delta) FILTER (WHERE state = 'FINALIZED'), 0) AS used_units "
                        + "FROM account_tenant_capacity_reservations GROUP BY tenant_uuid, owner_service) inventory USING (tenant_uuid, owner_service) "
                        + "WHERE usage.tenant_uuid IS NULL OR conserved.tenant_uuid IS NULL "
                        + "OR usage.held_units IS DISTINCT FROM conserved.held_units OR usage.used_units IS DISTINCT FROM conserved.used_units "
                        + "OR usage.held_units IS DISTINCT FROM COALESCE(inventory.held_units, 0) "
                        + "OR usage.used_units IS DISTINCT FROM COALESCE(inventory.used_units, 0)")
                .get(0, Integer.class))
        .isZero();
  }

  private static void assertConservationRefusal(Throwable failure) {
    assertThat(sqlState(failure)).isEqualTo("23514");
    assertThat(failure)
        .hasStackTraceContaining(
            "Capacity usage differs from its protected reservation conservation");
  }

  private static void assertBoundedFunctions(DSLContext dsl) {
    assertThat(function(dsl, "account_tenant_capacity_exact_usage"))
        .contains("account_tenant_capacity_conservation")
        .doesNotContain("account_tenant_capacity_reservations", "sum(");
    assertThat(function(dsl, "account_tenant_capacity_conservation_delta"))
        .doesNotContain("FROM account_tenant_capacity_reservations", "sum(");
    String updateTrigger =
        Objects.requireNonNull(
            dsl.fetchSingle(
                    "SELECT pg_get_triggerdef(oid) FROM pg_trigger "
                        + "WHERE tgrelid = 'account_tenant_capacity_reservations'::regclass "
                        + "AND tgname = 'account_tenant_capacity_reservation_conservation_update'")
                .get(0, String.class));
    assertThat(
            updateTrigger
                .toLowerCase(Locale.ROOT)
                .replace("::text", "")
                .replace("(", "")
                .replace(")", ""))
        .contains("old.state is distinct from new.state");
  }

  private static String function(DSLContext dsl, String name) {
    return Objects.requireNonNull(
        dsl.fetchSingle("SELECT pg_get_functiondef(?::regprocedure)", name + "()")
            .get(0, String.class));
  }

  private static long reservationRowsRead(DSLContext dsl) {
    return Objects.requireNonNull(
        dsl.fetchSingle(
                "SELECT seq_tup_read + idx_tup_fetch FROM pg_stat_xact_user_tables "
                    + "WHERE relid = 'account_tenant_capacity_reservations'::regclass")
            .get(0, Long.class));
  }

  private static List<String> conservationImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT (to_jsonb(row_data) || jsonb_build_object('_original_xmin', row_data.xmin::text))::text AS image "
                + "FROM account_tenant_capacity_conservation row_data ORDER BY tenant_uuid, owner_service")
        .getValues("image", String.class);
  }

  private static List<String> historyImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT (to_jsonb(history) || jsonb_build_object('_original_xmin', history.xmin::text))::text AS image "
                + "FROM flyway_schema_history history WHERE version IS DISTINCT FROM '1044' ORDER BY installed_rank")
        .getValues("image", String.class);
  }

  private static List<String> triggerImages(DSLContext dsl) {
    return dsl.fetch(
            "SELECT pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE NOT tgisinternal "
                + "AND tgrelid IN ('account_tenant_capacity_usage'::regclass, 'account_tenant_capacity_reservations'::regclass) ORDER BY tgname")
        .getValues("definition", String.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Capacity race did not start");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static Database database(String target) {
    String schema = "capacity_incremental_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    configureFlyway(data, schema, target).migrate();
    return new Database(
        schema,
        data,
        DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES),
        new TransactionTemplate(new DataSourceTransactionManager(data)));
  }

  private record Database(
      String schema, javax.sql.DataSource data, DSLContext dsl, TransactionTemplate transaction)
      implements AutoCloseable {
    @Override
    public void close() {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
