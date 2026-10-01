package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
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
  void compositeSnapshotBlocksIssuerAndTenantAdvanceAndKeepsScopeSetsIndependent()
      throws Exception {
    DatabaseFixture fixture = databaseFixture();
    DSLContext setupDsl = fixture.setupDsl();
    AccountAuthorityGenerationRepository repository = fixture.repository();
    TransactionTemplate transaction = fixture.transaction();

    UUID accountId = insertAccount(setupDsl, "auth-comp-" + UUID.randomUUID());
    AuthorityScope issuerScope = AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER);
    AuthorityScope accountScope = AuthorityScope.account(accountId);
    AuthorityScope tenantScopeA = AuthorityScope.tenant(TENANT_A);
    AuthorityScope tenantScopeB = AuthorityScope.tenant(TENANT_B);
    AuthorityScope membershipScopeA = AuthorityScope.membership(accountId, TENANT_A);

    inTransaction(transaction, () -> repository.initializeIssuerIfAbsent(issuerScope.issuerId()));
    inTransaction(transaction, () -> repository.initialize(accountScope));
    inTransaction(transaction, () -> repository.initialize(tenantScopeA));
    inTransaction(transaction, () -> repository.initialize(tenantScopeB));
    inTransaction(transaction, () -> repository.initialize(membershipScopeA));

    CompositeSnapshot billingSafeSnapshot =
        readCompositeSnapshot(
            fixture, issuerScope.issuerId(), accountId, List.of(), List.of(TENANT_A));
    assertThat(billingSafeSnapshot.tenants()).isEmpty();
    assertThat(billingSafeSnapshot.memberships())
        .extracting(ScopeState::scope)
        .containsExactly(membershipScopeA);
    assertThat(billingSafeSnapshot.memberships().getFirst().generation()).isEqualTo(1L);
    assertThat(billingSafeSnapshot.memberships().getFirst().sourceVersion()).isEqualTo(1L);

    AuthorityScope missingMembershipScope = AuthorityScope.membership(accountId, TENANT_B);
    assertThat(membershipGenerationCount(setupDsl, missingMembershipScope)).isZero();
    assertThatThrownBy(
            () ->
                readCompositeSnapshot(
                    fixture, issuerScope.issuerId(), accountId, List.of(), List.of(TENANT_B)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");
    assertThat(membershipGenerationCount(setupDsl, missingMembershipScope)).isZero();
    assertIssuanceFenceUnchanged(setupDsl, accountId, billingSafeSnapshot.issuanceFence());

    CompositeSnapshot exactBaseline =
        readCompositeSnapshot(
            fixture,
            issuerScope.issuerId(),
            accountId,
            List.of(TENANT_B, TENANT_A),
            List.of(TENANT_A));
    CompositeSnapshot afterIssuerAdvance =
        proveCompositeSnapshotBlocksAdvance(fixture, exactBaseline, exactBaseline.issuer());
    ScopeState tenantA =
        afterIssuerAdvance.tenants().stream()
            .filter(state -> state.scope().equals(tenantScopeA))
            .findFirst()
            .orElseThrow();
    CompositeSnapshot afterTenantAdvance =
        proveCompositeSnapshotBlocksAdvance(fixture, afterIssuerAdvance, tenantA);

    assertThat(afterTenantAdvance.issuer().generation())
        .isEqualTo(exactBaseline.issuer().generation() + 1L);
    assertThat(afterTenantAdvance.issuer().sourceVersion())
        .isEqualTo(exactBaseline.issuer().sourceVersion() + 1L);
    assertThat(afterTenantAdvance.tenants())
        .filteredOn(state -> state.scope().equals(tenantScopeA))
        .singleElement()
        .satisfies(
            state -> {
              assertThat(state.generation()).isEqualTo(tenantA.generation() + 1L);
              assertThat(state.sourceVersion()).isEqualTo(tenantA.sourceVersion() + 1L);
            });
    assertThat(afterTenantAdvance.tenants())
        .filteredOn(state -> state.scope().equals(tenantScopeB))
        .containsExactly(
            exactBaseline.tenants().stream()
                .filter(state -> state.scope().equals(tenantScopeB))
                .findFirst()
                .orElseThrow());
    assertThat(afterTenantAdvance.account()).isEqualTo(exactBaseline.account());
    assertThat(afterTenantAdvance.memberships()).isEqualTo(exactBaseline.memberships());
    assertThat(afterTenantAdvance.issuanceFence()).isEqualTo(exactBaseline.issuanceFence());
    assertIssuanceFenceUnchanged(setupDsl, accountId, exactBaseline.issuanceFence());
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
    UUID accountId = insertAccount(setupDsl, "auth-overflow-" + UUID.randomUUID());
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

  private DatabaseFixture databaseFixture() {
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
    return new DatabaseFixture(
        setupDsl,
        transactionDsl,
        new AccountAuthorityGenerationRepository(transactionDsl),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private CompositeSnapshot readCompositeSnapshot(
      DatabaseFixture fixture,
      String issuerId,
      UUID accountId,
      List<UUID> tenantIds,
      List<UUID> membershipTenantIds) {
    return inTransaction(
        fixture.transaction(),
        () ->
            fixture
                .repository()
                .readCompositeSnapshot(issuerId, accountId, tenantIds, membershipTenantIds));
  }

  private CompositeSnapshot proveCompositeSnapshotBlocksAdvance(
      DatabaseFixture fixture, CompositeSnapshot expectedSnapshot, ScopeState expectedAdvance)
      throws Exception {
    CountDownLatch snapshotHeld = new CountDownLatch(1);
    CountDownLatch releaseSnapshot = new CountDownLatch(1);
    CountDownLatch advanceStarted = new CountDownLatch(1);
    AtomicInteger snapshotBackendPid = new AtomicInteger(-1);
    AtomicInteger advanceBackendPid = new AtomicInteger(-1);
    AtomicReference<CompositeSnapshot> heldSnapshot = new AtomicReference<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      var snapshotTransaction =
          executor.submit(
              () ->
                  inTransaction(
                      fixture.transaction(),
                      () -> {
                        CompositeSnapshot snapshot =
                            fixture
                                .repository()
                                .readCompositeSnapshot(
                                    expectedSnapshot.issuer().scope().issuerId(),
                                    expectedSnapshot.account().scope().accountId(),
                                    expectedSnapshot.tenants().stream()
                                        .map(state -> state.scope().tenantId())
                                        .toList(),
                                    expectedSnapshot.memberships().stream()
                                        .map(state -> state.scope().tenantId())
                                        .toList());
                        heldSnapshot.set(snapshot);
                        Integer backendPid =
                            fixture
                                .transactionDsl()
                                .resultQuery("SELECT pg_backend_pid()")
                                .fetchOne(0, Integer.class);
                        snapshotBackendPid.set(
                            Objects.requireNonNull(
                                backendPid, "Expected PostgreSQL backend for held snapshot"));
                        snapshotHeld.countDown();
                        awaitBounded(releaseSnapshot);
                        return snapshot;
                      }));

      assertThat(snapshotHeld.await(20, TimeUnit.SECONDS)).isTrue();
      assertThat(heldSnapshot.get()).isEqualTo(expectedSnapshot);

      var advanceTransaction =
          executor.submit(
              () ->
                  inTransaction(
                      fixture.transaction(),
                      () -> {
                        Integer backendPid =
                            fixture
                                .transactionDsl()
                                .resultQuery("SELECT pg_backend_pid()")
                                .fetchOne(0, Integer.class);
                        advanceBackendPid.set(
                            Objects.requireNonNull(
                                backendPid, "Expected PostgreSQL backend for authority advance"));
                        advanceStarted.countDown();
                        return fixture
                            .repository()
                            .advance(expectedAdvance, expectedAdvance.issuanceFence());
                      }));

      assertThat(advanceStarted.await(20, TimeUnit.SECONDS)).isTrue();
      int snapshotPid = snapshotBackendPid.get();
      int backendPid = advanceBackendPid.get();
      awaitBlockedByBackend(fixture.setupDsl(), snapshotPid, backendPid);
      assertThat(advanceTransaction.isDone()).isFalse();
      assertPersistedGeneration(fixture.setupDsl(), expectedAdvance);
      assertIssuanceFenceUnchanged(
          fixture.setupDsl(),
          expectedSnapshot.account().scope().accountId(),
          expectedSnapshot.issuanceFence());

      releaseSnapshot.countDown();
      CompositeSnapshot completedHeldSnapshot = snapshotTransaction.get(20, TimeUnit.SECONDS);
      ScopeState advanced = advanceTransaction.get(20, TimeUnit.SECONDS);
      assertThat(completedHeldSnapshot).isEqualTo(expectedSnapshot);
      assertThat(advanced.generation()).isEqualTo(expectedAdvance.generation() + 1L);
      assertThat(advanced.sourceVersion()).isEqualTo(expectedAdvance.sourceVersion() + 1L);
      assertThat(advanced.issuanceFence()).isNull();
      assertPersistedGeneration(fixture.setupDsl(), advanced);

      CompositeSnapshot afterRelease =
          readCompositeSnapshot(
              fixture,
              expectedSnapshot.issuer().scope().issuerId(),
              expectedSnapshot.account().scope().accountId(),
              expectedSnapshot.tenants().stream().map(state -> state.scope().tenantId()).toList(),
              expectedSnapshot.memberships().stream()
                  .map(state -> state.scope().tenantId())
                  .toList());
      ScopeState expectedIssuer =
          expectedAdvance.scope().kind() == ScopeKind.ISSUER ? advanced : expectedSnapshot.issuer();
      List<ScopeState> expectedTenants =
          expectedSnapshot.tenants().stream()
              .map(state -> state.scope().equals(expectedAdvance.scope()) ? advanced : state)
              .toList();
      assertThat(afterRelease.issuer()).isEqualTo(expectedIssuer);
      assertThat(afterRelease.account()).isEqualTo(expectedSnapshot.account());
      assertThat(afterRelease.tenants()).containsExactlyElementsOf(expectedTenants);
      assertThat(afterRelease.memberships()).isEqualTo(expectedSnapshot.memberships());
      assertThat(afterRelease.issuanceFence()).isEqualTo(expectedSnapshot.issuanceFence());
      assertIssuanceFenceUnchanged(
          fixture.setupDsl(),
          expectedSnapshot.account().scope().accountId(),
          expectedSnapshot.issuanceFence());
      return afterRelease;
    } finally {
      releaseSnapshot.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void awaitBlockedByBackend(
      DSLContext setupDsl, int snapshotBackendPid, int advanceBackendPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      Boolean blockedBySnapshot =
          setupDsl
              .resultQuery(
                  "SELECT ? = ANY(pg_blocking_pids(?))", snapshotBackendPid, advanceBackendPid)
              .fetchOne(0, Boolean.class);
      if (Boolean.TRUE.equals(blockedBySnapshot)) {
        return;
      }
      Thread.sleep(10L);
    }
    throw new AssertionError(
        "Authority advance was not blocked by held composite snapshot backend "
            + snapshotBackendPid
            + "; advance backend: "
            + advanceBackendPid);
  }

  private void assertPersistedGeneration(DSLContext setupDsl, ScopeState expected) {
    var row =
        Objects.requireNonNull(
            setupDsl
                .resultQuery(
                    "SELECT generation, source_version FROM account_authority_generations "
                        + "WHERE scope_kind = ? AND issuer_id IS NOT DISTINCT FROM ? "
                        + "AND account_uuid IS NOT DISTINCT FROM ? "
                        + "AND tenant_uuid IS NOT DISTINCT FROM ?",
                    expected.scope().kind().name(),
                    expected.scope().issuerId(),
                    expected.scope().accountId(),
                    expected.scope().tenantId())
                .fetchOne(),
            "Expected persisted authority-generation row for " + expected.scope());
    assertThat(row.get("generation", Long.class)).isEqualTo(expected.generation());
    assertThat(row.get("source_version", Long.class)).isEqualTo(expected.sourceVersion());
  }

  private void assertIssuanceFenceUnchanged(
      DSLContext setupDsl, UUID accountId, IssuanceFence expected) {
    Long persistedFence =
        Objects.requireNonNull(
            setupDsl
                .resultQuery(
                    "SELECT issuance_fence FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    accountId)
                .fetchOne(0, Long.class),
            "Expected persisted Account issuance fence");
    Long persistedSourceVersion =
        Objects.requireNonNull(
            setupDsl
                .resultQuery(
                    "SELECT source_version FROM account_authority_issuance_fences "
                        + "WHERE account_uuid = ?",
                    accountId)
                .fetchOne(0, Long.class),
            "Expected persisted Account issuance-fence source version");
    assertThat(persistedFence).isEqualTo(expected.value());
    assertThat(persistedSourceVersion).isEqualTo(expected.sourceVersion());
  }

  private Long membershipGenerationCount(DSLContext setupDsl, AuthorityScope membershipScope) {
    return Objects.requireNonNull(
        setupDsl
            .resultQuery(
                "SELECT count(*) FROM account_authority_generations "
                    + "WHERE scope_kind = 'MEMBERSHIP' AND account_uuid = ? AND tenant_uuid = ?",
                membershipScope.accountId(),
                membershipScope.tenantId())
            .fetchOne(0, Long.class),
        "Expected membership-generation row count");
  }

  private void awaitBounded(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Composite authority snapshot release was not signaled");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Composite authority snapshot was interrupted", interrupted);
    }
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

  private record DatabaseFixture(
      DSLContext setupDsl,
      DSLContext transactionDsl,
      AccountAuthorityGenerationRepository repository,
      TransactionTemplate transaction) {}
}
