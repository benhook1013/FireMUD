package net.firedevops.firemud.loggingadmin.operator;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient.AuthorizationReference;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Acquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizationCompletion;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimHandle;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionTransition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.PendingTransition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.RecoveryAcquisition;

/**
 * Coordinates Account authorization and durably records the owner handoff without activating an
 * ingress. Explicit dispatch methods forward only a newly committed owner-pending handoff.
 *
 * <p>The Account issue call occurs only after the original claim wins the durable pending
 * transition. The Account recovery call is read-only and uses a fresh recovery claim. A transient
 * owner payload is returned only after both the Account response and the OWNER_EXECUTION_PENDING
 * handoff are durable; neither the raw reference nor the forwarded control-ui token is persisted or
 * logged here.
 */
public final class StartSessionAuthorizationCoordinator {
  private final StartSessionPreAuthorizationReservationService reservations;
  private final StartSessionOperatorAuthorizationClient accountClient;

  public StartSessionAuthorizationCoordinator(
      StartSessionPreAuthorizationReservationService reservations,
      StartSessionOperatorAuthorizationClient accountClient) {
    this.reservations = Objects.requireNonNull(reservations, "reservation service is required");
    this.accountClient = Objects.requireNonNull(accountClient, "Account client is required");
  }

  /** Begins human issuance only for the first reservation owner that wins AUTHORIZATION_PENDING. */
  public Result issueHuman(
      StartSessionPreAuthorizationReservationTuple tuple, String controlUiJwt) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Acquisition acquisition = reservations.acquire(tuple);
    if (!acquisition.newlyAcquired()) {
      return Result.alreadyInProgress();
    }

    ClaimHandle claim = acquisition.claim();
    PendingTransition pending = reservations.markAuthorizationPending(claim);
    if (!pending.mayDispatchAccountAuthorization()) {
      return Result.alreadyInProgress();
    }

    ClaimEvidence claimEvidence = claim.claimEvidence();
    AuthorizationReference accountResponse =
        accountClient.issueHuman(
            sharedTuple(tuple),
            controlUiJwt,
            claimEvidence.reservationOwnerId(),
            claimEvidence.reservationClaimFence(),
            claimEvidence.currentClaimOwnerId(),
            claimEvidence.currentClaimFence());
    return persistExactAccountResponse(tuple, claim, accountResponse);
  }

  /** Issues the human reference and forwards only this invocation's durable owner-pending win. */
  public OwnerDispatchResult issueHumanAndDispatch(
      StartSessionPreAuthorizationReservationTuple tuple,
      String controlUiJwt,
      GameSessionClient ownerClient) {
    Objects.requireNonNull(ownerClient, "Game Session owner client is required");
    return dispatchOwnerPending(issueHuman(tuple, controlUiJwt), ownerClient);
  }

  /** Recovers only the original issuance response under a fresh read-only recovery claim. */
  public Result recover(StartSessionPreAuthorizationReservationTuple tuple) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Optional<RecoveryAcquisition> acquired = reservations.acquireAuthorizationRecoveryClaim(tuple);
    if (acquired.isEmpty()) {
      return Result.recoveryUnavailable();
    }

    ClaimHandle claim = acquired.orElseThrow().claim();
    ClaimEvidence claimEvidence = claim.claimEvidence();
    AuthorizationReference accountResponse =
        accountClient.recover(
            sharedTuple(tuple),
            claimEvidence.reservationOwnerId(),
            claimEvidence.reservationClaimFence(),
            claimEvidence.currentClaimOwnerId(),
            claimEvidence.currentClaimFence());
    return persistExactAccountResponse(tuple, claim, accountResponse);
  }

  /** Recovers the original reference and forwards only a newly persisted owner-pending handoff. */
  public OwnerDispatchResult recoverAndDispatch(
      StartSessionPreAuthorizationReservationTuple tuple, GameSessionClient ownerClient) {
    Objects.requireNonNull(ownerClient, "Game Session owner client is required");
    return dispatchOwnerPending(recover(tuple), ownerClient);
  }

  private static OwnerDispatchResult dispatchOwnerPending(
      Result authorization, GameSessionClient ownerClient) {
    Objects.requireNonNull(authorization, "authorization result is required");
    Objects.requireNonNull(ownerClient, "Game Session owner client is required");
    if (authorization.progress() != Progress.OWNER_EXECUTION_PENDING) {
      return new OwnerDispatchResult(authorization.progress(), Optional.empty());
    }
    GameSessionClient.StartSessionOwnerHandoffResult ownerEcho =
        Objects.requireNonNull(
            ownerClient.authorizeStartSession(authorization.handoff()),
            "Game Session owner echo is required");
    return new OwnerDispatchResult(authorization.progress(), Optional.of(ownerEcho));
  }

  private Result persistExactAccountResponse(
      StartSessionPreAuthorizationReservationTuple tuple,
      ClaimHandle claim,
      AuthorizationReference accountResponse) {
    Objects.requireNonNull(accountResponse, "validated Account response is required");
    StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple =
        StartSessionPostAuthorizationExecutionTuple.createHuman(
            sharedTuple(tuple),
            accountResponse.authenticatedLoggingWorkloadIdentity(),
            accountResponse.authorizationReferenceFingerprint(),
            claim.claimEvidence().reservationOwnerId(),
            claim.claimEvidence().reservationClaimFence(),
            accountResponse.authorityEvidenceBundle(),
            accountResponse.bundleReference());
    AuthorizationCompletion completion =
        reservations.completeAuthorization(claim, postAuthorizationTuple);
    if (!completion.transitioned()) {
      return Result.alreadyInProgress();
    }
    OwnerExecutionHandoff handoff = new OwnerExecutionHandoff(UUID.randomUUID());
    OwnerExecutionTransition ownerTransition =
        reservations.beginOwnerExecution(claim, postAuthorizationTuple, handoff);
    if (!ownerTransition.mayDispatch()) {
      return Result.alreadyInProgress();
    }
    return Result.ownerExecutionPending(
        new TransientOwnerExecutionHandoff(
            ownerTransition.snapshot().handoff(), accountResponse, postAuthorizationTuple));
  }

  private static net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      sharedTuple(StartSessionPreAuthorizationReservationTuple tuple) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .fromCanonicalJson(tuple.canonicalJson());
  }

  public enum Progress {
    OWNER_EXECUTION_PENDING,
    ALREADY_IN_PROGRESS,
    RECOVERY_UNAVAILABLE
  }

  /** Contains no response material unless this call won the durable owner-pending transition. */
  public record Result(Progress progress, TransientOwnerExecutionHandoff handoff) {
    public Result {
      Objects.requireNonNull(progress, "progress is required");
      if ((progress == Progress.OWNER_EXECUTION_PENDING) != (handoff != null)) {
        throw new IllegalArgumentException(
            "only newly persisted owner-pending state has a handoff");
      }
    }

    private static Result ownerExecutionPending(TransientOwnerExecutionHandoff handoff) {
      return new Result(Progress.OWNER_EXECUTION_PENDING, handoff);
    }

    private static Result alreadyInProgress() {
      return new Result(Progress.ALREADY_IN_PROGRESS, null);
    }

    private static Result recoveryUnavailable() {
      return new Result(Progress.RECOVERY_UNAVAILABLE, null);
    }

    @Override
    public String toString() {
      return "Result[progress="
          + progress
          + ", handoff="
          + (handoff == null ? "null" : "<redacted>")
          + "]";
    }
  }

  /**
   * Safe composition result: original progress and, only after dispatch, a secret-free owner echo.
   */
  public record OwnerDispatchResult(
      Progress progress, Optional<GameSessionClient.StartSessionOwnerHandoffResult> ownerEcho) {
    public OwnerDispatchResult {
      Objects.requireNonNull(progress, "progress is required");
      Objects.requireNonNull(ownerEcho, "owner echo optional is required");
      if ((progress == Progress.OWNER_EXECUTION_PENDING) != ownerEcho.isPresent()) {
        throw new IllegalArgumentException(
            "only a newly committed owner-pending handoff has an owner echo");
      }
    }

    @Override
    public String toString() {
      return "OwnerDispatchResult[progress="
          + progress
          + ", ownerEcho="
          + (ownerEcho.isPresent() ? "<secret-free>" : "empty")
          + "]";
    }
  }

  /**
   * Transient caller handoff; its diagnostic representation never includes raw authorization data.
   */
  public record TransientOwnerExecutionHandoff(
      OwnerExecutionHandoff ownerExecutionHandoff,
      AuthorizationReference accountResponse,
      StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple) {
    public TransientOwnerExecutionHandoff {
      Objects.requireNonNull(ownerExecutionHandoff, "owner execution handoff is required");
      Objects.requireNonNull(accountResponse, "Account response is required");
      Objects.requireNonNull(postAuthorizationTuple, "post-authorization tuple is required");
    }

    @Override
    public String toString() {
      return "TransientOwnerExecutionHandoff[ownerExecutionHandoff="
          + ownerExecutionHandoff
          + ", accountResponse=<redacted>, "
          + "postAuthorizationTuple=<redacted>]";
    }
  }
}
