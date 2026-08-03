package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRepositoryIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String MIGRATION_PROOF_SCHEMA = "account_migration_proof";
  private static final String COLLISION_MIGRATION_PROOF_SCHEMA =
      "account_migration_collision_proof";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DriverManagerDataSource dataSource;
  private DSLContext dsl;
  private AccountRepository repository;

  @BeforeAll
  void setUpRepository() {
    dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new AccountRepository(dsl);
  }

  @BeforeEach
  void cleanTables() {
    dsl.execute("TRUNCATE TABLE accounts RESTART IDENTITY CASCADE");
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountLifecycleState.class,
      names = {"SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED"})
  void genericUpdatePreservesProtectedLifecycleState(AccountLifecycleState lifecycleState) {
    Account persisted = account("original", "original@example.com", lifecycleState);
    Account saved = repository.save(persisted);

    Account staleUpdate = account("updated", "updated@example.com", AccountLifecycleState.ACTIVE);
    staleUpdate.setId(saved.getId());
    repository.save(staleUpdate);

    Account loaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getUsername()).isEqualTo("updated");
    assertThat(loaded.getLifecycleState()).isEqualTo(lifecycleState);
  }

  @Test
  void saveCanonicalizesEmail() {
    Account saved =
        repository.save(
            account("canonical", "  Player@Example.COM ", AccountLifecycleState.ACTIVE));

    assertThat(saved.getEmail()).isEqualTo("player@example.com");
    assertThat(repository.findByEmail("  PLAYER@EXAMPLE.COM ")).isPresent();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void saveRejectsMissingEmailBeforeStorage(String email) {
    assertThatThrownBy(
            () -> repository.save(account("missing-email", email, AccountLifecycleState.ACTIVE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flywayCanonicalizesLegacyEmailAndRejectsNonCanonicalValues() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(MIGRATION_PROOF_SCHEMA)
        .defaultSchema(MIGRATION_PROOF_SCHEMA)
        .target("21")
        .load()
        .migrate();

    dsl.execute(
        "INSERT INTO "
            + MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('legacy-migration', ' Legacy@Example.COM ', 'hash')");

    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(MIGRATION_PROOF_SCHEMA)
        .defaultSchema(MIGRATION_PROOF_SCHEMA)
        .load()
        .migrate();

    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'legacy-migration'"))
        .isEqualTo("legacy@example.com");
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO "
                        + MIGRATION_PROOF_SCHEMA
                        + ".accounts (username, email, password_hash) "
                        + "VALUES ('noncanonical', ' Another@Example.COM ', 'hash')"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void flywayRejectsCanonicalEmailCollisionsBeforeRewriting() {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(COLLISION_MIGRATION_PROOF_SCHEMA)
        .defaultSchema(COLLISION_MIGRATION_PROOF_SCHEMA)
        .target("21")
        .load()
        .migrate();

    dsl.execute(
        "INSERT INTO "
            + COLLISION_MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('collision-first', 'Player@Example.COM', 'hash')");
    dsl.execute(
        "INSERT INTO "
            + COLLISION_MIGRATION_PROOF_SCHEMA
            + ".accounts (username, email, password_hash) "
            + "VALUES ('collision-second', ' player@example.com ', 'hash')");

    assertThatThrownBy(
            () ->
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations(MIGRATION_LOCATION)
                    .schemas(COLLISION_MIGRATION_PROOF_SCHEMA)
                    .defaultSchema(COLLISION_MIGRATION_PROOF_SCHEMA)
                    .load()
                    .migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("accounts_email_canonicalization_collision");

    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + COLLISION_MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'collision-first'"))
        .isEqualTo("Player@Example.COM");
    assertThat(
            dsl.fetchValue(
                "SELECT email FROM "
                    + COLLISION_MIGRATION_PROOF_SCHEMA
                    + ".accounts WHERE username = 'collision-second'"))
        .isEqualTo(" player@example.com ");
  }

  private Account account(String username, String email, AccountLifecycleState lifecycleState) {
    Account account = new Account();
    account.setUsername(username);
    account.setEmail(email);
    account.setPasswordHash("hash");
    account.setRole("player");
    account.setLoginAuthModes("PASSWORD");
    account.setLifecycleState(lifecycleState);
    return account;
  }
}
