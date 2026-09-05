package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
class GameplayCommandRepositoryTest {
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
    dsl.deleteFrom(GAMEPLAY_COMMAND).execute();
  }

  @Test
  void saveRoundTripsCompleteScriptPinTuple() {
    GameplayCommand command = command("script-command", "AUTOMATION");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(7L);
    command.setScriptPinControlPlaneRequestId("pin-request-7");

    GameplayCommand saved = repository.save(command);

    assertThat(saved)
        .extracting(
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly("patch-1", 7L, "pin-request-7");

    saved.setScriptPatchVersion("patch-2");
    saved.setScriptPinEpoch(8L);
    saved.setScriptPinControlPlaneRequestId("pin-request-8");
    GameplayCommand updated = repository.save(saved);

    assertThat(updated)
        .extracting(
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly("patch-2", 8L, "pin-request-8");
    assertThat(repository.findByCommandId("script-command"))
        .get()
        .extracting(
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly("patch-2", 8L, "pin-request-8");
  }

  @Test
  void saveRoundTripsAbsentScriptPinTupleForPlayerCommand() {
    GameplayCommand command = command("player-command", "PLAYER");

    GameplayCommand saved = repository.save(command);

    assertThat(saved)
        .extracting(
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly(null, null, null);
  }

  @Test
  void saveRoundTripsLegacyPatchOnlyRemoteAutomationCommand() {
    GameplayCommand command = command("remote-command", "AUTOMATION");
    command.setRemoteFollowupId("remote-followup-1");
    command.setScriptPatchVersion("legacy-patch");

    GameplayCommand saved = repository.save(command);

    assertThat(saved)
        .extracting(
            GameplayCommand::getRemoteFollowupId,
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly("remote-followup-1", "legacy-patch", null, null);
    assertThat(repository.findByCommandId("remote-command"))
        .get()
        .extracting(
            GameplayCommand::getRemoteFollowupId,
            GameplayCommand::getScriptPatchVersion,
            GameplayCommand::getScriptPinEpoch,
            GameplayCommand::getScriptPinControlPlaneRequestId)
        .containsExactly("remote-followup-1", "legacy-patch", null, null);
  }

  @Test
  void saveRejectsAutomationCommandWithPatchOnlyTuple() {
    GameplayCommand command = command("partial-script-command", "AUTOMATION");
    command.setScriptPatchVersion("patch-only");

    assertThatThrownBy(() -> repository.save(command))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_command_script_pin_tuple_coherent");
  }

  @Test
  void saveRejectsPlayerCommandWithCompletePinnedTuple() {
    GameplayCommand command = command("player-script-command", "PLAYER");
    command.setScriptPatchVersion("patch-1");
    command.setScriptPinEpoch(7L);
    command.setScriptPinControlPlaneRequestId("pin-request-7");

    assertThatThrownBy(() -> repository.save(command))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("gameplay_command_script_pin_tuple_coherent");
  }

  private static GameplayCommand command(String commandId, String sourceType) {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId(commandId);
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(0L);
    command.setCommandName("say");
    command.setCommandText("say hello");
    command.setSanitizedCommandText("say hello");
    command.setRequiresSoloTick(false);
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(1);
    command.setSourceType(sourceType);
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    command.setRegionId("region-1");
    command.setRegionEpoch(12L);
    command.setTargetEntityId("npc-1");
    return command;
  }
}
