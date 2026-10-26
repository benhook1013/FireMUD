package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered Account composition for exact original StartSession admission protection. */
public final class AccountStartSessionAdmissionProtectionAcquisitionService {
  private final AccountStartSessionOperatorAuthorizationService issuer;
  private final AccountStartSessionWorldParticipationRepository participationRepository;
  private final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository;
  private final AccountStartSessionAdmissionProtectionRepository protectionRepository;
  private final OriginalStartSessionCurrentAttemptClient gameSessionClient;
  private final TransactionTemplate lookupTransaction;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionAdmissionProtectionAcquisitionService(
      AccountStartSessionOperatorAuthorizationService issuer,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository,
      AccountStartSessionAdmissionProtectionRepository protectionRepository,
      OriginalStartSessionCurrentAttemptClient gameSessionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.issuer =
        Objects.requireNonNull(issuer, "current Account StartSession authority is required");
    this.participationRepository =
        Objects.requireNonNull(
            participationRepository, "Account World participation repository is required");
    this.attemptRepository =
        Objects.requireNonNull(
            attemptRepository, "original-attempt evidence repository is required");
    this.protectionRepository =
        Objects.requireNonNull(protectionRepository, "admission protection repository is required");
    this.gameSessionClient =
        Objects.requireNonNull(
            gameSessionClient, "Game Session current-attempt reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    lookupTransaction = transaction(transactionManager);
    readbackTransaction = transaction(transactionManager);
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Acquires or replays the exact current immutable protection, without extending its lease. */
  public AccountStartSessionAdmissionProtectionEvidence acquire(
      AccountStartSessionAdmissionProtectionRequest request) {
    requireGameSessionPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "Account admission protection request is required");
    requireRequestTarget(request);

    byte[] tupleBytes = request.originalPostAuthorizationTupleBytes();
    Request currentAttemptRequest = currentAttemptRequest(request);
    InitialOwnerState initial = readInitialOwnerState(tupleBytes, currentAttemptRequest, request);

    // This mTLS owner observation is outside Account SQL on every exact retry. It is an
    // observation only; the current Account callback below owns the source-currentness decision.
    requireNoAmbientTransaction();
    Result observed = readCurrentAttempt(currentAttemptRequest);
    requireNoAmbientTransaction();
    requireObservedBinding(request, currentAttemptRequest, observed);
    requireSameAttempt(initial.retainedAttempt().result(), observed);

    StoredProtection committed =
        issuer.withCurrentGameSessionAdmissionProtectionCurrentness(
            tupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            capture -> {
              StoredParticipation exactParticipation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact original Account participation is unavailable"));
              requireSameParticipation(initial.participation(), exactParticipation);
              StoredEvidence retainedAttempt =
                  attemptRepository
                      .findHistoricalExact(exactParticipation, currentAttemptRequest)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact original Account attempt evidence is unavailable"));
              requireSameAttempt(retainedAttempt.result(), observed);
              return protectionRepository.acquireOrReadExact(request, capture, exactParticipation);
            });

    requireNoAmbientTransaction();
    StoredProtection readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    protectionRepository
                        .findCurrentExact(
                            committed.evidence().accountProtectionId(),
                            committed.evidence().accountProtectionFence(),
                            request.canonicalBytes())
                        .orElseThrow(
                            () ->
                                failedPrecondition(
                                    "Committed current admission protection is not readable"))),
            "Account admission protection readback transaction returned no value");
    if (!Arrays.equals(
        committed.evidence().canonicalBytes(), readback.evidence().canonicalBytes())) {
      throw failedPrecondition("Committed admission protection readback differs from its receipt");
    }
    return readback.evidence();
  }

  private InitialOwnerState readInitialOwnerState(
      byte[] tupleBytes,
      Request currentAttemptRequest,
      AccountStartSessionAdmissionProtectionRequest request) {
    requireNoAmbientTransaction();
    return Objects.requireNonNull(
        lookupTransaction.execute(
            ignored -> {
              StoredParticipation participation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact original Account participation is unavailable"));
              requireParticipationBinding(request, participation);
              // World preparation is already COMMITTED, so its Account participation is settled.
              // Read the immutable original witness here; currentness comes from the fresh
              // authenticated Game Session observation and the Account callback below.
              StoredEvidence attempt =
                  attemptRepository
                      .findHistoricalExact(participation, currentAttemptRequest)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact original Account attempt evidence is unavailable"));
              requireStoredAttemptBinding(request, currentAttemptRequest, attempt.result());
              return new InitialOwnerState(participation, attempt);
            }),
        "Account initial owner-state transaction returned no value");
  }

  private Result readCurrentAttempt(Request request) {
    try {
      Result observed = gameSessionClient.read(request);
      if (observed == null)
        throw failedPrecondition("Game Session current-attempt proof is unavailable");
      var response = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observed);
      return OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, response);
    } catch (StatusRuntimeException failure) {
      throw dependencyFailure(failure);
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Game Session current-attempt proof is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private Request currentAttemptRequest(AccountStartSessionAdmissionProtectionRequest request) {
    return new Request(
        freshReadId(request.accountWorldParticipationId(), request.gameSessionOwnerAttemptId()),
        workloadNamespace,
        request.originalPostAuthorizationTupleBytes(),
        request.gameSessionOwnerAttemptId(),
        request.gameSessionOwnerMutationId(),
        request.gameSessionOwnerFence());
  }

  private void requireRequestTarget(AccountStartSessionAdmissionProtectionRequest request) {
    var tuple = request.originalTuple();
    var scope = tuple.preAuthorizationTuple().action().scope();
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
        || !workloadNamespace.equals(scope.targetNamespace())
        || !workloadNamespace.equals(hold.targetNamespace())
        || !scope.tenantId().equals(hold.canonicalTenantId())) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact original StartSession request for this Game Session namespace required")
          .asRuntimeException();
    }
  }

  private static void requireParticipationBinding(
      AccountStartSessionAdmissionProtectionRequest request, StoredParticipation participation) {
    var hold = request.worldAdmissionHoldIdentity().request();
    if (!request
            .originalTuple()
            .controlPlaneRequestId()
            .equals(participation.controlPlaneRequestId())
        || !Arrays.equals(
            request.originalPostAuthorizationTupleBytes(),
            participation.originalPostAuthorizationTuple())
        || !request.accountWorldParticipationId().equals(participation.participationId())
        || request.accountWorldParticipationFence() != participation.participationFence()
        || !request.gameSessionOwnerAttemptId().equals(participation.gameSessionOwnerAttemptId())
        || request.gameSessionOwnerFence() != participation.gameSessionOwnerFence()
        || !hold.targetNamespace().equals(participation.targetNamespace())
        || !hold.canonicalTenantId().equals(participation.canonicalTenantId())
        || !hold.canonicalGameInstanceId().equals(participation.canonicalGameInstanceId())) {
      throw failedPrecondition(
          "Original Account participation differs from the full admission request");
    }
  }

  private static void requireStoredAttemptBinding(
      AccountStartSessionAdmissionProtectionRequest request, Request expected, Result stored) {
    if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(stored.phaseState())
        || !expected.targetNamespace().equals(stored.request().targetNamespace())
        || !Arrays.equals(
            expected.canonicalPostAuthorizationTuple(),
            stored.request().canonicalPostAuthorizationTuple())
        || !expected.expectedOwnerAttemptId().equals(stored.request().expectedOwnerAttemptId())
        || !expected.expectedOwnerMutationId().equals(stored.request().expectedOwnerMutationId())
        || expected.expectedOwnerFence() != stored.request().expectedOwnerFence()
        || !request.originalLeaseExpiresAt().equals(stored.originalLeaseExpiresAt())
        || !MessageDigest.isEqual(
            request.accountRedemptionProjectionBytes(), stored.accountRedemptionProjection())) {
      throw failedPrecondition(
          "Retained Account attempt evidence differs from the full admission request");
    }
  }

  private static void requireObservedBinding(
      AccountStartSessionAdmissionProtectionRequest request, Request expected, Result observed) {
    requireStoredAttemptBinding(request, expected, observed);
    try {
      StartSessionAccountRedemptionProjection.requireCurrentBinding(
          observed.accountRedemptionProjection(), request.originalTuple());
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Current Game Session observation lacks the exact original redemption")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static void requireSameAttempt(Result retained, Result observed) {
    if (!retained.request().targetNamespace().equals(observed.request().targetNamespace())
        || !Arrays.equals(
            retained.request().canonicalPostAuthorizationTuple(),
            observed.request().canonicalPostAuthorizationTuple())
        || !retained
            .request()
            .expectedOwnerAttemptId()
            .equals(observed.request().expectedOwnerAttemptId())
        || !retained
            .request()
            .expectedOwnerMutationId()
            .equals(observed.request().expectedOwnerMutationId())
        || retained.request().expectedOwnerFence() != observed.request().expectedOwnerFence()
        || !retained.originalLeaseExpiresAt().equals(observed.originalLeaseExpiresAt())
        || !MessageDigest.isEqual(
            retained.accountRedemptionProjection(), observed.accountRedemptionProjection())) {
      throw failedPrecondition(
          "Current Game Session attempt differs from the retained original evidence");
    }
  }

  private static void requireSameParticipation(
      StoredParticipation expected, StoredParticipation actual) {
    if (!expected.participationId().equals(actual.participationId())
        || expected.participationFence() != actual.participationFence()
        || !expected.controlPlaneRequestId().equals(actual.controlPlaneRequestId())
        || !Arrays.equals(
            expected.originalPostAuthorizationTuple(), actual.originalPostAuthorizationTuple())
        || !expected.targetNamespace().equals(actual.targetNamespace())
        || !expected.canonicalTenantId().equals(actual.canonicalTenantId())
        || !expected.canonicalGameInstanceId().equals(actual.canonicalGameInstanceId())
        || !expected.gameSessionOwnerAttemptId().equals(actual.gameSessionOwnerAttemptId())
        || expected.gameSessionOwnerFence() != actual.gameSessionOwnerFence()
        || !expected.preparationInputJson().equals(actual.preparationInputJson())
        || !expected.preparationInputDigest().equals(actual.preparationInputDigest())
        || !expected.sources().equals(actual.sources())) {
      throw failedPrecondition(
          "Retained Account participation changed before protection acquisition");
    }
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription(
              "Exact same-namespace Game Session workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Account admission owner reads require independent transactions")
          .asRuntimeException();
    }
  }

  private static UUID freshReadId(UUID... reserved) {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (Arrays.asList(reserved).contains(id));
    return id;
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(manager));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(false);
    return template;
  }

  private static StatusRuntimeException failedPrecondition(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  private static StatusRuntimeException dependencyFailure(StatusRuntimeException failure) {
    Status.Code code = failure.getStatus().getCode();
    Status status =
        switch (code) {
          case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN ->
              Status.fromCode(code);
          default -> Status.FAILED_PRECONDITION;
        };
    return status
        .withDescription("Game Session current-attempt dependency failed")
        .asRuntimeException();
  }

  private record InitialOwnerState(
      StoredParticipation participation, StoredEvidence retainedAttempt) {}
}
