package net.firedevops.firemud.accountservice.service.session;

import com.google.protobuf.Message;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountStartSessionWorldParticipationAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquiredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone unregistered receiver authenticating the exact World peer before request parsing. */
public final class AccountStartSessionWorldParticipationAcquisitionGrpcService
    extends AccountStartSessionWorldParticipationAcquisitionServiceGrpc
        .AccountStartSessionWorldParticipationAcquisitionServiceImplBase {
  private static final int MAX_REQUEST_BYTES =
      WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
          + StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
          + 2 * 1024;
  private static final int MAX_RESPONSE_BYTES =
      MAX_REQUEST_BYTES
          + OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES
          + 2 * 1024;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private final AccountStartSessionWorldParticipationAcquisitionService owner;
  private final String namespace;

  public AccountStartSessionWorldParticipationAcquisitionGrpcService(
      AccountStartSessionWorldParticipationAcquisitionService owner, String namespace) {
    this.owner = Objects.requireNonNull(owner, "World participation acquisition owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void acquireStartSessionWorldParticipation(
      AcquireStartSessionWorldParticipationRequest request,
      StreamObserver<AcquireStartSessionWorldParticipationResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/world-management-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !expectedPeer.equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
      return;
    }
    if (request == null || request.getSerializedSize() > MAX_REQUEST_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    final AcquisitionRequest acquisition;
    try {
      acquisition = decodeRequest(request, namespace);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      AcquiredParticipation acquired = owner.acquire(acquisition);
      AcquireStartSessionWorldParticipationResponse response =
          toResponse(request, namespace, acquired);
      observer.onNext(response);
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code != Status.Code.UNAUTHENTICATED
          && code != Status.Code.PERMISSION_DENIED
          && code != Status.Code.INVALID_ARGUMENT
          && code != Status.Code.FAILED_PRECONDITION) {
        code = Status.Code.UNAVAILABLE;
      }
      observer.onError(Status.fromCode(code).asRuntimeException());
    } catch (IllegalArgumentException invalidEvidence) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }

  private static AcquisitionRequest decodeRequest(
      AcquireStartSessionWorldParticipationRequest request, String expectedNamespace) {
    requireClosed(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || request.getSchemaVersion() != 1
        || !GrpcPeerIdentity.isValidNamespace(expectedNamespace)
        || !expectedNamespace.equals(request.getTargetNamespace())) {
      throw new IllegalArgumentException("Unsupported or oversized World-participation request");
    }
    UUID acquisitionId = canonicalUuid(request.getAcquisitionRequestId(), "acquisitionRequestId");
    UUID mutationId = canonicalUuid(request.getGameSessionOwnerMutationId(), "ownerMutationId");
    UUID attemptId = canonicalUuid(request.getGameSessionOwnerAttemptId(), "ownerAttemptId");
    UUID instanceId = canonicalUuid(request.getCanonicalGameInstanceId(), "gameInstanceId");
    if (request.getGameSessionOwnerFence() <= 0L) {
      throw new IllegalArgumentException("Game Session owner fence must be positive");
    }
    byte[] tupleBytes = request.getOriginalPostAuthorizationTuple().toByteArray();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    if (tupleBytes.length == 0
        || tupleBytes.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES
        || !MessageDigest.isEqual(tupleBytes, tuple.canonicalBytes())
        || !StartSessionOperatorAction.ACTION_FAMILY.equals(
            tuple.preAuthorizationTuple().actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(
            tuple.preAuthorizationTuple().targetOwner())
        || !expectedNamespace.equals(
            tuple.preAuthorizationTuple().action().scope().targetNamespace())) {
      throw new IllegalArgumentException("Exact canonical original StartSession tuple required");
    }
    Set<UUID> occupied = new HashSet<>();
    occupied.add(mutationId);
    occupied.add(attemptId);
    occupied.add(instanceId);
    occupied.add(tuple.reservationOwnerId());
    occupied.add(tuple.preAuthorizationTuple().actor().accountId());
    occupied.add(tuple.preAuthorizationTuple().action().scope().tenantId());
    occupied.add(
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
            .issuanceOperationId());
    if (tuple.controlPlaneRequestId().equals(acquisitionId.toString())
        || occupied.contains(acquisitionId)) {
      throw new IllegalArgumentException(
          "Acquisition correlation must be independent of retained operation identities");
    }
    String preparation = request.getPreparationInputJson();
    if (preparation.isBlank()
        || !StandardCharsets.UTF_8.newEncoder().canEncode(preparation)
        || preparation.getBytes(StandardCharsets.UTF_8).length
            > WorldStartSessionExecutionTerminal.MAX_CANONICAL_BYTES
        || !SHA256.matcher(request.getPreparationInputDigest()).matches()
        || !request
            .getPreparationInputDigest()
            .equals(digestPrefixed(preparation.getBytes(StandardCharsets.UTF_8)))) {
      throw new IllegalArgumentException(
          "Exact bounded World preparation input and digest required");
    }
    var canonical =
        AcquireStartSessionWorldParticipationRequest.newBuilder()
            .setSchemaVersion(1)
            .setAcquisitionRequestId(acquisitionId.toString())
            .setTargetNamespace(expectedNamespace)
            .setOriginalPostAuthorizationTuple(request.getOriginalPostAuthorizationTuple())
            .setGameSessionOwnerMutationId(mutationId.toString())
            .setGameSessionOwnerAttemptId(attemptId.toString())
            .setGameSessionOwnerFence(request.getGameSessionOwnerFence())
            .setCanonicalGameInstanceId(instanceId.toString())
            .setPreparationInputJson(preparation)
            .setPreparationInputDigest(request.getPreparationInputDigest())
            .build();
    if (!canonical.equals(request)) {
      throw new IllegalArgumentException("World-participation request is not canonical");
    }
    return new AcquisitionRequest(
        tupleBytes,
        mutationId,
        attemptId,
        request.getGameSessionOwnerFence(),
        instanceId,
        preparation,
        request.getPreparationInputDigest());
  }

  private static AcquireStartSessionWorldParticipationResponse toResponse(
      AcquireStartSessionWorldParticipationRequest request,
      String namespace,
      AcquiredParticipation acquired) {
    Objects.requireNonNull(acquired, "Acquired Account participation is required");
    StoredParticipation participation = acquired.participation();
    StoredEvidence evidence = acquired.originalAttemptEvidence();
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            request.getOriginalPostAuthorizationTuple().toByteArray());
    if (participation.participationFence() <= 0L
        || !namespace.equals(participation.targetNamespace())
        || !participation.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
        || !Arrays.equals(
            participation.originalPostAuthorizationTuple(),
            request.getOriginalPostAuthorizationTuple().toByteArray())
        || !participation
            .canonicalTenantId()
            .equals(tuple.preAuthorizationTuple().action().scope().tenantId())
        || !participation
            .canonicalGameInstanceId()
            .toString()
            .equals(request.getCanonicalGameInstanceId())
        || !participation
            .gameSessionOwnerAttemptId()
            .toString()
            .equals(request.getGameSessionOwnerAttemptId())
        || participation.gameSessionOwnerFence() != request.getGameSessionOwnerFence()
        || !participation.preparationInputJson().equals(request.getPreparationInputJson())
        || !participation.preparationInputDigest().equals(request.getPreparationInputDigest())
        || !participation.participationId().equals(evidence.participationId())
        || !evidence
            .gameSessionOwnerMutationId()
            .toString()
            .equals(request.getGameSessionOwnerMutationId())
        || !SHA256.matcher(evidence.originalResponseDigest()).matches()
        || !evidence
            .originalResponseDigest()
            .equals(digestPrefixed(evidence.originalResponseBytes()))) {
      throw new IllegalArgumentException(
          "Acquired Account participation changed its request binding");
    }
    ReadOriginalStartSessionCurrentAttemptResponse observation;
    try {
      byte[] retained = evidence.originalResponseBytes();
      if (retained.length == 0
          || retained.length
              > OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.MAX_RESPONSE_BYTES) {
        throw new IllegalArgumentException("Retained original-attempt evidence is oversized");
      }
      observation = ReadOriginalStartSessionCurrentAttemptResponse.parseFrom(retained);
    } catch (com.google.protobuf.InvalidProtocolBufferException malformed) {
      throw new IllegalArgumentException(
          "Retained original-attempt evidence is malformed", malformed);
    }
    var observedRequest =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromRequest(observation.getRequest());
    var exactObservation =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
            observedRequest, observation);
    if (!OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE.equals(
            exactObservation.phaseState())
        || !namespace.equals(observedRequest.targetNamespace())
        || !Arrays.equals(
            request.getOriginalPostAuthorizationTuple().toByteArray(),
            observedRequest.canonicalPostAuthorizationTuple())
        || !request
            .getGameSessionOwnerMutationId()
            .equals(observedRequest.expectedOwnerMutationId().toString())
        || !request
            .getGameSessionOwnerAttemptId()
            .equals(observedRequest.expectedOwnerAttemptId().toString())
        || request.getGameSessionOwnerFence() != observedRequest.expectedOwnerFence()
        || !MessageDigest.isEqual(
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            exactObservation.accountRedemptionProjection())
        || !evidence.result().request().equals(exactObservation.request())
        || !evidence
            .result()
            .originalLeaseExpiresAt()
            .equals(exactObservation.originalLeaseExpiresAt())
        || !MessageDigest.isEqual(
            evidence.result().accountRedemptionProjection(),
            exactObservation.accountRedemptionProjection())
        || !evidence.originalLeaseExpiresAt().equals(exactObservation.originalLeaseExpiresAt())
        || !Arrays.equals(
            evidence.originalResponseBytes(),
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(exactObservation)
                .toByteArray())) {
      throw new IllegalArgumentException("Retained original-attempt evidence changed its binding");
    }
    var response =
        AcquireStartSessionWorldParticipationResponse.newBuilder()
            .setRequest(request)
            .setAccountWorldParticipationId(participation.participationId().toString())
            .setAccountWorldParticipationFence(participation.participationFence())
            .setOriginalAttemptEvidence(observation)
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("World-participation response exceeds its wire budget");
    }
    return response;
  }

  private static void requireClosed(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "World-participation " + label + " contains unknown fields");
    }
  }

  private static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static String digestPrefixed(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
