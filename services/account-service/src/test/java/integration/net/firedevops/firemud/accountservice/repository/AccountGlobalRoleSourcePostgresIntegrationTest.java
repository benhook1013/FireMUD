package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for Account's fresh-only explicit-empty global-role source prerequisite. */
@Testcontainers(disabledWithoutDocker = true)
class AccountGlobalRoleSourcePostgresIntegrationTest {
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final String REPOSITORY_ACCOUNT = "global-role-source-repository";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void freshRepositoryAndDatabaseAccountsHaveStableIndependentExplicitEmptySources() {
    Fixture fixture = fixture(false);
    AccountRepository accountRepository = new AccountRepository(fixture.transactionDsl());
    AccountGlobalRoleSourceRepository sourceRepository = fixture.sourceRepository();

    Account repositoryAccount = new Account();
    repositoryAccount.setUsername(REPOSITORY_ACCOUNT);
    repositoryAccount.setEmail(REPOSITORY_ACCOUNT + "@example.test");
    repositoryAccount.setPasswordHash("hash");
    accountRepository.save(repositoryAccount);

    UUID databaseAccountUuid =
        insertAccount(fixture.setupDsl(), "global-role-source-database", null);

    assertThatThrownBy(
            () ->
                sourceRepository.readFreshEmptySourceForUpdate(repositoryAccount.getAccountUuid()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");

    AccountGlobalRoleSourceRepository.FreshEmptySource repositorySource =
        inTransaction(
            fixture.transaction(),
            () ->
                sourceRepository.readFreshEmptySourceForUpdate(repositoryAccount.getAccountUuid()));
    AccountGlobalRoleSourceRepository.FreshEmptySource databaseSource =
        inTransaction(
            fixture.transaction(),
            () -> sourceRepository.readFreshEmptySourceForUpdate(databaseAccountUuid));

    assertThat(repositorySource.accountUuid()).isEqualTo(repositoryAccount.getAccountUuid());
    assertThat(repositorySource.accountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    assertThat(databaseSource.accountUuid()).isEqualTo(databaseAccountUuid);
    assertThat(databaseSource.accountUuidProvenance())
        .isEqualTo(AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT);
    assertExplicitEmpty(repositorySource);
    assertExplicitEmpty(databaseSource);

    AccountGlobalRoleSourceRepository.FreshEmptySource repeatedRead =
        inTransaction(
            fixture.transaction(),
            () ->
                sourceRepository.readFreshEmptySourceForUpdate(repositoryAccount.getAccountUuid()));
    assertThat(repeatedRead).isEqualTo(repositorySource);
    assertThatThrownBy(() -> repositorySource.globalRoles().add("platformAdmin"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertSourceStoredAsExactEmptyArray(fixture.setupDsl(), repositoryAccount.getAccountUuid());
    assertThat(sourceCount(fixture.setupDsl())).isEqualTo(2L);

    AccountAuthorityGenerationRepository generations = fixture.generations();
    AuthorityScope accountScope = AuthorityScope.account(repositoryAccount.getAccountUuid());
    ScopeState initialGeneration =
        inTransaction(fixture.transaction(), () -> generations.initialize(accountScope));
    ScopeState advancedGeneration =
        inTransaction(
            fixture.transaction(),
            () -> generations.advance(initialGeneration, initialGeneration.issuanceFence()));
    assertThat(advancedGeneration.generation()).isEqualTo(initialGeneration.generation() + 1L);
    assertThat(advancedGeneration.sourceVersion())
        .isEqualTo(initialGeneration.sourceVersion() + 1L);
    assertThat(
            inTransaction(
                fixture.transaction(),
                () ->
                    sourceRepository.readFreshEmptySourceForUpdate(
                        repositoryAccount.getAccountUuid())))
        .isEqualTo(repositorySource);
    assertThat(globalRoleSourceVersion(fixture.setupDsl(), repositoryAccount.getAccountUuid()))
        .isEqualTo(1L);
    assertThat(sourceCount(fixture.setupDsl())).isEqualTo(2L);
  }

  @Test
  void v55DoesNotBackfillRetainedOrPreexistingFreshProvenanceRows() {
    Fixture fixture = fixture(true);
    UUID retainedUuid = UUID.randomUUID();
    UUID preexistingFreshUuid = UUID.randomUUID();
    insertPreexistingAccount(
        fixture.setupDsl(), "global-role-source-retained", retainedUuid, "ACCOUNT_V29_MIGRATION");
    insertPreexistingAccount(
        fixture.setupDsl(),
        "global-role-source-preexisting-fresh",
        preexistingFreshUuid,
        "ACCOUNT_DATABASE_INSERT");
    Record retainedBefore = accountIdentityRecord(fixture.setupDsl(), retainedUuid);
    Record freshBefore = accountIdentityRecord(fixture.setupDsl(), preexistingFreshUuid);

    migrateToLatest(fixture.dataSource(), fixture.schema());

    assertThat(sourceCount(fixture.setupDsl())).isZero();
    assertThat(accountIdentityRecord(fixture.setupDsl(), retainedUuid)).isEqualTo(retainedBefore);
    assertThat(accountIdentityRecord(fixture.setupDsl(), preexistingFreshUuid))
        .isEqualTo(freshBefore);
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () -> fixture.sourceRepository().readFreshEmptySourceForUpdate(retainedUuid)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh canonical Account identity readback is invalid");
    assertReaderDeniesMissingSource(fixture, preexistingFreshUuid);
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () ->
                        fixture
                            .sourceRepository()
                            .readFreshEmptySourceForUpdate(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Persisted Account row is missing");
  }

  @Test
  void nonnullLegacyPlayerAndCanonicalRoleScalarsDoNotInventSources() {
    Fixture fixture = fixture(false);
    List<String> scalarRoles = List.of("player", "platformAdmin", "support", "billingAdmin");

    for (int index = 0; index < scalarRoles.size(); index++) {
      insertAccount(
          fixture.setupDsl(), "global-role-source-scalar-" + index, scalarRoles.get(index));
    }

    assertThat(sourceCount(fixture.setupDsl())).isZero();
    assertThat(
            fixture
                .setupDsl()
                .resultQuery("SELECT COUNT(*) FROM accounts WHERE role IS NOT NULL")
                .fetchOne(0, Long.class))
        .isEqualTo((long) scalarRoles.size());
  }

  @Test
  void directSourceWritesAndWrongScopeEvidenceCannotManufactureOrEraseBirthState() {
    Fixture fixture = fixture(false);
    UUID accountWithSource =
        insertAccount(fixture.setupDsl(), "global-role-source-protected", null);
    UUID accountWithoutSource =
        insertAccount(fixture.setupDsl(), "global-role-source-no-birth", "player");
    Record sourceOwner = accountIdentityRecord(fixture.setupDsl(), accountWithSource);
    Record nonSourceOwner = accountIdentityRecord(fixture.setupDsl(), accountWithoutSource);

    assertThatThrownBy(
            () ->
                insertSource(
                    fixture.setupDsl(),
                    accountWithoutSource,
                    nonSourceOwner.get("account_uuid_source_numeric_id", Long.class),
                    nonSourceOwner.get("account_uuid_provenance", String.class),
                    List.of(),
                    1L))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Account global-role source may only be inserted by its fresh Account birth path");
    assertThatThrownBy(
            () ->
                insertSource(
                    fixture.setupDsl(),
                    accountWithSource,
                    sourceOwner.get("account_uuid_source_numeric_id", Long.class),
                    sourceOwner.get("account_uuid_provenance", String.class),
                    List.of("platformAdmin"),
                    1L))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Account global-role source may only be inserted by its fresh Account birth path");
    assertThatThrownBy(
            () ->
                insertSource(
                    fixture.setupDsl(),
                    accountWithSource,
                    nonSourceOwner.get("account_uuid_source_numeric_id", Long.class),
                    nonSourceOwner.get("account_uuid_provenance", String.class),
                    List.of(),
                    1L))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Account global-role source may only be inserted by its fresh Account birth path");
    assertThat(sourceCount(fixture.setupDsl())).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_global_role_sources "
                            + "SET account_uuid_source_numeric_id = "
                            + "account_uuid_source_numeric_id + 1 "
                            + "WHERE account_uuid = ?",
                        accountWithSource))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Fresh Account global-role source is immutable until a versioned writer exists");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_global_role_sources WHERE account_uuid = ?",
                        accountWithSource))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Fresh Account global-role source is immutable until a versioned writer exists");
    assertThat(sourceCount(fixture.setupDsl())).isEqualTo(1L);
    assertThatThrownBy(() -> fixture.setupDsl().execute("TRUNCATE account_global_role_sources"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("Fresh Account global-role source cannot be truncated");
    assertThat(sourceCount(fixture.setupDsl())).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE accounts SET role = 'support' WHERE account_uuid = ?",
                        accountWithSource))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Account role change requires a versioned global-role source writer");
    assertThat(globalRoleSourceVersion(fixture.setupDsl(), accountWithSource)).isEqualTo(1L);
    assertSourceStoredAsExactEmptyArray(fixture.setupDsl(), accountWithSource);
  }

  @Test
  void abortedFreshInsertLeavesNoAccountOrPartialSourceAndReadRequiresWritableTransaction() {
    Fixture fixture = fixture(false);
    AccountRepository accountRepository = new AccountRepository(fixture.transactionDsl());
    AccountGlobalRoleSourceRepository sourceRepository = fixture.sourceRepository();
    Account rolledBack = new Account();
    rolledBack.setUsername("global-role-source-rollback");
    rolledBack.setEmail("global-role-source-rollback@example.test");
    rolledBack.setPasswordHash("hash");

    assertThatThrownBy(
            () ->
                fixture
                    .transaction()
                    .executeWithoutResult(
                        status -> {
                          accountRepository.save(rolledBack);
                          throw new IllegalStateException("rollback fresh Account birth");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("rollback fresh Account birth");
    assertThat(
            fixture
                .setupDsl()
                .resultQuery(
                    "SELECT COUNT(*) FROM accounts WHERE username = ?", rolledBack.getUsername())
                .fetchOne(0, Long.class))
        .isZero();
    assertThat(sourceCount(fixture.setupDsl())).isZero();

    Account committed = new Account();
    committed.setUsername("global-role-source-readonly");
    committed.setEmail("global-role-source-readonly@example.test");
    committed.setPasswordHash("hash");
    accountRepository.save(committed);
    assertThatThrownBy(
            () ->
                fixture
                    .readOnlyTransaction()
                    .execute(
                        status ->
                            sourceRepository.readFreshEmptySourceForUpdate(
                                committed.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account owner transaction");
    assertThatThrownBy(
            () ->
                fixture
                    .repeatableReadTransaction()
                    .execute(
                        status ->
                            sourceRepository.readFreshEmptySourceForUpdate(
                                committed.getAccountUuid())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("READ COMMITTED isolation");
    assertThat(
            inTransaction(
                fixture.transaction(),
                () -> sourceRepository.readFreshEmptySourceForUpdate(committed.getAccountUuid())))
        .satisfies(source -> assertExplicitEmpty(source));
  }

  private Fixture fixture(boolean stopAtV54) {
    String schema = "account_global_role_source_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
    dataSource.setUrl(postgres.getJdbcUrl() + separator + "currentSchema=" + schema);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    Flyway flyway = flyway(dataSource, schema, stopAtV54);
    flyway.migrate();
    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    TransactionTemplate readOnlyTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    readOnlyTransaction.setReadOnly(true);
    TransactionTemplate repeatableReadTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repeatableReadTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return new Fixture(
        schema,
        dataSource,
        setupDsl,
        transactionDsl,
        transaction,
        readOnlyTransaction,
        repeatableReadTransaction,
        new AccountGlobalRoleSourceRepository(transactionDsl, dataSource),
        new AccountAuthorityGenerationRepository(transactionDsl));
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, boolean stopAtV54) {
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(MIGRATION_LOCATION)
            .load();
    if (!stopAtV54) {
      return flyway;
    }
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("54"))
        .load();
  }

  private static void migrateToLatest(DriverManagerDataSource dataSource, String schema) {
    flyway(dataSource, schema, false).migrate();
  }

  private static UUID insertAccount(DSLContext dsl, String username, String scalarRole) {
    if (scalarRole == null) {
      return dsl.resultQuery(
              "INSERT INTO accounts (username, email, password_hash, role) "
                  + "VALUES (?, ?, ?, NULL) RETURNING account_uuid",
              username,
              username + "@example.test",
              "hash")
          .fetchOne(0, UUID.class);
    }
    return dsl.resultQuery(
            "INSERT INTO accounts (username, email, password_hash, role) "
                + "VALUES (?, ?, ?, ?) RETURNING account_uuid",
            username,
            username + "@example.test",
            "hash",
            scalarRole)
        .fetchOne(0, UUID.class);
  }

  private static void insertPreexistingAccount(
      DSLContext dsl, String username, UUID accountUuid, String provenance) {
    dsl.execute(
        "INSERT INTO accounts (username, email, password_hash, role, account_uuid, "
            + "account_uuid_provenance) VALUES (?, ?, ?, NULL, ?, ?)",
        username,
        username + "@example.test",
        "hash",
        accountUuid,
        provenance);
  }

  private static void insertSource(
      DSLContext dsl,
      UUID accountUuid,
      Long sourceNumericId,
      String provenance,
      List<String> globalRoles,
      long sourceVersion) {
    dsl.execute(
        "INSERT INTO account_global_role_sources (account_uuid, account_uuid_source_numeric_id, "
            + "account_uuid_provenance, global_roles, global_role_source_version) "
            + "VALUES (?, ?, ?, ?::TEXT[], ?)",
        accountUuid,
        sourceNumericId,
        provenance,
        globalRoles.toArray(String[]::new),
        sourceVersion);
  }

  private static Record accountIdentityRecord(DSLContext dsl, UUID accountUuid) {
    return Objects.requireNonNull(
        dsl.fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", accountUuid),
        "Expected exact Account identity row");
  }

  private static long sourceCount(DSLContext dsl) {
    return Objects.requireNonNull(
        dsl.resultQuery("SELECT COUNT(*) FROM account_global_role_sources")
            .fetchOne(0, Long.class));
  }

  private static long globalRoleSourceVersion(DSLContext dsl, UUID accountUuid) {
    return Objects.requireNonNull(
        dsl.resultQuery(
                "SELECT global_role_source_version FROM account_global_role_sources "
                    + "WHERE account_uuid = ?",
                accountUuid)
            .fetchOne(0, Long.class));
  }

  private static void assertSourceStoredAsExactEmptyArray(DSLContext dsl, UUID accountUuid) {
    Record source =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT cardinality(global_roles) AS role_count, global_roles = ARRAY[]::TEXT[] "
                    + "AS is_exact_empty, global_role_source_version "
                    + "FROM account_global_role_sources WHERE account_uuid = ?",
                accountUuid),
            "Expected durable fresh global-role source");
    assertThat(source.get("role_count", Integer.class)).isZero();
    assertThat(source.get("is_exact_empty", Boolean.class)).isTrue();
    assertThat(source.get("global_role_source_version", Long.class)).isEqualTo(1L);
  }

  private static void assertExplicitEmpty(
      AccountGlobalRoleSourceRepository.FreshEmptySource source) {
    assertThat(source.globalRoles()).isEmpty();
    assertThat(source.globalRoleSourceVersion()).isEqualTo(1L);
    assertThat(source.accountUuidSourceNumericId()).isEqualTo(source.accountRowId());
  }

  private static void assertReaderDeniesMissingSource(Fixture fixture, UUID accountUuid) {
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () -> fixture.sourceRepository().readFreshEmptySourceForUpdate(accountUuid)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Fresh Account global-role source is missing");
  }

  private static <T> T inTransaction(TransactionTemplate transaction, Supplier<T> operation) {
    return transaction.execute(status -> operation.get());
  }

  private record Fixture(
      String schema,
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext transactionDsl,
      TransactionTemplate transaction,
      TransactionTemplate readOnlyTransaction,
      TransactionTemplate repeatableReadTransaction,
      AccountGlobalRoleSourceRepository sourceRepository,
      AccountAuthorityGenerationRepository generations) {}
}
