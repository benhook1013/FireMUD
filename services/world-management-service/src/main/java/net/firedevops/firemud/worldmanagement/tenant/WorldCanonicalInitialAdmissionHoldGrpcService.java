package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.TooManyRowsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicitly unregistered same-namespace Game Session boundary for canonical World holds. */
public final class WorldCanonicalInitialAdmissionHoldGrpcService
    extends WorldCanonicalInitialAdmissionHoldServiceGrpc
        .WorldCanonicalInitialAdmissionHoldServiceImplBase {
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final int MAX_IDENTITY_BYTES = 64 * 1024;
  private static final int MAX_LIFECYCLE_REQUEST_BYTES = 64 * 1024;

  private final WorldCanonicalInitialAdmissionHoldOwner owner;
  private final String trustedNamespace;

  public WorldCanonicalInitialAdmissionHoldGrpcService(
      WorldCanonicalInitialAdmissionHoldOwner owner,
      @Value("${firemud.grpc.workload-namespace:}") String trustedNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void acquireCanonicalInitialAdmissionHold(
      AcquireCanonicalInitialAdmissionHoldRequest request,
      StreamObserver<AcquireCanonicalInitialAdmissionHoldResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (!requireIndependentOwnerOperation(responseObserver)) return;

    Request holdRequest;
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest;
    try {
      requireNoUnknownFields(request, "AcquireCanonicalInitialAdmissionHoldRequest");
      holdRequest = decodeHoldRequest(request.getCanonicalHoldRequestBytes());
      lifecycleRequest = decodeLifecycleRequest(request.getCanonicalLifecycleReadRequestBytes());
      requireTrustedNamespace(holdRequest.targetNamespace());
      requireSameTarget(holdRequest, lifecycleRequest);
    } catch (IllegalArgumentException malformed) {
      fail(responseObserver, Status.INVALID_ARGUMENT, "Canonical World hold request is malformed");
      return;
    } catch (NamespaceMismatchException unauthorized) {
      fail(responseObserver, Status.PERMISSION_DENIED, "World hold namespace is not authorized");
      return;
    }

    try {
      HoldIdentity identity = owner.acquire(holdRequest, lifecycleRequest);
      responseObserver.onNext(
          AcquireCanonicalInitialAdmissionHoldResponse.newBuilder()
              .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
              .build());
      responseObserver.onCompleted();
    } catch (WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException conflict) {
      fail(
          responseObserver,
          Status.ALREADY_EXISTS,
          "A canonical World hold already owns this realm");
    } catch (WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold evidence is inconsistent");
    } catch (
        WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World association is inconsistent");
    } catch (
        WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World lifecycle evidence is inconsistent");
    } catch (CannotCreateTransactionException | DataAccessResourceFailureException unavailable) {
      fail(responseObserver, Status.UNAVAILABLE, "Canonical World hold storage is unavailable");
    } catch (TooManyRowsException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold evidence is inconsistent");
    } catch (DataAccessException | org.springframework.dao.DataAccessException storageFailure) {
      fail(
          responseObserver,
          WorldPreparationStorageFailureStatus.from(storageFailure),
          "Canonical World hold storage operation failed");
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World hold acquisition failed");
    }
  }

  @Override
  public void readCanonicalInitialAdmissionHoldIdentity(
      ReadCanonicalInitialAdmissionHoldIdentityRequest request,
      StreamObserver<ReadCanonicalInitialAdmissionHoldIdentityResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (!requireIndependentOwnerOperation(responseObserver)) return;

    Request holdRequest;
    UUID readRequestId;
    try {
      requireNoUnknownFields(request, "ReadCanonicalInitialAdmissionHoldIdentityRequest");
      readRequestId = parseReadRequestId(request.getReadRequestId());
      holdRequest = decodeHoldRequest(request.getCanonicalHoldRequestBytes());
      requireTrustedNamespace(holdRequest.targetNamespace());
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical World hold identity request is malformed");
      return;
    } catch (NamespaceMismatchException unauthorized) {
      fail(responseObserver, Status.PERMISSION_DENIED, "World hold namespace is not authorized");
      return;
    }

    try {
      HoldIdentity identity = owner.readIdentity(holdRequest).orElse(null);
      if (identity == null) {
        fail(
            responseObserver,
            Status.NOT_FOUND,
            "No exact canonical World hold identity is retained");
        return;
      }
      responseObserver.onNext(
          ReadCanonicalInitialAdmissionHoldIdentityResponse.newBuilder()
              .setReadRequestId(readRequestId.toString())
              .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
              .build());
      responseObserver.onCompleted();
    } catch (WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold identity is inconsistent");
    } catch (
        WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World association is inconsistent");
    } catch (
        WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World lifecycle evidence is inconsistent");
    } catch (CannotCreateTransactionException | DataAccessResourceFailureException unavailable) {
      fail(responseObserver, Status.UNAVAILABLE, "Canonical World hold storage is unavailable");
    } catch (TooManyRowsException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold identity is inconsistent");
    } catch (DataAccessException | org.springframework.dao.DataAccessException storageFailure) {
      fail(
          responseObserver,
          WorldPreparationStorageFailureStatus.from(storageFailure),
          "Canonical World hold storage operation failed");
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World hold identity read failed");
    }
  }

  @Override
  public void readCanonicalInitialAdmissionHoldState(
      ReadCanonicalInitialAdmissionHoldStateRequest request,
      StreamObserver<ReadCanonicalInitialAdmissionHoldStateResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (!requireIndependentOwnerOperation(responseObserver)) return;

    HoldIdentity identity;
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest;
    UUID readRequestId;
    try {
      requireNoUnknownFields(request, "ReadCanonicalInitialAdmissionHoldStateRequest");
      readRequestId = parseReadRequestId(request.getReadRequestId());
      identity = decodeHoldIdentity(request.getHoldIdentityBytes());
      lifecycleRequest = decodeLifecycleRequest(request.getCanonicalLifecycleReadRequestBytes());
      requireTrustedNamespace(identity.request().targetNamespace());
      requireSameTarget(identity.request(), lifecycleRequest);
      if (!readRequestId.equals(lifecycleRequest.readRequestId())) {
        throw new IllegalArgumentException(
            "Lifecycle read request must use the hold-state correlation UUID");
      }
    } catch (IllegalArgumentException malformed) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Canonical World hold state request is malformed");
      return;
    } catch (NamespaceMismatchException unauthorized) {
      fail(responseObserver, Status.PERMISSION_DENIED, "World hold namespace is not authorized");
      return;
    }

    try {
      WorldCanonicalInitialAdmissionHoldState state =
          owner.readState(identity, lifecycleRequest).orElse(null);
      if (state == null) {
        fail(responseObserver, Status.NOT_FOUND, "No exact canonical World hold state is retained");
        return;
      }
      responseObserver.onNext(
          ReadCanonicalInitialAdmissionHoldStateResponse.newBuilder()
              .setReadRequestId(readRequestId.toString())
              .setHoldStateBytes(ByteString.copyFrom(state.canonicalBytes()))
              .build());
      responseObserver.onCompleted();
    } catch (WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold state is inconsistent");
    } catch (
        WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World association is inconsistent");
    } catch (
        WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException invalid) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World lifecycle evidence is inconsistent");
    } catch (CannotCreateTransactionException | DataAccessResourceFailureException unavailable) {
      fail(responseObserver, Status.UNAVAILABLE, "Canonical World hold storage is unavailable");
    } catch (TooManyRowsException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold state is inconsistent");
    } catch (DataAccessException | org.springframework.dao.DataAccessException storageFailure) {
      fail(
          responseObserver,
          WorldPreparationStorageFailureStatus.from(storageFailure),
          "Canonical World hold storage operation failed");
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World hold state read failed");
    }
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> responseObserver) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      fail(
          responseObserver,
          Status.UNAUTHENTICATED,
          "An authenticated workload identity is required");
      return false;
    }
    if (peer.isService("game-session-service")
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

  private static boolean requireIndependentOwnerOperation(StreamObserver<?> responseObserver) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive()) {
      return true;
    }
    fail(
        responseObserver,
        Status.FAILED_PRECONDITION,
        "Canonical World hold operations require an independent owner transaction");
    return false;
  }

  private void requireTrustedNamespace(String targetNamespace) {
    if (!trustedNamespace.equals(targetNamespace)) {
      throw new NamespaceMismatchException();
    }
  }

  private static Request decodeHoldRequest(ByteString bytes) {
    if (bytes.isEmpty() || bytes.size() > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException("Canonical hold request bytes are absent or too large");
    }
    return Request.fromStored(bytes.toByteArray());
  }

  private static HoldIdentity decodeHoldIdentity(ByteString bytes) {
    if (bytes.isEmpty() || bytes.size() > MAX_IDENTITY_BYTES) {
      throw new IllegalArgumentException("Canonical hold identity bytes are absent or too large");
    }
    return HoldIdentity.fromStored(bytes.toByteArray());
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request decodeLifecycleRequest(
      ByteString bytes) {
    if (bytes.isEmpty() || bytes.size() > MAX_LIFECYCLE_REQUEST_BYTES) {
      throw new IllegalArgumentException(
          "Canonical lifecycle request bytes are absent or too large");
    }
    return WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(bytes.toByteArray());
  }

  private static UUID parseReadRequestId(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (new UUID(0L, 0L).equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Read request ID must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Read request ID must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireSameTarget(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    if (!holdRequest.targetNamespace().equals(lifecycleRequest.targetNamespace())
        || !holdRequest.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        || !holdRequest.worldSlug().equals(lifecycleRequest.worldSlug())
        || !holdRequest.canonicalGameInstanceId().equals(lifecycleRequest.canonicalGameInstanceId())
        || !holdRequest
            .playableStateNamespaceId()
            .equals(lifecycleRequest.playableStateNamespaceId())
        || !holdRequest.playableStateScope().equals(lifecycleRequest.playableStateScope())
        || !holdRequest.canonicalVersionId().equals(lifecycleRequest.canonicalVersionId())) {
      throw new IllegalArgumentException(
          "Lifecycle request differs from the complete canonical World hold target");
    }
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private static void fail(
      StreamObserver<?> responseObserver, Status.Code code, String description) {
    fail(responseObserver, Status.fromCode(code), description);
  }

  private static final class NamespaceMismatchException extends RuntimeException {}
}
