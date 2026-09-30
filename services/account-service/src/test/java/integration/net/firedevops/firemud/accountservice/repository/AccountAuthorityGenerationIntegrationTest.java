package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
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
  private static final String SCHEMA_PREFIX = "authority_generation_proof";
  private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void explicitExactScopeStateUsesCompareAndAdvanceAndFailsClosed() throws Exception {
    String schema = uniqueSchema();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
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

    AuthorityScope issuerScope = AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
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

    proveConcurrentIssuerEnrollment(repository, transaction, issuerScope.issuerId());
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT count(*) FROM account_authority_generations "
                        + "WHERE scope_kind = 'ISSUER' AND issuer_id = ?",
                    issuerScope.issuerId())
                .fetchOne(0, Long.class))
        .isEqualTo(1L);
    ScopeState enrolledIssuer = inTransaction(transaction, () -> repository.read(issuerScope));
    assertThat(enrolledIssuer.generation()).isEqualTo(1L);
    assertThat(enrolledIssuer.sourceVersion()).isEqualTo(1L);
    ScopeState advancedIssuer =
        inTransaction(transaction, () -> repository.advance(enrolledIssuer, null));
    assertThat(advancedIssuer.generation()).isEqualTo(2L);
    assertThat(advancedIssuer.sourceVersion()).isEqualTo(2L);
    assertThat(
            inTransaction(
                transaction, () -> repository.initializeIssuerIfAbsent(issuerScope.issuerId())))
        .isEqualTo(advancedIssuer);
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
                    issuerScope.issuerId(),
                    accountA,
                    List.of(TENANT_B, TENANT_A),
                    List.of(TENANT_A)));
    assertThat(snapshot.issuer().scope()).isEqualTo(issuerScope);
    assertThat(snapshot.account().scope()).isEqualTo(accountScopeA);
    assertThat(snapshot.tenants())
        .extracting(state -> state.scope().tenantId())
        .containsExactly(TENANT_A, TENANT_B);
    assertThat(snapshot.memberships())
        .extracting(state -> state.scope())
        .containsExactly(membershipAA);
    assertThat(snapshot.issuanceFence().value()).isEqualTo(1L);
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

  @Test
  void exhaustedAccountCountersRollbackTheWholeCallerTransaction() {
    String schema = uniqueSchema();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    AccountAuthorityGenerationRepository generationRepository =
        new AccountAuthorityGenerationRepository(transactionDsl);
    AccountAuthorityOutboxRepository outboxRepository =
        new AccountAuthorityOutboxRepository(transactionDsl);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    setupDsl.execute(
        "CREATE TABLE authority_overflow_sentinel "
            + "(sentinel_id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
    setupDsl.execute(
        "INSERT INTO authority_overflow_sentinel (sentinel_id, value) "
            + "VALUES (1, 0), (2, 0), (3, 0), (4, 0)");

    proveOverflowRollsBack(
        setupDsl,
        transactionDsl,
        generationRepository,
        outboxRepository,
        transaction,
        "generation",
        1,
        OverflowCounter.GENERATION);
    proveOverflowRollsBack(
        setupDsl,
        transactionDsl,
        generationRepository,
        outboxRepository,
        transaction,
        "source-version",
        2,
        OverflowCounter.SOURCE_VERSION);
    proveOverflowRollsBack(
        setupDsl,
        transactionDsl,
        generationRepository,
        outboxRepository,
        transaction,
        "issuance-fence",
        3,
        OverflowCounter.ISSUANCE_FENCE);
    proveOverflowRollsBack(
        setupDsl,
        transactionDsl,
        generationRepository,
        outboxRepository,
        transaction,
        "issuance-fence-source-version",
        4,
        OverflowCounter.ISSUANCE_FENCE_SOURCE_VERSION);
  }

  private void proveOverflowRollsBack(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      AccountAuthorityGenerationRepository generationRepository,
      AccountAuthorityOutboxRepository outboxRepository,
      TransactionTemplate transaction,
      String caseName,
      int sentinelId,
      OverflowCounter overflowCounter) {
    UUID accountId = insertAccount(setupDsl, "authority-overflow-" + UUID.randomUUID());
    AuthorityScope scope = AuthorityScope.account(accountId);
    inTransaction(transaction, () -> generationRepository.initialize(scope));
    seedOverflowCounter(setupDsl, accountId, overflowCounter);

    ScopeState original = inTransaction(transaction, () -> generationRepository.read(scope));
    assertOverflowCounterWasSeeded(original, overflowCounter);
    String stream = "account:auth-authority:v1:account/" + accountId;
    String requestId = "overflow-sentinel-" + caseName;
    String eventId = "overflow-sentinel-event-" + caseName;

    assertThatThrownBy(
            () ->
                inTransaction(
                    transaction,
                    () -> {
                      transactionDsl.execute(
                          "UPDATE authority_overflow_sentinel SET value = value + 1 "
                              + "WHERE sentinel_id = ?",
                          sentinelId);
                      outboxRepository.append(
                          stream,
                          requestId,
                          eventId,
                          "overflow-sentinel-digest-" + caseName,
                          new byte[] {1, 2, 3});
                      return generationRepository.advance(original, original.issuanceFence());
                    }))
        .isInstanceOf(DataAccessException.class);

    ScopeState after = inTransaction(transaction, () -> generationRepository.read(scope));
    assertThat(after).isEqualTo(original);
    var generationRow =
        setupDsl
            .resultQuery(
                "SELECT generation, source_version FROM account_authority_generations "
                    + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                accountId)
            .fetchOne();
    assertThat(generationRow.get("generation", Long.class)).isEqualTo(original.generation());
    assertThat(generationRow.get("source_version", Long.class)).isEqualTo(original.sourceVersion());
    var fenceRow =
        setupDsl
            .resultQuery(
                "SELECT issuance_fence, source_version FROM account_authority_issuance_fences "
                    + "WHERE account_uuid = ?",
                accountId)
            .fetchOne();
    assertThat(fenceRow.get("issuance_fence", Long.class))
        .isEqualTo(original.issuanceFence().value());
    assertThat(fenceRow.get("source_version", Long.class))
        .isEqualTo(original.issuanceFence().sourceVersion());
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT value FROM authority_overflow_sentinel WHERE sentinel_id = ?",
                    sentinelId)
                .fetchOne(0, Integer.class))
        .isZero();
    assertThat(inTransaction(transaction, () -> outboxRepository.readCheckpoint(stream))).isEmpty();
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_streams "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(
            setupDsl
                .resultQuery(
                    "SELECT count(*) FROM account_authority_outbox_events "
                        + "WHERE outbox_stream_key = ?",
                    stream)
                .fetchOne(0, Long.class))
        .isZero();
  }

  private void assertOverflowCounterWasSeeded(ScopeState state, OverflowCounter overflowCounter) {
    switch (overflowCounter) {
      case GENERATION -> {
        assertThat(state.generation()).isEqualTo(Long.MAX_VALUE);
        assertThat(state.sourceVersion()).isEqualTo(1L);
        assertThat(state.issuanceFence().value()).isEqualTo(1L);
        assertThat(state.issuanceFence().sourceVersion()).isEqualTo(1L);
      }
      case SOURCE_VERSION -> {
        assertThat(state.generation()).isEqualTo(1L);
        assertThat(state.sourceVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(state.issuanceFence().value()).isEqualTo(1L);
        assertThat(state.issuanceFence().sourceVersion()).isEqualTo(1L);
      }
      case ISSUANCE_FENCE -> {
        assertThat(state.generation()).isEqualTo(1L);
        assertThat(state.sourceVersion()).isEqualTo(1L);
        assertThat(state.issuanceFence().value()).isEqualTo(Long.MAX_VALUE);
        assertThat(state.issuanceFence().sourceVersion()).isEqualTo(1L);
      }
      case ISSUANCE_FENCE_SOURCE_VERSION -> {
        assertThat(state.generation()).isEqualTo(1L);
        assertThat(state.sourceVersion()).isEqualTo(1L);
        assertThat(state.issuanceFence().value()).isEqualTo(1L);
        assertThat(state.issuanceFence().sourceVersion()).isEqualTo(Long.MAX_VALUE);
      }
    }
  }

  private void seedOverflowCounter(
      DSLContext setupDsl, UUID accountId, OverflowCounter overflowCounter) {
    // Seed a valid positive maximum directly; only the monotonic update guard is bypassed here.
    switch (overflowCounter) {
      case GENERATION -> {
        setupDsl.execute(
            "ALTER TABLE account_authority_generations "
                + "DISABLE TRIGGER account_authority_generations_monotonic");
        try {
          setupDsl.execute(
              "UPDATE account_authority_generations SET generation = ? "
                  + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
              Long.MAX_VALUE,
              accountId);
        } finally {
          setupDsl.execute(
              "ALTER TABLE account_authority_generations "
                  + "ENABLE TRIGGER account_authority_generations_monotonic");
        }
      }
      case SOURCE_VERSION -> {
        setupDsl.execute(
            "ALTER TABLE account_authority_generations "
                + "DISABLE TRIGGER account_authority_generations_monotonic");
        try {
          setupDsl.execute(
              "UPDATE account_authority_generations SET source_version = ? "
                  + "WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
              Long.MAX_VALUE,
              accountId);
        } finally {
          setupDsl.execute(
              "ALTER TABLE account_authority_generations "
                  + "ENABLE TRIGGER account_authority_generations_monotonic");
        }
      }
      case ISSUANCE_FENCE -> {
        setupDsl.execute(
            "ALTER TABLE account_authority_issuance_fences "
                + "DISABLE TRIGGER account_authority_issuance_fences_monotonic");
        try {
          setupDsl.execute(
              "UPDATE account_authority_issuance_fences SET issuance_fence = ? "
                  + "WHERE account_uuid = ?",
              Long.MAX_VALUE,
              accountId);
        } finally {
          setupDsl.execute(
              "ALTER TABLE account_authority_issuance_fences "
                  + "ENABLE TRIGGER account_authority_issuance_fences_monotonic");
        }
      }
      case ISSUANCE_FENCE_SOURCE_VERSION -> {
        setupDsl.execute(
            "ALTER TABLE account_authority_issuance_fences "
                + "DISABLE TRIGGER account_authority_issuance_fences_monotonic");
        try {
          setupDsl.execute(
              "UPDATE account_authority_issuance_fences SET source_version = ? "
                  + "WHERE account_uuid = ?",
              Long.MAX_VALUE,
              accountId);
        } finally {
          setupDsl.execute(
              "ALTER TABLE account_authority_issuance_fences "
                  + "ENABLE TRIGGER account_authority_issuance_fences_monotonic");
        }
      }
    }
  }

  private String uniqueSchema() {
    return SCHEMA_PREFIX + "_" + UUID.randomUUID().toString().replace("-", "");
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

  private void proveConcurrentIssuerEnrollment(
      AccountAuthorityGenerationRepository repository,
      TransactionTemplate transaction,
      String issuerId)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          executor.submit(
              () -> concurrentIssuerEnrollment(repository, transaction, issuerId, ready, start));
      var second =
          executor.submit(
              () -> concurrentIssuerEnrollment(repository, transaction, issuerId, ready, start));
      ready.await();
      start.countDown();
      List<ScopeState> outcomes = List.of(first.get(), second.get());
      ScopeState initial = new ScopeState(AuthorityScope.issuer(issuerId), 1L, 1L, null);
      assertThat(outcomes).containsExactly(initial, initial);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private ScopeState concurrentIssuerEnrollment(
      AccountAuthorityGenerationRepository repository,
      TransactionTemplate transaction,
      String issuerId,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    await(start);
    return inTransaction(transaction, () -> repository.initializeIssuerIfAbsent(issuerId));
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

  private enum OverflowCounter {
    GENERATION,
    SOURCE_VERSION,
    ISSUANCE_FENCE,
    ISSUANCE_FENCE_SOURCE_VERSION
  }
}
