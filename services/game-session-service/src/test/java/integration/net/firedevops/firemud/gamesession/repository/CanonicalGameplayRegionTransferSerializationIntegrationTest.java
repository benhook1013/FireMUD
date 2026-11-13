package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionTransferSerializationIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

  @Test
  void ownerInstallUsesFreshReadCommittedSnapshotAfterWaitingForAdvisoryLock() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var blocker =
            java.sql.DriverManager.getConnection(
                postgresContainer().getJdbcUrl(),
                postgresContainer().getUsername(),
                postgresContainer().getPassword())) {
      var dispatched = proof.newControlledExecution();
      String tag =
          CanonicalGameplayRegionRedisKeys.tenantRegionTag(
              proof.scope.tenantId(), proof.scope.gameInstanceId(), proof.scope.regionId());
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        lock.setString(1, "game-session-region-owner-install:v1:" + tag);
        lock.execute();
      }
      int blockerPid;
      try (var statement = blocker.createStatement();
          var result = statement.executeQuery("SELECT pg_backend_pid()")) {
        assertThat(result.next()).isTrue();
        blockerPid = result.getInt(1);
      }

      var repeatableReadSource =
          new DriverManagerDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
              java.sql.Connection connection = super.getConnection();
              connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
              return connection;
            }
          };
      repeatableReadSource.setDriverClassName(postgresContainer().getDriverClassName());
      String url = postgresContainer().getJdbcUrl();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      repeatableReadSource.setUrl(
          url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
      repeatableReadSource.setUsername(postgresContainer().getUsername());
      repeatableReadSource.setPassword(postgresContainer().getPassword());
      var repeatableReadRepository =
          new CanonicalGameplayRegionRuntimeStatusRepository(
              DSL.using(repeatableReadSource, SQLDialect.POSTGRES));
      assertThat(repeatableReadRepository.find(proof.scope)).contains(proof.prior);
      Future<?> install =
          executor.submit(
              () ->
                  repeatableReadRepository.installOwner(
                      proof.scope,
                      1L,
                      proof.prior,
                      "e".repeat(64),
                      "game-session-service",
                      "repeatable-read-successor",
                      Instant.parse("2026-10-26T00:00:03Z")));

      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid);
        proof.seedUndeliveredDispatch(dispatched);
      } finally {
        blocker.commit();
      }

      assertThatThrownBy(install::get)
          .rootCause()
          .isInstanceOfSatisfying(
              IllegalStateException.class,
              failure ->
                  assertThat(failure)
                      .hasMessage("Canonical Game Session region ownership is unavailable"));
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(dispatched)).isEqualTo("INSTALL_DISPATCHED");
    }
  }

  @Test
  void actualAuthorizationHoldingRegionRowMakesFreezeWaitAndRetainsThatOriginal() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();
      Future<UUID> freeze =
          executor.submit(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> proof.transfers.begin(proof.scope, proof.prior)));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(freeze.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      UUID id = freeze.get(10, TimeUnit.SECONDS);
      assertThat(id).isNotNull();
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void actualFreezeUsesReadCommittedWithRepeatableReadDatasourceAfterAuthorizationWaits()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();

      var repeatableReadSource =
          new DriverManagerDataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
              java.sql.Connection connection = super.getConnection();
              connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
              return connection;
            }
          };
      repeatableReadSource.setDriverClassName(postgresContainer().getDriverClassName());
      String url = postgresContainer().getJdbcUrl();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      repeatableReadSource.setUrl(
          url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
      repeatableReadSource.setUsername(postgresContainer().getUsername());
      repeatableReadSource.setPassword(postgresContainer().getPassword());
      var repeatableReadTransfers =
          new CanonicalGameplayRegionOwnerTransferRepository(
              DSL.using(repeatableReadSource, SQLDialect.POSTGRES), proof.clock, proof.tags);
      Future<UUID> freeze =
          executor.submit(
              () ->
                  repeatableReadTransfers.within(
                      transferDeadline(),
                      ignored -> repeatableReadTransfers.begin(proof.scope, proof.prior)));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(freeze.isDone()).isFalse();
      } finally {
        gate.release();
      }

      authorization.get(10, TimeUnit.SECONDS);
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      assertThat(transferId).isNotNull();
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(transferId));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void freezeHoldingRegionRowMakesActualAuthorizationWaitThenRefuseBeforeDispatch()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var seeded = proof.newControlledExecution();
      proof.seedUndeliveredDispatch(seeded);
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      var freezing = proof.transfers(gate.attach(proof.sql));
      Future<UUID> freeze =
          executor.submit(
              () ->
                  freezing.within(
                      transferDeadline(), ignored -> freezing.begin(proof.scope, proof.prior)));
      gate.awaitLock();
      Future<Boolean> refused =
          executor.submit(
              () -> {
                try {
                  proof
                      .capacity(proof.sql)
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline());
                  return false;
                } catch (IllegalStateException frozen) {
                  return true;
                }
              });
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(refused.isDone()).isFalse();
      } finally {
        gate.release();
      }
      UUID id = freeze.get(10, TimeUnit.SECONDS);
      assertThat(refused.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.executionState(seeded)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      // The INSERT trigger independently takes the same row lock and rejects raw late dispatch.
      assertSqlGuard(() -> proof.seedUndeliveredDispatch(proof.execution));
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(seeded.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void rawFrozenTransferInsertLocksExactCurrentRegionBeforeLateCapacityDispatch() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> freeze =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        postgresContainer().getJdbcUrl(),
                        postgresContainer().getUsername(),
                        postgresContainer().getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Raw frozen-insert gate was not released");
                  connection.commit();
                  return transferId;
                }
              });
      awaitGate(inserted, freeze, "Raw frozen insert did not reach its transaction gate");
      Future<Boolean> refused =
          executor.submit(
              () -> {
                try {
                  proof
                      .capacity(proof.sql)
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline());
                  return false;
                } catch (IllegalStateException frozen) {
                  return true;
                }
              });
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(refused.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      assertThat(refused.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
    }
  }

  @Test
  void rawFrozenTransferInsertWaitsForActualDispatchAndRetainsIt() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> freeze =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        postgresContainer().getJdbcUrl(),
                        postgresContainer().getUsername(),
                        postgresContainer().getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  connection.commit();
                  return transferId;
                }
              });
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(freeze.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      var captured =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(transferId));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void rawFrozenInsertAndCapacityDispatchRejectRepeatableRead() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration -> {
                        DSLContext tx = DSL.using(configuration);
                        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                        proof.insertRawFrozenTransfer(tx);
                      }))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region transfer freeze requires READ COMMITTED owner fencing");
              });
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration -> {
                        DSLContext tx = DSL.using(configuration);
                        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                        proof.insertExecution(tx, proof.execution, "INSTALL_DISPATCHED", null);
                      }))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage()).contains("requires READ COMMITTED");
              });
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isZero();
    }
  }

  @Test
  void rawOwnerReplacementRejectsSnapshotThatPredatesCommittedDispatch() throws Exception {
    try (var proof = new TransferSqlProof()) {
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      try (var connection =
          java.sql.DriverManager.getConnection(
              postgresContainer().getJdbcUrl(),
              postgresContainer().getUsername(),
              postgresContainer().getPassword())) {
        connection.setSchema(schema);
        connection.setAutoCommit(false);
        DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
        tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        assertThat(
                tx.fetchSingle("SELECT count(*) FROM game_session_original_new_capacity_execution")
                    .get(0, Long.class))
            .isZero();

        proof.seedUndeliveredDispatch(proof.execution);
        assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
        assertThatThrownBy(
                () -> proof.rawReplaceOwner(tx, "stale-snapshot-successor", "e".repeat(64)))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Region owner replacement requires READ COMMITTED owner fencing");
                });
        connection.rollback();
      }
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
    }
  }

  @Test
  void rawTransferChildInsertSerializesWithCompletionAcrossCurrentAndOlderSnapshots()
      throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID transferId = proof.begin();
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<?> itemInsert =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        postgresContainer().getJdbcUrl(),
                        postgresContainer().getUsername(),
                        postgresContainer().getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  proof.insertRawTransferItem(rawSql, transferId, 0);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Raw transfer-item gate was not released");
                  connection.commit();
                }
                return null;
              });
      awaitGate(
          inserted, itemInsert, "Raw transfer-item insert did not reach its transaction gate");
      Future<String> oldSnapshotCompletion =
          executor.submit(
              () ->
                  sqlStateOf(
                      () ->
                          proof.sql.transaction(
                              configuration -> {
                                DSLContext tx = DSL.using(configuration);
                                tx.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                                tx.fetchSingle(
                                    "SELECT state FROM game_session_region_owner_transfer WHERE"
                                        + " transfer_id=?",
                                    transferId);
                                tx.execute(
                                    "UPDATE game_session_region_owner_transfer SET"
                                        + " state='COMPLETED',"
                                        + "new_owner_id='sql-proof-successor',new_owner_fence=?,new_lease_correlation=?"
                                        + " WHERE transfer_id=?",
                                    proof.prior.executorFence().longValueExact() + 1,
                                    "e".repeat(64),
                                    transferId);
                              })));
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(oldSnapshotCompletion.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      itemInsert.get(10, TimeUnit.SECONDS);
      assertThat(oldSnapshotCompletion.get(10, TimeUnit.SECONDS)).isEqualTo("40001");
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                      + "new_owner_id='sql-proof-successor',new_owner_fence=?,new_lease_correlation=?"
                      + " WHERE transfer_id=?",
                  proof.prior.executorFence().longValueExact() + 1,
                  "e".repeat(64),
                  transferId));
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE"
                          + " transfer_id=?",
                      transferId)
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void acknowledgedBridgeRetryWaitsBeforeTransitionWhileAuthorizationOwnsRegion() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution);
        var gate = new RegionRowLockGate();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<?> authorization =
          executor.submit(
              () ->
                  proof
                      .capacity(gate.attach(proof.sql))
                      .authorize(
                          proof.admission,
                          template,
                          account,
                          issuer,
                          proof.execution,
                          transferDeadline()));
      gate.awaitLock();
      Future<?> replay = executor.submit(proof::replayBridge);
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(replay.isDone()).isFalse();
      } finally {
        gate.release();
      }
      authorization.get(10, TimeUnit.SECONDS);
      replay.get(10, TimeUnit.SECONDS);
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void frozenContinuationWaitsBeforeRegionWhileAcknowledgedBridgeOwnsInventory() throws Exception {
    try (var gate = new InventoryRowLockGate();
        var proof = new TransferSqlProof(gate);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured).hasSize(1);
      var original =
          proof.transfers.within(
              transferDeadline(),
              ignored -> proof.transfers.frozenOriginal(proof.execution.transitionId()));
      String before = proof.transferImage(id);
      gate.arm();
      Future<?> replay = executor.submit(proof::replayBridge);
      gate.awaitLock();
      Future<?> continuation =
          executor.submit(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> {
                        proof.transfers.requireFrozenOriginal(original, proof.scope, proof.prior);
                        return null;
                      }));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(continuation.isDone()).isFalse();
      } finally {
        gate.release();
      }
      replay.get(10, TimeUnit.SECONDS);
      continuation.get(10, TimeUnit.SECONDS);
      assertThat(proof.transferImage(id)).isEqualTo(before);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void competingTransferCreatorsAndLostBeginReplyKeepOneOriginalIntent() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var other = proof.transfers(proof.sql);
      Future<UUID> first =
          executor.submit(() -> beginAfterBarrier(proof, proof.transfers, ready, start));
      Future<UUID> second = executor.submit(() -> beginAfterBarrier(proof, other, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      UUID retained = first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(retained);
      String original = proof.transferImage(retained);
      assertThatThrownBy(
              () -> {
                other.within(transferDeadline(), ignored -> other.begin(proof.scope, proof.prior));
                throw new IllegalStateException(
                    "Controlled lost begin reply after committed readback");
              })
          .hasMessage("Controlled lost begin reply after committed readback");
      var recreated = proof.transfers(proof.sql);
      UUID replayed =
          recreated.within(
              transferDeadline(), ignored -> recreated.begin(proof.scope, proof.prior));
      assertThat(replayed).isEqualTo(retained);
      assertThat(proof.transferImage(retained)).isEqualTo(original);
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void postgresSerializesTheLastTransferParentAcrossRegionsOfTheSameTenant() throws Exception {
    try (var proof = withTransferHistoryBudget(1, 2);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      proof.seedUndeliveredDispatch(proof.execution);
      var otherScope =
          new CanonicalGameplayRegionRuntimeStatusRepository.Source(
              proof.scope.tenantId(),
              proof.scope.gameInstanceId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              proof.scope.lifecycleEpoch(),
              proof.scope.lifecycleRowVersion(),
              proof.scope.lifecycleRequest(),
              proof.scope.captureId(),
              proof.scope.graphSha256(),
              proof.scope.preparationInputDigest());
      var status = new CanonicalGameplayRegionRuntimeStatusRepository(proof.sql);
      var otherPrior =
          status.installOwner(
              otherScope,
              1L,
              null,
              "f".repeat(64),
              "game-session-service",
              "sql-proof-other-region",
              proof.clock.instant());
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<UUID> first =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        postgresContainer().getJdbcUrl(),
                        postgresContainer().getUsername(),
                        postgresContainer().getPassword())) {
                  connection.setSchema(schema);
                  connection.setAutoCommit(false);
                  DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
                  rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                  UUID transferId = proof.insertRawFrozenTransfer(rawSql);
                  blockerPid.set(
                      rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                  inserted.countDown();
                  if (!release.await(4, TimeUnit.SECONDS))
                    throw new AssertionError("Transfer history reservation gate was not released");
                  connection.commit();
                  return transferId;
                }
              });
      awaitGate(inserted, first, "First transfer did not reserve the tenant history slot");
      var second =
          executor.submit(
              () ->
                  assertTransferHistoryGuard(
                      () -> proof.insertRawFrozenTransfer(proof.sql, otherScope, otherPrior),
                      "Transfer history is exhausted"));
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(second.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      UUID winner = first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      var retained =
          proof.sql.fetchSingle(
              "SELECT transfer_id,region_id,state,reserved_execution_items"
                  + " FROM game_session_region_owner_transfer WHERE tenant_id=?",
              proof.scope.tenantId());
      assertThat(retained.get("transfer_id", UUID.class)).isEqualTo(winner);
      assertThat(retained.get("region_id", UUID.class)).isEqualTo(proof.scope.regionId());
      assertThat(retained.get("state", String.class)).isEqualTo("FROZEN");
      assertThat(retained.get("reserved_execution_items", Integer.class)).isEqualTo(1);
      assertThat(proof.itemImage(winner)).isEqualTo("[]");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(status.find(otherScope)).contains(otherPrior);
    }
  }
}
