package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameplayCommandRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private GameplayCommandRepository repository;

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
    repository = new GameplayCommandRepository(dsl);
  }

  @BeforeEach
  void cleanTable() {
    dsl.execute("TRUNCATE TABLE gameplay_command RESTART IDENTITY CASCADE");
  }

  @Test
  void saveRoundTripsExecutionHook() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-1");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(11L);
    command.setAccountId(13L);
    command.setCharacterId(17L);
    command.setCommandName("wave");
    command.setCommandText("wave");
    command.setSanitizedCommandText("wave");
    command.setRequiresSoloTick(false);
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(0);
    command.setSourceType("PLAYER");
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    command.setExecutionHook("runtime.workflow.wave");

    GameplayCommand saved = repository.save(command);

    assertThat(saved.getExecutionHook()).isEqualTo("runtime.workflow.wave");
    assertThat(repository.findByCommandId("cmd-1"))
        .get()
        .extracting(GameplayCommand::getExecutionHook)
        .isEqualTo("runtime.workflow.wave");
  }
}
