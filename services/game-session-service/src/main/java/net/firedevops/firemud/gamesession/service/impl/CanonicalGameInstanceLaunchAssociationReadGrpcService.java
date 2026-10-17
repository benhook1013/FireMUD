package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository.OriginalStartSessionAssociation;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Directly constructible, deliberately unregistered reader for the original Human STARTING owner.
 *
 * <p>The repository performs the exact pending-attempt, immutable-pin, and current-STARTING read.
 * This observation returns no mutation, continuation, activation, or admission authority.
 */
public final class CanonicalGameInstanceLaunchAssociationReadGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private static final String WORLD_MANAGEMENT_SERVICE = "world-management-service";

  private final CanonicalGameInstanceLaunchAssociationRepository repository;
  private final String workloadNamespace;

  public CanonicalGameInstanceLaunchAssociationReadGrpcService(
      CanonicalGameInstanceLaunchAssociationRepository repository, String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void getCanonicalGameInstanceLaunchAssociation(
      GetCanonicalGameInstanceLaunchAssociationRequest request,
      StreamObserver<GetCanonicalGameInstanceLaunchAssociationResponse> observer) {
    Objects.requireNonNull(observer, "observer");
    if (!hasExactWorldWorkloadPeer()) {
      GrpcPeerIdentity peer = GrpcPeerIdentity.current();
      Status status =
          peer == null
              ? Status.UNAUTHENTICATED.withDescription(
                  "Verified World Management workload required")
              : Status.PERMISSION_DENIED.withDescription(
                  "Exact same-namespace World Management workload required");
      observer.onError(status.asRuntimeException());
      return;
    }

    final CanonicalGameInstanceLaunchAssociationReadEvidence.Request decoded;
    try {
      if (request == null) {
        throw new IllegalArgumentException("Complete launch association selector required");
      }
      decoded = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete canonical launch-association selector required")
              .asRuntimeException());
      return;
    }

    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Launch-association namespace differs from configured owner")
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Independent Game Session launch snapshot required")
              .asRuntimeException());
      return;
    }

    final GetCanonicalGameInstanceLaunchAssociationResponse response;
    try {
      Optional<OriginalStartSessionAssociation> retained =
          repository.readOriginalStartSessionAssociation(decoded);
      if (retained == null || retained.isEmpty()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact original StartSession launch association is unavailable")
                .asRuntimeException());
        return;
      }

      OriginalStartSessionAssociation original = retained.orElseThrow();
      var association = original.association();
      requireExactOriginalSelection(decoded, original);
      if (association.currentGameInstanceStatus()
          != net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation
              .CurrentGameInstanceStatus.STARTING) {
        throw new IllegalStateException("Original launch association is not currently STARTING");
      }

      var result =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
              decoded,
              association.playableStateNamespaceId(),
              association.playableStateScope(),
              association.publicProduction(),
              CurrentGameInstanceStatus.valueOf(association.currentGameInstanceStatus().name()),
              association.currentRowVersion(),
              original.ownerAttemptId(),
              original.ownerFence(),
              original.canonicalRealmId(),
              original.catalogRevision(),
              association.launchBindingEvidence());
      response = CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result);
    } catch (DataAccessException
        | TransientDataAccessException
        | DataAccessResourceFailureException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Session launch owner storage unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException incompleteOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete exact original StartSession owner evidence is unavailable")
              .asRuntimeException());
      return;
    }

    observer.onNext(response);
    observer.onCompleted();
  }

  private boolean hasExactWorldWorkloadPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    return peer != null
        && WORLD_MANAGEMENT_SERVICE.equals(peer.service())
        && workloadNamespace.equals(peer.namespace())
        && ("spiffe://firemud/ns/" + workloadNamespace + "/sa/" + WORLD_MANAGEMENT_SERVICE)
            .equals(peer.uri())
        && !SessionContext.hasAuthenticatedCallerContext();
  }

  private static void requireExactOriginalSelection(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector,
      OriginalStartSessionAssociation original) {
    var association = original.association();
    var binding = association.launchBindingEvidence();
    var descriptor = binding.descriptor();
    var attestation = binding.releaseAttestation();
    var tuple = original.postAuthorizationExecutionTuple();
    var scope = tuple.preAuthorizationTuple().action().scope();
    var target = tuple.preAuthorizationTuple().action().target();

    if (!selector.targetNamespace().equals(association.targetNamespace())
        || !selector.canonicalTenantId().equals(association.canonicalTenantId())
        || !selector.worldSlug().equals(association.worldSlug())
        || !selector.gameInstanceUuid().equals(association.gameInstanceUuid())
        || !selector.controlPlaneRequestId().equals(association.controlPlaneRequestId())
        || !selector.launchDescriptorId().equals(association.launchDescriptorId())
        || !selector.expectedDescriptorRequestDigest().equals(descriptor.requestDigest())
        || !selector.expectedDescriptorResultDigest().equals(descriptor.resultDigest())
        || !selector.expectedReleaseAttestationEvidenceDigest().equals(attestation.evidenceDigest())
        || !selector.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
        || !selector
            .controlPlaneRequestId()
            .equals(tuple.preAuthorizationTuple().controlPlaneRequestId())
        || !selector.targetNamespace().equals(scope.targetNamespace())
        || !selector.canonicalTenantId().equals(scope.tenantId())
        || target.gameTemplateId() != descriptor.gameTemplateId()) {
      throw new IllegalStateException(
          "Original StartSession tuple or launch association differs from its exact selector");
    }
  }
}
