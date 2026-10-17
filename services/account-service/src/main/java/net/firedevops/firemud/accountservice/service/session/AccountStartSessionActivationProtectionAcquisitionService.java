package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository.StoredProtection;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account composition that protects the original StartSession source vector before
 * World activates its exact PREPARING instance.
 *
 * <p>The Game Session launch association and current original-attempt observations are independent
 * mTLS owner reads. Account then revalidates its own redeemed tuple, live source capture, retained
 * World participation, and finite original lease inside the existing original-currentness callback.
 * These controlled owner reads establish no issuer provenance or runtime admission by themselves.
 */
public final class AccountStartSessionActivationProtectionAcquisitionService {
  private final AccountStartSessionOperatorAuthorizationService issuer;
  private final AccountStartSessionWorldParticipationRepository participationRepository;
  private final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository;
  private final AccountStartSessionActivationProtectionRepository protectionRepository;
  private final CanonicalGameInstanceLaunchAssociationClient launchClient;
  private final OriginalStartSessionCurrentAttemptClient currentAttemptClient;
  private final TransactionTemplate ownerTransaction;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionActivationProtectionAcquisitionService(
      AccountStartSessionOperatorAuthorizationService issuer,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository,
      AccountStartSessionActivationProtectionRepository protectionRepository,
      CanonicalGameInstanceLaunchAssociationClient launchClient,
      OriginalStartSessionCurrentAttemptClient currentAttemptClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.issuer =
        Objects.requireNonNull(issuer, "current Account StartSession authority is required");
    this.participationRepository =
        Objects.requireNonNull(
            participationRepository, "World participation repository is required");
    this.attemptRepository =
        Objects.requireNonNull(
            attemptRepository, "original-attempt evidence repository is required");
    this.protectionRepository =
        Objects.requireNonNull(
            protectionRepository, "activation protection repository is required");
    this.launchClient =
        Objects.requireNonNull(launchClient, "Game Session launch reader is required");
    this.currentAttemptClient =
        Objects.requireNonNull(
            currentAttemptClient, "Game Session current-attempt reader is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    ownerTransaction = transaction(transactionManager);
    readbackTransaction = transaction(transactionManager);
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Acquires or returns the immutable first protection for the exact original request. */
  public AccountStartSessionActivationProtectionEvidence acquire(
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(input, "activation protection acquisition input is required");
    input.requireTargetNamespace(workloadNamespace);

    byte[] tupleBytes = input.originalPostAuthorizationTuple();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    StoredParticipation initiallyRetained = readCommittedParticipation(tupleBytes, input);

    OriginalStartSessionCurrentAttemptEvidence.Request attemptRequest =
        new OriginalStartSessionCurrentAttemptEvidence.Request(
            freshReadId(
                initiallyRetained.participationId(), initiallyRetained.gameSessionOwnerAttemptId()),
            workloadNamespace,
            tupleBytes,
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerFence());
    OriginalStartSessionCurrentAttemptEvidence.Result currentAttempt =
        readCurrentAttempt(attemptRequest, tuple);

    Request launchRequest = launchRequest(input);
    CanonicalGameInstanceLaunchAssociationReadEvidence.Result launchAssociation =
        readLaunchAssociation(launchRequest, input);

    AccountStartSessionActivationProtectionRequest request;
    try {
      request =
          AccountStartSessionActivationProtectionRequest.create(
              initiallyRetained.participationId(),
              initiallyRetained.participationFence(),
              launchAssociation,
              currentAttempt,
              input.worldActivationRequest());
    } catch (IllegalArgumentException invalid) {
      throw failedPrecondition(
          "Game Session owner observations differ from the original World request", invalid);
    }

    StoredProtection committed =
        issuer.withCurrentWorldReceivingParticipationCurrentness(
            tupleBytes,
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerFence(),
            capture -> {
              StoredParticipation exactParticipation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact committed original World participation is unavailable"));
              requireSameParticipation(initiallyRetained, exactParticipation);
              requireCommittedParticipation(exactParticipation);
              requireExactRetainedAttempt(exactParticipation, attemptRequest, currentAttempt);
              return protectionRepository.acquireOrReadExact(request, capture, exactParticipation);
            });

    requireNoAmbientTransaction();
    StoredProtection readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    protectionRepository
                        .findHistoricalExact(
                            committed.evidence().accountProtectionId(),
                            committed.evidence().accountProtectionFence())
                        .orElseThrow(
                            () ->
                                failedPrecondition(
                                    "Committed original activation protection is not readable"))),
            "Account activation protection readback transaction returned no value");
    if (!Arrays.equals(
        committed.evidence().canonicalBytes(), readback.evidence().canonicalBytes())) {
      throw failedPrecondition("Committed activation protection readback differs from its receipt");
    }
    return readback.evidence();
  }

  private StoredParticipation readCommittedParticipation(
      byte[] tupleBytes, AccountStartSessionActivationProtectionAcquisitionInput input) {
    return Objects.requireNonNull(
        ownerTransaction.execute(
            ignored -> {
              StoredParticipation participation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () ->
                              failedPrecondition(
                                  "Exact original World participation is unavailable"));
              requireTupleAndWorldBinding(participation, tupleBytes, input);
              requireCommittedParticipation(participation);
              return participation;
            }),
        "Account World participation transaction returned no value");
  }

  private void requireCommittedParticipation(StoredParticipation participation) {
    Optional<AccountStartSessionWorldParticipationRepository.StoredSettlement> settlement =
        participationRepository.findSettlementExact(
            participation.participationId(), participation.participationFence());
    if (settlement.isEmpty()
        || settlement.orElseThrow().outcome()
            != WorldStartSessionExecutionTerminal.Outcome.COMMITTED) {
      throw failedPrecondition("Original Account World participation is not committed");
    }
  }

  private void requireExactRetainedAttempt(
      StoredParticipation participation,
      OriginalStartSessionCurrentAttemptEvidence.Request expectedRequest,
      OriginalStartSessionCurrentAttemptEvidence.Result observed) {
    StoredEvidence retained =
        attemptRepository
            // World preparation is already COMMITTED, so its receiving participation is settled.
            // Read the immutable first witness without reopening that participation. Currentness
            // comes from the separate authenticated fresh read and Account's admission callback.
            .findHistoricalExact(participation, expectedRequest)
            .orElseThrow(
                () ->
                    failedPrecondition(
                        "Exact retained original Game Session attempt is unavailable"));
    var stored = retained.result();
    if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(stored.phaseState())
        || !stored.originalLeaseExpiresAt().equals(observed.originalLeaseExpiresAt())
        || !MessageDigest.isEqual(
            stored.accountRedemptionProjection(), observed.accountRedemptionProjection())
        || !sameAttemptRequest(stored.request(), observed.request())) {
      throw failedPrecondition(
          "Retained original Game Session attempt differs from its authenticated current observation");
    }
    try {
      var response =
          net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse
              .parseFrom(retained.originalResponseBytes());
      var canonical =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              stored.request(), response);
      if (!sameAttemptResult(stored, canonical)) {
        throw failedPrecondition("Retained original Game Session response bytes differ");
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw failedPrecondition(
          "Retained original Game Session response bytes are malformed", malformed);
    } catch (IllegalArgumentException malformed) {
      throw failedPrecondition(
          "Retained original Game Session response is not canonical", malformed);
    }
  }

  private OriginalStartSessionCurrentAttemptEvidence.Result readCurrentAttempt(
      OriginalStartSessionCurrentAttemptEvidence.Request request,
      StartSessionPostAuthorizationExecutionTuple tuple) {
    requireNoAmbientTransaction();
    OriginalStartSessionCurrentAttemptEvidence.Result observed;
    try {
      observed = currentAttemptClient.read(request);
    } catch (StatusRuntimeException failure) {
      throw dependencyFailure(
          failure, "Account original Game Session current-attempt dependency failed");
    }
    requireNoAmbientTransaction();
    if (observed == null) {
      throw failedPrecondition("Exact current original Game Session attempt is unavailable");
    }
    try {
      var response = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observed);
      var exact =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(request, response);
      if (!sameAttemptRequest(request, exact.request())
          || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(exact.phaseState())
          || !MessageDigest.isEqual(
              StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
              exact.accountRedemptionProjection())) {
        throw failedPrecondition(
            "Current original Game Session attempt differs from the original tuple");
      }
      return exact;
    } catch (IllegalArgumentException malformed) {
      throw failedPrecondition(
          "Current original Game Session attempt evidence is malformed", malformed);
    }
  }

  private CanonicalGameInstanceLaunchAssociationReadEvidence.Result readLaunchAssociation(
      Request request, AccountStartSessionActivationProtectionAcquisitionInput input) {
    requireNoAmbientTransaction();
    CanonicalGameInstanceLaunchAssociationReadEvidence.Result result;
    try {
      result = launchClient.read(request);
    } catch (StatusRuntimeException failure) {
      throw dependencyFailure(failure, "Account Game Session launch-association dependency failed");
    }
    requireNoAmbientTransaction();
    var preparing = input.worldActivationRequest().preparing();
    if (result == null
        || !request.equals(result.request())
        || result.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus.STARTING
        || !input.gameSessionOwnerAttemptId().equals(result.ownerAttemptId())
        || input.gameSessionOwnerFence() != result.ownerFence()
        || !preparing.request().canonicalGameInstanceId().equals(request.gameInstanceUuid())
        || !preparing.launchBinding().equals(result.launchBindingEvidence())
        || !preparing
            .request()
            .playableStateNamespaceId()
            .equals(result.playableStateNamespaceId())) {
      throw failedPrecondition(
          "Current Game Session launch association differs from exact World PREPARING evidence");
    }
    return result;
  }

  private Request launchRequest(AccountStartSessionActivationProtectionAcquisitionInput input) {
    var preparing = input.worldActivationRequest().preparing();
    var world = preparing.request();
    var descriptor = preparing.launchBinding().descriptor();
    var release = preparing.launchBinding().releaseAttestation();
    return new Request(
        freshReadId(
            input.worldActivationRequest().activationRequestId(), world.canonicalGameInstanceId()),
        workloadNamespace,
        world.canonicalTenantId(),
        world.worldSlug(),
        world.canonicalGameInstanceId(),
        world.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest(),
        new CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
            input.originalPostAuthorizationTuple(),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerFence()));
  }

  private void requireTupleAndWorldBinding(
      StoredParticipation participation,
      byte[] tupleBytes,
      AccountStartSessionActivationProtectionAcquisitionInput input) {
    var preparing = input.worldActivationRequest().preparing();
    var tuple = StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (!Arrays.equals(participation.originalPostAuthorizationTuple(), tupleBytes)
        || !workloadNamespace.equals(participation.targetNamespace())
        || !participation.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
        || !participation.gameSessionOwnerAttemptId().equals(input.gameSessionOwnerAttemptId())
        || participation.gameSessionOwnerFence() != input.gameSessionOwnerFence()
        || !participation
            .canonicalGameInstanceId()
            .equals(preparing.request().canonicalGameInstanceId())
        || !participation.canonicalTenantId().equals(preparing.request().canonicalTenantId())
        || !tuple.controlPlaneRequestId().equals(preparing.request().controlPlaneRequestId())
        || !workloadNamespace.equals(preparing.request().targetNamespace())) {
      throw failedPrecondition(
          "Original Account participation differs from the complete World activation identity");
    }
  }

  private void requireSameParticipation(StoredParticipation expected, StoredParticipation actual) {
    if (!expected.participationId().equals(actual.participationId())
        || expected.participationFence() != actual.participationFence()
        || !expected.controlPlaneRequestId().equals(actual.controlPlaneRequestId())
        || !Arrays.equals(
            expected.originalPostAuthorizationTuple(), actual.originalPostAuthorizationTuple())
        || !expected.canonicalGameInstanceId().equals(actual.canonicalGameInstanceId())
        || !expected.canonicalTenantId().equals(actual.canonicalTenantId())
        || !expected.gameSessionOwnerAttemptId().equals(actual.gameSessionOwnerAttemptId())
        || expected.gameSessionOwnerFence() != actual.gameSessionOwnerFence()
        || !expected.targetNamespace().equals(actual.targetNamespace())
        || !expected.sourceVector().equals(actual.sourceVector())) {
      throw failedPrecondition(
          "Committed original Account participation changed during acquisition");
    }
  }

  private void requireWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified World workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !peer.uri()
            .equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Owner reads must run outside an ambient Account transaction")
          .asRuntimeException();
    }
  }

  private static boolean sameAttemptRequest(
      OriginalStartSessionCurrentAttemptEvidence.Request left,
      OriginalStartSessionCurrentAttemptEvidence.Request right) {
    return left.targetNamespace().equals(right.targetNamespace())
        && Arrays.equals(
            left.canonicalPostAuthorizationTuple(), right.canonicalPostAuthorizationTuple())
        && left.expectedOwnerMutationId().equals(right.expectedOwnerMutationId())
        && left.expectedOwnerAttemptId().equals(right.expectedOwnerAttemptId())
        && left.expectedOwnerFence() == right.expectedOwnerFence();
  }

  private static boolean sameAttemptResult(
      OriginalStartSessionCurrentAttemptEvidence.Result left,
      OriginalStartSessionCurrentAttemptEvidence.Result right) {
    return sameAttemptRequest(left.request(), right.request())
        && left.originalLeaseExpiresAt().equals(right.originalLeaseExpiresAt())
        && Arrays.equals(left.accountRedemptionProjection(), right.accountRedemptionProjection());
  }

  private static UUID freshReadId(UUID... reserved) {
    UUID readId;
    do {
      readId = UUID.randomUUID();
    } while (Arrays.asList(reserved).contains(readId));
    return readId;
  }

  private static TransactionTemplate transaction(PlatformTransactionManager manager) {
    TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(manager));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setReadOnly(false);
    return template;
  }

  private static RuntimeException failedPrecondition(String message) {
    return Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException();
  }

  private static RuntimeException failedPrecondition(String message, Throwable cause) {
    return Status.FAILED_PRECONDITION
        .withDescription(message)
        .withCause(cause)
        .asRuntimeException();
  }

  private static RuntimeException dependencyFailure(
      StatusRuntimeException failure, String description) {
    Status.Code code = failure.getStatus().getCode();
    Status status =
        switch (code) {
          case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN ->
              Status.fromCode(code);
          default -> Status.UNAVAILABLE;
        };
    return status.withDescription(description).asRuntimeException();
  }
}
