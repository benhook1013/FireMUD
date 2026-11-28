package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayOriginalNewCapacityTransferCustodyIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Container private static final GenericContainer<?> REDIS = TransferContainers.REDIS;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void guardedReplacementUsesFreshReadCommittedSnapshotAfterWaitingForAdvisoryLock(
      boolean dispatchWhileWaiting) throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var blocker =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      var dispatched = proof.newIndependentControlledOriginExecution();
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
          url
              + (url.contains("?") ? "&" : "?")
              + "currentSchema="
              + schema
              + "&connectTimeout=3&socketTimeout=5");
      repeatableReadSource.setUsername(POSTGRES.getUsername());
      repeatableReadSource.setPassword(POSTGRES.getPassword());
      AtomicInteger installIsolation = new AtomicInteger(-1);
      var installObserver =
          new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
              if (!"SELECT pg_advisory_xact_lock(hashtextextended(?, 0))".equals(context.sql())) {
                return;
              }
              var statement = context.statement();
              if (statement == null) {
                throw new AssertionError(
                    "Install transaction did not prepare the advisory-lock statement");
              }
              try {
                installIsolation.set(statement.getConnection().getTransactionIsolation());
              } catch (SQLException failure) {
                throw new AssertionError(
                    "Could not read the install transaction isolation", failure);
              }
            }
          };
      DSLContext repeatableReadDsl = DSL.using(repeatableReadSource, SQLDialect.POSTGRES);
      DSLContext observingDsl =
          DSL.using(
              repeatableReadDsl
                  .configuration()
                  .derive(new DefaultExecuteListenerProvider(installObserver)));
      assertThat(new CanonicalGameplayRegionRuntimeStatusRepository(observingDsl).find(proof.scope))
          .contains(proof.prior);
      Future<?> install =
          executor.submit(() ->
              observingDsl.transaction(configuration -> {
                DSLContext tx = DSL.using(configuration);
                tx.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                tx.execute("SET LOCAL statement_timeout = '5s'");

                tx.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    "game-session-region-owner-install:v1:" + tag);
                proof.rawReplaceOwner(tx, "repeatable-read-successor", "e".repeat(64));
              }));

      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid);
        assertThat(installIsolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        if (dispatchWhileWaiting) proof.seedUndeliveredDispatch(dispatched);
      } finally {
        blocker.commit();
      }

      if (dispatchWhileWaiting) {
        assertThatThrownBy(() -> install.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Region owner replacement lacks exact transfer custody");
                });
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.executionState(dispatched)).isEqualTo("INSTALL_DISPATCHED");
      } else {
        install.get(10, TimeUnit.SECONDS);
        var successor = proof.currentOwner();
        assertThat(successor.ownerInstanceId()).isEqualTo("repeatable-read-successor");
        assertThat(successor.executorFence()).isGreaterThan(proof.prior.executorFence());
        assertThat(proof.executionState(dispatched)).isNull();
      }

      var current = proof.currentOwner();
      installIsolation.set(-1);
      assertThatThrownBy(() ->
              new CanonicalGameplayRegionRuntimeStatusRepository(observingDsl)
                  .installOwner(
                      proof.scope,
                      current.regionEpoch(),
                      current,
                      "f".repeat(64),
                      current.ownerService(),
                      "production-replacement",
                      proof.clock.instant()))
          .rootCause()
          .isInstanceOf(
              CanonicalGameplayRegionRuntimeStatusRepository.ExistingOwnerReplacementRefused.class);
      assertThat(installIsolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(proof.currentOwner()).isEqualTo(current);
    }
  }

  @Test
  void selectedOwnerSettlesExactDenialAfterRecordedAccountAbort() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3)) {
      var original = proof.execution;
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              original.selectedOwnerId(), original.selectedOwnerFence());
      var denial = new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, UUID.randomUUID());
      assertThat(proof.executionState(original)).isNull();
      assertThat(proof.fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();

      proof
          .capacity(proof.sql)
          .settleDeniedCharge(
              proof.admission, original.request(), selected, denial, transferDeadline());

      String chargeSql =
          "SELECT account_denial_id,account_denial_kind FROM game_session_capacity_evidence_charge"
              + " WHERE admission_attempt_id=?";
      var charge = proof.sql.fetchSingle(chargeSql, original.request().admissionAttemptId());
      assertThat(charge.get("account_denial_id", UUID.class)).isEqualTo(denial.durableDenialId());
      assertThat(charge.get("account_denial_kind", String.class)).isEqualTo(denial.code().name());
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge"
                          + " WHERE tenant_id=? AND account_denial_id IS NULL",
                      original.request().identity().tenantId())
                  .get(0, Long.class))
          .isZero();
      assertThat(proof.executionState(original)).isNull();
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void retainedDenialReleasesTheLastSlotAndCannotRaceAStoredDispatch() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3)) {
      var original = proof.execution;
      var later = proof.newIndependentControlledOriginExecution();
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void denialSettlementRequiresTheCurrentSelectedOwner(boolean ownerReplaced) throws Exception {
    try (var proof = new TransferSqlProof()) {
      var original = proof.execution;
      proof.precharge(original);
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              original.selectedOwnerId(), original.selectedOwnerFence());
      var denial = new Refusal(Failure.CAPACITY_EVALUATION_EXPIRED, UUID.randomUUID());
      Runnable settle =
          () ->
              proof
                  .capacity(proof.sql)
                  .settleDeniedCharge(
                      proof.admission, original.request(), selected, denial, transferDeadline());
      if (ownerReplaced) {
        proof.rawReplaceOwner("denial-settlement-successor", "e".repeat(64));
        assertThatThrownBy(settle::run)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity execution is indeterminate")
            .hasStackTraceContaining(
                "CanonicalGameplayOriginalNewCapacityExecutionOwner.requireSelectedOwner");
      } else {
        settle.run();
        settle.run();
        var restarted = proof.capacity(proof.sql);
        assertThat(
                restarted
                    .findExistingCharge(
                        proof.admission, original.request(), selected, transferDeadline())
                    .accountDenial())
            .isEqualTo(denial);
        var wrongKind = new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, denial.durableDenialId());
        assertThatThrownBy(() ->
                restarted.settleDeniedCharge(
                    proof.admission, original.request(), selected, wrongKind, transferDeadline()))
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity evidence budget is unavailable");
      }
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_denial_id FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      original.request().admissionAttemptId())
                  .get(0, UUID.class))
          .isEqualTo(ownerReplaced ? null : denial.durableDenialId());
    }
  }

  @Test
  void expiredFreshEvidenceCannotChargeButExactRetainedDenialStillReplays() throws Exception {
    try (var proof = new TransferSqlProof()) {
      var original = proof.execution;
      var fresh = proof.newIndependentControlledOriginExecution();
      var denial = UUID.randomUUID();
      proof.settle(original, denial);
      var tag = proof.tags.assignmentForTransition(original.transitionId()).tenantGameplayTag();
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              original.selectedOwnerId(), original.selectedOwnerFence());
      java.util.function.Function<OriginalNewCapacityExecution, Refusal> precharge =
          execution ->
              proof.sql.transactionResult(configuration ->
                  CanonicalGameplayCapacityEvidenceBudget.precharge(
                          DSL.using(configuration),
                          execution.request(),
                          execution.candidateDigest(),
                          selected,
                          tag,
                          Clock.fixed(
                              execution.request().evidence().expiresAt(), java.time.ZoneOffset.UTC))
                      .accountDenial());
      assertThatThrownBy(() -> precharge.apply(fresh))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
      assertThat(
              proof.sql.fetchOne(
                  "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                  fresh.request().admissionAttemptId()))
          .isNull();
      assertThat(precharge.apply(original))
          .isEqualTo(new Refusal(Failure.CAPACITY_QUOTA_EXCEEDED, denial));
    }
  }

  @Test
  void settlementWaitsForUncommittedDispatchAndPreservesItsActiveCharge() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3);
        ExecutorService executor = Executors.newFixedThreadPool(2)) {
      var original = proof.execution;
      var dispatched = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blockerPid = new AtomicInteger();
      Future<?> dispatch =
          executor.submit(() ->
              proof.sql.transaction(configuration -> {
                DSLContext transaction = DSL.using(configuration);
                transaction.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                proof.insertExecution(transaction, original, "INSTALL_DISPATCHED", null);
                blockerPid.set(
                    transaction.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                dispatched.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Dispatch transaction gate was not released");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
              }));
      try {
        awaitGate(dispatched, dispatch, "Dispatch did not reach its transaction gate");
        var denial = UUID.randomUUID();
        Future<?> settlement =
            executor.submit(() ->
                assertThatThrownBy(() -> proof.settle(original, denial))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class)
                    .hasMessageContaining(
                        "Execution custody prevents capacity evidence settlement"));
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(settlement.isDone()).isFalse();
        release.countDown();
        dispatch.get(10, TimeUnit.SECONDS);
        settlement.get(10, TimeUnit.SECONDS);
        assertThat(proof.executionState(original)).isEqualTo("INSTALL_DISPATCHED");
        assertThat(
                proof
                    .sql
                    .fetchSingle(
                        "SELECT account_denial_id FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                        original.request().admissionAttemptId())
                    .get(0))
            .isNull();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void retainedDenialDoesNotAuthorizeAReplacementAttemptForTheSameOrigin() throws Exception {
    try (var proof = new TransferSqlProof(2, 2_097_152L, 3)) {
      var original = proof.execution;
      var replacement = proof.newControlledExecution();
      assertThat(replacement.request().identity()).isEqualTo(original.request().identity());
      assertThat(replacement.request().admissionAttemptId())
          .isNotEqualTo(original.request().admissionAttemptId());
      proof.settle(original, UUID.randomUUID());

      assertThatThrownBy(() -> proof.precharge(replacement))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
      assertThat(
              proof.sql.fetchOne(
                  "SELECT 1 FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                  replacement.request().admissionAttemptId()))
          .isNull();
      proof.precharge(proof.newIndependentControlledOriginExecution());
    }
  }

  @Test
  void retainedDenialHistoryRemainsBoundedIndependentlyOfActiveSlots() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 2)) {
      proof.settle(proof.execution, UUID.randomUUID());
      var next = proof.newIndependentControlledOriginExecution();
      proof.precharge(next);
      proof.settle(next, UUID.randomUUID());
      assertThatThrownBy(() -> proof.precharge(proof.newIndependentControlledOriginExecution()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
    }
  }

  @Test
  void postgresSerializesTheLastEvidenceChargeAcrossConcurrentAttempts() throws Exception {
    try (var proof = withCapacityBudget(2, 2_097_152L);
        var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = proof.newIndependentControlledOriginExecution();
      var second = proof.newIndependentControlledOriginExecution();
      var assignment =
          proof.tags.assignmentForTransition(proof.admission.candidate().bindingDecisionId());
      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              proof.execution.selectedOwnerId(), proof.execution.selectedOwnerFence());
      java.util.function.BiConsumer<DSLContext, OriginalNewCapacityExecution> precharge =
          (tx, attempt) ->
              CanonicalGameplayCapacityEvidenceBudget.precharge(
                  tx,
                  attempt.request(),
                  attempt.candidateDigest(),
                  selected,
                  assignment.tenantGameplayTag(),
                  Clock.fixed(
                      attempt.request().evidence().evaluatedAt(), java.time.ZoneOffset.UTC));
      var winnerPid = new java.util.concurrent.CompletableFuture<Integer>();
      var allowCommit = new CountDownLatch(1);
      Future<?> winner =
          callers.submit(() ->
              proof.sql.transaction(configuration -> {
                DSLContext tx = DSL.using(configuration);
                precharge.accept(tx, first);
                winnerPid.complete(tx.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                if (!allowCommit.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Concurrent proof commit was not released");
                }
              }));
      Future<?> loser;
      try {
        int pid = winnerPid.get(5, TimeUnit.SECONDS);
        loser =
            callers.submit(() ->
                assertThatThrownBy(() ->
                        proof.sql.transaction(
                            configuration -> precharge.accept(DSL.using(configuration), second)))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("Original NEW capacity evidence budget is unavailable"));
        assertDatabaseWaitsFor(proof.sql, pid);
        assertThat(loser.isDone()).isFalse();
      } finally {
        allowCommit.countDown();
      }
      winner.get(10, TimeUnit.SECONDS);
      loser.get(10, TimeUnit.SECONDS);
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
      var seeded = proof.newIndependentControlledOriginExecution();
      proof.seedUndeliveredDispatch(seeded);
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      Future<UUID> freeze =
          executor.submit(() ->
              gate.attach(proof.sql)
                  .transactionResult(configuration -> {
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
      Future<?> refused =
          executor.submit(() -> {
            assertThatThrownBy(() ->
                    proof
                        .capacity(proof.sql)
                        .authorize(
                            proof.admission,
                            template,
                            account,
                            issuer,
                            proof.execution,
                            transferDeadline()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Original NEW capacity execution is indeterminate")
                .hasStackTraceContaining(
                    "CanonicalGameplayOriginalNewCapacityExecutionOwner.requireSelectedOwner");
          });
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(refused.isDone()).isFalse();
      } finally {
        gate.release();
      }
      UUID id = freeze.get(10, TimeUnit.SECONDS);
      refused.get(10, TimeUnit.SECONDS);
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
          executor.submit(() -> {
            try (var connection =
                java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
              connection.setSchema(schema);
              connection.setAutoCommit(false);
              DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
              rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
              UUID transferId = proof.insertRawFrozenTransfer(rawSql);
              blockerPid.set(rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
              inserted.countDown();
              if (!release.await(4, TimeUnit.SECONDS))
                throw new AssertionError("Raw frozen-insert gate was not released");
              connection.commit();
              return transferId;
            }
          });
      awaitGate(inserted, freeze, "Raw frozen insert did not reach its transaction gate");
      Future<?> refused =
          executor.submit(() -> {
            assertThatThrownBy(() ->
                    proof
                        .capacity(proof.sql)
                        .authorize(
                            proof.admission,
                            template,
                            account,
                            issuer,
                            proof.execution,
                            transferDeadline()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Original NEW capacity execution is indeterminate")
                .hasStackTraceContaining(
                    "CanonicalGameplayOriginalNewCapacityExecutionOwner.requireSelectedOwner");
          });
      try {
        assertDatabaseWaitsFor(proof.sql, blockerPid.get());
        assertThat(refused.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      UUID transferId = freeze.get(10, TimeUnit.SECONDS);
      refused.get(10, TimeUnit.SECONDS);
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
          executor.submit(() ->
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
          executor.submit(() -> {
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
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration -> {
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
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration -> {
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
          executor.submit(() -> {
            try (var connection =
                java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
              connection.setSchema(schema);
              connection.setAutoCommit(false);
              DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
              rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
              proof.insertRawTransferItem(rawSql, transferId, 0);
              blockerPid.set(rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
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
          executor.submit(() ->
              sqlStateOf(() ->
                  proof.sql.transaction(configuration -> {
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
      assertSqlGuard(() ->
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
          executor.submit(() ->
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
          executor.submit(() -> {
            try (var connection =
                java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
              connection.setSchema(schema);
              connection.setAutoCommit(false);
              DSLContext rawSql = DSL.using(connection, SQLDialect.POSTGRES);
              rawSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
              UUID transferId = proof.insertRawFrozenTransfer(rawSql);
              blockerPid.set(rawSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
              inserted.countDown();
              if (!release.await(4, TimeUnit.SECONDS))
                throw new AssertionError("Transfer history reservation gate was not released");
              connection.commit();
              return transferId;
            }
          });
      awaitGate(inserted, first, "First transfer did not reserve the tenant history slot");
      var second =
          executor.submit(() ->
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

        assertThatThrownBy(() ->
                status.installOwner(
                    proof.scope,
                    proof.prior.regionEpoch(),
                    proof.prior,
                    "e".repeat(64),
                    "game-session-service",
                    "sql-proof-successor",
                    proof.clock.instant()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("region ownership is unavailable")
            .hasCauseInstanceOf(
                CanonicalGameplayRegionRuntimeStatusRepository.ExistingOwnerReplacementRefused
                    .class);
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
  void transferCompletionRequiresExactSuccessorInstallationAtCommit() throws Exception {
    try (var proof = new TransferSqlProof()) {
      UUID transfer = proof.insertRawFrozenTransfer(proof.sql);
      String frozen = proof.transferImage(transfer);
      String successor = "sql-proof-successor";
      String lease = "e".repeat(64);
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration -> {
                DSLContext transaction = DSL.using(configuration);
                long fence =
                    transaction
                        .fetchSingle("SELECT nextval('game_session_region_executor_fence_seq')")
                        .get(0, Long.class);
                // The deferred guard permits the completion statement but refuses the commit.
                assertThat(
                        transaction.execute(
                            "UPDATE game_session_region_owner_transfer"
                                + " SET state='COMPLETED',new_owner_id=?,new_owner_fence=?,new_lease_correlation=?"
                                + " WHERE transfer_id=? AND state='FROZEN'",
                            successor,
                            fence,
                            lease,
                            transfer))
                    .isEqualTo(1);
              }))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region transfer completion lacks its exact installed successor");
              });
      assertThat(proof.transferImage(transfer)).isEqualTo(frozen);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);

      var installed = proof.rawCompleteAndInstall(transfer, successor, lease, false);
      assertThat(installed.ownerInstanceId()).isEqualTo(successor);
      assertThat(installed.leaseTokenCorrelation()).isEqualTo(lease);
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transfer)
                  .get(0, String.class))
          .isEqualTo("COMPLETED");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void transferIncarnationUpgradePreservesRetainedDispatches(boolean conflictingIncarnations)
      throws Exception {
    try (var proof = new TransferSqlProof(MigrationVersion.fromVersion("1065.0.11.2"))) {
      assertThat(
              proof.sql
                  .fetchSingle(
                      "SELECT version FROM flyway_schema_history WHERE success"
                          + " ORDER BY installed_rank DESC LIMIT 1")
                  .get(0, String.class))
          .isEqualTo("1065.0.11.2");
      var first = proof.execution;
      var second = proof.newIndependentControlledOriginExecution();
      proof.seedUndeliveredDispatch(first);
      proof.seedUndeliveredDispatch(second);
      UUID transfer = proof.insertRawFrozenTransfer(proof.sql);
      proof.insertRawTransferItem(proof.sql, transfer, 0, first);
      proof.insertRawTransferItem(proof.sql, transfer, 1, second);

      String dispatchSql =
          "UPDATE game_session_region_owner_transfer_item"
              + " SET fence_dispatch_id=?,fence_dispatch_incarnation=?"
              + " WHERE transfer_id=? AND execution_digest=?";
      String incarnation = "sha256:" + "a".repeat(64);
      assertThat(
              proof.sql.execute(
                  dispatchSql, UUID.randomUUID(), incarnation, transfer, first.digest()))
          .isEqualTo(1);
      assertThat(
              proof.sql.execute(
                  dispatchSql,
                  UUID.randomUUID(),
                  conflictingIncarnations ? "sha256:" + "b".repeat(64) : incarnation,
                  transfer,
                  second.digest()))
          .isEqualTo(1);
      String parentSql =
          "SELECT to_jsonb(t)::text||':'||xmin::text"
              + " FROM game_session_region_owner_transfer t WHERE transfer_id=?";
      String itemsSql =
          "SELECT to_jsonb(i)::text||':'||xmin::text"
              + " FROM game_session_region_owner_transfer_item i WHERE transfer_id=?"
              + " ORDER BY target_ordinal";
      var parentBefore = proof.sql.fetch(parentSql, transfer).getValues(0, String.class);
      var itemsBefore = proof.sql.fetch(itemsSql, transfer).getValues(0, String.class);
      String epochsSql =
          "SELECT to_jsonb(e)::text||':'||xmin::text"
              + " FROM game_session_runtime_family_catalog_epoch e ORDER BY catalog_epoch";
      var epochsBefore = proof.sql.fetch(epochsSql).getValues(0, String.class);
      var target = MigrationVersion.fromVersion("1065.0.11.3");
      if (conflictingIncarnations) {
        assertThatThrownBy(() -> proof.fixture.upgradeWithDefaultGameSessionMigrations(target))
            .isInstanceOf(FlywayException.class)
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Retained region fence dispatches disagree on storage incarnation");
                });
        assertThat(proof.sql.fetch(epochsSql).getValues(0, String.class)).isEqualTo(epochsBefore);
      } else {
        assertThat(proof.fixture.upgradeWithDefaultGameSessionMigrations(target)).isEqualTo(1);
        assertThat(proof.sql.fetch(epochsSql).getValues(0, String.class))
            .hasSize(epochsBefore.size() + 1)
            .startsWith(epochsBefore.toArray(String[]::new));
      }
      assertThat(proof.sql.fetch(parentSql, transfer).getValues(0, String.class))
          .isEqualTo(parentBefore);
      assertThat(proof.sql.fetch(itemsSql, transfer).getValues(0, String.class))
          .isEqualTo(itemsBefore);
    }
  }

  @Test
  void firstFenceDispatchPinsIncarnationForAllCapturedExecutions() throws Exception {
    try (var proof = new TransferSqlProof()) {
      var first = proof.execution;
      var second = proof.newIndependentControlledOriginExecution();
      proof.seedUndeliveredDispatch(first);
      proof.seedUndeliveredDispatch(second);
      UUID transfer = proof.insertRawFrozenTransfer(proof.sql);
      proof.insertRawTransferItem(proof.sql, transfer, 0, first);
      proof.insertRawTransferItem(proof.sql, transfer, 1, second);

      String incarnation = "sha256:" + "a".repeat(64);
      String dispatchSql =
          "UPDATE game_session_region_owner_transfer_item"
              + " SET fence_dispatch_id=?,fence_dispatch_incarnation=?"
              + " WHERE transfer_id=? AND execution_digest=?";
      UUID firstDispatch = UUID.randomUUID();
      assertThat(
              proof.sql.execute(dispatchSql, firstDispatch, incarnation, transfer, first.digest()))
          .isEqualTo(1);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT incarnation_digest FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transfer)
                  .get(0, String.class))
          .isEqualTo(incarnation);
      String pinned = proof.transferImage(transfer);
      String items = proof.itemImage(transfer);
      UUID secondDispatch = UUID.randomUUID();
      assertThatThrownBy(() ->
              proof.sql.execute(
                  dispatchSql,
                  secondDispatch,
                  "sha256:" + "b".repeat(64),
                  transfer,
                  second.digest()))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Region transfer incarnation disagrees with retained fence dispatch");
              });
      assertThat(proof.transferImage(transfer)).isEqualTo(pinned);
      assertThat(proof.itemImage(transfer)).isEqualTo(items);
      assertThat(
              proof.sql.execute(
                  dispatchSql, secondDispatch, incarnation, transfer, second.digest()))
          .isEqualTo(1);
      assertThat(
              proof
                  .sql
                  .fetch(
                      "SELECT fence_dispatch_id FROM game_session_region_owner_transfer_item"
                          + " WHERE transfer_id=? AND fence_dispatch_incarnation=? ORDER BY target_ordinal",
                      transfer,
                      incarnation)
                  .getValues(0, UUID.class))
          .containsExactly(firstDispatch, secondDispatch);
      assertThat(proof.transferImage(transfer)).isEqualTo(pinned);
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
        assertThatThrownBy(() ->
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
      assertSqlGuard(() ->
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
      assertSqlGuard(() ->
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
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration -> {
                var transaction = org.jooq.impl.DSL.using(configuration);
                var changed = "sha256:" + "b".repeat(64);
                var round = UUID.randomUUID();
                transaction.execute(
                    "UPDATE game_session_region_owner_transfer SET verification_round=? WHERE transfer_id=?",
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

      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer_item SET fence_dispatch_id=?"
                  + " WHERE transfer_id=?",
              UUID.randomUUID(),
              id));
      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                      + " new_owner_id='sql-proof-successor',"
                      + " new_owner_fence=nextval('game_session_region_executor_fence_seq'),"
                      + " new_lease_correlation=?,verification_round=?,verification_complete=true"
                      + " WHERE transfer_id=?",
                  "e".repeat(64),
                  UUID.randomUUID(),
                  id))
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
