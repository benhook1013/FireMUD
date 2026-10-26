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
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortOutcome;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortCompositionFixture;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayAdmissionAbortManualFactory;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

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

  @BeforeAll static void startPostgres() { AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.startPostgres(); }
  @AfterAll static void stopPostgres() { AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.stopPostgres(); }
  @AfterEach void cleanupAccountSchemas() { accountFixtures.dropRunOwnedSchemas(); }

  @Test
  void actualAbortExactRetryAndRestartRetainOriginalLeaseAndRejectTerminalRewrites() throws Exception {
    try (var session = open()) {
      var original = session.gs.source();
      var result = session.abort();
      assertThat(result.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      var source = session.gs.source();
      var retainedImage = session.image();
      assertThat(source.isAccountAborted()).isTrue();
      assertThat(source.orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(source.originalDecision().leaseEvidenceJson()).isEqualTo(original.originalDecision().leaseEvidenceJson());
      assertThat(source.originalDecision().leaseSha256()).isEqualTo(original.originalDecision().leaseSha256());
      assertThat(source.candidate().lifecycle()).isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      assertThat(source.reservation().lifecycle()).isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);
      // New owner instance and independent repository read recover the same retained source.
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state()).isEqualTo(State.ABORTED);
      assertThat(session.client.finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId()).state()).isEqualTo(State.ABORTED);
      assertThatThrownBy(() -> session.abortWith("invalid-current-caller-token")).isInstanceOf(RuntimeException.class);
      assertThat(session.image()).isEqualTo(retainedImage);

      for (String assignment : new String[] {
          "admission_orphan_cleanup_id = '34343434-3434-4434-8434-343434343434'",
          "status = 'PROVISIONAL', admission_orphan_cleanup_id = NULL",
          "admission_lease_id = '45454545-4545-4545-8545-454545454545'",
          "admission_lease_digest = '" + "e".repeat(64) + "'",
          "admission_lease_evidence = '{}'",
          "inventory_revision = inventory_revision - 1" }) {
        assertThatThrownBy(() -> session.gs.dsl().execute(
            "UPDATE game_session_canonical_binding_transition SET " + assignment + " WHERE transition_id = ?",
            session.gs.transitionId())).isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(session.image()).isEqualTo(retainedImage);
      }
      assertThatThrownBy(() -> session.gs.dsl().execute(
          "UPDATE game_session_canonical_issuer_partition_reservation SET lifecycle = 'RELEASED' WHERE transition_id = ?",
          session.gs.transitionId())).isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(retainedImage);
      // Cleanup is intentionally unresolved. A newer first-NEW candidate must remain fenced;
      // this boundary does not fabricate an admitted/newer binding to claim cleanup safety.
      assertThatThrownBy(session.gs::prepareNewerCandidate).isInstanceOf(RuntimeException.class);
      assertThat(session.abort().orphanCleanupId()).isEqualTo(result.orphanCleanupId());
      assertThat(session.image()).isEqualTo(retainedImage);
    }
  }

  @Test
  void actualAccountAbortWithLostReplyKeepsGsProvisionalThenRecoversSameOriginalAfterRestart() throws Exception {
    try (var session = open()) {
      var before = session.image();
      var lostReplyClient = mock(AccountGameplayAdmissionLeaseClient.class);
      doAnswer(invocation -> {
        session.client.abort(session.jwt, session.leaseBytes(), session.gs.transitionId());
        throw new IllegalStateException("controlled loss after actual Account ABORTED reply");
      }).when(lostReplyClient).abort(any(), any(), any());
      var lostReplyOwner = CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredOwner(session.gs.dsl(), lostReplyClient);
      assertThatThrownBy(() -> lostReplyOwner.abortFirstNew(session.gs.transitionId(), session.jwt))
          .hasMessage("controlled loss after actual Account ABORTED reply");
      assertThat(session.image()).isEqualTo(before);
      var accountAbort = session.client.read(session.jwt, session.leaseBytes());
      assertThat(accountAbort.state()).isEqualTo(State.ABORTED);
      session.restartAccount();
      assertThat(session.abort().orphanCleanupId()).isEqualTo(accountAbort.orphanCleanupId());
      assertThat(session.client.finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId()).state()).isEqualTo(State.ABORTED);
    }
  }

  @Test
  void unavailableActualReverseReadCannotInferAbortFromAbsenceOrExpiry() throws Exception {
    try (var session = open()) {
      var before = session.image();
      session.gsServer.shutdownNow();
      assertThat(session.gsServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(session::abort).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state()).isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
      long expiresAt = Long.parseLong((String) session.lease.carrier().get("expiresAt"));
      long remaining = expiresAt - System.currentTimeMillis();
      if (remaining >= 0) Thread.sleep(remaining + 5L);
      assertThatThrownBy(session::abort).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state()).isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
    }
  }

  @Test
  void actualConcurrentFinalizeAndAbortHaveOneAccountWinnerWithoutGsCommittedRelabelling() throws Exception {
    try (var session = open(); var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var finalize = executor.submit(() -> {
        start.await();
        try { return session.client.finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId()); }
        catch (RuntimeException ambiguous) { return null; }
      });
      var abort = executor.submit(() -> {
        start.await();
        try { return session.abort(); } catch (RuntimeException ambiguous) { return null; }
      });
      start.countDown();
      var finalized = finalize.get(20, TimeUnit.SECONDS);
      var aborted = abort.get(20, TimeUnit.SECONDS);
      // A missing ACK may make a committed read unavailable. The retained SQL operation still
      // decides one winner; no test manufactures a receipt or relabels that winner.
      var accountRow = session.accountFixture.dataSource;
      var retained = org.jooq.impl.DSL.using(accountRow, org.jooq.SQLDialect.POSTGRES)
          .fetchSingle("SELECT status FROM account_gameplay_admission_lease_operations WHERE lease_id = ?",
              UUID.fromString((String) session.lease.carrier().get("leaseId")));
      assertThat(retained.get("status", String.class)).isIn("ABORTED", "COMMITTED");
      if ("ABORTED".equals(retained.get("status", String.class))) {
        assertThat(session.abort().state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
        assertThat(session.client.finalizeLease(session.jwt, session.leaseBytes(), session.gs.transitionId()).state()).isEqualTo(State.ABORTED);
        if (finalized != null) assertThat(finalized.state()).isEqualTo(State.ABORTED);
      } else {
        assertThat(session.gs.source().isAccountAborted()).isFalse();
        if (aborted != null) assertThat(aborted.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ACCOUNT_COMMITTED);
      }
    }
  }

  @Test
  void actualAccountAbortedOrdinaryIssuerMemberReleaseHasExactReadbackRetryAndFreesCapacity() throws Exception {
    try (var session = open()) {
      UUID originalOperationId = session.gs.originalOperationId();
      byte[] originalMemberBytes = session.gs.originalMemberValueBytes();

      var abort = session.abort();
      assertThat(abort.state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
      var aborted = session.gs.source();
      assertThat(aborted.isAccountAborted()).isTrue();
      assertThat(aborted.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASE_PENDING);

      var pending = session.gs.prepareIssuerRelease();
      assertThat(pending.originalOperationId()).isEqualTo(originalOperationId);
      assertThat(pending.exactOriginalMemberValueBytes()).containsExactly(originalMemberBytes);
      assertThat(pending.sourceInventoryRevision()).isGreaterThan(aborted.transition().inventoryRevision());
      assertThat(session.gs.source().transition().inventoryRevision()).isEqualTo(pending.sourceInventoryRevision());

      UUID secondOperationId = session.gs.prepareOtherControllerNew("second-controller");
      assertThat(secondOperationId).isNotEqualTo(originalOperationId);
      var clockAfterOtherWriter = session.gs.dsl().fetchSingle(
          "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1")
          .get("inventory_revision", java.math.BigDecimal.class).toBigIntegerExact();
      assertThat(clockAfterOtherWriter).isGreaterThan(pending.sourceInventoryRevision());
      assertThat(session.gs.source().transition().inventoryRevision()).isEqualTo(pending.sourceInventoryRevision());
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.TWO);
      assertThatThrownBy(() -> session.gs.prepareOtherControllerNew("full-before-release"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("capacity");

      String preparedImage = session.image();
      assertThatThrownBy(() -> session.gs.dsl().execute(
          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
              + " SET original_member_value_bytes = ? WHERE operation_id = ?",
          new byte[] {1}, pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(() -> session.gs.dsl().execute(
          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
              + " SET original_reservation_fence = ? WHERE operation_id = ?",
          UUID.randomUUID(), pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(() -> session.gs.dsl().execute(
          "UPDATE game_session_canonical_issuer_binding_local_release_operation"
              + " SET anchor_operation_fence = anchor_operation_fence + 1 WHERE operation_id = ?",
          pending.operationId()))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(session.image()).isEqualTo(preparedImage);
      assertThatThrownBy(() -> session.gs.dsl().execute(
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
      assertThat(session.gs.readReleaseCapacityValue(pending)).containsExactly(wrongReservationValue);
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
      assertThat(resumed.exactReleasedMemberValueBytes()).containsExactly(pending.exactReleasedMemberValueBytes());
      var physicalReadback = session.gs.projectIssuerRelease(resumed);
      assertThat(physicalReadback.scriptOutcome()).isEqualTo(2L);
      assertThat(session.gs.readbackHasExactReleasedMember(resumed, physicalReadback)).isTrue();
      var clockBeforeCompletion = session.gs.dsl().fetchSingle(
          "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock WHERE singleton_id = 1")
          .get("inventory_revision", java.math.BigDecimal.class).toBigIntegerExact();
      assertThat(clockBeforeCompletion).isEqualTo(clockAfterOtherWriter);
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
      assertThat(released.releasedMemberValue().reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(released.releasedMemberValue().reservation().inventoryRevision())
          .isEqualTo(released.sourceInventoryRevision());
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.ONE);

      var source = session.gs.source();
      assertThat(source.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(source.candidate().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL_UNRESOLVED);
      var issuer = session.gs.dsl().fetchSingle(
          "SELECT status, local_release_operation_id, inventory_revision"
              + " FROM game_session_canonical_binding_issuer_index_obligation WHERE transition_id = ?",
          session.gs.transitionId());
      assertThat(issuer.get("status", String.class)).isEqualTo("RELEASED");
      assertThat(issuer.get("local_release_operation_id", UUID.class)).isEqualTo(released.operationId());
      assertThat(issuer.get("inventory_revision", java.math.BigDecimal.class))
          .isEqualTo(new java.math.BigDecimal(released.completionInventoryRevision()));
      assertThat(session.gs.dsl().fetchSingle(
          "SELECT status FROM game_session_canonical_binding_account_index_obligation"
              + " WHERE transition_id = ? AND obligation_ordinal = 0",
          session.gs.transitionId()).get("status", String.class)).isIn("REQUIRED", "ACKNOWLEDGED");
      assertThat(session.gs.dsl().fetchSingle(
          "SELECT status FROM game_session_canonical_binding_region_bridge_obligation WHERE transition_id = ?",
          session.gs.transitionId()).get("status", String.class)).isEqualTo("REQUIRED");

      UUID thirdOperationId = session.gs.prepareOtherControllerNew("freed-capacity-other-controller");
      assertThat(session.gs.preparedLocalCountedReservations()).isEqualTo(java.math.BigInteger.TWO);
      var thirdReadback = session.gs.projectOtherControllerNew("freed-capacity-other-controller");
      assertThat(thirdReadback.operationId()).isEqualTo(thirdOperationId);
      assertThat(thirdReadback.scriptOutcome()).isIn(1L, 2L, 3L);

      byte[] retainedReadback = released.exactPhysicalReadbackFieldSetBytes();
      var releaseReplay = session.gs.prepareIssuerRelease();
      assertThat(releaseReplay.operationId()).isEqualTo(released.operationId());
      assertThat(releaseReplay.exactReleasedMemberValueBytes()).containsExactly(released.exactReleasedMemberValueBytes());
      var replayReadback = session.gs.projectIssuerRelease(releaseReplay);
      assertThat(replayReadback.scriptOutcome()).isEqualTo(2L);
      var completedReplay = session.gs.completeIssuerRelease(releaseReplay, replayReadback);
      assertThat(completedReplay.completionInventoryRevision()).isEqualTo(released.completionInventoryRevision());
      assertThat(completedReplay.exactPhysicalReadbackFieldSetBytes()).containsExactly(retainedReadback);
      assertThat(session.abort().orphanCleanupId()).isEqualTo(abort.orphanCleanupId());
    }
  }

  @Test
  void actualReverseReadRefusesWrongPeerDecisionAndChangedOriginalTupleWithoutAccountAbort() throws Exception {
    try (var session = open()) {
      var before = session.image();
      assertThat(session.reverse.read(session.gs.transitionId(), session.lease).bindingDecisionId())
          .isEqualTo(session.gs.transitionId());
      assertThatThrownBy(() -> session.reverse.read(UUID.randomUUID(), session.lease))
          .isInstanceOf(RuntimeException.class);
      var changedCarrier = new java.util.LinkedHashMap<>(session.lease.carrier());
      changedCarrier.put("leaseFence", session.lease.leaseFence().add(java.math.BigInteger.ONE).toString());
      var changedLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changedCarrier);
      assertThatThrownBy(() -> session.reverse.read(session.gs.transitionId(), changedLease))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> session.client.abort(session.jwt,
          changedLease.canonicalJson().getBytes(StandardCharsets.UTF_8), session.gs.transitionId()))
          .isInstanceOf(RuntimeException.class);
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("127.0.0.1:" + session.gsServer.getPort());
      try (var wrongPeer = new CanonicalGameplayAdmissionDecisionReadClient(endpoints,
          session.pki.clientProperties(session.pkiDirectory, session.pki.identity("wrong")),
          new GrpcChannelFactory(), "test")) {
        wrongPeer.init();
        assertThatThrownBy(() -> wrongPeer.read(session.gs.transitionId(), session.lease))
            .isInstanceOf(RuntimeException.class);
      }
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state()).isEqualTo(State.PENDING);
      assertThat(session.image()).isEqualTo(before);
      // The runtime owner cannot retire the launch underneath this exact pending decision.
      assertThatThrownBy(session.gs::stopRuntime)
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("runtime row is fenced by an unresolved lease-bound binding decision");
      assertThat(session.image()).isEqualTo(before);
      assertThat(session.abort().state()).isEqualTo(CanonicalGameplayAdmissionAbortOutcome.State.ABORTED);
    }
  }

  private Session open() throws Exception {
    accountFixtures.temporaryDirectory = temporaryDirectory;
    return new Session();
  }

  private final class Session implements AutoCloseable {
    private final PhysicalRedisFixture redis = PhysicalRedisFixture.start();
    private final AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.Fixture accountFixture;
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

    private Session() throws Exception {
      try {
      accountFixture = accountFixtures.newFixture(redis.client());
      var issuance = accountFixture.committedCandidate.identity();
      var credential = accountFixture.responseRecoveryOwner.recoverInitialLoginResponse(
          issuance.requestId(), issuance.accountId(), new CallerIdentity(issuance.callerWorkload(), issuance.callerContextId()));
      jwt = new String(credential.compactJwtBytes(), StandardCharsets.US_ASCII);
      Candidate candidate =
          AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.admissionCandidate(accountFixture);
      Candidate capacityCandidate = new Candidate(
          candidate.accountId(), candidate.tenantId(), candidate.playableStateNamespaceId(),
          candidate.characterId(), candidate.playableStateScope(), candidate.gameInstanceId(),
          candidate.runtimeGameInstanceId(), candidate.sessionId(), candidate.regionId(),
          candidate.regionEpoch(), candidate.issuerId(), candidate.issuerAuthGeneration(),
          candidate.issuerIndexLayoutVersion(), candidate.issuerIndexPartitionCount(),
          java.math.BigInteger.valueOf(2));
      gs = new CanonicalGameplayAdmissionAbortCompositionFixture(
          accountFixture.dataSource, capacityCandidate, redis.issuerOwnerClient());
      pkiDirectory = Files.createTempDirectory(temporaryDirectory, "actual-abort-mtls-");
      pki = LoopbackMtlsTestPki.create(pkiDirectory, "Account GS abort test CA",
          new LoopbackMtlsTestPki.IdentitySpec("account", ACCOUNT, true),
          new LoopbackMtlsTestPki.IdentitySpec("gs", GS, true),
          new LoopbackMtlsTestPki.IdentitySpec("gs-client", GS, false),
          new LoopbackMtlsTestPki.IdentitySpec("account-client", ACCOUNT, false),
          new LoopbackMtlsTestPki.IdentitySpec("wrong", "spiffe://firemud/ns/test/sa/world-management-service", false));
      gsServer = CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredDecisionReadServer(
          "127.0.0.1:0", pki.keyManagerFactory(pki.identity("gs")), pki.trustManagerFactory(), gs.dsl(), "test");
      gsServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
      reverse = spy(new CanonicalGameplayAdmissionDecisionReadClient(endpoints,
          pki.clientProperties(pkiDirectory, pki.identity("account-client")), new GrpcChannelFactory(), "test"));
      // Only published-target authority is controlled. read(decisionId, originalLease) uses the
      // actual mTLS receiver/repository; it is never stubbed or replaced by DTO equality.
      reverse.init();
      UUID requestId = UUID.randomUUID();
      doReturn(AccountGameplayAdmissionCurrentSourcesPostgresIntegrationTest.controlledPublishedTarget(gs.candidate(), gs.target(), requestId))
          .when(reverse).readPublishedRealmAdmissionOwner(any());
      verifier = new AccountGameplayAdmissionCallerVerifier(accountFixture.signerFixture.trustedJwksSource(),
          accountFixture.committedIssuanceOwner, Clock.systemUTC());
      restartAccount();
      lease = client.acquire(requestId, jwt, gs.candidate(), gs.target(), gs.generation()).originalLeaseEvidence();
      gs.publish(lease);
      } catch (Exception | Error setupFailure) {
        try { close(); } catch (Exception cleanupFailure) { setupFailure.addSuppressed(cleanupFailure); }
        throw setupFailure;
      }
    }

    private void restartAccount() throws Exception {
      if (client != null) client.close();
      if (accountServer != null) {
        accountServer.shutdownNow();
        if (!accountServer.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Account server teardown timed out");
      }
      var owner = new AccountGameplayAdmissionLeaseOwner(verifier, reverse, "test", accountFixture.dataSource);
      accountServer = AccountGameplayAdmissionLeaseManualFactory.createUnregisteredServer("127.0.0.1:0",
          pki.keyManagerFactory(pki.identity("account")), pki.trustManagerFactory(), owner, "test");
      accountServer.start();
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService("127.0.0.1:" + accountServer.getPort());
      client = new AccountGameplayAdmissionLeaseClient(endpoints, pki.clientProperties(pkiDirectory, pki.identity("gs-client")), new GrpcChannelFactory(), "test");
      client.init();
    }
    private String image() {
      return java.util.List.of("game_session_canonical_binding_transition", "game_session_canonical_gameplay_binding_inventory",
          "game_session_canonical_issuer_partition_reservation",
          "game_session_canonical_binding_issuer_index_obligation",
          "game_session_canonical_issuer_binding_local_operation",
          "game_session_canonical_issuer_binding_local_release_operation").stream()
          .map(table -> gs.dsl().fetch("SELECT to_jsonb(t)::text AS image FROM " + table + " t ORDER BY 1").getValues("image").toString())
          .collect(java.util.stream.Collectors.joining("\n"));
    }
    private byte[] leaseBytes() { return lease.canonicalJson().getBytes(StandardCharsets.UTF_8); }
    private CanonicalGameplayAdmissionAbortOutcome abort() { return abortWith(jwt); }
    private CanonicalGameplayAdmissionAbortOutcome abortWith(String callerToken) {
      return CanonicalGameplayAdmissionAbortManualFactory.createUnregisteredOwner(gs.dsl(), client)
          .abortFirstNew(gs.transitionId(), callerToken);
    }
    @Override public void close() throws Exception {
      try {
        if (client != null) client.close();
        if (reverse != null) reverse.close();
      } finally {
        try {
          if (accountServer != null) accountServer.shutdownNow();
          if (gsServer != null) gsServer.shutdownNow();
          if (accountServer != null) assertThat(accountServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          if (gsServer != null) assertThat(gsServer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally { try { if (gs != null) gs.close(); } finally { redis.close(); } }
      }
    }
  }
}
