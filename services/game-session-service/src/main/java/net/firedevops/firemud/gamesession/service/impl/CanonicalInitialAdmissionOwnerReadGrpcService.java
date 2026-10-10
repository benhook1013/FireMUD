package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionReconciliationRequiredException;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;

/**
 * Unregistered read-only producer for exact World initial-admission owner reconciliation.
 *
 * <p>This point-in-time read does not acquire or renew a lease and does not guarantee the proof
 * remains valid through a later transaction owned by another service.
 */
public final class CanonicalInitialAdmissionOwnerReadGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private final CanonicalInitialAdmissionRepository repository;
  private final CanonicalInitialAdmissionOwnerReadWorkloadGuard workloadGuard;

  public CanonicalInitialAdmissionOwnerReadGrpcService(
      CanonicalInitialAdmissionRepository repository,
      CanonicalInitialAdmissionOwnerReadWorkloadGuard workloadGuard) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.workloadGuard = Objects.requireNonNull(workloadGuard, "workloadGuard");
  }

  @Override
  public void getCanonicalInitialAdmissionOwnerProof(
      GetCanonicalInitialAdmissionOwnerProofRequest request,
      StreamObserver<GetCanonicalInitialAdmissionOwnerProofResponse> responseObserver) {
    Objects.requireNonNull(responseObserver, "responseObserver");
    if (request == null) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Owner proof request is required")
              .asRuntimeException());
      return;
    }

    HoldIdentity identity;
    try {
      identity = CanonicalInitialAdmissionOwnerGrpcCodec.parseRequest(request);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Owner proof request is malformed")
              .asRuntimeException());
      return;
    }

    try {
      workloadGuard.requireWorldManagementOwnerReadCaller(identity.request().targetNamespace());

      Optional<CanonicalInitialAdmissionOwnerProof> readback =
          repository.read(
              identity.request().targetNamespace(), identity.request().initialAdmissionRequestId());
      if (readback.isEmpty()) {
        responseObserver.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact durable owner proof is not available")
                .asRuntimeException());
        return;
      }
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          toCanonicalProof(identity, readback.get());
      responseObserver.onNext(CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(identity, proof));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException unauthorized) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Owner proof caller is not authorized")
              .asRuntimeException());
    } catch (CanonicalInitialAdmissionReconciliationRequiredException ambiguous) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact durable owner proof is ambiguous")
              .asRuntimeException());
    } catch (IllegalArgumentException mismatchedOwnerReadback) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact durable owner proof is ambiguous")
              .asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Canonical owner proof read failed")
              .asRuntimeException());
    }
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof toCanonicalProof(
      HoldIdentity identity, CanonicalInitialAdmissionOwnerProof ownerProof) {
    Request request = identity.request();
    if (!request.initialAdmissionRequestId().equals(ownerProof.initialAdmissionRequestId())
        || !request.initialAdmissionRequestDigest().equals(ownerProof.requestDigest())
        || !request.targetNamespace().equals(ownerProof.targetNamespace())
        || !request.canonicalTenantId().equals(ownerProof.canonicalTenantId())
        || !request.worldSlug().equals(ownerProof.worldSlug())
        || !request.realmId().equals(ownerProof.realmId())
        || !request.playableStateNamespaceId().equals(ownerProof.playableStateNamespaceId())
        || !request.playableStateScope().equals(ownerProof.playableStateScope())
        || !request.canonicalGameInstanceId().equals(ownerProof.canonicalGameInstanceId())
        || !request.canonicalVersionId().equals(ownerProof.canonicalVersionId())
        || request.activeLifecycleEpoch() != ownerProof.activeLifecycleEpoch()
        || request.expectedCatalogRevision() != ownerProof.expectedCatalogRevision()
        || !request.initialAdmissionOrigin().name().equals(ownerProof.originKind().name())
        || !Objects.equals(
            request.expectedPriorPointerVersion(), ownerProof.expectedPriorPointerVersion())
        || !identity.holdId().equals(ownerProof.holdId())
        || !identity.holdFence().equals(ownerProof.holdFence())
        || !identity.holdBindingDigest().equals(ownerProof.holdBindingDigest())) {
      throw new IllegalArgumentException("Owner readback changed the requested immutable tuple");
    }
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.valueOf(ownerProof.outcome().name()),
        ownerProof.committedPointerVersion(),
        ownerProof.auditEventId(),
        ownerProof.proofDigest(),
        ownerProof.positiveDurableAbort(),
        ownerProof.terminalAt());
  }
}
