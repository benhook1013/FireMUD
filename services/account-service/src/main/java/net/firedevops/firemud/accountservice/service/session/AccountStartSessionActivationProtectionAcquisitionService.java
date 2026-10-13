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
import net.firedevops.firemud.accountservice.repository.AccountStartSessionActivationProtectionRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadClient;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered Account composition that acquires the distinct source protection for one exact
 * original StartSession World activation.
 *
 * <p>Each call authenticates the same-namespace World workload, obtains fresh Game Session
 * current-attempt and original STARTING-launch readbacks outside Account SQL, and then revalidates
 * the original Account authority and locked source capture through its existing issuer callback.
 * V129 participation, its COMMITTED preparation terminal, and the retained V131 owner observation
 * supply the historical binding; caller-provided lease, projection, or participation coordinates
 * are never trusted. This composition performs acquisition and immutable readback only: it does not
 * settle, release, renew, or re-arm protection, nor register a transport endpoint.
 */
public final class AccountStartSessionActivationProtectionAcquisitionService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final AccountStartSessionOperatorAuthorizationService issuer;
  private final AccountStartSessionWorldParticipationRepository participationRepository;
  private final AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository;
  private final AccountStartSessionActivationProtectionRepository protectionRepository;
  private final OriginalStartSessionCurrentAttemptClient currentAttemptClient;
  private final OriginalStartSessionLaunchReadClient launchReadClient;
  private final TransactionTemplate readbackTransaction;
  private final String workloadNamespace;

  public AccountStartSessionActivationProtectionAcquisitionService(
      AccountStartSessionOperatorAuthorizationService issuer,
      AccountStartSessionWorldParticipationRepository participationRepository,
      AccountStartSessionWorldOriginalAttemptEvidenceRepository attemptRepository,
      AccountStartSessionActivationProtectionRepository protectionRepository,
      OriginalStartSessionCurrentAttemptClient currentAttemptClient,
      OriginalStartSessionLaunchReadClient launchReadClient,
      PlatformTransactionManager transactionManager,
      String workloadNamespace) {
    this.issuer =
        Objects.requireNonNull(issuer, "original Account StartSession issuer is required");
    this.participationRepository =
        Objects.requireNonNull(
            participationRepository, "World participation repository is required");
    this.attemptRepository =
        Objects.requireNonNull(
            attemptRepository, "original-attempt evidence repository is required");
    this.protectionRepository =
        Objects.requireNonNull(
            protectionRepository, "activation protection repository is required");
    this.currentAttemptClient =
        Objects.requireNonNull(
            currentAttemptClient, "Game Session current-attempt client is required");
    this.launchReadClient =
        Objects.requireNonNull(launchReadClient, "Game Session launch-read client is required");
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
   * Acquires or recovers the first immutable protection receipt, then independently reads it back.
   * A retry may use new owner read correlation IDs but cannot change the stable activation
   * operation, original source capture, owner attempt, or finite lease.
   */
  public AccountStartSessionActivationProtectionEvidence acquire(AcquisitionRequest request) {
    requireWorldPeer();
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "activation protection acquisition request is required");

    byte[] tupleBytes = request.originalPostAuthorizationTuple();
    StartSessionPostAuthorizationExecutionTuple tuple = decodeOriginalTuple(tupleBytes);
    WorldCanonicalInstanceActivation.Request activation = request.worldActivationRequest();
    if (activation == null) throw denied("Exact World PREPARING activation statement is required");
    requireOriginalBinding(tuple, request, activation);
    requireNonnil(request.gameSessionOwnerMutationId(), "Game Session owner mutation ID");
    requireNonnil(request.gameSessionOwnerAttemptId(), "Game Session owner attempt ID");
    requirePositive(request.gameSessionOwnerFence(), "Game Session owner fence");

    Request currentAttemptRequest =
        new Request(
            freshReadId(tuple, activation, request),
            workloadNamespace,
            tupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerFence());

    // Both owner observations are independent and happen before opening Account SQL. They are
    // evidence inputs, not authority; the currentness callback below remains the local authority.
    requireNoAmbientTransaction();
    Result currentAttempt = currentAttemptClient.read(currentAttemptRequest);
    requireNoAmbientTransaction();
    Result exactCurrentAttempt = exactCurrentAttempt(currentAttemptRequest, currentAttempt, tuple);

    UUID launchReadId =
        freshReadId(tuple, activation, request, currentAttemptRequest.readRequestId());
    OriginalStartSessionLaunchReadEvidence.Request launchRequest =
        new OriginalStartSessionLaunchReadEvidence.Request(
            launchSelector(activation.preparing(), launchReadId), tupleBytes);
    requireNoAmbientTransaction();
    OriginalStartSessionLaunchReadEvidence.Result observedLaunch =
        launchReadClient.read(launchRequest);
    requireNoAmbientTransaction();
    OriginalStartSessionLaunchReadEvidence.Result exactLaunch =
        exactLaunch(launchRequest, observedLaunch);

    AccountStartSessionActivationProtectionEvidence committed =
        issuer.withCurrentWorldReceivingParticipationCurrentness(
            tupleBytes,
            request.gameSessionOwnerAttemptId(),
            request.gameSessionOwnerFence(),
            capture -> {
              StoredParticipation participation =
                  participationRepository
                      .findHistoricalForOriginalTuple(tupleBytes)
                      .orElseThrow(
                          () ->
                              denied("Exact original COMMITTED World preparation is unavailable"));
              requireParticipationBinding(tuple, request, activation, participation);

              var settlement =
                  participationRepository
                      .findSettlementExact(
                          participation.participationId(), participation.participationFence())
                      .orElseThrow(
                          () -> denied("Original World preparation terminal is unavailable"));
              if (settlement.outcome() != WorldStartSessionExecutionTerminal.Outcome.COMMITTED) {
                throw denied("Original World preparation is not COMMITTED");
              }

              StoredEvidence retained =
                  attemptRepository
                      .findHistoricalExact(participation, currentAttemptRequest)
                      .orElseThrow(
                          () ->
                              denied(
                                  "Exact original Game Session current-attempt evidence is unavailable"));
              requireUnchangedAttempt(
                  participation, currentAttemptRequest, tuple, exactCurrentAttempt, retained);

              AccountStartSessionActivationProtectionRequest exactRequest =
                  AccountStartSessionActivationProtectionRequest.create(
                      tupleBytes,
                      exactCurrentAttempt.accountRedemptionProjection(),
                      request.gameSessionOwnerMutationId(),
                      request.gameSessionOwnerAttemptId(),
                      request.gameSessionOwnerFence(),
                      exactCurrentAttempt.originalLeaseExpiresAt(),
                      participation.participationId(),
                      participation.participationFence(),
                      exactLaunch,
                      activation);
              return protectionRepository.createOrReadExact(exactRequest, capture);
            });

    requireNoAmbientTransaction();
    AccountStartSessionActivationProtectionEvidence readback =
        Objects.requireNonNull(
            readbackTransaction.execute(
                ignored ->
                    protectionRepository
                        .findExact(committed.request())
                        .orElseThrow(
                            () ->
                                denied(
                                    "Committed exact Account activation protection is not readable"))),
            "Account activation protection readback transaction returned no result");
    if (!MessageDigest.isEqual(committed.canonicalBytes(), readback.canonicalBytes())) {
      throw denied("Committed Account activation protection readback differs");
    }
    return readback;
  }

  private Result exactCurrentAttempt(
      Request expected, Result observed, StartSessionPostAuthorizationExecutionTuple tuple) {
    if (observed == null) {
      throw denied("Exact current original Game Session attempt evidence is unavailable");
    }
    try {
      Result exact =
          OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
              expected, OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observed));
      if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(exact.phaseState())
          || !sameRequest(expected, exact.request(), true)
          || !MessageDigest.isEqual(
              StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
              exact.accountRedemptionProjection())) {
        throw denied("Current Game Session attempt or Account redemption projection differs");
      }
      return exact;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact current original Game Session attempt evidence is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static OriginalStartSessionLaunchReadEvidence.Result exactLaunch(
      OriginalStartSessionLaunchReadEvidence.Request expected,
      OriginalStartSessionLaunchReadEvidence.Result observed) {
    if (observed == null) {
      throw denied("Exact original Game Session STARTING launch read is unavailable");
    }
    try {
      OriginalStartSessionLaunchReadEvidence.Result exact =
          OriginalStartSessionLaunchReadGrpcCodec.fromResponse(
              expected, OriginalStartSessionLaunchReadGrpcCodec.toResponse(observed));
      if (exact.launch().currentGameInstanceStatus()
          != CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
              .STARTING) {
        throw denied("Original Game Session launch is not STARTING");
      }
      return exact;
    } catch (IllegalArgumentException malformed) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact original Game Session launch read is malformed")
          .withCause(malformed)
          .asRuntimeException();
    }
  }

  private static void requireUnchangedAttempt(
      StoredParticipation participation,
      Request expectedRequest,
      StartSessionPostAuthorizationExecutionTuple tuple,
      Result observed,
      StoredEvidence retained) {
    if (retained == null
        || !participation.participationId().equals(retained.participationId())
        || !expectedRequest.expectedOwnerMutationId().equals(retained.gameSessionOwnerMutationId())
        || !sameRequest(expectedRequest, retained.result().request(), false)
        || !sameResult(observed, retained.result())
        || !OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            retained.result().phaseState())
        || !retained.originalLeaseExpiresAt().equals(observed.originalLeaseExpiresAt())
        || !MessageDigest.isEqual(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            retained.result().accountRedemptionProjection())) {
      throw denied("Current Game Session attempt or finite original lease differs from V131");
    }
  }

  private static void requireParticipationBinding(
      StartSessionPostAuthorizationExecutionTuple tuple,
      AcquisitionRequest request,
      WorldCanonicalInstanceActivation.Request activation,
      StoredParticipation participation) {
    if (!tuple.controlPlaneRequestId().equals(participation.controlPlaneRequestId())
        || !Arrays.equals(tuple.canonicalBytes(), participation.originalPostAuthorizationTuple())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .targetNamespace()
            .equals(participation.targetNamespace())
        || !tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(participation.canonicalTenantId())
        || !activation.canonicalGameInstanceId().equals(participation.canonicalGameInstanceId())
        || !request.gameSessionOwnerAttemptId().equals(participation.gameSessionOwnerAttemptId())
        || request.gameSessionOwnerFence() != participation.gameSessionOwnerFence()) {
      throw denied(
          "Historical World participation differs from the original tuple and owner attempt");
    }
  }

  private void requireOriginalBinding(
      StartSessionPostAuthorizationExecutionTuple tuple,
      AcquisitionRequest request,
      WorldCanonicalInstanceActivation.Request activation) {
    var pre = tuple.preAuthorizationTuple();
    var scope = pre.action().scope();
    var preparing = activation.preparing();
    var world = preparing.request();
    if (!"StartSession".equals(pre.actionFamily())
        || !"game-session-service".equals(pre.targetOwner())
        || !workloadNamespace.equals(scope.targetNamespace())
        || !scope.targetNamespace().equals(world.targetNamespace())
        || !scope.tenantId().equals(world.canonicalTenantId())
        || !tuple.controlPlaneRequestId().equals(world.controlPlaneRequestId())
        || !"PREPARING".equals(preparing.lifecycleStatus())
        || !activation.canonicalGameInstanceId().equals(world.canonicalGameInstanceId())
        || !tuple
            .controlPlaneRequestId()
            .equals(activation.preparing().launchBinding().descriptor().controlPlaneRequestId())) {
      throw denied("Exact original StartSession tuple and World PREPARING identity required");
    }
    requireNonnil(request.gameSessionOwnerMutationId(), "Game Session owner mutation ID");
    requireNonnil(request.gameSessionOwnerAttemptId(), "Game Session owner attempt ID");
    requirePositive(request.gameSessionOwnerFence(), "Game Session owner fence");
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request launchSelector(
      WorldCanonicalInstanceLifecycleEvidence preparing, UUID readRequestId) {
    var request = preparing.request();
    var descriptor = preparing.launchBinding().descriptor();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        readRequestId,
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.canonicalGameInstanceId(),
        request.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        request.expectedDescriptorRequestDigest(),
        request.expectedDescriptorResultDigest(),
        request.expectedReleaseAttestationDigest());
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

  private static UUID freshReadId(
      StartSessionPostAuthorizationExecutionTuple tuple,
      WorldCanonicalInstanceActivation.Request activation,
      AcquisitionRequest request,
      UUID... additionalOccupied) {
    Set<UUID> occupied = new HashSet<>();
    occupied.addAll(
        List.of(
            tuple.reservationOwnerId(),
            tuple.preAuthorizationTuple().actor().accountId(),
            tuple.preAuthorizationTuple().action().scope().tenantId(),
            activation.activationRequestId(),
            request.gameSessionOwnerMutationId(),
            request.gameSessionOwnerAttemptId()));
    occupied.addAll(Arrays.asList(additionalOccupied));
    UUID result;
    do {
      result = UUID.randomUUID();
    } while (occupied.contains(result));
    return result;
  }

  private void requireWorldPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified World workload identity required")
          .asRuntimeException();
    }
    String expectedUri =
        "spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !workloadNamespace.equals(peer.namespace())
        || !expectedUri.equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace World workload without end-user context required")
          .asRuntimeException();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription(
              "Activation protection acquisition requires owner reads outside Account SQL")
          .asRuntimeException();
    }
  }

  private static boolean sameResult(Result left, Result right) {
    return left.originalLeaseExpiresAt().equals(right.originalLeaseExpiresAt())
        && sameRequest(left.request(), right.request(), false)
        && Arrays.equals(left.accountRedemptionProjection(), right.accountRedemptionProjection());
  }

  private static boolean sameRequest(Request left, Request right, boolean compareReadId) {
    return (!compareReadId || left.readRequestId().equals(right.readRequestId()))
        && left.targetNamespace().equals(right.targetNamespace())
        && Arrays.equals(
            left.canonicalPostAuthorizationTuple(), right.canonicalPostAuthorizationTuple())
        && left.expectedOwnerAttemptId().equals(right.expectedOwnerAttemptId())
        && left.expectedOwnerMutationId().equals(right.expectedOwnerMutationId())
        && left.expectedOwnerFence() == right.expectedOwnerFence();
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

  /**
   * Raw caller statement; owner lease, projection, participation and capture are never supplied.
   */
  public record AcquisitionRequest(
      byte[] originalPostAuthorizationTuple,
      UUID gameSessionOwnerMutationId,
      UUID gameSessionOwnerAttemptId,
      long gameSessionOwnerFence,
      WorldCanonicalInstanceActivation.Request worldActivationRequest) {
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
