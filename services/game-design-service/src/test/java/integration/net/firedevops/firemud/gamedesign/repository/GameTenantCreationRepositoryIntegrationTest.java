package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GameTenantCreationRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final org.jooq.Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final org.jooq.Field<Integer> SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final org.jooq.Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final org.jooq.Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final org.jooq.Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final org.jooq.Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final org.jooq.Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final String NAMESPACE = "fresh-tenant-test";
  private static final String SOURCE_KEY = "new-game-tenant-01";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void createCandidateExactRetryReturnsCommittedReceiptWithoutSecondGameWrite() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence first =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));
    String firstGameXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(first);
    FreshTenantCreationEvidence exactRetry =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(exactRetry).isEqualTo(first);
    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(firstGameXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void runtimeIdentityLookupReadsOnlyExactNewAndRetainedGameDesignRows() throws Exception {
    RetainedFixture retainedFixture = fixtureWithRetainedGame();
    Fixture fixture = retainedFixture.fixture();
    String retainedTenantKey = "retained-game-tenant-9001";
    UUID retainedTenantId =
        fixture.dsl
            .select(CANONICAL_TENANT_ID)
            .from(GAME)
            .where(GAME_ID.eq(retainedFixture.retainedGameRowId()))
            .fetchOne(CANONICAL_TENANT_ID);
    assertThat(retainedTenantId).isNotNull();
    String retainedXmin = gameXmin(fixture.dsl, retainedTenantKey);

    FreshTenantCreationEvidence freshReceipt =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Runtime Identity", null));
    String freshXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(
                freshReceipt.canonicalTenantId()))
        .contains(
            new GameTenantIdentity(
                freshReceipt.canonicalTenantId(),
                GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                freshReceipt.sourceGameRowId(),
                SOURCE_KEY));
    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(retainedTenantId))
        .contains(
            new GameTenantIdentity(
                retainedTenantId,
                GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                retainedFixture.retainedGameRowId(),
                retainedTenantKey));
    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))
        .isEmpty();

    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(freshXmin);
    assertThat(gameXmin(fixture.dsl, retainedTenantKey)).isEqualTo(retainedXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void changedPayloadConflictsAndNonTransactionalConstructionCannotWrite() throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Game Design owner transaction");
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> fixture.repository.read(REQUEST_ID, NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isEmpty();
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    fixture.inTransaction(
        () -> fixture.repository.createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null));
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "Changed World", null)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("reused with changed input");
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isPresent();
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void candidateEnforcesGameBoundsAndAcceptsMaximalMultibyteDescription() throws Exception {
    Fixture fixture = fixture();
    String malformedName = "bad" + (char) 0xD800;
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, "x".repeat(73), "World", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceGameTenantKey");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "x".repeat(201), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", "x".repeat(256))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", "😀".repeat(256))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, malformedName, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");

    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    String maximalMultibyteDescription = "😀".repeat(255);
    assertThat(
            fixture.inTransaction(
                () ->
                    fixture.repository.createCandidate(
                        NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", maximalMultibyteDescription)))
        .isNotNull();
    assertThat(
            fixture
                .dsl
                .select(DESCRIPTION)
                .from(GAME)
                .where(TENANT_ID.eq(SOURCE_KEY))
                .fetchOne(DESCRIPTION))
        .isEqualTo(maximalMultibyteDescription);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void rollbackAndClaimOnlyAttemptsLeaveNoPersistedOperation() throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture.repository.createCandidate(
                          NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null);
                      throw new IllegalStateException("simulate lost owner transaction");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulate lost owner transaction");
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture
                          .dsl
                          .insertInto(OPERATIONS)
                          .set(OPERATION_ID, UUID.randomUUID())
                          .set(SCHEMA_VERSION, 1)
                          .set(TARGET_NAMESPACE, NAMESPACE)
                          .set(
                              CREATION_REQUEST_ID,
                              UUID.fromString("22222222-2222-4222-8222-222222222222"))
                          .set(
                              REQUEST_DIGEST,
                              GameTenantCreationDigest.requestDigest(
                                  NAMESPACE,
                                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                                  "claim-only",
                                  "World",
                                  null))
                          .set(SOURCE_GAME_TENANT_KEY, "claim-only")
                          .set(NAME, "World")
                          .set(STATUS, "PENDING")
                          .execute();
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("cannot commit while pending");
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    assertThat(
            fixture.inTransaction(
                () ->
                    fixture.repository.createCandidate(
                        NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null)))
        .isNotNull();
  }

  @Test
  void completedOperationCannotBeUpdatedOrDeleted() throws Exception {
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
                    .set(NAME, "Changed")
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

  @Test
  void readFailsClosedWhenGameSourceTupleChangesOrSourceRowDisappears() throws Exception {
    Fixture changedFixture = fixture();
    FreshTenantCreationEvidence changedReceipt =
        changedFixture.inTransaction(
            () ->
                changedFixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Changed Source", null));
    try (Connection connection = changedFixture.dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE game DISABLE TRIGGER trg_game_tenant_identity_immutable");
      changedFixture
          .dsl
          .update(GAME)
          .set(
              DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class),
              "RETAINED_GAME_V30")
          .where(GAME_ID.eq(changedReceipt.sourceGameRowId()))
          .execute();
      statement.execute("ALTER TABLE game ENABLE TRIGGER trg_game_tenant_identity_immutable");
    }
    assertThatThrownBy(
            () ->
                changedFixture.inTransaction(
                    () ->
                        changedFixture.repository.createCandidate(
                            NAMESPACE,
                            REQUEST_ID,
                            SOURCE_KEY,
                            "Changed request against damaged source",
                            null)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("reused with changed input");
    assertThatThrownBy(() -> changedFixture.repository.read(REQUEST_ID, NAMESPACE))
        .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
        .hasMessageContaining("source tuple no longer matches");

    Fixture missingFixture = fixture();
    FreshTenantCreationEvidence missingReceipt =
        missingFixture.inTransaction(
            () ->
                missingFixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Missing Source", null));
    try (Connection connection = missingFixture.dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE game DISABLE TRIGGER ALL");
      missingFixture
          .dsl
          .deleteFrom(GAME)
          .where(GAME_ID.eq(missingReceipt.sourceGameRowId()))
          .execute();
      statement.execute("ALTER TABLE game ENABLE TRIGGER ALL");
    }
    assertThatThrownBy(() -> missingFixture.repository.read(REQUEST_ID, NAMESPACE))
        .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
        .hasMessageContaining("source row is missing");
  }

  @Test
  void concurrentDuplicateCreationRequestsConvergeOnOneUuidAndReceipt() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch firstOperationCreated = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    CountDownLatch secondOperationStarted = new CountDownLatch(1);
    AtomicInteger firstBackendPid = new AtomicInteger();
    AtomicInteger secondBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FreshTenantCreationEvidence> first =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        FreshTenantCreationEvidence receipt =
                            fixture.repository.createCandidate(
                                NAMESPACE,
                                REQUEST_ID,
                                SOURCE_KEY,
                                "Concurrent World",
                                "same request");
                        firstBackendPid.set(currentBackendPid(fixture.dsl));
                        firstOperationCreated.countDown();
                        awaitLatch(allowFirstCommit);
                        return receipt;
                      }));
      assertThat(firstOperationCreated.await(10, TimeUnit.SECONDS)).isTrue();

      Future<FreshTenantCreationEvidence> second =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        secondBackendPid.set(currentBackendPid(fixture.dsl));
                        secondOperationStarted.countDown();
                        return fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "Concurrent World", "same request");
                      }));
      assertThat(secondOperationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseBlocking(fixture.dataSource, secondBackendPid.get(), firstBackendPid.get());
      allowFirstCommit.countDown();

      FreshTenantCreationEvidence firstReceipt = first.get(10, TimeUnit.SECONDS);
      FreshTenantCreationEvidence secondReceipt = second.get(10, TimeUnit.SECONDS);
      assertThat(secondReceipt).isEqualTo(firstReceipt);
      assertThat(secondReceipt.canonicalTenantId()).isEqualTo(firstReceipt.canonicalTenantId());
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  private Fixture fixture() {
    String schema = "game_design_creation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    assertThat(dsl.select(DSL.field("current_schema()", String.class)).fetchSingle().value1())
        .isEqualTo(schema);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    return new Fixture(dataSource, dsl, gameRepository, repository, transactionTemplate);
  }

  private RetainedFixture fixtureWithRetainedGame() {
    String schema =
        "game_design_retained_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);

    migrate(dataSource, schema, MigrationVersion.fromVersion("29"));
    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long retainedGameRowId =
        legacyDsl
            .insertInto(GAME)
            .set(TENANT_ID, "retained-game-tenant-9001")
            .set(NAME, "Retained Game Design Row")
            .set(DESCRIPTION, "Retained source identity fixture")
            .returning(GAME_ID)
            .fetchOne(GAME_ID);
    assertThat(retainedGameRowId).isNotNull();

    migrate(dataSource, schema, null);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    assertThat(dsl.select(DSL.field("current_schema()", String.class)).fetchSingle().value1())
        .isEqualTo(schema);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    Fixture fixture = new Fixture(dataSource, dsl, gameRepository, repository, transactionTemplate);
    return new RetainedFixture(fixture, retainedGameRowId);
  }

  private void migrate(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private String gameXmin(DSLContext dsl, String sourceGameTenantKey) {
    return dsl.fetch("SELECT xmin::text AS xmin FROM game WHERE tenant_id = ?", sourceGameTenantKey)
        .getFirst()
        .get("xmin", String.class);
  }

  private int currentBackendPid(DSLContext dsl) {
    var backendRecord = dsl.fetchOne("SELECT pg_backend_pid() AS pid");
    if (backendRecord == null) {
      throw new IllegalStateException("Could not read the PostgreSQL backend id");
    }
    Integer pid = backendRecord.get("pid", Integer.class);
    if (pid == null || pid <= 0) {
      throw new IllegalStateException("PostgreSQL returned an invalid backend id");
    }
    return pid;
  }

  private void awaitDatabaseBlocking(
      DriverManagerDataSource dataSource, int blockedBackendPid, int blockerBackendPid)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
      statement.setInt(1, blockerBackendPid);
      statement.setInt(2, blockedBackendPid);
      while (System.nanoTime() < deadline) {
        try (ResultSet resultSet = statement.executeQuery()) {
          resultSet.next();
          if (resultSet.getBoolean(1)) {
            return;
          }
        }
        Thread.yield();
      }
    }
    throw new AssertionError(
        "PostgreSQL did not report the duplicate request blocked on the first writer");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to commit the first tenant operation");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting to commit tenant operation", exception);
    }
  }

  private record Fixture(
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      GameRepository gameRepository,
      GameTenantCreationRepository repository,
      TransactionTemplate transactionTemplate) {
    private <T> T inTransaction(java.util.concurrent.Callable<T> work) throws Exception {
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

  private record RetainedFixture(Fixture fixture, long retainedGameRowId) {}
}
