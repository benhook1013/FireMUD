package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.State;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Never-admitted Account abort and negative cleanup through the shared physical fixture. */
@Execution(ExecutionMode.SAME_THREAD)
class AccountGameSessionNeverAdmittedAbortCompositionIntegrationTest
    extends AccountGameSessionAbortCompositionFixture {
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture(true);

  AccountGameSessionNeverAdmittedAbortCompositionIntegrationTest() {
    super(POSTGRES);
  }

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void claimedBeforeCreateProcessLossSettlesThroughActualBidirectionalMtlsAndIssuerRetirement(
      boolean installedBootstrap) throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      assertThat(session.gs.originalIssuerCapacityAbsent()).isFalse();
      var frozenProofs =
          session
              .gs
              .dsl()
              .fetch(
                  "SELECT to_jsonb(proof)::text FROM game_session_canonical_issuer_bootstrap_partition_proof proof ORDER BY partition_id")
              .formatJSON();
      var claimed =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT status, original_new_candidate_state, original_new_candidate_readback_digest, admission_lease_id"
                      + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                  session.gs.transitionId());
      assertThat(claimed.get("status", String.class)).isEqualTo("PREPARED");
      assertThat(claimed.get("original_new_candidate_state", String.class))
          .isEqualTo("DISPATCH_POSSIBLE");
      assertThat(claimed.get("original_new_candidate_readback_digest")).isNull();
      assertThat(claimed.get("admission_lease_id")).isNull();
      var terminal = session.abortNeverAdmitted();
      assertThat(terminal.cleanupComplete()).isTrue();
      assertThat(terminal.transition().status().name()).isEqualTo("ABORTED");
      assertThat(terminal.inventory().lifecycle())
          .isEqualTo(CanonicalGameplayBindingInventoryEntry.Lifecycle.TERMINAL);
      assertThat(terminal.reservation().lifecycle())
          .isEqualTo(CanonicalIssuerPartitionReservation.Lifecycle.RELEASED);
      assertThat(terminal.candidate().leaseEvidence().canonicalJson())
          .isEqualTo(session.lease.canonicalJson());
      assertThat(terminal.orphanCleanupId()).isNotNull();
      assertThat(terminal.negativeResolutionId()).isNotNull();
      assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
      assertThat(session.gs.negativeFence())
          .isEqualTo(
              "original-new-never-admitted-closure/v1:"
                  + terminal.abandonmentId()
                  + ":"
                  + terminal.candidateDigest());
      assertThat(session.gs.maximumConcurrentClosureQualifications()).isGreaterThanOrEqualTo(2);
      assertThat(session.gs.heldClosureQualifications()).isZero();
      var account = session.client.read(session.jwt, session.leaseBytes());
      assertThat(account.state()).isEqualTo(State.ABORTED);
      assertThat(account.bindingDecisionId()).isNull();
      assertThat(account.originalAckReceipt()).isNull();
      assertThat(account.orphanCleanupId()).isEqualTo(terminal.orphanCleanupId());
      org.mockito.Mockito.verify(session.reverse)
          .readOriginalNewNeverAdmitted(
              org.mockito.ArgumentMatchers.eq(session.gs.transitionId()),
              org.mockito.ArgumentMatchers.eq(terminal.abandonmentId()),
              any());
      org.mockito.Mockito.verify(session.reverse, org.mockito.Mockito.never()).read(any(), any());
      var releaseSource =
          session
              .gs
              .dsl()
              .fetchSingle(
                  "SELECT original_local_operation_id, original_bootstrap_member_operation_id FROM game_session_canonical_issuer_binding_local_release_operation WHERE transition_id = ?",
                  session.gs.transitionId());
      assertThat(releaseSource.get("original_bootstrap_member_operation_id") != null)
          .isEqualTo(installedBootstrap);
      assertThat(releaseSource.get("original_local_operation_id") != null)
          .isEqualTo(!installedBootstrap);
      session.assertNeverAdmittedExactRetryPreservesState();
      assertThat(
              session
                  .gs
                  .dsl()
                  .fetch(
                      "SELECT to_jsonb(proof)::text FROM game_session_canonical_issuer_bootstrap_partition_proof proof ORDER BY partition_id")
                  .formatJSON())
          .isEqualTo(frozenProofs);
      if (installedBootstrap) {
        session.gs.assertUnaffectedBootstrapFieldsPresent();
        assertThat(
                session
                    .gs
                    .dsl()
                    .fetchSingle(
                        "SELECT count(*) FROM game_session_canonical_issuer_binding_local_operation WHERE transition_id = ?",
                        session.gs.transitionId())
                    .get(0, Long.class))
            .isZero();
        session.gs.prepareOtherControllerNew("after-bootstrap-retirement");
        assertThat(
                session.gs.projectOtherControllerNew("after-bootstrap-retirement").scriptOutcome())
            .isEqualTo(1L);
        session.gs.assertUnaffectedBootstrapFieldsPresent();
        assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void neverAdmittedUnknownAccountTerminalAndRestartRetainOriginalCleanupAndNegativeResolution(
      boolean installedBootstrap) throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      var unknown = session.client.abort(session.jwt, session.leaseBytes(), null);
      assertThat(unknown.state()).isEqualTo(State.ABORTED);
      assertThat(unknown.bindingDecisionId()).isNull();
      String originalAccountTerminal = session.accountOperationImage();
      var terminal = session.abortNeverAdmitted();
      assertThat(terminal.cleanupComplete()).isTrue();
      assertThat(terminal.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      assertThat(session.accountOperationImage()).isEqualTo(originalAccountTerminal);
      String originalResolution = session.negativeResolutionImage();
      String settledGs = session.image();
      byte[] settledIssuer = session.gs.issuerPartitionFieldSetBytes();
      String fence = session.gs.negativeFence();
      session.restartAccount();
      var replay = session.abortNeverAdmitted();
      assertThat(replay.cleanupComplete()).isTrue();
      assertThat(replay.abandonmentId()).isEqualTo(terminal.abandonmentId());
      assertThat(replay.negativeResolutionId()).isEqualTo(terminal.negativeResolutionId());
      assertThat(replay.orphanCleanupId()).isEqualTo(unknown.orphanCleanupId());
      assertThat(replay.closureDigest()).isEqualTo(terminal.closureDigest());
      assertThat(session.accountOperationImage()).isEqualTo(originalAccountTerminal);
      assertThat(session.negativeResolutionImage()).isEqualTo(originalResolution);
      assertThat(session.image()).isEqualTo(settledGs);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(settledIssuer);
      assertThat(session.gs.negativeFence()).isEqualTo(fence);
      assertThat(session.gs.heldClosureQualifications()).isZero();
    }
  }

  @Test
  void installedBootstrapNegativeCleanupResumesOriginalReleaseAndRetirementAfterLostReplies()
      throws Exception {
    try (var session = openNeverAdmitted(true)) {
      // Actual Account negative RPC completes; the changed controlled issuer incarnation holds GS
      // cleanup.
      session.gs.moveIssuerIncarnation();
      assertThatThrownBy(session::abortNeverAdmitted).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.ABORTED);
      assertThat(session.gs.abandonmentSource().cleanupComplete()).isFalse();
      session.gs.restoreIssuerState();
      session.gs.prepareOtherControllerNew("pending-bootstrap-cleanup");
      var release = session.gs.prepareIssuerRelease();
      assertThatThrownBy(() -> session.gs.projectOtherControllerNew("pending-bootstrap-cleanup"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> session.gs.projectIssuerReleaseAndLoseReply(release))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled lost issuer release reply");
      var retry = session.gs.prepareIssuerRelease();
      assertThat(retry.operationId()).isEqualTo(release.operationId());
      assertThat(retry.exactReleasedMemberValueBytes())
          .containsExactly(release.exactReleasedMemberValueBytes());
      var readback = session.gs.projectIssuerRelease(retry);
      assertThat(readback.scriptOutcome()).isEqualTo(2L);
      session.gs.completeIssuerRelease(retry, readback);
      var releasedFields = session.gs.issuerPartitionFieldSetBytes();
      assertThatThrownBy(session.gs::replayOriginalBootstrapPartition)
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(releasedFields);
      session.gs.prepareIssuerRetirement(session.gs.transitionId());
      assertThatThrownBy(() -> session.gs.projectOtherControllerNew("pending-bootstrap-cleanup"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(
              () ->
                  session.gs.retireIssuerCapacityAndLoseReplyForTransition(
                      session.gs.transitionId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled lost retirement reply");
      assertThat(
              session.gs.isIssuerRetirementComplete(
                  session.gs.prepareIssuerRetirement(session.gs.transitionId())))
          .isTrue();
      var retiredFields = session.gs.issuerPartitionFieldSetBytes();
      assertThatThrownBy(() -> session.gs.projectIssuerRelease(release))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(session.gs::replayOriginalBootstrapPartition)
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(retiredFields);
      session.restartAccount();
      assertThat(session.abortNeverAdmitted().cleanupComplete()).isTrue();
      assertThat(session.gs.projectOtherControllerNew("pending-bootstrap-cleanup").scriptOutcome())
          .isEqualTo(1L);
      assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void negativeCleanupOwnerResumesAfterCommittedReleasePreparationReplyLoss(
      boolean installedBootstrap) throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      session.gs.moveIssuerIncarnation();
      assertThatThrownBy(session::abortNeverAdmitted).isInstanceOf(RuntimeException.class);
      assertThat(session.client.read(session.jwt, session.leaseBytes()).state())
          .isEqualTo(State.ABORTED);
      String account = session.accountOperationImage();
      String resolution = session.negativeResolutionImage();
      String fence = session.gs.negativeFence();
      session.gs.restoreIssuerState();

      assertThatThrownBy(session.gs::prepareIssuerReleaseAndLoseReply)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled lost issuer release preparation reply");
      String originalRelease = session.issuerReleaseIdentityImage();
      var release = session.gs.prepareIssuerRelease();
      var preparedRetry = session.gs.prepareIssuerRelease();
      assertThat(preparedRetry.operationId()).isEqualTo(release.operationId());
      assertThat(preparedRetry.exactOriginalMemberValueBytes())
          .containsExactly(release.exactOriginalMemberValueBytes());
      assertThat(preparedRetry.exactReleasedMemberValueBytes())
          .containsExactly(release.exactReleasedMemberValueBytes());
      session.restartAccount();
      var completed = session.abortNeverAdmitted();

      assertThat(completed.cleanupComplete()).isTrue();
      assertThat(session.accountOperationImage()).isEqualTo(account);
      assertThat(session.negativeResolutionImage()).isEqualTo(resolution);
      assertThat(session.gs.negativeFence()).isEqualTo(fence);
      assertThat(session.issuerReleaseIdentityImage()).isEqualTo(originalRelease);
      assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
      if (installedBootstrap) session.gs.assertUnaffectedBootstrapFieldsPresent();
      session.assertNeverAdmittedExactRetryPreservesState();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void negativeCleanupOwnerResumesAfterCommittedRetirementReplyLoss(boolean installedBootstrap)
      throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      session.gs.moveIssuerIncarnation();
      assertThatThrownBy(session::abortNeverAdmitted).isInstanceOf(RuntimeException.class);
      String account = session.accountOperationImage();
      String resolution = session.negativeResolutionImage();
      String fence = session.gs.negativeFence();
      session.gs.restoreIssuerState();
      var release = session.gs.prepareIssuerRelease();
      session.gs.completeIssuerRelease(release, session.gs.projectIssuerRelease(release));
      assertThatThrownBy(
              () ->
                  session.gs.retireIssuerCapacityAndLoseReplyForTransition(
                      session.gs.transitionId()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("controlled lost retirement reply");
      String history = session.issuerCleanupHistoryImage();
      byte[] retiredFields = session.gs.issuerPartitionFieldSetBytes();
      assertThat(session.gs.abandonmentSource().cleanupComplete()).isFalse();

      session.restartAccount();
      assertThat(session.abortNeverAdmitted().cleanupComplete()).isTrue();

      assertThat(session.accountOperationImage()).isEqualTo(account);
      assertThat(session.negativeResolutionImage()).isEqualTo(resolution);
      assertThat(session.gs.negativeFence()).isEqualTo(fence);
      assertThat(session.issuerCleanupHistoryImage()).isEqualTo(history);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(retiredFields);
      assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
      if (installedBootstrap) session.gs.assertUnaffectedBootstrapFieldsPresent();
      session.assertNeverAdmittedExactRetryPreservesState();
    }
  }

  @Test
  void neverAdmittedMissingQualificationChangedCredentialAndMismatchedAbandonmentRemainUnsettled()
      throws Exception {
    try (var session = openNeverAdmitted()) {
      String accountPending = session.accountOperationImage();
      assertThatThrownBy(
              () ->
                  session.client.abortNeverAdmitted(
                      session.jwt, session.lease, session.gs.transitionId(), UUID.randomUUID()))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.accountOperationImage()).isEqualTo(accountPending);
      assertThatThrownBy(
              () ->
                  session
                      .gs
                      .neverAdmittedOwner(session.client)
                      .abort(session.gs.transitionId(), "invalid-current-caller-token"))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.accountOperationImage()).isEqualTo(accountPending);
      assertThat(session.gs.abandonmentSource().cleanupComplete()).isFalse();
      assertThat(session.gs.abandonmentSource().orphanCleanupId()).isNull();
      assertThat(session.gs.originalIssuerCapacityAbsent()).isFalse();
      session.gs.withdrawControlledClosureQualification();
      assertThatThrownBy(session::abortNeverAdmitted).isInstanceOf(RuntimeException.class);
      assertThat(session.accountOperationImage()).isEqualTo(accountPending);
      assertThat(session.gs.abandonmentSource().cleanupComplete()).isFalse();
      assertThat(session.gs.heldClosureQualifications()).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retainedNegativeSettlementResumesIssuerCleanupAfterPresentedCallerExpires(
      boolean installedBootstrap) throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      session.gs.moveIssuerIncarnation();
      assertThatThrownBy(session::abortNeverAdmitted).isInstanceOf(RuntimeException.class);
      var retained = session.gs.abandonmentSource();
      assertThat(retained.transition().status().name()).isEqualTo("ABORTED");
      assertThat(retained.orphanCleanupId()).isNotNull();
      assertThat(retained.negativeResolutionId()).isNotNull();
      assertThat(retained.cleanupComplete()).isFalse();
      assertThat(session.gs.originalIssuerCapacityAbsent()).isFalse();
      String account = session.accountOperationImage();
      String resolution = session.negativeResolutionImage();
      String fence = session.gs.negativeFence();

      session.restartAccountAfterPresentedCallerExpiry();
      // The actual receiving verifier now rejects the unchanged original signed JWT.
      assertThatThrownBy(
              () ->
                  session.client.abortNeverAdmitted(
                      session.jwt,
                      session.lease,
                      session.gs.transitionId(),
                      retained.abandonmentId()))
          .isInstanceOf(IllegalStateException.class);
      var expiredAccount = spy(session.client);
      var recreatedOwner = session.gs.neverAdmittedOwner(expiredAccount);
      byte[] pendingIssuer = session.gs.issuerPartitionFieldSetBytes();
      assertThatThrownBy(() -> recreatedOwner.abort(session.gs.transitionId(), session.jwt))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.gs.abandonmentSource().cleanupComplete()).isFalse();
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(pendingIssuer);
      session.gs.restoreIssuerState();

      var completed = recreatedOwner.abort(session.gs.transitionId(), session.jwt);
      assertThat(completed.cleanupComplete()).isTrue();
      assertThat(completed.abandonmentId()).isEqualTo(retained.abandonmentId());
      assertThat(completed.orphanCleanupId()).isEqualTo(retained.orphanCleanupId());
      assertThat(completed.negativeResolutionId()).isEqualTo(retained.negativeResolutionId());
      assertThat(completed.closureDigest()).isEqualTo(retained.closureDigest());
      assertThat(completed.candidate().canonicalBytes())
          .containsExactly(retained.candidate().canonicalBytes());
      assertThat(session.accountOperationImage()).isEqualTo(account);
      assertThat(session.negativeResolutionImage()).isEqualTo(resolution);
      assertThat(session.gs.negativeFence()).isEqualTo(fence);
      assertThat(session.gs.originalIssuerCapacityAbsent()).isTrue();
      if (installedBootstrap) session.gs.assertUnaffectedBootstrapFieldsPresent();
      session.assertNeverAdmittedExactRetryPreservesState();

      String terminalRows = session.completedNegativeCleanupCurrentImage();
      String history = session.issuerCleanupHistoryImage();
      byte[] retiredIssuer = session.gs.issuerPartitionFieldSetBytes();
      session.gs.withdrawControlledClosureQualification();
      assertThatThrownBy(() -> recreatedOwner.abort(session.gs.transitionId(), session.jwt))
          .isInstanceOf(RuntimeException.class);
      assertThat(session.completedNegativeCleanupCurrentImage()).isEqualTo(terminalRows);
      assertThat(session.issuerCleanupHistoryImage()).isEqualTo(history);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(retiredIssuer);
      assertThat(session.gs.heldClosureQualifications()).isZero();
      org.mockito.Mockito.verify(expiredAccount, org.mockito.Mockito.never())
          .abortNeverAdmitted(any(), any(), any(), any());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void expiredPresentedCallerCannotEstablishDurableNegativeSettlement(boolean installedBootstrap)
      throws Exception {
    try (var session = openNeverAdmitted(installedBootstrap)) {
      String account = session.accountOperationImage();
      session.restartAccountAfterPresentedCallerExpiry();
      var expiredAccount = spy(session.client);
      var owner = session.gs.neverAdmittedOwner(expiredAccount);
      assertThatThrownBy(() -> owner.abort(session.gs.transitionId(), session.jwt))
          .isInstanceOf(IllegalStateException.class);
      var closed = session.gs.abandonmentSource();
      assertThat(closed.closureDigest()).isNotNull();
      assertThat(closed.orphanCleanupId()).isNull();
      assertThat(closed.negativeResolutionId()).isNull();
      assertThat(closed.cleanupComplete()).isFalse();
      String closedRows = session.completedNegativeCleanupCurrentImage();
      String history = session.issuerCleanupHistoryImage();
      String fence = session.gs.negativeFence();
      byte[] issuerFields = session.gs.issuerPartitionFieldSetBytes();
      assertThatThrownBy(() -> owner.abort(session.gs.transitionId(), session.jwt))
          .isInstanceOf(IllegalStateException.class);
      org.mockito.Mockito.verify(expiredAccount, org.mockito.Mockito.times(2))
          .abortNeverAdmitted(any(), any(), any(), any());
      assertThat(session.accountOperationImage()).isEqualTo(account);
      assertThat(session.completedNegativeCleanupCurrentImage()).isEqualTo(closedRows);
      assertThat(session.issuerCleanupHistoryImage()).isEqualTo(history);
      assertThat(session.gs.negativeFence()).isEqualTo(fence);
      assertThat(session.gs.issuerPartitionFieldSetBytes()).containsExactly(issuerFields);
      assertThat(session.gs.originalIssuerCapacityAbsent()).isFalse();
      assertThat(session.gs.heldClosureQualifications()).isZero();
    }
  }
}
