package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseWireCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real PostgreSQL definitions for non-admitting storage only. Carriers are fabricated shape
 * fixtures, never authenticated source snapshots or evidence that a binding may be admitted.
 */
class AccountGameplayAdmissionLeasePersistenceIntegrationTest {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);
  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void fixtureUsesDurablePrimarySettingsRequiredByCommitConfirmation() {
    var settings = POSTGRES.verifyDurablePrimary();
    assertThat(settings.inRecovery()).isFalse();
    assertThat(settings.fsync()).isEqualTo("on");
    assertThat(settings.synchronousCommit()).isEqualTo("on");
  }

  @Test
  void originalExecutorAcknowledgesActualCommitWithoutCreatingTemporalReceipt() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    var before = operation(context, original);
    // Upstream carrier and authority fields are component-only fixtures, never admission proof.
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
            .execute(original, decision);
    var committed = operation(context, original);
    assertThat(acknowledgement.evidence().canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(acknowledgement.evidence().sha256()).isEqualTo(original.sha256());
    assertThat(acknowledgement.decisionId()).isEqualTo(decision);
    assertThat(acknowledgement.finalizationXid())
        .isEqualTo(committed.get("finalization_xid", String.class));
    assertThat(acknowledgement.committedBeforeMs()).isPositive().isLessThan(expiresAt(original));
    assertThat(committed.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(committed.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    for (String field :
        List.of(
            "request_id",
            "account_uuid",
            "lease_id",
            "lease_fence",
            "evidence_json",
            "evidence_sha256",
            "evaluated_at_ms",
            "expires_at_ms")) {
      assertThat(committed.get(field)).as(field).isEqualTo(before.get(field));
    }
    assertThat(committed.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    // The in-process ACK and bound are not a persisted temporal confirmation or durable receipt.
  }

  @Test
  void originalExecutorReadsClockOnOriginalConnectionOnlyAfterActualCommitAcknowledgement() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    var clockQueries = new AtomicInteger();
    // Component-only upstream evidence. The wrapper delegates the actual commit and clock query.
    var acknowledgement =
        new AccountGameplayAdmissionOriginalCommitExecutor(
                postCommitClockDataSource(context.dataSource(), 0L, false, clockQueries))
            .execute(original, decision);
    assertThat(clockQueries.get()).isEqualTo(1);
    assertThat(acknowledgement.committedBeforeMs()).isPositive().isLessThan(expiresAt(original));
    assertThat(databaseNow(context)).isGreaterThanOrEqualTo(acknowledgement.committedBeforeMs());
    var committed = operation(context, original);
    assertThat(committed.get("finalization_xid", String.class))
        .isEqualTo(acknowledgement.finalizationXid());
    assertThat(committed.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(committed.get("evidence_json", String.class)).isEqualTo(original.canonicalJson());
    assertThat(committed.get("evidence_sha256", String.class)).isEqualTo(original.sha256());
    assertThat(committed.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void delayedPostAcknowledgementClockDeniesWithoutUndoingOriginalCommitOrRenewingExpiry() {
    var context = context(null);
    var original = pending(context, account(context), 1000L);
    UUID decision = UUID.randomUUID();
    var clockQueries = new AtomicInteger();
    var executor =
        new AccountGameplayAdmissionOriginalCommitExecutor(
            postCommitClockDataSource(
                context.dataSource(), expiresAt(original), false, clockQueries));
    assertThatThrownBy(() -> executor.execute(original, decision))
        .hasMessageContaining("post-COMMIT clock bound unavailable");
    assertThat(clockQueries.get()).isEqualTo(1);
    assertThat(databaseNow(context)).isGreaterThanOrEqualTo(expiresAt(original));
    var committed = operation(context, original);
    assertThat(committed.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(committed.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(committed.get("finalization_xid", String.class)).isNotBlank();
    assertThat(committed.get("evidence_json", String.class)).isEqualTo(original.canonicalJson());
    assertThat(committed.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
                    .execute(original, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    assertThat(operation(context, original)).isEqualTo(committed);
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void lostPostAcknowledgementClockResponseCannotBeRemintedFromCommittedRetry() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    var clockQueries = new AtomicInteger();
    var executor =
        new AccountGameplayAdmissionOriginalCommitExecutor(
            postCommitClockDataSource(context.dataSource(), 0L, true, clockQueries));
    assertThatThrownBy(() -> executor.execute(original, decision))
        .hasMessageContaining("post-COMMIT clock unavailable");
    assertThat(clockQueries.get()).isEqualTo(1);
    var committed = operation(context, original);
    assertThat(committed.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(committed.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(committed.get("finalization_xid", String.class)).isNotBlank();
    assertThat(committed.get("evidence_json", String.class)).isEqualTo(original.canonicalJson());
    assertThat(committed.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
                    .execute(original, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    assertThat(operation(context, original)).isEqualTo(committed);
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  /** Real database clock faults on the original connection, strictly after successful COMMIT. */
  private static DataSource postCommitClockDataSource(
      DataSource source, long waitUntilMs, boolean loseClockResponse, AtomicInteger clockQueries) {
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        return clockConnection(super.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return clockConnection(super.getConnection(username, password));
      }

      private Connection clockConnection(Connection physical) {
        var committed = new AtomicBoolean();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                    physical.commit();
                    committed.set(true);
                    return null;
                  }
                  if (method.getName().equals("createStatement") && method.getParameterCount() == 0)
                    return clockStatement(physical.createStatement(), committed);
                  try {
                    return method.invoke(physical, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }

      private Statement clockStatement(Statement physical, AtomicBoolean committed) {
        return (Statement)
            Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("executeQuery")
                      && arguments != null
                      && arguments.length == 1
                      && "SELECT ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint"
                          .equals(arguments[0])) {
                    assertThat(committed.get()).isTrue();
                    clockQueries.incrementAndGet();
                    if (waitUntilMs > 0) {
                      // Delay only this test's post-ACK clock query using database time.
                      physical.execute(
                          "SELECT pg_sleep(GREATEST(0, ("
                              + waitUntilMs
                              + " - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))");
                    }
                    if (loseClockResponse) {
                      try (ResultSet discarded = physical.executeQuery((String) arguments[0])) {
                        assertThat(discarded.next()).isTrue();
                      }
                      throw new SQLException("Test lost post-COMMIT clock response", "08006");
                    }
                  }
                  try {
                    return method.invoke(physical, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  @Test
  void originalExecutorRejectsCommittedRetryWithoutRefreshingEvidence() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    var executor = new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource());
    var acknowledgement = executor.execute(original, decision);
    var committed = operation(context, original);
    assertThatThrownBy(() -> executor.execute(original, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    assertThat(operation(context, original)).isEqualTo(committed);
    assertThat(operation(context, original).get("finalization_xid", String.class))
        .isEqualTo(acknowledgement.finalizationXid());
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void lostOriginalCommitAcknowledgementDeniesDespiteActualCommittedReadback() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    var physicalCommits = new AtomicInteger();
    var executor =
        new AccountGameplayAdmissionOriginalCommitExecutor(
            originalCommitFailureDataSource(context.dataSource(), true, physicalCommits));
    assertThatThrownBy(() -> executor.execute(original, decision))
        .hasMessageContaining("Original Account physical COMMIT unavailable");
    assertThat(physicalCommits.get()).isEqualTo(1);
    var committed = operation(context, original);
    assertThat(committed.get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(committed.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(committed.get("finalization_xid", String.class)).isNotBlank();
    assertThat(committed.get("evidence_json", String.class)).isEqualTo(original.canonicalJson());
    assertThat(committed.get("evidence_sha256", String.class)).isEqualTo(original.sha256());
    assertThat(committed.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    // Retry on a normal physical connection cannot turn raw visibility into the missing ACK.
    assertThatThrownBy(
            () ->
                new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
                    .execute(original, decision))
        .hasMessageContaining("Fresh owned Account original COMMIT required");
    assertThat(operation(context, original)).isEqualTo(committed);
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void rejectionBeforeOriginalPhysicalCommitRollsBackPendingWithoutAcknowledgement() {
    var context = context(null);
    var original = pending(context, account(context));
    var unchanged = storageSnapshot(context);
    var physicalCommits = new AtomicInteger();
    var executor =
        new AccountGameplayAdmissionOriginalCommitExecutor(
            originalCommitFailureDataSource(context.dataSource(), false, physicalCommits));
    assertThatThrownBy(() -> executor.execute(original, UUID.randomUUID()))
        .hasMessageContaining("Original Account physical COMMIT unavailable");
    assertThat(physicalCommits.get()).isZero();
    assertThat(operation(context, original).get("status", String.class)).isEqualTo("PENDING");
    assertThat(operation(context, original).get("finalization_xid", String.class)).isNull();
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  /** Test-only transport fault around real JDBC COMMIT, with no production success injection. */
  private static DataSource originalCommitFailureDataSource(
      DataSource source, boolean afterPhysicalCommit, AtomicInteger physicalCommits) {
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        return faultConnection(super.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return faultConnection(super.getConnection(username, password));
      }

      private Connection faultConnection(Connection physical) {
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                    if (!afterPhysicalCommit)
                      throw new SQLException("Test rejection before physical COMMIT", "08006");
                    physical.commit();
                    physicalCommits.incrementAndGet();
                    throw new SQLException("Test lost original COMMIT acknowledgement", "08006");
                  }
                  try {
                    return method.invoke(physical, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  @Test
  void actualAbortOwnerRecoversLostResponseAndRetainsOriginalDecisionAndCleanup() throws Exception {
    var context = context(null);
    var original = pending(context, account(context));
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var transactionManager = new DataSourceTransactionManager(context.dataSource());
    var owner = new AccountGameplayAdmissionAbortOwner(repository, transactionManager, "test");
    var readOwner = new AccountGameplayAdmissionReadOwner(repository, transactionManager, "test");
    UUID decision = UUID.randomUUID();
    var request =
        AbortGameplayAdmissionLeaseRequest.newBuilder()
            .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(original))
            .setBindingDecisionId(decision.toString())
            .build();
    // The trusted context is synthetic: this exercises real SQL ownership, never actual mTLS.
    syntheticAbortPeer().call(() -> owner.abort(request)); // Discard the first response.
    var stored = tx(context, () -> repository.readExact(original).orElseThrow());
    var recoveredAfterLostAbortAck =
        syntheticReadPeer().call(() -> readOwner.read(readRequest(original)));
    assertThat(recoveredAfterLostAbortAck).isEqualTo(stored);
    assertThat(recoveredAfterLostAbortAck.state()).isEqualTo(State.ABORTED);
    assertThat(recoveredAfterLostAbortAck.evidence().canonicalJson())
        .isEqualTo(original.canonicalJson());
    assertThat(recoveredAfterLostAbortAck.evidence().sha256()).isEqualTo(original.sha256());
    assertThat(recoveredAfterLostAbortAck.orphanCleanupId()).isEqualTo(stored.orphanCleanupId());
    assertThat(recoveredAfterLostAbortAck.hasPendingOrphanCleanup()).isTrue();
    var replay = syntheticAbortPeer().call(() -> owner.abort(request));
    assertThat(replay).isEqualTo(stored);
    assertThat(replay.state()).isEqualTo(State.ABORTED);
    assertThat(replay.bindingDecisionId()).isEqualTo(decision);
    assertThat(replay.orphanCleanupId()).isNotNull();
    assertThat(replay.evidence().canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(replay.evidence().sha256()).isEqualTo(original.sha256());
    assertThat(AccountGameplayAdmissionLeaseWireCodec.encodeReference(replay.evidence()))
        .isEqualTo(request.getLease());
    for (var changed :
        List.of(
            request.toBuilder().clearBindingDecisionId().build(),
            request.toBuilder().setBindingDecisionId(UUID.randomUUID().toString()).build())) {
      assertThatThrownBy(() -> syntheticAbortPeer().call(() -> owner.abort(changed)))
          .isInstanceOf(AccountGameplayAdmissionAbortOwner.AbortDeniedException.class)
          .satisfies(
              failure ->
                  assertThat(
                          ((AccountGameplayAdmissionAbortOwner.AbortDeniedException) failure)
                              .code())
                      .isEqualTo("IDEMPOTENCY_CONFLICT"));
      assertThat(tx(context, () -> repository.readExact(original))).contains(stored);
    }
  }

  @Test
  void concurrentActualAbortOwnersRetainOneCleanupAndDenySubstitutionOrCommittedLease()
      throws Exception {
    var context = context(null);
    UUID account = account(context);
    var original = pending(context, account);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var owner =
        new AccountGameplayAdmissionAbortOwner(
            repository, new DataSourceTransactionManager(context.dataSource()), "test");
    var request =
        AbortGameplayAdmissionLeaseRequest.newBuilder()
            .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(original))
            .build();
    var start = new CountDownLatch(1);
    Callable<AccountGameplayAdmissionLeaseOperation> abort =
        () -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          try {
            // DriverManagerDataSource supplies each thread's owned transaction its own SQL
            // connection.
            return syntheticAbortPeer().call(() -> owner.abort(request));
          } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause != null) {
              if (cause instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState()))
                return null;
              cause = cause.getCause();
            }
            throw failure;
          }
        };
    AccountGameplayAdmissionLeaseOperation left;
    AccountGameplayAdmissionLeaseOperation right;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(abort);
      var second = executor.submit(abort);
      start.countDown();
      left = first.get(30, TimeUnit.SECONDS);
      right = second.get(30, TimeUnit.SECONDS);
    }
    assertThat(left != null || right != null).isTrue();
    var replay = syntheticAbortPeer().call(() -> owner.abort(request));
    if (left != null) assertThat(left).isEqualTo(replay);
    if (right != null) assertThat(right).isEqualTo(replay);
    assertThat(replay.state()).isEqualTo(State.ABORTED);
    assertThat(replay.bindingDecisionId()).isNull();
    assertThat(replay.orphanCleanupId()).isNotNull();
    assertThat(tx(context, () -> repository.readExact(original))).contains(replay);
    assertThat(context.dsl().fetchCount(DSL.table("account_gameplay_admission_lease_operations")))
        .isEqualTo(1);
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT count(DISTINCT orphan_cleanup_id) AS cleanups FROM account_gameplay_admission_lease_operations"))
                .get("cleanups", Long.class))
        .isEqualTo(1L);
    var changedCarrier = new LinkedHashMap<>(original.carrier());
    changedCarrier.put("leaseId", UUID.randomUUID().toString());
    var changed =
        request.toBuilder()
            .setLease(
                AccountGameplayAdmissionLeaseWireCodec.encodeReference(
                    AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier)))
            .build();
    assertThatThrownBy(() -> syntheticAbortPeer().call(() -> owner.abort(changed)))
        .isInstanceOf(AccountGameplayAdmissionAbortOwner.AbortDeniedException.class);
    var mismatchedRequest =
        request.toBuilder()
            .setLease(request.getLease().toBuilder().setRequestId(UUID.randomUUID().toString()))
            .build();
    assertThatThrownBy(() -> syntheticAbortPeer().call(() -> owner.abort(mismatchedRequest)))
        .isInstanceOf(IllegalArgumentException.class);
    var changedDecision =
        request.toBuilder().setBindingDecisionId(UUID.randomUUID().toString()).build();
    assertThatThrownBy(() -> syntheticAbortPeer().call(() -> owner.abort(changedDecision)))
        .isInstanceOf(AccountGameplayAdmissionAbortOwner.AbortDeniedException.class);
    assertThat(tx(context, () -> repository.readExact(original))).contains(replay);

    var committedEvidence = pending(context, account);
    var committed =
        tx(context, () -> repository.recordCommitted(committedEvidence, UUID.randomUUID()));
    var committedRequest =
        request.toBuilder()
            .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(committedEvidence))
            .build();
    assertThatThrownBy(() -> syntheticAbortPeer().call(() -> owner.abort(committedRequest)))
        .isInstanceOf(AccountGameplayAdmissionAbortOwner.AbortDeniedException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((AccountGameplayAdmissionAbortOwner.AbortDeniedException) failure).code())
                    .isEqualTo("ADMISSION_LEASE_NOT_ABORTABLE"));
    assertThat(tx(context, () -> repository.readExact(committedEvidence))).contains(committed);
    assertThat(committed.orphanCleanupId()).isNull();
  }

  @Test
  void exactReadRejectsMissingRawCommittedAndMismatchedOperations() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var readOwner =
        new AccountGameplayAdmissionReadOwner(
            repository, new DataSourceTransactionManager(context.dataSource()), "test");

    var allocation =
        tx(context, () -> repository.allocate(account, UUID.randomUUID(), UUID.randomUUID()));
    var missing = evidence(allocation);
    assertFailedExactRead(readOwner, missing);
    assertThat(tx(context, () -> repository.readExact(missing))).isEmpty();

    var original = pending(context, account);
    var changedCarrier = new LinkedHashMap<>(original.carrier());
    changedCarrier.put(
        "leaseFence", Long.toString(Long.parseLong((String) changedCarrier.get("leaseFence")) + 1));
    assertFailedExactRead(
        readOwner, AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier));
    assertThat(tx(context, () -> repository.readExact(original).orElseThrow().state()))
        .isEqualTo(State.PENDING);

    var committedEvidence = pending(context, account);
    var committed =
        tx(context, () -> repository.recordCommitted(committedEvidence, UUID.randomUUID()));
    assertThat(committed.state()).isEqualTo(State.COMMITTED);
    assertFailedExactRead(readOwner, committedEvidence);
    assertThat(tx(context, () -> repository.readExact(committedEvidence))).contains(committed);
  }

  /** Synthetic trusted caller only; the existing carriers remain fabricated storage fixtures. */
  private static io.grpc.Context syntheticAbortPeer() {
    return io.grpc.Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
                .orElseThrow());
  }

  /** Synthetic peer only; neither this identity nor the fabricated carrier proves mTLS. */
  private static io.grpc.Context syntheticReadPeer() {
    return io.grpc.Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
                .orElseThrow());
  }

  private static ReadGameplayAdmissionLeaseRequest readRequest(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    return ReadGameplayAdmissionLeaseRequest.newBuilder()
        .setRequestId((String) evidence.carrier().get("requestId"))
        .setExpectedLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence))
        .build();
  }

  private static void assertFailedExactRead(
      AccountGameplayAdmissionReadOwner owner, AccountGameplayAdmissionLeaseEvidence evidence) {
    assertThatThrownBy(() -> syntheticReadPeer().call(() -> owner.read(readRequest(evidence))))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  @Test
  void lostResponseRecoveryUsesOriginalRequestWithoutReconstructingServerLeaseIdentity() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    var allocation = tx(context, () -> repository.allocate(account, request, lease));
    assertThat(tx(context, () -> repository.findAllocation(account, request))).contains(allocation);
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .isEmpty();
    var original = evidence(allocation);
    var pending = tx(context, () -> repository.beginPending(original));
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .contains(pending);
    UUID decision = UUID.randomUUID();
    var committed = tx(context, () -> repository.recordCommitted(original, decision));
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account, request, "spiffe://firemud/ns/test/sa/game-session-service")))
        .contains(committed);
    assertThat(tx(context, () -> repository.findAllocation(account, request))).contains(allocation);
    UUID wrongAccount = account(context);
    assertThatThrownBy(() -> tx(context, () -> repository.findAllocation(wrongAccount, request)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        repository.findByRequest(
                            wrongAccount,
                            request,
                            "spiffe://firemud/ns/test/sa/game-session-service")))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        repository.findByRequest(
                            account, request, "spiffe://firemud/ns/other/sa/game-session-service")))
        .hasMessageContaining("identity conflict");
    UUID missingRequest = UUID.randomUUID();
    assertThat(tx(context, () -> repository.findAllocation(account, missingRequest))).isEmpty();
    assertThat(
            tx(
                context,
                () ->
                    repository.findByRequest(
                        account,
                        missingRequest,
                        "spiffe://firemud/ns/test/sa/game-session-service")))
        .isEmpty();
    assertThat(context.dsl().fetchCount(DSL.table("account_gameplay_admission_lease_allocations")))
        .isEqualTo(1);
  }

  @Test
  void freshMigrationStoresExactReplayAndRejectsChangedBaselineDigestOrAllocation() {
    var context = context(null);
    UUID account = account(context);
    var evidence = pending(context, account);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    assertThat(tx(context, () -> repository.beginPending(evidence)).evidence()).isEqualTo(evidence);
    var changed = new LinkedHashMap<>(evidence.carrier());
    changed.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "INACTIVE",
            "membershipVersion",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            "1"));
    var changedEvidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(changedEvidence)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET evidence_sha256 = ? WHERE request_id = ?",
                                "b".repeat(64),
                                UUID.fromString((String) evidence.carrier().get("requestId")))))
        .isInstanceOf(RuntimeException.class);
    changed.put("requestId", UUID.randomUUID().toString());
    changed.put("leaseId", UUID.randomUUID().toString());
    var unallocated = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(unallocated)))
        .hasMessageContaining("identity conflict");
    var otherAllocation =
        tx(context, () -> repository.allocate(account, UUID.randomUUID(), UUID.randomUUID()));
    var substituted = new LinkedHashMap<>(evidence.carrier());
    substituted.put("leaseFence", Long.toString(otherAllocation.leaseFence()));
    var substitutedEvidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(substituted);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(substitutedEvidence)))
        .hasMessageContaining("identity conflict");
    var original = evidence(otherAllocation);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "INSERT INTO account_gameplay_admission_lease_operations "
                                    + "(account_uuid, request_id, lease_id, lease_fence, caller_workload, evidence_json, "
                                    + "evidence_sha256, evaluated_at_ms, expires_at_ms, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                                account,
                                otherAllocation.requestId(),
                                otherAllocation.leaseId(),
                                otherAllocation.leaseFence(),
                                original.carrier().get("callerWorkload"),
                                original.canonicalJson(),
                                "b".repeat(64),
                                Long.parseLong((String) original.carrier().get("evaluatedAt")),
                                Long.parseLong((String) original.carrier().get("expiresAt")))))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void retainedAccountMigrationPreservesAccountAndAddsIndependentFenceDomain() {
    var context = context("84.4");
    UUID account =
        tx(
            context,
            () ->
                PreRestrictionBirthAccountFixture.create(
                        context.dsl(),
                        "lease-" + UUID.randomUUID(),
                        UUID.randomUUID() + "@example.test",
                        "storage-fixture-only",
                        "PASSWORD")
                    .getAccountUuid());
    migrate(context, null);
    var first = pending(context, account);
    var second = pending(context, account);
    assertThat(first.leaseFence().longValueExact()).isEqualTo(1);
    assertThat(second.leaseFence().longValueExact()).isEqualTo(2);
    assertThat(context.dsl().fetchCount(DSL.table("accounts"))).isEqualTo(1);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_fences SET last_fence = 1 WHERE account_uuid = ?",
                                account)))
        .isInstanceOf(RuntimeException.class);
    for (String table :
        List.of(
            "account_gameplay_admission_lease_fences",
            "account_gameplay_admission_lease_allocations",
            "account_gameplay_admission_lease_operations")) {
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute("DELETE FROM " + table)))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute("TRUNCATE " + table)))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void commitsExactDecisionAndAtomicallyRetainsAbortedOrphanIntentWithTerminalConflict() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var committed = pending(context, account);
    UUID decision = UUID.randomUUID();
    var result = tx(context, () -> repository.recordCommitted(committed, decision));
    assertThat(result.state()).isEqualTo(State.COMMITTED);
    assertThat(tx(context, () -> repository.recordCommitted(committed, decision)))
        .isEqualTo(result);
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordCommitted(committed, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(context, () -> repository.recordAborted(committed, decision, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    var aborted = pending(context, account);
    UUID cleanup = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      repository.recordAborted(aborted, decision, cleanup);
                      throw new IllegalStateException("simulate transaction rollback");
                    }))
        .hasMessageContaining("rollback");
    assertThat(tx(context, () -> repository.readExact(aborted).orElseThrow()).state())
        .isEqualTo(State.PENDING);
    var orphan = tx(context, () -> repository.recordAborted(aborted, decision, cleanup));
    assertThat(orphan.hasPendingOrphanCleanup()).isTrue();
    assertThat(orphan.evidence()).isEqualTo(aborted);
    assertThat(orphan.orphanCleanupId()).isEqualTo(cleanup);
    assertThat(tx(context, () -> repository.recordAborted(aborted, decision, cleanup)))
        .isEqualTo(orphan);
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordAborted(aborted, decision, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
  }

  @Test
  void forwardMigrationPreservesRetainedReceiptsAndAllowsUnknownDecisionAbort() {
    var context = context("88");
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var retainedPending = pending(context, account);
    var retainedKnown = pending(context, account);
    UUID decision = UUID.randomUUID();
    UUID knownCleanup = UUID.randomUUID();
    var known = tx(context, () -> repository.recordAborted(retainedKnown, decision, knownCleanup));
    var retainedCommitted = pending(context, account);
    var committed = tx(context, () -> repository.recordCommitted(retainedCommitted, decision));

    migrate(context, null);

    assertThat(tx(context, () -> repository.readExact(retainedKnown))).contains(known);
    assertThat(tx(context, () -> repository.readExact(retainedCommitted))).contains(committed);
    UUID cleanup = UUID.randomUUID();
    var unknown = tx(context, () -> repository.recordAborted(retainedPending, null, cleanup));
    assertThat(unknown.state()).isEqualTo(State.ABORTED);
    assertThat(unknown.bindingDecisionId()).isNull();
    assertThat(unknown.orphanCleanupId()).isEqualTo(cleanup);
    assertThat(unknown.hasPendingOrphanCleanup()).isTrue();
    assertThat(unknown.evidence()).isEqualTo(retainedPending);
    assertThat(tx(context, () -> repository.recordAborted(retainedPending, null, cleanup)))
        .isEqualTo(unknown);
    assertThat(tx(context, () -> repository.readExact(retainedPending))).contains(unknown);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> repository.recordAborted(retainedPending, UUID.randomUUID(), cleanup)))
        .hasMessageContaining("identity conflict");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> repository.recordAborted(retainedPending, null, UUID.randomUUID())))
        .hasMessageContaining("identity conflict");
    // The unchanged V87 trigger prevents a direct rewrite of the unknown terminal observation.
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET binding_decision_id = ? WHERE request_id = ?",
                                UUID.randomUUID(),
                                UUID.fromString(
                                    (String) retainedPending.carrier().get("requestId")))))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void relaxedAbortShapeStillRejectsMissingCleanupAndCommittedWithoutDecision() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var original = pending(context, account);
    UUID request = UUID.fromString((String) original.carrier().get("requestId"));
    assertThatThrownBy(() -> tx(context, () -> repository.recordAborted(original, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tx(context, () -> repository.recordCommitted(original, null)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String state : List.of("ABORTED", "COMMITTED")) {
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () ->
                          context
                              .dsl()
                              .execute(
                                  "UPDATE account_gameplay_admission_lease_operations SET status = ? WHERE request_id = ?",
                                  state,
                                  request)))
          .isInstanceOf(RuntimeException.class);
    }
    assertThat(tx(context, () -> repository.readExact(original).orElseThrow()).state())
        .isEqualTo(State.PENDING);
  }

  @Test
  void expiredPendingCannotCommitOrRenewButKeepsExactReadbackAndCanAbort() throws Exception {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var evidence = pending(context, account);
    tx(context, () -> context.dsl().fetchOne("SELECT pg_sleep(16)"));
    assertThat(tx(context, () -> repository.beginPending(evidence)).evidence()).isEqualTo(evidence);
    var readOwner =
        new AccountGameplayAdmissionReadOwner(
            repository, new DataSourceTransactionManager(context.dataSource()), "test");
    var expiredRead = syntheticReadPeer().call(() -> readOwner.read(readRequest(evidence)));
    assertThat(expiredRead.state()).isEqualTo(State.PENDING);
    assertThat(expiredRead.evidence().canonicalJson()).isEqualTo(evidence.canonicalJson());
    assertThat(expiredRead.evidence().sha256()).isEqualTo(evidence.sha256());
    assertThat(expiredRead.bindingDecisionId()).isNull();
    assertThat(expiredRead.orphanCleanupId()).isNull();
    assertThatThrownBy(
            () -> tx(context, () -> repository.recordCommitted(evidence, UUID.randomUUID())))
        .hasMessageContaining("deadline expired");
    var changed = new LinkedHashMap<>(evidence.carrier());
    long now = Instant.now().toEpochMilli();
    changed.put("evaluatedAt", Long.toString(now));
    changed.put("expiresAt", Long.toString(now + 15000));
    var renewed = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> tx(context, () -> repository.beginPending(renewed)))
        .hasMessageContaining("identity conflict");
    var aborted = tx(context, () -> repository.recordAborted(evidence, null, UUID.randomUUID()));
    assertThat(aborted.state()).isEqualTo(State.ABORTED);
    var expiredAbortRead = syntheticReadPeer().call(() -> readOwner.read(readRequest(evidence)));
    assertThat(expiredAbortRead).isEqualTo(aborted);
    assertThat(expiredAbortRead.evidence().canonicalJson()).isEqualTo(evidence.canonicalJson());
    assertThat(expiredAbortRead.evidence().sha256()).isEqualTo(evidence.sha256());
    assertThat(expiredAbortRead.bindingDecisionId()).isNull();
    assertThat(expiredAbortRead.orphanCleanupId()).isEqualTo(aborted.orphanCleanupId());
    assertThat(expiredAbortRead.hasPendingOrphanCleanup()).isTrue();
  }

  @Test
  void concurrentDuplicatesReplayOneAllocationAndDistinctRequestsAdvanceFence() throws Exception {
    var context = context(null);
    UUID account = account(context);
    UUID request = UUID.randomUUID();
    UUID lease = UUID.randomUUID();
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var start = new CountDownLatch(1);
    Callable<AccountGameplayAdmissionLeaseRepository.Allocation> duplicate =
        () -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return retrySerialization(
              () -> tx(context, () -> repository.allocate(account, request, lease)));
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(duplicate);
      var right = executor.submit(duplicate);
      start.countDown();
      assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(right.get(30, TimeUnit.SECONDS));
      var a =
          executor.submit(
              () ->
                  retrySerialization(
                      () ->
                          tx(
                              context,
                              () ->
                                  repository.allocate(
                                      account, UUID.randomUUID(), UUID.randomUUID()))));
      var b =
          executor.submit(
              () ->
                  retrySerialization(
                      () ->
                          tx(
                              context,
                              () ->
                                  repository.allocate(
                                      account, UUID.randomUUID(), UUID.randomUUID()))));
      assertThat(
              List.of(
                  a.get(30, TimeUnit.SECONDS).leaseFence(),
                  b.get(30, TimeUnit.SECONDS).leaseFence()))
          .containsExactlyInAnyOrder(2L, 3L);
    }
  }

  @Test
  void durableConfirmationStoresOriginalCommitProofAndRecoversLostResponseAfterExpiry()
      throws Exception {
    var context = context(null);
    UUID account = account(context);
    var original = pending(context, account);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(original, decision));
    var unchanged = storageSnapshot(context);
    var transactionManager = new DataSourceTransactionManager(context.dataSource());
    var confirmationOwner =
        new AccountGameplayAdmissionCommitConfirmationOwner(context.dataSource(), "test");
    var request =
        FinalizeGameplayAdmissionLeaseRequest.newBuilder()
            .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(original))
            .setBindingDecisionId(decision.toString())
            .build();

    // The loopback peer, carrier and decision are synthetic upstream fixtures. The real Java
    // owner creates and independently reads Account SQL proof; this does not prove mTLS.
    syntheticReadPeer().call(() -> confirmationOwner.confirm(request)); // Discard the response.
    // Inspect stored fields separately; this visible row alone is not durable readback proof.
    var receipt =
        Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT * FROM account_gameplay_admission_commit_confirmations WHERE request_id = ?",
                    requestId(original)));
    assertThat(receipt.get("confirmation_version", Short.class)).isEqualTo((short) 1);
    assertThat(receipt.get("request_id", UUID.class)).isEqualTo(requestId(original));
    assertThat(receipt.get("account_uuid", UUID.class)).isEqualTo(account);
    assertThat(receipt.get("lease_id", UUID.class))
        .isEqualTo(UUID.fromString((String) original.carrier().get("leaseId")));
    assertThat(receipt.get("lease_fence", Long.class))
        .isEqualTo(original.leaseFence().longValueExact());
    assertThat(receipt.get("evidence_sha256", String.class)).isEqualTo(original.sha256());
    assertThat(receipt.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(receipt.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThat(receipt.get("committed_before_ms", Long.class))
        .isPositive()
        .isLessThan(expiresAt(original));
    assertThat(receipt.get("finalization_xid", String.class))
        .isEqualTo(operation(context, original).get("finalization_xid", String.class));
    assertThat(receipt.get("confirmation_xid", String.class))
        .isNotEqualTo(receipt.get("finalization_xid", String.class));
    assertThat(
            Objects.requireNonNull(
                    context
                        .dsl()
                        .fetchOne(
                            "SELECT ?::pg_lsn >= ?::pg_lsn AS covered",
                            receipt.get("wal_flush_lsn", String.class),
                            receipt.get("wal_insert_lsn", String.class)))
                .get("covered", Boolean.class))
        .isTrue();
    // Recovery opens the real owner's fresh transaction and covers the independently committed
    // receipt's WAL before returning the immutable DTO, without reconstructing any proof fields.
    var recovered = syntheticReadPeer().call(() -> confirmationOwner.read(request));
    assertThat(recovered.operation().state()).isEqualTo(State.COMMITTED);
    assertThat(recovered.operation().evidence().canonicalJson())
        .isEqualTo(original.canonicalJson());
    assertThat(recovered.operation().evidence().sha256()).isEqualTo(original.sha256());
    assertThat(recovered.operation().bindingDecisionId()).isEqualTo(decision);
    assertThat(recovered.requestId()).isEqualTo(receipt.get("request_id", UUID.class));
    assertThat(recovered.accountId()).isEqualTo(receipt.get("account_uuid", UUID.class));
    assertThat(recovered.leaseId()).isEqualTo(receipt.get("lease_id", UUID.class));
    assertThat(recovered.leaseFence()).isEqualTo(receipt.get("lease_fence", Long.class));
    assertThat(recovered.confirmationVersion())
        .isEqualTo(receipt.get("confirmation_version", Short.class));
    assertThat(recovered.evidenceSha256()).isEqualTo(receipt.get("evidence_sha256", String.class));
    assertThat(recovered.bindingDecisionId())
        .isEqualTo(receipt.get("binding_decision_id", UUID.class));
    assertThat(recovered.expiresAtMs()).isEqualTo(receipt.get("expires_at_ms", Long.class));
    assertThat(recovered.committedBeforeMs())
        .isEqualTo(receipt.get("committed_before_ms", Long.class));
    assertThat(recovered.finalizationXid())
        .isEqualTo(receipt.get("finalization_xid", String.class));
    assertThat(recovered.confirmationXid())
        .isEqualTo(receipt.get("confirmation_xid", String.class));
    assertThat(recovered.walInsertLsn()).isEqualTo(receipt.get("wal_insert_lsn", String.class));
    assertThat(recovered.walFlushLsn()).isEqualTo(receipt.get("wal_flush_lsn", String.class));
    waitPastDeadline(context, original);
    assertThat(tx(context, () -> confirm(context, original, decision))).isEqualTo(receipt);
    assertThat(syntheticReadPeer().call(() -> confirmationOwner.read(request)))
        .isEqualTo(recovered);
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
    var readOwner = new AccountGameplayAdmissionReadOwner(repository, transactionManager, "test");
    assertFailedExactRead(readOwner, original);
  }

  @Test
  void receiptPhysicalCommitPrecedesIndependentV90ReadOnAnotherConnection() throws Exception {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
        .execute(original, decision);
    var trace = new ReceiptCommitTrace();
    var owner =
        new AccountGameplayAdmissionCommitConfirmationOwner(
            receiptCommitDataSource(context.dataSource(), ReceiptCommitFault.NONE, trace), "test");

    var receipt =
        syntheticReadPeer().call(() -> owner.confirm(confirmationRequest(original, decision)));

    assertThat(trace.count("physical-commit")).isEqualTo(2);
    assertThat(trace.count("v90-read")).isEqualTo(1);
    var receiptCommit = firstEvent(trace, "physical-commit");
    var independentRead = firstEvent(trace, "v90-read");
    assertThat(trace.events().indexOf(receiptCommit))
        .isLessThan(trace.events().indexOf(independentRead));
    assertThat(independentRead.connectionId()).isNotEqualTo(receiptCommit.connectionId());
    assertThat(receipt.operation().evidence().canonicalJson()).isEqualTo(original.canonicalJson());
    assertThat(receipt.operation().evidence().sha256()).isEqualTo(original.sha256());
    assertThat(receipt.bindingDecisionId()).isEqualTo(decision);
    assertThat(receipt.expiresAtMs()).isEqualTo(expiresAt(original));
    assertThat(receipt.committedBeforeMs()).isPositive().isLessThan(expiresAt(original));
    assertThat(receipt.confirmationXid()).isNotEqualTo(receipt.finalizationXid());
    assertThat(operation(context, original).get("finalization_xid", String.class))
        .isEqualTo(receipt.finalizationXid());
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isEqualTo(1);
  }

  @Test
  void lostReceiptCommitAcknowledgementDeniesThenRecoversOnlyThroughIndependentV90Read()
      throws Exception {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
        .execute(original, decision);
    var trace = new ReceiptCommitTrace();
    var owner =
        new AccountGameplayAdmissionCommitConfirmationOwner(
            receiptCommitDataSource(
                context.dataSource(), ReceiptCommitFault.AFTER_FIRST_COMMIT, trace),
            "test");
    var request = confirmationRequest(original, decision);

    assertThatThrownBy(() -> syntheticReadPeer().call(() -> owner.confirm(request)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
    assertThat(trace.count("physical-commit")).isEqualTo(1);
    assertThat(trace.count("v90-read")).isZero();

    var retainedRow = confirmationRow(context, original);
    assertThat(retainedRow.get("request_id", UUID.class)).isEqualTo(requestId(original));
    assertThat(retainedRow.get("evidence_sha256", String.class)).isEqualTo(original.sha256());
    assertThat(retainedRow.get("binding_decision_id", UUID.class)).isEqualTo(decision);
    assertThat(retainedRow.get("expires_at_ms", Long.class)).isEqualTo(expiresAt(original));
    assertThat(retainedRow.get("committed_before_ms", Long.class))
        .isPositive()
        .isLessThan(expiresAt(original));
    assertThat(retainedRow.get("finalization_xid", String.class))
        .isEqualTo(operation(context, original).get("finalization_xid", String.class));
    assertThat(retainedRow.get("confirmation_xid", String.class))
        .isNotEqualTo(retainedRow.get("finalization_xid", String.class));

    // Recovery is an explicit fresh owner read, which invokes the existing V90 independent WAL
    // coverage observer. No confirm retry can mint or acknowledge the lost receipt COMMIT.
    var recovered = syntheticReadPeer().call(() -> owner.read(request));
    assertThat(trace.count("v90-read")).isEqualTo(1);
    var lostAckCommit = firstEvent(trace, "physical-commit");
    var independentRead = firstEvent(trace, "v90-read");
    assertThat(trace.events().indexOf(lostAckCommit))
        .isLessThan(trace.events().indexOf(independentRead));
    assertThat(independentRead.connectionId()).isNotEqualTo(lostAckCommit.connectionId());
    assertThat(recovered.requestId()).isEqualTo(retainedRow.get("request_id", UUID.class));
    assertThat(recovered.evidenceSha256())
        .isEqualTo(retainedRow.get("evidence_sha256", String.class));
    assertThat(recovered.bindingDecisionId())
        .isEqualTo(retainedRow.get("binding_decision_id", UUID.class));
    assertThat(recovered.expiresAtMs()).isEqualTo(retainedRow.get("expires_at_ms", Long.class));
    assertThat(recovered.committedBeforeMs())
        .isEqualTo(retainedRow.get("committed_before_ms", Long.class));
    assertThat(recovered.finalizationXid())
        .isEqualTo(retainedRow.get("finalization_xid", String.class));
    assertThat(recovered.confirmationXid())
        .isEqualTo(retainedRow.get("confirmation_xid", String.class));
    assertThat(confirmationRow(context, original).intoMap()).isEqualTo(retainedRow.intoMap());
  }

  @Test
  void receiptCommitFailureBeforePhysicalDelegateRollsBackWithoutReceiptOrIndependentRead() {
    var context = context(null);
    var original = pending(context, account(context));
    UUID decision = UUID.randomUUID();
    new AccountGameplayAdmissionOriginalCommitExecutor(context.dataSource())
        .execute(original, decision);
    var committedOperation = operation(context, original).intoMap();
    var unchanged = storageSnapshot(context);
    var trace = new ReceiptCommitTrace();
    var owner =
        new AccountGameplayAdmissionCommitConfirmationOwner(
            receiptCommitDataSource(
                context.dataSource(), ReceiptCommitFault.BEFORE_FIRST_COMMIT, trace),
            "test");

    assertThatThrownBy(
            () ->
                syntheticReadPeer()
                    .call(() -> owner.confirm(confirmationRequest(original, decision))))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(Status.fromThrowable(failure).getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE));
    assertThat(trace.count("commit-attempt")).isEqualTo(1);
    assertThat(trace.count("physical-commit")).isZero();
    assertThat(trace.count("v90-read")).isZero();
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    assertThat(operation(context, original).intoMap()).isEqualTo(committedOperation);
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void idlePrimaryConfirmationCapturesBeforeLocksAndIndependentlyReadsExactReceipt() {
    var context = context(null);
    var original = pending(context, account(context));
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(original, decision));
    var unchanged = storageSnapshot(context);

    String finalizationXid = operation(context, original).get("finalization_xid", String.class);
    // No marker, forced flush or unrelated writer is used to make proof succeed. Establish a
    // fixed snapshot before the call and prove the original finalizer is independently visible.
    // Background WAL may advance, so a pre-call global LSN need not equal the stored fence.
    var receipt =
        tx(
            context,
            () -> {
              assertThat(
                      Objects.requireNonNull(
                              context
                                  .dsl()
                                  .fetchOne(
                                      "SELECT pg_visible_in_snapshot(?::xid8, pg_current_snapshot()) "
                                          + "AND pg_current_xact_id_if_assigned() IS NULL AS independent",
                                      finalizationXid))
                          .get("independent", Boolean.class))
                  .isTrue();
              String beforeHeapReads =
                  Objects.requireNonNull(
                          context.dsl().fetchOne("SELECT pg_current_wal_insert_lsn()::text AS lsn"))
                      .get("lsn", String.class);
              var created = confirm(context, original, decision);
              assertThat(
                      Objects.requireNonNull(
                              context
                                  .dsl()
                                  .fetchOne(
                                      "SELECT ?::pg_lsn >= ?::pg_lsn "
                                          + "AND ?::pg_lsn >= ?::pg_lsn AS snapshot_fence_covered",
                                      created.get("wal_insert_lsn", String.class),
                                      beforeHeapReads,
                                      created.get("wal_flush_lsn", String.class),
                                      created.get("wal_insert_lsn", String.class)))
                          .get("snapshot_fence_covered", Boolean.class))
                  .isTrue();
              return created;
            });

    tx(
        context,
        () -> {
          assertThat(
                  Objects.requireNonNull(
                          context
                              .dsl()
                              .fetchOne(
                                  "SELECT pg_visible_in_snapshot(?::xid8, pg_current_snapshot()) "
                                      + "AND pg_current_xact_id_if_assigned() IS NULL AS independent",
                                  receipt.get("confirmation_xid", String.class)))
                      .get("independent", Boolean.class))
              .isTrue();
          assertThat(readConfirmation(context, original, decision)).isEqualTo(receipt);
          return null;
        });
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void staleSerializableConfirmationInsertRetriesInsteadOfLeakingUniqueViolation()
      throws Exception {
    var context = context(null);
    var original = pending(context, account(context));
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(original, decision));
    var unchanged = storageSnapshot(context);
    var snapshotReady = new CountDownLatch(1);
    var winnerCommitted = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var stale =
          executor.submit(
              () ->
                  tx(
                      context,
                      () -> {
                        assertThat(
                                context
                                    .dsl()
                                    .fetchCount(
                                        DSL.table(
                                            "account_gameplay_admission_commit_confirmations")))
                            .isZero();
                        snapshotReady.countDown();
                        try {
                          if (!winnerCommitted.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("winner commit timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return confirm(context, original, decision);
                      }));
      Record retained;
      try {
        assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
        retained = tx(context, () -> confirm(context, original, decision));
      } finally {
        winnerCommitted.countDown();
      }
      assertThatThrownBy(() -> stale.get(30, TimeUnit.SECONDS))
          .satisfies(
              failure -> {
                Throwable cause = failure;
                while (cause != null && !(cause instanceof java.sql.SQLException))
                  cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("40001");
              });
      assertThat(tx(context, () -> confirm(context, original, decision))).isEqualTo(retained);
      assertThat(tx(context, () -> readConfirmation(context, original, decision)))
          .isEqualTo(retained);
    }
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isEqualTo(1);
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void updateBeforeExpiryButPhysicalCommitAfterExpiryCannotCreateConfirmation() {
    var context = context(null);
    var original = pending(context, account(context), 1000);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(
        context,
        () -> {
          repository.recordCommitted(original, decision);
          assertThat(databaseNow(context)).isLessThan(expiresAt(original));
          waitPastDeadline(context, original);
          return null;
        });
    assertThat(operation(context, original).get("status", String.class)).isEqualTo("COMMITTED");
    assertThat(operation(context, original).get("finalization_xid", String.class)).isNotNull();
    var unchanged = storageSnapshot(context);
    assertThatThrownBy(() -> tx(context, () -> confirm(context, original, decision)))
        .hasMessageContaining("original deadline expired");
    assertThatThrownBy(() -> tx(context, () -> readConfirmation(context, original, decision)))
        .hasMessageContaining("exact durable receipt required");
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void sameTransactionAndReleasedSubtransactionCannotConfirmOriginalFinalization() {
    var context = context(null);
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    for (boolean subtransaction : List.of(false, true)) {
      var original = pending(context, account);
      UUID decision = UUID.randomUUID();
      tx(
          context,
          () -> {
            if (subtransaction) context.dsl().execute("SAVEPOINT original_finalization");
            repository.recordCommitted(original, decision);
            if (subtransaction) context.dsl().execute("RELEASE SAVEPOINT original_finalization");
            var stamp = operation(context, original).get("finalization_xid", String.class);
            assertThat(stamp)
                .isEqualTo(
                    Objects.requireNonNull(
                            context.dsl().fetchOne("SELECT pg_current_xact_id()::text AS xid"))
                        .get("xid", String.class));
            assertDeniedInSavepoint(
                context,
                () -> confirm(context, original, decision),
                "requires independent finalization commit");
            assertDeniedInSavepoint(
                context,
                () ->
                    context
                        .dsl()
                        .execute(
                            "INSERT INTO account_gameplay_admission_commit_confirmations(request_id, evidence_sha256, binding_decision_id) VALUES (?, ?, ?)",
                            requestId(original),
                            original.sha256(),
                            decision),
                "requires independent finalization commit");
            return null;
          });
    }
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
  }

  @Test
  void missingPendingRolledBackAbortedAndRetainedUnstampedCommitsStayUnproved() {
    var context = context("89");
    UUID account = account(context);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    var retained = pending(context, account);
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(retained, decision));
    var retainedPending = pending(context, account);
    var aborted = pending(context, account);
    tx(context, () -> repository.recordAborted(aborted, null, UUID.randomUUID()));
    migrate(context, null);
    assertThat(operation(context, retained).get("finalization_xid", String.class)).isNull();
    assertThat(operation(context, retainedPending).get("finalization_xid", String.class)).isNull();
    assertThat(operation(context, aborted).get("finalization_xid", String.class)).isNull();
    var rolledBack = pending(context, account);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () -> {
                      repository.recordCommitted(rolledBack, decision);
                      throw new IllegalStateException("roll back physical finalization");
                    }))
        .hasMessageContaining("roll back physical finalization");
    assertThat(operation(context, rolledBack).get("status", String.class)).isEqualTo("PENDING");
    assertThat(operation(context, rolledBack).get("finalization_xid", String.class)).isNull();
    var unchanged = storageSnapshot(context);
    for (var unproved : List.of(retained, retainedPending, aborted, rolledBack)) {
      assertThatThrownBy(() -> tx(context, () -> confirm(context, unproved, decision)))
          .hasMessageContaining("exact committed binding required");
      assertThatThrownBy(() -> tx(context, () -> readConfirmation(context, unproved, decision)))
          .hasMessageContaining("exact durable receipt required");
    }
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .fetchOne(
                                "SELECT * FROM account_gameplay_admission_confirm_committed(?, ?, ?)",
                                UUID.randomUUID(),
                                retained.sha256(),
                                decision)))
        .hasMessageContaining("operation missing");
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void confirmationRejectsChangedBindingsAndCallerProofAndRemainsImmutable() {
    var context = context(null);
    UUID account = account(context);
    var original = pending(context, account);
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    UUID request = requestId(original);
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET status = 'COMMITTED', binding_decision_id = ?, finalization_xid = '1' WHERE request_id = ?",
                                decision,
                                request)))
        .hasMessageContaining("finalization transaction is database stamped");
    tx(context, () -> repository.recordCommitted(original, decision));
    var unchanged = storageSnapshot(context);
    // SQL owns the transaction prerequisite even for callers bypassing the Java owner.
    for (int isolation :
        List.of(
            TransactionDefinition.ISOLATION_READ_COMMITTED,
            TransactionDefinition.ISOLATION_REPEATABLE_READ)) {
      var unsupported =
          new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
      unsupported.setIsolationLevel(isolation);
      for (Supplier<?> action :
          List.<Supplier<?>>of(
              () -> confirm(context, original, decision),
              () -> readConfirmation(context, original, decision),
              () ->
                  context
                      .dsl()
                      .execute(
                          "INSERT INTO account_gameplay_admission_commit_confirmations(request_id, evidence_sha256, binding_decision_id) VALUES (?, ?, ?)",
                          request,
                          original.sha256(),
                          decision))) {
        assertThatThrownBy(() -> unsupported.execute(status -> action.get()))
            .hasMessageContaining("writable SERIALIZABLE transaction required");
      }
    }
    var readOnly = new TransactionTemplate(new DataSourceTransactionManager(context.dataSource()));
    readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () -> readOnly.execute(status -> readConfirmation(context, original, decision)))
        .hasMessageContaining("writable SERIALIZABLE transaction required");
    assertThatThrownBy(() -> readOnly.execute(status -> confirm(context, original, decision)))
        .hasMessageContaining("read-only transaction");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .fetchOne(
                                "SELECT * FROM account_gameplay_admission_confirm_committed(?, ?, ?)",
                                request,
                                "b".repeat(64),
                                decision)))
        .hasMessageContaining("exact committed binding required");
    assertThatThrownBy(() -> tx(context, () -> confirm(context, original, UUID.randomUUID())))
        .hasMessageContaining("exact committed binding required");
    // Every proof/identity field is database-derived even if supplied values look plausible.
    for (String assignment :
        List.of(
            "account_uuid = '" + account + "'::uuid",
            "lease_id = '" + original.carrier().get("leaseId") + "'::uuid",
            "lease_fence = 1",
            "expires_at_ms = " + expiresAt(original),
            "finalization_xid = '1'",
            "wal_insert_lsn = '0/1'",
            "wal_flush_lsn = '0/1'",
            "committed_before_ms = 1",
            "confirmation_xid = '1'")) {
      String[] fieldAndValue = assignment.split(" = ", 2);
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () ->
                          context
                              .dsl()
                              .execute(
                                  "INSERT INTO account_gameplay_admission_commit_confirmations(request_id, evidence_sha256, binding_decision_id, "
                                      + fieldAndValue[0]
                                      + ") VALUES (?, ?, ?, "
                                      + fieldAndValue[1]
                                      + ")",
                                  request,
                                  original.sha256(),
                                  decision)))
          .hasMessageContaining("proof is database derived");
    }
    var receipt = tx(context, () -> confirm(context, original, decision));
    for (String mutation :
        List.of(
            "UPDATE account_gameplay_admission_commit_confirmations SET committed_before_ms = 1",
            "UPDATE account_gameplay_admission_commit_confirmations SET expires_at_ms = expires_at_ms + 1",
            "UPDATE account_gameplay_admission_commit_confirmations SET evidence_sha256 = '"
                + "b".repeat(64)
                + "'",
            "DELETE FROM account_gameplay_admission_commit_confirmations",
            "TRUNCATE account_gameplay_admission_commit_confirmations")) {
      assertThatThrownBy(() -> tx(context, () -> context.dsl().execute(mutation)))
          .hasMessageContaining("confirmation is immutable");
    }
    assertThatThrownBy(
            () -> tx(context, () -> readConfirmation(context, original, UUID.randomUUID())))
        .hasMessageContaining("exact durable receipt required");
    assertThatThrownBy(
            () ->
                tx(
                    context,
                    () ->
                        context
                            .dsl()
                            .execute(
                                "UPDATE account_gameplay_admission_lease_operations SET finalization_xid = '1' WHERE request_id = ?",
                                request)))
        .hasMessageContaining("finalization transaction is database stamped");
    assertThat(tx(context, () -> readConfirmation(context, original, decision))).isEqualTo(receipt);
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  @Test
  void sameTransactionReceiptReadAndRolledBackConfirmationCannotProveDurability() {
    var context = context(null);
    var original = pending(context, account(context));
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(original, decision));
    for (boolean subtransaction : List.of(false, true)) {
      assertThatThrownBy(
              () ->
                  tx(
                      context,
                      () -> {
                        if (subtransaction) context.dsl().execute("SAVEPOINT receipt_creation");
                        confirm(context, original, decision);
                        if (subtransaction)
                          context.dsl().execute("RELEASE SAVEPOINT receipt_creation");
                        assertDeniedInSavepoint(
                            context,
                            () -> readConfirmation(context, original, decision),
                            "requires independent receipt commit");
                        throw new IllegalStateException("roll back confirmation commit");
                      }))
          .hasMessageContaining("roll back confirmation commit");
    }
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isZero();
    assertThatThrownBy(() -> tx(context, () -> readConfirmation(context, original, decision)))
        .hasMessageContaining("exact durable receipt required");
    var receipt = tx(context, () -> confirm(context, original, decision));
    assertThat(tx(context, () -> readConfirmation(context, original, decision))).isEqualTo(receipt);
  }

  @Test
  void concurrentConfirmationsRetainOneOriginalReceiptWithoutAdvancingSources() throws Exception {
    var context = context(null);
    var original = pending(context, account(context));
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    UUID decision = UUID.randomUUID();
    tx(context, () -> repository.recordCommitted(original, decision));
    var unchanged = storageSnapshot(context);
    var start = new CountDownLatch(1);
    Callable<Record> duplicate =
        () -> {
          if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
          return retrySerialization(() -> tx(context, () -> confirm(context, original, decision)));
        };
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(duplicate);
      var right = executor.submit(duplicate);
      start.countDown();
      assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(right.get(30, TimeUnit.SECONDS));
    }
    assertThat(
            context.dsl().fetchCount(DSL.table("account_gameplay_admission_commit_confirmations")))
        .isEqualTo(1);
    var retained = tx(context, () -> confirm(context, original, decision));
    waitPastDeadline(context, original);
    assertThat(
            tx(
                context,
                () ->
                    context
                        .dsl()
                        .execute(
                            "INSERT INTO account_gameplay_admission_commit_confirmations(request_id, evidence_sha256, binding_decision_id) VALUES (?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
                            requestId(original),
                            original.sha256(),
                            decision)))
        .isZero();
    // Both duplicates enter the INSERT guard but replay the expired retained receipt without
    // taking fresh temporal proof, restamping its bound or allocating replacement evidence.
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(duplicate);
      var right = executor.submit(duplicate);
      assertThat(left.get(30, TimeUnit.SECONDS)).isEqualTo(retained);
      assertThat(right.get(30, TimeUnit.SECONDS)).isEqualTo(retained);
    }
    assertThat(tx(context, () -> readConfirmation(context, original, decision)))
        .isEqualTo(retained);
    assertThat(storageSnapshot(context)).isEqualTo(unchanged);
  }

  private static Record confirm(
      Context context, AccountGameplayAdmissionLeaseEvidence original, UUID decision) {
    return Objects.requireNonNull(
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_admission_confirm_committed(?, ?, ?)",
                requestId(original),
                original.sha256(),
                decision));
  }

  private static FinalizeGameplayAdmissionLeaseRequest confirmationRequest(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    return FinalizeGameplayAdmissionLeaseRequest.newBuilder()
        .setLease(AccountGameplayAdmissionLeaseWireCodec.encodeReference(evidence))
        .setBindingDecisionId(decision.toString())
        .build();
  }

  private static Record confirmationRow(
      Context context, AccountGameplayAdmissionLeaseEvidence original) {
    return Objects.requireNonNull(
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_admission_commit_confirmations WHERE request_id = ?",
                requestId(original)));
  }

  private enum ReceiptCommitFault {
    NONE,
    BEFORE_FIRST_COMMIT,
    AFTER_FIRST_COMMIT
  }

  private record ReceiptCommitEvent(String kind, int connectionId) {}

  private static final class ReceiptCommitTrace {
    private final AtomicInteger connectionIds = new AtomicInteger();
    private final AtomicInteger commitAttempts = new AtomicInteger();
    private final List<ReceiptCommitEvent> events = new java.util.ArrayList<>();

    private void record(String kind, int connectionId) {
      events.add(new ReceiptCommitEvent(kind, connectionId));
    }

    private int count(String kind) {
      return (int) events.stream().filter(event -> event.kind().equals(kind)).count();
    }

    private List<ReceiptCommitEvent> events() {
      return events;
    }
  }

  /** Traces only JDBC work performed by the real receipt owner over the supplied PostgreSQL DS. */
  private static DataSource receiptCommitDataSource(
      DataSource source, ReceiptCommitFault fault, ReceiptCommitTrace trace) {
    return new DelegatingDataSource(source) {
      @Override
      public Connection getConnection() throws SQLException {
        return tracedConnection(super.getConnection());
      }

      @Override
      public Connection getConnection(String username, String password) throws SQLException {
        return tracedConnection(super.getConnection(username, password));
      }

      private Connection tracedConnection(Connection physical) {
        int connectionId = trace.connectionIds.incrementAndGet();
        return (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                    int attempt = trace.commitAttempts.incrementAndGet();
                    if (attempt == 1 && fault == ReceiptCommitFault.BEFORE_FIRST_COMMIT) {
                      trace.record("commit-attempt", connectionId);
                      throw new SQLException("Test failed before physical receipt COMMIT", "08006");
                    }
                    physical.commit();
                    trace.record("physical-commit", connectionId);
                    if (attempt == 1 && fault == ReceiptCommitFault.AFTER_FIRST_COMMIT)
                      throw new SQLException("Test lost physical receipt COMMIT response", "08006");
                    return null;
                  }
                  try {
                    Object result = method.invoke(physical, arguments);
                    if (result instanceof PreparedStatement statement) {
                      String sql = "";
                      if (arguments != null
                          && arguments.length > 0
                          && arguments[0] instanceof String statementSql) {
                        sql = statementSql;
                      }
                      return tracedStatement(statement, sql, connectionId, trace);
                    }
                    return result;
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }

  private static PreparedStatement tracedStatement(
      PreparedStatement physical, String sql, int connectionId, ReceiptCommitTrace trace) {
    return (PreparedStatement)
        Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, arguments) -> {
              if (method.getName().startsWith("execute") && isV90IndependentRead(sql))
                trace.record("v90-read", connectionId);
              try {
                return method.invoke(physical, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private static boolean isV90IndependentRead(String sql) {
    return sql.toLowerCase(java.util.Locale.ROOT)
        .contains("account_gameplay_admission_read_commit_confirmation");
  }

  private static ReceiptCommitEvent firstEvent(ReceiptCommitTrace trace, String kind) {
    return trace.events().stream()
        .filter(event -> event.kind().equals(kind))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing receipt JDBC event: " + kind));
  }

  private static Record readConfirmation(
      Context context, AccountGameplayAdmissionLeaseEvidence original, UUID decision) {
    return Objects.requireNonNull(
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_admission_read_commit_confirmation(?, ?, ?)",
                requestId(original),
                original.sha256(),
                decision));
  }

  private static void assertDeniedInSavepoint(
      Context context, Supplier<?> action, String expectedMessage) {
    context.dsl().execute("SAVEPOINT denied_confirmation");
    try {
      assertThatThrownBy(action::get)
          .hasMessageContaining(expectedMessage)
          .satisfies(
              failure -> {
                Throwable cause = failure;
                while (cause != null && !(cause instanceof java.sql.SQLException))
                  cause = cause.getCause();
                assertThat(cause).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("23514");
              });
    } finally {
      context.dsl().execute("ROLLBACK TO SAVEPOINT denied_confirmation");
      context.dsl().execute("RELEASE SAVEPOINT denied_confirmation");
    }
  }

  private static Record operation(Context context, AccountGameplayAdmissionLeaseEvidence original) {
    return Objects.requireNonNull(
        context
            .dsl()
            .fetchOne(
                "SELECT * FROM account_gameplay_admission_lease_operations WHERE request_id = ?",
                requestId(original)));
  }

  private static Map<String, List<Map<String, Object>>> storageSnapshot(Context context) {
    Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
    for (String table :
        List.of(
            "accounts",
            "account_gameplay_admission_lease_fences",
            "account_gameplay_admission_lease_allocations",
            "account_gameplay_admission_lease_operations",
            "account_authority_generations",
            "account_authority_issuance_fences",
            "account_authority_source_records",
            "account_authority_outbox_streams",
            "account_authority_outbox_events")) {
      snapshot.put(
          table,
          context.dsl().fetch("SELECT * FROM " + table + " ORDER BY 1").stream()
              .map(Record::intoMap)
              .toList());
    }
    return snapshot;
  }

  private static UUID requestId(AccountGameplayAdmissionLeaseEvidence original) {
    return UUID.fromString((String) original.carrier().get("requestId"));
  }

  private static long expiresAt(AccountGameplayAdmissionLeaseEvidence original) {
    return Long.parseLong((String) original.carrier().get("expiresAt"));
  }

  private static long databaseNow(Context context) {
    return Objects.requireNonNull(
            context
                .dsl()
                .fetchOne(
                    "SELECT ceil(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_ms"))
        .get("now_ms", Long.class);
  }

  private static void waitPastDeadline(
      Context context, AccountGameplayAdmissionLeaseEvidence original) {
    context
        .dsl()
        .fetchOne(
            "SELECT pg_sleep(GREATEST(0, (? - ceil(extract(epoch FROM clock_timestamp()) * 1000) + 50) / 1000.0))",
            expiresAt(original));
    assertThat(databaseNow(context)).isGreaterThanOrEqualTo(expiresAt(original));
  }

  private static AccountGameplayAdmissionLeaseEvidence pending(
      Context context, UUID account, long lifetimeMs) {
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    return tx(
        context,
        () -> {
          var allocation = repository.allocate(account, UUID.randomUUID(), UUID.randomUUID());
          var carrier = new LinkedHashMap<>(evidence(allocation).carrier());
          carrier.put(
              "expiresAt",
              Long.toString(Long.parseLong((String) carrier.get("evaluatedAt")) + lifetimeMs));
          var evidence = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
          repository.beginPending(evidence);
          return evidence;
        });
  }

  private static <T> T retrySerialization(Supplier<T> action) {
    for (int attempt = 0; ; attempt++) {
      try {
        return action.get();
      } catch (RuntimeException failure) {
        Throwable cause = failure;
        boolean serialization = false;
        while (cause != null) {
          if (cause instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState()))
            serialization = true;
          cause = cause.getCause();
        }
        if (!serialization || attempt >= 4) throw failure;
      }
    }
  }

  private static AccountGameplayAdmissionLeaseEvidence pending(Context context, UUID account) {
    var repository = new AccountGameplayAdmissionLeaseRepository(context.dsl());
    return tx(
        context,
        () -> {
          var allocation = repository.allocate(account, UUID.randomUUID(), UUID.randomUUID());
          var evidence = evidence(allocation);
          repository.beginPending(evidence);
          return evidence;
        });
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence(
      AccountGameplayAdmissionLeaseRepository.Allocation allocation) {
    String account = allocation.accountId().toString();
    long now = Instant.now().toEpochMilli();
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", "test");
    carrier.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    carrier.put("requestId", allocation.requestId().toString());
    carrier.put("leaseId", allocation.leaseId().toString());
    carrier.put("leaseFence", Long.toString(allocation.leaseFence()));
    carrier.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, OTHER);
    scope.put("accountId", account);
    scope.put("tenantId", TENANT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "1");
    carrier.put("bindingScope", scope);
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            Map.of(TENANT, "1"),
            "privateRealmGrantVersions",
            List.of()));
    carrier.put("issuanceFence", "7");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT, "1"),
            "membershipAuthorityGeneration",
            "1"));
    carrier.put(
        "outboxCheckpoints",
        List.of(
                "account/" + account,
                "issuer/firemud-account-service",
                "membership/" + account + "/" + TENANT,
                "tenant/" + TENANT)
            .stream()
            .map(
                suffix ->
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:" + suffix,
                        "outboxSequence",
                        "1"))
            .toList());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, OTHER);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    token.put("tokenGeneration", "1");
    token.put("issuanceFence", "7");
    token.put("tokenIdentityFence", "9");
    token.put("issuedAt", Long.toString(now / 1000 - 1));
    token.put("notBefore", Long.toString(now / 1000 - 1));
    token.put("expiresAt", Long.toString(now / 1000 + 300));
    carrier.put("tokenIdentityEvidence", token);
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put("expiresAt", Long.toString(now + 15000));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  private static UUID account(Context context) {
    var authorities = new AccountAuthorityGenerationRepository(context.dsl());
    var sources =
        new AccountAuthoritySourceEvidenceRepository(
            context.dsl(), authorities, new AccountAuthorityOutboxRepository(context.dsl()));
    return tx(
        context,
        () -> {
          sources.initializeIssuerIfAbsent("firemud-account-service");
          var account = new Account();
          account.setUsername("lease-" + UUID.randomUUID());
          account.setEmail(UUID.randomUUID() + "@example.test");
          account.setPasswordHash("storage-fixture-only");
          account.setLoginAuthModes("PASSWORD");
          return new AccountRepository(context.dsl(), sources).save(account).getAccountUuid();
        });
  }

  private static Context context(String target) {
    String schema = "admission_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = POSTGRES.dataSource(schema);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    var context =
        new Context(
            DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES),
            transaction,
            dataSource,
            schema);
    migrate(context, target);
    return context;
  }

  private static void migrate(Context context, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(context.dataSource())
            .schemas(context.schema())
            .defaultSchema(context.schema())
            .placeholders(Map.of("serviceSchema", context.schema()))
            .locations("classpath:db/migration");
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }

  private static <T> T tx(Context context, Supplier<T> action) {
    return context.transaction().execute(status -> action.get());
  }

  private record Context(
      DSLContext dsl,
      TransactionTemplate transaction,
      DriverManagerDataSource dataSource,
      String schema) {}
}
