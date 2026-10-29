package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.grpc.Server;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.PhysicalRedisFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionLeaseManualFactory;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortOutcome;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortCompositionFixture;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortManualFactory;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBindingLocalReleaseOperation;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Actual Account acquisition/current caller/physical token and abort owner, real GS durable
 * PROVISIONAL reverse read and guarded abort CAS, physical PostgreSQL/Redis and manual mTLS.
 * Launch/publication/initial-target and test PKI inputs remain controlled. The physical issuer
 * proof is binding-local only; it does not prove runtime registration, admission, production PKI,
 * or completion of the remaining Account and region terminal obligations.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AccountGameSessionDefinitiveAbortCompositionIntegrationTest {
  private static final String GS = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ACCOUNT = "spiffe://firemud/ns/test/sa/account-service";
  private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest accountFixtures =
      new AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest();
  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void startPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.startPostgres();
  }

  @AfterAll
  static void stopPostgres() {
    AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.stopPostgres();
  }

  @AfterEach
  void cleanupAccountSchemas() {
    accountFixtures.dropRunOwnedSchemas();
  }

  @Test
  void retainedUnknownAbortResolvesTheRealTransitionWithoutRewritingAccountTerminal()
      throws Exception {
    try (var session = open()) {
      var original = session.gs.source();
      String beforeUnknownAbort = session.image();
      UUID requestId = UUID.fromString((String) session.lease.carrier().get("requestId"));
      var accountDsl = DSL.using(session.accountFixture.dataSource, SQLDialect.POSTGRES);
      var unknown = session.client.abort(session.jwt, session.leaseBytes(), null);
      assertThat(unknown.state()).isEqualTo(State.ABORTED);
      assertThat(unknown.bindingDecisionId()).isNull();
      assertThat(unknown.abortResolution()).isNull();
      assertThat(unknown.orphanCleanupId()).isNotNull();
      assertThat(session.image()).isEqualTo(beforeUnknownAbort);
      String originalTerminal =
          accountDsl
              .fetchSingle(
                  "SELECT to_jsonb(operation)::text FROM account_gameplay_admission_lease_operations"
                      + " operation WHERE request_id = ?",
                  requestId)
              .get(0, String.class);
      org.mockito.Mockito.verify(session.reverse, org.mockito.Mockito.never()).read(any(), any());

      var outcome = session.abort();
      assertThat(outcome.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      assertThat(outcome.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      assertThat(outcome.accountOperation().bindingDecisionId()).isNull();
      var resolution = outcome.accountOperation().abortResolution();
      assertThat(resolution).isNotNull();
      assertThat(resolution.bindingDecisionId()).isEqualTo(session.gs.transitionId());
      assertThat(resolution.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      assertThat(resolution.originalLeaseSha256()).isEqualTo(session.lease.sha256());
      assertThat(outcome.accountOperation().originalLeaseEvidence().canonicalJson())
          .isEqualTo(original.originalDecision().leaseEvidenceJson());
      assertThat(session.gs.source().candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(session.gs.source().reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);

      var pendingRelease = session.gs.prepareIssuerRelease();
      var releaseReadback = session.gs.projectIssuerRelease(pendingRelease);
      session.gs.completeIssuerRelease(pendingRelease, releaseReadback);
      var pendingRetirement = session.gs.prepareIssuerRetirement(session.gs.transitionId());
      assertThat(session.gs.retireIssuerCapacityOutcome(pendingRetirement)).isEqualTo(1L);
      assertThat(
              session.gs.isIssuerRetirementComplete(
                  session.gs.prepareIssuerRetirement(session.gs.transitionId())))
          .isTrue();
      String settledImage = session.image();
      String retainedResolution =
          accountDsl
              .fetchSingle(
                  "SELECT to_jsonb(resolution)::text FROM account_gameplay_admission_abort_resolutions"
                      + " resolution WHERE request_id = ?",
                  requestId)
              .get(0, String.class);

      // A fresh Account server and GS owner recover durable resolution after GS is ABORTED;
      // querying its PROVISIONAL reverse reader again would fail at this point.
      session.restartAccount();
      var replay = session.abort();
      assertThat(replay.accountOperation().bindingDecisionId()).isNull();
      assertThat(replay.accountOperation().abortResolution()).isEqualTo(resolution);
      assertThat(replay.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      org.mockito.Mockito.verify(session.reverse, org.mockito.Mockito.times(1))
          .read(org.mockito.ArgumentMatchers.eq(session.gs.transitionId()), any());
      assertThatThrownBy(
              () -> session.client.abort(session.jwt, session.leaseBytes(), UUID.randomUUID()))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> session.abortWith("invalid-current-caller-token"))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.image()).isEqualTo(settledImage);
      var retainedRead = session.client.read(session.jwt, session.leaseBytes());
      assertThat(retainedRead.bindingDecisionId()).isNull();
      assertThat(retainedRead.abortResolution()).isNull();
      assertThat(retainedRead.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      assertThat(
              accountDsl
                  .fetchSingle(
                      "SELECT to_jsonb(operation)::text FROM"
                          + " account_gameplay_admission_lease_operations operation"
                          + " WHERE request_id = ?",
                      requestId)
                  .get(0, String.class))
          .isEqualTo(originalTerminal);
      assertThat(
              accountDsl
                  .fetchSingle(
                      "SELECT to_jsonb(resolution)::text FROM"
                          + " account_gameplay_admission_abort_resolutions resolution"
                          + " WHERE request_id = ?",
                      requestId)
                  .get(0, String.class))
          .isEqualTo(retainedResolution);
    }
  }

  @Test
  void actualAbortExactRetryAndRestartRetainOriginalLeaseAndRejectTerminalRewrites()
      throws Exception {
    try (var session = open()) {
      var original = session.gs.source();
      var result = session.abort();
      assertThat(result.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      var source = session.gs.source();
      var retainedImage = session.image();
      assertThat(source.isAccountAborted()).isTrue();
      assertThat(source.orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(source.originalDecision().leaseEvidenceJson())
          .isEqualTo(original.originalDecision().leaseEvidenceJson());
      assertThat(source.originalDecision().leaseSha256())
          .isEqualTo(original.originalDecision().leaseSha256());
      assertThat(source.candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(source.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);
      // New owner instance and independent repository read recover the same retained source.
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.ABORTED);
      assertThat(
              session
                  .client
                  .finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId())
                  .state())
          .isEqualTo(State.ABORTED);
      assertThatThrownBy(() -> session.abortWith("invalid-current-caller-token"))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.image()).isEqualTo(retainedImage);

      for (String assignment :
          new String[] {
            "admission_orphan_cleanup_id = '34343434-3434-4434-8434-343434343434'",
            "status = 'PROVISIONAL', admission_orphan_cleanup_id = NULL",
            "admission_lease_id = '45454545-4545-4545-8545-454545454545'",
            "admission_lease_digest = '" + "e".repeat(64) + "'",
            "admission_lease_evidence = '{}'",
            "inventory_revision = inventory_revision - 1"
          }) {
        assertThatThrownBy(
                () ->
                    session
                        .gs
                        .dsl()
                        .execute(
                            "UPDATE game_session_canonical_binding_transition SET "
                                + assignment
                                + " WHERE transition_id = ?",
                            session.gs.transitionId()))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(session.image()).isEqualTo(retainedImage);
      }
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_partition_reservation SET lifecycle = 'RELEASED' WHERE transition_id = ?",
                          session.gs.transitionId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(retainedImage);
      for (String statement :
          new String[] {
            "UPDATE game_session_canonical_issuer_partition_reservation SET reservation_fence = '56565656-5656-4656-8656-565656565656' WHERE transition_id = ?",
            "UPDATE game_session_canonical_gameplay_binding_inventory SET lifecycle = 'ACTIVE' WHERE transition_id = ?",
            "UPDATE game_session_canonical_gameplay_binding_inventory SET session_id = '67676767-6767-4767-8767-676767676767' WHERE transition_id = ?",
            "DELETE FROM game_session_canonical_issuer_partition_reservation WHERE transition_id = ?",
            "DELETE FROM game_session_canonical_gameplay_binding_inventory WHERE transition_id = ?"
          }) {
        assertThatThrownBy(() -> session.gs.dsl().execute(statement, session.gs.transitionId()))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(session.image()).isEqualTo(retainedImage);
      }
      // Cleanup is intentionally unresolved. A newer first-NEW candidate must remain fenced;
      // this boundary does not fabricate an admitted/newer binding to claim cleanup safety.
      assertThatThrownBy(session.gs::prepareNewerCandidate).isInstanceOf(RuntimeException.class);
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(session.image()).isEqualTo(retainedImage);
    }
  }

  @Test
  void actualAccountAbortWithLostReplyKeepsGsProvisionalThenRecoversSameOriginalAfterRestart()
      throws Exception {
    try (var session = open()) {
      var before = session.image();
      var lostReplyClient = mock(AccountGameplayAdmissionLeaseClient.class);
      doAnswer(
              invocation -> {
                session.client.abort(session.jwt, session.leaseBytes(), session.gs.transitionId());
                throw new IllegalStateException(
                    "controlled loss after actual Account ABORTED reply");
              })
          .when(lostReplyClient)
          .abort(any(), any(), any());
      var lostReplyOwner =
          CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredOwner(
              session.gs.dsl(), lostReplyClient);
      assertThatThrownBy(() -> lostReplyOwner.abortFirstNew(session.gs.transitionId(), session.jwt))
          .hasMessage("controlled loss after actual Account ABORTED reply");
      assertThat(session.image()).isEqualTo(before);
      var accountAbort = session.client.read(session.jwt, session.leaseBytes());
      assertThat(accountAbort.state()).isEqualTo(State.ABORTED);
      session.restartAccount();
      assertThat(session.abort().orphanCleanupId()).isEqualTo(accountAbort.orphanCleanupId());
      assertThat(
              session
                  .client
                  .finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId())
                  .state())
          .isEqualTo(State.ABORTED);
    }
  }

  @Test
  void unavailableActualReverseReadCannotInferAbortFromAbsenceOrExpiry() throws Exception {
    try (var session = open()) {
      var before = session.image();
      session.gsServer.shutdownNow();
      assertThat(session.gsServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(session::abort).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
      long expiresAt = Long.parseLong((String) session.lease.carrier().get("expiresAt"));
      long remaining = expiresAt - System.currentTimeMillis();
      if (remaining >= 0) Thread.sleep(remaining + 5L);
      assertThatThrownBy(session::abort).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
    }
  }

  @Test
  void actualConcurrentFinalizeAndAbortHaveOneAccountWinnerWithoutGsCommittedRelabelling()
      throws Exception {
    try (var session = open();
        var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var finalize =
          executor.submit(
              () -> {
                start.await();
                try {
                  return session.client.finalizeLease(
                      session.jwt, session.leaseBytes(), session.gs.transitionId());
                } catch (RuntimeException ambiguous) {
                  return null;
                }
              });
      var abort =
          executor.submit(
              () -> {
                start.await();
                try {
                  return session.abort();
                } catch (RuntimeException ambiguous) {
                  return null;
                }
              });
      start.countDown();
      var finalized = finalize.get(20, TimeUnit.SECONDS);
      var aborted = abort.get(20, TimeUnit.SECONDS);
      // A missing ACK may make a committed read unavailable. The retained SQL operation still
      // decides one winner; no test manufactures a receipt or relabels that winner.
      var accountRow = session.accountFixture.dataSource;
      var retained =
          org.jooq
              .impl
              .DSL
              .using(accountRow, org.jooq.SQLDialect.POSTGRES)
              .fetchSingle(
                  "SELECT status FROM account_gameplay_admission_lease_operations WHERE lease_id = ?",
                  UUID.fromString((String) session.lease.carrier().get("leaseId")));
      assertThat(retained.get("status", String.class)).isIn("ABORTED", "COMMITTED");
      if ("ABORTED".equals(retained.get("status", String.class))) {
        assertThat(session.abort().state())
            .isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
        assertThat(
                session
                    .client
                    .finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId())
                    .state())
            .isEqualTo(State.ABORTED);
        if (finalized != null) assertThat(finalized.state()).isEqualTo(State.ABORTED);
      } else {
        assertThat(session.gs.source().isAccountAborted()).isFalse();
        if (aborted != null)
          assertThat(aborted.state())
              .isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ACCOUNT_COMMITTED);
      }
    }
  }

  @Test
  void actualAccountAbortedOrdinaryIssuerMemberReleaseHasExactReadbackRetryAndFreesCapacity()
      throws Exception {
    try (var session = open()) {
      UUID originalOperationId = session.gs.originalOperationId();
      byte[] originalMemberBytes = session.gs.originalMemberValueBytes();

      var abort = session.abort();
      assertThat(abort.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      var aborted = session.gs.source();
      assertThat(aborted.isAccountAborted()).isTrue();
      assertThat(aborted.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);

      UUID secondOperationId = session.gs.prepareOtherControllerNew("second-controller");
      assertThat(secondOperationId).isNotEqualTo(originalOperationId);
      var secondReadback = session.gs.projectOtherControllerNew("second-controller");
      assertThat(secondReadback.operationId()).isEqualTo(secondOperationId);
      assertThat(secondReadback.scriptOutcome()).isEqualTo(1L);
      var clockAfterOtherWriter =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1")
              .get("inventory_revision", java.math.BigDecimal.class)
              .toBigIntegerExact();
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.TWO);
      assertThatThrownBy(() -> session.gs.prepareOtherControllerNew("full-before-release"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("capacity");

      var pending = session.gs.prepareIssuerRelease();
      assertThat(pending.originalOperationId()).isEqualTo(originalOperationId);
      assertThat(pending.exactOriginalMemberValueBytes()).containsExactly(originalMemberBytes);
      assertThat(pending.sourceInventoryRevision())
          .isGreaterThan(aborted.transition().inventoryRevision());
      assertThat(pending.sourceInventoryRevision()).isGreaterThan(clockAfterOtherWriter);
      assertThat(session.gs.source().transition().inventoryRevision())
          .isEqualTo(pending.sourceInventoryRevision());

      String preparedImage = session.image();
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
                              + " SET original_member_value_bytes = ? WHERE operation_id = ?",
                          new byte[] {1},
                          pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
                              + " SET original_reservation_fence = ? WHERE operation_id = ?",
                          UUID.randomUUID(),
                          pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
                              + " SET anchor_operation_fence = anchor_operation_fence + 1 WHERE operation_id = ?",
                          pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .dsl()
                      .execute(
                          "UPDATE game_session_canonical_issuer_partition_reservation"
                              + " SET lifecycle = 'RELEASED', inventory_revision = inventory_revision + 1"
                              + " WHERE reservation_id = ?",
                          pending.abortSource().reservation().reservationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);

      assertThatThrownBy(() -> session.gs.prepareOtherControllerNew("still-full-during-release"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("capacity");

      session.gs.moveIssuerSourceCheckpoint();
      assertThatThrownBy(() -> session.gs.projectIssuerRelease(pending))
          .isInstanceOf(RuntimeException.class);
      session.gs.restoreIssuerState();
      assertThat(session.gs.readReleaseCapacityValue(pending)).containsExactly(originalMemberBytes);
      assertThat(session.gs.readReleaseBindingValue(pending)).containsExactly(originalMemberBytes);

      session.gs.moveIssuerIncarnation();
      assertThatThrownBy(() -> session.gs.projectIssuerRelease(pending))
          .isInstanceOf(RuntimeException.class);
      session.gs.restoreIssuerState();
      assertThat(session.gs.readReleaseCapacityValue(pending)).containsExactly(originalMemberBytes);
      assertThat(session.gs.readReleaseBindingValue(pending)).containsExactly(originalMemberBytes);

      byte[] wrongReservationValue = session.gs.otherMemberValueBytes("second-controller");
      session.gs.installConflictingReleaseCapacity(pending, wrongReservationValue);
      assertThatThrownBy(() -> session.gs.projectIssuerRelease(pending))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.readReleaseCapacityValue(pending))
          .containsExactly(wrongReservationValue);
      assertThat(session.gs.readReleaseBindingValue(pending)).containsExactly(originalMemberBytes);
      session.gs.restoreOriginalReleaseCapacity(pending);

      session.gs.installConflictingReleaseBinding(pending);
      byte[] conflictingBinding = session.gs.readReleaseBindingValue(pending);
      byte[] originalCapacity = session.gs.readReleaseCapacityValue(pending);
      assertThatThrownBy(() -> session.gs.projectIssuerRelease(pending))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.readReleaseBindingValue(pending)).containsExactly(conflictingBinding);
      assertThat(session.gs.readReleaseCapacityValue(pending)).containsExactly(originalCapacity);
      session.gs.restoreOriginalReleaseBinding(pending);
      assertThat(session.gs.readReleaseBindingValue(pending)).containsExactly(originalMemberBytes);
      assertThat(session.gs.readReleaseCapacityValue(pending)).containsExactly(originalMemberBytes);

      assertThatThrownBy(() -> session.gs.projectIssuerReleaseAndLoseReply(pending))
          .hasMessage("controlled lost issuer release reply after physical readback");
      var resumed = session.gs.prepareIssuerRelease();
      assertThat(resumed.operationId()).isEqualTo(pending.operationId());
      assertThat(resumed.sourceInventoryRevision()).isEqualTo(pending.sourceInventoryRevision());
      assertThat(resumed.exactReleasedMemberValueBytes())
          .containsExactly(pending.exactReleasedMemberValueBytes());
      var physicalReadback = session.gs.projectIssuerRelease(resumed);
      assertThat(physicalReadback.scriptOutcome()).isEqualTo(2L);
      assertThat(session.gs.readbackHasExactReleasedMember(resumed, physicalReadback)).isTrue();
      var clockBeforeCompletion =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1")
              .get("inventory_revision", java.math.BigDecimal.class)
              .toBigIntegerExact();
      assertThat(clockBeforeCompletion).isEqualTo(pending.sourceInventoryRevision());
      var afterPhysicalReadback = session.gs.source();
      assertThat(afterPhysicalReadback.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);
      var released = session.gs.completeIssuerRelease(resumed, physicalReadback);
      assertThat(released.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertThat(released.completionInventoryRevision())
          .isGreaterThan(released.sourceInventoryRevision());
      assertThat(released.completionInventoryRevision()).isGreaterThan(clockBeforeCompletion);
      assertThat(released.exactOriginalMemberValueBytes()).containsExactly(originalMemberBytes);
      assertThat(session.gs.releasedReservation(released).lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(session.gs.releasedReservation(released).inventoryRevision())
          .isEqualTo(released.sourceInventoryRevision());
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.ONE);

      var source = session.gs.source();
      assertThat(source.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(source.candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      var issuer =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT status, local_release_operation_id, inventory_revision"
                      + " FROM game_session_canonical_binding_issuer_index_obligation WHERE transition_id = ?",
                  session.gs.transitionId());
      assertThat(issuer.get("status", String.class)).isEqualTo("RELEASED");
      assertThat(issuer.get("local_release_operation_id", UUID.class))
          .isEqualTo(released.operationId());
      assertThat(issuer.get("inventory_revision", java.math.BigDecimal.class))
          .isEqualTo(new java.math.BigDecimal(released.completionInventoryRevision()));
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_account_index_obligation"
                          + " WHERE transition_id = ? AND obligation_ordinal = 0",
                      session.gs.transitionId())
                  .get("status", String.class))
          .isEqualTo("REQUIRED");
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_region_bridge_obligation WHERE transition_id = ?",
                      session.gs.transitionId())
                  .get("status", String.class))
          .isEqualTo("REQUIRED");

      UUID thirdOperationId =
          session.gs.prepareOtherControllerNew("freed-capacity-other-controller");
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.TWO);
      var thirdReadback = session.gs.projectOtherControllerNew("freed-capacity-other-controller");
      assertThat(thirdReadback.operationId()).isEqualTo(thirdOperationId);
      assertThat(thirdReadback.scriptOutcome()).isEqualTo(1L);

      byte[] retainedReadback = released.exactPhysicalReadbackFieldSetBytes();
      var releaseReplay = session.gs.prepareIssuerRelease();
      assertThat(releaseReplay.operationId()).isEqualTo(released.operationId());
      assertThat(releaseReplay.exactReleasedMemberValueBytes())
          .containsExactly(released.exactReleasedMemberValueBytes());
      var replayReadback = session.gs.projectIssuerRelease(releaseReplay);
      assertThat(replayReadback.scriptOutcome()).isEqualTo(2L);
      var completedReplay = session.gs.completeIssuerRelease(releaseReplay, replayReadback);
      assertThat(completedReplay.completionInventoryRevision())
          .isEqualTo(released.completionInventoryRevision());
      assertThat(completedReplay.exactPhysicalReadbackFieldSetBytes())
          .containsExactly(retainedReadback);
      assertThat(session.abort().orphanCleanupId()).isEqualTo(abort.orphanCleanupId());
    }
  }

  @Test
  void actualReverseReadRefusesWrongPeerDecisionAndChangedOriginalTupleWithoutAccountAbort()
      throws Exception {
    try (var session = open()) {
      var before = session.image();
      assertThat(session.reverse.read(session.gs.transitionId(), session.lease).bindingDecisionId())
          .isEqualTo(session.gs.transitionId());
      assertThatThrownBy(() -> session.reverse.read(UUID.randomUUID(), session.lease))
          .isInstanceOf(RuntimeException.class);
      var changedCarrier = new java.util.LinkedHashMap<>(session.lease.carrier());
      changedCarrier.put(
          "leaseFence", session.lease.leaseFence().add(java.math.BigInteger.ONE).toString());
      var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
      assertThatThrownBy(() -> session.reverse.read(session.gs.transitionId(), changedLease))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  session.client.abort(
                      session.jwt,
                      changedLease.canonicalJson().getBytes(StandardCharsets.UTF_8),
                      session.gs.transitionId()))
          .isInstanceOf(RuntimeException.class);
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("127.0.0.1:" + session.gsServer.getPort());
      try (var wrongPeer =
          new CanonicalGameplayAdmissionDecisionReadClient(
              endpoints,
              session.pki.clientProperties(session.pkiDirectory, session.pki.identity("wrong")),
              new GrpcChannelFactory(),
              "test")) {
        wrongPeer.init();
        assertThatThrownBy(() -> wrongPeer.read(session.gs.transitionId(), session.lease))
            .isInstanceOf(RuntimeException.class);
      }
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
      // The runtime owner cannot retire the launch underneath this exact pending decision.
      assertThatThrownBy(session.gs::stopRuntime)
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining(
              "runtime row is fenced by an unresolved lease-bound binding decision");
      assertThat(session.image()).isEqualTo(before);
      assertThat(session.abort().state())
          .isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
    }
  }

  @Test
  void actualCompletedMemberAckSurvivesAccountAbortAndRetainsUnresolvedPhysicalCleanup()
      throws Exception {
    try (var session = open(true)) {
      var acknowledgement = session.gs.repairAccountMember();
      assertThat(acknowledgement.schemaVersion()).isEqualTo(2);
      assertThat(acknowledgement.transitionId()).isEqualTo(session.gs.transitionId());
      assertThat(session.gs.source().candidate().accountIndexState())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      var originalAck = session.gs.memberAcknowledgementImage();
      var originalPhase = session.gs.accountObligationPhaseImage();
      var result = session.abort();
      assertThat(result.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.ABORTED);
      assertThat(
              session
                  .client
                  .finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId())
                  .state())
          .isEqualTo(State.ABORTED);
      var retained = session.gs.source();
      assertThat(retained.candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(retained.candidate().accountIndexState())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);
      assertThat(retained.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      session.restartAccount();
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      for (String statement :
          new String[] {
            "UPDATE game_session_canonical_account_member_repair_operation SET readback_member = 'invented' WHERE transition_id = ?",
            "DELETE FROM game_session_canonical_account_member_repair_operation WHERE transition_id = ?",
            "UPDATE game_session_canonical_binding_account_index_obligation SET status = 'REQUIRED' WHERE transition_id = ?"
          }) {
        assertThatThrownBy(() -> session.gs.dsl().execute(statement, session.gs.transitionId()))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
        assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      }
      // Definitive abort permits the actual runtime owner to retire this instance. Cleanup
      // must still use the retained original operation, without reopening admission.
      session.gs.stopRuntime();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_instances WHERE id = ?",
                      session.gs.target().gameInstanceId())
                  .get("status", String.class))
          .isEqualTo("STOPPED");
      // Issuer release is independent of the historical account ADD acknowledgement.
      // The physical account member and its cleanup obligation remain retained throughout.
      var pendingIssuerRelease = session.gs.prepareIssuerRelease();
      var issuerReadback = session.gs.projectIssuerRelease(pendingIssuerRelease);
      var issuerRelease = session.gs.completeIssuerRelease(pendingIssuerRelease, issuerReadback);
      assertThat(issuerRelease.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertThat(session.gs.source().reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(session.gs.source().candidate().accountIndexState())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);
      assertThat(session.gs.source().candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      session.restartAccount();
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      var replayIssuerRelease = session.gs.prepareIssuerRelease();
      var replayIssuerReadback = session.gs.projectIssuerRelease(replayIssuerRelease);
      assertThat(
              session
                  .gs
                  .completeIssuerRelease(replayIssuerRelease, replayIssuerReadback)
                  .completionInventoryRevision())
          .isEqualTo(issuerRelease.completionInventoryRevision());
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
    }
  }

  @Test
  void controlledHistoricalV1038AbortRowsAndHistorySurviveForwardCleanupMigration()
      throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    // Reuse only the run-owned PostgreSQL connection; no current Game Session owner is
    // constructed against V1038, and no transport or Redis authority is fabricated.
    new HistoricalRetainedAbortMigration().verify(accountFixtures.newFixture(null).dataSource);
  }

  @Test
  void actualAcknowledgedAbortCleanupSurvivesLaterAccountIntentAndRetiresExactIssuerMember()
      throws Exception {
    try (var session = open(true)) {
      var acknowledgement = session.gs.repairAccountMember();
      assertThat(acknowledgement.transitionId()).isEqualTo(session.gs.transitionId());
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      var originalAck = session.gs.memberAcknowledgementImage();
      var originalPhase = session.gs.accountObligationPhaseImage();

      var abort = session.abort();
      assertThat(abort.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      assertThat(session.gs.source().isAccountAborted()).isTrue();
      assertThat(session.gs.source().candidate().accountIndexState())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED);

      String newerMemberSeed = "later-account-newer-issuer-member";
      session.gs.prepareOtherControllerNew(newerMemberSeed);
      assertThat(session.gs.projectOtherControllerNew(newerMemberSeed).scriptOutcome())
          .isEqualTo(1L);
      session.gs.publishOtherControllerNew(newerMemberSeed);
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();

      String staleTargetAddSeed = "later-account-stale-target-add";
      session.gs.prepareOtherControllerNew(staleTargetAddSeed);
      byte[] fieldsBeforeStaleAdd = session.gs.issuerPartitionFieldSetBytes();
      session.gs.beginAccountAcquisitionIntent("later-account-acquisition");
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      session.gs.candidate().accountId())
                  .get(0, String.class))
          .isEqualTo("ACQUISITION_INTENT");

      assertThatThrownBy(() -> session.gs.projectOtherControllerNew(staleTargetAddSeed))
          .isInstanceOf(IllegalStateException.class);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(fieldsBeforeStaleAdd);
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();
      assertThat(session.gs.hasExactAccountMember()).isTrue();

      var retainedRowsBeforeRetry = session.gs.retainedFirstNewAbortRowsImage();
      var issuerFieldsBeforeRetry = session.gs.issuerPartitionFieldSetBytes();
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();

      // Retained abort replay still authenticates through the actual Account owner after the
      // later GS Account intent; it must preserve the original source and cleanup identity.
      var imageBeforeAbortRetry = session.image();
      session.restartAccount();
      var abortRetry = session.abort();
      assertThat(abortRetry.state())
          .isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      assertThat(abortRetry.orphanCleanupId()).isEqualTo(abort.orphanCleanupId());
      assertThat(abortRetry.accountOperation().originalLeaseEvidence().canonicalJson())
          .isEqualTo(session.lease.canonicalJson());
      assertThat(session.image()).isEqualTo(imageBeforeAbortRetry);
      assertThat(session.gs.retainedFirstNewAbortRowsImage()).isEqualTo(retainedRowsBeforeRetry);
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
      assertThat(session.gs.issuerPartitionFieldSetBytes())
          .containsExactly(issuerFieldsBeforeRetry);

      var pendingRelease = session.gs.prepareIssuerRelease();
      var releaseReadback = session.gs.projectIssuerRelease(pendingRelease);
      var released = session.gs.completeIssuerRelease(pendingRelease, releaseReadback);
      assertThat(released.status())
          .isEqualTo(CanonicalGameplayIssuerBindingLocalReleaseOperation.Status.RELEASED);
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();

      var releaseRetry = session.gs.prepareIssuerRelease();
      var releaseRetryReadback = session.gs.projectIssuerRelease(releaseRetry);
      assertThat(releaseRetryReadback.scriptOutcome()).isEqualTo(2L);
      assertThat(
              session
                  .gs
                  .completeIssuerRelease(releaseRetry, releaseRetryReadback)
                  .completionInventoryRevision())
          .isEqualTo(released.completionInventoryRevision());

      var pendingRetirement = session.gs.prepareIssuerRetirement(session.gs.transitionId());
      assertThat(session.gs.retireIssuerCapacityOutcome(pendingRetirement)).isEqualTo(1L);
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();
      byte[] fieldsBeforeRetirementRetry = session.gs.issuerPartitionFieldSetBytes();
      var retirementRetry = session.gs.prepareIssuerRetirement(session.gs.transitionId());
      assertThat(session.gs.isIssuerRetirementComplete(retirementRetry)).isTrue();
      assertThat(session.gs.retireIssuerCapacityOutcome(retirementRetry)).isEqualTo(2L);
      assertThat(session.gs.issuerPartitionFieldSetBytes())
          .containsExactly(fieldsBeforeRetirementRetry);
      assertThat(session.gs.hasExactOtherControllerMember(newerMemberSeed)).isTrue();
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      assertThat(session.gs.memberAcknowledgementImage()).isEqualTo(originalAck);
      assertThat(session.gs.accountObligationPhaseImage()).isEqualTo(originalPhase);
    }
  }

  @Test
  void provisionalAbortStillRefusesLaterAccountIntentWithoutChangingOriginalRows()
      throws Exception {
    try (var session = open(true)) {
      session.gs.repairAccountMember();
      session.gs.beginAccountAcquisitionIntent("provisional-abort-later-account-acquisition");
      var originalRows = session.gs.retainedFirstNewAbortRowsImage();
      var originalImage = session.image();
      var originalFields = session.gs.issuerPartitionFieldSetBytes();

      assertThatThrownBy(session::abort).isInstanceOf(RuntimeException.class);

      assertThat(session.gs.retainedFirstNewAbortRowsImage()).isEqualTo(originalRows);
      assertThat(session.image()).isEqualTo(originalImage);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(originalFields);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.PENDING);
    }
  }

  /**
   * Controlled GS repository input exercises the initial deferred SQL guard, not Account's
   * terminal-state operation.
   */
  @Test
  void initialAbortDeferredGuardStillRejectsControlAdvanceBeforeCommit() throws Exception {
    try (var session = open(true)) {
      session.gs.repairAccountMember();
      var originalRows = session.gs.retainedFirstNewAbortRowsImage();
      var originalFields = session.gs.issuerPartitionFieldSetBytes();
      assertThatThrownBy(session.gs::recordOriginalAccountAbortThenAdvanceControlBeforeCommit)
          .isInstanceOf(RuntimeException.class)
          .rootCause()
          .isInstanceOf(org.postgresql.util.PSQLException.class)
          .hasMessageContaining("First-NEW Account abort requires its exact retained candidate");
      assertThat(session.gs.source().isAccountAborted()).isFalse();
      assertThat(session.gs.retainedFirstNewAbortRowsImage()).isEqualTo(originalRows);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(originalFields);
      assertThat(session.gs.hasExactAccountMember()).isTrue();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT status FROM game_session_canonical_binding_transition"
                          + " WHERE transition_id = ?",
                      session.gs.transitionId())
                  .get(0, String.class))
          .isEqualTo("PROVISIONAL");
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetchSingle(
                      "SELECT state FROM game_session_canonical_account_coverage_control"
                          + " WHERE account_id = ?",
                      session.gs.candidate().accountId())
                  .get(0, String.class))
          .isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
    }
  }

  private Session open() throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(false);
  }

  private Session open(boolean prepareMemberRepair) throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session(prepareMemberRepair);
  }

  private final class Session implements AutoCloseable {
    private final PhysicalRedisFixture redis = PhysicalRedisFixture.start();
    private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture
        accountFixture;
    private final CanonicalGameplayAdmissionAbortCompositionFixture gs;
    private final LoopbackMtlsTestPki.Pki pki;
    private final Path pkiDirectory;
    private final String jwt;
    private final CanonicalGameplayAdmissionDecisionReadClient reverse;
    private final AccountGameplayAdmissionCallerVerifier verifier;
    private Server gsServer;
    private Server accountServer;
    private AccountGameplayAdmissionLeaseClient client;
    private AccountGameplayAdmissionLeaseEvidence lease;

    private Session(boolean prepareMemberRepair) throws Exception {
      try {
        accountFixture = accountFixtures.newFixture(redis.client());
        var issuance = accountFixture.committedCandidate.identity();
        var credential =
            accountFixture.responseRecoveryOwner.recoverInitialLoginResponse(
                issuance.requestId(),
                issuance.accountId(),
                new CallerIdentity(issuance.callerWorkload(), issuance.callerContextId()));
        jwt = new String(credential.compactJwtBytes(), StandardCharsets.US_ASCII);
        Candidate candidate =
            AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.admissionCandidate(
                accountFixture);
        Candidate capacityCandidate =
            new Candidate(
                candidate.accountId(),
                candidate.tenantId(),
                candidate.playableStateNamespaceId(),
                candidate.characterId(),
                candidate.playableStateScope(),
                candidate.gameInstanceId(),
                candidate.runtimeGameInstanceId(),
                candidate.sessionId(),
                candidate.regionId(),
                candidate.regionEpoch(),
                candidate.issuerId(),
                candidate.issuerAuthGeneration(),
                candidate.issuerIndexLayoutVersion(),
                candidate.issuerIndexPartitionCount(),
                java.math.BigInteger.valueOf(prepareMemberRepair ? 3 : 2));
        gs =
            new CanonicalGameplayAdmissionAbortCompositionFixture(
                accountFixture.dataSource, capacityCandidate, redis.issuerOwnerClient(), 0);
        if (prepareMemberRepair) gs.prepareMemberRepairRedis();
        pkiDirectory = Files.createTempDirectory(temporaryDirectory, "actual-abort-mtls-");
        pki =
            LoopbackMtlsTestPki.create(
                pkiDirectory,
                "Account GS abort test CA",
                new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, true),
                new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
                new LoopbackMtlsTestPki.IdentitySpec("gs-client", GS, false),
                new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "wrong", "spiffe://firemud/ns/test/sa/world-management-service", false));
        gsServer =
            CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredDecisionReadServer(
                "127.0.0.1:0",
                pki.keyManagerFactory(pki.identity("gs")),
                pki.trustManagerFactory(),
                gs.dsl(),
                "test");
        gsServer.start();
        var endpoints = new ServiceEndpointsProperties();
        endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
        reverse =
            spy(
                new CanonicalGameplayAdmissionDecisionReadClient(
                    endpoints,
                    pki.clientProperties(pkiDirectory, pki.identity("account-client")),
                    new GrpcChannelFactory(),
                    "test"));
        // Only published-target authority is controlled. read(decisionId, originalLease) uses the
        // actual mTLS receiver/repository; it is never stubbed or replaced by DTO equality.
        reverse.init();
        UUID requestId = UUID.randomUUID();
        doReturn(
                AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest
                    .controlledPublishedTarget(gs.candidate(), gs.target(), requestId))
            .when(reverse)
            .readPublishedRealmAdmissionOwner(any());
        verifier =
            new AccountGameplayAdmissionCallerVerifier(
                accountFixture.signerFixture.trustedJwksSource(),
                accountFixture.committedIssuanceOwner,
                Clock.systemUTC());
        restartAccount();
        lease =
            client
                .acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation())
                .originalLeaseEvidence();
        gs.publish(lease);
      } catch (Exception | Error setupFailure) {
        try {
          close();
        } catch (Exception cleanupFailure) {
          setupFailure.addSuppressed(cleanupFailure);
        }
        throw setupFailure;
      }
    }

    private void restartAccount() throws Exception {
      if (client != null) client.close();
      if (accountServer != null) {
        accountServer.shutdownNow();
        if (!accountServer.awaitTermination(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Account server teardown timed out");
      }
      var owner =
          new AccountGameplayAdmissionLeaseOwner(
              verifier, reverse, "test", accountFixture.dataSource);
      accountServer =
          AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer(
              "127.0.0.1:0",
              pki.keyManagerFactory(pki.identity("account")),
              pki.trustManagerFactory(),
              owner,
              "test");
      accountServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
      client =
          new AccountGameplayAdmissionLeaseClient(
              endpoints,
              pki.clientProperties(pkiDirectory, pki.identity("gs-client")),
              new GrpcChannelFactory(),
              "test");
      client.init();
    }

    private String image() {
      return java.util.List.of(
              "game_session_canonical_binding_transition",
              "game_session_canonical_gameplay_binding_inventory",
              "game_session_canonical_issuer_partition_reservation",
              "game_session_canonical_binding_issuer_index_obligation",
              "game_session_canonical_issuer_binding_local_operation",
              "game_session_canonical_issuer_binding_local_release_operation")
          .stream()
          .map(
              table ->
                  gs.dsl()
                      .fetch("SELECT to_jsonb(t)::text AS image FROM " + table + " t ORDER BY 1")
                      .getValues("image")
                      .toString())
          .collect(java.util.stream.Collectors.joining("\n"));
    }

    private byte[] leaseBytes() {
      return lease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private CanonicalGameplayAdmissionAbortOutcome abort() {
      return abortWith(jwt);
    }

    private CanonicalGameplayAdmissionAbortOutcome abortWith(String callerToken) {
      return CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredOwner(gs.dsl(), client)
          .abortFirstNew(gs.transitionId(), callerToken);
    }

    @Override
    public void close() throws Exception {
      try {
        if (client != null) client.close();
        if (reverse != null) reverse.close();
      } finally {
        try {
          if (accountServer != null) accountServer.shutdownNow();
          if (gsServer != null) gsServer.shutdownNow();
          if (accountServer != null)
            assertThat(accountServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          if (gsServer != null) assertThat(gsServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
          try {
            if (gs != null) gs.close();
          } finally {
            redis.close();
          }
        }
      }
    }
  }

  /**
   * Controlled V1038 predecessor storage with installed guards and actual filesystem migrations.
   * Lease, terminal Account outcome and member readback are stipulated historical SQL inputs; this
   * never invokes current owners or supplies fresh admission, issuer control or grants.
   */
  private static final class HistoricalRetainedAbortMigration {
    private static final String PREFIX = "game_session_canonical_";
    private static final List<String> RETAINED_TABLES =
        List.of(
            "binding_transition",
            "gameplay_binding_inventory",
            "issuer_partition_reservation",
            "binding_account_index_obligation",
            "binding_issuer_index_obligation",
            "binding_region_bridge_obligation",
            "account_member_repair_operation");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NAMESPACE = UUID.randomUUID();
    private static final UUID CHARACTER = UUID.randomUUID();
    private static final UUID TRANSITION = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();
    private static final UUID ACCOUNT_FENCE = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID REGION = UUID.randomUUID();
    private static final byte[] BINDING_REF = new byte[] {1, 2, 3};
    private static final String MEMBER = "accountIndexMember/v1:AQID:1:" + ACCOUNT_FENCE;
    private static final String RAW_DIGEST = "a".repeat(64);
    private static final String DIGEST = "sha256:" + RAW_DIGEST;

    void verify(DriverManagerDataSource source) {
      String schema = "gs_retained_abort_" + UUID.randomUUID().toString().replace("-", "");
      var dataSource =
          new DriverManagerDataSource(
              java.util.Objects.requireNonNull(source.getUrl(), "Account fixture JDBC URL"),
              source.getUsername(),
              source.getPassword());
      dataSource.setSchema(schema);
      try {
        Flyway predecessor = flyway(dataSource, schema, "1038");
        predecessor.migrate();
        predecessor.validate();
        DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
        seedControlledAcknowledgedAbort(dsl);
        // Validate the complete terminal predecessor under its original NO_ACTIVE control. All
        // installed immediate and deferred guards remain enabled; no current prepare owner is used.
        dsl.execute(
            "UPDATE " + PREFIX + "binding_transition SET inventory_revision = inventory_revision");
        seedLaterControlledAcquisitionIntent(dsl);

        Map<String, List<String>> originalRows = rows(dsl, false);
        originalRows.values().forEach(images -> assertThat(images).hasSize(1));
        Map<String, List<String>> immutableRows = rows(dsl, true);
        assertThat(
                dsl.fetchSingle("SELECT state FROM " + PREFIX + "account_coverage_control")
                    .get(0, String.class))
            .isEqualTo("ACQUISITION_INTENT");
        List<String> historyBefore = history(dsl);
        assertThat(historyBefore)
            .anyMatch(row -> row.contains("V1000__create_saga_schema.sql"))
            .anyMatch(row -> row.contains("V1001__saga_instance_table.sql"))
            .anyMatch(row -> row.contains("V1002__saga_step_table.sql"));
        assertThat(
                dsl.fetchSingle(
                        "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                    .get(0, String.class))
            .isEqualTo("1038");

        // This is the V1038 defect: a retained abort's exact original ACK must not require the
        // account to remain forever at its old NO_ACTIVE epoch. The failed transaction rolls back
        // every affected row, including the singleton inventory clock.
        assertThatThrownBy(() -> advanceRetainedRevision(dsl))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .rootCause()
            .isInstanceOf(org.postgresql.util.PSQLException.class)
            .hasMessageContaining("First-NEW Account abort requires its exact retained candidate");
        assertThat(rows(dsl, false)).isEqualTo(originalRows);
        assertThat(
                dsl.fetchSingle(
                        "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                    .get(0, Integer.class))
            .isEqualTo(5);

        Flyway upgraded = flyway(dataSource, schema, "1038.1");
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        List<String> historyAfter = history(dsl);
        assertThat(historyAfter).hasSize(historyBefore.size() + 1);
        assertThat(historyAfter.subList(0, historyBefore.size()))
            .containsExactlyElementsOf(historyBefore);
        assertThat(historyAfter.get(historyAfter.size() - 1))
            .contains("1038.1", "V1038.1__retained_first_new_abort_cleanup_account_control.sql");
        assertThat(rows(dsl, false)).isEqualTo(originalRows);
        assertThat(
                dsl.fetchSingle(
                        "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                    .get(0, Integer.class))
            .isEqualTo(5);

        advanceRetainedRevision(dsl);
        assertThat(rows(dsl, true)).isEqualTo(immutableRows);
        for (String table : RETAINED_TABLES.subList(0, 6)) {
          assertThat(
                  dsl.fetchSingle("SELECT inventory_revision FROM " + PREFIX + table)
                      .get(0, Integer.class))
              .isEqualTo(6);
        }
        assertThat(
                dsl.fetchSingle(
                        "SELECT inventory_revision FROM " + PREFIX + "binding_inventory_clock")
                    .get(0, Integer.class))
            .isEqualTo(6);
        assertThat(history(dsl)).containsExactlyElementsOf(historyAfter);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        assertThat(history(dsl)).containsExactlyElementsOf(historyAfter);
      } finally {
        DSL.using(dataSource, SQLDialect.POSTGRES)
            .execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    }

    private static void seedControlledAcknowledgedAbort(DSLContext dsl) {
      dsl.transaction(
          configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("UPDATE " + PREFIX + "binding_inventory_clock SET inventory_revision = 5");
            insert(
                tx,
                "account_coverage_control",
                Map.of(
                    "account_id",
                    ACCOUNT,
                    "state",
                    "NO_ACTIVE_ACCOUNT_WIDE_FLOW",
                    "last_operation_fence",
                    0));
            insert(
                tx,
                "account_member_repair_control",
                Map.of(
                    "account_id", ACCOUNT,
                    "last_operation_fence", 1,
                    "last_coverage_generation", 3));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("binding_ref", BINDING_REF);
            candidate.put("account_id", ACCOUNT);
            candidate.put("tenant_id", TENANT);
            candidate.put("playable_state_namespace_id", NAMESPACE);
            candidate.put("character_id", CHARACTER);
            candidate.put("playable_state_scope", "SHARED");
            candidate.put("game_instance_id", INSTANCE);
            candidate.put("runtime_game_instance_id", 1L);
            candidate.put("session_id", UUID.randomUUID().toString());
            candidate.put("binding_generation", 1);
            candidate.put("region_id", REGION);
            candidate.put("region_epoch", 1);
            candidate.put("issuer_id", "firemud-account-service");
            candidate.put("issuer_auth_generation", 1);
            candidate.put("issuer_index_layout_version", 1);
            candidate.put("issuer_index_partition_count", 1);
            candidate.put("issuer_index_partition_capacity", 3);
            candidate.put("issuer_partition_id", 0);
            candidate.put("account_index_fence", ACCOUNT_FENCE);
            candidate.put("issuer_reservation_id", RESERVATION);
            candidate.put("transition_id", TRANSITION);
            candidate.put("lifecycle", "TERMINAL_UNRESOLVED");
            candidate.put("account_index_state", "ACKNOWLEDGED");
            candidate.put("issuer_index_state", "REPAIR_REQUIRED");
            candidate.put("inventory_revision", 5);
            insert(tx, "gameplay_binding_inventory", candidate);

            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("transition_id", TRANSITION);
            transition.put("tenant_id", TENANT);
            transition.put("playable_state_namespace_id", NAMESPACE);
            transition.put("character_id", CHARACTER);
            transition.put("candidate_binding_ref", BINDING_REF);
            transition.put("candidate_binding_generation", 1);
            transition.put("candidate_account_index_fence", ACCOUNT_FENCE);
            transition.put("issuer_reservation_id", RESERVATION);
            transition.put("status", "ABORTED");
            transition.put("inventory_revision", 5);
            transition.put("admission_request_id", UUID.randomUUID());
            transition.put("admission_lease_id", UUID.randomUUID());
            transition.put("admission_lease_fence", 1);
            transition.put("admission_lease_kind", "NEW_BINDING");
            transition.put("admission_lease_digest", RAW_DIGEST);
            transition.put(
                "admission_lease_evidence", "{\"controlledHistoricalAccountAbort\":true}");
            transition.put("admission_lease_expires_at", 1);
            transition.put("admission_orphan_cleanup_id", UUID.randomUUID());
            transition.put("admission_target_namespace", "test");
            transition.put("admission_target_tenant_slug", "historical-tenant");
            transition.put("admission_target_tenant_id", TENANT);
            transition.put("admission_target_world_slug", "historical-world");
            transition.put("admission_target_world_display_name", "Historical world");
            transition.put("admission_target_realm_id", UUID.randomUUID());
            transition.put("admission_target_realm_slug", "historical-realm");
            transition.put("admission_target_realm_display_name", "Historical realm");
            transition.put("admission_target_game_session_tenant_id", 1L);
            transition.put("admission_target_game_instance_id", 1L);
            transition.put("admission_target_playable_state_namespace_id", NAMESPACE);
            transition.put("admission_target_playable_state_scope", "SHARED");
            transition.put("admission_target_canonical_game_instance_id", INSTANCE);
            transition.put("admission_target_canonical_version_id", UUID.randomUUID());
            transition.put("admission_target_runtime_version_id", 1L);
            transition.put("admission_target_catalog_revision", 1L);
            transition.put("admission_target_pointer_version", 1L);
            transition.put("admission_target_pointer_snapshot_digest", RAW_DIGEST);
            transition.put("admission_target_active_world_epoch", 1L);
            transition.put(
                "admission_target_initial_admission_request_id", "controlled-historical-request");
            transition.put("admission_target_initial_admission_request_digest", RAW_DIGEST);
            transition.put("admission_target_origin_kind", "NO_PRIOR_POINTER");
            transition.put("admission_target_hold_id", UUID.randomUUID());
            transition.put("admission_target_hold_fence", UUID.randomUUID());
            transition.put("admission_target_hold_binding_digest", DIGEST);
            transition.put("admission_target_audit_event_id", 1L);
            transition.put("admission_target_owner_proof_digest", DIGEST);
            transition.put("admission_target_owner_proof_outcome", "COMMITTED");
            transition.put("admission_target_positive_durable_abort", false);
            transition.put(
                "admission_target_terminal_at", OffsetDateTime.parse("2026-10-26T00:00:00Z"));
            transition.put("admission_target_character_creation_policy", "{}");
            insert(tx, "binding_transition", transition);

            Map<String, Object> reservation = new LinkedHashMap<>(candidate);
            reservation.remove("issuer_partition_id");
            reservation.remove("account_index_fence");
            reservation.remove("issuer_reservation_id");
            reservation.remove("account_index_state");
            reservation.remove("issuer_index_state");
            reservation.put("reservation_id", RESERVATION);
            reservation.put("partition_id", 0);
            reservation.put("lifecycle", "RELEASE_PENDING");
            reservation.put("reservation_fence", UUID.randomUUID());
            reservation.put("issuer_coverage_operation_id", UUID.randomUUID());
            reservation.put("issuer_coverage_operation_fence", 1);
            reservation.put("coverage_fence", 1);
            reservation.put("inventory_snapshot_revision", 1);
            insert(tx, "issuer_partition_reservation", reservation);

            Map<String, Object> accountObligation = new LinkedHashMap<>();
            accountObligation.put("transition_id", TRANSITION);
            accountObligation.put("obligation_ordinal", 0);
            accountObligation.put("binding_ref", BINDING_REF);
            accountObligation.put("account_id", ACCOUNT);
            accountObligation.put("tenant_id", TENANT);
            accountObligation.put("action", "ADD_OR_RETAIN");
            accountObligation.put("binding_generation", 1);
            accountObligation.put("account_index_fence", ACCOUNT_FENCE);
            accountObligation.put("execution_phase", "BEFORE_FINAL_CAS");
            accountObligation.put("status", "ACKNOWLEDGED");
            accountObligation.put("inventory_revision", 5);
            accountObligation.put("projection_state", "PRESENT_AWAITING_COVERAGE");
            accountObligation.put("projection_member", MEMBER);
            accountObligation.put("projection_inventory_revision", 4);
            insert(tx, "binding_account_index_obligation", accountObligation);
            insert(
                tx,
                "binding_issuer_index_obligation",
                Map.of(
                    "transition_id",
                    TRANSITION,
                    "binding_ref",
                    BINDING_REF,
                    "binding_generation",
                    1,
                    "reservation_id",
                    RESERVATION,
                    "status",
                    "REQUIRED",
                    "inventory_revision",
                    5));
            Map<String, Object> region = new LinkedHashMap<>();
            for (String key :
                List.of(
                    "transition_id",
                    "binding_ref",
                    "binding_generation",
                    "account_id",
                    "tenant_id",
                    "game_instance_id",
                    "runtime_game_instance_id",
                    "session_id",
                    "region_id",
                    "region_epoch",
                    "inventory_revision")) {
              region.put(key, candidate.get(key));
            }
            region.put("status", "REQUIRED");
            insert(tx, "binding_region_bridge_obligation", region);

            Map<String, Object> repair = new LinkedHashMap<>();
            repair.put("operation_id", UUID.randomUUID());
            repair.put("transition_id", TRANSITION);
            repair.put("obligation_ordinal", 0);
            repair.put("operation_fence", 1);
            repair.put("coverage_fence", UUID.randomUUID());
            repair.put("inventory_snapshot_revision", 2);
            repair.put("binding_ref", BINDING_REF);
            repair.put("account_id", ACCOUNT);
            repair.put("tenant_id", TENANT);
            repair.put("binding_generation", 1);
            repair.put("account_index_fence", ACCOUNT_FENCE);
            repair.put("original_action", "ADD_OR_RETAIN");
            repair.put("original_execution_phase", "BEFORE_FINAL_CAS");
            repair.put("original_status", "REQUIRED");
            repair.put("original_inventory_revision", 1);
            repair.put("original_projection_state", "REQUIRED");
            repair.put("desired_action", "ADD_OR_RETAIN");
            repair.put("member", MEMBER);
            repair.put("account_control_state", "NO_ACTIVE_ACCOUNT_WIDE_FLOW");
            repair.put("account_control_epoch", 0);
            repair.put("status", "ACKNOWLEDGED");
            repair.put("coverage_generation", 3);
            repair.put("readback_state", "PRESENT");
            repair.put("readback_member", MEMBER);
            repair.put("completed_inventory_revision", 4);
            insert(tx, "account_member_repair_operation", repair);
          });
    }

    private static void seedLaterControlledAcquisitionIntent(DSLContext dsl) {
      UUID intent = UUID.randomUUID();
      UUID fence = UUID.randomUUID();
      Map<String, Object> operation = new LinkedHashMap<>();
      operation.put("operation_id", intent);
      operation.put("request_id", UUID.randomUUID());
      operation.put("account_id", ACCOUNT);
      operation.put("operation_fence", 1);
      operation.put("planned_account_admission_fence", fence);
      operation.put("inventory_snapshot_revision", 5);
      operation.put("legacy_cohort_id", UUID.randomUUID());
      operation.put("legacy_owner_operation_id", UUID.randomUUID());
      operation.put("legacy_writer_fence", UUID.randomUUID());
      operation.put("legacy_source_snapshot_revision", 1);
      operation.put("legacy_canonical_inventory_revision", 1);
      operation.put("legacy_namespace_index_readback_revision", 1);
      operation.put("legacy_evidence_digest", DIGEST);
      operation.put("intent_state", "ACQUISITION_INTENT");
      operation.put("cohort_row_count", 0L);
      operation.put("cohort_digest", DIGEST);
      operation.put("barrier_target_count", 0L);
      operation.put("barrier_target_digest", DIGEST);
      insert(dsl, "account_admission_fence_intent", operation);
      dsl.execute(
          "UPDATE "
              + PREFIX
              + "account_coverage_control SET state = 'ACQUISITION_INTENT',"
              + " last_operation_fence = 1, current_operation_fence = 1,"
              + " current_fence_intent_id = ?, account_admission_fence = ?, inventory_snapshot_revision = 5"
              + " WHERE account_id = ?",
          intent,
          fence,
          ACCOUNT);
    }

    private static void advanceRetainedRevision(DSLContext dsl) {
      dsl.transaction(
          configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute("UPDATE " + PREFIX + "binding_inventory_clock SET inventory_revision = 6");
            for (String table : RETAINED_TABLES.subList(0, 6)) {
              tx.execute("UPDATE " + PREFIX + table + " SET inventory_revision = 6");
            }
          });
    }

    private static Map<String, List<String>> rows(DSLContext dsl, boolean immutableOnly) {
      Map<String, List<String>> rows = new LinkedHashMap<>();
      for (String table : RETAINED_TABLES) {
        String expression = "to_jsonb(retained)";
        if (immutableOnly && !table.equals("account_member_repair_operation")) {
          expression += " - 'inventory_revision'";
        }
        rows.put(
            table,
            dsl.fetch(
                    "SELECT ("
                        + expression
                        + ")::text AS image FROM "
                        + PREFIX
                        + table
                        + " retained ORDER BY 1")
                .getValues("image", String.class));
      }
      for (String table :
          List.of(
              "account_coverage_control",
              "account_admission_fence_intent",
              "account_member_repair_control")) {
        rows.put(
            table,
            dsl.fetch(
                    "SELECT to_jsonb(retained)::text AS image FROM "
                        + PREFIX
                        + table
                        + " retained ORDER BY 1")
                .getValues("image", String.class));
      }
      return rows;
    }

    private static void insert(DSLContext dsl, String table, Map<String, Object> values) {
      Map<Field<?>, Object> fields = new LinkedHashMap<>();
      values.forEach((column, value) -> fields.put(DSL.field(DSL.name(column)), value));
      dsl.insertInto(DSL.table(DSL.name(PREFIX + table))).set(fields).execute();
    }

    private static List<String> history(DSLContext dsl) {
      return dsl.fetch(
              "SELECT to_jsonb(history_row)::text AS image FROM flyway_schema_history history_row"
                  + " ORDER BY installed_rank")
          .getValues("image", String.class);
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String schema, String target) {
      return Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .placeholders(Map.of("serviceSchema", schema))
          .locations(
              "filesystem:"
                  + Path.of("../game-session-service/src/main/resources/db/migration")
                      .toAbsolutePath()
                      .normalize(),
              "filesystem:"
                  + Path.of("../common-saga/src/main/resources/db/migration/saga")
                      .toAbsolutePath()
                      .normalize())
          .target(MigrationVersion.fromVersion(target))
          .load();
    }
  }
}
