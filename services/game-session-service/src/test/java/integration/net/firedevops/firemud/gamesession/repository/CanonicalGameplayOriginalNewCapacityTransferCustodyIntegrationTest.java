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
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayOriginalNewCapacityTransferCustodyIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Test
  void ownerInstallUsesFreshReadCommittedSnapshotAfterWaitingForAdvisoryLock() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var blocker =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
      repeatableReadSource.setDriverClassName(POSTGRES.getDriverClassName());
      String url = POSTGRES.getJdbcUrl();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      repeatableReadSource.setUrl(
          url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
      repeatableReadSource.setUsername(POSTGRES.getUsername());
      repeatableReadSource.setPassword(POSTGRES.getPassword());
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
  void retainedDenialReleasesTheLastSlotAndCannotRaceAStoredDispatch() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3)) {
      var original = proof.execution;
      var later = proof.newControlledExecution();
      assertThatThrownBy(() -> proof.precharge(later)).isInstanceOf(IllegalStateException.class);
      var denial = UUID.randomUUID();
      proof.settle(original, denial);
      proof.settle(original, denial);
      assertThatThrownBy(() -> proof.settle(original, UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> proof.insertExecution(original, "INSTALL_DISPATCHED", null))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Exact retained capacity evidence charge required");

      proof.seedUndeliveredDispatch(later);
      assertThatThrownBy(() -> proof.settle(later, UUID.randomUUID()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Execution custody prevents capacity evidence settlement");
      assertThat(proof.executionState(later)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.executionState(original)).isNull();
      assertThat(proof.physicalExecutionExists(original)).isFalse();
    }
  }

  @Test
  void retainedDenialHistoryRemainsBoundedIndependentlyOfActiveSlots() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 2)) {
      proof.settle(proof.execution, UUID.randomUUID());
      var next = proof.newControlledExecution();
      proof.precharge(next);
      proof.settle(next, UUID.randomUUID());
      assertThatThrownBy(() -> proof.precharge(proof.newControlledExecution()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
    }
  }

  @Test
  void postgresSerializesTheLastEvidenceChargeAcrossConcurrentAttempts() throws Exception {
    try (var proof = withCapacityBudget(2, 2_097_152L);
        var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = proof.newControlledExecution();
      var second = proof.newControlledExecution();
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      var assignment =
          proof.tags.assignmentForTransition(proof.admission.candidate().bindingDecisionId());
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              proof.execution.selectedOwnerId(), proof.execution.selectedOwnerFence());
      java.util.function.Function<
              OriginalNewCapacityExecution, java.util.concurrent.Future<Boolean>>
          compete =
              attempt ->
                  callers.submit(
                      () -> {
                        ready.countDown();
                        if (!start.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                          throw new IllegalStateException("Concurrent proof start unavailable");
                        }
                        try {
                          proof.sql.transaction(
                              configuration ->
                                  CanonicalGameplayCapacityEvidenceBudget.precharge(
                                      DSL.using(configuration),
                                      attempt.request(),
                                      attempt.candidateDigest(),
                                      selected,
                                      assignment.tenantGameplayTag()));
                          return true;
                        } catch (IllegalStateException refused) {
                          assertThat(refused)
                              .hasMessage("Original NEW capacity evidence budget is unavailable");
                          return false;
                        }
                      });
      var firstResult = compete.apply(first);
      var secondResult = compete.apply(second);
      assertThat(ready.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              List.of(
                  firstResult.get(5, java.util.concurrent.TimeUnit.SECONDS),
                  secondResult.get(5, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE tenant_id=?",
                      assignment.tenantId())
                  .get(0, Long.class))
          .isEqualTo(2L);
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
      Future<UUID> freeze =
          executor.submit(
              () ->
                  gate.attach(proof.sql)
                      .transactionResult(
                          configuration -> {
                            DSLContext transaction = DSL.using(configuration);
                            transaction.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                            transaction.fetchSingle(
                                "SELECT 1 FROM game_session_region_runtime_status"
                                    + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?"
                                    + " FOR UPDATE",
                                proof.scope.tenantId(),
                                proof.scope.gameInstanceId(),
                                proof.scope.regionId());
                            return proof.insertRawFrozenTransfer(transaction);
                          }));
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
      proof.insertRawTransferItem(proof.sql, id, 0, seeded);
      assertThat(proof.transferExecutionDigests(id)).containsExactly(seeded.digest());
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
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
      proof.insertRawTransferItem(proof.sql, transferId, 0);
      assertThat(proof.transferExecutionDigests(transferId))
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
                assertThat(failure.getMessage())
                    .contains("Region dispatch requires READ COMMITTED owner fencing");
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
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
      UUID transferId = proof.insertRawFrozenTransfer(proof.sql);
      var inserted = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      Future<?> itemInsert =
          executor.submit(
              () -> {
                try (var connection =
                    java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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
                                    "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                                    transferId);
                                tx.execute(
                                    "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
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
                      "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
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
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
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

  @Test
  void fullTransferItemBudgetAllowsReservedCompletionButRefusesTheNextFreeze() throws Exception {
    try (var proof = withTransferHistoryBudget(2, 1)) {
      proof.seedUndeliveredDispatch(proof.execution);
      String originalExecution = proof.originalImage();
      UUID id = proof.insertRawFrozenTransfer(proof.sql);
      assertThat(id).isNotNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT reserved_execution_items FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      id)
                  .get(0, Integer.class))
          .isEqualTo(1);
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      assertTransferHistoryGuard(
          () -> proof.insertRawTransferItem(proof.sql, id, 1),
          "Transfer item is outside its reserved inventory");
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      proof.insertRawTransferItem(proof.sql, id, 0);
      assertThat(proof.transferExecutionDigests(id)).containsExactly(proof.execution.digest());
      proof.retainControlledTransferReceipt(id);
      var installed = proof.rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64), false);
      String completed = proof.transferImage(id);
      String items = proof.itemImage(id);
      assertTransferHistoryGuard(
          () -> proof.insertRawFrozenTransfer(proof.sql, proof.scope, installed),
          "Transfer history cannot reserve its full execution inventory");
      assertThat(proof.originalImage()).isEqualTo(originalExecution);
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
      assertThat(proof.itemImage(id)).isEqualTo(items);
    }
  }

  @Test
  void capturedOriginalThatBecomesTerminalStillRequiresQualifiedTransferCompletion()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      try (var readback =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                  proof.redis, proof.scripts, proof.clock, proof.tags)
              .finalizeOriginalNewOnce(
                  proof.admission,
                  template,
                  account,
                  issuer,
                  proof.execution,
                  transferDeadline())) {
        UUID id = proof.insertRawFrozenTransfer(proof.sql);
        proof.insertRawTransferItem(proof.sql, id, 0);
        assertThat(proof.transferExecutionDigests(id)).containsExactly(proof.execution.digest());
        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(readback);
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        String frozen = proof.transferImage(id);
        String inventory = proof.itemImage(id);
        assertThatThrownBy(
                () -> proof.rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64), true))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains(
                          "Captured region transfer item lacks its full exact current receipt");
                });
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transferImage(id)).isEqualTo(frozen);
        assertThat(proof.itemImage(id)).isEqualTo(inventory);
        var status = new CanonicalGameplayRegionRuntimeStatusRepository(proof.sql);

        assertThatThrownBy(
                () ->
                    status.installOwner(
                        proof.scope,
                        proof.prior.regionEpoch(),
                        proof.prior,
                        "e".repeat(64),
                        "game-session-service",
                        "sql-proof-successor",
                        proof.clock.instant()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("region ownership is unavailable");
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transferImage(id)).isEqualTo(frozen);
        assertThat(proof.itemImage(id)).isEqualTo(inventory);
      }
    }
  }

  @Test
  void originalCapacityExecutionRefusesForgedInitialAuthorizationAndTerminalEvidence()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      byte[] forgedReadback = {1};
      for (String status : List.of("AUTHORIZED", "COMMITTED", "CLOSED", "INSTALL_DISPATCHED")) {
        byte[] readback = "AUTHORIZED".equals(status) ? null : forgedReadback;
        assertThatThrownBy(() -> proof.insertExecution(proof.execution, status, readback))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining(
                "Original NEW capacity execution cannot start with an authorization or terminal proof");
      }
      assertThat(proof.executionState(proof.execution)).isNull();
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);

      proof.seedUndeliveredDispatch(proof.execution);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
    }
  }

  @Test
  void rawOwnerReplacementCannotSkipFrozenIncompleteOrChangedExactTransfer() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      proof.seedUndeliveredDispatch(proof.execution);
      String original = proof.originalImage();
      assertSqlGuard(() -> proof.rawReplaceOwner("unretained-successor", "e".repeat(64)));
      String forgedOwner = "forged-successor";
      String forgedLease = "e".repeat(64);
      long forgedFence =
          proof
              .sql
              .fetchSingle("SELECT nextval('game_session_region_executor_fence_seq')")
              .get(0, Long.class);
      for (String state : List.of("COMPLETED", "FROZEN")) {
        boolean completed = "COMPLETED".equals(state);
        assertThatThrownBy(
                () ->
                    proof.sql.execute(
                        "INSERT INTO game_session_region_owner_transfer"
                            + " (transfer_id,tenant_id,game_instance_id,region_id,region_epoch,old_owner_id,old_owner_fence,"
                            + " old_lease_correlation,source_digest,intent_digest,state,incarnation_digest,verification_round,"
                            + " verification_cursor,verification_complete,new_owner_id,new_owner_fence,new_lease_correlation)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,?,?,?)",
                        UUID.randomUUID(),
                        proof.scope.tenantId(),
                        proof.scope.gameInstanceId(),
                        proof.scope.regionId(),
                        proof.prior.regionEpoch(),
                        proof.prior.ownerInstanceId(),
                        proof.prior.executorFence().longValueExact(),
                        proof.prior.leaseTokenCorrelation(),
                        "sha256:" + "b".repeat(64),
                        "sha256:" + "c".repeat(64),
                        state,
                        "sha256:" + "a".repeat(64),
                        UUID.randomUUID(),
                        0L,
                        completed ? forgedOwner : null,
                        completed ? forgedFence : null,
                        completed ? forgedLease : null))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("Region transfer must start as a clean frozen intent");
      }
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_runtime_status SET owner_instance_id=?,executor_fence=?,lease_token_correlation=?"
                      + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
                  forgedOwner,
                  forgedFence,
                  forgedLease,
                  proof.scope.tenantId(),
                  proof.scope.gameInstanceId(),
                  proof.scope.regionId()));
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      UUID id = proof.insertRawFrozenTransfer(proof.sql);
      assertSqlGuard(() -> proof.rawReplaceOwner("frozen-successor", "e".repeat(64)));
      proof.insertRawTransferItem(proof.sql, id, 0);
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                      + " new_owner_id='forged-successor',new_owner_fence=?,new_lease_correlation=?,"
                      + " incarnation_digest=?,verification_round=?,verification_complete=true WHERE transfer_id=?",
                  proof.prior.executorFence().longValueExact() + 1,
                  "e".repeat(64),
                  "sha256:" + "a".repeat(64),
                  UUID.randomUUID(),
                  id));
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.transferExecutionDigests(id)).containsExactly(proof.execution.digest());
      proof.sql.execute(
          "UPDATE game_session_region_owner_transfer_item"
              + " SET fence_dispatch_id=?,fence_dispatch_incarnation=? WHERE transfer_id=?",
          UUID.randomUUID(),
          "sha256:" + "a".repeat(64),
          id);
      String dispatched = proof.itemImage(id);
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration -> {
                        var transaction = org.jooq.impl.DSL.using(configuration);
                        var changed = "sha256:" + "b".repeat(64);
                        var round = UUID.randomUUID();
                        transaction.execute(
                            "UPDATE game_session_region_owner_transfer SET incarnation_digest=?,verification_round=? WHERE transfer_id=?",
                            changed,
                            round,
                            id);
                        transaction.execute(
                            "UPDATE game_session_region_owner_transfer_item SET fence_readback=?,incarnation_digest=?,before_payload_digest=?,after_payload_digest=?,verified_round=? WHERE transfer_id=?",
                            new byte[] {1},
                            changed,
                            changed,
                            changed,
                            round,
                            id);
                      }))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("gs_region_fence_receipt_dispatch_incarnation");
      assertThat(proof.itemImage(id)).isEqualTo(dispatched);

      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET fence_dispatch_id=?"
                      + " WHERE transfer_id=?",
                  UUID.randomUUID(),
                  id));
      assertThatThrownBy(
              () -> proof.rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64), true))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region transfer requires settled fence dispatch custody");
              });
      assertThat(proof.itemImage(id)).isEqualTo(dispatched);
      proof.retainControlledTransferReceipt(id);
      var installed = proof.rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64), false);
      String completed = proof.transferImage(id);
      assertSqlGuard(() -> proof.rawReplaceOwner("different-successor", "f".repeat(64)));
      var lateExecution = proof.newControlledExecution();
      proof.retainExactChargeForControlledSeed(lateExecution);
      assertThatThrownBy(() -> proof.seedUndeliveredDispatch(lateExecution))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region dispatch requires the exact current selected owner");
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
      assertThat(proof.originalImage()).isEqualTo(original);
    }
  }
}
