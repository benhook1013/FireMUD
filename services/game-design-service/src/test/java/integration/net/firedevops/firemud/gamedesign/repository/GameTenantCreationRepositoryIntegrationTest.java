package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import org.flywaydb.core.Flyway;
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
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

class GameTenantCreationRepositoryIntegrationTest {
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final String NAMESPACE = "fresh-tenant-test";
  private static final String SOURCE_KEY = "new-game-tenant-01";
  private static final String NAME = "World";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

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
  void exactRetryReturnsCommittedReceiptWithoutSecondGameWrite() {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence first =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(first);
    FreshTenantCreationEvidence exactRetry =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(exactRetry).isEqualTo(first);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateCreationRequestsConvergeOnOneUuidAndReceipt() throws Exception {
    Fixture fixture = fixture();
    var firstBackend = new CompletableFuture<Integer>();
    var secondBackend = new CompletableFuture<Integer>();
    var allowFirstCommit = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<OwnerWrite> first =
          executor.submit(
              () ->
                  fixture.inTransaction(
                      () -> {
                        FreshTenantCreationEvidence receipt = createCandidate(fixture);
                        RowVersions rowVersions = ownerRowVersions(fixture.dsl, receipt);
                        firstBackend.complete(backendPid(fixture.dsl));
                        assertThat(allowFirstCommit.await(30, TimeUnit.SECONDS))
                            .as("release of the first creation transaction")
                            .isTrue();
                        return new OwnerWrite(receipt, rowVersions);
                      }));
      int firstPid = firstBackend.get(10, TimeUnit.SECONDS);

      Future<FreshTenantCreationEvidence> second =
          executor.submit(
              () ->
                  fixture.inTransaction(
                      () -> {
                        fixture.dsl.execute("SET LOCAL statement_timeout = '15s'");
                        secondBackend.complete(backendPid(fixture.dsl));
                        return createCandidate(fixture);
                      }));
      int secondPid = secondBackend.get(10, TimeUnit.SECONDS);
      assertBackendWaitsForOwner(fixture.dsl, secondPid, firstPid);
      assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isEmpty();
      assertThat(fixture.dsl.fetchCount(GAME)).isZero();
      allowFirstCommit.countDown();

      OwnerWrite committed = first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(committed.receipt());
      assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(committed.receipt());
      FreshTenantCreationEvidence exactRetry =
          fixture.inTransaction(() -> createCandidate(fixture));
      assertThat(exactRetry).isEqualTo(committed.receipt());

      RowVersions rowVersions = ownerRowVersions(fixture.dsl, committed.receipt());
      assertThat(rowVersions).isEqualTo(committed.rowVersions());
      assertThat(rowVersions.game()).isEqualTo(rowVersions.operation());
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void claimWithoutCompletionCannotCommitAndSameRequestCanRetry() {
    Fixture fixture = fixture();
    String requestDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.dsl.execute(
                            """
                            INSERT INTO game_tenant_creation_operations
                              (operation_id, schema_version, target_namespace, creation_request_id,
                               request_digest, source_game_tenant_key, name, status)
                            VALUES (?, 1, ?, ?, ?, ?, ?, 'PENDING')
                            """,
                            UUID.randomUUID(),
                            NAMESPACE,
                            REQUEST_ID,
                            requestDigest,
                            SOURCE_KEY,
                            NAME)))
        .isInstanceOf(TransactionSystemException.class)
        .hasStackTraceContaining("Game tenant creation operation cannot commit while pending");

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isEmpty();
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    FreshTenantCreationEvidence retry = fixture.inTransaction(() -> createCandidate(fixture));
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(retry);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void completedCreationEvidenceCannotBeChangedOrDeleted() {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null));

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(OPERATIONS)
                    .set(DSL.field(DSL.name("name"), String.class), "Changed")
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(OPERATIONS)
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  private static FreshTenantCreationEvidence createCandidate(Fixture fixture) {
    return fixture.repository.createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null);
  }

  private static int backendPid(DSLContext dsl) {
    return Objects.requireNonNull(dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
  }

  private static void assertBackendWaitsForOwner(DSLContext dsl, int contenderPid, int ownerPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          dsl.fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", ownerPid, contenderPid)
              .get(0, Boolean.class))) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("The duplicate creation never waited for the original transaction");
  }

  private static RowVersions ownerRowVersions(DSLContext dsl, FreshTenantCreationEvidence receipt) {
    var row =
        dsl.fetchSingle(
            """
            SELECT g.xmin::text AS game_xmin, op.xmin::text AS operation_xmin
              FROM game g
              JOIN game_tenant_creation_operations op
                ON op.source_game_row_id = g.id AND op.canonical_tenant_id = g.canonical_tenant_id
             WHERE g.id = ? AND g.tenant_id = ? AND g.canonical_tenant_id = ?
               AND op.operation_id = ? AND op.status = 'COMPLETED'
            """,
            receipt.sourceGameRowId(),
            receipt.sourceGameTenantKey(),
            receipt.canonicalTenantId(),
            receipt.operationId());
    return new RowVersions(
        row.get("game_xmin", String.class), row.get("operation_xmin", String.class));
  }

  private Fixture fixture() {
    String schema = "game_design_creation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource();
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_game_design_service")
        .placeholders(Map.of("serviceSchema", schema))
        .locations(
            "filesystem:"
                + java.nio.file.Path.of("src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();

    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    return new Fixture(dsl, repository, transactionTemplate);
  }

  private record OwnerWrite(FreshTenantCreationEvidence receipt, RowVersions rowVersions) {}

  private record RowVersions(String game, String operation) {}

  private record Fixture(
      DSLContext dsl,
      GameTenantCreationRepository repository,
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
  }
}
