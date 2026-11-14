package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCapacityRefusalBudgetRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
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
              transaction.execute(
                  status -> {
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
              transaction.execute(
                  status -> {
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
      assertThatThrownBy(() -> transaction.execute(status ->
          budgets.requireReady(existing.canonicalTenantId(), 4, 2)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      transaction.executeWithoutResult(status -> budgets.provision(existing.canonicalTenantId(), 4));
      var beforeBudget = dsl.fetchSingle("SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=?", existing.canonicalTenantId()).intoMap();
      transaction.executeWithoutResult(status -> budgets.provision(existing.canonicalTenantId(), 4));
      assertThat(dsl.fetchSingle("SELECT *, xmin::text AS row_version FROM account_capacity_origin_refusal_budget WHERE tenant_uuid=?", existing.canonicalTenantId()).intoMap()).isEqualTo(beforeBudget);
      Long readyHeadroom = transaction.execute(status -> budgets.requireReady(existing.canonicalTenantId(), 4, 2));
      assertThat(readyHeadroom).isEqualTo(4L);
      assertThatThrownBy(() -> transaction.executeWithoutResult(status -> budgets.provision(existing.canonicalTenantId(), 5)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      assertThatThrownBy(() -> transaction.execute(status -> budgets.requireReady(later.canonicalTenantId(), 4, 1)))
          .isInstanceOf(AccountTenantCapacityRepository.CapacityException.class);
      assertThatThrownBy(() -> transaction.executeWithoutResult(status -> budgets.provision(existing.canonicalTenantId(), 0)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_usage"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_tenant_capacity_reservations"))).isZero();
      assertThat(dsl.fetchCount(DSL.table("account_capacity_origin_decisions"))).isZero();

    } finally {
      new JdbcTemplate(POSTGRES.dataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

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
