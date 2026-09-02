package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.jooq.tables.GameplayAdmissionPointer.GAMEPLAY_ADMISSION_POINTER;
import static net.firedevops.firemud.gamesession.jooq.tables.GameplayCommand.GAMEPLAY_COMMAND;
import static net.firedevops.firemud.gamesession.jooq.tables.TickEffect.TICK_EFFECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import org.springframework.transaction.support.TransactionTemplate;
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
  private TransactionTemplate transactionTemplate;
  private PointerLockInterlockListener pointerLockInterlock;

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

    pointerLockInterlock = new PointerLockInterlockListener();
    DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(new TransactionAwareDataSourceProxy(dataSource));
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(new DefaultExecuteListenerProvider(pointerLockInterlock));
    dsl = DSL.using(configuration);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    repository = transactionalRepository(new GameplayCommandRepository(dsl), transactionManager);
  }

  @BeforeEach
  void cleanTable() {
    dsl.execute(
        "TRUNCATE TABLE gameplay_command, gameplay_admission_pointer RESTART IDENTITY CASCADE");
  }

  @Test
  void routedInsertIsConditionallyBoundToCurrentAdmissionPointer() {
    insertAdmissionPointer("demo", "production", 17L, "SHARED", 7L);
    GameplayCommand command = automationCommand("routed-current", "dispatch-routed-current");
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("DEMO");
    command.setRealmSlug("Production");
    command.setPointerVersion(17L);

    GameplayCommandRepository.IdempotentInsertResult inserted =
        repository.insertIfAbsentByIdempotencyIdentity(command);
    assertThat(inserted.inserted()).isTrue();

    dsl.update(GAMEPLAY_ADMISSION_POINTER)
        .set(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION, 18L)
        .where(
            GAMEPLAY_ADMISSION_POINTER
                .TENANT_ID
                .eq(1L)
                .and(GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG.eq("demo")))
        .execute();

    GameplayCommand retry = automationCommand("routed-current", "dispatch-routed-current");
    retry.setPlayableStateScope("SHARED");
    retry.setWorldSlug("demo");
    retry.setRealmSlug("production");
    retry.setPointerVersion(17L);
    GameplayCommandRepository.IdempotentInsertResult replay =
        repository.insertIfAbsentByIdempotencyIdentity(retry);
    assertThat(replay.inserted()).isFalse();
    assertThat(replay.command().getCommandId()).isEqualTo("routed-current");

    GameplayCommand stale = automationCommand("routed-stale", "dispatch-routed-stale");
    stale.setPlayableStateScope("SHARED");
    stale.setWorldSlug("demo");
    stale.setRealmSlug("production");
    stale.setPointerVersion(17L);

    assertThatThrownBy(() -> repository.insertIfAbsentByIdempotencyIdentity(stale))
        .isInstanceOf(GameplayCommandRepository.AdmissionPointerUnavailableException.class);
    assertThat(dsl.fetchCount(GAMEPLAY_COMMAND)).isEqualTo(1);
  }

  @Test
  void routedIdempotencyConflictRemainsDistinctFromStalePointer() {
    insertAdmissionPointer("demo", "production", 17L, "SHARED", 7L);
    GameplayCommand first = automationCommand("routed-existing", "dispatch-routed-existing");
    first.setPlayableStateScope("SHARED");
    first.setWorldSlug("demo");
    first.setRealmSlug("production");
    first.setPointerVersion(17L);
    repository.insertIfAbsentByIdempotencyIdentity(first);

    GameplayCommand retry = automationCommand("routed-retry", "dispatch-routed-existing");
    retry.setPlayableStateScope("SHARED");
    retry.setWorldSlug("demo");
    retry.setRealmSlug("production");
    retry.setPointerVersion(17L);

    GameplayCommandRepository.IdempotentInsertResult result =
        repository.insertIfAbsentByIdempotencyIdentity(retry);
    assertThat(result.inserted()).isFalse();
    assertThat(result.command().getCommandId()).isEqualTo("routed-existing");
  }

  private void insertAdmissionPointer(
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      String stateScope,
      long gameInstanceId) {
    dsl.insertInto(GAMEPLAY_ADMISSION_POINTER)
        .set(GAMEPLAY_ADMISSION_POINTER.WORLD_SLUG, worldSlug)
        .set(GAMEPLAY_ADMISSION_POINTER.WORLD_DISPLAY_NAME, "Demo")
        .set(GAMEPLAY_ADMISSION_POINTER.REALM_SLUG, realmSlug)
        .set(GAMEPLAY_ADMISSION_POINTER.REALM_DISPLAY_NAME, "Production")
        .set(GAMEPLAY_ADMISSION_POINTER.TENANT_ID, 1L)
        .set(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID, gameInstanceId)
        .set(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION, pointerVersion)
        .set(GAMEPLAY_ADMISSION_POINTER.VISIBLE, true)
        .set(GAMEPLAY_ADMISSION_POINTER.PUBLIC_PRODUCTION_REALM, true)
        .set(GAMEPLAY_ADMISSION_POINTER.REQUIRES_CHARACTER_SELECTION, false)
        .set(GAMEPLAY_ADMISSION_POINTER.STATE_SCOPE, stateScope)
        .set(GAMEPLAY_ADMISSION_POINTER.CHARACTER_CREATION_POLICY, "NONE")
        .set(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATED_BY, "test")
        .set(GAMEPLAY_ADMISSION_POINTER.LAST_UPDATE_REASON, "test")
        .execute();
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

  @Test
  void concurrentAutomationAdmissionsUseOneDurableIdentityRow() throws Exception {
    GameplayCommand first = automationCommand("auto-concurrent-1", "dispatch-concurrent");
    GameplayCommand second = automationCommand("auto-concurrent-2", "dispatch-concurrent");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<GameplayCommandRepository.IdempotentInsertResult> firstResult =
          executor.submit(
              () -> {
                start.await();
                return repository.insertIfAbsentByIdempotencyIdentity(first);
              });
      Future<GameplayCommandRepository.IdempotentInsertResult> secondResult =
          executor.submit(
              () -> {
                start.await();
                return repository.insertIfAbsentByIdempotencyIdentity(second);
              });
      start.countDown();

      GameplayCommandRepository.IdempotentInsertResult firstInsert = firstResult.get();
      GameplayCommandRepository.IdempotentInsertResult secondInsert = secondResult.get();
      GameplayCommand firstSaved = firstInsert.command();
      GameplayCommand secondSaved = secondInsert.command();
      assertThat(List.of(firstInsert.inserted(), secondInsert.inserted()))
          .containsExactlyInAnyOrder(true, false);
      assertThat(firstSaved.getId()).isEqualTo(secondSaved.getId());
      assertThat(firstSaved.getCommandId()).isEqualTo(secondSaved.getCommandId());
      assertThat(firstSaved.getCommandId()).isIn(List.of("auto-concurrent-1", "auto-concurrent-2"));
      assertThat(dsl.fetchCount(GAMEPLAY_COMMAND)).isEqualTo(1);
      assertThat(
              repository
                  .findByTenantIdAndGameInstanceIdAndRegionIdAndRegionEpochAndAutomationDispatchId(
                      1L, 7L, "region-1", 12L, "dispatch-concurrent"))
          .get()
          .extracting(GameplayCommand::getExecutionOutcome)
          .isEqualTo("ACCEPTED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentRoutedRemoteAdmissionCannotInterleavePointerMutation() throws Exception {
    GameplayCommand command = automationCommand("remote-concurrent", "dispatch-unused");
    command.setAutomationDispatchId(null);
    command.setRemoteFollowupId("followup-concurrent");
    assertRoutedAdmissionLocksPointerMutation(command);
  }

  @Test
  void concurrentRoutedAutomationAdmissionCannotInterleavePointerMutation() throws Exception {
    GameplayCommand command = automationCommand("automation-concurrent", "dispatch-concurrent");
    assertRoutedAdmissionLocksPointerMutation(command);
  }

  private void assertRoutedAdmissionLocksPointerMutation(GameplayCommand command) throws Exception {
    insertAdmissionPointer("demo", "production", 17L, "SHARED", 7L);
    command.setPlayableStateScope("SHARED");
    command.setWorldSlug("demo");
    command.setRealmSlug("production");
    command.setPointerVersion(17L);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    pointerLockInterlock.reset();
    try {
      Future<GameplayCommandRepository.IdempotentInsertResult> admission =
          executor.submit(() -> repository.insertIfAbsentByIdempotencyIdentity(command));
      assertThat(pointerLockInterlock.pointerLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Integer> pointerMutation =
          executor.submit(
              () -> {
                try {
                  transactionTemplate.execute(
                      status -> {
                        dsl.execute("SET LOCAL lock_timeout = '100ms'");
                        assertThat(dsl.fetchValue("SHOW lock_timeout")).isEqualTo("100ms");
                        return updateAdmissionPointer();
                      });
                  pointerLockInterlock.pointerMutationBlocked.countDown();
                  throw new AssertionError("The pointer mutation interleaved with admission");
                } catch (RuntimeException failure) {
                  pointerLockInterlock.pointerMutationBlocked.countDown();
                  if (!"55P03".equals(sqlState(failure))) {
                    throw failure;
                  }
                }
                return transactionTemplate.execute(status -> updateAdmissionPointer());
              });
      assertThat(pointerLockInterlock.mutationStatementStarted.await(10, TimeUnit.SECONDS))
          .isTrue();

      GameplayCommandRepository.IdempotentInsertResult result = admission.get(10, TimeUnit.SECONDS);
      assertThat(result.inserted()).isTrue();
      assertThat(pointerMutation.get(10, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(
              dsl.fetchValue(
                  dsl.select(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION)
                      .from(GAMEPLAY_ADMISSION_POINTER)
                      .where(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID.eq(7L))))
          .isEqualTo(18L);
      assertThat(dsl.fetchCount(GAMEPLAY_COMMAND)).isEqualTo(1);
    } finally {
      pointerLockInterlock.disable();
      executor.shutdownNow();
    }
  }

  private int updateAdmissionPointer() {
    return dsl.update(GAMEPLAY_ADMISSION_POINTER)
        .set(GAMEPLAY_ADMISSION_POINTER.POINTER_VERSION, 18L)
        .where(
            GAMEPLAY_ADMISSION_POINTER
                .TENANT_ID
                .eq(1L)
                .and(GAMEPLAY_ADMISSION_POINTER.GAME_INSTANCE_ID.eq(7L)))
        .execute();
  }

  private static String sqlState(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        return sqlException.getSQLState();
      }
    }
    return null;
  }

  private GameplayCommandRepository transactionalRepository(
      GameplayCommandRepository target, DataSourceTransactionManager transactionManager) {
    TransactionProxyFactoryBean proxyFactory = new TransactionProxyFactoryBean();
    proxyFactory.setTransactionManager(transactionManager);
    proxyFactory.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    proxyFactory.setTarget(target);
    proxyFactory.setProxyTargetClass(true);
    proxyFactory.afterPropertiesSet();
    return (GameplayCommandRepository) proxyFactory.getObject();
  }

  private static GameplayCommand automationCommand(String commandId, String dispatchId) {
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
    command.setLastAttemptAt(Instant.parse("2026-07-05T06:00:00Z"));
    command.setAttemptCount(1);
    command.setSourceType("AUTOMATION");
    command.setAutomationDispatchId(dispatchId);
    command.setAutomationWorkItemId("work-1");
    command.setScriptId("script-1");
    command.setScriptPatchVersion("patch-1");
    command.setPlayableStateScope("");
    command.setWorldSlug("");
    command.setRealmSlug("");
    command.setTargetEntityId("npc-1");
    command.setRegionId("region-1");
    command.setRegionEpoch(12L);
    return command;
  }

  private static final class PointerLockInterlockListener extends DefaultExecuteListener {
    private volatile CountDownLatch pointerLockAcquired = new CountDownLatch(1);
    private volatile CountDownLatch mutationStatementStarted = new CountDownLatch(1);
    private volatile CountDownLatch pointerMutationBlocked = new CountDownLatch(1);
    private final AtomicBoolean enabled = new AtomicBoolean();

    private void reset() {
      pointerLockAcquired = new CountDownLatch(1);
      mutationStatementStarted = new CountDownLatch(1);
      pointerMutationBlocked = new CountDownLatch(1);
      enabled.set(true);
    }

    private void disable() {
      enabled.set(false);
    }

    @Override
    public void executeStart(ExecuteContext context) {
      String sqlText = context.sql();
      if (!enabled.get() || sqlText == null) {
        return;
      }
      String sql = sqlText.toLowerCase(java.util.Locale.ROOT);
      if (sql.contains("update \"gameplay_admission_pointer\"")) {
        mutationStatementStarted.countDown();
      }
    }

    @Override
    public void executeEnd(ExecuteContext context) {
      String sqlText = context.sql();
      if (!enabled.get() || sqlText == null) {
        return;
      }
      String sql = sqlText.toLowerCase(java.util.Locale.ROOT);
      if (sql.contains("for update") && sql.contains("\"gameplay_admission_pointer\"")) {
        pointerLockAcquired.countDown();
        try {
          if (!mutationStatementStarted.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent pointer mutation");
          }
          if (!pointerMutationBlocked.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Pointer mutation was not blocked by admission");
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted waiting for concurrent pointer mutation", ex);
        }
      }
    }
  }
}
