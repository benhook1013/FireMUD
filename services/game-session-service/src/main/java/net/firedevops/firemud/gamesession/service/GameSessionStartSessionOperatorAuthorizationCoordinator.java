package net.firedevops.firemud.gamesession.service;

import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient.RedemptionResult;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationDisposition;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disabled-by-default, non-activating composition of the durable owner attempt and Account
 * redemption.
 *
 * <p>It commits the exact owner claim before making the one Account call. Exact retries only return
 * the existing snapshot; an ambiguous Account transport outcome remains pending and is never
 * automatically retried or terminalized here.
 */
public final class GameSessionStartSessionOperatorAuthorizationCoordinator {
  private static final String LOGGING_ADMIN_SERVICE = "logging-admin-service";

  private final GameSessionStartSessionOperatorAttemptRepository attemptRepository;
  private final StartSessionOperatorRedemptionClient redemptionClient;
  private final TransactionTemplate ownerTransaction;
  private final String workloadNamespace;

  public GameSessionStartSessionOperatorAuthorizationCoordinator(
      GameSessionStartSessionOperatorAttemptRepository attemptRepository,
      StartSessionOperatorRedemptionClient redemptionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.attemptRepository =
        Objects.requireNonNull(attemptRepository, "owner-attempt repository is required");
    this.redemptionClient =
        Objects.requireNonNull(redemptionClient, "redemption client is required");
    Objects.requireNonNull(transactionManager, "transaction manager is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Game Session workload namespace must be valid");
    }
    this.workloadNamespace = workloadNamespace;
    this.ownerTransaction = new TransactionTemplate(transactionManager);
    this.ownerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Reserves one attempt, redeems once only for its newly created claim, then attaches the exact
   * Account projection under that same still-current claim. The authorization reference is
   * transient input and is never returned or persisted by this coordinator.
   */
  public AuthorizationResult authorize(
      StartSessionPostAuthorizationExecutionTuple tuple,
      String transientOperatorAuthorizationReference) {
    Objects.requireNonNull(tuple, "complete post-authorization tuple is required");
    Objects.requireNonNull(
        transientOperatorAuthorizationReference, "transient Account reference is required");
    requireNoAmbientTransaction();
    requireAuthenticatedLoggingPeer(tuple);

    ReservationResult reservation =
        ownerTransaction.execute(status -> attemptRepository.reserve(tuple));
    if (reservation == null) {
      throw new IllegalStateException("Game Session owner reservation returned no result");
    }
    requireReservationMatchesTuple(reservation.snapshot(), tuple);

    if (reservation.disposition() == ReservationDisposition.EXACT_REPLAY) {
      if (reservation.claim().isPresent()) {
        throw new IllegalStateException("Exact owner-attempt replay unexpectedly returned a claim");
      }
      return new AuthorizationResult(
          reservation.snapshot(), Optional.empty(), Progress.EXACT_REPLAY);
    }

    AttemptClaim claim =
        reservation
            .claim()
            .orElseThrow(() -> new IllegalStateException("New owner attempt returned no claim"));
    requireClaimMatchesTuple(claim, tuple);
    requireSnapshotMatchesClaim(reservation.snapshot(), claim, tuple);
    requireNoAmbientTransaction();

    RedemptionResult redemption;
    try {
      redemption = redemptionClient.redeem(tuple, transientOperatorAuthorizationReference, claim);
    } catch (StatusRuntimeException transportOutcome) {
      if (!isAmbiguousTransportOutcome(transportOutcome)) {
        throw transportOutcome;
      }
      return new AuthorizationResult(
          reservation.snapshot(), Optional.of(claim), Progress.ACCOUNT_OUTCOME_AMBIGUOUS);
    }
    if (redemption == null || !claim.equals(redemption.gameSessionClaim())) {
      throw new IllegalStateException(
          "Account redemption result is not bound to the reserved Game Session claim");
    }
    AccountRedemptionProjection projection = redemption.projection();

    AttemptSnapshot attached =
        ownerTransaction.execute(
            status -> {
              AttemptSnapshot current = attemptRepository.validateCurrentClaim(claim);
              requireSameCurrentAttempt(reservation.snapshot(), current, claim, tuple);
              return attemptRepository.attachAccountRedemptionProjection(claim, projection);
            });
    if (attached == null) {
      throw new IllegalStateException("Account projection attachment returned no owner snapshot");
    }
    requireSnapshotMatchesClaim(attached, claim, tuple);
    if (!Arrays.equals(attached.accountRedemptionProjection(), projection.canonicalBytes())) {
      throw new IllegalStateException(
          "Persisted Account projection differs from the exact redemption response");
    }
    return new AuthorizationResult(
        attached, Optional.of(claim), Progress.ACCOUNT_PROJECTION_ATTACHED);
  }

  private static boolean isAmbiguousTransportOutcome(StatusRuntimeException exception) {
    return switch (exception.getStatus().getCode()) {
      case DEADLINE_EXCEEDED, UNAVAILABLE, CANCELLED, UNKNOWN, INTERNAL -> true;
      default -> false;
    };
  }

  private void requireAuthenticatedLoggingPeer(StartSessionPostAuthorizationExecutionTuple tuple) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    String tupleNamespace = tuple.preAuthorizationTuple().action().scope().targetNamespace();
    if (peer == null
        || !LOGGING_ADMIN_SERVICE.equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !workloadNamespace.equals(tupleNamespace)
        || !peer.uri().equals(tuple.authenticatedWorkloadIdentity())) {
      throw new SecurityException(
          "Authenticated peer is not the exact same-namespace Logging and Admin workload");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "StartSession Account redemption cannot run inside an ambient owner transaction");
    }
  }

  private static void requireReservationMatchesTuple(
      AttemptSnapshot snapshot, StartSessionPostAuthorizationExecutionTuple tuple) {
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(snapshot.targetNamespace())
        || !tuple.controlPlaneRequestId().equals(snapshot.controlPlaneRequestId())
        || !Arrays.equals(tuple.canonicalBytes(), snapshot.postAuthorizationExecutionTuple())) {
      throw new IllegalStateException(
          "Durable owner reservation differs from the exact StartSession tuple");
    }
  }

  private static void requireClaimMatchesTuple(
      AttemptClaim claim, StartSessionPostAuthorizationExecutionTuple tuple) {
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(claim.targetNamespace())
        || !tuple.controlPlaneRequestId().equals(claim.controlPlaneRequestId())) {
      throw new IllegalStateException("New owner claim differs from the exact StartSession tuple");
    }
  }

  private static void requireSnapshotMatchesClaim(
      AttemptSnapshot snapshot,
      AttemptClaim claim,
      StartSessionPostAuthorizationExecutionTuple tuple) {
    requireReservationMatchesTuple(snapshot, tuple);
    if (!claim.ownerAttemptId().equals(snapshot.ownerAttemptId())
        || !claim.ownerMutationId().equals(snapshot.ownerMutationId())
        || claim.ownerFence() != snapshot.ownerFence()) {
      throw new IllegalStateException(
          "Durable owner snapshot differs from the exact StartSession claim");
    }
  }

  private static void requireSameCurrentAttempt(
      AttemptSnapshot reserved,
      AttemptSnapshot current,
      AttemptClaim claim,
      StartSessionPostAuthorizationExecutionTuple tuple) {
    requireSnapshotMatchesClaim(current, claim, tuple);
    if (!reserved.ownerAttemptId().equals(current.ownerAttemptId())
        || !reserved.ownerMutationId().equals(current.ownerMutationId())
        || reserved.ownerFence() != current.ownerFence()
        || !reserved.phaseState().equals(current.phaseState())) {
      throw new IllegalStateException(
          "Current Game Session claim no longer identifies its original pending attempt");
    }
  }

  public enum Progress {
    EXACT_REPLAY,
    ACCOUNT_OUTCOME_AMBIGUOUS,
    ACCOUNT_PROJECTION_ATTACHED
  }

  /** Secret-free durable progress result; the raw Account reference is deliberately excluded. */
  public record AuthorizationResult(
      AttemptSnapshot snapshot, Optional<AttemptClaim> claim, Progress progress) {
    public AuthorizationResult {
      Objects.requireNonNull(snapshot, "durable owner-attempt snapshot is required");
      Objects.requireNonNull(claim, "owner claim wrapper is required");
      Objects.requireNonNull(progress, "authorization progress is required");
      if ((progress == Progress.EXACT_REPLAY) != claim.isEmpty()) {
        throw new IllegalArgumentException(
            "only exact owner-attempt replays may omit the new claim");
      }
      if (progress == Progress.ACCOUNT_PROJECTION_ATTACHED
          && snapshot.accountRedemptionProjection() == null) {
        throw new IllegalArgumentException(
            "attached progress requires the exact durable Account projection");
      }
      if (progress == Progress.ACCOUNT_OUTCOME_AMBIGUOUS
          && snapshot.accountRedemptionProjection() != null) {
        throw new IllegalArgumentException(
            "ambiguous Account progress cannot contain a redemption projection");
      }
    }
  }
}
