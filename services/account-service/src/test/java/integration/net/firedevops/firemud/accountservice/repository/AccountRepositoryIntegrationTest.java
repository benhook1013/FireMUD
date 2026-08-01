package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private AccountRepository repository;

  @BeforeAll
  void setUpRepository() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

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
    assertThat(repository.findByEmail("player@example.com")).isPresent();
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
