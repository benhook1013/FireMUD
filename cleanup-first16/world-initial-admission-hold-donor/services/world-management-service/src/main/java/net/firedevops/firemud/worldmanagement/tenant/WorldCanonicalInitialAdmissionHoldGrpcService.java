package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.service.WorldCanonicalInitialAdmissionHoldOwner;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalInitialAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldIdentityResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalInitialAdmissionHoldStateResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalInitialAdmissionHoldServiceGrpc;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

/** Authenticated gRPC boundary for World-owned canonical fixture hold acquisition and readback. */
@GrpcService
@ConditionalOnProperty(name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED", havingValue = "true")
public final class WorldCanonicalInitialAdmissionHoldGrpcService
    extends WorldCanonicalInitialAdmissionHoldServiceGrpc.WorldCanonicalInitialAdmissionHoldServiceImplBase {
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final int MAX_LIFECYCLE_BYTES = 2 * 1024 * 1024;

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
      StreamObserver<AcquireCanonicalInitialAdmissionHoldResponse> observer) {
    if (!requireAuthenticatedGameSessionPeer(observer)) return;
    try {
      requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty());
      byte[] holdRequest = bounded(request.getCanonicalHoldRequestBytes().toByteArray(), MAX_REQUEST_BYTES);
      byte[] lifecycleRequest =
          bounded(request.getCanonicalLifecycleReadRequestBytes().toByteArray(), MAX_LIFECYCLE_BYTES);
      var decoded = WorldCanonicalInitialAdmissionHold.Request.fromStored(holdRequest);
      var lifecycle = WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(lifecycleRequest);
      if (!trustedNamespace.equals(decoded.targetNamespace())
          || !trustedNamespace.equals(lifecycle.targetNamespace())) {
        throw new SecurityException("World canonical hold namespace differs from this workload");
      }
      var identity = owner.acquire(holdRequest, lifecycleRequest);
      observer.onNext(
          AcquireCanonicalInitialAdmissionHoldResponse.newBuilder()
              .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
              .build());
      observer.onCompleted();
    } catch (SecurityException denied) {
      fail(observer, Status.PERMISSION_DENIED, "Authenticated Game Session peer is required");
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.FAILED_PRECONDITION, "World fixture hold request has no exact owner proof");
    } catch (RuntimeException failure) {
      fail(observer, Status.UNAVAILABLE, "World fixture hold owner is temporarily unavailable");
    }
  }

  @Override
  public void readCanonicalInitialAdmissionHoldIdentity(
      ReadCanonicalInitialAdmissionHoldIdentityRequest request,
      StreamObserver<ReadCanonicalInitialAdmissionHoldIdentityResponse> observer) {
    if (!requireAuthenticatedGameSessionPeer(observer)) return;
    try {
      requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty());
      UUID readId = canonicalNonNilUuid(request.getReadRequestId());
      byte[] holdRequest = bounded(request.getCanonicalHoldRequestBytes().toByteArray(), MAX_REQUEST_BYTES);
      var decoded = WorldCanonicalInitialAdmissionHold.Request.fromStored(holdRequest);
      if (!trustedNamespace.equals(decoded.targetNamespace())) {
        throw new SecurityException("World canonical hold namespace differs from this workload");
      }
      var identity = owner.readIdentity(holdRequest);
      observer.onNext(
          ReadCanonicalInitialAdmissionHoldIdentityResponse.newBuilder()
              .setReadRequestId(readId.toString())
              .setHoldIdentityBytes(ByteString.copyFrom(identity.canonicalBytes()))
              .build());
      observer.onCompleted();
    } catch (SecurityException denied) {
      fail(observer, Status.PERMISSION_DENIED, "Authenticated Game Session peer is required");
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.FAILED_PRECONDITION, "World fixture hold identity is unavailable");
    } catch (RuntimeException failure) {
      fail(observer, Status.UNAVAILABLE, "World fixture hold identity read is unavailable");
    }
  }

  @Override
  public void readCanonicalInitialAdmissionHoldState(
      ReadCanonicalInitialAdmissionHoldStateRequest request,
      StreamObserver<ReadCanonicalInitialAdmissionHoldStateResponse> observer) {
    if (!requireAuthenticatedGameSessionPeer(observer)) return;
    try {
      requireNoUnknownFields(request.getUnknownFields().asMap().isEmpty());
      UUID readId = canonicalNonNilUuid(request.getReadRequestId());
      byte[] identityBytes = bounded(request.getHoldIdentityBytes().toByteArray(), MAX_REQUEST_BYTES);
      byte[] lifecycleRequestBytes =
          bounded(request.getCanonicalLifecycleReadRequestBytes().toByteArray(), MAX_LIFECYCLE_BYTES);
      var identity = WorldCanonicalInitialAdmissionHold.HoldIdentity.fromStored(identityBytes);
      var lifecycleRequest = WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(lifecycleRequestBytes);
      if (!trustedNamespace.equals(identity.request().targetNamespace())
          || !trustedNamespace.equals(lifecycleRequest.targetNamespace())
          || !readId.equals(lifecycleRequest.readRequestId())) {
        throw new SecurityException("World canonical hold state read identity is not exact");
      }
      var state = owner.readState(identityBytes, lifecycleRequestBytes);
      observer.onNext(
          ReadCanonicalInitialAdmissionHoldStateResponse.newBuilder()
              .setReadRequestId(readId.toString())
              .setHoldStateBytes(ByteString.copyFrom(state.canonicalBytes()))
              .build());
      observer.onCompleted();
    } catch (SecurityException denied) {
      fail(observer, Status.PERMISSION_DENIED, "Authenticated Game Session peer is required");
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.FAILED_PRECONDITION, "World fixture hold state has no exact owner proof");
    } catch (RuntimeException failure) {
      fail(observer, Status.UNAVAILABLE, "World fixture hold state read is unavailable");
    }
  }

  private boolean requireAuthenticatedGameSessionPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (SessionContext.hasAuthenticatedCallerContext()
        || peer == null
        || !peer.isService("game-session-service")
        || !peer.isInNamespace(trustedNamespace)) {
      fail(observer, Status.PERMISSION_DENIED, "Verified same-namespace Game Session workload required");
      return false;
    }
    return true;
  }

  private static UUID canonicalNonNilUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Read correlation UUID is invalid");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Read correlation UUID is invalid", invalid);
    }
  }

  private static byte[] bounded(byte[] value, int maximum) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw new IllegalArgumentException("Canonical World owner bytes are outside their limit");
    }
    return value;
  }

  private static void requireNoUnknownFields(boolean knownOnly) {
    if (!knownOnly) throw new IllegalArgumentException("World hold request contains unknown fields");
  }

  private static void fail(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
