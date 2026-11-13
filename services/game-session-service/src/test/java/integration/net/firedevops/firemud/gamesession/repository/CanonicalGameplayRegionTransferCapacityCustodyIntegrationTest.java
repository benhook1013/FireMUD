package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
                      "SELECT count(*) FROM game_session_capacity_evidence_charge WHERE"
                          + " tenant_id=?",
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
      // Controlled reservation envelopes seed SQL custody, not Account-authorized multiattempt
      // behavior. Every candidate, bridge, mapping and owner source is retained by its real owner.
      List<OriginalNewCapacityExecution> originals = new ArrayList<>();
      for (int index = 0; index < 65; index++) {
        var original = proof.newControlledExecution();
        originals.add(original);
        proof.seedUndeliveredDispatch(original);
      }
      UUID id = proof.begin();
      var first = proof.captureAndFence(id);
      assertThat(first).hasSize(32);
      assertThat(first)
          .allMatch(item -> item.execution().transitionId().equals(proof.execution.transitionId()));
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(),
                      ignored -> proof.transfers.frozenOriginal(proof.execution.transitionId())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Normal region owner transfer is held");
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(), ignored -> proof.transfers.verificationCursor(id)))
          .isInstanceOf(IllegalStateException.class);
      proof.recreateTransferOwners();
      assertThat(proof.begin()).isEqualTo(id);
      assertThat(proof.captureAndFence(id)).hasSize(32);
      assertThatThrownBy(
              () ->
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
        proof.seedUndeliveredDispatch(proof.newControlledExecution());
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
        assertThatThrownBy(
                () ->
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
        assertThatThrownBy(
                () ->
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

  @Test
  void installationRefusesSignedEvidenceThatExpiresWhileAcquiringTheRegionLock() throws Exception {
    try (var proof = new TransferSqlProof()) {
      proof.seedUndeliveredDispatch(proof.newControlledExecution());
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
      assertThatThrownBy(
              () ->
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
      try {
        long start = System.nanoTime();
        assertThatThrownBy(
                () ->
                    proof.receiver.observeForRegionOwnerTransfer(
                        id, captured.getFirst().ordinal(), proof.clock.millis() + 500L))
            .isInstanceOf(ObservationUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
      } finally {
        lock.rollback();
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
  void rawOwnerReplacementCannotSkipFrozenIncompleteOrChangedExactTransfer() throws Exception {
    try (var proof = new TransferSqlProof()) {
      assertThatThrownBy(
              () ->
                  proof.sql.transaction(
                      configuration ->
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
      assertSqlGuard(
          () ->
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
      var lateExecution = proof.newControlledExecution();
      proof.retainExactChargeForControlledSeed(lateExecution);
      assertThatThrownBy(() -> proof.seedUndeliveredDispatch(lateExecution))
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
            assertThat(competing.takeSubmission(item)).isNull();
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

  @Test
  void interruptedFenceRetentionRecoversExactTerminalProofWithoutDispatchingAgain()
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
      try (var physicalCommit =
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
        long deadline = transferDeadline();
        var item =
            proof.transfers.within(deadline, ignored -> proof.transfers.capture(id).getFirst());
        proof.transfers.within(
            deadline,
            budget -> {
              var before =
                  proof.receiver.observeForRegionOwnerTransfer(id, item.ordinal(), deadline);
              var dispatch = proof.transfers.fenceDispatch(item, before);
              proof
                  .capacity(proof.sql)
                  .forTransferBudget(budget)
                  .fenceForTransfer(item, dispatch, deadline);
              return null;
            });
        assertThat(proof.executionState(proof.execution)).isEqualTo("COMMITTED");
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
        String original = proof.originalImage();
        proof.recreateTransferOwners();
        proof.fence(item);
        assertThat(proof.itemImage(id))
            .contains(retainedDispatch.toString())
            .doesNotContain("\"fence_readback\": null");
        assertThat(proof.originalImage()).isEqualTo(original);
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
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_original_new_capacity_execution SET"
                      + " selected_owner_id='changed' WHERE execution_digest=?",
                  proof.execution.digest()));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET intent_digest=? WHERE"
                      + " transfer_id=?",
                  "sha256:" + "f".repeat(64),
                  id));
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer_item SET canonical_execution=? WHERE"
                      + " transfer_id=?",
                  "changed-original".getBytes(StandardCharsets.UTF_8),
                  id));
      assertSqlGuard(
          () ->
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
      assertThatThrownBy(
              () ->
                  proof.transfers.within(
                      transferDeadline(), ignored -> proof.transfers.capture(id)))
          .isInstanceOf(IllegalStateException.class);
      assertSqlGuard(
          () ->
              proof.sql.execute(
                  "UPDATE game_session_region_owner_transfer SET new_owner_id='changed-successor'"
                      + " WHERE transfer_id=?",
                  id));
      assertSqlGuard(
          () ->
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
