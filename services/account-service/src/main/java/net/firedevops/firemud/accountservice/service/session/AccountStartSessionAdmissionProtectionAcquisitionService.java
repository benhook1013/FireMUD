package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionAdmissionProtectionRepository;
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
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account composition that retains exact original StartSession protection through the
 * original Game Session admission boundary.
 *
 * <p>Game Session is read outside Account SQL on every attempt. Account then rechecks the live
 * original actor, JTI, reference, current authority and captured sources in its existing
 * currentness callback before it creates or reads the immutable protection. This component does not
 * issue or renew authorization, settle/release protection, or perform a remote call under SQL.
 */
public final class AccountStartSessionAdmissionProtectionAcquisitionService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final AccountStartSessionOperatorAuthorizationService issuer;
  private final AccountStartSessionWorldParticipationRepository participationRepository;
  private final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository;
  private final AccountStartSessionAdmissionProtectionRepository protectionRepository;
  private final OriginalStartSessionCurrentAttemptClient gameSessionClient;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionAdmissionProtectionAcquisitionService(
      AccountStartSessionOperatorAuthorizationService issuer,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptEvidenceRepository,
      AccountStartSessionAdmissionProtectionRepository protectionRepository,
      OriginalStartSessionCurrentAttemptClient gameSessionClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.issuer =
        Objects.requireNonNull(issuer, "original Account StartSession issuer is required");
    this.participationRepository =
        Objects.requireNonNull(
            participationRepository, "World participation repository is required");
    this.attemptEvidenceRepository =
        Objects.requireNonNull(
            attemptEvidenceRepository, "original-attempt evidence repository is required");
    this.protectionRepository =
        Objects.requireNonNull(protectionRepository, "admission protection repository is required");
    this.gameSessionClient =
        Objects.requireNonNull(
            gameSessionClient, "Game Session current-attempt client is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    readbackTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    readbackTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readbackTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    readbackTransaction.setReadOnly(false);
  }

  /**
   * Acquires and reads back the exact immutable protection. An unknown commit is propagated for a
   * later retry with this same original tuple; each retry first obtains fresh Game Session and
   * Account currentness evidence and can only read the existing protection identity.
   */
  public AccountStartSessionAdmissionProtectionEvidence acquire(AcquisitionRequest request) {
    requireGameSessionPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "admission protection acquisition request is required");
    Objects.requireNonNull(request.worldHoldIdentity(), "original World hold identity is required");

    byte[] tupleBytes = request.originalPostAuthorizationTuple();
    StartSessionPostAuthorizationExecutionTuple tuple = decodeOriginalTuple(tupleBytes);
    requireOriginalBinding(tuple, request);
    Request currentAttemptRequest =
        new Request(
            freshReadId(tuple, request),
            workloadNamespace,
            tupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerFence());

    // Every acquisition and exact retry observes the original owner before opening Account SQL.
    requireNoAmbientTransaction();
    Result observed = gameSessionClient.read(currentAttemptRequest);
    requireNoAmbientTransaction();
    Result exactObservation = exactObservation(currentAttemptRequest, observed, tuple);

    AccountStartSessionAdmissionProtectionEvidence committed =
        issuer.withCurrentGameSessionAdmissionProtectionCurrentness(
            tupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            capture -> {
              StoredParticipation historicalParticipation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () -> denied("Exact original World participation is unavailable"));
              requireParticipationBinding(request, tuple, historicalParticipation);

              StoredEvidence priorObservation =
                  attemptEvidenceRepository
                      .findHistoricalExact(historicalParticipation, currentAttemptRequest)
                      .orElseThrow(
                          () -> denied("Exact original Game Session observation is unavailable"));
              requireUnchangedObservation(
                  currentAttemptRequest,
                  exactObservation,
                  historicalParticipation,
                  priorObservation);

              AccountStartSessionAdmissionProtectionRequest exactRequest =
                  AccountStartSessionAdmissionProtectionRequest.create(
                      tupleBytes,
                      exactObservation.accountRedemptionProjection(),
                      request.gameSessionOwnerMutationId(),
                      request.gameSessionOwnerAttemptId(),
                      request.gameSessionOwnerFence(),
                      exactObservation.originalLeaseExpiresAt(),
                      historicalParticipation.participationId(),
                      historicalParticipation.participationFence(),
                      request.worldHoldIdentity());
              return protectionRepository.createOrReadExact(exactRequest, capture);
            });

    requireNoAmbientTransaction();
    AccountStartSessionAdmissionProtectionEvidence readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    protectionRepository
                        .findCurrentExact(committed.request())
                        .orElseThrow(
                            () ->
                                denied(
                                    "Committed exact Account admission protection is not readable"))),
            "Account admission protection readback transaction returned no result");

    if (!MessageDigest.isEqual(committed.canonicalBytes(), readback.canonicalBytes())) {
      throw denied("Committed Account admission protection readback differs");
    }
    return readback;
  }

  private Result exactObservation(
      Request expected, Result observed, StartSessionPostAuthorizationExecutionTuple tuple) {
    if (observed == null) {
      throw denied("Exact current original Game Session attempt evidence is unavailable");
    }
    try {
      var canonicalResponse =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observed);
      Result exact =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              expected, canonicalResponse);
      if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(exact.phaseState())
          || !sameRequest(expected, exact.request())
          || !MessageDigest.isEqual(
              expectedAccountRedemptionProjection(tuple), exact.accountRedemptionProjection())) {
        throw denied(
            "Current Game Session attempt or full Account redemption projection differs from the original issuer");
      }
      return exact;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact current original Game Session attempt evidence is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static void requireUnchangedObservation(
      Request expectedRequest,
      Result observed,
      StoredParticipation participation,
      StoredEvidence retained) {
    if (retained == null
        || !participation.participationId().equals(retained.participationId())
        || !expectedRequest.expectedOwnerMutationId().equals(retained.gameSessionOwnerMutationId())
        || !sameRequest(expectedRequest, retained.result().request())
        || !sameResult(observed, retained.result())
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            retained.result().phaseState())
        || !retained.originalLeaseExpiresAt().equals(observed.originalLeaseExpiresAt())) {
      throw denied("Current original Game Session attempt differs from retained World evidence");
    }
    try {
      var response =
          net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse
              .parseFrom(retained.originalResponseBytes());
      Result decoded =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              retained.result().request(), response);
      if (!sameResult(retained.result(), decoded)
          || !Arrays.equals(
              retained.originalResponseBytes(),
              OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(decoded)
                  .toByteArray())) {
        throw denied("Retained original Game Session response bytes are not canonical");
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Retained original Game Session response is malformed")
          .withCause(malformed)
          .asRuntimeException();
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Retained original Game Session response is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static void requireParticipationBinding(
      AcquisitionRequest request,
      StartSessionPostAuthorizationExecutionTuple tuple,
      StoredParticipation participation) {
    if (!tuple.controlPlaneRequestId().equals(participation.controlPlaneRequestId())
        || !MessageDigest.isEqual(
            tuple.canonicalBytes(), participation.originalPostAuthorizationTuple())
        || !request
            .worldHoldIdentity()
            .request()
            .targetNamespace()
            .equals(participation.targetNamespace())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(participation.canonicalTenantId())
        || !request
            .worldHoldIdentity()
            .request()
            .canonicalGameInstanceId()
            .equals(participation.canonicalGameInstanceId())
        || !request.gameSessionOwnerAttemptId().equals(participation.gameSessionOwnerAttemptId())
        || request.gameSessionOwnerFence() != participation.gameSessionOwnerFence()) {
      throw denied("Original Account World participation binding differs");
    }
  }

  private void requireOriginalBinding(
      StartSessionPostAuthorizationExecutionTuple tuple, AcquisitionRequest request) {
    if (!"StartSession".equals(tuple.preAuthorizationTuple().actionFamily())
        || !"game-session-service".equals(tuple.preAuthorizationTuple().targetOwner())
        || !workloadNamespace.equals(
            tuple.preAuthorizationTuple().action().scope().targetNamespace())
        || !workloadNamespace.equals(request.worldHoldIdentity().request().targetNamespace())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(request.worldHoldIdentity().request().canonicalTenantId())) {
      throw denied("Exact original human StartSession tuple and World hold are required");
    }
    requireNonnil(request.gameSessionOwnerMutationId(), "Game Session owner mutation ID");
    requireNonnil(request.gameSessionOwnerAttemptId(), "Game Session owner attempt ID");
    requirePositive(request.gameSessionOwnerFence(), "Game Session owner fence");
  }

  private static StartSessionPostAuthorizationExecutionTuple decodeOriginalTuple(
      byte[] tupleBytes) {
    if (tupleBytes == null) throw denied("Complete original StartSession tuple is required");
    try {
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
      if (!Arrays.equals(tupleBytes, tuple.canonicalBytes())) {
        throw denied("Original StartSession tuple is not exact canonical bytes");
      }
      return tuple;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Complete canonical original StartSession tuple required")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static byte[] expectedAccountRedemptionProjection(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple);
  }

  private static boolean sameResult(Result expected, Result actual) {
    return expected.originalLeaseExpiresAt().equals(actual.originalLeaseExpiresAt())
        && expected.phaseState().equals(actual.phaseState())
        && sameRequest(expected.request(), actual.request())
        && Arrays.equals(
            expected.accountRedemptionProjection(), actual.accountRedemptionProjection());
  }

  private static boolean sameRequest(Request expected, Request actual) {
    return expected.targetNamespace().equals(actual.targetNamespace())
        && Arrays.equals(
            expected.canonicalPostAuthorizationTuple(), actual.canonicalPostAuthorizationTuple())
        && expected.expectedOwnerAttemptId().equals(actual.expectedOwnerAttemptId())
        && expected.expectedOwnerMutationId().equals(actual.expectedOwnerMutationId())
        && expected.expectedOwnerFence() == actual.expectedOwnerFence();
  }

  private static UUID freshReadId(
      StartSessionPostAuthorizationExecutionTuple tuple, AcquisitionRequest request) {
    Set<UUID> occupied = new HashSet<>();
    occupied.addAll(
        List.of(
            tuple.reservationOwnerId(),
            tuple.preAuthorizationTuple().actor().accountId(),
            tuple.preAuthorizationTuple().action().scope().tenantId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId(),
            request.worldHoldIdentity().holdId(),
            StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
                .issuanceOperationId()));
    UUID result;
    do {
      result = UUID.randomUUID();
    } while (occupied.contains(result));
    return result;
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    String expectedUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !expectedUri.equals(peer.uri())) {
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
          .withDescription(
              "Admission protection acquisition requires owner currentness outside Account SQL")
          .asRuntimeException();
    }
  }

  private static void requireNonnil(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw denied(name + " must be a canonical non-nil UUID");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) throw denied(name + " must be positive");
  }

  private static StatusRuntimeException denied(String description) {
    return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
  }

  /** Raw bounded DTO; peer and transaction checks precede decoding or dependency calls. */
  public record AcquisitionRequest(
      byte[] originalPostAuthorizationTuple,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      WorldCanonicalInitialAdmissionHold.HoldIdentity worldHoldIdentity) {
    public AcquisitionRequest {
      originalPostAuthorizationTuple =
          originalPostAuthorizationTuple == null ? null : originalPostAuthorizationTuple.clone();
    }

    @Override
    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple == null ? null : originalPostAuthorizationTuple.clone();
    }
  }
}
