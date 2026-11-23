package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.AccountTenantCreatorBootstrapIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCapacityRefusalBudgetRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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
import org.springframework.transaction.TransactionDefinition;
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

  private static FluentConfiguration capacityMigrations(DataSource data, String schema) {
    return Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(java.util.Map.of("serviceSchema", schema))
        .locations("classpath:db/migration");
  }

  @Test
  void duplicateHistoricalOriginAttemptsStopUpgradeWithoutRewritingEvidence() {
    String schema = "capacity_duplicate_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      capacityMigrations(data, schema).target("1040.1").load().migrate();
      var dsl = DSL.using(data, SQLDialect.POSTGRES);
      UUID attempt = UUID.randomUUID();
      for (int index = 0; index < 2; index++) {
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id,"
                + " capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload,"
                + " decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            attempt,
            "a".repeat(64),
            new byte[] {1},
            UUID.randomUUID());
      }
      capacityMigrations(data, schema).target("1040.3").load().migrate();
      var original =
          dsl.fetch(
                  "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence,"
                      + " admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex')"
                      + " AS payload, decision, decision_id, refusal_code, xmin::text AS"
                      + " row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
              .intoMaps();
      assertThatThrownBy(() -> capacityMigrations(data, schema).target("1040.4").load().migrate())
          .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
          .hasStackTraceContaining("account_capacity_origin_attempt_unique")
          .satisfies(failure -> {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof java.sql.SQLException))
              cause = cause.getCause();
            assertThat(cause).isInstanceOf(java.sql.SQLException.class);
            assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23505");
          });
      assertThat(
              dsl.fetch(
                      "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence,"
                          + " admission_attempt_id, mutation_digest, encode(canonical_payload,"
                          + " 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS"
                          + " row_version FROM account_capacity_origin_decisions ORDER BY"
                          + " decision_id")
                  .intoMaps())
          .isEqualTo(original);
      assertThat(original).hasSize(2);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void budgetConflictDoesNotMaskRetainedHistoryAndFirstInstallStillChecksIt() {
    String schema = "capacity_budget_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      capacityMigrations(data, schema).target("1040.1").load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var tenant = source();
      transaction.executeWithoutResult(status ->
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant));
      UUID retainedAttempt = UUID.randomUUID();
      for (int index = 0; index < 3; index++) {
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id,"
                + " capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload,"
                + " decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
            tenant.canonicalTenantId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            index == 0 ? retainedAttempt : UUID.randomUUID(),
            "a".repeat(64),
            new byte[] {1},
            UUID.randomUUID());
      }
      capacityMigrations(data, schema).load().migrate();
      var budgets = new AccountCapacityRefusalBudgetRepository(dsl);
      assertThatThrownBy(() ->
              transaction.executeWithoutResult(
                  status -> budgets.provision(tenant.canonicalTenantId(), 2)))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure)
                    .hasMessageContaining(
                        "Capacity refusal budget cannot exclude retained evidence");
              });
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_refusal_budget"))).isZero();
      assertThatThrownBy(() ->
              transaction.executeWithoutResult(status -> {
                budgets.provision(tenant.canonicalTenantId(), 4);
                budgets.requireReady(tenant.canonicalTenantId(), 4, 2);
              }))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_refusal_budget"))).isZero();
      transaction.executeWithoutResult(status -> budgets.provision(tenant.canonicalTenantId(), 4));
      assertThat(
              transaction.<Long>execute(
                  status -> budgets.requireReady(tenant.canonicalTenantId(), 4, 1)))
          .isEqualTo(1);
      assertThatThrownBy(() ->
              transaction.execute(status -> budgets.requireReady(tenant.canonicalTenantId(), 4, 2)))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure ->
                  assertThat(failure.code())
                      .isEqualTo(
                          net.firedevops.firemud.common.account.capacity
                              .TenantCapacityAdmissionGrpcCodec.Failure.ENTITLEMENT_UNAVAILABLE));
      var original =
          Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT *, xmin::text AS row_version FROM"
                          + " account_capacity_origin_refusal_budget"))
              .intoMap();
      transaction.executeWithoutResult(status -> budgets.provision(tenant.canonicalTenantId(), 4));
      assertThatThrownBy(() ->
              transaction.executeWithoutResult(
                  status -> budgets.provision(tenant.canonicalTenantId(), 2)))
          .isInstanceOfSatisfying(
              AccountTenantCapacityRepository.CapacityException.class,
              failure ->
                  assertThat(failure.code())
                      .isEqualTo(
                          net.firedevops.firemud.common.account.capacity
                              .TenantCapacityAdmissionGrpcCodec.Failure.IDEMPOTENCY_CONFLICT));
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT *, xmin::text AS row_version FROM"
                              + " account_capacity_origin_refusal_budget"))
                  .intoMap())
          .isEqualTo(original);
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id,"
                      + " admission_id, capacity_rearm_fence, admission_attempt_id,"
                      + " mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?,"
                      + " ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
                  tenant.canonicalTenantId(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  retainedAttempt,
                  "b".repeat(64),
                  new byte[] {2},
                  UUID.randomUUID()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23505"));
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isEqualTo(3);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void historicalBootstrapCompletionAndPlainNewImportCannotInitializeCapacity() {
    String schema = "capacity_migration_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      capacityMigrations(data, schema).target("1038").load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var repository = new FreshTenantIdentityAssociationRepository(dsl, "prod");
      var operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      var existing = source();
      var historical =
          Objects.requireNonNull(
              transaction.execute(status -> {
                repository.importVerified(existing);
                return AccountTenantCreatorBootstrapIntegrationFixture.claimBootstrap(
                    operations, new AccountRepository(dsl).save(creator()), existing);
              }));
      assertThat(historical.status()).isEqualTo("IN_PROGRESS");
      capacityMigrations(data, schema).load().migrate();
      var retained = transaction.execute(status -> repository.read(existing.canonicalTenantId()));
      assertThat(retained).contains(existing);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM"
                              + " account_fresh_tenant_identity_associations WHERE"
                              + " canonical_tenant_id = ?",
                          existing.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNull();
      var completed =
          Objects.requireNonNull(
              transaction.execute(status -> {
                var operation = operations.findForUpdate(historical.requestId()).orElseThrow();
                assertThat(operation.status()).isEqualTo("IN_PROGRESS");
                assertThat(operation.creationOperationId()).isEqualTo(existing.operationId());
                assertThat(operation.requestPayload()).isEqualTo(historical.requestPayload());
                return operations.complete(
                    operation.requestId(),
                    AccountTenantCreatorBootstrapIntegrationFixture.completion(operation));
              }));
      assertThat(completed.status()).isEqualTo("COMMITTED");
      assertThat(completed.tenantUuid()).isEqualTo(existing.canonicalTenantId());
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      var later = source();
      transaction.executeWithoutResult(status -> repository.importVerified(later));
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT capacity_birth_transaction FROM"
                              + " account_fresh_tenant_identity_associations WHERE"
                              + " canonical_tenant_id = ?",
                          later.canonicalTenantId()))
                  .get("capacity_birth_transaction"))
          .isNotNull();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_refusal_budget"))).isZero();
      var budgets = new AccountCapacityRefusalBudgetRepository(dsl);
      assertThatThrownBy(() ->
              transaction.execute(
                  status -> budgets.requireReady(existing.canonicalTenantId(), 4, 2)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      transaction.executeWithoutResult(
          status -> budgets.provision(existing.canonicalTenantId(), 4));
      var beforeBudget =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget"
                      + " WHERE tenant_uuid=?",
                  existing.canonicalTenantId())
              .intoMap();
      transaction.executeWithoutResult(
          status -> budgets.provision(existing.canonicalTenantId(), 4));
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM"
                          + " account_capacity_origin_refusal_budget WHERE tenant_uuid=?",
                      existing.canonicalTenantId())
                  .intoMap())
          .isEqualTo(beforeBudget);
      Long readyHeadroom =
          transaction.execute(status -> budgets.requireReady(existing.canonicalTenantId(), 4, 2));
      assertThat(readyHeadroom).isEqualTo(4L);
      assertThatThrownBy(() ->
              transaction.executeWithoutResult(
                  status -> budgets.provision(existing.canonicalTenantId(), 5)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      assertThatThrownBy(() ->
              transaction.execute(status -> budgets.requireReady(later.canonicalTenantId(), 4, 1)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      assertThatThrownBy(() ->
              transaction.executeWithoutResult(
                  status -> budgets.provision(existing.canonicalTenantId(), 0)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isZero();

    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void v1040_5AllowsTheFinalRefusalAndAnExactRetryWithoutSpendingAnotherSlot() {
    String schema = "capacity_v1040_5_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      var flyway = capacityMigrations(data, schema).target("1040.5").load();
      flyway.migrate();
      assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1040.5");
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var tenant = source();
      transaction.executeWithoutResult(status -> {
        new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant);
        new AccountCapacityRefusalBudgetRepository(dsl).provision(tenant.canonicalTenantId(), 1);
      });
      String insert =
          """
          INSERT INTO account_capacity_origin_decisions
          (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id,
           mutation_digest, canonical_payload, decision, decision_id, refusal_code)
          VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?, 'CAPACITY_QUOTA_EXCEEDED')
          ON CONFLICT DO NOTHING
          """;
      UUID decision = UUID.randomUUID();
      Object[] original = {
        tenant.canonicalTenantId(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        new byte[] {1},
        decision
      };
      assertThat(dsl.execute(insert, original)).isOne();
      Object[] retry = original.clone();
      retry[retry.length - 1] = UUID.randomUUID();
      assertThat(dsl.execute(insert, retry)).isZero();
      assertThatThrownBy(() ->
              dsl.execute(
                  insert,
                  tenant.canonicalTenantId(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "b".repeat(64),
                  new byte[] {2},
                  UUID.randomUUID()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThat(
              dsl.fetch("SELECT decision_id FROM account_capacity_origin_decisions")
                  .getValues("decision_id", UUID.class))
          .containsExactly(decision);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void refusalBudgetRunsAfterIdentityArbitrationAndCountsTheInsertedLastSlot() throws Exception {
    String schema = "capacity_arbitration_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      capacityMigrations(data, schema).load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var tenant = source();
      transaction.executeWithoutResult(status -> {
        new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant);
        new AccountCapacityRefusalBudgetRepository(dsl).provision(tenant.canonicalTenantId(), 1);
      });
      UUID request = UUID.randomUUID(),
          admission = UUID.randomUUID(),
          attempt = UUID.randomUUID(),
          decision = UUID.randomUUID();
      String insert =
          """
          INSERT INTO account_capacity_origin_decisions
          (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id,
           mutation_digest, canonical_payload, decision, decision_id, refusal_code)
          VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?, 'CAPACITY_QUOTA_EXCEEDED')
          ON CONFLICT DO NOTHING
          """;
      Object[] original = {
        tenant.canonicalTenantId(),
        request,
        admission,
        attempt,
        "a".repeat(64),
        new byte[] {1},
        decision
      };
      assertThat(dsl.execute(insert, original)).isEqualTo(1);
      var retained =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_decisions")
              .intoMap();
      var retainedBudget =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget")
              .intoMap();
      // The repository generates a new candidate decision ID on an exact retry.
      Object[] retry = original.clone();
      retry[retry.length - 1] = UUID.randomUUID();
      assertThat(dsl.execute(insert, retry)).isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM"
                          + " account_capacity_origin_refusal_budget")
                  .intoMap())
          .isEqualTo(retainedBudget);
      assertThatThrownBy(() ->
              dsl.execute(
                  insert,
                  tenant.canonicalTenantId(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "a".repeat(64),
                  new byte[] {1},
                  UUID.randomUUID()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      // A different outer identity with the same global attempt reaches conflict arbitration,
      // not the missing budget guard. The repository then verifies exact retained identity.
      assertThat(
              dsl.execute(
                  insert,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  attempt,
                  "b".repeat(64),
                  new byte[] {2},
                  UUID.randomUUID()))
          .isZero();
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM account_capacity_origin_decisions")
                  .intoMap())
          .usingRecursiveComparison()
          .isEqualTo(retained);
      // A genuinely new refusal without a budget still fails and leaves no evidence.
      assertThatThrownBy(() ->
              dsl.execute(
                  insert,
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  "b".repeat(64),
                  new byte[] {2},
                  UUID.randomUUID()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isEqualTo(1);

      var concurrentTenant = source();
      transaction.executeWithoutResult(status -> {
        new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(concurrentTenant);
        new AccountCapacityRefusalBudgetRepository(dsl)
            .provision(concurrentTenant.canonicalTenantId(), 1);
      });
      UUID winningRequest = UUID.randomUUID();
      UUID winningAdmission = UUID.randomUUID();
      UUID winningAttempt = UUID.randomUUID();
      UUID winningDecision = UUID.randomUUID();
      UUID losingRequest = UUID.randomUUID();
      UUID losingAdmission = UUID.randomUUID();
      UUID losingAttempt = UUID.randomUUID();
      UUID losingDecision = UUID.randomUUID();
      assertThat(losingRequest).isNotEqualTo(winningRequest);
      assertThat(losingAdmission).isNotEqualTo(winningAdmission);
      assertThat(losingAttempt).isNotEqualTo(winningAttempt);
      String concurrentRefusalInsert =
          "INSERT INTO account_capacity_origin_decisions "
              + "(tenant_uuid, request_id, admission_id, capacity_rearm_fence, "
              + "admission_attempt_id, mutation_digest, canonical_payload, decision, "
              + "decision_id, refusal_code) "
              + "VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?, 'CAPACITY_QUOTA_EXCEEDED') "
              + "ON CONFLICT DO NOTHING";
      try (var stale = data.getConnection();
          var winner = data.getConnection()) {
        stale.setTransactionIsolation(java.sql.Connection.TRANSACTION_SERIALIZABLE);
        stale.setAutoCommit(false);
        winner.setAutoCommit(true);
        var staleDsl = DSL.using(stale, SQLDialect.POSTGRES);
        // Both origins are distinct and belong to one tenant with exactly one refusal slot.
        // Establish the losing transaction's snapshot before the winner commits its last slot.
        assertThat(
                staleDsl.fetch(
                    "SELECT slot FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ?",
                    concurrentTenant.canonicalTenantId()))
            .isEmpty();
        assertThat(
                DSL.using(winner, SQLDialect.POSTGRES)
                    .execute(
                        concurrentRefusalInsert,
                        concurrentTenant.canonicalTenantId(),
                        winningRequest,
                        winningAdmission,
                        winningAttempt,
                        "c".repeat(64),
                        new byte[] {3},
                        winningDecision))
            .isEqualTo(1);
        assertThatThrownBy(() ->
                staleDsl.execute(
                    concurrentRefusalInsert,
                    concurrentTenant.canonicalTenantId(),
                    losingRequest,
                    losingAdmission,
                    losingAttempt,
                    "d".repeat(64),
                    new byte[] {4},
                    losingDecision))
            .isInstanceOfSatisfying(
                org.jooq.exception.DataAccessException.class,
                failure -> assertThat(failure.sqlState()).isEqualTo("40001"));
        stale.rollback();
      }

      var winningOrigin =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_decisions "
                      + "WHERE tenant_uuid = ?",
                  concurrentTenant.canonicalTenantId())
              .intoMap();
      assertThat(winningOrigin)
          .containsEntry("request_id", winningRequest)
          .containsEntry("admission_id", winningAdmission)
          .containsEntry("admission_attempt_id", winningAttempt)
          .containsEntry("decision", "NO_ACCEPT")
          .containsEntry("decision_id", winningDecision)
          .containsEntry("refusal_code", "CAPACITY_QUOTA_EXCEEDED");
      // Retry the exact rolled-back loser in a fresh transaction/snapshot, not a new original.
      var retryTransaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      retryTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThatThrownBy(() ->
              retryTransaction.executeWithoutResult(status ->
                  dsl.execute(
                      concurrentRefusalInsert,
                      concurrentTenant.canonicalTenantId(),
                      losingRequest,
                      losingAdmission,
                      losingAttempt,
                      "d".repeat(64),
                      new byte[] {4},
                      losingDecision)))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure).hasMessageContaining("Capacity refusal budget is exhausted");
              });
      assertThat(
              dsl.fetch(
                      "SELECT *, xmin::text AS row_version FROM account_capacity_origin_decisions "
                          + "WHERE tenant_uuid = ?",
                      concurrentTenant.canonicalTenantId())
                  .intoMaps())
          .usingRecursiveComparison()
          .isEqualTo(List.of(winningOrigin));
      assertThat(
              dsl.fetch(
                      "SELECT tenant_uuid, slot, decision_id "
                          + "FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ?",
                      concurrentTenant.canonicalTenantId())
                  .map(row ->
                      new RefusalSlot(
                          row.get("tenant_uuid", UUID.class),
                          row.get("slot", Long.class),
                          row.get("decision_id", UUID.class))))
          .containsExactly(
              new RefusalSlot(concurrentTenant.canonicalTenantId(), 1, winningDecision));
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
          .startsWith(before1041.migrationHistoryImage().toArray(String[]::new));
      assertThat(capacityTableImages(dsl)).isEqualTo(before1041.tableImages());
      List<String> after1041Triggers = otherCapacityTriggerImages(dsl);
      assertThat(after1041Triggers).containsAll(before1041.otherTriggerImages());
      assertThat(after1041Triggers).hasSize(before1041.otherTriggerImages().size() + 1);
      assertThat(after1041Triggers)
          .anyMatch(image -> image.contains(":account_capacity_origin_reservation_guard:"));
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

  @Test
  void refusalSlotsBackfillRetainedDecisionsAndOnlyFreshRefusalsSpendSlots() {
    String schema = "capacity_refusal_slots_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      capacityMigrations(data, schema).target("1038").load().migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var budgets = new AccountCapacityRefusalBudgetRepository(dsl);
      var historicalTenant = source();
      var otherHistoricalTenant = source();
      assertThat(otherHistoricalTenant.canonicalTenantId())
          .isNotEqualTo(historicalTenant.canonicalTenantId());
      transaction.executeWithoutResult(status -> {
        var tenants = new FreshTenantIdentityAssociationRepository(dsl, "prod");
        tenants.importVerified(historicalTenant);
        tenants.importVerified(otherHistoricalTenant);
      });

      capacityMigrations(data, schema).target("1040.1").load().migrate();
      UUID firstHistoricalRequest = UUID.randomUUID();
      UUID firstHistoricalAdmission = UUID.randomUUID();
      UUID firstHistoricalAttempt = UUID.randomUUID();
      UUID firstHistoricalDecision = UUID.fromString("00000000-0000-0000-0000-000000001041");
      UUID secondHistoricalRequest = UUID.randomUUID();
      UUID secondHistoricalAdmission = UUID.randomUUID();
      UUID secondHistoricalAttempt = UUID.randomUUID();
      UUID secondHistoricalDecision = UUID.fromString("00000000-0000-0000-0000-000000001042");
      UUID otherHistoricalDecision = UUID.fromString("00000000-0000-0000-0000-000000001043");
      String historicalInsert =
          "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id,"
              + " capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload,"
              + " decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)";
      dsl.execute(
          historicalInsert,
          historicalTenant.canonicalTenantId(),
          firstHistoricalRequest,
          firstHistoricalAdmission,
          firstHistoricalAttempt,
          "a".repeat(64),
          new byte[] {1, 2, 3},
          firstHistoricalDecision);
      dsl.execute(
          historicalInsert,
          historicalTenant.canonicalTenantId(),
          secondHistoricalRequest,
          secondHistoricalAdmission,
          secondHistoricalAttempt,
          "b".repeat(64),
          new byte[] {4, 5, 6},
          secondHistoricalDecision);
      dsl.execute(
          historicalInsert,
          otherHistoricalTenant.canonicalTenantId(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "6".repeat(64),
          new byte[] {26, 27, 28},
          otherHistoricalDecision);

      var acceptedTenant = source();
      var operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      transaction.executeWithoutResult(status -> {
        new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(acceptedTenant);
        var claimed =
            AccountTenantCreatorBootstrapIntegrationFixture.claimBootstrap(
                operations, new AccountRepository(dsl).save(creator()), acceptedTenant);
        var current = operations.findForUpdate(claimed.requestId()).orElseThrow();
        assertThat(current.status()).isEqualTo("IN_PROGRESS");
        operations.complete(
            current.requestId(),
            AccountTenantCreatorBootstrapIntegrationFixture.completion(current));
      });

      UUID acceptedRequest = UUID.randomUUID();
      UUID acceptedAdmission = UUID.randomUUID();
      UUID acceptedAttempt = UUID.randomUUID();
      UUID acceptedDecision = UUID.randomUUID();
      UUID reservationId = UUID.randomUUID();
      String acceptedDigest = "c".repeat(64);
      byte[] acceptedPayload = new byte[] {7, 0, 8, 9};
      String evaluationIdentity = "sha256:" + "d".repeat(64);
      String snapshotIdentity = "sha256:" + "e".repeat(64);
      String callerWorkload = "controlled-capacity-fixture";
      Instant evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
      Instant evaluationExpiresAt = evaluatedAt.plusSeconds(60);
      Instant reservationExpiresAt = evaluatedAt.plusSeconds(30);
      Instant reconcileDeadline = reservationExpiresAt.plusSeconds(60);
      transaction.executeWithoutResult(status -> {
        dsl.execute(
            "INSERT INTO account_tenant_capacity_evaluations (evaluation_identity,"
                + " snapshot_identity, tenant_uuid, caller_workload, owner_service,"
                + " canonical_evidence, evaluated_at, expires_at) VALUES (?, ?, ?, ?,"
                + " 'game-session-service', ?, ?::timestamptz, ?::timestamptz)",
            evaluationIdentity,
            snapshotIdentity,
            acceptedTenant.canonicalTenantId(),
            callerWorkload,
            new byte[] {10, 11, 12},
            AccountTenantCapacityRepository.at(evaluatedAt),
            AccountTenantCapacityRepository.at(evaluationExpiresAt));
        assertThat(
                dsl.execute(
                    "UPDATE account_tenant_capacity_usage SET held_units = 1, usage_revision = 2"
                        + " WHERE tenant_uuid = ? AND owner_service = 'game-session-service' AND"
                        + " held_units = 0 AND used_units = 0 AND usage_revision = 1",
                    acceptedTenant.canonicalTenantId()))
            .isEqualTo(1);
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id,"
                + " capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload,"
                + " decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'ACCEPTED', ?)",
            acceptedTenant.canonicalTenantId(),
            acceptedRequest,
            acceptedAdmission,
            acceptedAttempt,
            acceptedDigest,
            acceptedPayload,
            acceptedDecision);
        dsl.execute(
            "INSERT INTO account_tenant_capacity_reservations (tenant_uuid, request_id,"
                + " admission_id, reservation_id, owner_service, caller_workload,"
                + " admission_attempt_id, capacity_rearm_fence, reservation_fence, mutation_digest,"
                + " canonical_payload, evaluation_identity, capacity_delta, expires_at,"
                + " reconcile_deadline, state, execution_status) VALUES (?, ?, ?, ?,"
                + " 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 1, ?::timestamptz, ?::timestamptz,"
                + " 'RESERVED', 'OWNER_EXECUTION_PENDING')",
            acceptedTenant.canonicalTenantId(),
            acceptedRequest,
            acceptedAdmission,
            reservationId,
            callerWorkload,
            acceptedAttempt,
            acceptedDigest,
            acceptedPayload,
            evaluationIdentity,
            AccountTenantCapacityRepository.at(reservationExpiresAt),
            AccountTenantCapacityRepository.at(reconcileDeadline));
      });

      capacityMigrations(data, schema).target("1040.5").load().migrate();
      transaction.executeWithoutResult(status -> {
        budgets.provision(historicalTenant.canonicalTenantId(), 3);
        budgets.provision(otherHistoricalTenant.canonicalTenantId(), 3);
      });
      var provisionedBudgetAtV1040_5 =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget"
                      + " WHERE tenant_uuid = ?",
                  historicalTenant.canonicalTenantId())
              .intoMap();
      var otherProvisionedBudgetAtV1040_5 =
          dsl.fetchSingle(
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget"
                      + " WHERE tenant_uuid = ?",
                  otherHistoricalTenant.canonicalTenantId())
              .intoMap();
      var originalDecisions =
          dsl.fetch(
                  "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence,"
                      + " admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex')"
                      + " AS payload, decision, decision_id, refusal_code, xmin::text AS"
                      + " row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
              .intoMaps();
      assertThat(originalDecisions).hasSize(4);
      var acceptedReservationsAtV1040_5 = reservationRows(dsl, acceptedTenant.canonicalTenantId());
      var acceptedUsageAtV1040_5 = usageRows(dsl, acceptedTenant.canonicalTenantId());

      capacityMigrations(data, schema).target("1040.6").load().migrate();

      assertThat(reservationRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedReservationsAtV1040_5);
      assertThat(usageRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedUsageAtV1040_5);
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM"
                          + " account_capacity_origin_refusal_budget WHERE tenant_uuid = ?",
                      historicalTenant.canonicalTenantId())
                  .intoMap())
          .isEqualTo(provisionedBudgetAtV1040_5);
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM"
                          + " account_capacity_origin_refusal_budget WHERE tenant_uuid = ?",
                      otherHistoricalTenant.canonicalTenantId())
                  .intoMap())
          .isEqualTo(otherProvisionedBudgetAtV1040_5);
      Long remainingHeadroom =
          transaction.execute(
              status -> budgets.requireReady(historicalTenant.canonicalTenantId(), 3, 1));
      assertThat(remainingHeadroom).isEqualTo(1L);
      Long otherRemainingHeadroom =
          transaction.execute(
              status -> budgets.requireReady(otherHistoricalTenant.canonicalTenantId(), 3, 2));
      assertThat(otherRemainingHeadroom).isEqualTo(2L);
      assertThat(
              dsl.fetch(
                      "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence,"
                          + " admission_attempt_id, mutation_digest, encode(canonical_payload,"
                          + " 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS"
                          + " row_version FROM account_capacity_origin_decisions ORDER BY"
                          + " decision_id")
                  .intoMaps())
          .isEqualTo(originalDecisions);
      assertThat(refusalSlots(dsl))
          .containsExactlyInAnyOrder(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision),
              new RefusalSlot(
                  otherHistoricalTenant.canonicalTenantId(), 1, otherHistoricalDecision));
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_budget WHERE tenant_uuid = ?",
                  acceptedTenant.canonicalTenantId()))
          .isNull();
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_slots WHERE decision_id = ?",
                  acceptedDecision))
          .isNull();

      var expectedSlots =
          List.of(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision),
              new RefusalSlot(
                  otherHistoricalTenant.canonicalTenantId(), 1, otherHistoricalDecision));
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot,"
                      + " decision_id) VALUES (?, 3, ?)",
                  acceptedTenant.canonicalTenantId(),
                  acceptedDecision))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot,"
                      + " decision_id) VALUES (?, 3, ?)",
                  acceptedTenant.canonicalTenantId(),
                  firstHistoricalDecision))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);

      String insertedDecision =
          "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id,"
              + " capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload,"
              + " decision, decision_id, refusal_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON"
              + " CONFLICT DO NOTHING";
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_budget WHERE tenant_uuid = ?",
                  acceptedTenant.canonicalTenantId()))
          .isNull();
      var acceptedAfterMigration = freshAcceptedAttempt();
      transaction.executeWithoutResult(status ->
          insertAcceptedCapacityAttempt(
              dsl, acceptedTenant.canonicalTenantId(), acceptedAfterMigration, 1, 2));
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ?",
                  acceptedTenant.canonicalTenantId()))
          .isNull();
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);
      assertTenantCapacityInventoryMatches(dsl, acceptedTenant.canonicalTenantId());

      var acceptedOriginsBeforeReplay = originRows(dsl, acceptedTenant.canonicalTenantId());
      var acceptedReservationsBeforeReplay =
          reservationRows(dsl, acceptedTenant.canonicalTenantId());
      var acceptedUsageBeforeReplay = usageRows(dsl, acceptedTenant.canonicalTenantId());
      assertThat(acceptedOriginsBeforeReplay)
          .anySatisfy(row ->
              assertThat(row)
                  .containsEntry("request_id", acceptedAfterMigration.requestId())
                  .containsEntry("admission_id", acceptedAfterMigration.admissionId())
                  .containsEntry("admission_attempt_id", acceptedAfterMigration.attemptId())
                  .containsEntry("mutation_digest", acceptedAfterMigration.digest())
                  .containsEntry("decision", "ACCEPTED")
                  .containsEntry(
                      "payload",
                      java.util.HexFormat.of().formatHex(acceptedAfterMigration.payload())));
      assertThat(acceptedReservationsBeforeReplay)
          .anySatisfy(row ->
              assertThat(row)
                  .containsEntry("request_id", acceptedAfterMigration.requestId())
                  .containsEntry("admission_id", acceptedAfterMigration.admissionId())
                  .containsEntry("admission_attempt_id", acceptedAfterMigration.attemptId())
                  .containsEntry("mutation_digest", acceptedAfterMigration.digest())
                  .containsEntry("capacity_delta", 1L)
                  .containsEntry(
                      "payload",
                      java.util.HexFormat.of().formatHex(acceptedAfterMigration.payload())));

      assertThat(
              dsl.execute(
                  insertedDecision,
                  acceptedTenant.canonicalTenantId(),
                  acceptedAfterMigration.requestId(),
                  acceptedAfterMigration.admissionId(),
                  1L,
                  acceptedAfterMigration.attemptId(),
                  acceptedAfterMigration.digest(),
                  acceptedAfterMigration.payload(),
                  "ACCEPTED",
                  UUID.randomUUID(),
                  null))
          .isZero();
      assertThat(originRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedOriginsBeforeReplay);
      assertThat(reservationRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedReservationsBeforeReplay);
      assertThat(usageRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedUsageBeforeReplay);

      var rolledBackAccepted = freshAcceptedAttempt();
      transaction.executeWithoutResult(status -> {
        insertAcceptedCapacityAttempt(
            dsl, acceptedTenant.canonicalTenantId(), rolledBackAccepted, 2, 3);
        status.setRollbackOnly();
      });
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_decisions WHERE tenant_uuid = ? AND"
                      + " request_id = ? AND admission_id = ?",
                  acceptedTenant.canonicalTenantId(),
                  rolledBackAccepted.requestId(),
                  rolledBackAccepted.admissionId()))
          .isNull();
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_tenant_capacity_reservations WHERE tenant_uuid = ? AND"
                      + " request_id = ? AND admission_id = ?",
                  acceptedTenant.canonicalTenantId(),
                  rolledBackAccepted.requestId(),
                  rolledBackAccepted.admissionId()))
          .isNull();
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_tenant_capacity_evaluations WHERE evaluation_identity = ?",
                  rolledBackAccepted.evaluationIdentity()))
          .isNull();
      assertThat(originRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedOriginsBeforeReplay);
      assertThat(reservationRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedReservationsBeforeReplay);
      assertThat(usageRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedUsageBeforeReplay);
      assertTenantCapacityInventoryMatches(dsl, acceptedTenant.canonicalTenantId());

      UUID refusedWithoutBudgetRequest = UUID.randomUUID();
      UUID refusedWithoutBudgetAdmission = UUID.randomUUID();
      assertThatThrownBy(() ->
              dsl.execute(
                  insertedDecision,
                  acceptedTenant.canonicalTenantId(),
                  refusedWithoutBudgetRequest,
                  refusedWithoutBudgetAdmission,
                  1L,
                  UUID.randomUUID(),
                  "7".repeat(64),
                  new byte[] {24, 25},
                  "NO_ACCEPT",
                  UUID.randomUUID(),
                  "CAPACITY_QUOTA_EXCEEDED"))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure).hasMessageContaining("Capacity refusal budget is unavailable");
              });
      assertThat(originRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedOriginsBeforeReplay);
      assertThat(reservationRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedReservationsBeforeReplay);
      assertThat(usageRows(dsl, acceptedTenant.canonicalTenantId()))
          .isEqualTo(acceptedUsageBeforeReplay);
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);

      UUID freshRequest = UUID.randomUUID();
      UUID freshAdmission = UUID.randomUUID();
      UUID freshAttempt = UUID.randomUUID();
      UUID freshDecision = UUID.randomUUID();
      Object[] freshRefusal = {
        historicalTenant.canonicalTenantId(),
        freshRequest,
        freshAdmission,
        1L,
        freshAttempt,
        "f".repeat(64),
        new byte[] {13, 14},
        "NO_ACCEPT",
        freshDecision,
        "CAPACITY_QUOTA_EXCEEDED"
      };
      transaction.executeWithoutResult(status -> {
        assertThat(dsl.execute(insertedDecision, freshRefusal)).isEqualTo(1);
        status.setRollbackOnly();
      });
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);
      assertThat(dsl.execute(insertedDecision, freshRefusal)).isEqualTo(1);
      expectedSlots =
          List.of(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 3, freshDecision),
              new RefusalSlot(
                  otherHistoricalTenant.canonicalTenantId(), 1, otherHistoricalDecision));
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);

      Object[] refusalReplay = freshRefusal.clone();
      refusalReplay[8] = UUID.randomUUID();
      assertThat(dsl.execute(insertedDecision, refusalReplay)).isZero();
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);
      assertThat(
              dsl.execute(
                  insertedDecision,
                  historicalTenant.canonicalTenantId(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1L,
                  freshAttempt,
                  "1".repeat(64),
                  new byte[] {15},
                  "NO_ACCEPT",
                  UUID.randomUUID(),
                  "CAPACITY_QUOTA_EXCEEDED"))
          .isZero();
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);
      assertThat(
              dsl.execute(
                  insertedDecision,
                  acceptedTenant.canonicalTenantId(),
                  acceptedRequest,
                  acceptedAdmission,
                  1L,
                  acceptedAttempt,
                  acceptedDigest,
                  acceptedPayload,
                  "ACCEPTED",
                  UUID.randomUUID(),
                  null))
          .isZero();
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);

      assertThatThrownBy(() ->
              dsl.execute(
                  insertedDecision,
                  historicalTenant.canonicalTenantId(),
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1L,
                  UUID.randomUUID(),
                  "2".repeat(64),
                  new byte[] {16},
                  "NO_ACCEPT",
                  UUID.randomUUID(),
                  "CAPACITY_QUOTA_EXCEEDED"))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23514");
                assertThat(failure).hasMessageContaining("Capacity refusal budget is exhausted");
              });
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isEqualTo(6);
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);

      assertThatThrownBy(() ->
              dsl.execute(
                  "UPDATE account_capacity_origin_refusal_slots SET slot = slot + 10 WHERE"
                      + " tenant_uuid = ? AND slot = 1",
                  historicalTenant.canonicalTenantId()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure ->
                  assertThat(failure)
                      .hasMessageContaining("Account capacity origin decisions are immutable"));
      assertThatThrownBy(() ->
              dsl.execute(
                  "DELETE FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ? AND slot"
                      + " = 1",
                  historicalTenant.canonicalTenantId()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure ->
                  assertThat(failure)
                      .hasMessageContaining("Account capacity origin decisions are immutable"));
      assertThatThrownBy(() -> dsl.execute("TRUNCATE account_capacity_origin_refusal_slots"))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure ->
                  assertThat(failure)
                      .hasMessageContaining("Account capacity origin decisions are immutable"));
      assertThat(refusalSlots(dsl)).containsExactlyInAnyOrderElementsOf(expectedSlots);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static List<RefusalSlot> refusalSlots(org.jooq.DSLContext dsl) {
    return dsl.fetch(
            "SELECT tenant_uuid, slot, decision_id FROM account_capacity_origin_refusal_slots ORDER"
                + " BY tenant_uuid, slot")
        .map(row ->
            new RefusalSlot(
                row.get("tenant_uuid", UUID.class),
                row.get("slot", Long.class),
                row.get("decision_id", UUID.class)));
  }

  private static AcceptedCapacityAttempt freshAcceptedAttempt() {
    UUID requestId = UUID.randomUUID();
    Instant evaluatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant evaluationExpiresAt = evaluatedAt.plusSeconds(60);
    Instant reservationExpiresAt = evaluatedAt.plusSeconds(30);
    return new AcceptedCapacityAttempt(
        requestId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        ("fresh accepted capacity payload " + requestId).getBytes(StandardCharsets.UTF_8),
        "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2),
        "sha256:" + UUID.randomUUID().toString().replace("-", "").repeat(2),
        evaluatedAt,
        evaluationExpiresAt,
        reservationExpiresAt,
        reservationExpiresAt.plusSeconds(60));
  }

  private static void insertAcceptedCapacityAttempt(
      org.jooq.DSLContext dsl,
      UUID tenantUuid,
      AcceptedCapacityAttempt attempt,
      long expectedHeldUnits,
      long newHeldUnits) {
    assertThat(attempt.reservationExpiresAt()).isBefore(attempt.evaluationExpiresAt());
    String callerWorkload = "controlled-capacity-fixture";
    dsl.execute(
        "INSERT INTO account_tenant_capacity_evaluations (evaluation_identity, snapshot_identity,"
            + " tenant_uuid, caller_workload, owner_service, canonical_evidence, evaluated_at,"
            + " expires_at) VALUES (?, ?, ?, ?, 'game-session-service', ?, ?::timestamptz,"
            + " ?::timestamptz)",
        attempt.evaluationIdentity(),
        attempt.snapshotIdentity(),
        tenantUuid,
        callerWorkload,
        ("fresh accepted evaluation " + attempt.requestId()).getBytes(StandardCharsets.UTF_8),
        AccountTenantCapacityRepository.at(attempt.evaluatedAt()),
        AccountTenantCapacityRepository.at(attempt.evaluationExpiresAt()));
    assertThat(
            dsl.execute(
                "UPDATE account_tenant_capacity_usage SET held_units = ?, usage_revision = ? WHERE"
                    + " tenant_uuid = ? AND owner_service = 'game-session-service' AND held_units ="
                    + " ? AND used_units = 0 AND usage_revision = ?",
                newHeldUnits,
                newHeldUnits + 1,
                tenantUuid,
                expectedHeldUnits,
                expectedHeldUnits + 1))
        .isEqualTo(1);
    assertThat(
            dsl.execute(
                "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id,"
                    + " admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest,"
                    + " canonical_payload, decision, decision_id, refusal_code) VALUES (?, ?, ?, 1,"
                    + " ?, ?, ?, 'ACCEPTED', ?, NULL)",
                tenantUuid,
                attempt.requestId(),
                attempt.admissionId(),
                attempt.attemptId(),
                attempt.digest(),
                attempt.payload(),
                attempt.decisionId()))
        .isEqualTo(1);
    assertThat(
            dsl.execute(
                "INSERT INTO account_tenant_capacity_reservations (tenant_uuid, request_id,"
                    + " admission_id, reservation_id, owner_service, caller_workload,"
                    + " admission_attempt_id, capacity_rearm_fence, reservation_fence,"
                    + " mutation_digest, canonical_payload, evaluation_identity, capacity_delta,"
                    + " expires_at, reconcile_deadline, state, execution_status) VALUES (?, ?, ?,"
                    + " ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 1, ?::timestamptz,"
                    + " ?::timestamptz, 'RESERVED', 'OWNER_EXECUTION_PENDING')",
                tenantUuid,
                attempt.requestId(),
                attempt.admissionId(),
                attempt.reservationId(),
                callerWorkload,
                attempt.attemptId(),
                attempt.digest(),
                attempt.payload(),
                attempt.evaluationIdentity(),
                AccountTenantCapacityRepository.at(attempt.reservationExpiresAt()),
                AccountTenantCapacityRepository.at(attempt.reconcileDeadline())))
        .isEqualTo(1);
  }

  private static List<java.util.Map<String, Object>> originRows(
      org.jooq.DSLContext dsl, UUID tenantUuid) {
    return dsl.fetch(
            "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence,"
                + " admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex') AS"
                + " payload, decision, decision_id, refusal_code, xmin::text AS row_version FROM"
                + " account_capacity_origin_decisions WHERE tenant_uuid = ? ORDER BY decision_id",
            tenantUuid)
        .intoMaps();
  }

  private static List<java.util.Map<String, Object>> reservationRows(
      org.jooq.DSLContext dsl, UUID tenantUuid) {
    return dsl.fetch(
            "SELECT tenant_uuid, request_id, admission_id, reservation_id, owner_service,"
                + " caller_workload, admission_attempt_id, capacity_rearm_fence, reservation_fence,"
                + " mutation_digest, encode(canonical_payload, 'hex') AS payload,"
                + " evaluation_identity, capacity_delta, expires_at, reconcile_deadline, state,"
                + " execution_status, recovery_owner, recovery_claim_fence,"
                + " recovery_claim_expires_at, next_reconcile_at, recovery_policy_version,"
                + " recovery_attempt_count, recovery_escalated_at, encode(owner_readback, 'hex') AS"
                + " owner_readback, owner_readback_digest, xmin::text AS row_version FROM"
                + " account_tenant_capacity_reservations WHERE tenant_uuid = ? ORDER BY request_id,"
                + " admission_id",
            tenantUuid)
        .intoMaps();
  }

  private static List<java.util.Map<String, Object>> usageRows(
      org.jooq.DSLContext dsl, UUID tenantUuid) {
    return dsl.fetch(
            "SELECT tenant_uuid, owner_service, bootstrap_request_id, creation_evidence_digest,"
                + " held_units, used_units, usage_revision, xmin::text AS row_version FROM"
                + " account_tenant_capacity_usage WHERE tenant_uuid = ? ORDER BY owner_service",
            tenantUuid)
        .intoMaps();
  }

  private static void assertTenantCapacityInventoryMatches(
      org.jooq.DSLContext dsl, UUID tenantUuid) {
    var rows =
        dsl.fetch(
                "SELECT u.owner_service, u.held_units, u.used_units, COALESCE(sum(r.capacity_delta)"
                    + " FILTER (WHERE r.state IN ('RESERVED',"
                    + " 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')), 0) AS"
                    + " reservation_held_units, COALESCE(sum(r.capacity_delta) FILTER (WHERE"
                    + " r.state = 'FINALIZED'), 0) AS reservation_used_units FROM"
                    + " account_tenant_capacity_usage u LEFT JOIN"
                    + " account_tenant_capacity_reservations r ON r.tenant_uuid = u.tenant_uuid AND"
                    + " r.owner_service = u.owner_service WHERE u.tenant_uuid = ? GROUP BY"
                    + " u.owner_service, u.held_units, u.used_units ORDER BY u.owner_service",
                tenantUuid)
            .intoMaps();
    assertThat(rows).hasSize(2);
    for (var row : rows) {
      assertThat(((Number) row.get("held_units")).longValue())
          .isEqualTo(((Number) row.get("reservation_held_units")).longValue());
      assertThat(((Number) row.get("used_units")).longValue())
          .isEqualTo(((Number) row.get("reservation_used_units")).longValue());
    }
  }

  private record AcceptedCapacityAttempt(
      UUID requestId,
      UUID admissionId,
      UUID attemptId,
      UUID decisionId,
      UUID reservationId,
      String digest,
      byte[] payload,
      String evaluationIdentity,
      String snapshotIdentity,
      Instant evaluatedAt,
      Instant evaluationExpiresAt,
      Instant reservationExpiresAt,
      Instant reconcileDeadline) {}

  private record RefusalSlot(UUID tenantUuid, long slot, UUID decisionId) {}

  static Flyway configureFlyway(javax.sql.DataSource data, String schema, String target) {
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

  static List<String> capacityTableImages(DSLContext dsl) {
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
      appendCapacityTableImage(dsl, table, images);
    }
    return List.copyOf(images);
  }

  static List<String> capacityFreshnessTableImages(DSLContext dsl) {
    List<String> images = new ArrayList<>(capacityTableImages(dsl));
    appendCapacityTableImage(dsl, "account_capacity_origin_decisions", images);
    return List.copyOf(images);
  }

  private static void appendCapacityTableImage(DSLContext dsl, String table, List<String> images) {
    images.add(table);
    images.addAll(
        dsl.fetch(
                "SELECT (to_jsonb(row_data) || jsonb_build_object('_original_xmin', row_data.xmin::text))::text AS row_image "
                    + "FROM "
                    + table
                    + " row_data ORDER BY row_image")
            .getValues("row_image", String.class));
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

  static CapacityFixture populateCapacity(DSLContext dsl, TransactionTemplate transaction) {
    UUID accountUuid =
        Objects.requireNonNull(
            transaction.execute(status ->
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

    transaction.executeWithoutResult(status -> {
      new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant);
      UUID attemptId = UUID.randomUUID();
      String mutationDigest = hex('c');
      byte[] canonicalPayload = new byte[] {8};
      insertOriginDecisionIfPresent(
          dsl,
          tenant.canonicalTenantId(),
          requestId,
          admissionId,
          1,
          attemptId,
          mutationDigest,
          canonicalPayload);
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
          "account:auth-authority:v1:membership/" + accountUuid + "/" + tenant.canonicalTenantId(),
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
          attemptId,
          mutationDigest,
          canonicalPayload,
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

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status ->
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

    transaction.executeWithoutResult(status -> {
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
      dsl.execute("SET CONSTRAINTS account_tenant_capacity_reservation_complete_update IMMEDIATE");
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
    transaction.executeWithoutResult(status -> {
      UUID requestId = UUID.randomUUID();
      UUID admissionId = UUID.randomUUID();
      UUID attemptId = UUID.randomUUID();
      String mutationDigest = hex('4');
      byte[] canonicalPayload = new byte[] {9};
      insertOriginDecisionIfPresent(
          dsl,
          capacity.tenantUuid(),
          requestId,
          admissionId,
          1,
          attemptId,
          mutationDigest,
          canonicalPayload);
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
          requestId,
          admissionId,
          UUID.randomUUID(),
          capacity.callerWorkload(),
          attemptId,
          mutationDigest,
          canonicalPayload,
          capacity.evaluationIdentity());
      status.setRollbackOnly();
      assertThatThrownBy(() ->
              dsl.execute(
                  "SET CONSTRAINTS account_tenant_capacity_reservation_complete_insert IMMEDIATE"))
          .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
    });
  }

  private static void insertOriginDecisionIfPresent(
      DSLContext dsl,
      UUID tenantUuid,
      UUID requestId,
      UUID admissionId,
      long rearmFence,
      UUID attemptId,
      String mutationDigest,
      byte[] canonicalPayload) {
    if (dsl.fetchSingle("SELECT to_regclass('account_capacity_origin_decisions') IS NOT NULL")
        .get(0, Boolean.class)) {
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
  }

  private static void assertReservationStateChangeMismatchRefused(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    transaction.executeWithoutResult(status -> {
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
      status.setRollbackOnly();
      assertThatThrownBy(() ->
              dsl.execute(
                  "SET CONSTRAINTS account_tenant_capacity_reservation_complete_update IMMEDIATE"))
          .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
    });
  }

  private static void assertUsageWriteMismatchRefused(
      DSLContext dsl, TransactionTemplate transaction, CapacityFixture capacity) {
    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              dsl.execute(
                  "UPDATE account_tenant_capacity_usage SET used_units = used_units + 1, "
                      + "usage_revision = usage_revision + 1 "
                      + "WHERE tenant_uuid = ? AND owner_service = 'game-session-service'",
                  capacity.tenantUuid());
              dsl.execute("SET CONSTRAINTS account_tenant_capacity_usage_complete IMMEDIATE");
            }))
        .satisfies(AccountTenantCapacityMigrationPostgresIntegrationTest::assertInventoryRefusal);
  }

  private static void assertInventoryRefusal(Throwable failure) {
    assertThat(sqlState(failure)).isEqualTo("23514");
    assertThat(failure)
        .hasStackTraceContaining(
            "Capacity usage differs from its complete durable reservation inventory");
  }

  static String sqlState(Throwable failure) {
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

  static String hex(char digit) {
    return String.valueOf(digit).repeat(64);
  }

  private record PredecessorImage(
      List<String> migrationHistoryImage,
      Integer migrationChecksum,
      List<String> tableImages,
      List<String> otherTriggerImages,
      String exactUsageFunctionImage) {}

  record CapacityFixture(
      UUID tenantUuid,
      UUID requestId,
      UUID admissionId,
      String evaluationIdentity,
      String callerWorkload) {}

  private static Account creator() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    var account = new Account();
    account.setUsername("capacity-" + suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("test-only-password-hash");
    account.setRole("player");
    return account;
  }

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
