package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.ActivateCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.ActivateCanonicalWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInstanceActivationServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone authenticated canonical activation adapter; intentionally not runtime-registered. */
public final class WorldCanonicalInstanceActivationGrpcService
    extends WorldCanonicalInstanceActivationServiceGrpc
        .WorldCanonicalInstanceActivationServiceImplBase {
  private final WorldCanonicalInstanceActivationService activationService;
  private final String trustedNamespace;

  public WorldCanonicalInstanceActivationGrpcService(
      WorldCanonicalInstanceActivationService activationService, String trustedNamespace) {
    this.activationService = Objects.requireNonNull(activationService, "activationService");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void activateCanonicalWorldInstance(
      ActivateCanonicalWorldInstanceRequest request,
      StreamObserver<ActivateCanonicalWorldInstanceResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (hasAmbientTransaction()) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World activation requires independent owner operations");
      return;
    }

    WorldCanonicalInstanceActivation.Request activationRequest;
    try {
      activationRequest = parseRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A complete canonical World activation request is required");
      return;
    }
    if (!trustedNamespace.equals(activationRequest.preparing().request().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World activation namespace must match the authenticated peer");
      return;
    }

    WorldCanonicalInstanceActivation.Result result;
    try {
      result = activationService.activate(activationRequest);
    } catch (WorldCanonicalInstanceActivationService.ActivationDeniedException denied) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World activation authority was denied");
      return;
    } catch (WorldCanonicalInstanceActivationRepository.ActivationConflictException conflict) {
      fail(
          responseObserver,
          Status.ALREADY_EXISTS,
          "Canonical World activation request conflicts with an existing operation");
      return;
    } catch (
        WorldCanonicalInstanceActivationRepository.InvalidActivationEvidenceException
            inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World activation evidence is inconsistent");
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World activation storage is temporarily unavailable");
      return;
    } catch (DataAccessException permanentStorageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World activation storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World activation failed");
      return;
    }

    try {
      requireExactResult(activationRequest, result);
      byte[] resultBytes = result.canonicalBytes();
      // This is the immutable operation result, including historical retries after later
      // lifecycle movement. The caller must use the separate lifecycle-read RPC for current state.
      ActivateCanonicalWorldInstanceResponse response =
          ActivateCanonicalWorldInstanceResponse.newBuilder()
              .setActivationRequestId(result.request().activationRequestId().toString())
              .setCanonicalResultBytes(ByteString.copyFrom(resultBytes))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (InvalidActivationResponseException | IllegalArgumentException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World activation result does not match the requested operation");
    }
  }

  private static WorldCanonicalInstanceActivation.Request parseRequest(
      ActivateCanonicalWorldInstanceRequest request) {
    Objects.requireNonNull(request, "request");
    if (!request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Canonical World activation request has unknown fields");
    }
    String activationIdText = request.getActivationRequestId();
    UUID activationId;
    try {
      activationId = UUID.fromString(activationIdText);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Activation request ID must be a canonical UUID", invalid);
    }
    if (!activationId.toString().equals(activationIdText)) {
      throw new IllegalArgumentException("Activation request ID must be a canonical UUID");
    }
    byte[] preparingBytes = request.getPreparingLifecycleEvidenceBytes().toByteArray();
    if (preparingBytes.length == 0) {
      throw new IllegalArgumentException("Complete PREPARING lifecycle evidence is required");
    }
    WorldCanonicalInstanceLifecycleEvidence preparing =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(preparingBytes);
    return new WorldCanonicalInstanceActivation.Request(activationId, preparing);
  }

  private static void requireExactResult(
      WorldCanonicalInstanceActivation.Request activationRequest,
      WorldCanonicalInstanceActivation.Result result) {
    if (result == null
        || !activationRequest.activationRequestId().equals(result.request().activationRequestId())
        || !activationRequest.requestDigest().equals(result.request().requestDigest())
        || !Arrays.equals(
            activationRequest.canonicalRequestBytes(), result.request().canonicalRequestBytes())) {
      throw new InvalidActivationResponseException();
    }
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer != null
        && peer.isService("game-session-service")
        && peer.isInNamespace(trustedNamespace)
        && !SessionContext.hasAuthenticatedCallerContext()) {
      return true;
    }
    fail(
        responseObserver,
        Status.PERMISSION_DENIED,
        "Only the verified same-namespace Game Session workload without end-user context is allowed");
    return false;
  }

  private static boolean hasAmbientTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private static final class InvalidActivationResponseException extends IllegalStateException {}
}
