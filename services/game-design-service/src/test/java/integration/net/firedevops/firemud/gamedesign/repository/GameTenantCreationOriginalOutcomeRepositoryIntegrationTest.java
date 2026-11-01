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
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeBinding;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OriginalOutcomeSnapshot;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationOriginalOutcomeRepository.OutcomeStatus;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository.CreationRequestConflictException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
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
 * Physical PostgreSQL proof for durable original outcomes. Account binding values are controlled
 * INTERNAL fixture inputs; these cases do not prove authenticated creator authority or permission
 * to write.
 */
class GameTenantCreationOriginalOutcomeRepositoryIntegrationTest {
  private static final Table<?> OUTCOMES =
      DSL.table(DSL.name("game_tenant_creation_original_outcomes"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final org.jooq.Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final org.jooq.Field<UUID> TARGET_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final org.jooq.Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final org.jooq.Field<UUID> CREATION_OPERATION_ID =
      DSL.field(DSL.name("creation_operation_id"), UUID.class);
  private static final org.jooq.Field<String> CREATION_EVIDENCE_DIGEST =
      DSL.field(DSL.name("creation_evidence_digest"), String.class);
  private static final String NAMESPACE = "fresh-outcome-test";
  private static final String SOURCE_KEY = "fresh-outcome-tenant-01";
  private static final String NAME = "Original World";
  private static final String DESCRIPTION = "Created by controlled INTERNAL test input";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String ACCOUNT_DIGEST = "sha256:" + "a".repeat(64);
  private static final GameDesignPostgresIntegrationFixture postgres =
      new GameDesignPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void realCreationReceiptIsRecordedAndReplayedAsTheExactOriginalOutcome() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();

    CreatedResult original =
        fixture.inTransaction(
            () -> {
              OriginalOutcomeSnapshot pending = fixture.outcomeRepository.claimLocked(binding);
              assertThat(pending.status()).isEqualTo(OutcomeStatus.PENDING);
              FreshTenantCreationEvidence receipt =
                  fixture.creationRepository.createCandidate(
                      NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
              return new CreatedResult(
                  receipt, fixture.outcomeRepository.markCreatedLocked(binding, receipt));
            });

    assertThat(original.snapshot().status()).isEqualTo(OutcomeStatus.CREATED);
    assertThat(original.snapshot().creationOperationId())
        .isEqualTo(original.receipt().operationId());
    assertThat(original.snapshot().creationEvidenceDigest())
        .isEqualTo(original.receipt().evidenceDigest());
    assertThat(fixture.outcomeRepository.readCommitted(binding)).contains(original.snapshot());

    OriginalOutcomeSnapshot exactRetry =
        fixture.inTransaction(
            () -> {
              OriginalOutcomeSnapshot claimed = fixture.outcomeRepository.claimLocked(binding);
              assertThat(claimed.status()).isEqualTo(OutcomeStatus.CREATED);
              return fixture.outcomeRepository.markCreatedLocked(binding, original.receipt());
            });
    assertThat(exactRetry).isEqualTo(original.snapshot());
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> fixture.outcomeRepository.closeNotCreatedLocked(binding)))
        .isInstanceOf(CreationRequestConflictException.class)
        .hasMessageContaining("cannot be closed as not created");
    assertThat(fixture.creationRepository.read(REQUEST_ID, NAMESPACE)).contains(original.receipt());
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OUTCOMES)).isEqualTo(1);
  }

  @Test
  void missingReadIsNotNoCreationProofAndDurableTombstoneBlocksDelayedCreate() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();

    assertThat(fixture.outcomeRepository.readCommitted(binding)).isEmpty();

    OriginalOutcomeSnapshot closed =
        fixture.inTransaction(() -> fixture.outcomeRepository.closeNotCreatedLocked(binding));
    assertThat(closed.status()).isEqualTo(OutcomeStatus.NOT_CREATED);
    assertThat(fixture.outcomeRepository.readCommitted(binding)).contains(closed);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.creationRepository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("definitively closed");

    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    assertThat(fixture.outcomeRepository.readCommitted(binding)).contains(closed);
  }

  @Test
  void aRealCreationWinsAndCannotBeFalselyClosedAsNotCreated() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();
    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () ->
                fixture.creationRepository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> fixture.outcomeRepository.closeNotCreatedLocked(binding)))
        .isInstanceOf(CreationRequestConflictException.class)
        .hasMessageContaining("cannot be closed as not created");
    assertThat(fixture.creationRepository.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThat(fixture.outcomeRepository.readCommitted(binding)).isEmpty();
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void concurrentCloseSerializesBeforeDelayedCreationInsert() throws Exception {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();
    CountDownLatch closeHasWritten = new CountDownLatch(1);
    CountDownLatch allowCloseCommit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<OriginalOutcomeSnapshot> close =
          executor.submit(
              () ->
                  fixture.inTransaction(
                      () -> {
                        OriginalOutcomeSnapshot result =
                            fixture.outcomeRepository.closeNotCreatedLocked(binding);
                        closeHasWritten.countDown();
                        await(allowCloseCommit);
                        return result;
                      }));
      assertThat(closeHasWritten.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Throwable> delayedCreate =
          executor.submit(
              () -> {
                try {
                  fixture.inTransaction(
                      () ->
                          fixture.creationRepository.createCandidate(
                              NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));
                  return null;
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      waitForAdvisoryWaiter(fixture.dsl);
      allowCloseCommit.countDown();

      assertThat(close.get(10, TimeUnit.SECONDS).status()).isEqualTo(OutcomeStatus.NOT_CREATED);
      Throwable createFailure = delayedCreate.get(10, TimeUnit.SECONDS);
      assertThat(createFailure)
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("definitively closed");
      assertThat(fixture.outcomeRepository.readCommitted(binding).orElseThrow().status())
          .isEqualTo(OutcomeStatus.NOT_CREATED);
      assertThat(fixture.dsl.fetchCount(GAME)).isZero();
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    } finally {
      allowCloseCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void changedBindingsAndDirectSqlCannotInventRewriteDeleteOrTruncateOutcomes() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding binding = binding();

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .insertInto(OUTCOMES)
                    .set(TARGET_NAMESPACE, binding.targetNamespace())
                    .set(TARGET_REQUEST_ID, binding.creationRequestId())
                    .set(STATUS, "NOT_CREATED")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("must begin pending");

    assertThatThrownBy(
            () -> fixture.inTransaction(() -> fixture.outcomeRepository.claimLocked(binding)))
        .hasStackTraceContaining("cannot commit while pending");

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> {
                      fixture.outcomeRepository.claimLocked(binding);
                      FreshTenantCreationEvidence receipt =
                          fixture.creationRepository.createCandidate(
                              NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
                      fixture
                          .dsl
                          .update(OUTCOMES)
                          .set(STATUS, "CREATED")
                          .set(CREATION_OPERATION_ID, UUID.randomUUID())
                          .set(CREATION_EVIDENCE_DIGEST, receipt.evidenceDigest())
                          .where(
                              TARGET_NAMESPACE
                                  .eq(binding.targetNamespace())
                                  .and(TARGET_REQUEST_ID.eq(binding.creationRequestId())))
                          .execute();
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("exact V30 evidence");

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> {
                      fixture.outcomeRepository.claimLocked(binding);
                      fixture.creationRepository.createCandidate(
                          NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);
                      return fixture.outcomeRepository.closeNotCreatedLocked(binding);
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot be closed as not created");

    OriginalOutcomeSnapshot closed =
        fixture.inTransaction(() -> fixture.outcomeRepository.closeNotCreatedLocked(binding));
    var changedBindings =
        java.util.List.of(
            new OriginalOutcomeBinding(
                binding.targetNamespace(),
                binding.creationRequestId(),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                binding.initiatingAccountId(),
                binding.creationRequestDigest(),
                binding.accountAuthorizationDigest()),
            new OriginalOutcomeBinding(
                binding.targetNamespace(),
                binding.creationRequestId(),
                binding.accountAuthorizationOperationId(),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                binding.creationRequestDigest(),
                binding.accountAuthorizationDigest()),
            new OriginalOutcomeBinding(
                binding.targetNamespace(),
                binding.creationRequestId(),
                binding.accountAuthorizationOperationId(),
                binding.initiatingAccountId(),
                binding.creationRequestDigest(),
                "sha256:" + "b".repeat(64)),
            new OriginalOutcomeBinding(
                binding.targetNamespace(),
                binding.creationRequestId(),
                binding.accountAuthorizationOperationId(),
                binding.initiatingAccountId(),
                "sha256:" + "c".repeat(64),
                binding.accountAuthorizationDigest()));
    for (OriginalOutcomeBinding changedBinding : changedBindings) {
      assertThatThrownBy(
              () ->
                  fixture.inTransaction(
                      () -> fixture.outcomeRepository.claimLocked(changedBinding)))
          .isInstanceOf(CreationRequestConflictException.class)
          .hasMessageContaining("changed Account or request binding");
    }

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(OUTCOMES)
                    .set(STATUS, "CREATED")
                    .set(CREATION_OPERATION_ID, UUID.randomUUID())
                    .set(CREATION_EVIDENCE_DIGEST, ACCOUNT_DIGEST)
                    .where(
                        TARGET_NAMESPACE
                            .eq(binding.targetNamespace())
                            .and(TARGET_REQUEST_ID.eq(binding.creationRequestId())))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(OUTCOMES)
                    .where(
                        TARGET_NAMESPACE
                            .eq(binding.targetNamespace())
                            .and(TARGET_REQUEST_ID.eq(binding.creationRequestId())))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE TABLE game_tenant_creation_original_outcomes"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot be truncated");
    assertThat(fixture.outcomeRepository.readCommitted(binding)).contains(closed);
    assertThat(fixture.dsl.fetchCount(OUTCOMES)).isEqualTo(1);
  }

  @Test
  void accountAuthorizationOperationCannotBindASecondOriginal() {
    Fixture fixture = fixture();
    OriginalOutcomeBinding firstBinding = binding();
    OriginalOutcomeSnapshot firstOutcome =
        fixture.inTransaction(() -> fixture.outcomeRepository.closeNotCreatedLocked(firstBinding));
    String firstBytesAndXmin = outcomeBytesAndXmin(fixture.dsl, firstBinding);

    String secondNamespace = "fresh-outcome-second";
    UUID secondRequestId = UUID.fromString("66666666-6666-4666-8666-666666666666");
    String secondSourceKey = "fresh-outcome-tenant-02";
    String secondName = "Second World";
    OriginalOutcomeBinding secondBinding =
        new OriginalOutcomeBinding(
            secondNamespace,
            secondRequestId,
            ACCOUNT_OPERATION_ID,
            ACCOUNT_ID,
            GameTenantCreationDigest.requestDigest(
                secondNamespace, secondRequestId, secondSourceKey, secondName, DESCRIPTION),
            ACCOUNT_DIGEST);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () -> fixture.outcomeRepository.closeNotCreatedLocked(secondBinding)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("uq_game_creation_original_account_operation");

    assertThat(fixture.outcomeRepository.readCommitted(firstBinding)).contains(firstOutcome);
    assertThat(outcomeBytesAndXmin(fixture.dsl, firstBinding)).isEqualTo(firstBytesAndXmin);
    assertThat(fixture.outcomeRepository.readCommitted(secondBinding)).isEmpty();
    assertThat(fixture.dsl.fetchCount(OUTCOMES)).isEqualTo(1);
  }

  @Test
  void predecessorCreationSurvivesV1033AndCannotBeAdoptedOrClosed() {
    Fixture fixture = fixtureBeforeV1033();
    OriginalOutcomeBinding binding = binding();
    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () ->
                fixture.creationRepository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION));
    String predecessorBytesAndXmin = operationBytesAndXmin(fixture.dsl);

    migrate(fixture.dataSource(), fixture.schema(), null);
    Fixture upgraded = fixture.withCurrentRepositories();

    assertThat(operationBytesAndXmin(upgraded.dsl)).isEqualTo(predecessorBytesAndXmin);
    assertThat(upgraded.creationRepository.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThatThrownBy(
            () ->
                upgraded.inTransaction(
                    () -> upgraded.outcomeRepository.closeNotCreatedLocked(binding)))
        .isInstanceOf(CreationRequestConflictException.class)
        .hasMessageContaining("cannot be closed as not created");
    assertThat(upgraded.outcomeRepository.readCommitted(binding)).isEmpty();
    assertThat(operationBytesAndXmin(upgraded.dsl)).isEqualTo(predecessorBytesAndXmin);
  }

  private Fixture fixture() {
    return fixtureAtTarget(null);
  }

  private Fixture fixtureBeforeV1033() {
    return fixtureAtTarget("1032");
  }

  private Fixture fixtureAtTarget(String targetVersion) {
    String schema = "game_design_original_outcome_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource();
    dataSource.setSchema(schema);
    migrate(dataSource, schema, targetVersion);
    return fixture(dataSource, schema);
  }

  private static Fixture fixture(DriverManagerDataSource dataSource, String schema) {
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository creationRepository =
        new GameTenantCreationRepository(dsl, gameRepository);
    GameTenantCreationOriginalOutcomeRepository outcomeRepository =
        new GameTenantCreationOriginalOutcomeRepository(dsl);
    return new Fixture(
        dsl, dataSource, schema, creationRepository, outcomeRepository, transactionTemplate);
  }

  private static void migrate(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history_game_design_service")
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
    if (targetVersion != null) {
      configuration.target(MigrationVersion.fromVersion(targetVersion));
    }
    configuration.load().migrate();
  }

  private static OriginalOutcomeBinding binding() {
    return new OriginalOutcomeBinding(
        NAMESPACE,
        REQUEST_ID,
        ACCOUNT_OPERATION_ID,
        ACCOUNT_ID,
        GameTenantCreationDigest.requestDigest(
            NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION),
        ACCOUNT_DIGEST);
  }

  private static String operationBytesAndXmin(DSLContext dsl) {
    var row =
        dsl.fetchOne(
            "SELECT row_to_json(operation)::TEXT AS row_bytes, xmin::TEXT AS xmin "
                + "FROM game_tenant_creation_operations operation "
                + "WHERE target_namespace = ? AND creation_request_id = ?",
            NAMESPACE,
            REQUEST_ID);
    if (row == null) {
      throw new IllegalStateException("Expected predecessor fresh-creation operation is missing");
    }
    return row.get("row_bytes", String.class) + ":" + row.get("xmin", String.class);
  }

  private static String outcomeBytesAndXmin(DSLContext dsl, OriginalOutcomeBinding binding) {
    var row =
        dsl.fetchOne(
            "SELECT row_to_json(original_outcome)::TEXT AS row_bytes, xmin::TEXT AS xmin "
                + "FROM game_tenant_creation_original_outcomes original_outcome "
                + "WHERE target_namespace = ? AND creation_request_id = ?",
            binding.targetNamespace(),
            binding.creationRequestId());
    if (row == null) {
      throw new IllegalStateException("Expected original outcome row is missing");
    }
    return row.get("row_bytes", String.class) + ":" + row.get("xmin", String.class);
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
    throw new AssertionError("Concurrent creation did not wait on the shared identity lock");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent outcome test release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while coordinating concurrent database test", exception);
    }
  }

  private record CreatedResult(
      FreshTenantCreationEvidence receipt, OriginalOutcomeSnapshot snapshot) {}

  private record Fixture(
      DSLContext dsl,
      DriverManagerDataSource dataSource,
      String schema,
      GameTenantCreationRepository creationRepository,
      GameTenantCreationOriginalOutcomeRepository outcomeRepository,
      TransactionTemplate transactionTemplate) {
    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
      return transactionTemplate.execute(
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

    private Fixture withCurrentRepositories() {
      return fixture(dataSource, schema);
    }
  }
}
