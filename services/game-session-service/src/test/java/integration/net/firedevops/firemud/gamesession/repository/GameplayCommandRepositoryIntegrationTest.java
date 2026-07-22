package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.TickEffect.TICK_EFFECT;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
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

  @Test
  void tenantAndGameQualifiedCommandLookupDoesNotCrossScope() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-qualified");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(11L);
    command.setCommandName("look");
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(0);
    command.setSourceType("PLAYER");
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    repository.save(command);

    assertThat(repository.findByTenantIdAndGameInstanceIdAndCommandId(1L, 7L, "cmd-qualified"))
        .isPresent();
    assertThat(repository.findByTenantIdAndGameInstanceIdAndCommandId(2L, 7L, "cmd-qualified"))
        .isEmpty();
    assertThat(repository.findByTenantIdAndGameInstanceIdAndCommandId(1L, 8L, "cmd-qualified"))
        .isEmpty();
  }

  @Test
  void updatePreservesAdmittedAuthoredActionSnapshot() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-authored-1");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(11L);
    command.setCommandName("wave-salute");
    command.setCommandText("salute captain");
    command.setSanitizedCommandText("salute captain");
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(1);
    command.setSourceType("PLAYER");
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    command.setAdmittedReleaseBundleId(300L);
    command.setAdmittedVersionId(41L);
    command.setDeclaredEffectsJson("[{\"effectKind\":\"APPLY_ACTION_STATE\"}]");

    GameplayCommand saved = repository.save(command);
    saved.setExecutionOutcome("STAGED");
    saved.setAdmittedReleaseBundleId(999L);
    saved.setAdmittedVersionId(998L);
    saved.setDeclaredEffectsJson("[]");
    repository.save(saved);

    assertThat(repository.findByCommandId("cmd-authored-1"))
        .get()
        .extracting(
            GameplayCommand::getExecutionOutcome,
            GameplayCommand::getAdmittedReleaseBundleId,
            GameplayCommand::getAdmittedVersionId,
            GameplayCommand::getDeclaredEffectsJson)
        .containsExactly("STAGED", 300L, 41L, "[{\"effectKind\":\"APPLY_ACTION_STATE\"}]");
  }

  @Test
  void stageTransitionIsConditionalAndDurableTickEffectLookupUsesStoredEvidence() {
    GameplayCommand command = new GameplayCommand();
    command.setCommandId("cmd-stage-1");
    command.setTenantId(1L);
    command.setGameInstanceId(7L);
    command.setSessionId(11L);
    command.setCommandName("look");
    command.setCommandText("look");
    command.setSanitizedCommandText("look");
    command.setExecutionOutcome("ACCEPTED");
    command.setGameplayResult("PENDING");
    command.setAcceptedAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(0);
    command.setSourceType("PLAYER");
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    repository.save(command);

    Instant stagedAt = Instant.parse("2026-07-05T06:01:00Z");
    assertThat(repository.markAcceptedCommandStaged("cmd-stage-1", stagedAt)).isTrue();
    assertThat(repository.markAcceptedCommandStaged("cmd-stage-1", stagedAt.plusSeconds(1)))
        .isFalse();
    assertThat(
            repository.markAcceptedCommandFailed(
                "cmd-stage-1", "QUEUE_UNAVAILABLE", "must not overwrite", stagedAt.plusSeconds(2)))
        .isFalse();
    assertThat(repository.findByCommandId("cmd-stage-1"))
        .get()
        .extracting(GameplayCommand::getExecutionOutcome, GameplayCommand::getStagedAt)
        .containsExactly("STAGED", stagedAt);

    assertThat(repository.hasDurableTickEffect("cmd-stage-1")).isFalse();
    dsl.insertInto(TICK_EFFECT)
        .set(TICK_EFFECT.EFFECT_ID, "effect-stage-1")
        .set(TICK_EFFECT.TICK_BATCH_ID, "batch-stage-1")
        .set(TICK_EFFECT.COMMAND_ID, "cmd-stage-1")
        .set(TICK_EFFECT.EFFECT_TYPE, "TEST")
        .set(TICK_EFFECT.TARGET_AGGREGATE, "test:1")
        .set(TICK_EFFECT.STATUS, "STAGED")
        .set(TICK_EFFECT.STAGED_AT, LocalDateTime.parse("2026-07-05T06:01:00"))
        .set(TICK_EFFECT.EFFECT_KEY, "effect-key-stage-1")
        .execute();
    assertThat(repository.hasDurableTickEffect("cmd-stage-1")).isTrue();
  }
}
