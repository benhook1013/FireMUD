package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalGameplayRegionTransferCapacityCustodyIntegrationTest
    extends CanonicalGameplayRegionRuntimeStatusRepositoryIntegrationTestSupport {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @Test
  void restoredPreparedReceiptCannotReapplyAfterDurableIssuance() throws Exception {
    try (var proof = new TransferSqlProof();
        var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      long deadline = transferDeadline();
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            proof
                .capacity(proof.sql)
                .forTransferBudget(budget)
                .fenceForTransfer(item, dispatch, deadline);
            return null;
          });
      byte[] receipt =
          ascii(
              "session:game:capacity-transfer-receipt:{"
                  + item.assignment().tenantGameplayTag()
                  + "}:"
                  + item.execution().request().admissionAttemptId());
      var commands = connection.sync();
      assertThat(new String(commands.hget(receipt, ascii("state")), StandardCharsets.US_ASCII))
          .isEqualTo("APPLIED");
      // Simulate same-incarnation rollback, not a detectable reset. It must never grant APPLY
      // again.
      commands.hset(receipt, ascii("state"), ascii("PREPARED"));
      proof.losePhysicalExecution(item.execution());
      String retained = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThatThrownBy(() -> proof.fence(item)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
      assertThat(proof.itemImage(id)).isEqualTo(retained);
      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET fence_apply_issued=false WHERE transfer_id=?",
                  id))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage()).contains("Apply issuance cannot be reset");
              });
      assertThat(proof.itemImage(id)).isEqualTo(retained);
      assertThat(proof.itemImage(id)).contains("\"fence_apply_issued\": true");
    }
  }

  @Test
  void preparationSubmissionIsOneUseAndHistoryIsBounded() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      long deadline = transferDeadline();
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            var submission = dispatch.issuePreparation();
            assertThat(submission.takeSubmission(item, dispatch.receiptId())).isNotNull();
            assertThatThrownBy(() -> submission.takeSubmission(item, dispatch.receiptId()))
                .isInstanceOf(IllegalStateException.class);
            for (int index = 1; index < 15; index++) {
              assertThat(dispatch.issuePreparation()).isNotNull();
            }
            var finalOwner =
                new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    proof.redis,
                    proof.scripts,
                    proof.clock,
                    proof.tags.withCapacityExecutionDsl(budget.sql(proof.sql)),
                    () ->
                        CanonicalGameplayOriginalNewCapacityExecutionOwner.connectForRead(
                            proof.redis, budget),
                    budget::remainingMillis,
                    budget::awaitAsyncClose);
            var lastPrepared =
                finalOwner.prepareTransferReceipt(
                    item.execution(), item.candidate(), item.assignment(), dispatch, deadline);
            assertThat(dispatch.issuePreparation()).isNull();
            var recovered =
                finalOwner.prepareTransferReceipt(
                    item.execution(), item.candidate(), item.assignment(), dispatch, deadline);
            assertThat(recovered.nonce()).isEqualTo(lastPrepared.nonce());
            dispatch.seal(recovered);
            assertThat(dispatch.sealedNonce()).isEqualTo(lastPrepared.nonce());
            assertThat(proof.itemImage(id)).contains("\"fence_apply_issued\": false");
            return null;
          });
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
    }
  }

  @Test
  void lostApplyIssuanceCommitAcknowledgementNeverDispatchesOrRearms() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      var failed = new AtomicBoolean();
      var delegate = proof.sql.configuration().connectionProvider();
      var connections =
          java.util.Collections.synchronizedMap(
              new java.util.IdentityHashMap<java.sql.Connection, java.sql.Connection>());
      var provider =
          new org.jooq.ConnectionProvider() {
            public java.sql.Connection acquire() {
              var actual = delegate.acquire();
              var proxy =
                  (java.sql.Connection)
                      java.lang.reflect.Proxy.newProxyInstance(
                          java.sql.Connection.class.getClassLoader(),
                          new Class<?>[] {java.sql.Connection.class},
                          (object, method, args) -> {
                            Object result;
                            try {
                              result = method.invoke(actual, args);
                            } catch (java.lang.reflect.InvocationTargetException error) {
                              throw error.getCause();
                            }
                            if (method.getName().equals("commit")
                                && !failed.get()
                                && Boolean.TRUE.equals(
                                    proof
                                        .sql
                                        .fetchSingle(
                                            "SELECT fence_apply_issued FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                                            id)
                                        .get(0, Boolean.class))
                                && failed.compareAndSet(false, true)) {
                              throw new java.sql.SQLException(
                                  "Controlled lost APPLY issuance commit acknowledgement");
                            }
                            return result;
                          });
              connections.put(proxy, actual);
              return proxy;
            }

            public void release(java.sql.Connection connection) {
              delegate.release(connections.remove(connection));
            }
          };
      var broken =
          new CanonicalGameplayRegionOwnerTransferRepository(
              DSL.using(proof.sql.configuration().derive(provider)), proof.clock, proof.tags);
      long deadline = transferDeadline();
      assertThatThrownBy(() ->
              broken.within(
                  deadline,
                  budget -> {
                    var before =
                        proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
                    var dispatch = broken.fenceDispatch(item, before);
                    return proof
                        .capacity(proof.sql)
                        .forTransferBudget(budget)
                        .fenceForTransfer(item, dispatch, deadline);
                  }))
          .isInstanceOf(RuntimeException.class);
      assertThat(failed).isTrue();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT fence_apply_issued FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                      id)
                  .get(0, Boolean.class))
          .isTrue();
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
      String retained = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThatThrownBy(() -> proof.fence(item)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.itemImage(id)).isEqualTo(retained);
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
    }
  }

  @Test
  void competingOwnersIssueApplyExactlyOnce() throws Exception {
    var issued = new java.util.concurrent.atomic.AtomicInteger();
    var listener =
        new DefaultExecuteListener() {
          @Override
          public void executeEnd(ExecuteContext context) {
            String statement = context.sql();
            if (statement != null
                && statement.contains("SET fence_apply_issued=true")
                && context.rows() == 1) issued.incrementAndGet();
          }
        };
    try (var proof = new TransferSqlProof(listener)) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        java.util.concurrent.Callable<Boolean> race =
            () -> {
              ready.countDown();
              if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("race start timed out");
              try {
                proof.fence(item);
                return true;
              } catch (IllegalStateException held) {
                return false;
              }
            };
        var first = executor.submit(race);
        var second = executor.submit(race);
        assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        start.countDown();
        boolean firstCompleted = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
        boolean secondCompleted = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(firstCompleted || secondCompleted).isTrue();
      }
      assertThat(issued.get()).isEqualTo(1);
      assertThat(proof.physicalExecutionExists(item.execution())).isTrue();
      assertThat(proof.itemImage(id)).contains("\"fence_apply_issued\": true");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void sealedDispatchRestartRecoversNeverSentButPreservesAppliedThenLost(boolean appliedThenLost)
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      long deadline = transferDeadline();
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            if (appliedThenLost) {
              proof
                  .capacity(proof.sql)
                  .forTransferBudget(budget)
                  .fenceForTransfer(item, dispatch, deadline);
              assertThat(proof.physicalExecutionExists(item.execution())).isTrue();
              proof.losePhysicalExecution(item.execution());
            } else {
              var finalOwner =
                  new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      proof.redis,
                      proof.scripts,
                      proof.clock,
                      proof.tags.withCapacityExecutionDsl(budget.sql(proof.sql)),
                      () ->
                          CanonicalGameplayOriginalNewCapacityExecutionOwner.connectForRead(
                              proof.redis, budget),
                      budget::remainingMillis,
                      budget::awaitAsyncClose);
              var prepared =
                  finalOwner.prepareTransferReceipt(
                      item.execution(), item.candidate(), item.assignment(), dispatch, deadline);
              dispatch.seal(prepared);
              assertThat(dispatch.sealed()).isTrue();
              assertThat(
                      proof
                          .sql
                          .fetchSingle(
                              "SELECT fence_prepare_seal"
                                  + " FROM game_session_region_owner_transfer_item WHERE transfer_id=?",
                              id)
                          .get(0, UUID.class))
                  .isEqualTo(dispatch.sealedNonce());
            }
            return null;
          });
      String retained = proof.itemImage(id);
      if (!appliedThenLost) {
        assertThat(retained).contains("\"fence_apply_issued\": false");
        assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
      }
      proof.recreateTransferOwners();
      if (appliedThenLost) {
        assertThatThrownBy(() -> proof.fence(item)).isInstanceOf(IllegalStateException.class);
        assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
        assertThat(proof.itemImage(id)).isEqualTo(retained);
      } else {
        proof.fence(item);
        assertThat(proof.physicalExecutionExists(item.execution())).isTrue();
        assertThat(proof.itemImage(id)).doesNotContain("\"fence_readback\": null");
      }
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @Test
  void recreatedOwnerObservesAppliedReceiptBeforeRetainingItsExactFence() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      long deadline = transferDeadline();
      var item =
          proof.transfers.within(deadline, ignored -> proof.transfers.capture(id).getFirst());
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            proof
                .capacity(proof.sql)
                .forTransferBudget(budget)
                .fenceForTransfer(item, dispatch, deadline);
            return null;
          });
      assertThat(proof.itemImage(id))
          .contains("\"fence_apply_issued\": true", "\"fence_readback\": null");
      proof.recreateTransferOwners();
      var completed =
          List.of(
              new java.util.concurrent.CompletableFuture<ReceiptAction>(),
              new java.util.concurrent.CompletableFuture<ReceiptAction>());
      var commands = new java.util.concurrent.atomic.AtomicInteger();
      var observedRedis = org.mockito.Mockito.mock(io.lettuce.core.RedisClient.class);
      org.mockito.Mockito.when(observedRedis.connect(ByteArrayCodec.INSTANCE))
          .thenAnswer(ignored ->
              observingReceiptConnection(
                  proof.redis.connect(ByteArrayCodec.INSTANCE),
                  completed,
                  commands,
                  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner
                      .CAPACITY_EXECUTION_FENCED_INDETERMINATE_OBSERVED));
      var recovered =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner(
              proof.sql, observedRedis, proof.scripts, proof.clock, proof.tags);
      long recoveryDeadline = transferDeadline();
      proof.transfers.within(
          recoveryDeadline,
          budget -> {
            var before =
                proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), recoveryDeadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            var actual =
                recovered
                    .forTransferBudget(budget)
                    .fenceForTransfer(item, dispatch, recoveryDeadline);
            var after =
                proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), recoveryDeadline);
            proof.transfers.retainFence(item, actual, before, after);
            return null;
          });
      for (var observed : completed) {
        assertThat(observed.get(2, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(ReceiptAction.OBSERVE);
      }
      assertThat(commands.get()).isEqualTo(2);
      assertThat(proof.itemImage(id)).doesNotContain("\"fence_readback\": null");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  private enum ReceiptAction {
    PREPARE,
    APPLY,
    OBSERVE
  }

  @SuppressWarnings("unchecked")
  private static io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>
      observingReceiptConnection(
          io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> real,
          List<java.util.concurrent.CompletableFuture<ReceiptAction>> completed,
          java.util.concurrent.atomic.AtomicInteger commands,
          long expectedCode) {
    var actual = real.async();
    var observed =
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.async.RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.async.RedisAsyncCommands.class},
            (proxy, method, arguments) -> {
              if (!"evalsha".equals(method.getName())
                  || arguments.length != 4
                  || !(arguments[3] instanceof byte[][] values)
                  || values.length != 9) return invokeRedis(actual, method, arguments);
              // Do not stringify, retain, or render any argument; classify only the action.
              ReceiptAction action = null;
              for (var candidate : ReceiptAction.values()) {
                if (java.util.Arrays.equals(values[0], ascii(candidate.name()))) action = candidate;
              }
              int index = commands.getAndIncrement();
              if (action != ReceiptAction.OBSERVE || index >= completed.size()) {
                throw new AssertionError(
                    "Expected an original or post-retention read-only OBSERVE");
              }
              var outcome = completed.get(index);
              var result = (io.lettuce.core.RedisFuture<?>) invokeRedis(actual, method, arguments);
              result.whenComplete((value, failure) -> {
                if (failure == null
                    && value instanceof List<?> row
                    && row.size() == 3
                    && Long.valueOf(expectedCode).equals(row.get(0))
                    && row.get(2) instanceof byte[] state
                    && java.util.Arrays.equals(ascii("APPLIED"), state)) {
                  outcome.complete(ReceiptAction.OBSERVE);
                } else {
                  outcome.completeExceptionally(
                      new AssertionError("Redis did not complete the exact terminal observation"));
                }
              });
              return result;
            });
    return (io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>)
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.StatefulRedisConnection.class},
            (proxy, method, arguments) ->
                "async".equals(method.getName()) ? observed : invokeRedis(real, method, arguments));
  }

  private static Object invokeRedis(Object receiver, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(receiver, args);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @Override
  PostgreSQLContainer<?> postgresContainer() {
    return POSTGRES;
  }

  @Override
  GenericContainer<?> redisContainer() {
    return REDIS;
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"1,134217728", "128,1048576"})
  void chargedAbsentExecutionCanStillBeFencedWhenEitherBudgetDimensionIsFull(
      int maximumRecords, long maximumBindingBytes) throws Exception {
    try (var proof = withCapacityBudget(maximumRecords, maximumBindingBytes)) {
      proof.seedUndeliveredDispatch(proof.execution);
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE"
                          + " tenant_id=?",
                      proof.admission.candidate().bindingIdentity().tenantId())
                  .get(0, Long.class))
          .isEqualTo(1L);

      UUID transferId = proof.begin();
      assertThat(proof.captureAndFence(transferId)).hasSize(1);
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      assertThat(proof.executionState(proof.execution)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE"
                          + " tenant_id=?",
                      proof.admission.candidate().bindingIdentity().tenantId())
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  @Test
  void unsentChargeCanBeReleasedButPossibleSendNeedsExactAccountDenial() throws Exception {
    try (var proof = new TransferSqlProof()) {
      var execution = proof.execution;
      UUID attemptId = execution.request().admissionAttemptId();
      String chargeIdentityQuery =
          "SELECT (to_jsonb(c) - 'account_send_state' - 'account_unsent_released_at'"
              + " - 'account_denial_id' - 'account_denial_kind')::text"
              + " FROM game_session_capacity_evidence_charge c WHERE admission_attempt_id=?";
      String retainedIdentity =
          proof.sql.fetchSingle(chargeIdentityQuery, attemptId).get(0, String.class);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      attemptId)
                  .get(0, String.class))
          .isEqualTo("UNCLAIMED");

      // Controlled SQL proof of the owner-recorded no-send transition; no Account RPC occurs.
      assertThat(
              proof.sql.execute(
                  "UPDATE game_session_capacity_evidence_charge"
                      + " SET account_send_state='RELEASED_UNSENT'"
                      + " WHERE admission_attempt_id=? AND account_send_state='UNCLAIMED'",
                  attemptId))
          .isEqualTo(1);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state,account_unsent_released_at"
                          + " FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      attemptId)
                  .intoMap())
          .containsEntry("account_send_state", "RELEASED_UNSENT")
          .satisfies(row -> assertThat(row.get("account_unsent_released_at")).isNotNull());
      assertThat(proof.sql.fetchSingle(chargeIdentityQuery, attemptId).get(0, String.class))
          .isEqualTo(retainedIdentity);
      assertThat(proof.physicalExecutionExists(execution)).isFalse();

      var selected =
          new CanonicalGameplayOriginalNewCapacityExecutionOwner.CurrentOwner(
              execution.selectedOwnerId(), execution.selectedOwnerFence());
      assertThat(
              proof
                  .capacity(proof.sql)
                  .authorizeAccountSend(
                      proof.admission, execution.request(), selected, transferDeadline()))
          .isEqualTo(
              net.firedevops.firemud.gamesession.client.AccountTenantCapacityClient.SendDisposition
                  .AUTHORIZE_NEW);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      attemptId)
                  .get(0, String.class))
          .isEqualTo("MAY_HAVE_SENT");

      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_capacity_evidence_charge"
                      + " SET account_send_state='RELEASED_UNSENT'"
                      + " WHERE admission_attempt_id=?",
                  attemptId))
          .rootCause()
          .isInstanceOfSatisfying(
              org.postgresql.util.PSQLException.class,
              failure -> {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                assertThat(failure.getMessage())
                    .contains("Invalid capacity evidence send transition");
              });
      assertThat(proof.sql.fetchSingle(chargeIdentityQuery, attemptId).get(0, String.class))
          .isEqualTo(retainedIdentity);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state,account_denial_id,account_denial_kind"
                          + " FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                      attemptId)
                  .intoMap())
          .containsEntry("account_send_state", "MAY_HAVE_SENT")
          .containsEntry("account_denial_id", null)
          .containsEntry("account_denial_kind", null);

      UUID exactDenial = UUID.randomUUID();
      proof.settle(execution, exactDenial);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state,account_denial_id,account_denial_kind"
                          + " FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      attemptId)
                  .intoMap())
          .containsEntry("account_send_state", "MAY_HAVE_SENT")
          .containsEntry("account_denial_id", exactDenial)
          .containsEntry("account_denial_kind", "CAPACITY_QUOTA_EXCEEDED");
      assertThat(proof.sql.fetchSingle(chargeIdentityQuery, attemptId).get(0, String.class))
          .isEqualTo(retainedIdentity);
    }
  }

  @Test
  void freshPrechargeReclaimsOneExpiredUnclaimedSlotWithoutTouchingAccount() throws Exception {
    try (var proof = new TransferSqlProof(1, 1_048_576L, 3)) {
      UUID expiredAttempt = proof.execution.request().admissionAttemptId();
      proof.sql.fetchSingle(
          "SELECT pg_sleep(greatest(0,extract(epoch FROM account_send_evidence_expires_at"
              + " - clock_timestamp()))) FROM game_session_capacity_evidence_charge"
              + " WHERE admission_attempt_id=?",
          expiredAttempt);
      var mismatched = proof.newMismatchedRequestExecution();
      assertThatThrownBy(() -> proof.precharge(mismatched))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Original NEW capacity evidence budget is unavailable");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state,account_unsent_released_at"
                          + " FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                      expiredAttempt)
                  .intoMap())
          .containsEntry("account_send_state", "UNCLAIMED")
          .containsEntry("account_unsent_released_at", null);
      var fresh = proof.newIndependentControlledOriginExecution();
      assertThat(fresh.request().identity().requestId())
          .isNotEqualTo(proof.execution.request().identity().requestId());
      assertThat(fresh.transitionId()).isNotEqualTo(proof.execution.transitionId());
      assertThat(fresh.request().identity().tenantId())
          .isEqualTo(proof.execution.request().identity().tenantId());
      proof.precharge(fresh);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state,account_denial_id,account_unsent_released_at"
                          + " FROM game_session_capacity_evidence_charge WHERE admission_attempt_id=?",
                      expiredAttempt)
                  .intoMap())
          .containsEntry("account_send_state", "RELEASED_UNSENT")
          .containsEntry("account_denial_id", null)
          .satisfies(row -> assertThat(row.get("account_unsent_released_at")).isNotNull());
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT account_send_state FROM game_session_capacity_evidence_charge"
                          + " WHERE admission_attempt_id=?",
                      fresh.request().admissionAttemptId())
                  .get(0, String.class))
          .isEqualTo("UNCLAIMED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      assertThat(proof.physicalExecutionExists(fresh)).isFalse();
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
          .hasMessageContaining("Execution custody prevents capacity evidence charge changes");
      assertThat(proof.executionState(later)).isEqualTo("INSTALL_DISPATCHED");
      assertThat(proof.executionState(original)).isNull();
      assertThat(proof.physicalExecutionExists(original)).isFalse();
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
      var allowCommit = new java.util.concurrent.CountDownLatch(1);
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
  void fullTransferItemBudgetAllowsExistingIntentToCompleteButRefusesTheNextFreeze()
      throws Exception {
    try (var proof = withTransferHistoryBudget(2, 1)) {
      proof.seedUndeliveredDispatch(proof.execution);
      String originalExecution = proof.originalImage();
      UUID id = proof.begin();
      assertThat(id).isNotNull();
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT reserved_execution_items FROM game_session_region_owner_transfer"
                          + " WHERE transfer_id=?",
                      id)
                  .get(0, Integer.class))
          .isEqualTo(1);
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      assertTransferHistoryGuard(
          () -> proof.insertRawTransferItem(proof.sql, id, 1),
          "Transfer item is outside its reserved inventory");
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      String frozen = proof.transferImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.transferImage(id)).isEqualTo(frozen);
      assertThat(proof.captureAndFence(id))
          .extracting(item -> item.execution().digest())
          .containsExactly(proof.execution.digest());
      proof.verifyPage(id);
      boolean verified =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(verified).isTrue();
      var installed = proof.install(id);
      String completed = proof.transferImage(id);
      String items = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThat(
              proof.coordinator.recoverCompleted(
                  id,
                  installed.ownerInstanceId(),
                  installed.leaseTokenCorrelation(),
                  transferDeadline()))
          .isEqualTo(installed);

      assertTransferHistoryGuard(
          () -> proof.coordinator.advance(proof.scope, installed, transferDeadline()),
          "Transfer history cannot reserve its full execution inventory");
      assertThat(
              proof
                  .sql
                  .fetch("SELECT transfer_id FROM game_session_region_owner_transfer")
                  .getValues(0, UUID.class))
          .containsExactly(id);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
      assertThat(proof.itemImage(id)).isEqualTo(items);
      assertThat(proof.originalImage()).isEqualTo(originalExecution);
      assertThat(proof.currentOwner()).isEqualTo(installed);
    }
  }

  @Test
  void boundedCensusAndVerificationResumeBeyondThirtyTwoWithoutLosingAnyOriginal()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      // Controlled reservation envelopes seed SQL custody. Each original has its own retained
      // provisional transition, candidate, bridge and request.
      List<OriginalNewCapacityExecution> originals = new ArrayList<>();
      for (int index = 0; index < 65; index++) {
        var original = proof.newIndependentControlledOriginExecution();
        originals.add(original);
        proof.seedUndeliveredDispatch(original);
      }
      UUID id = proof.begin();
      var first = proof.captureAndFence(id);
      assertThat(first).hasSize(32);
      assertThat(first)
          .extracting(item -> item.execution().transitionId())
          .doesNotHaveDuplicates()
          .doesNotContain(proof.execution.transitionId());
      var firstExecution = first.getFirst().execution();
      var frozen =
          proof.transfers.within(
              transferDeadline(),
              ignored -> proof.transfers.frozenOriginal(firstExecution.transitionId()));
      assertThat(frozen.executionDigest()).isEqualTo(firstExecution.digest());
      assertThatThrownBy(() ->
              proof.transfers.within(
                  transferDeadline(), ignored -> proof.transfers.verificationCursor(id)))
          .isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.captureAndFence(id)).hasSize(32);
      assertThatThrownBy(() ->
              proof.transfers.within(
                  transferDeadline(), ignored -> proof.transfers.verificationCursor(id)))
          .isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      assertThat(proof.captureAndFence(id)).hasSize(1);
      assertThat(proof.captureAndFence(id)).isEmpty();

      List<String> captured = new ArrayList<>();
      long cursor = -1;
      for (int size : List.of(32, 32, 1)) {
        long after = cursor;
        var page =
            proof.transfers.within(transferDeadline(), ignored -> proof.transfers.page(id, after));
        assertThat(page).hasSize(size);
        captured.addAll(page.stream().map(item -> item.execution().digest()).toList());
        cursor = page.getLast().ordinal();
      }
      assertThat(captured)
          .containsExactlyInAnyOrderElementsOf(
              originals.stream().map(OriginalNewCapacityExecution::digest).toList());
      assertThat(cursor).isEqualTo(64L);
      long initialVerificationCursor =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.verificationCursor(id));
      assertThat(initialVerificationCursor).isEqualTo(-1L);
      proof.verifyPage(id);
      boolean firstPageComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(firstPageComplete).isFalse();
      assertThatThrownBy(() -> proof.install(id)).isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      long resumedVerificationCursor =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.verificationCursor(id));
      assertThat(resumedVerificationCursor).isEqualTo(31L);
      proof.verifyPage(id);
      boolean secondPageComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(secondPageComplete).isFalse();
      proof.recreateTransferOwners();
      proof.verifyPage(id);
      boolean allPagesComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(allPagesComplete).isTrue();
      var installed = proof.install(id);
      assertThat(installed.executorFence()).isGreaterThan(proof.prior.executorFence());
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transfers.readCurrent(id, 64).controlState()).isEqualTo("COMPLETED");
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT count(*) FROM game_session_region_owner_transfer_item WHERE"
                          + " transfer_id=? AND fence_readback IS NOT NULL AND verified_round IS"
                          + " NOT NULL",
                      id)
                  .get(0, Long.class))
          .isEqualTo(65L);
      assertThat(
              proof
                  .sql
                  .fetch("SELECT status FROM game_session_original_new_capacity_execution")
                  .getValues("status", String.class))
          .containsOnly("INSTALL_DISPATCHED");
    }
  }

  @Test
  void finalQualificationRechecksLostFenceBeyondTheFirstPageWithoutRecreatingIt() throws Exception {
    try (var proof = new TransferSqlProof()) {
      for (int index = 0; index < 33; index++) {
        proof.seedUndeliveredDispatch(proof.newIndependentControlledOriginExecution());
      }
      UUID id = proof.begin();
      assertThat(proof.captureAndFence(id)).hasSize(32);
      assertThat(proof.captureAndFence(id)).hasSize(1);
      proof.verifyPage(id);
      proof.recreateTransferOwners();
      proof.verifyPage(id);
      boolean allPagesComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(allPagesComplete).isTrue();
      var last =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.page(id, 31).getFirst());
      assertThat(last.ordinal()).isEqualTo(32L);
      String receipt = proof.itemImage(id);
      proof.losePhysicalExecution(last.execution());
      proof.recreateTransferOwners();
      assertThatThrownBy(() -> proof.install(id)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.transfers.readCurrent(id, 32).controlState()).isEqualTo("FROZEN");
      assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.captureAndFence(id)).isEmpty();
      assertThat(proof.physicalExecutionExists(last.execution())).isFalse();
    }
  }

  @Test
  void installationDoesNotReturnServingCapabilityWhenAnyCompletedMarkerIsLost() throws Exception {
    try (var proof = new TransferSqlProof();
        var administrator =
            RedisClient.create(RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379)));
        var inspection = administrator.connect(ByteArrayCodec.INSTANCE)) {
      proof.seedUndeliveredDispatch(proof.execution);
      var secondExecution = proof.newIndependentControlledOriginExecution();
      proof.seedUndeliveredDispatch(secondExecution);
      UUID id = proof.begin();
      assertThat(proof.captureAndFence(id)).hasSize(2);
      proof.verifyPage(id);
      boolean verificationComplete =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(verificationComplete).isTrue();

      var connectionDuringInstall = new AtomicReference<String>();
      var checkedAfterCommit = new AtomicBoolean();
      var lostAfterInstall = new AtomicBoolean();
      var listener =
          new DefaultExecuteListener() {
            @Override
            public void executeEnd(ExecuteContext context) {
              String sql = context.sql();
              if (sql == null) return;
              if (sql.stripLeading()
                      .toLowerCase(Locale.ROOT)
                      .startsWith("update game_session_region_runtime_status")
                  && lostAfterInstall.compareAndSet(false, true)) {
                connectionDuringInstall.set(
                    transferReadbackClientId(inspection.sync().clientList()));
                // Lose one marker after the successor update, before its SQL commit.
                proof.losePhysicalExecution(secondExecution);
              } else if (connectionDuringInstall.get() != null
                  && sql.contains("t.state='COMPLETED'")
                  && checkedAfterCommit.compareAndSet(false, true)) {
                // The independent SQL read verifies commit before inspecting the same connection.
                assertThat(proof.currentOwner().ownerInstanceId()).isEqualTo("sql-proof-successor");
                assertThat(transferReadbackClientId(inspection.sync().clientList()))
                    .isEqualTo(connectionDuringInstall.get());
              }
            }
          };
      DSLContext installingSql = DSL.using(proof.sql.configuration().deriveAppending(listener));
      var transfers = proof.transfers(installingSql);
      var observations =
          new CanonicalGameplayControlledRegionTransferObservationFixture(proof.clock)
              .receiver(transfers, proof.tags);
      var coordinator =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              installingSql, proof.clock, transfers, proof.capacity(installingSql), observations);

      assertThatThrownBy(() ->
              coordinator.install(
                  id,
                  proof.scope,
                  proof.prior,
                  "e".repeat(64),
                  "game-session-service",
                  "sql-proof-successor",
                  transferDeadline()))
          .isInstanceOf(IllegalStateException.class);

      assertThat(lostAfterInstall).isTrue();
      assertThat(checkedAfterCommit).isTrue();
      assertThat(proof.currentOwner().ownerInstanceId()).isEqualTo("sql-proof-successor");
      assertThat(proof.transfers.readCurrent(id, 0).controlState()).isEqualTo("COMPLETED");
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      assertThat(proof.physicalExecutionExists(secondExecution)).isFalse();
      assertThatThrownBy(() ->
              coordinator.recoverCompleted(
                  id, "sql-proof-successor", "e".repeat(64), transferDeadline()))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static String transferReadbackClientId(String clientList) {
    var clients =
        clientList.lines().filter(line -> line.contains(" user=gamesession_coord_app ")).toList();
    assertThat(clients).hasSize(1);
    return clients.getFirst().split(" ", 2)[0];
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void missingAppliedReceiptCannotQualifyCurrentTransfer(boolean installedBeforeLoss)
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      assertThat(proof.captureAndFence(id)).hasSize(1);
      proof.verifyPage(id);
      boolean verified =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      assertThat(verified).isTrue();
      var expectedOwner = installedBeforeLoss ? proof.install(id) : proof.prior;
      String retainedTransfer = proof.transferImage(id);
      String retainedItem = proof.itemImage(id);
      assertThat(retainedItem).contains("\"fence_apply_issued\": true");
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      var assignment = proof.tags.assignmentForTransition(proof.execution.transitionId());
      byte[] receiptKey =
          ascii(
              "session:game:capacity-transfer-receipt:{"
                  + assignment.tenantGameplayTag()
                  + "}:"
                  + proof.execution.request().admissionAttemptId());
      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(connection.sync().pexpire(receiptKey, 0)).isTrue();
        proof.recreateTransferOwners();
        if (installedBeforeLoss) {
          assertThatThrownBy(() ->
                  proof.coordinator.recoverCompleted(
                      id,
                      expectedOwner.ownerInstanceId(),
                      expectedOwner.leaseTokenCorrelation(),
                      transferDeadline()))
              .isInstanceOf(IllegalStateException.class);
        } else {
          assertThatThrownBy(() -> proof.install(id)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(connection.sync().type(receiptKey)).isEqualTo("none");
      }
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      assertThat(proof.currentOwner()).isEqualTo(expectedOwner);
      assertThat(proof.transferImage(id)).isEqualTo(retainedTransfer);
      assertThat(proof.itemImage(id)).isEqualTo(retainedItem);
    }
  }

  @Test
  void terminalOnlyTransferRecoversExactCompletedSuccessorAfterOriginalFinalReceipt()
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
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
        UUID id = proof.begin();
        assertThatThrownBy(() ->
                proof.coordinator.recoverCompleted(
                    id, "sql-proof-successor", "e".repeat(64), transferDeadline()))
            .isInstanceOf(IllegalStateException.class);

        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(readback);
        assertThat(proof.captureAndFence(id)).isEmpty();
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        var installed = proof.install(id);
        String completion = proof.transferImage(id);
        String retained = proof.originalImage();
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        assertThat(
                proof.coordinator.recoverCompleted(
                    id,
                    installed.ownerInstanceId(),
                    installed.leaseTokenCorrelation(),
                    transferDeadline()))
            .isEqualTo(installed);
        assertThatThrownBy(() ->
                proof.coordinator.recoverCompleted(
                    id, installed.ownerInstanceId(), "f".repeat(64), transferDeadline()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(proof.currentOwner()).isEqualTo(installed);
        assertThat(proof.transferImage(id)).isEqualTo(completion);
        assertThat(proof.originalImage()).isEqualTo(retained);
      }
    }
  }

  @Test
  void installationResumesFrozenTransferWhenTheOriginalAbortedBeforeCapture() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      assertThat(id).isNotNull();
      assertThat(proof.itemImage(id)).isEqualTo("[]");
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
      String tag =
          proof.tags.assignmentForTransition(proof.execution.transitionId()).tenantGameplayTag();
      assertThat(proof.fixture.recordOriginalAccountAbort().isAccountAborted()).isTrue();
      String originalExecution = proof.originalImage();
      var original = proof.region.installation();
      try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
        assertThat(connection.sync().pexpire(ascii(original.grant.key()), 0)).isTrue();
      }
      proof.recreateTransferOwners();

      var installed =
          CanonicalGameplayRegionOwnerCompositionFixture.handoffExistingOriginal(
              proof.sql,
              proof.redis,
              proof.coordinationUri,
              RedisScriptCatalog.fromContributions(
                  List.of(new CanonicalGameplayRegionLeaseRedisScriptContribution())),
              original,
              proof.upstream.resolver,
              proof.coordinator,
              transferDeadline());

      assertThat(installed.status.executorFence()).isGreaterThan(proof.prior.executorFence());
      assertThat(installed.status).isEqualTo(proof.currentOwner());
      assertThat(installed.evidence).isNull();
      assertThat(proof.transfers.readCurrent(id, 0).controlState()).isEqualTo("COMPLETED");
      assertThat(proof.itemImage(id)).contains(proof.execution.digest());
      assertThat(proof.physicalExecutionExists(proof.execution, tag)).isTrue();
      assertThat(proof.originalImage()).isEqualTo(originalExecution);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void expiredProtectedObservationCannotIssueTransferMutation(boolean prepareBeforeExpiry)
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      var wall = new AtomicLong(proof.clock.millis());
      Clock clock = org.mockito.Mockito.mock(Clock.class);
      org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> wall.get());
      org.mockito.Mockito.when(clock.instant())
          .thenAnswer(ignored -> Instant.ofEpochMilli(wall.get()));
      var transfers =
          new CanonicalGameplayRegionOwnerTransferRepository(proof.sql, clock, proof.tags);
      var observations =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
                  clock, Duration.ofSeconds(3))
              .receiver(transfers, proof.tags);
      UUID id = proof.begin();
      long deadline = proof.clock.millis() + 10_000;
      transfers.within(
          deadline,
          budget -> {
            var item = transfers.capture(id).getFirst();
            var before = observations.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = transfers.fenceDispatch(item, before);
            if (prepareBeforeExpiry) {
              var owner =
                  new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      proof.redis,
                      proof.scripts,
                      clock,
                      proof.tags.withCapacityExecutionDsl(budget.sql(proof.sql)),
                      () ->
                          CanonicalGameplayOriginalNewCapacityExecutionOwner.connectForRead(
                              proof.redis, budget),
                      budget::remainingMillis,
                      budget::awaitAsyncClose);
              dispatch.seal(
                  owner.prepareTransferReceipt(
                      item.execution(), item.candidate(), item.assignment(), dispatch, deadline));
            }
            String retainedItem = proof.itemImage(id);
            wall.set(before.expiresAtEpochMillis());
            assertThat(wall.get()).isLessThan(deadline);
            if (prepareBeforeExpiry) {
              assertThatThrownBy(dispatch::claimApply).isInstanceOf(IllegalStateException.class);
            } else {
              assertThatThrownBy(dispatch::issuePreparation)
                  .isInstanceOf(IllegalStateException.class);
            }
            assertThat(proof.itemImage(id)).isEqualTo(retainedItem);
            return null;
          });
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.physicalExecutionExists(proof.execution)).isFalse();
    }
  }

  @Test
  void transferReceiptWireDeadlineCannotExceedProtectedObservation() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      var observations =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
                  proof.clock, Duration.ofSeconds(3))
              .receiver(proof.transfers, proof.tags);
      UUID id = proof.begin();
      long deadline = proof.clock.millis() + 10_000;
      var wireDeadlines = new java.util.EnumMap<ReceiptAction, Long>(ReceiptAction.class);
      proof.transfers.within(
          deadline,
          budget -> {
            var item = proof.transfers.capture(id).getFirst();
            var before = observations.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            assertThat(before.expiresAtEpochMillis()).isLessThan(deadline);
            var owner =
                new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                    proof.redis,
                    proof.scripts,
                    proof.clock,
                    proof.tags.withCapacityExecutionDsl(budget.sql(proof.sql)),
                    () ->
                        recordingReceiptDeadline(
                            CanonicalGameplayOriginalNewCapacityExecutionOwner.connectForRead(
                                proof.redis, budget),
                            wireDeadlines),
                    budget::remainingMillis,
                    budget::awaitAsyncClose);
            dispatch.seal(
                owner.prepareTransferReceipt(
                    item.execution(), item.candidate(), item.assignment(), dispatch, deadline));
            assertThat(
                    owner.applyTransferReceipt(
                        item.execution(), item.candidate(), item.assignment(), dispatch, deadline))
                .isEqualTo(
                    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner
                        .CAPACITY_EXECUTION_FENCED_INDETERMINATE);
            assertThat(wireDeadlines)
                .containsOnly(
                    org.assertj.core.api.Assertions.entry(
                        ReceiptAction.PREPARE, before.expiresAtEpochMillis()),
                    org.assertj.core.api.Assertions.entry(
                        ReceiptAction.APPLY, before.expiresAtEpochMillis()));
            return null;
          });
      assertThat(proof.physicalExecutionExists(proof.execution)).isTrue();
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
    }
  }

  @SuppressWarnings("unchecked")
  private static io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>
      recordingReceiptDeadline(
          io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]> real,
          java.util.EnumMap<ReceiptAction, Long> deadlines) {
    var actual = real.async();
    Object observed =
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.async.RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.async.RedisAsyncCommands.class},
            (proxy, method, arguments) -> {
              if ("evalsha".equals(method.getName())
                  && arguments.length == 4
                  && arguments[3] instanceof byte[][] values
                  && values.length == 9) {
                // Inspect only operation type and numeric deadline, not candidate or proof bytes.
                var action =
                    ReceiptAction.valueOf(new String(values[0], StandardCharsets.US_ASCII));
                long expires = Long.parseLong(new String(values[6], StandardCharsets.US_ASCII));
                if (deadlines.putIfAbsent(action, expires) != null) {
                  throw new AssertionError("Transfer receipt action was submitted more than once");
                }
              }
              return invokeRedis(actual, method, arguments);
            });
    return (io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>)
        java.lang.reflect.Proxy.newProxyInstance(
            io.lettuce.core.api.StatefulRedisConnection.class.getClassLoader(),
            new Class<?>[] {io.lettuce.core.api.StatefulRedisConnection.class},
            (proxy, method, arguments) ->
                "async".equals(method.getName()) ? observed : invokeRedis(real, method, arguments));
  }

  @Test
  void installationRefusesSignedEvidenceThatExpiresWhileAcquiringTheRegionLock() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.newIndependentControlledOriginExecution());
      var wall = new AtomicLong(proof.clock.millis());
      Clock clock = org.mockito.Mockito.mock(Clock.class);
      org.mockito.Mockito.when(clock.millis()).thenAnswer(ignored -> wall.get());
      org.mockito.Mockito.when(clock.instant())
          .thenAnswer(ignored -> Instant.ofEpochMilli(wall.get()));
      var expireAfterLock = new AtomicBoolean();
      var listener =
          new DefaultExecuteListener() {
            @Override
            public void executeEnd(ExecuteContext context) {
              String sql = context.sql();
              if (sql != null
                  && sql.contains("pg_advisory_xact_lock")
                  && expireAfterLock.compareAndSet(true, false)) wall.addAndGet(1_100);
            }
          };
      DSLContext installingSql = DSL.using(proof.sql.configuration().deriveAppending(listener));
      var transfers =
          new CanonicalGameplayRegionOwnerTransferRepository(installingSql, clock, proof.tags);
      var observations =
          new CanonicalGameplayControlledRegionTransferObservationFixture(
                  clock, Duration.ofSeconds(1))
              .receiver(transfers, proof.tags);
      var coordinator =
          new CanonicalGameplayRegionOwnerTransferCoordinator(
              installingSql, clock, transfers, proof.capacity(installingSql), observations);
      UUID id = coordinator.advance(proof.scope, proof.prior, wall.get() + 5_000);
      assertThat(id).isNotNull();
      String before = proof.transferImage(id);
      expireAfterLock.set(true);
      long deadline = wall.get() + 5_000;
      assertThatThrownBy(() ->
              coordinator.install(
                  id,
                  proof.scope,
                  proof.prior,
                  "e".repeat(64),
                  "game-session-service",
                  "sql-proof-successor",
                  deadline))
          .isInstanceOf(IllegalStateException.class);
      assertThat(expireAfterLock).isFalse();
      assertThat(wall.get()).isLessThan(deadline);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(proof.transferImage(id)).isEqualTo(before);
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
      proof.authorizeAccountSend(proof.execution);
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
        UUID id = proof.begin();
        var captured =
            proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
        assertThat(captured)
            .extracting(item -> item.execution().digest())
            .containsExactly(proof.execution.digest());
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
            .hasRootCauseMessage("Normal region owner transfer is held");
        assertThat(proof.currentOwner()).isEqualTo(proof.prior);
        assertThat(proof.transferImage(id)).isEqualTo(frozen);
        assertThat(proof.itemImage(id)).isEqualTo(inventory);

        proof.fence(captured.getFirst());
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        assertThat(
                proof
                    .rawCompleteAndInstall(id, "sql-proof-successor", "e".repeat(64))
                    .ownerInstanceId())
            .isEqualTo("sql-proof-successor");
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
        assertThat(proof.transfers.readCurrent(id, captured.getFirst().ordinal()).controlState())
            .isEqualTo("COMPLETED");
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "admissionAt,1",
    "continuityExpiresAt,1",
    "sessionSha1,0000000000000000000000000000000000000000"
  })
  void committedTransferRejectsPhysicalMetadataDrift(String field, String replacement)
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
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
        UUID id = proof.begin();
        var item =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
        new CanonicalGameplayBindingInventoryRepository(proof.sql)
            .commitOriginalNewFinalAdmission(readback);
        proof.fence(item);
        String retained = proof.itemImage(id);
        String original = proof.originalImage();
        String tag =
            proof.tags.assignmentForTransition(proof.execution.transitionId()).tenantGameplayTag();
        byte[] key =
            ("session:game:capacity-execution:{"
                    + tag
                    + "}:"
                    + proof.execution.request().admissionAttemptId())
                .getBytes(StandardCharsets.UTF_8);
        try (var connection = proof.redis.connect(ByteArrayCodec.INSTANCE)) {
          byte[] name = field.getBytes(StandardCharsets.UTF_8);
          byte[] prior = connection.sync().hget(key, name);
          assertThat(prior).isNotNull();
          connection.sync().hset(key, name, replacement.getBytes(StandardCharsets.UTF_8));
          assertThatThrownBy(
                  () -> proof.capacity(proof.sql).readTransferFence(item, transferDeadline()))
              .isInstanceOf(IllegalStateException.class);
          assertThat(proof.itemImage(id)).isEqualTo(retained);
          assertThat(proof.originalImage()).isEqualTo(original);
          assertThat(proof.currentOwner()).isEqualTo(proof.prior);
          connection.sync().hset(key, name, prior);
          assertThat(proof.capacity(proof.sql).readTransferFence(item, transferDeadline()))
              .isNotNull();
        }
      }
    }
  }

  @Test
  void standaloneTransferObservationCancelsBlockedSqlAtItsExplicitDeadline() throws Exception {
    try (var proof = new TransferSqlProof();
        var lock =
            java.sql.DriverManager.getConnection(
                postgresContainer().getJdbcUrl(),
                postgresContainer().getUsername(),
                postgresContainer().getPassword())) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var captured =
          proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertThat(captured).hasSize(1);
      lock.setSchema(proof.sql.fetchSingle("SELECT current_schema()").get(0, String.class));
      lock.setAutoCommit(false);
      try (var statement = lock.createStatement()) {
        statement.execute("LOCK TABLE game_session_region_owner_transfer IN ACCESS EXCLUSIVE MODE");
      }
      var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
      try (AutoCloseable shutdown =
          () -> {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("bounded SQL observation caller termination")
                .isTrue();
          }) {
        long start = System.nanoTime();
        var pending =
            worker.submit(() ->
                proof.receiver.observeForRegionOwnerTransfer(
                    id, captured.getFirst().ordinal(), proof.clock.millis() + 500L));
        try (AutoCloseable cancel = () -> pending.cancel(true);
            AutoCloseable rollback = lock::rollback) {
          assertThatThrownBy(() -> pending.get(3, java.util.concurrent.TimeUnit.SECONDS))
              .isInstanceOf(java.util.concurrent.ExecutionException.class)
              .hasCauseInstanceOf(ObservationUnavailableException.class);
          assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
      }
      assertThat(
              proof
                  .receiver
                  .observeForRegionOwnerTransfer(
                      id, captured.getFirst().ordinal(), transferDeadline())
                  .regionOwnerTransferContext()
                  .transferId())
          .isEqualTo(id);
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
                "Original NEW capacity execution cannot start with an authorization or terminal"
                    + " proof");
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
  void resumedFenceReceiptCannotChangeDispatchedStorageIncarnation() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.insertRawFrozenTransfer(proof.sql);
      proof.insertRawTransferItem(proof.sql, id, 0);
      String dispatchSql =
          "UPDATE game_session_region_owner_transfer_item"
              + " SET fence_dispatch_id=?,fence_dispatch_incarnation=? WHERE transfer_id=?";
      UUID dispatch = UUID.randomUUID();
      String observed = "sha256:" + "a".repeat(64);
      String changed = "sha256:" + "b".repeat(64);
      assertThatThrownBy(() -> proof.sql.execute(dispatchSql, dispatch, observed, id))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("exact bound incarnation");
      proof.sql.execute(
          "UPDATE game_session_region_owner_transfer SET incarnation_digest=? WHERE transfer_id=?",
          observed,
          id);
      assertThatThrownBy(() -> proof.sql.execute(dispatchSql, dispatch, changed, id))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("exact bound incarnation");
      assertThat(proof.sql.execute(dispatchSql, dispatch, observed, id)).isEqualTo(1);
      String dispatched = proof.itemImage(id);
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration -> {
                var transaction = DSL.using(configuration);
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
          .hasMessageContaining("Transfer receipt differs from the bound parent incarnation");
      assertThat(proof.itemImage(id)).isEqualTo(dispatched);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      assertThat(
              proof
                  .sql
                  .fetchSingle(
                      "SELECT state FROM game_session_region_owner_transfer WHERE transfer_id=?",
                      id)
                  .get(0, String.class))
          .isEqualTo("FROZEN");
    }
  }

  @Test
  void terminalTransferCompletionRequiresItsExactInstalledSuccessor() throws Exception {
    try (var proof = new TransferSqlProof()) {
      UUID id = proof.insertRawFrozenTransfer(proof.sql);
      String frozen = proof.transferImage(id);
      String successor = "terminal-successor";
      String lease = "e".repeat(64);
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration ->
                  CanonicalGameplayRegionOwnerTransferRepository.completeTerminalOnly(
                      DSL.using(configuration),
                      proof.prior,
                      successor,
                      proof.prior.executorFence().longValueExact() + 1,
                      lease)))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasStackTraceContaining(
              "Region transfer completion requires its exact installed successor");
      assertThat(proof.transferImage(id)).isEqualTo(frozen);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);

      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_region_runtime_status SET owner_service=?"
                      + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
                  "other-owner-service",
                  proof.scope.tenantId(),
                  proof.scope.gameInstanceId(),
                  proof.scope.regionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region owner replacement requires an advancing executor fence");
      assertThatThrownBy(() ->
              proof.sql.execute(
                  "UPDATE game_session_region_runtime_status"
                      + " SET owner_service=?, executor_fence=?"
                      + " WHERE tenant_id=? AND game_instance_id=? AND region_id=?",
                  "other-owner-service",
                  proof.prior.executorFence().longValueExact() + 1,
                  proof.scope.tenantId(),
                  proof.scope.gameInstanceId(),
                  proof.scope.regionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region transfer must complete with owner installation");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);

      var installed = proof.rawCompleteAndInstall(id, successor, lease);
      assertThat(installed.ownerService()).isEqualTo("game-session-service");
      assertThat(installed.ownerInstanceId()).isEqualTo(successor);
      assertThat(installed.leaseTokenCorrelation()).isEqualTo(lease);
      assertThat(proof.currentOwner()).isEqualTo(installed);
    }
  }

  @Test
  void rawOwnerReplacementCannotSkipFrozenIncompleteOrChangedExactTransfer() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThatThrownBy(() ->
              proof.sql.transaction(configuration ->
                  CanonicalGameplayRegionOwnerTransferRepository.completeTerminalOnly(
                      DSL.using(configuration),
                      proof.prior,
                      "unretained-terminal-successor",
                      proof.prior.executorFence().longValueExact() + 1,
                      "e".repeat(64))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Normal region owner transfer is held");
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      proof.seedUndeliveredDispatch(proof.execution);
      var lateExecution = proof.newControlledExecution();
      proof.retainExactChargeForControlledSeed(lateExecution);
      proof.authorizeAccountSend(lateExecution);
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
              "UPDATE game_session_region_runtime_status SET"
                  + " owner_instance_id=?,executor_fence=?,lease_token_correlation=? WHERE"
                  + " tenant_id=? AND game_instance_id=? AND region_id=?",
              forgedOwner,
              forgedFence,
              forgedLease,
              proof.scope.tenantId(),
              proof.scope.gameInstanceId(),
              proof.scope.regionId()));
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      UUID id = proof.begin();
      assertSqlGuard(() -> proof.rawReplaceOwner("frozen-successor", "e".repeat(64)));
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id));
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer SET state='COMPLETED',"
                  + " new_owner_id='forged-successor',new_owner_fence=?,new_lease_correlation=?,"
                  + " incarnation_digest=?,verification_round=?,verification_complete=true"
                  + " WHERE transfer_id=?",
              proof.prior.executorFence().longValueExact() + 1,
              "e".repeat(64),
              "sha256:" + "a".repeat(64),
              UUID.randomUUID(),
              id));
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.currentOwner()).isEqualTo(proof.prior);
      proof.captureAndFence(id);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String completed = proof.transferImage(id);
      assertSqlGuard(() -> proof.rawReplaceOwner("different-successor", "f".repeat(64)));
      var newCharge = proof.newControlledExecution();
      assertThatThrownBy(() -> proof.retainExactChargeForControlledSeed(newCharge))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Capacity send boundary requires the exact current selected owner");
      assertThatThrownBy(
              () -> proof.insertExecution(proof.sql, lateExecution, "INSTALL_DISPATCHED", null))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("Region dispatch requires the exact current selected owner");
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completed);
    }
  }

  @Test
  void lostFenceDispatchReplyRecoversOnlyItsPhysicalWitnessAndCannotRecreateIt() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      long deadline = transferDeadline();
      proof.transfers.within(
          deadline,
          budget -> {
            var before = proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
            var dispatch = proof.transfers.fenceDispatch(item, before);
            var competing = proof.transfers.fenceDispatch(item, before);
            assertThat(competing.hasReceipt(item)).isTrue();
            var owner = proof.capacity(proof.sql).forTransferBudget(budget);
            owner.fenceForTransfer(item, dispatch, deadline);
            return null;
          });
      assertThat(proof.itemImage(id)).contains("\"fence_readback\": null");
      proof.recreateTransferOwners();
      proof.fence(item);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String receipt = proof.itemImage(id);
      String completion = proof.transferImage(id);
      proof.losePhysicalExecution(item.execution());
      assertThatThrownBy(() -> proof.fence(item)).isInstanceOf(IllegalStateException.class);
      assertThat(proof.physicalExecutionExists(item.execution())).isFalse();
      assertThat(proof.currentOwner()).isEqualTo(installed);
      assertThat(proof.transferImage(id)).isEqualTo(completion);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void interruptedFenceRetentionRecoversExactTerminalProofWithoutDispatchingAgain(boolean committed)
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      var finalOwner =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
              proof.redis, proof.scripts, proof.clock, proof.tags);
      var assignment = proof.tags.assignmentForTransition(proof.execution.transitionId());
      if (!committed) {
        finalOwner.closeCapacityExecutionOnce(
            proof.execution, proof.admission.candidate(), assignment, transferDeadline());
      }
      try (var physicalCommit =
          committed
              ? new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      proof.redis, proof.scripts, proof.clock, proof.tags)
                  .finalizeOriginalNewOnce(
                      proof.admission,
                      template,
                      account,
                      issuer,
                      proof.execution,
                      transferDeadline())
              : null) {
        UUID id = proof.begin();
        long deadline = transferDeadline();
        var item =
            proof.transfers.within(deadline, ignored -> proof.transfers.capture(id).getFirst());
        proof.transfers.within(
            deadline,
            budget -> {
              var before =
                  proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
              var dispatch = proof.transfers.fenceDispatch(item, before);
              UUID submission = dispatch.takeSubmission(item);
              assertThat(submission).isNotNull();
              finalOwner.fenceCapacityExecutionOnce(
                  item.execution(), item.candidate(), item.assignment(), submission, deadline);
              // Simulate process loss after terminal Redis observation but before SQL retention.
              return null;
            });
        assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
        assertThat(proof.itemImage(id)).contains("\"fence_readback\": null");
        UUID retainedDispatch =
            proof
                .sql
                .fetchSingle(
                    "SELECT fence_dispatch_id FROM game_session_region_owner_transfer_item WHERE"
                        + " transfer_id=?",
                    id)
                .get("fence_dispatch_id", UUID.class);
        assertThat(retainedDispatch).isNotNull();
        proof.recreateTransferOwners();
        proof.fence(item);
        assertThat(proof.itemImage(id))
            .contains(retainedDispatch.toString())
            .doesNotContain("\"fence_readback\": null");
        assertThat(proof.executionState(proof.execution))
            .isEqualTo(committed ? "COMMITTED" : "CLOSED");
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        assertThat(proof.install(id).executorFence()).isGreaterThan(proof.prior.executorFence());
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void interruptedForwardReceiptRecoversExactTerminalProofWithoutReapplying(boolean committed)
      throws Exception {
    try (var proof = new TransferSqlProof();
        var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
                proof.admission.candidate(), proof.clock);
        var raw = credential.template();
        var template = raw.bindCapacityExecution(proof.execution)) {
      var account = proof.controlledAccountOpen();
      var issuer = proof.issuerOpen();
      proof.authorizeAccountSend(proof.execution);
      proof
          .capacity(proof.sql)
          .authorize(
              proof.admission, template, account, issuer, proof.execution, transferDeadline());
      var finalOwner =
          new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
              proof.redis, proof.scripts, proof.clock, proof.tags);
      var assignment = proof.tags.assignmentForTransition(proof.execution.transitionId());
      if (!committed) {
        finalOwner.closeCapacityExecutionOnce(
            proof.execution, proof.admission.candidate(), assignment, transferDeadline());
      }
      try (var physicalCommit =
          committed
              ? new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      proof.redis, proof.scripts, proof.clock, proof.tags)
                  .finalizeOriginalNewOnce(
                      proof.admission,
                      template,
                      account,
                      issuer,
                      proof.execution,
                      transferDeadline())
              : null) {
        UUID id = proof.begin();
        long deadline = transferDeadline();
        var item =
            proof.transfers.within(deadline, ignored -> proof.transfers.capture(id).getFirst());
        long terminalCode =
            committed
                ? CanonicalGameplayOriginalNewFinalAdmissionRedisOwner
                    .CAPACITY_EXECUTION_COMMITTED_OBSERVED
                : CanonicalGameplayOriginalNewFinalAdmissionRedisOwner
                    .CAPACITY_EXECUTION_CLOSED_OBSERVED;
        proof.transfers.within(
            deadline,
            budget -> {
              var before =
                  proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
              var dispatch = proof.transfers.fenceDispatch(item, before);
              var scoped =
                  new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
                      proof.redis,
                      proof.scripts,
                      proof.clock,
                      proof.tags.withCapacityExecutionDsl(budget.sql(proof.sql)),
                      () ->
                          CanonicalGameplayOriginalNewCapacityExecutionOwner.connectForRead(
                              proof.redis, budget),
                      budget::remainingMillis,
                      budget::awaitAsyncClose);
              var prepared =
                  scoped.prepareTransferReceipt(
                      item.execution(), item.candidate(), item.assignment(), dispatch, deadline);
              dispatch.seal(prepared);
              assertThat(
                      scoped.applyTransferReceipt(
                          item.execution(),
                          item.candidate(),
                          item.assignment(),
                          dispatch,
                          deadline))
                  .isEqualTo(terminalCode);
              // End after the actual terminal receipt, before the receiving owner retains SQL.
              return null;
            });
        assertThat(proof.executionState(proof.execution)).isEqualTo("AUTHORIZED");
        assertThat(proof.itemImage(id))
            .contains("\"fence_apply_issued\": true", "\"fence_readback\": null");
        UUID retainedDispatch =
            proof
                .sql
                .fetchSingle(
                    "SELECT fence_dispatch_id FROM game_session_region_owner_transfer_item WHERE"
                        + " transfer_id=?",
                    id)
                .get("fence_dispatch_id", UUID.class);
        assertThat(retainedDispatch).isNotNull();
        proof.recreateTransferOwners();
        var completed =
            List.of(
                new java.util.concurrent.CompletableFuture<ReceiptAction>(),
                new java.util.concurrent.CompletableFuture<ReceiptAction>());
        var commands = new java.util.concurrent.atomic.AtomicInteger();
        var observedRedis = org.mockito.Mockito.mock(io.lettuce.core.RedisClient.class);
        org.mockito.Mockito.when(observedRedis.connect(ByteArrayCodec.INSTANCE))
            .thenAnswer(ignored ->
                observingReceiptConnection(
                    proof.redis.connect(ByteArrayCodec.INSTANCE),
                    completed,
                    commands,
                    terminalCode));
        var recovered =
            new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                proof.sql, observedRedis, proof.scripts, proof.clock, proof.tags);
        long recoveryDeadline = transferDeadline();
        proof.transfers.within(
            recoveryDeadline,
            budget -> {
              var before =
                  proof.receiver.observeForRegionOwnerTransfer(
                      id, item.ordinal(), recoveryDeadline);
              var dispatch = proof.transfers.fenceDispatch(item, before);
              var actual =
                  recovered
                      .forTransferBudget(budget)
                      .fenceForTransfer(item, dispatch, recoveryDeadline);
              var after =
                  proof.receiver.observeForRegionOwnerTransfer(
                      id, item.ordinal(), recoveryDeadline);
              proof.transfers.retainFence(item, actual, before, after);
              return null;
            });
        for (var observed : completed) {
          assertThat(observed.get(2, java.util.concurrent.TimeUnit.SECONDS))
              .isEqualTo(ReceiptAction.OBSERVE);
        }
        assertThat(commands.get()).isEqualTo(2);
        assertThat(proof.itemImage(id))
            .contains(retainedDispatch.toString())
            .doesNotContain("\"fence_readback\": null");
        assertThat(proof.executionState(proof.execution))
            .isEqualTo(committed ? "COMMITTED" : "CLOSED");
        proof.verifyPage(id);
        boolean verified =
            proof.transfers.within(
                transferDeadline(), ignored -> proof.transfers.finishVerification(id));
        assertThat(verified).isTrue();
        assertThat(proof.install(id).executorFence()).isGreaterThan(proof.prior.executorFence());
      }
    }
  }

  @Test
  void recreatedRepositoryRetainsLostFenceReplyAndImmutableOriginalReceiptsAndCompletion()
      throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.execution);
      UUID id = proof.begin();
      var item =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.capture(id).getFirst());
      String original = proof.originalImage();
      assertThatThrownBy(
              () -> {
                proof.fence(item);
                // The SQL receipt has committed, but the caller did not receive its result.
                throw new IllegalStateException("Controlled lost retained fence reply");
              })
          .hasMessage("Controlled lost retained fence reply");
      String receipt = proof.itemImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      var replay =
          proof.transfers.within(
              transferDeadline(), ignored -> proof.transfers.page(id, -1).getFirst());
      assertThat(replay.execution().canonicalBytes())
          .containsExactly(item.execution().canonicalBytes());
      proof.fence(replay);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_original_new_capacity_execution SET"
                  + " selected_owner_id='changed' WHERE execution_digest=?",
              proof.execution.digest()));
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer SET intent_digest=? WHERE"
                  + " transfer_id=?",
              "sha256:" + "f".repeat(64),
              id));
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer_item SET canonical_execution=? WHERE"
                  + " transfer_id=?",
              "changed-original".getBytes(StandardCharsets.UTF_8),
              id));
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer_item SET fence_readback=? WHERE"
                  + " transfer_id=?",
              "changed-readback".getBytes(StandardCharsets.UTF_8),
              id));
      for (String table :
          List.of(
              "game_session_original_new_capacity_execution",
              "game_session_region_owner_transfer",
              "game_session_region_owner_transfer_item")) {
        assertSqlGuard(() -> proof.sql.execute("DELETE FROM " + table));
        assertSqlGuard(() -> proof.sql.execute("TRUNCATE TABLE " + table + " CASCADE"));
      }
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.itemImage(id)).isEqualTo(receipt);
      proof.verifyPage(id);
      proof.transfers.within(transferDeadline(), ignored -> proof.transfers.finishVerification(id));
      var installed = proof.install(id);
      String completion = proof.transferImage(id);
      proof.recreateTransferOwners();
      assertThat(proof.transfers.readCurrent(id, 0).controlState()).isEqualTo("COMPLETED");
      assertThat(proof.transfers.readCurrent(id, 0).completionDigest()).isNotBlank();
      assertThatThrownBy(() ->
              proof.transfers.within(transferDeadline(), ignored -> proof.transfers.capture(id)))
          .isInstanceOf(IllegalStateException.class);
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer SET new_owner_id='changed-successor'"
                  + " WHERE transfer_id=?",
              id));
      assertSqlGuard(() ->
          proof.sql.execute(
              "UPDATE game_session_region_owner_transfer_item SET verified_round=? WHERE"
                  + " transfer_id=?",
              UUID.randomUUID(),
              id));
      assertThat(proof.transferImage(id)).isEqualTo(completion);
      assertThat(proof.originalImage()).isEqualTo(original);
      assertThat(proof.currentOwner()).isEqualTo(installed);
    }
  }
}
