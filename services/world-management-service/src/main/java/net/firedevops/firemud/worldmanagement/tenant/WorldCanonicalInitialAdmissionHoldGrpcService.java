package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone authenticated hold adapter; intentionally not registered as a runtime RPC. */
public final class WorldCanonicalInitialAdmissionHoldGrpcService
    extends WorldCanonicalInitialAdmissionHoldServiceGrpc
        .WorldCanonicalInitialAdmissionHoldServiceImplBase {
  private final WorldCanonicalInitialAdmissionHoldRepository repository;
  private final String trustedNamespace;

  public WorldCanonicalInitialAdmissionHoldGrpcService(
      WorldCanonicalInitialAdmissionHoldRepository repository, String trustedNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
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

    ParsedAcquire parsed;
    try {
      parsed = parseAcquireRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "Complete canonical World initial-admission hold evidence is required");
      return;
    }
    if (!trustedNamespace.equals(parsed.holdRequest().targetNamespace())
        || !trustedNamespace.equals(parsed.lifecycleRequest().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World hold and lifecycle namespaces must match the authenticated peer");
      return;
    }
    if (!sameTarget(parsed.holdRequest(), parsed.lifecycleRequest())) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World lifecycle evidence does not match the complete hold target");
      return;
    }

    HoldIdentity identity;
    try {
      identity = repository.acquire(parsed.holdRequest(), parsed.lifecycleRequest());
    } catch (WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException conflict) {
      fail(
          responseObserver,
          Status.ALREADY_EXISTS,
          "Canonical World initial-admission hold conflicts with existing owner state");
      return;
    } catch (
        WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World initial-admission hold identity is inconsistent");
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World initial-admission hold storage is temporarily unavailable");
      return;
    } catch (DataAccessException storageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World initial-admission hold storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World initial-admission hold acquisition failed");
      return;
    }

    byte[] identityBytes;
    try {
      identityBytes = requireExactIdentity(parsed.holdRequest(), identity);
    } catch (RuntimeException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold owner returned a substituted or incomplete identity");
      return;
    }
    responseObserver.onNext(
        AcquireCanonicalInitialAdmissionHoldResponse.newBuilder()
            .setHoldIdentityBytes(ByteString.copyFrom(identityBytes))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void readCanonicalInitialAdmissionHoldIdentity(
      ReadCanonicalInitialAdmissionHoldIdentityRequest request,
      StreamObserver<ReadCanonicalInitialAdmissionHoldIdentityResponse> responseObserver) {
    if (!requireAuthenticatedGameSessionPeer(responseObserver)) return;
    if (!requireIndependentOwnerOperation(responseObserver)) return;

    ParsedRead parsed;
    try {
      parsed = parseReadRequest(request);
    } catch (IllegalArgumentException invalid) {
      fail(
          responseObserver,
          Status.INVALID_ARGUMENT,
          "A canonical read UUID and complete hold request are required");
      return;
    }
    if (!trustedNamespace.equals(parsed.holdRequest().targetNamespace())) {
      fail(
          responseObserver,
          Status.PERMISSION_DENIED,
          "Canonical World hold namespace must match the authenticated peer");
      return;
    }

    Optional<HoldIdentity> maybeIdentity;
    try {
      maybeIdentity = repository.readIdentity(parsed.holdRequest());
    } catch (WorldCanonicalInitialAdmissionHoldRepository.HoldConflictException conflict) {
      fail(
          responseObserver,
          Status.ALREADY_EXISTS,
          "Canonical World initial-admission hold identity conflicts with owner state");
      return;
    } catch (
        WorldCanonicalInitialAdmissionHoldRepository.InvalidHoldIdentityException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World initial-admission hold identity is inconsistent");
      return;
    } catch (TransientDataAccessException unavailable) {
      fail(
          responseObserver,
          Status.UNAVAILABLE,
          "Canonical World initial-admission hold storage is temporarily unavailable");
      return;
    } catch (DataAccessException storageFailure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World initial-admission hold storage failed");
      return;
    } catch (RuntimeException failure) {
      fail(responseObserver, Status.INTERNAL, "Canonical World initial-admission hold read failed");
      return;
    }
    if (maybeIdentity == null) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold owner returned no read result");
      return;
    }
    if (maybeIdentity.isEmpty()) {
      fail(
          responseObserver,
          Status.NOT_FOUND,
          "No exact historical canonical World hold identity was found");
      return;
    }

    byte[] identityBytes;
    try {
      identityBytes = requireExactIdentity(parsed.holdRequest(), maybeIdentity.orElseThrow());
    } catch (RuntimeException inconsistent) {
      fail(
          responseObserver,
          Status.FAILED_PRECONDITION,
          "Canonical World hold owner returned a substituted or incomplete identity");
      return;
    }
    responseObserver.onNext(
        ReadCanonicalInitialAdmissionHoldIdentityResponse.newBuilder()
            .setReadRequestId(parsed.readRequestId())
            .setHoldIdentityBytes(ByteString.copyFrom(identityBytes))
            .build());
    responseObserver.onCompleted();
  }

  private static ParsedAcquire parseAcquireRequest(
      AcquireCanonicalInitialAdmissionHoldRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Closed canonical acquire request is required");
    }
    Request holdRequest =
        WorldCanonicalInitialAdmissionHold.Request.fromStored(
            request.getCanonicalHoldRequestBytes().toByteArray());
    WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest =
        WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
            request.getCanonicalLifecycleReadRequestBytes().toByteArray());
    return new ParsedAcquire(holdRequest, lifecycleRequest);
  }

  private static ParsedRead parseReadRequest(
      ReadCanonicalInitialAdmissionHoldIdentityRequest request) {
    if (request == null || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Closed canonical read request is required");
    }
    String readId = request.getReadRequestId();
    UUID parsedReadId;
    try {
      parsedReadId = UUID.fromString(readId);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Read request ID must be a canonical UUID", invalid);
    }
    if (new UUID(0L, 0L).equals(parsedReadId) || !parsedReadId.toString().equals(readId)) {
      throw new IllegalArgumentException("Read request ID must be a canonical non-nil UUID");
    }
    Request holdRequest =
        WorldCanonicalInitialAdmissionHold.Request.fromStored(
            request.getCanonicalHoldRequestBytes().toByteArray());
    return new ParsedRead(readId, holdRequest);
  }

  private static boolean sameTarget(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    return holdRequest.canonicalTenantId().equals(lifecycleRequest.canonicalTenantId())
        && holdRequest.worldSlug().equals(lifecycleRequest.worldSlug())
        && holdRequest.playableStateNamespaceId().equals(lifecycleRequest.playableStateNamespaceId())
        && holdRequest.playableStateScope().equals(lifecycleRequest.playableStateScope())
        && holdRequest.canonicalGameInstanceId().equals(lifecycleRequest.canonicalGameInstanceId())
        && holdRequest.canonicalVersionId().equals(lifecycleRequest.canonicalVersionId())
        && lifecycleRequest.publicProduction();
  }

  private static byte[] requireExactIdentity(Request expected, HoldIdentity actual) {
    if (actual == null
        || actual.request() == null
        || !expected.equals(actual.request())
        || !Arrays.equals(expected.canonicalRequestBytes(), actual.canonicalRequestBytes())
        || !expected.holdBindingDigest().equals(actual.holdBindingDigest())) {
      throw new IllegalStateException("Canonical World hold identity differs from the request");
    }
    byte[] bytes = actual.canonicalBytes();
    HoldIdentity decoded = WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(bytes);
    if (!expected.equals(decoded.request())
        || !Arrays.equals(expected.canonicalRequestBytes(), decoded.canonicalRequestBytes())
        || !expected.holdBindingDigest().equals(decoded.holdBindingDigest())
        || decoded.holdId() == null
        || decoded.holdFence() == null) {
      throw new IllegalStateException("Canonical World hold identity failed exact readback");
    }
    return bytes;
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

  private static boolean requireIndependentOwnerOperation(StreamObserver<?> responseObserver) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive()) {
      return true;
    }
    fail(
        responseObserver,
        Status.FAILED_PRECONDITION,
        "Canonical World hold operations require independent owner transactions");
    return false;
  }

  private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
    responseObserver.onError(status.withDescription(description).asRuntimeException());
  }

  private record ParsedAcquire(
      Request holdRequest, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {}

  private record ParsedRead(String readRequestId, Request holdRequest) {}
}
