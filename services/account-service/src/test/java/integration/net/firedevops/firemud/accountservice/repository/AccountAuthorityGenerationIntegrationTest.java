package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountAuthorityGenerationIntegrationTest {
  private static final String SCHEMA = "account_authority_generation_proof";
  private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void explicitExactScopeStateUsesCompareAndAdvanceAndFailsClosed() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + SCHEMA);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    UUID accountA = insertAccount(setupDsl, "authority-owner-a");
    UUID accountB = insertAccount(setupDsl, "authority-owner-b");
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountAuthorityGenerationRepository repository =
        new AccountAuthorityGenerationRepository(transactionDsl);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    AuthorityScope issuerScope = AuthorityScope.issuer("Issuer-A");
    AuthorityScope lowerIssuerScope = AuthorityScope.issuer("issuer-a");
    AuthorityScope accountScopeA = AuthorityScope.account(accountA);
    AuthorityScope accountScopeB = AuthorityScope.account(accountB);
    AuthorityScope tenantScopeA = AuthorityScope.tenant(TENANT_A);
    AuthorityScope tenantScopeB = AuthorityScope.tenant(TENANT_B);
    AuthorityScope membershipAA = AuthorityScope.membership(accountA, TENANT_A);
    AuthorityScope membershipAB = AuthorityScope.membership(accountA, TENANT_B);
    AuthorityScope membershipBA = AuthorityScope.membership(accountB, TENANT_A);

    assertThatThrownBy(() -> inTransaction(transaction, () -> repository.read(tenantScopeA)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT count(*) FROM account_authority_generations WHERE scope_kind = 'TENANT'")
                .fetchOne(0, Long.class))
        .isZero();

    assertThat(inTransaction(transaction, () -> repository.initialize(issuerScope)).generation())
        .isEqualTo(1L);
    inTransaction(transaction, () -> repository.initialize(lowerIssuerScope));
    inTransaction(transaction, () -> repository.initialize(accountScopeA));
    inTransaction(transaction, () -> repository.initialize(accountScopeB));
    assertThatThrownBy(() -> inTransaction(transaction, () -> repository.initialize(membershipAA)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    accountA)
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    inTransaction(transaction, () -> repository.initialize(tenantScopeA));
    inTransaction(transaction, () -> repository.initialize(tenantScopeB));
    inTransaction(transaction, () -> repository.initialize(membershipAA));
    inTransaction(transaction, () -> repository.initialize(membershipAB));
    inTransaction(transaction, () -> repository.initialize(membershipBA));

    CompositeSnapshot snapshot =
        inTransaction(
            transaction,
            () ->
                repository.readCompositeSnapshot(
                    "Issuer-A", accountA, List.of(TENANT_B, TENANT_A), List.of(TENANT_A)));
    assertThat(snapshot.issuer().scope()).isEqualTo(issuerScope);
    assertThat(snapshot.account().scope()).isEqualTo(accountScopeA);
    assertThat(snapshot.tenants())
        .extracting(state -> state.scope().tenantId())
        .containsExactly(TENANT_A, TENANT_B);
    assertThat(snapshot.memberships())
        .extracting(state -> state.scope())
        .containsExactly(membershipAA);
    assertThat(snapshot.issuanceFence().value()).isEqualTo(3L);
    assertThat(
            inTransaction(transaction, () -> repository.read(lowerIssuerScope)).scope().issuerId())
        .isEqualTo("issuer-a");
    assertThat(inTransaction(transaction, () -> repository.read(membershipAB)).scope())
        .isEqualTo(membershipAB);
    assertThat(inTransaction(transaction, () -> repository.read(membershipBA)).scope())
        .isEqualTo(membershipBA);

    ScopeState tenantExpected = inTransaction(transaction, () -> repository.read(tenantScopeA));
    ScopeState tenantAdvanced =
        inTransaction(transaction, () -> repository.advance(tenantExpected, null));
    assertThat(tenantAdvanced.generation()).isEqualTo(2L);
    assertThat(tenantAdvanced.sourceVersion()).isEqualTo(2L);
    assertThatThrownBy(
            () -> inTransaction(transaction, () -> repository.advance(tenantExpected, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Stale");

    ScopeState membershipExpected = inTransaction(transaction, () -> repository.read(membershipAA));
    IssuanceFence staleFence = membershipExpected.issuanceFence();
    ScopeState membershipAdvanced =
        inTransaction(transaction, () -> repository.advance(membershipExpected, staleFence));
    assertThat(membershipAdvanced.generation()).isEqualTo(2L);
    assertThat(membershipAdvanced.sourceVersion()).isEqualTo(2L);
    assertThat(membershipAdvanced.issuanceFence().value()).isEqualTo(staleFence.value() + 1L);
    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction, () -> repository.advance(membershipExpected, staleFence)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fence");

    proveConcurrentStaleMembershipAdvance(
        repository, transaction, membershipAdvanced, membershipAdvanced.issuanceFence());

    assertThatThrownBy(
            () ->
                setupDsl.execute(
                    "INSERT INTO account_authority_generations "
                        + "(scope_kind, tenant_uuid, generation, source_version) "
                        + "VALUES ('TENANT', ?, 0, 1)",
                    UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")))
        .isInstanceOf(DataAccessException.class);

    AuthorityScope corruptScope =
        AuthorityScope.tenant(UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
    inTransaction(transaction, () -> repository.initialize(corruptScope));
    setupDsl.execute(
        "ALTER TABLE account_authority_generations "
            + "DROP CONSTRAINT account_authority_generations_positive_check");
    setupDsl.execute(
        "ALTER TABLE account_authority_generations "
            + "DISABLE TRIGGER account_authority_generations_monotonic");
    setupDsl.execute(
        "UPDATE account_authority_generations SET generation = 0, source_version = 0 "
            + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
        corruptScope.tenantId());
    setupDsl.execute(
        "ALTER TABLE account_authority_generations "
            + "ENABLE TRIGGER account_authority_generations_monotonic");
    assertThatThrownBy(() -> inTransaction(transaction, () -> repository.read(corruptScope)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed");
  }

  private void proveConcurrentStaleMembershipAdvance(
      AccountAuthorityGenerationRepository repository,
      TransactionTemplate transaction,
      ScopeState expectedState,
      IssuanceFence expectedFence)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          executor.submit(
              () ->
                  concurrentAdvance(
                      repository, transaction, expectedState, expectedFence, ready, start));
      var second =
          executor.submit(
              () ->
                  concurrentAdvance(
                      repository, transaction, expectedState, expectedFence, ready, start));
      ready.await();
      start.countDown();
      List<Boolean> outcomes = List.of(first.get(), second.get());
      assertThat(outcomes).containsExactlyInAnyOrder(true, false);
      ScopeState actual = inTransaction(transaction, () -> repository.read(expectedState.scope()));
      assertThat(actual.generation()).isEqualTo(expectedState.generation() + 1L);
      assertThat(actual.issuanceFence().value()).isEqualTo(expectedFence.value() + 1L);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private boolean concurrentAdvance(
      AccountAuthorityGenerationRepository repository,
      TransactionTemplate transaction,
      ScopeState expectedState,
      IssuanceFence expectedFence,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    try {
      inTransaction(transaction, () -> repository.advance(expectedState, expectedFence));
      return true;
    } catch (IllegalStateException stale) {
      return false;
    }
  }

  private UUID insertAccount(DSLContext dsl, String username) {
    return dsl.resultQuery(
            "INSERT INTO accounts (username, email, password_hash) "
                + "VALUES (?, ?, ?) RETURNING account_uuid",
            username,
            username + "@example.test",
            "hash")
        .fetchOne(0, UUID.class);
  }

  private <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Authority-generation concurrency proof was interrupted", interrupted);
    }
  }
}
