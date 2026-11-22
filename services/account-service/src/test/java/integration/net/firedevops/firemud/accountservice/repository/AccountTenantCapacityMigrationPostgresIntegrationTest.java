package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCapacityRefusalBudgetRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapDigest;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.Completion;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository.StoredOperation;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
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
  void duplicateHistoricalOriginAttemptsStopUpgradeWithoutRewritingEvidence() {
    String schema = "capacity_duplicate_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.1")
          .load()
          .migrate();
      var dsl = DSL.using(data, SQLDialect.POSTGRES);
      UUID attempt = UUID.randomUUID();
      for (int index = 0; index < 2; index++) {
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            attempt,
            "a".repeat(64),
            new byte[] {1},
            UUID.randomUUID());
      }
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.3")
          .load()
          .migrate();
      var original =
          dsl.fetch(
                  "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
              .intoMaps();
      assertThatThrownBy(() ->
              Flyway.configure()
                  .dataSource(data)
                  .schemas(schema)
                  .defaultSchema(schema)
                  .placeholders(java.util.Map.of("serviceSchema", schema))
                  .locations("classpath:db/migration")
                  .target("1040.4")
                  .load()
                  .migrate())
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
                      "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
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
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.1")
          .load()
          .migrate();
      var dsl = DSL.using(new TransactionAwareDataSourceProxy(data), SQLDialect.POSTGRES);
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
      var tenant = source();
      transaction.executeWithoutResult(status ->
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenant));
      UUID retainedAttempt = UUID.randomUUID();
      for (int index = 0; index < 3; index++) {
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
            tenant.canonicalTenantId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            index == 0 ? retainedAttempt : UUID.randomUUID(),
            "a".repeat(64),
            new byte[] {1},
            UUID.randomUUID());
      }
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
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
                      "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget"))
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
                          "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget"))
                  .intoMap())
          .isEqualTo(original);
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)",
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
      var operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      var existing = source();
      var historical =
          Objects.requireNonNull(
              transaction.execute(status -> {
                repository.importVerified(existing);
                return claimBootstrap(
                    operations, new AccountRepository(dsl).save(creator()), existing);
              }));
      assertThat(historical.status()).isEqualTo("IN_PROGRESS");
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
      var completed =
          Objects.requireNonNull(
              transaction.execute(status -> {
                var operation = operations.findForUpdate(historical.requestId()).orElseThrow();
                assertThat(operation.status()).isEqualTo("IN_PROGRESS");
                assertThat(operation.creationOperationId()).isEqualTo(existing.operationId());
                assertThat(operation.requestPayload()).isEqualTo(historical.requestPayload());
                return operations.complete(operation.requestId(), completion(operation));
              }));
      assertThat(completed.status()).isEqualTo("COMMITTED");
      assertThat(completed.tenantUuid()).isEqualTo(existing.canonicalTenantId());
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
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
                  "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=?",
                  existing.canonicalTenantId())
              .intoMap();
      transaction.executeWithoutResult(
          status -> budgets.provision(existing.canonicalTenantId(), 4));
      assertThat(
              dsl.fetchSingle(
                      "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=?",
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
  void refusalBudgetRunsAfterIdentityArbitrationAndCountsTheInsertedLastSlot() throws Exception {
    String schema = "capacity_arbitration_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(POSTGRES.dataSource()).execute("CREATE SCHEMA " + schema);
    var data = POSTGRES.dataSource(schema);
    try {
      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .load()
          .migrate();
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
                      "SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget")
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
      UUID freshRequest = UUID.randomUUID();
      UUID freshAdmission = UUID.randomUUID();
      UUID freshAttempt = UUID.randomUUID();
      UUID freshDecision = UUID.randomUUID();
      assertThat(List.of(winningRequest, losingRequest, freshRequest)).doesNotHaveDuplicates();
      assertThat(List.of(winningAdmission, losingAdmission, freshAdmission))
          .doesNotHaveDuplicates();
      assertThat(List.of(winningAttempt, losingAttempt, freshAttempt)).doesNotHaveDuplicates();
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
      assertThatThrownBy(() ->
              dsl.execute(
                  concurrentRefusalInsert,
                  concurrentTenant.canonicalTenantId(),
                  freshRequest,
                  freshAdmission,
                  freshAttempt,
                  "e".repeat(64),
                  new byte[] {5},
                  freshDecision))
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
  void refusalSlotsBackfillRetainedDecisionsAndOnlyFreshRefusalsSpendSlots() {
    String schema = "capacity_refusal_slots_" + UUID.randomUUID().toString().replace("-", "");
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
      var historicalTenant = source();
      transaction.executeWithoutResult(status ->
          new FreshTenantIdentityAssociationRepository(dsl, "prod")
              .importVerified(historicalTenant));

      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.1")
          .load()
          .migrate();
      UUID firstHistoricalRequest = UUID.randomUUID();
      UUID firstHistoricalAdmission = UUID.randomUUID();
      UUID firstHistoricalAttempt = UUID.randomUUID();
      UUID firstHistoricalDecision = UUID.fromString("00000000-0000-0000-0000-000000001041");
      UUID secondHistoricalRequest = UUID.randomUUID();
      UUID secondHistoricalAdmission = UUID.randomUUID();
      UUID secondHistoricalAttempt = UUID.randomUUID();
      UUID secondHistoricalDecision = UUID.fromString("00000000-0000-0000-0000-000000001042");
      String historicalInsert =
          "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'NO_ACCEPT', ?)";
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

      var acceptedTenant = source();
      var operations = new AccountTenantCreationBootstrapOperationRepository(dsl);
      transaction.executeWithoutResult(status -> {
        new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(acceptedTenant);
        var claimed =
            claimBootstrap(operations, new AccountRepository(dsl).save(creator()), acceptedTenant);
        var current = operations.findForUpdate(claimed.requestId()).orElseThrow();
        assertThat(current.status()).isEqualTo("IN_PROGRESS");
        operations.complete(current.requestId(), completion(current));
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
      Instant evaluationExpiresAt = evaluatedAt.plusSeconds(30);
      Instant reservationExpiresAt = evaluatedAt.plusSeconds(60);
      Instant reconcileDeadline = reservationExpiresAt.plusSeconds(60);
      transaction.executeWithoutResult(status -> {
        dsl.execute(
            "INSERT INTO account_tenant_capacity_evaluations (evaluation_identity, snapshot_identity, tenant_uuid, caller_workload, owner_service, canonical_evidence, evaluated_at, expires_at) VALUES (?, ?, ?, ?, 'game-session-service', ?, ?::timestamptz, ?::timestamptz)",
            evaluationIdentity,
            snapshotIdentity,
            acceptedTenant.canonicalTenantId(),
            callerWorkload,
            new byte[] {10, 11, 12},
            AccountTenantCapacityRepository.at(evaluatedAt),
            AccountTenantCapacityRepository.at(evaluationExpiresAt));
        assertThat(
                dsl.execute(
                    "UPDATE account_tenant_capacity_usage SET held_units = 1, usage_revision = 2 WHERE tenant_uuid = ? AND owner_service = 'game-session-service' AND held_units = 0 AND used_units = 0 AND usage_revision = 1",
                    acceptedTenant.canonicalTenantId()))
            .isEqualTo(1);
        dsl.execute(
            "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id) VALUES (?, ?, ?, 1, ?, ?, ?, 'ACCEPTED', ?)",
            acceptedTenant.canonicalTenantId(),
            acceptedRequest,
            acceptedAdmission,
            acceptedAttempt,
            acceptedDigest,
            acceptedPayload,
            acceptedDecision);
        dsl.execute(
            "INSERT INTO account_tenant_capacity_reservations (tenant_uuid, request_id, admission_id, reservation_id, owner_service, caller_workload, admission_attempt_id, capacity_rearm_fence, reservation_fence, mutation_digest, canonical_payload, evaluation_identity, capacity_delta, expires_at, reconcile_deadline, state, execution_status) VALUES (?, ?, ?, ?, 'game-session-service', ?, ?, 1, 1, ?, ?, ?, 1, ?::timestamptz, ?::timestamptz, 'RESERVED', 'OWNER_EXECUTION_PENDING')",
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

      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.5")
          .load()
          .migrate();
      var originalDecisions =
          dsl.fetch(
                  "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
              .intoMaps();
      assertThat(originalDecisions).hasSize(3);

      Flyway.configure()
          .dataSource(data)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(java.util.Map.of("serviceSchema", schema))
          .locations("classpath:db/migration")
          .target("1040.6")
          .load()
          .migrate();

      assertThat(
              dsl.fetch(
                      "SELECT tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, encode(canonical_payload, 'hex') AS payload, decision, decision_id, refusal_code, xmin::text AS row_version FROM account_capacity_origin_decisions ORDER BY decision_id")
                  .intoMaps())
          .isEqualTo(originalDecisions);
      assertThat(refusalSlots(dsl))
          .containsExactly(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision));
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_budget WHERE tenant_uuid = ?",
                  historicalTenant.canonicalTenantId()))
          .isNull();
      assertThat(
              dsl.fetchOne(
                  "SELECT 1 FROM account_capacity_origin_refusal_slots WHERE decision_id = ?",
                  acceptedDecision))
          .isNull();

      var expectedSlots =
          List.of(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision));
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot, decision_id) VALUES (?, 3, ?)",
                  acceptedTenant.canonicalTenantId(),
                  acceptedDecision))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThatThrownBy(() ->
              dsl.execute(
                  "INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot, decision_id) VALUES (?, 3, ?)",
                  acceptedTenant.canonicalTenantId(),
                  firstHistoricalDecision))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure -> assertThat(failure.sqlState()).isEqualTo("23514"));
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);

      transaction.executeWithoutResult(status ->
          new AccountCapacityRefusalBudgetRepository(dsl)
              .provision(historicalTenant.canonicalTenantId(), 3));
      String insertedDecision =
          "INSERT INTO account_capacity_origin_decisions (tenant_uuid, request_id, admission_id, capacity_rearm_fence, admission_attempt_id, mutation_digest, canonical_payload, decision, decision_id, refusal_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
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
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);
      assertThat(dsl.execute(insertedDecision, freshRefusal)).isEqualTo(1);
      expectedSlots =
          List.of(
              new RefusalSlot(historicalTenant.canonicalTenantId(), 1, firstHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 2, secondHistoricalDecision),
              new RefusalSlot(historicalTenant.canonicalTenantId(), 3, freshDecision));
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);

      Object[] refusalReplay = freshRefusal.clone();
      refusalReplay[8] = UUID.randomUUID();
      assertThat(dsl.execute(insertedDecision, refusalReplay)).isZero();
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);
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
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);
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
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);

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
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isEqualTo(4);
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);

      assertThatThrownBy(() ->
              dsl.execute(
                  "UPDATE account_capacity_origin_refusal_slots SET slot = slot + 10 WHERE tenant_uuid = ? AND slot = 1",
                  historicalTenant.canonicalTenantId()))
          .isInstanceOfSatisfying(
              org.jooq.exception.DataAccessException.class,
              failure ->
                  assertThat(failure)
                      .hasMessageContaining("Account capacity origin decisions are immutable"));
      assertThatThrownBy(() ->
              dsl.execute(
                  "DELETE FROM account_capacity_origin_refusal_slots WHERE tenant_uuid = ? AND slot = 1",
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
      assertThat(refusalSlots(dsl)).containsExactlyElementsOf(expectedSlots);
    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static List<RefusalSlot> refusalSlots(org.jooq.DSLContext dsl) {
    return dsl.fetch(
            "SELECT tenant_uuid, slot, decision_id FROM account_capacity_origin_refusal_slots ORDER BY tenant_uuid, slot")
        .map(row ->
            new RefusalSlot(
                row.get("tenant_uuid", UUID.class),
                row.get("slot", Long.class),
                row.get("decision_id", UUID.class)));
  }

  private record RefusalSlot(UUID tenantUuid, long slot, UUID decisionId) {}

  private static Account creator() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    var account = new Account();
    account.setUsername("capacity-" + suffix);
    account.setEmail(suffix + "@example.test");
    account.setPasswordHash("test-only-password-hash");
    account.setRole("player");
    return account;
  }

  private static StoredOperation claimBootstrap(
      AccountTenantCreationBootstrapOperationRepository operations,
      Account creator,
      FreshTenantCreationEvidence creation) {
    UUID accountId = creator.getAccountUuid();
    UUID authorizationId = UUID.randomUUID();
    String authorizationDigest =
        AccountTenantCreationBootstrapDigest.sha256(
            "fixture authorization".getBytes(StandardCharsets.UTF_8));
    var evidence =
        new FreshTenantCreatorEvidence(
            1,
            creation,
            accountId,
            authorizationId,
            authorizationDigest,
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, accountId, authorizationId, authorizationDigest));
    byte[] creatorPayload = AccountTenantCreationBootstrapDigest.creatorEvidencePayload(evidence);
    byte[] sourcePayload = "fixture source snapshot".getBytes(StandardCharsets.UTF_8);
    byte[] requestPayload =
        AccountTenantCreationBootstrapDigest.requestPayload(creatorPayload, sourcePayload);
    var claim =
        operations.claim(
            evidence,
            creatorPayload,
            sourcePayload,
            AccountTenantCreationBootstrapDigest.sha256(sourcePayload),
            requestPayload,
            AccountTenantCreationBootstrapDigest.sha256(requestPayload),
            1,
            1);
    assertThat(claim.claimed()).isTrue();
    return claim.operation();
  }

  private static Completion completion(StoredOperation operation) {
    // Controlled completion receipts exercise the real storage owner's guarded transition.
    byte[] event = "fixture membership event".getBytes(StandardCharsets.UTF_8);
    byte[] audit = "fixture bootstrap audit".getBytes(StandardCharsets.UTF_8);
    byte[] result = "fixture bootstrap result".getBytes(StandardCharsets.UTF_8);
    return new Completion(
        1,
        operation.baselineMembershipVersion() + 1,
        operation.baselineMembershipAuthorityGeneration(),
        "ACTIVE",
        false,
        "[\"tenantAdmin\"]".getBytes(StandardCharsets.UTF_8),
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + operation.initiatingAccountUuid()
            + "/"
            + operation.tenantUuid(),
        operation.requestId().toString(),
        1,
        "fixture-membership-event",
        AccountTenantCreationBootstrapDigest.sha256(event),
        false,
        event,
        UUID.randomUUID(),
        "ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED",
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        AccountTenantCreationBootstrapDigest.sha256(audit),
        audit,
        result,
        AccountTenantCreationBootstrapDigest.sha256(result));
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
