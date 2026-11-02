package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.CurrentOwnerBinding;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.InitialBindingResult;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.InitialRequest;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository.OperationConflictException;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL proof for initial billing-owner persistence, retry, collision, and rollback fences.
 * Synthetic preverified inputs prove storage only, not caller authentication, current authority, or
 * committed-receipt verification.
 */
class AccountInitialTenantBillingOwnerRepositoryPostgresIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private final Set<String> schemas = new HashSet<>();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropTestOwnedSchemas() {
    JdbcTemplate jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.matches("initial_billing_owner_[a-f0-9]{32}")) {
        throw new IllegalStateException("Refusing to clean an unowned PostgreSQL schema");
      }
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void exactRetryReturnsOriginalConsentAndBindingAndRejectsEveryChangedBinding() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest original =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 3);
    InitialBindingResult first =
        context.transaction().execute(status -> context.repository().establishInitial(original));
    assertThat(first).isNotNull();

    InitialBindingResult retry =
        context.transaction().execute(status -> context.repository().establishInitial(original));
    assertSameResult(first, retry);
    assertThat(first.ownerVersion()).isEqualTo(1L);
    assertThat(first.ownerGeneration()).isNotEqualTo(new UUID(0L, 0L));
    assertThat(first.ownerAccountUuid()).isEqualTo(seed.accountUuid());
    assertThat(first.tenantUuid()).isEqualTo(seed.tenantUuid());
    assertThat(first.consentAffirmative()).isTrue();

    CurrentOwnerBinding current =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .findCurrentOwnerForUpdate(seed.tenantUuid())
                        .orElseThrow());
    assertThat(current.ownerAccountUuid()).isEqualTo(first.ownerAccountUuid());
    assertThat(current.ownerVersion()).isEqualTo(first.ownerVersion());
    assertThat(current.ownerGeneration()).isEqualTo(first.ownerGeneration());
    assertThat(current.initialRequestId()).isEqualTo(first.requestId());

    FreshTenantCreatorEvidence otherAccountEvidence =
        creatorEvidence(UUID.randomUUID(), seed.creationEvidence());
    InitialRequest changedAccount =
        request(otherAccountEvidence, original.requestId(), UUID.randomUUID(), 3);
    assertConflictWithoutMutation(context, changedAccount, seed.tenantUuid());

    Seed otherTenant = seed(context, UUID.randomUUID());
    InitialRequest changedTenant =
        request(otherTenant.creatorEvidence(), original.requestId(), UUID.randomUUID(), 3);
    assertConflictWithoutMutation(context, changedTenant, seed.tenantUuid());
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                otherTenant.tenantUuid()))
        .isZero();
    assertThat(count(context.dsl(), "account_tenant_billing_owners", otherTenant.tenantUuid()))
        .isZero();

    InitialRequest changedConsentOperation =
        request(seed.creatorEvidence(), original.requestId(), UUID.randomUUID(), 3);
    assertConflictWithoutMutation(context, changedConsentOperation, seed.tenantUuid());
    InitialRequest changedConsentVersion =
        request(seed.creatorEvidence(), original.requestId(), original.consentOperationId(), 4);
    assertConflictWithoutMutation(context, changedConsentVersion, seed.tenantUuid());

    FreshTenantCreationEvidence changedSource =
        freshTenant(
            seed.tenantUuid(),
            seed.creationEvidence().creationRequestId(),
            UUID.randomUUID(),
            seed.creationEvidence().sourceGameRowId() + 100L);
    InitialRequest sourceMismatch =
        request(
            creatorEvidence(seed.accountUuid(), changedSource),
            UUID.randomUUID(),
            UUID.randomUUID(),
            3);
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(status -> context.repository().establishInitial(sourceMismatch)))
        .satisfies(
            failure -> {
              SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
              assertThat(sqlFailure.getMessage())
                  .contains("Initial billing-owner operation differs from its fresh tenant source");
            });
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                seed.tenantUuid()))
        .isEqualTo(1L);

    InitialRequest secondAttempt =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 3);
    assertConflictWithoutMutation(context, secondAttempt, seed.tenantUuid());
  }

  @Test
  void absentAndReadOnlyTransactionsCannotMutateEitherOwnerTable() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest request =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);

    assertThatThrownBy(() -> context.repository().establishInitial(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    TransactionTemplate readOnly = new TransactionTemplate(context.transactionManager());
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> context.repository().establishInitial(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                seed.tenantUuid()))
        .isZero();
    assertThat(count(context.dsl(), "account_tenant_billing_owners", seed.tenantUuid())).isZero();
  }

  @Test
  void rollbackRemovesBothInitialOperationAndCurrentOwnerAndAllowsTheSameOriginalRetry() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest request =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 2);

    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context.repository().establishInitial(request);
              status.setRollbackOnly();
            });
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                seed.tenantUuid()))
        .isZero();
    assertThat(count(context.dsl(), "account_tenant_billing_owners", seed.tenantUuid())).isZero();

    InitialBindingResult committed =
        context.transaction().execute(status -> context.repository().establishInitial(request));
    assertThat(committed).isNotNull();
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                seed.tenantUuid()))
        .isEqualTo(1L);
    assertThat(count(context.dsl(), "account_tenant_billing_owners", seed.tenantUuid()))
        .isEqualTo(1L);
  }

  @Test
  void deferredOwnerPairConstraintRejectsCaughtOwnerInsertFailureWithoutPartialCommit() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest request =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);
    context
        .setupDsl()
        .execute(
            "CREATE FUNCTION account_test_skip_initial_billing_owner_insert() "
                + "RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$");
    context
        .setupDsl()
        .execute(
            "CREATE TRIGGER account_test_skip_initial_billing_owner_insert "
                + "BEFORE INSERT ON account_tenant_billing_owners FOR EACH ROW "
                + "EXECUTE FUNCTION account_test_skip_initial_billing_owner_insert()");

    boolean[] repositoryFailureCaught = {false};
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(
                        status -> {
                          try {
                            context.repository().establishInitial(request);
                          } catch (OperationConflictException expected) {
                            repositoryFailureCaught[0] = true;
                            assertThat(
                                    count(
                                        context.dsl(),
                                        "account_initial_tenant_billing_owner_operations",
                                        seed.tenantUuid()))
                                .isEqualTo(1L);
                            assertThat(
                                    count(
                                        context.dsl(),
                                        "account_tenant_billing_owners",
                                        seed.tenantUuid()))
                                .isZero();
                          }
                          return null;
                        }))
        .satisfies(
            failure -> {
              SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23503");
              assertThat(sqlFailure.getMessage())
                  .contains("account_initial_billing_owner_current_owner_fk");
            });
    assertThat(repositoryFailureCaught[0]).isTrue();
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                seed.tenantUuid()))
        .isZero();
    assertThat(count(context.dsl(), "account_tenant_billing_owners", seed.tenantUuid())).isZero();
  }

  @Test
  void exactInitialReplaySurvivesLaterCurrentOwnerTupleAdvance() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest request =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 2);
    InitialBindingResult original =
        context.transaction().execute(status -> context.repository().establishInitial(request));
    UUID transferredOwner = insertAccount(context.setupDsl());
    UUID transferredGeneration = UUID.randomUUID();
    // Test-only current-tuple advance: no transfer repository or production transfer mutator
    // exists.
    int updated =
        context
            .setupDsl()
            .execute(
                "UPDATE account_tenant_billing_owners SET owner_account_uuid = ?, "
                    + "owner_version = 2, owner_generation = ?, updated_at = clock_timestamp() "
                    + "WHERE tenant_uuid = ? AND initial_request_id = ?",
                transferredOwner,
                transferredGeneration,
                seed.tenantUuid(),
                original.requestId());
    assertThat(updated).isEqualTo(1);

    InitialBindingResult replay =
        context.transaction().execute(status -> context.repository().establishInitial(request));
    assertSameResult(original, replay);
    CurrentOwnerBinding current =
        context
            .transaction()
            .execute(
                status ->
                    context
                        .repository()
                        .findCurrentOwnerForUpdate(seed.tenantUuid())
                        .orElseThrow());
    assertThat(current.ownerAccountUuid()).isEqualTo(transferredOwner);
    assertThat(current.ownerVersion()).isEqualTo(2L);
    assertThat(current.ownerGeneration()).isEqualTo(transferredGeneration);
    assertThat(current.initialRequestId()).isEqualTo(original.requestId());
  }

  @Test
  void concurrentExactRetriesConvergeAndConcurrentDifferentInitialBindingsHaveOneWinner()
      throws Exception {
    Context exactContext = context();
    Seed exactSeed = seed(exactContext, UUID.randomUUID());
    InitialRequest exactRequest =
        request(exactSeed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);
    InitialBindingResult[] exactResults = runConcurrent(exactContext, exactRequest, exactRequest);
    assertSameResult(exactResults[0], exactResults[1]);
    assertThat(
            count(
                exactContext.dsl(),
                "account_initial_tenant_billing_owner_operations",
                exactSeed.tenantUuid()))
        .isEqualTo(1L);
    assertThat(count(exactContext.dsl(), "account_tenant_billing_owners", exactSeed.tenantUuid()))
        .isEqualTo(1L);

    Context conflictContext = context();
    Seed conflictSeed = seed(conflictContext, UUID.randomUUID());
    InitialRequest left =
        request(conflictSeed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);
    InitialRequest right =
        request(conflictSeed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<InitialBindingResult> first =
          executor.submit(() -> establishAfterBarrier(conflictContext, left, ready, start));
      Future<InitialBindingResult> second =
          executor.submit(() -> establishAfterBarrier(conflictContext, right, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      Future<InitialBindingResult> winner = first;
      Future<InitialBindingResult> loser = second;
      try {
        winner.get(15, TimeUnit.SECONDS);
      } catch (ExecutionException failure) {
        winner = second;
        loser = first;
        assertThat(failure.getCause()).isInstanceOf(OperationConflictException.class);
        winner.get(15, TimeUnit.SECONDS);
      }
      Future<InitialBindingResult> loserFuture = loser;
      assertThatThrownBy(() -> loserFuture.get(15, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(OperationConflictException.class);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    assertThat(
            count(
                conflictContext.dsl(),
                "account_initial_tenant_billing_owner_operations",
                conflictSeed.tenantUuid()))
        .isEqualTo(1L);
    assertThat(
            count(
                conflictContext.dsl(), "account_tenant_billing_owners", conflictSeed.tenantUuid()))
        .isEqualTo(1L);
  }

  @Test
  void immutableOperationSqlRejectsUpdateDeleteAndTruncate() {
    Context context = context();
    Seed seed = seed(context, UUID.randomUUID());
    InitialRequest request =
        request(seed.creatorEvidence(), UUID.randomUUID(), UUID.randomUUID(), 1);
    InitialBindingResult committed =
        context.transaction().execute(status -> context.repository().establishInitial(request));

    assertAppendOnlyRejected(
        () ->
            context
                .setupDsl()
                .execute(
                    "UPDATE account_initial_tenant_billing_owner_operations "
                        + "SET result_digest = result_digest WHERE request_id = ?",
                    committed.requestId()));
    assertAppendOnlyRejected(
        () ->
            context
                .setupDsl()
                .execute(
                    "DELETE FROM account_initial_tenant_billing_owner_operations WHERE request_id = ?",
                    committed.requestId()));
    assertAppendOnlyRejected(
        () ->
            context
                .setupDsl()
                .execute("TRUNCATE account_initial_tenant_billing_owner_operations CASCADE"));

    InitialBindingResult retry =
        context.transaction().execute(status -> context.repository().establishInitial(request));
    assertSameResult(committed, retry);
  }

  private Context context() {
    String schema = "initial_billing_owner_" + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    DriverManagerDataSource source = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext setupDsl = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(source);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(
        setupDsl,
        transactionDsl,
        transactionManager,
        transaction,
        new AccountInitialTenantBillingOwnerRepository(transactionDsl));
  }

  private static Seed seed(Context context, UUID tenantUuid) {
    UUID accountUuid = insertAccount(context.setupDsl());
    FreshTenantCreationEvidence creation =
        freshTenant(tenantUuid, UUID.randomUUID(), UUID.randomUUID(), 42L);
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(context.dsl(), "prod");
    context.transaction().executeWithoutResult(status -> freshTenants.importVerified(creation));
    return new Seed(accountUuid, tenantUuid, creation, creatorEvidence(accountUuid, creation));
  }

  private static UUID insertAccount(DSLContext dsl) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash) VALUES (?, ?, ?) "
                    + "RETURNING account_uuid",
                "initial-owner-" + suffix,
                "initial-owner-" + suffix + "@example.test",
                "integration-fixture-hash"),
            "test Account insert returned no identity");
    return row.get("account_uuid", UUID.class);
  }

  private static FreshTenantCreationEvidence freshTenant(
      UUID tenantUuid, UUID creationRequestId, UUID creationOperationId, long sourceGameRowId) {
    String sourceTenantKey = "game-" + UUID.randomUUID().toString().substring(0, 8);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", creationRequestId, sourceTenantKey, "Synthetic tenant", "Test-only source");
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            creationRequestId,
            creationOperationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        creationRequestId,
        creationOperationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static FreshTenantCreatorEvidence creatorEvidence(
      UUID accountUuid, FreshTenantCreationEvidence creation) {
    UUID authorizationOperationId = UUID.randomUUID();
    String authorizationDigest = digest("synthetic preverified Account authority evidence");
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        accountUuid,
        authorizationOperationId,
        authorizationDigest,
        FreshTenantCreatorDigest.evidenceDigest(
            1, creation, accountUuid, authorizationOperationId, authorizationDigest));
  }

  private static InitialRequest request(
      FreshTenantCreatorEvidence evidence,
      UUID requestId,
      UUID consentOperationId,
      int consentStatementVersion) {
    return new InitialRequest(
        requestId, evidence, consentOperationId, consentStatementVersion, true);
  }

  private static void assertConflictWithoutMutation(
      Context context, InitialRequest request, UUID originalTenantUuid) {
    assertThatThrownBy(
            () ->
                context
                    .transaction()
                    .execute(status -> context.repository().establishInitial(request)))
        .isInstanceOf(OperationConflictException.class);
    assertThat(
            count(
                context.dsl(),
                "account_initial_tenant_billing_owner_operations",
                originalTenantUuid))
        .isEqualTo(1L);
    assertThat(count(context.dsl(), "account_tenant_billing_owners", originalTenantUuid))
        .isEqualTo(1L);
  }

  private static InitialBindingResult[] runConcurrent(
      Context context, InitialRequest left, InitialRequest right) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<InitialBindingResult> first =
          executor.submit(() -> establishAfterBarrier(context, left, ready, start));
      Future<InitialBindingResult> second =
          executor.submit(() -> establishAfterBarrier(context, right, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return new InitialBindingResult[] {
        first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
      };
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static InitialBindingResult establishAfterBarrier(
      Context context, InitialRequest request, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return context.transaction().execute(status -> context.repository().establishInitial(request));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrent billing-owner test was not released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent billing-owner test was interrupted", interrupted);
    }
  }

  private static long count(DSLContext dsl, String table, UUID tenantUuid) {
    Record row =
        Objects.requireNonNull(
            dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE tenant_uuid = ?", tenantUuid),
            "billing-owner row count query returned no row");
    return Objects.requireNonNull(row.get(0, Long.class), "billing-owner count was null");
  }

  private static void assertSameResult(InitialBindingResult expected, InitialBindingResult actual) {
    assertThat(actual.requestId()).isEqualTo(expected.requestId());
    assertThat(actual.tenantUuid()).isEqualTo(expected.tenantUuid());
    assertThat(actual.ownerAccountUuid()).isEqualTo(expected.ownerAccountUuid());
    assertThat(actual.ownerVersion()).isEqualTo(expected.ownerVersion());
    assertThat(actual.ownerGeneration()).isEqualTo(expected.ownerGeneration());
    assertThat(actual.consentOperationId()).isEqualTo(expected.consentOperationId());
    assertThat(actual.consentStatementVersion()).isEqualTo(expected.consentStatementVersion());
    assertThat(actual.consentPayload()).containsExactly(expected.consentPayload());
    assertThat(actual.consentDigest()).isEqualTo(expected.consentDigest());
    assertThat(actual.requestPayload()).containsExactly(expected.requestPayload());
    assertThat(actual.requestDigest()).isEqualTo(expected.requestDigest());
    assertThat(actual.resultPayload()).containsExactly(expected.resultPayload());
    assertThat(actual.resultDigest()).isEqualTo(expected.resultDigest());
    assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
  }

  private static void assertAppendOnlyRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              SQLException sqlFailure = findSqlException(failure);
              assertThat(sqlFailure.getSQLState()).isEqualTo("23514");
              assertThat(sqlFailure.getMessage())
                  .contains("Initial billing-owner operation evidence is append-only");
            });
  }

  private static SQLException findSqlException(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        return sqlException;
      }
    }
    throw new AssertionError("Expected PostgreSQL append-only rejection", failure);
  }

  private static String digest(String value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record Context(
      DSLContext setupDsl,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      TransactionTemplate transaction,
      AccountInitialTenantBillingOwnerRepository repository) {}

  private record Seed(
      UUID accountUuid,
      UUID tenantUuid,
      FreshTenantCreationEvidence creationEvidence,
      FreshTenantCreatorEvidence creatorEvidence) {}
}
