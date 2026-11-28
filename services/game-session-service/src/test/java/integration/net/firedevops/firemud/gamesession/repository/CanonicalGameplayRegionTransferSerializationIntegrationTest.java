package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
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
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Refusal;
import net.firedevops.firemud.test.TestContainerImages;
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownerInstallUsesFreshReadCommittedSnapshotAfterWaitingForAdvisoryLock(
      boolean dispatchWhileWaiting) throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var blocker =
            java.sql.DriverManager.getConnection(
                postgresContainer().getJdbcUrl(),
                postgresContainer().getUsername(),
                postgresContainer().getPassword())) {
      var dispatched = proof.newIndependentControlledOriginExecution();
      if (dispatchWhileWaiting) {
        proof.precharge(dispatched);
        proof.authorizeAccountSend(dispatched);
      }
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
          url
              + (url.contains("?") ? "&" : "?")
              + "currentSchema="
              + schema
              + "&connectTimeout=3&socketTimeout=5");
      repeatableReadSource.setUsername(postgresContainer().getUsername());
      repeatableReadSource.setPassword(postgresContainer().getPassword());
      AtomicInteger installIsolation = new AtomicInteger(-1);
      AtomicInteger custodyReads = new AtomicInteger();
      var installObserver =
          new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
              String query = context.sql();
              if (query != null
                  && query.contains("game_session_original_new_capacity_execution")
                  && query.contains("game_session_canonical_binding_region_bridge_obligation")) {
                custodyReads.incrementAndGet();
              }
              if (!"SELECT pg_advisory_xact_lock(hashtextextended(?, 0))".equals(query)) {
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
      var repeatableReadRepository =
          new CanonicalGameplayRegionRuntimeStatusRepository(
              DSL.using(
                  repeatableReadDsl
                      .configuration()
                      .derive(new DefaultExecuteListenerProvider(installObserver))));
      assertThat(repeatableReadRepository.find(proof.scope)).contains(proof.prior);
      Future<?> install =
          executor.submit(() ->
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
        assertThat(installIsolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(custodyReads.get()).isZero();
        if (dispatchWhileWaiting)
          proof.insertExecution(proof.sql, dispatched, "INSTALL_DISPATCHED", null);
      } finally {
        blocker.commit();
      }

      if (dispatchWhileWaiting) {
        assertThatThrownBy(() -> install.get(10, TimeUnit.SECONDS))
            .rootCause()
            .isInstanceOfSatisfying(
                IllegalStateException.class,
                failure ->
                    assertThat(failure)
                        .hasMessage("Canonical Game Session region ownership is unavailable"));
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.executionState(dispatched)).isEqualTo("INSTALL_DISPATCHED");
      } else {
        install.get(10, TimeUnit.SECONDS);
        var successor = proof.currentOwner();
        assertThat(successor.ownerInstanceId()).isEqualTo("repeatable-read-successor");
        assertThat(successor.executorFence()).isGreaterThan(proof.prior.executorFence());
        assertThat(proof.executionState(dispatched)).isNull();
      }
      assertThat(custodyReads.get()).isPositive();

      var current = proof.currentOwner();
      installIsolation.set(-1);
      assertThatThrownBy(() ->
              repeatableReadRepository.installOwner(
                  proof.scope,
                  current.regionEpoch(),
                  null,
                  "f".repeat(64),
                  current.ownerService(),
                  "replacement-without-original-owner-evidence",
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
      proof.authorizeAccountSend(original);
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
      proof.authorizeAccountSend(proof.execution);
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
      Future<UUID> freeze =
          executor.submit(() ->
              proof.transfers.within(
                  transferDeadline(), ignored -> proof.transfers.begin(proof.scope, proof.prior)));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void denialSettlementRequiresTheCurrentSelectedOwner(boolean ownerReplaced) throws Exception {
    try (var proof = new TransferSqlProof()) {
      var original = proof.execution;
      proof.authorizeAccountSend(original);
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
        String retainedQuery =
            """
            SELECT to_jsonb(c)::text AS charge,
                coalesce((SELECT to_jsonb(e)::text
                  FROM game_session_original_new_capacity_execution e
                  WHERE e.admission_attempt_id = c.admission_attempt_id), '<absent>') AS execution
            FROM game_session_capacity_evidence_charge c
            WHERE c.admission_attempt_id = ?
            """;
        var attemptId = original.request().admissionAttemptId();
        var retainedBefore = proof.sql.fetchSingle(retainedQuery, attemptId).intoMap();
        assertThatThrownBy(settle::run)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("Original NEW capacity execution is indeterminate")
            .hasStackTraceContaining(
                "CanonicalGameplayOriginalNewCapacityExecutionOwner.requireSelectedOwner");
        var retainedAfter = proof.sql.fetchSingle(retainedQuery, attemptId).intoMap();
        assertThat(retainedAfter).isEqualTo(retainedBefore);
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
  void capacityChargeRequiresTheTransitionRequestIdentity() throws Exception {
    try (var proof = new TransferSqlProof()) {
      var mismatched = proof.newMismatchedRequestExecution();
      var request = mismatched.request();
      var identity = request.identity();
      assertThat(identity.requestId())
          .isNotEqualTo(proof.execution.request().identity().requestId());

      assertThatThrownBy(() ->
              proof.sql.transaction(configuration ->
                  DSL.using(configuration)
                      .execute(
                          "INSERT INTO game_session_capacity_evidence_charge"
                              + " (admission_attempt_id,tenant_id,request_id,admission_id,"
                              + "capacity_rearm_fence,mutation_digest,candidate_digest,"
                              + "selected_owner_id,selected_owner_fence,reserved_binding_bytes,"
                              + "account_send_state,account_send_evidence_expires_at)"
                              + " VALUES (?,?,?,?,?,?,?,?,?,1048576,'UNCLAIMED',CAST(? AS timestamptz))",
                          request.admissionAttemptId(),
                          identity.tenantId(),
                          identity.requestId(),
                          identity.admissionId(),
                          request.capacityRearmFence(),
                          request.mutationDigest(),
                          mismatched.candidateDigest(),
                          mismatched.selectedOwnerId(),
                          mismatched.selectedOwnerFence(),
                          java.time.OffsetDateTime.ofInstant(
                              request
                                  .evidence()
                                  .expiresAt()
                                  .truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                              java.time.ZoneOffset.UTC))))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Capacity evidence source or predecessor coverage is unavailable");
              });

      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT request_id FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      proof.execution.request().admissionAttemptId())
                  .get(0, UUID.class))
          .isEqualTo(proof.execution.request().identity().requestId());

      var exact = proof.newIndependentControlledOriginExecution();
      proof.precharge(exact);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT request_id,admission_id,candidate_digest"
                          + " FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      exact.request().admissionAttemptId())
                  .intoMap())
          .containsEntry("request_id", exact.request().identity().requestId())
          .containsEntry("admission_id", exact.request().identity().admissionId())
          .containsEntry("candidate_digest", exact.candidateDigest());
    }
  }

  @Test
  void transferItemRequiresExactAllocationVersionAndWorkloadNamespace() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID transferId = proof.insertRawFrozenTransfer(proof.sql);
      var assignment = proof.tags.assignmentForTransition(proof.execution.transitionId());
      String insert =
          "INSERT INTO game_session_region_owner_transfer_item"
              + " (transfer_id,target_ordinal,execution_digest,canonical_execution,candidate_digest,"
              + "tenant_gameplay_tag,workload_namespace,allocation_version)"
              + " VALUES (?,?,?,?,?,?,?,?)";

      assertThatThrownBy(() ->
              proof.sql.transaction(configuration ->
                  DSL.using(configuration)
                      .execute(
                          insert,
                          transferId,
                          0L,
                          proof.execution.digest(),
                          proof.execution.canonicalBytes(),
                          proof.execution.candidateDigest(),
                          assignment.tenantGameplayTag(),
                          assignment.workloadNamespace(),
                          assignment.allocationVersion() + 1)))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Transfer item differs from unresolved original region execution");
              });
      assertThat(
              proof.sql.fetchOne(
                  "SELECT 1 FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                  transferId))
          .isNull();

      assertThatThrownBy(() ->
              proof.sql.transaction(configuration ->
                  DSL.using(configuration)
                      .execute(
                          insert,
                          transferId,
                          0L,
                          proof.execution.digest(),
                          proof.execution.canonicalBytes(),
                          proof.execution.candidateDigest(),
                          assignment.tenantGameplayTag(),
                          "wrong-workload-namespace",
                          assignment.allocationVersion())))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Transfer item differs from unresolved original region execution");
              });
      assertThat(
              proof.sql.fetchOne(
                  "SELECT 1 FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                  transferId))
          .isNull();

      proof.insertRawTransferItem(proof.sql, transferId, 0);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT workload_namespace,allocation_version"
                          + " FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                      transferId)
                  .intoMap())
          .containsEntry("workload_namespace", assignment.workloadNamespace())
          .containsEntry("allocation_version", assignment.allocationVersion());
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
      proof.authorizeAccountSend(original);
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
                        "Execution custody prevents capacity evidence charge changes"));
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
      proof.authorizeAccountSend(proof.execution);
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
          executor.submit(() ->
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
      var seeded = proof.newIndependentControlledOriginExecution();
      proof.seedUndeliveredDispatch(seeded);
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
      var freezing = proof.transfers(gate.attach(proof.sql));
      Future<UUID> freeze =
          executor.submit(() ->
              freezing.within(
                  transferDeadline(), ignored -> freezing.begin(proof.scope, proof.prior)));
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
      assertSqlGuard(
          () -> proof.insertExecution(proof.sql, proof.execution, "INSTALL_DISPATCHED", null));
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
          executor.submit(() -> {
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
      proof.authorizeAccountSend(proof.execution);
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
      proof.authorizeAccountSend(proof.execution);
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
  void staleTransferEnrollmentAfterTerminalCloseIsRefusedAndFrozenIntentCanResume()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      UUID transferId = proof.begin();
      assertThat(
              proof.sql.fetch(
                  "SELECT execution_digest FROM game_session_original_new_capacity_execution"
                      + " WHERE execution_digest=? AND status='AUTHORIZED'",
                  proof.execution.digest()))
          .hasSize(1);
      // Freeze/census selected this exact assignment before the terminal close below. Reuse the
      // captured value for the stale insert so the test reaches the SQL custody guard rather than
      // trying to rediscover an inventory that has since become terminal.
      var capturedAssignment = proof.tags.assignmentForTransition(proof.execution.transitionId());

      // Retain a real terminal result through the owner fixture after the transfer's pending
      // census selected this execution but before that stale selection inserts its child row.
      assertThat(proof.fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      proof
          .capacity(proof.sql)
          .closeOriginalAttempt(proof.execution.request(), proof.execution.reservation());
      var terminal =
          proof.sql.fetchSingle(
              "SELECT status,terminal_readback FROM game_session_original_new_capacity_execution"
                  + " WHERE execution_digest=?",
              proof.execution.digest());
      assertThat(terminal.get("status", String.class)).isEqualTo("CLOSED");
      assertThat(terminal.get("terminal_readback", byte[].class)).isNotEmpty();
      assertThat(
              proof
                  .capacity(proof.sql)
                  .readExact(proof.execution.request(), proof.execution.reservation())
                  .disposition())
          .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");

      assertThatThrownBy(
              () -> proof.insertRawTransferItem(proof.sql, transferId, 0, capturedAssignment))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Transfer item differs from unresolved original region execution");
              });
      assertThat(
              proof.sql.fetchOne(
                  "SELECT 1 FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                  transferId))
          .isNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("FROZEN");

      var frozen =
          proof.coordinator.frozenOriginal(proof.execution.transitionId(), transferDeadline());
      assertThat(frozen).isNotNull();
      assertThat(frozen.transferId()).isEqualTo(transferId);
      assertThat(proof.coordinator.resume(frozen, proof.scope, proof.prior, transferDeadline()))
          .isEqualTo(transferId);
      var installed = proof.install(transferId);
      assertThat(installed.ownerInstanceId()).isEqualTo("sql-proof-successor");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("COMPLETED");
      assertThat(proof.executionState(proof.execution)).isEqualTo("CLOSED");
      assertThat(
              proof.sql.fetchCount(
                  org.jooq.impl.DSL.table("game_session_region_owner_transfer_item")))
          .isZero();
    }
  }

  @Test
  void ownerReplacementGuardTracksAuthorizedThenExactlyClosedCapacityExecution() throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), Clock.systemUTC());
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");

      assertOwnerReplacementGuard(proof, "owner-successor-while-authorized", false);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");

      assertThat(proof.fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      proof
          .capacity(proof.sql)
          .closeOriginalAttempt(proof.execution.request(), proof.execution.reservation());
      var terminal =
          proof.sql.fetchSingle(
              "SELECT status,terminal_readback FROM game_session_original_new_capacity_execution"
                  + " WHERE execution_digest=?",
              proof.execution.digest());
      assertThat(terminal.get("status", String.class)).isEqualTo("CLOSED");
      assertThat(terminal.get("terminal_readback", byte[].class)).isNotEmpty();
      assertThat(
              proof
                  .capacity(proof.sql)
                  .readExact(proof.execution.request(), proof.execution.reservation())
                  .disposition())
          .isEqualTo("NOT_EXECUTED_AFTER_AUTHORIZATION");

      assertOwnerReplacementGuard(proof, "owner-successor-after-closed-retention", true);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.executionState(proof.execution)).isEqualTo("CLOSED");
    }
  }

  private void assertOwnerReplacementGuard(
      TransferSqlProof proof, String successor, boolean expectedSuccess) throws Exception {
    String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
    try (var connection =
        java.sql.DriverManager.getConnection(
            postgresContainer().getJdbcUrl(),
            postgresContainer().getUsername(),
            postgresContainer().getPassword())) {
      connection.setSchema(schema);
      connection.setAutoCommit(false);
      DSLContext tx = DSL.using(connection, SQLDialect.POSTGRES);
      tx.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
      tx.execute("SAVEPOINT guarded_owner_replacement");
      if (expectedSuccess) {
        assertThat(replaceOwner(tx, proof, successor)).isEqualTo(1);
      } else {
        assertThatThrownBy(() -> replaceOwner(tx, proof, successor))
            .rootCause()
            .isInstanceOfSatisfying(
                org.postgresql.util.PSQLException.class,
                failure -> {
                  assertThat(failure.getSQLState()).isEqualTo("23514");
                  assertThat(failure.getMessage())
                      .contains("Region owner replacement lacks exact transfer custody");
                });
      }
      // Both the rejected statement and successful control remain speculative.
      tx.execute("ROLLBACK TO SAVEPOINT guarded_owner_replacement");
      connection.rollback();
    }
  }

  private int replaceOwner(DSLContext tx, TransferSqlProof proof, String successor) {
    return tx.execute(
        "UPDATE game_session_region_runtime_status SET"
            + " owner_instance_id=?,executor_fence=nextval('game_session_region_executor_fence_seq'),lease_token_correlation=?"
            + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
        successor,
        "e".repeat(64),
        proof.scope.tenantId(),
        proof.scope.gameInstanceId(),
        proof.scope.regionId());
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
          executor.submit(() -> {
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
                      "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE"
                          + " transfer_id=?",
                      transferId)
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void lateTransferVerificationWaitsForParentCompletionThenIsRefused() throws Exception {
    try (var proof = new TransferSqlProof();
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID transferId = proof.begin();
      proof.captureAndFence(transferId);
      proof.verifyPage(transferId);
      boolean verified =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(transferId));
      assertThat(verified).isTrue();
      var installed = proof.install(transferId);
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      transferId)
                  .get(0, String.class))
          .isEqualTo("COMPLETED");

      String schema = proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class);
      try (var terminalLock =
          java.sql.DriverManager.getConnection(
              postgresContainer().getJdbcUrl(),
              postgresContainer().getUsername(),
              postgresContainer().getPassword())) {
        terminalLock.setSchema(schema);
        terminalLock.setAutoCommit(false);
        DSLContext terminalSql = DSL.using(terminalLock, SQLDialect.POSTGRES);
        terminalSql.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
        assertThat(
                terminalSql
                    .fetchSingle(
                        "SELECT state FROM game_session_region_owner_transfer"
                            + " WHERE transfer_id=? FOR UPDATE",
                        transferId)
                    .get(0, String.class))
            .isEqualTo("COMPLETED");
        int blockerPid = terminalSql.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class);

        UUID lateRound = UUID.randomUUID();
        Future<String> lateVerification =
            executor.submit(() -> {
              try {
                proof.sql.execute(
                    "UPDATE game_session_region_owner_transfer_item SET verified_round=?"
                        + " WHERE transfer_id=? AND target_ordinal=0",
                    lateRound,
                    transferId);
                return "COMMITTED";
              } catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                  if (cause instanceof org.postgresql.util.PSQLException postgresFailure) {
                    return postgresFailure.getSQLState() + ":" + postgresFailure.getMessage();
                  }
                }
                throw failure;
              }
            });
        try {
          assertDatabaseWaitsFor(proof.sql, blockerPid);
          assertThat(lateVerification.isDone()).isFalse();
        } finally {
          terminalLock.commit();
        }

        assertThat(lateVerification.get(10, TimeUnit.SECONDS))
            .startsWith("23514:")
            .contains("Completed transfer cannot acquire or change custody");
        assertThat(
                proof
                    .sql
                    .fetchSingle(
                        "SELECT state,verification_round FROM game_session_region_owner_transfer"
                            + " WHERE transfer_id=?",
                        transferId)
                    .intoMap())
            .containsEntry("state", "COMPLETED");
        assertThat(
                proof
                    .sql
                    .fetchSingle(
                        "SELECT verified_round FROM game_session_region_owner_transfer_item"
                            + " WHERE transfer_id=? AND target_ordinal=0",
                        transferId)
                    .get(0, UUID.class))
            .isNotEqualTo(lateRound);
      }
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
      proof.authorizeAccountSend(proof.execution);
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
      var continuationLocks = new java.util.concurrent.CopyOnWriteArrayList<String>();
      var observer =
          new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
              String query = context.sql();
              if (query != null && query.endsWith("FOR UPDATE")) {
                if (query.contains("game_session_canonical_binding_inventory_clock")) {
                  continuationLocks.add("inventory");
                } else if (query.contains("game_session_region_runtime_status")) {
                  continuationLocks.add("region");
                }
              }
            }
          };
      var continuationRepository =
          proof.transfers(
              DSL.using(
                  proof.sql.configuration().derive(new DefaultExecuteListenerProvider(observer))));
      Future<?> continuation =
          executor.submit(() ->
              continuationRepository.within(
                  transferDeadline(),
                  ignored -> {
                    continuationRepository.requireFrozenOriginal(
                        original, proof.scope, proof.prior);
                    return null;
                  }));
      try {
        assertDatabaseWaitsFor(proof.sql, gate.backendPid());
        assertThat(continuation.isDone()).isFalse();
        assertThat(continuationLocks).containsExactly("inventory");
      } finally {
        gate.release();
      }
      replay.get(10, TimeUnit.SECONDS);
      continuation.get(10, TimeUnit.SECONDS);
      assertThat(continuationLocks).containsExactly("inventory", "region");
      assertThat(proof.transferImage(id)).isEqualTo(before);
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void competingTransferCreatorsKeepOneOriginalIntent() throws Exception {
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
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void lostCreateReplyKeepsOneOriginalIntent() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      assertThat(
              proof
                  .sql
                  .fetchSingle("SELECT count(*) FROM game_session_region_owner_transfer")
                  .get(0, Long.class))
          .isZero();
      assertThatThrownBy(
              () -> {
                proof.transfers.within(
                    transferDeadline(), ignored -> proof.transfers.begin(proof.scope, proof.prior));
                throw new IllegalStateException("Controlled lost reply after transfer creation");
              })
          .hasMessage("Controlled lost reply after transfer creation");
      var retained =
          proof
              .sql
              .fetchSingle("SELECT transfer_id FROM game_session_region_owner_transfer")
              .get(0, UUID.class);
      assertThat(retained).isNotNull();
      String original = proof.transferImage(retained);
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
          executor.submit(() -> {
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
                assertThat(failure.getServerErrorMessage()).isNotNull();
                assertThat(failure.getServerErrorMessage().getMessage())
                    .isEqualTo("Region transfer completion requires its exact installed successor");
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
              proof
                  .sql
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
      assertThat(
              proof
                  .sql
                  .fetch(
                      "SELECT execution_digest FROM game_session_region_owner_transfer_item"
                          + " WHERE transfer_id=? ORDER BY target_ordinal",
                      transfer)
                  .getValues(0, String.class))
          .containsExactly(first.digest(), second.digest());

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
      UUID transfer = proof.begin();
      var captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(transfer));
      assertThat(captured)
          .extracting(item -> item.execution().digest())
          .containsExactlyInAnyOrder(first.digest(), second.digest());

      var firstItem = captured.getFirst();
      long firstDeadline = transferDeadline();
      var firstDispatch =
          proof.transfers.within(
              firstDeadline,
              ignored ->
                  proof.transfers.fenceDispatch(
                      firstItem,
                      proof.receiver.observeForRegionOwnerTransfer(
                          transfer, firstItem.ordinal(), firstDeadline)));
      assertThat(firstDispatch.receiptId()).isNotNull();
      String incarnation = firstDispatch.incarnationDigest();
      assertThat(incarnation).isNotBlank();
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

      var secondItem = captured.getLast();
      String differentIncarnation = "sha256:" + "0".repeat(64);
      assertThat(differentIncarnation).isNotEqualTo(incarnation);
      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item"
                      + " SET fence_dispatch_id=?,fence_dispatch_incarnation=?,"
                      + " fence_prepare_ids=ARRAY[]::uuid[]"
                      + " WHERE transfer_id=? AND target_ordinal=? AND execution_digest=?",
                  UUID.randomUUID(),
                  differentIncarnation,
                  transfer,
                  secondItem.ordinal(),
                  secondItem.execution().digest()))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains(
                        "Region fence dispatch requires an unfenced frozen original with its exact bound incarnation");
              });
      assertThat(proof.transferImage(transfer)).isEqualTo(pinned);
      assertThat(proof.itemImage(transfer)).isEqualTo(items);

      long secondDeadline = transferDeadline();
      var secondDispatch =
          proof.transfers.within(
              secondDeadline,
              ignored ->
                  proof.transfers.fenceDispatch(
                      secondItem,
                      proof.receiver.observeForRegionOwnerTransfer(
                          transfer, secondItem.ordinal(), secondDeadline)));
      assertThat(secondDispatch.receiptId()).isNotNull().isNotEqualTo(firstDispatch.receiptId());
      assertThat(secondDispatch.incarnationDigest()).isEqualTo(incarnation);
      assertThat(
              proof
                  .sql
                  .fetch(
                      "SELECT fence_dispatch_id FROM game_session_region_owner_transfer_item"
                          + " WHERE transfer_id=? AND fence_dispatch_incarnation=? ORDER BY target_ordinal",
                      transfer,
                      incarnation)
                  .getValues(0, UUID.class))
          .containsExactly(firstDispatch.receiptId(), secondDispatch.receiptId());
      assertThat(proof.transferImage(transfer)).isEqualTo(pinned);
    }
  }
}
