package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.impl.DatabaseGameplayAdmissionPointerAuthorityService;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameplayAdmissionPointerCasPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long TENANT_ID = 419L;
  private static final long INITIAL_GAME_INSTANCE_ID = 8201L;
  private static final String WORLD_SLUG = "cas-world";
  private static final String REALM_SLUG = "cas-realm";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withCommand("postgres", "-c", "fsync=off", "-c", "track_activity_query_size=128");

  @BeforeEach
  void migrateIsolatedContainerSchema() {
    DriverManagerDataSource dataSource = dataSource();
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATION_LOCATION)
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
  }

  @Test
  void competingWritersWithSamePositiveVersionPairCommitOnePointerAndAudit() throws Exception {
    Fixture fixture = fixture();
    GameplayAdmissionPointerSnapshot initial =
        fixture
            .transactionTemplate()
            .execute(
                status ->
                    fixture
                        .service()
                        .upsertPointer(
                            mutation(
                                "Initial World",
                                INITIAL_GAME_INSTANCE_ID,
                                0L,
                                0L,
                                "initial-pointer")));
    assertThat(initial).isNotNull();
    assertThat(initial.pointerVersion()).isEqualTo(1L);
    assertThat(initial.catalogRevision()).isEqualTo(1L);
    assertThat(fixture.service().listPointerAudit(TENANT_ID, WORLD_SLUG, REALM_SLUG)).hasSize(1);

    CountDownLatch winningTransactionReady = new CountDownLatch(1);
    CountDownLatch releaseWinningTransaction = new CountDownLatch(1);
    CountDownLatch competingTransactionReady = new CountDownLatch(1);
    AtomicInteger winningBackendPid = new AtomicInteger();
    AtomicInteger competingBackendPid = new AtomicInteger();
    ExecutorService executor =
        Executors.newFixedThreadPool(
            2,
            task -> {
              Thread thread = new Thread(task, "admission-pointer-cas-test");
              thread.setDaemon(true);
              return thread;
            });
    Future<MutationAttempt> first =
        executor.submit(
            () -> {
              try {
                GameplayAdmissionPointerSnapshot snapshot =
                    fixture
                        .transactionTemplate()
                        .execute(
                            status -> {
                              fixture.dsl().execute("SET LOCAL statement_timeout = '30000ms'");
                              var backendPidRecord =
                                  Objects.requireNonNull(
                                      fixture.dsl().fetchOne("select pg_backend_pid()"),
                                      "Winning backend PID query returned no row");
                              winningBackendPid.set(
                                  Objects.requireNonNull(
                                      backendPidRecord.get(0, Integer.class),
                                      "Winning backend PID query returned no value"));
                              GameplayAdmissionPointerSnapshot updated =
                                  fixture
                                      .service()
                                      .upsertPointer(
                                          mutation(
                                              "Concurrent World A",
                                              INITIAL_GAME_INSTANCE_ID,
                                              1L,
                                              1L,
                                              "writer-a"));
                              winningTransactionReady.countDown();
                              awaitLatch(
                                  releaseWinningTransaction,
                                  "release of winning pointer transaction");
                              return updated;
                            });
                return new MutationAttempt(snapshot, null);
              } catch (Throwable failure) {
                return new MutationAttempt(null, failure);
              }
            });
    Future<MutationAttempt> second =
        executor.submit(
            () -> {
              try {
                GameplayAdmissionPointerSnapshot snapshot =
                    fixture
                        .transactionTemplate()
                        .execute(
                            status -> {
                              fixture.dsl().execute("SET LOCAL statement_timeout = '30000ms'");
                              var backendPidRecord =
                                  Objects.requireNonNull(
                                      fixture.dsl().fetchOne("select pg_backend_pid()"),
                                      "Competing backend PID query returned no row");
                              competingBackendPid.set(
                                  Objects.requireNonNull(
                                      backendPidRecord.get(0, Integer.class),
                                      "Competing backend PID query returned no value"));
                              competingTransactionReady.countDown();
                              awaitLatch(
                                  winningTransactionReady,
                                  "winning pointer transaction to acquire and hold its row lock");
                              return fixture
                                  .service()
                                  .upsertPointer(
                                      mutation(
                                          "Concurrent World B",
                                          INITIAL_GAME_INSTANCE_ID,
                                          1L,
                                          1L,
                                          "writer-b"));
                            });
                return new MutationAttempt(snapshot, null);
              } catch (Throwable failure) {
                return new MutationAttempt(null, failure);
              }
            });

    try {
      assertThat(winningTransactionReady.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(competingTransactionReady.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(winningBackendPid.get()).isPositive();
      assertThat(competingBackendPid.get()).isPositive();
      assertThat(competingBackendPid.get()).isNotEqualTo(winningBackendPid.get());
      awaitPostgresRowLockWait(fixture.dsl(), competingBackendPid.get(), winningBackendPid.get());
      releaseWinningTransaction.countDown();

      List<MutationAttempt> attempts =
          List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      List<MutationAttempt> winners = attempts.stream().filter(MutationAttempt::succeeded).toList();
      List<MutationAttempt> conflicts =
          attempts.stream().filter(attempt -> !attempt.succeeded()).toList();

      assertThat(winners).hasSize(1);
      assertThat(conflicts).hasSize(1);
      assertThat(conflicts.get(0).failure())
          .isInstanceOf(AdmissionPointerVersionMismatchException.class);

      GameplayAdmissionPointerSnapshot current =
          fixture.service().findPointer(TENANT_ID, WORLD_SLUG, REALM_SLUG).orElseThrow();
      assertThat(current.pointerVersion()).isEqualTo(initial.pointerVersion());
      assertThat(current.catalogRevision()).isEqualTo(initial.catalogRevision() + 1L);
      assertThat(current.worldDisplayName()).isIn("Concurrent World A", "Concurrent World B");

      List<GameplayAdmissionPointerAuditEntry> audit =
          fixture.service().listPointerAudit(TENANT_ID, WORLD_SLUG, REALM_SLUG);
      assertThat(audit).hasSize(2);
      assertThat(audit.get(0).catalogRevision()).isEqualTo(current.catalogRevision());
      assertThat(audit.get(0).worldDisplayName()).isEqualTo(current.worldDisplayName());
    } finally {
      releaseWinningTransaction.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void auditInsertFailureRollsBackPointerRevisionAndRuntimeTarget() {
    Fixture fixture = fixture();
    GameplayAdmissionPointerSnapshot initial =
        fixture
            .transactionTemplate()
            .execute(
                status ->
                    fixture
                        .service()
                        .upsertPointer(
                            mutation(
                                "Initial World",
                                INITIAL_GAME_INSTANCE_ID,
                                0L,
                                0L,
                                "initial-pointer")));
    assertThat(initial).isNotNull();
    List<GameplayAdmissionPointerAuditEntry> initialAudit =
        fixture.service().listPointerAudit(TENANT_ID, WORLD_SLUG, REALM_SLUG);
    assertThat(initialAudit).hasSize(1);

    fixture
        .dsl()
        .execute(
            "CREATE FUNCTION reject_gameplay_admission_audit() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'forced admission audit insert failure'; "
                + "END; $$");
    fixture
        .dsl()
        .execute(
            "CREATE TRIGGER reject_gameplay_admission_audit BEFORE INSERT ON "
                + "gameplay_admission_pointer_event FOR EACH ROW EXECUTE FUNCTION "
                + "reject_gameplay_admission_audit()");

    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status ->
                            fixture
                                .service()
                                .upsertPointer(
                                    mutation(
                                        "Initial World",
                                        INITIAL_GAME_INSTANCE_ID + 1L,
                                        initial.pointerVersion(),
                                        initial.catalogRevision(),
                                        "failed-audit"))))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("forced admission audit insert failure");

    GameplayAdmissionPointerSnapshot current =
        fixture.service().findPointer(TENANT_ID, WORLD_SLUG, REALM_SLUG).orElseThrow();
    assertThat(current.pointerVersion()).isEqualTo(initial.pointerVersion());
    assertThat(current.catalogRevision()).isEqualTo(initial.catalogRevision());
    assertThat(current.gameInstanceId()).isEqualTo(initial.gameInstanceId());
    assertThat(current.worldDisplayName()).isEqualTo(initial.worldDisplayName());
    assertThat(fixture.service().listPointerAudit(TENANT_ID, WORLD_SLUG, REALM_SLUG))
        .containsExactlyElementsOf(initialAudit);
  }

  private static void awaitPostgresRowLockWait(DSLContext dsl, int waiterPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    boolean blockedByWinner = false;
    while (System.nanoTime() < deadline) {
      // Activity SQL can be truncated, so lock state and blocker PIDs are the oracle.
      var lockWaitRecord =
          Objects.requireNonNull(
              dsl.fetchOne(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? "
                      + "AND wait_event_type = 'Lock' "
                      + "AND ? = ANY(pg_blocking_pids(pid)))",
                  waiterPid,
                  blockerPid),
              "PostgreSQL lock-wait query returned no row");
      blockedByWinner =
          Objects.requireNonNull(
              lockWaitRecord.get(0, Boolean.class), "PostgreSQL lock-wait query returned no value");
      if (blockedByWinner) {
        break;
      }
      try {
        Thread.sleep(10L);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while observing PostgreSQL row lock wait", exception);
      }
    }
    assertThat(blockedByWinner)
        .as("competing backend waits on a PostgreSQL lock held by the winning transaction")
        .isTrue();
  }

  private static void awaitLatch(CountDownLatch latch, String awaitedState) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for " + awaitedState);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for " + awaitedState, exception);
    }
  }

  private static Fixture fixture() {
    DriverManagerDataSource dataSource = dataSource();
    TransactionAwareDataSourceProxy transactionAwareDataSource =
        new TransactionAwareDataSourceProxy(dataSource);
    DSLContext dsl = DSL.using(transactionAwareDataSource, SQLDialect.POSTGRES);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    DatabaseGameplayAdmissionPointerAuthorityService service =
        new DatabaseGameplayAdmissionPointerAuthorityService(
            new GameplayAdmissionPointerRepository(dsl),
            new GameplayAdmissionPointerEventRepository(dsl));
    return new Fixture(dsl, transactionTemplate, service);
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private static GameplayAdmissionPointerMutation mutation(
      String worldDisplayName,
      long gameInstanceId,
      long expectedPointerVersion,
      long expectedCatalogRevision,
      String requestId) {
    return new GameplayAdmissionPointerMutation(
        WORLD_SLUG,
        worldDisplayName,
        REALM_SLUG,
        "CAS Realm",
        TENANT_ID,
        gameInstanceId,
        true,
        true,
        false,
        "SHARED",
        "ALLOW_NEW",
        "postgres-integration-test",
        "verify pointer CAS transaction",
        requestId,
        expectedPointerVersion,
        expectedCatalogRevision,
        null);
  }

  private record Fixture(
      DSLContext dsl,
      TransactionTemplate transactionTemplate,
      DatabaseGameplayAdmissionPointerAuthorityService service) {}

  private record MutationAttempt(GameplayAdmissionPointerSnapshot snapshot, Throwable failure) {
    private boolean succeeded() {
      return failure == null;
    }
  }
}
