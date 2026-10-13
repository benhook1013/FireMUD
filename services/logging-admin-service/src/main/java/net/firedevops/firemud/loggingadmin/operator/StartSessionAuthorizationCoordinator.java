package net.firedevops.firemud.loggingadmin.operator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient.AuthorizationReference;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Acquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizationCompletion;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizedSnapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimEvidence;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimHandle;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionTransition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.PendingTransition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.RecoveryAcquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
  private static final int MAX_CONTROL_UI_JWT_BYTES = 16 * 1024;
  private static final Pattern OPAQUE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final JsonMapper JWT_JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

  private final String configuredNamespace;
  private final StartSessionPreAuthorizationReservationService reservations;
  private final StartSessionOperatorAuthorizationClient accountClient;

  public StartSessionAuthorizationCoordinator(
      String configuredNamespace,
      StartSessionPreAuthorizationReservationService reservations,
      StartSessionOperatorAuthorizationClient accountClient) {
    if (!GrpcPeerIdentity.isValidNamespace(configuredNamespace)) {
      throw new IllegalArgumentException("A valid configured workload namespace is required");
    }
    this.configuredNamespace = configuredNamespace;
    this.reservations = Objects.requireNonNull(reservations, "reservation service is required");
    this.accountClient = Objects.requireNonNull(accountClient, "Account client is required");
  }

  /** Derives the original actor from the current authenticated tenant-admin request context. */
  public Result begin(
      String controlPlaneRequestId,
      StartSessionOperatorAction action,
      String originalControlUiJwt) {
    Objects.requireNonNull(action, "typed StartSession action is required");
    String stableRequestId =
        StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
            controlPlaneRequestId);
    requireConfiguredNamespace(action);
    requireCompactJwtEnvelope(originalControlUiJwt);
    // The verified Logging context binds the claimed original actor, but Account alone validates
    // the forwarded ControlUI token's current authorization and signature.
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
            stableRequestId, action);
    return issueHuman(tuple, originalControlUiJwt);
  }

  /** Begins human issuance only for the first reservation owner that wins AUTHORIZATION_PENDING. */
  public Result issueHuman(
      StartSessionPreAuthorizationReservationTuple tuple, String controlUiJwt) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    requireConfiguredNamespace(tuple.action());
    requireCompactJwtEnvelope(controlUiJwt);
    StartSessionPreAuthorizationReservationTuple currentTuple =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
            tuple.controlPlaneRequestId(), tuple.action());
    if (!currentTuple.canonicalJson().equals(tuple.canonicalJson())) {
      throw new StartSessionPreAuthorizationReservationService.IdempotencyConflictException(
          tuple.controlPlaneRequestId());
    }
    return issueHumanFromCurrentContext(currentTuple, controlUiJwt);
  }

  private Result issueHumanFromCurrentContext(
      StartSessionPreAuthorizationReservationTuple tuple, String controlUiJwt) {
    Acquisition acquisition = reservations.acquire(tuple);
    Snapshot acquiredSnapshot = acquisition.snapshot();
    requireSnapshotTuple(acquiredSnapshot, tuple);
    if (!acquisition.newlyAcquired()) {
      return observeExisting(tuple, acquiredSnapshot);
    }

    ClaimHandle claim = acquisition.claim();
    if (claim == null || !claimMatchesOriginalReservation(claim, tuple)) {
      return Result.outcomeUnknown(acquiredSnapshot, PendingReason.RESERVATION_CLAIM_MISMATCH);
    }

    PendingTransition pending;
    try {
      pending = reservations.markAuthorizationPending(claim);
    } catch (RuntimeException transitionFailure) {
      return Result.outcomeUnknown(
          acquiredSnapshot, PendingReason.PENDING_TRANSITION_OUTCOME_UNKNOWN);
    }
    if (pending == null || pending.snapshot() == null) {
      return Result.outcomeUnknown(
          acquiredSnapshot, PendingReason.PENDING_TRANSITION_OUTCOME_UNKNOWN);
    }
    Snapshot pendingSnapshot = pending.snapshot();
    if (!pending.mayDispatchAccountAuthorization()) {
      return observeExisting(tuple, pendingSnapshot);
    }
    if (!isExactPendingSnapshot(pendingSnapshot, tuple, claim)) {
      return Result.outcomeUnknown(
          pendingSnapshot, PendingReason.PENDING_TRANSITION_OUTCOME_UNKNOWN);
    }

    ClaimEvidence claimEvidence = claim.claimEvidence();
    AuthorizationReference accountResponse;
    try {
      accountResponse =
          accountClient.issueHuman(
              sharedTuple(tuple),
              controlUiJwt,
              claimEvidence.reservationOwnerId(),
              claimEvidence.reservationClaimFence(),
              claimEvidence.currentClaimOwnerId(),
              claimEvidence.currentClaimFence());
    } catch (RuntimeException ambiguousOrFailedCall) {
      return Result.outcomeUnknown(pendingSnapshot, PendingReason.ACCOUNT_ISSUANCE_OUTCOME_UNKNOWN);
    }
    return persistExactAccountResponse(tuple, claim, accountResponse, pendingSnapshot);
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
    requireConfiguredNamespace(tuple.action());
    Optional<RecoveryAcquisition> acquired = reservations.acquireAuthorizationRecoveryClaim(tuple);
    if (acquired.isEmpty()) {
      return Result.recoveryUnavailable();
    }

    ClaimHandle claim = acquired.orElseThrow().claim();
    ClaimEvidence claimEvidence = claim.claimEvidence();
    AuthorizationReference accountResponse;
    try {
      accountResponse =
          accountClient.recover(
              sharedTuple(tuple),
              claimEvidence.reservationOwnerId(),
              claimEvidence.reservationClaimFence(),
              claimEvidence.currentClaimOwnerId(),
              claimEvidence.currentClaimFence());
    } catch (RuntimeException ambiguousOrFailedCall) {
      return Result.outcomeUnknown(
          acquired.orElseThrow().snapshot(), PendingReason.ACCOUNT_RECOVERY_OUTCOME_UNKNOWN);
    }
    return persistExactAccountResponse(
        tuple, claim, accountResponse, acquired.orElseThrow().snapshot());
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
      AuthorizationReference accountResponse,
      Snapshot lastKnownSnapshot) {
    StartSessionPostAuthorizationExecutionTuple postAuthorizationTuple;
    String transientReference;
    try {
      if (accountResponse == null) {
        return Result.outcomeUnknown(lastKnownSnapshot, PendingReason.ACCOUNT_RESPONSE_INVALID);
      }
      transientReference = accountResponse.operatorAuthorizationReference();
      ClaimEvidence claimEvidence = claim.claimEvidence();
      if (transientReference == null || !OPAQUE_REFERENCE.matcher(transientReference).matches()) {
        return Result.outcomeUnknown(lastKnownSnapshot, PendingReason.ACCOUNT_RESPONSE_INVALID);
      }
      String expectedWorkloadIdentity =
          "spiffe://firemud/ns/" + configuredNamespace + "/sa/logging-admin-service";
      if (!expectedWorkloadIdentity.equals(
          accountResponse.authenticatedLoggingWorkloadIdentity())) {
        return Result.outcomeUnknown(lastKnownSnapshot, PendingReason.ACCOUNT_RESPONSE_INVALID);
      }
      StartSessionPostAuthorizationExecutionTuple expectedTuple =
          StartSessionPostAuthorizationExecutionTuple.createHuman(
              sharedTuple(tuple),
              accountResponse.authenticatedLoggingWorkloadIdentity(),
              accountResponse.authorizationReferenceFingerprint(),
              claimEvidence.reservationOwnerId(),
              claimEvidence.reservationClaimFence(),
              accountResponse.authorityEvidenceBundle(),
              accountResponse.bundleReference());
      postAuthorizationTuple = accountResponse.postAuthorizationTuple();
      if (postAuthorizationTuple == null
          || !MessageDigest.isEqual(
              expectedTuple.canonicalBytes(), postAuthorizationTuple.canonicalBytes())
          || !isExactAccountResult(tuple, claimEvidence, accountResponse, postAuthorizationTuple)) {
        return Result.outcomeUnknown(lastKnownSnapshot, PendingReason.ACCOUNT_RESPONSE_INVALID);
      }
    } catch (RuntimeException invalidResponse) {
      return Result.outcomeUnknown(lastKnownSnapshot, PendingReason.ACCOUNT_RESPONSE_INVALID);
    }

    AuthorizationCompletion completion;
    try {
      completion = reservations.completeAuthorization(claim, postAuthorizationTuple);
    } catch (RuntimeException localCompletionFailure) {
      return Result.outcomeUnknown(
          lastKnownSnapshot, PendingReason.LOCAL_COMPLETION_OUTCOME_UNKNOWN);
    }
    if (completion == null || completion.snapshot() == null) {
      return Result.outcomeUnknown(
          lastKnownSnapshot, PendingReason.LOCAL_COMPLETION_OUTCOME_UNKNOWN);
    }
    if (!completion.transitioned()) {
      return observeExisting(tuple, completion.snapshot().reservationSnapshot());
    }
    ClaimEvidence claimEvidence = claim.claimEvidence();
    AuthorizedSnapshot completedSnapshot = completion.snapshot();
    if (!isExactAuthorizedSnapshot(
        completedSnapshot, tuple, claim, claimEvidence, postAuthorizationTuple)) {
      return Result.outcomeUnknown(
          completedSnapshot.reservationSnapshot(), PendingReason.LOCAL_COMPLETION_RESULT_MISMATCH);
    }

    Optional<AuthorizedSnapshot> readback;
    try {
      readback = reservations.findAuthorizedExact(tuple);
    } catch (RuntimeException readbackFailure) {
      return Result.outcomeUnknown(
          completedSnapshot.reservationSnapshot(), PendingReason.AUTHORIZATION_READBACK_UNKNOWN);
    }
    if (readback == null
        || readback.isEmpty()
        || !sameAuthorizedSnapshot(completedSnapshot, readback.orElseThrow())
        || !isExactAuthorizedSnapshot(
            readback.orElseThrow(), tuple, claim, claimEvidence, postAuthorizationTuple)) {
      return Result.outcomeUnknown(
          completedSnapshot.reservationSnapshot(), PendingReason.AUTHORIZATION_READBACK_MISMATCH);
    }

    OwnerExecutionHandoff handoff = new OwnerExecutionHandoff(UUID.randomUUID());
    OwnerExecutionTransition ownerTransition;
    try {
      ownerTransition = reservations.beginOwnerExecution(claim, postAuthorizationTuple, handoff);
    } catch (RuntimeException ownerTransitionFailure) {
      return Result.outcomeUnknown(
          readback.orElseThrow().reservationSnapshot(),
          PendingReason.OWNER_PENDING_TRANSITION_OUTCOME_UNKNOWN,
          readback);
    }
    if (ownerTransition == null || ownerTransition.snapshot() == null) {
      return Result.outcomeUnknown(
          readback.orElseThrow().reservationSnapshot(),
          PendingReason.OWNER_PENDING_TRANSITION_OUTCOME_UNKNOWN,
          readback);
    }
    if (!ownerTransition.mayDispatch()) {
      return Result.alreadyInProgress(ownerTransition.snapshot().reservationSnapshot(), readback);
    }
    if (!isExactOwnerPendingSnapshot(
        ownerTransition.snapshot(), tuple, claim, claimEvidence, postAuthorizationTuple, handoff)) {
      return Result.outcomeUnknown(
          ownerTransition.snapshot().reservationSnapshot(),
          PendingReason.OWNER_PENDING_TRANSITION_RESULT_MISMATCH,
          readback);
    }
    return Result.ownerExecutionPending(
        new TransientOwnerExecutionHandoff(
            ownerTransition.snapshot().handoff(), accountResponse, postAuthorizationTuple),
        ownerTransition.snapshot().reservationSnapshot(),
        readback);
  }

  private Result observeExisting(
      StartSessionPreAuthorizationReservationTuple tuple, Snapshot acquisitionSnapshot) {
    Optional<AuthorizedSnapshot> readback;
    try {
      readback = reservations.findAuthorizedExact(tuple);
    } catch (RuntimeException unavailable) {
      return Result.outcomeUnknown(
          acquisitionSnapshot, PendingReason.EXISTING_AUTHORIZATION_READBACK_UNKNOWN);
    }
    if (readback == null) {
      return Result.outcomeUnknown(
          acquisitionSnapshot, PendingReason.EXISTING_AUTHORIZATION_READBACK_UNKNOWN);
    }
    if (readback.isPresent()) {
      AuthorizedSnapshot existing = readback.orElseThrow();
      if (!isBoundAuthorizedSnapshot(existing, tuple)) {
        return Result.outcomeUnknown(
            acquisitionSnapshot, PendingReason.EXISTING_AUTHORIZATION_READBACK_MISMATCH);
      }
      return Result.alreadyInProgress(existing.reservationSnapshot(), readback);
    }
    if (acquisitionSnapshot.state() == State.AUTHORIZED
        || acquisitionSnapshot.state() == State.OWNER_EXECUTION_PENDING) {
      return Result.outcomeUnknown(
          acquisitionSnapshot, PendingReason.EXISTING_AUTHORIZATION_READBACK_MISMATCH);
    }
    return Result.alreadyInProgress(acquisitionSnapshot, Optional.empty());
  }

  private boolean isExactAccountResult(
      StartSessionPreAuthorizationReservationTuple tuple,
      ClaimEvidence claimEvidence,
      AuthorizationReference response,
      StartSessionPostAuthorizationExecutionTuple postTuple) {
    try {
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(postTuple.authorityEvidenceBundleBytes());
      if (!response.expiresAt().equals(bundle.expiresAt())
          || !tuple.canonicalJson().equals(postTuple.preAuthorizationTuple().canonicalJson())
          || !tuple.controlPlaneRequestId().equals(postTuple.controlPlaneRequestId())
          || !tuple.mutationDigest().equals(postTuple.mutationDigest())
          || !"human_operator".equals(postTuple.issuanceKind())
          || !claimEvidence.reservationOwnerId().equals(postTuple.reservationOwnerId())
          || claimEvidence.reservationClaimFence() != postTuple.reservationClaimFence()) {
        return false;
      }
      bundle.requireTupleBinding(postTuple.preAuthorizationTuple());
      bundle.requireReferenceBinding(postTuple.bundleReference());
      return true;
    } catch (RuntimeException malformedEvidence) {
      return false;
    }
  }

  private static boolean claimMatchesOriginalReservation(
      ClaimHandle claim, StartSessionPreAuthorizationReservationTuple tuple) {
    ClaimEvidence evidence = claim.claimEvidence();
    return tuple.canonicalJson().equals(claim.tuple().canonicalJson())
        && tuple.mutationDigest().equals(claim.mutationDigest())
        && claim.purpose() == ClaimPurpose.ORIGINAL
        && evidence.reservationOwnerId().equals(evidence.currentClaimOwnerId())
        && evidence.reservationClaimFence() == evidence.currentClaimFence()
        && evidence.reservationClaimFence() > 0L;
  }

  private static boolean isExactPendingSnapshot(
      Snapshot snapshot, StartSessionPreAuthorizationReservationTuple tuple, ClaimHandle claim) {
    ClaimEvidence evidence = claim.claimEvidence();
    return snapshot.phase() == Phase.ACCOUNT_AUTHORIZATION
        && snapshot.state() == State.AUTHORIZATION_PENDING
        && snapshot.claimState() == ClaimState.ACTIVE
        && snapshot.claimPurpose() == claim.purpose()
        && snapshot.reservationClaimFence() == evidence.reservationClaimFence()
        && snapshot.claimFence() == evidence.currentClaimFence()
        && tuple.canonicalJson().equals(snapshot.tuple().canonicalJson())
        && tuple.mutationDigest().equals(snapshot.mutationDigest());
  }

  private static boolean isExactAuthorizedSnapshot(
      AuthorizedSnapshot snapshot,
      StartSessionPreAuthorizationReservationTuple tuple,
      ClaimHandle claim,
      ClaimEvidence evidence,
      StartSessionPostAuthorizationExecutionTuple postTuple) {
    return isBoundAuthorizedSnapshot(snapshot, tuple)
        && snapshot.reservationSnapshot().phase() == Phase.ACCOUNT_AUTHORIZATION
        && snapshot.reservationSnapshot().state() == State.AUTHORIZED
        && snapshot.reservationSnapshot().claimState() == ClaimState.ACTIVE
        && snapshot.reservationSnapshot().claimPurpose() == claim.purpose()
        && snapshot.reservationSnapshot().claimFence() == evidence.currentClaimFence()
        && snapshot.reservationSnapshot().reservationClaimFence()
            == evidence.reservationClaimFence()
        && snapshot.reservationOwnerId().equals(evidence.reservationOwnerId())
        && snapshot.currentClaimOwnerId().equals(evidence.currentClaimOwnerId())
        && snapshot.handoff() == null
        && Arrays.equals(
            snapshot.postAuthorizationTuple().canonicalBytes(), postTuple.canonicalBytes());
  }

  private static boolean isExactOwnerPendingSnapshot(
      AuthorizedSnapshot snapshot,
      StartSessionPreAuthorizationReservationTuple tuple,
      ClaimHandle claim,
      ClaimEvidence evidence,
      StartSessionPostAuthorizationExecutionTuple postTuple,
      OwnerExecutionHandoff expectedHandoff) {
    return isBoundAuthorizedSnapshot(snapshot, tuple)
        && snapshot.reservationSnapshot().phase() == Phase.OWNER_EXECUTION
        && snapshot.reservationSnapshot().state() == State.OWNER_EXECUTION_PENDING
        && snapshot.reservationSnapshot().claimState() == ClaimState.ACTIVE
        && snapshot.reservationSnapshot().claimPurpose() == claim.purpose()
        && snapshot.reservationSnapshot().claimFence() == evidence.currentClaimFence()
        && snapshot.reservationSnapshot().reservationClaimFence()
            == evidence.reservationClaimFence()
        && snapshot.reservationOwnerId().equals(evidence.reservationOwnerId())
        && snapshot.currentClaimOwnerId().equals(evidence.currentClaimOwnerId())
        && Arrays.equals(
            snapshot.postAuthorizationTuple().canonicalBytes(), postTuple.canonicalBytes())
        && expectedHandoff.equals(snapshot.handoff());
  }

  private static boolean isBoundAuthorizedSnapshot(
      AuthorizedSnapshot snapshot, StartSessionPreAuthorizationReservationTuple tuple) {
    if (snapshot == null
        || !tuple.canonicalJson().equals(snapshot.reservationSnapshot().tuple().canonicalJson())
        || !tuple.mutationDigest().equals(snapshot.reservationSnapshot().mutationDigest())
        || !tuple
            .canonicalJson()
            .equals(snapshot.postAuthorizationTuple().preAuthorizationTuple().canonicalJson())
        || !tuple
            .controlPlaneRequestId()
            .equals(snapshot.postAuthorizationTuple().controlPlaneRequestId())
        || !tuple.mutationDigest().equals(snapshot.postAuthorizationTuple().mutationDigest())
        || !"human_operator".equals(snapshot.postAuthorizationTuple().issuanceKind())
        || snapshot.reservationSnapshot().reservationClaimFence()
            != snapshot.postAuthorizationTuple().reservationClaimFence()
        || !snapshot
            .reservationOwnerId()
            .equals(snapshot.postAuthorizationTuple().reservationOwnerId())
        || snapshot.reservationSnapshot().state() != State.AUTHORIZED
            && snapshot.reservationSnapshot().state() != State.OWNER_EXECUTION_PENDING
        || snapshot.reservationSnapshot().phase() == Phase.ACCOUNT_AUTHORIZATION
            && snapshot.reservationSnapshot().state() != State.AUTHORIZED
        || snapshot.reservationSnapshot().phase() == Phase.OWNER_EXECUTION
            && snapshot.reservationSnapshot().state() != State.OWNER_EXECUTION_PENDING) {
      return false;
    }
    try {
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(
              snapshot.postAuthorizationTuple().authorityEvidenceBundleBytes());
      bundle.requireTupleBinding(snapshot.postAuthorizationTuple().preAuthorizationTuple());
      bundle.requireReferenceBinding(snapshot.postAuthorizationTuple().bundleReference());
      return true;
    } catch (RuntimeException malformedEvidence) {
      return false;
    }
  }

  private static boolean sameAuthorizedSnapshot(
      AuthorizedSnapshot expected, AuthorizedSnapshot actual) {
    return expected.reservationSnapshot().equals(actual.reservationSnapshot())
        && Arrays.equals(
            expected.postAuthorizationTuple().canonicalBytes(),
            actual.postAuthorizationTuple().canonicalBytes())
        && expected.reservationOwnerId().equals(actual.reservationOwnerId())
        && Objects.equals(expected.currentClaimOwnerId(), actual.currentClaimOwnerId())
        && Objects.equals(expected.handoff(), actual.handoff());
  }

  private void requireConfiguredNamespace(StartSessionOperatorAction action) {
    if (!configuredNamespace.equals(action.scope().targetNamespace())) {
      throw new IllegalArgumentException(
          "StartSession target namespace does not match the configured workload namespace");
    }
  }

  private static void requireSnapshotTuple(
      Snapshot snapshot, StartSessionPreAuthorizationReservationTuple tuple) {
    if (snapshot == null
        || !tuple.canonicalJson().equals(snapshot.tuple().canonicalJson())
        || !tuple.mutationDigest().equals(snapshot.mutationDigest())) {
      throw new StartSessionPreAuthorizationReservationService.IdempotencyConflictException(
          tuple.controlPlaneRequestId());
    }
  }

  /** Local envelope validation is not signature validation or current Account authorization. */
  private static void requireCompactJwtEnvelope(String token) {
    if (token == null || token.isEmpty() || token.length() > MAX_CONTROL_UI_JWT_BYTES) {
      throw malformedJwtEnvelope();
    }
    for (int index = 0; index < token.length(); index++) {
      if (token.charAt(index) > 0x7f) {
        throw malformedJwtEnvelope();
      }
    }
    String[] segments = token.split("\\.", -1);
    if (segments.length != 3
        || segments[0].isEmpty()
        || segments[1].isEmpty()
        || segments[2].isEmpty()) {
      throw malformedJwtEnvelope();
    }
    byte[] header = decodeJwtSegment(segments[0]);
    byte[] claims = decodeJwtSegment(segments[1]);
    byte[] signature = decodeJwtSegment(segments[2]);
    if (header == null || claims == null || signature == null || signature.length == 0) {
      throw malformedJwtEnvelope();
    }
    requireJsonObject(header);
    requireJsonObject(claims);
  }

  private static byte[] decodeJwtSegment(String segment) {
    if (!segment.matches("[A-Za-z0-9_-]+")) {
      return null;
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(segment);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)
          ? decoded
          : null;
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static void requireJsonObject(byte[] bytes) {
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      Map<String, Object> object = JWT_JSON.readValue(json, JSON_OBJECT);
      if (object == null) {
        throw malformedJwtEnvelope();
      }
    } catch (IOException | RuntimeException malformed) {
      throw malformedJwtEnvelope();
    }
  }

  private static IllegalArgumentException malformedJwtEnvelope() {
    return new IllegalArgumentException("control-ui JWT envelope is malformed");
  }

  private static net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      sharedTuple(StartSessionPreAuthorizationReservationTuple tuple) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .fromCanonicalJson(tuple.canonicalJson());
  }

  public enum Progress {
    OWNER_EXECUTION_PENDING,
    ALREADY_IN_PROGRESS,
    RECOVERY_UNAVAILABLE,
    AUTHORIZATION_OUTCOME_UNKNOWN
  }

  /** Contains no response material unless this call won the durable owner-pending transition. */
  public static final class Result {
    private final Progress progress;
    private final TransientOwnerExecutionHandoff handoff;
    private final Snapshot snapshot;
    private final Optional<AuthorizedSnapshot> authorizedReadback;
    private final PendingReason pendingReason;

    private Result(
        Progress progress,
        TransientOwnerExecutionHandoff handoff,
        Snapshot snapshot,
        Optional<AuthorizedSnapshot> authorizedReadback,
        PendingReason pendingReason) {
      this.progress = Objects.requireNonNull(progress, "progress is required");
      this.handoff = handoff;
      this.snapshot = snapshot;
      this.authorizedReadback =
          Objects.requireNonNull(authorizedReadback, "authorized readback is required");
      this.pendingReason = pendingReason;
      if ((progress == Progress.OWNER_EXECUTION_PENDING) != (handoff != null)
          || (progress == Progress.RECOVERY_UNAVAILABLE) != (snapshot == null)
          || (progress == Progress.AUTHORIZATION_OUTCOME_UNKNOWN) != (pendingReason != null)) {
        throw new IllegalArgumentException(
            "authorization result progress and transient material are inconsistent");
      }
    }

    private static Result ownerExecutionPending(
        TransientOwnerExecutionHandoff handoff,
        Snapshot snapshot,
        Optional<AuthorizedSnapshot> authorizedReadback) {
      return new Result(
          Progress.OWNER_EXECUTION_PENDING, handoff, snapshot, authorizedReadback, null);
    }

    private static Result alreadyInProgress(
        Snapshot snapshot, Optional<AuthorizedSnapshot> authorizedReadback) {
      return new Result(
          Progress.ALREADY_IN_PROGRESS,
          null,
          Objects.requireNonNull(snapshot, "observed snapshot is required"),
          authorizedReadback,
          null);
    }

    private static Result outcomeUnknown(Snapshot snapshot, PendingReason reason) {
      return outcomeUnknown(snapshot, reason, Optional.empty());
    }

    private static Result outcomeUnknown(
        Snapshot snapshot, PendingReason reason, Optional<AuthorizedSnapshot> authorizedReadback) {
      return new Result(
          Progress.AUTHORIZATION_OUTCOME_UNKNOWN,
          null,
          Objects.requireNonNull(snapshot, "last known snapshot is required"),
          authorizedReadback,
          Objects.requireNonNull(reason, "pending reason is required"));
    }

    private static Result recoveryUnavailable() {
      return new Result(Progress.RECOVERY_UNAVAILABLE, null, null, Optional.empty(), null);
    }

    public Progress progress() {
      return progress;
    }

    public TransientOwnerExecutionHandoff handoff() {
      return handoff;
    }

    public Snapshot snapshot() {
      return snapshot;
    }

    public Optional<AuthorizedSnapshot> authorizedReadback() {
      return authorizedReadback;
    }

    public Optional<PendingReason> pendingReason() {
      return Optional.ofNullable(pendingReason);
    }

    @Override
    public String toString() {
      return "Result[progress="
          + progress
          + ", handoff="
          + (handoff == null ? "null" : "<redacted>")
          + ", snapshot="
          + (snapshot == null ? "null" : "<observed>")
          + ", authorizedReadbackPresent="
          + authorizedReadback.isPresent()
          + ", pendingReason="
          + pendingReason
          + "]";
    }
  }

  public enum PendingReason {
    RESERVATION_CLAIM_MISMATCH,
    PENDING_TRANSITION_OUTCOME_UNKNOWN,
    ACCOUNT_ISSUANCE_OUTCOME_UNKNOWN,
    ACCOUNT_RECOVERY_OUTCOME_UNKNOWN,
    ACCOUNT_RESPONSE_INVALID,
    LOCAL_COMPLETION_OUTCOME_UNKNOWN,
    LOCAL_COMPLETION_RESULT_MISMATCH,
    AUTHORIZATION_READBACK_UNKNOWN,
    AUTHORIZATION_READBACK_MISMATCH,
    EXISTING_AUTHORIZATION_READBACK_UNKNOWN,
    EXISTING_AUTHORIZATION_READBACK_MISMATCH,
    OWNER_PENDING_TRANSITION_OUTCOME_UNKNOWN,
    OWNER_PENDING_TRANSITION_RESULT_MISMATCH
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
