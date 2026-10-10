package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.v1.GameSessionControlPlaneServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameInstanceLaunchAssociationResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Standalone, deliberately unregistered historical launch-association reader. Its current runtime
 * projection is neither World lifecycle authority nor authorization for admission or mutation.
 */
public final class CanonicalGameInstanceLaunchAssociationReadGrpcService
    extends GameSessionControlPlaneServiceGrpc.GameSessionControlPlaneServiceImplBase {
  private final GameSessionCanonicalInstanceLaunchRepository repository;
  private final String workloadNamespace;

  public CanonicalGameInstanceLaunchAssociationReadGrpcService(
      GameSessionCanonicalInstanceLaunchRepository repository, String workloadNamespace) {
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
    try {
      // Authenticate before parsing the selector or reaching owner storage.
      var peer = GrpcPeerIdentity.current();
      if (peer == null) {
        throw Status.UNAUTHENTICATED
            .withDescription("Verified World Management workload required")
            .asRuntimeException();
      }
      if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/world-management-service")
              .equals(peer.uri())
          || SessionContext.hasAuthenticatedCallerContext()) {
        throw Status.PERMISSION_DENIED
            .withDescription("Exact same-namespace World Management workload required")
            .asRuntimeException();
      }
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final CanonicalGameInstanceLaunchAssociationReadEvidence.Request decoded;
    if (request == null) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete canonical launch-association selector required")
              .asRuntimeException());
      return;
    }
    try {
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

    try {
      var retained = repository.readByLaunchIdentity(decoded);
      if (retained.isEmpty()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact complete launch association is unavailable")
                .asRuntimeException());
        return;
      }
      var association = retained.orElseThrow();
      var result =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
              decoded,
              association.playableStateNamespaceId(),
              association.playableStateScope(),
              association.publicProduction(),
              association.canonicalRealmId(),
              association.catalogRevision(),
              association.currentGameInstanceStatus(),
              association.currentRowVersion(),
              association.launchBindingEvidence());
      observer.onNext(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result));
      observer.onCompleted();
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Session launch owner storage unavailable")
              .asRuntimeException());
    } catch (RuntimeException incompleteOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete exact launch owner evidence is unavailable")
              .asRuntimeException());
    }
  }
}
