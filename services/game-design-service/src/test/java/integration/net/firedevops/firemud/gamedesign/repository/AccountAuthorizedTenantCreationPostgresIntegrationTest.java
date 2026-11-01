package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.AccountAuthorizedTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Physical PostgreSQL definitions for the V1034 creation commit fence; Account input is controlled.
 */
class AccountAuthorizedTenantCreationPostgresIntegrationTest {
  private static final String NAMESPACE = "authorized-creation-test";
  private static final String SOURCE_KEY = "authorized-tenant-01";
  private static final String NAME = "Authorized Realm";
  private static final String DESCRIPTION = "Controlled Account authority response";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID AUTHORIZATION_READ_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String ACCOUNT_DIGEST = "sha256:" + "a".repeat(64);
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Table<?> OUTCOMES =
      DSL.table(DSL.name("game_tenant_creation_original_outcomes"));
  private static final Table<?> DISPATCHES =
      DSL.table(DSL.name("game_tenant_creation_authorized_dispatches"));
  private static final GameDesignPostgresIntegrationFixture POSTGRES =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void commitsExactV30V1033ReceiptAndV1034AccountDeadlineFenceAtomically() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = repositoryBinding();
    long validBefore = System.currentTimeMillis() + 60_000L;

    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () -> {
              fixture.dispatches.applyLocalWriteTimeouts();
              assertThat(fixture.dispatches.isAuthorizationCurrent(validBefore)).isTrue();
              OriginalOutcomeSnapshot claim = fixture.outcomes.claimLocked(binding);
              assertThat(claim.status()).isEqualTo(OutcomeStatus.PENDING);
              FreshTenantCreationEvidence created =
                  fixture.creation.createCandidate(
                      NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
              fixture.outcomes.markCreatedLocked(binding, created);
              fixture.dispatches.recordAuthorizedDispatch(
                  binding, AUTHORIZATION_READ_ID, validBefore);
              return created;
            });

    OriginalOutcomeSnapshot outcome = fixture.outcomes.readCommitted(binding).orElseThrow();
    var dispatch = fixture.dispatches.readCommitted(binding).orElseThrow();
    assertThat(outcome.status()).isEqualTo(OutcomeStatus.CREATED);
    assertThat(fixture.creation.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThat(dispatch.binding()).isEqualTo(binding);
    assertThat(dispatch.authorizationReadRequestId()).isEqualTo(AUTHORIZATION_READ_ID);
    assertThat(dispatch.validBeforeEpochMillis()).isEqualTo(validBefore);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OUTCOMES)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(DISPATCHES)).isEqualTo(1);
  }

  @Test
  void commitTimeDeadlineGuardRollsBackTenantAndBothOwnerEvidenceRows() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = repositoryBinding();
    long expiredBefore = System.currentTimeMillis() - 1_000L;

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> {
                      fixture.dispatches.applyLocalWriteTimeouts();
                      fixture.outcomes.claimLocked(binding);
                      FreshTenantCreationEvidence receipt =
                          fixture.creation.createCandidate(
                              NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
                      fixture.outcomes.markCreatedLocked(binding, receipt);
                      // Intentionally bypass the preflight clock check to exercise V1034's deferred
                      // fresh-clock commit guard and prove the whole owner transaction rolls back.
                      fixture.dispatches.recordAuthorizedDispatch(
                          binding, AUTHORIZATION_READ_ID, expiredBefore);
                      return receipt;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("authorization expired before Game Design commit");

    assertThat(fixture.creation.read(REQUEST_ID, NAMESPACE)).isEmpty();
    assertThat(fixture.outcomes.readCommitted(binding)).isEmpty();
    assertThat(fixture.dispatches.readCommitted(binding)).isEmpty();
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(fixture.dsl.fetchCount(OUTCOMES)).isZero();
    assertThat(fixture.dsl.fetchCount(DISPATCHES)).isZero();
  }

  @Test
  void tombstoneHoldingSharedIdentityLockBlocksDelayedAuthorizedCreation() throws Exception {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = repositoryBinding();
    CountDownLatch tombstoneWritten = new CountDownLatch(1);
    CountDownLatch allowTombstoneCommit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<OriginalOutcomeSnapshot> tombstone =
          executor.submit(
              () ->
                  fixture.inTransaction(
                      () -> {
                        OriginalOutcomeSnapshot closed =
                            fixture.outcomes.closeNotCreatedLocked(binding);
                        tombstoneWritten.countDown();
                        await(allowTombstoneCommit);
                        return closed;
                      }));
      assertThat(tombstoneWritten.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Throwable> delayedAuthorizedCreate =
          executor.submit(
              () -> {
                try {
                  fixture.inTransaction(
                      () -> {
                        fixture.dispatches.applyLocalWriteTimeouts();
                        if (!fixture.dispatches.isAuthorizationCurrent(
                            System.currentTimeMillis() + 60_000L)) {
                          throw new IllegalStateException("Controlled authorization expired");
                        }
                        OriginalOutcomeSnapshot claim = fixture.outcomes.claimLocked(binding);
                        if (claim.status() == OutcomeStatus.NOT_CREATED) {
                          throw new IllegalStateException("Definitive tombstone blocks creation");
                        }
                        return fixture.creation.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
                      });
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });
      waitForAdvisoryWaiter(fixture.dsl);
      allowTombstoneCommit.countDown();

      assertThat(tombstone.get(10, TimeUnit.SECONDS).status()).isEqualTo(OutcomeStatus.NOT_CREATED);
      assertThat(delayedAuthorizedCreate.get(10, TimeUnit.SECONDS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("tombstone blocks creation");
      assertThat(fixture.outcomes.readCommitted(binding).orElseThrow().status())
          .isEqualTo(OutcomeStatus.NOT_CREATED);
      assertThat(fixture.creation.read(REQUEST_ID, NAMESPACE)).isEmpty();
      assertThat(fixture.dsl.fetchCount(GAME)).isZero();
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
      assertThat(fixture.dsl.fetchCount(DISPATCHES)).isZero();
    } finally {
      allowTombstoneCommit.countDown();
      executor.shutdownNow();
    }
  }

  private static Fixture fixture() {
    String schema = "gd_tenant_creation_auth_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = POSTGRES.dataSource();
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    TransactionTemplate write =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    write.setTimeout(15);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    return new Fixture(
        dsl,
        new GameTenantCreationRepository(dsl, gameRepository),
        new GameTenantCreationOriginalOutcomeRepository(dsl),
        new AccountAuthorizedTenantCreationRepository(dsl),
        write);
  }

  private static OriginalOutcomeBinding repositoryBinding() {
    return new OriginalOutcomeBinding(
        NAMESPACE, REQUEST_ID, ACCOUNT_OPERATION_ID, ACCOUNT_ID, REQUEST_DIGEST, ACCOUNT_DIGEST);
  }

  private static void waitForAdvisoryWaiter(DSLContext dsl) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          java.util.Objects.requireNonNull(
                  dsl.fetchOne(
                      "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted) AS waiting"))
              .get("waiting", Boolean.class);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Authorized creation did not wait on the shared identity lock");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent tombstone test release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while coordinating concurrent creation proof", exception);
    }
  }

  private record Fixture(
      DSLContext dsl,
      GameTenantCreationRepository creation,
      GameTenantCreationOriginalOutcomeRepository outcomes,
      AccountAuthorizedTenantCreationRepository dispatches,
      TransactionTemplate write) {
    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
      return write.execute(
          status -> {
            try {
              return work.call();
            } catch (RuntimeException exception) {
              throw exception;
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          });
    }
  }
}
