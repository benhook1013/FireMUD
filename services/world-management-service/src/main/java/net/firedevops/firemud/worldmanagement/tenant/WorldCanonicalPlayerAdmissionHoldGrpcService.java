package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldCanonicalPlayerAdmissionHoldServiceGrpc;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered workload-only acquire/read adapter; no accepting release or runtime activation. */
public final class WorldCanonicalPlayerAdmissionHoldGrpcService
    extends WorldCanonicalPlayerAdmissionHoldServiceGrpc
        .WorldCanonicalPlayerAdmissionHoldServiceImplBase {
  private final WorldCanonicalPlayerAdmissionHoldService service;
  private final String namespace;

  public WorldCanonicalPlayerAdmissionHoldGrpcService(
      WorldCanonicalPlayerAdmissionHoldService service, String namespace) {
    this.service = Objects.requireNonNull(service, "service");
    this.namespace = WorldCanonicalPlayerAdmissionHoldService.requireNamespace(namespace);
  }

  @Override
  public void acquireCanonicalPlayerAdmissionHold(
      AcquireCanonicalPlayerAdmissionHoldRequest request,
      StreamObserver<AcquireCanonicalPlayerAdmissionHoldResponse> observer) {
    if (!requireBoundary(observer)) return;
    try {
      if (request == null || !request.getUnknownFields().asMap().isEmpty()) throw invalid();
      var parsed =
          parse(
              request.getRequestId(),
              request.getOriginalLeaseJson(),
              request.getOriginalLeaseSha256(),
              request.getExpectedLifecycleEpoch(),
              request.getExpectedRowVersion());
      var held =
          service.acquire(
              parsed.bound().lease().canonicalJson(),
              parsed.bound().lease().sha256(),
              parsed.bound().expectedLifecycleEpoch(),
              parsed.bound().expectedRowVersion());
      var exact = requireResponse(parsed, held);
      observer.onNext(
          AcquireCanonicalPlayerAdmissionHoldResponse.newBuilder()
              .setRequestId(parsed.correlationId())
              .setOriginalLeaseSha256(parsed.bound().lease().sha256())
              .setHoldEvidenceJson(ByteString.copyFrom(exact.canonicalBytes()))
              .setHoldEvidenceSha256(exact.sha256())
              .build());
      observer.onCompleted();
    } catch (RuntimeException failure) {
      report(observer, failure);
    }
  }

  @Override
  public void readCanonicalPlayerAdmissionHold(
      ReadCanonicalPlayerAdmissionHoldRequest request,
      StreamObserver<ReadCanonicalPlayerAdmissionHoldResponse> observer) {
    if (!requireBoundary(observer)) return;
    try {
      if (request == null || !request.getUnknownFields().asMap().isEmpty()) throw invalid();
      var parsed =
          parse(
              request.getRequestId(),
              request.getOriginalLeaseJson(),
              request.getOriginalLeaseSha256(),
              request.getExpectedLifecycleEpoch(),
              request.getExpectedRowVersion());
      var held =
          service
              .read(
                  parsed.bound().lease().canonicalJson(),
                  parsed.bound().lease().sha256(),
                  parsed.bound().expectedLifecycleEpoch(),
                  parsed.bound().expectedRowVersion())
              .orElseThrow(
                  () ->
                      new IllegalStateException("Exact World player-admission hold is unresolved"));
      var exact = requireResponse(parsed, held);
      observer.onNext(
          ReadCanonicalPlayerAdmissionHoldResponse.newBuilder()
              .setRequestId(parsed.correlationId())
              .setOriginalLeaseSha256(parsed.bound().lease().sha256())
              .setHoldEvidenceJson(ByteString.copyFrom(exact.canonicalBytes()))
              .setHoldEvidenceSha256(exact.sha256())
              .build());
      observer.onCompleted();
    } catch (RuntimeException failure) {
      report(observer, failure);
    }
  }

  private boolean requireBoundary(StreamObserver<?> observer) {
    try {
      WorldCanonicalPlayerAdmissionHoldService.requireAccountPeer(namespace);
    } catch (SecurityException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription(
                  "Exact same-namespace Account workload without end-user context is required")
              .asRuntimeException());
      return false;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("World player hold requires an independent owner operation")
              .asRuntimeException());
      return false;
    }
    return true;
  }

  private Parsed parse(
      String correlation,
      ByteString originalLease,
      String leaseDigest,
      String epoch,
      String version) {
    UUID requestId;
    try {
      requestId = UUID.fromString(correlation);
    } catch (RuntimeException invalid) {
      throw invalid();
    }
    if (!requestId.toString().equals(correlation)
        || requestId.version() != 4
        || requestId.variant() != 2
        || originalLease.isEmpty()
        || originalLease.size() > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
        || !originalLease.isValidUtf8()) throw invalid();
    var lease = AccountGameplayAdmissionLeaseEvidence.parseCanonical(originalLease.toStringUtf8());
    if (leaseDigest == null
        || !lease.sha256().equals(leaseDigest)
        || !namespace.equals(lease.carrier().get("targetNamespace"))) {
      throw invalid();
    }
    return new Parsed(
        correlation,
        new WorldCanonicalPlayerAdmissionHoldEvidence.Request(
            lease,
            WorldCanonicalPlayerAdmissionHoldEvidence.decimal(epoch, true),
            WorldCanonicalPlayerAdmissionHoldEvidence.decimal(version, false)));
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence requireResponse(
      Parsed parsed, WorldCanonicalPlayerAdmissionHoldEvidence held) {
    if (held == null || held.request() == null || !parsed.bound().sameBinding(held.request())) {
      throw new IllegalStateException(
          "World hold response changed the complete original lease binding");
    }
    var world = held.worldEvidence();
    if (held.holdId() == null
        || held.holdFence() == null
        || world == null
        || world.request() == null
        || world.request().canonicalTenantId() == null
        || world.request().canonicalGameInstanceId() == null
        || world.request().playableStateNamespaceId() == null) {
      throw new IllegalStateException("World hold response has missing owner evidence fields");
    }
    try {
      parsed.bound().requireExactActiveWorld(world);
      // Rebuild the immutable carrier from the exact bound value rather than trusting supplied
      // bytes.
      var exact =
          new WorldCanonicalPlayerAdmissionHoldEvidence(
              held.holdId(), held.holdFence(), parsed.bound(), world);
      exact.canonicalBytes();
      return exact;
    } catch (IllegalArgumentException inconsistent) {
      throw new IllegalStateException(
          "World hold response has inconsistent owner evidence", inconsistent);
    }
  }

  private static void report(StreamObserver<?> observer, RuntimeException failure) {
    Status status;
    if (failure instanceof IllegalArgumentException) status = Status.INVALID_ARGUMENT;
    else if (failure instanceof SecurityException) status = Status.PERMISSION_DENIED;
    else if (failure instanceof TransientDataAccessException
        || failure instanceof DataAccessException) status = Status.UNAVAILABLE;
    else if (failure instanceof IllegalStateException) status = Status.FAILED_PRECONDITION;
    else status = Status.INTERNAL;
    observer.onError(
        status
            .withDescription(
                "World player hold request could not produce exact retained owner evidence")
            .asRuntimeException());
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Complete canonical World player hold request is required");
  }

  private record Parsed(
      String correlationId, WorldCanonicalPlayerAdmissionHoldEvidence.Request bound) {}
}
