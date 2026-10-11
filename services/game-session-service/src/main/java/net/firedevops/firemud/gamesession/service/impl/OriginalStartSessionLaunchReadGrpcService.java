package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.v1.OriginalStartSessionLaunchReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Directly constructible, deliberately unregistered Account-only read of the original StartSession
 * Game Session launch and its actual retained tuple.
 *
 * <p>This read returns a current row projection only. It does not require STARTING, grant lifecycle
 * authority, or authorize activation/admission.
 */
public final class OriginalStartSessionLaunchReadGrpcService
    extends OriginalStartSessionLaunchReadServiceGrpc
        .OriginalStartSessionLaunchReadServiceImplBase {
  private final GameSessionCanonicalInstanceLaunchRepository repository;
  private final String workloadNamespace;

  public OriginalStartSessionLaunchReadGrpcService(
      GameSessionCanonicalInstanceLaunchRepository repository, String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readOriginalStartSessionLaunch(
      ReadOriginalStartSessionLaunchRequest request,
      StreamObserver<ReadOriginalStartSessionLaunchResponse> observer) {
    Objects.requireNonNull(observer, "observer");

    try {
      // Authenticate the exact Account workload before decoding caller-controlled fields.
      var peer = GrpcPeerIdentity.current();
      if (peer == null) {
        throw Status.UNAUTHENTICATED
            .withDescription("Verified same-namespace Account workload required")
            .asRuntimeException();
      }
      String expectedPeerUri = "spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service";
      if (!"account-service".equals(peer.service())
          || !workloadNamespace.equals(peer.namespace())
          || !expectedPeerUri.equals(peer.uri())
          || SessionContext.hasAuthenticatedCallerContext()) {
        throw Status.PERMISSION_DENIED
            .withDescription(
                "Exact same-namespace Account workload without caller context required")
            .asRuntimeException();
      }
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    if (request == null) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete original StartSession launch read request required")
              .asRuntimeException());
      return;
    }
    final OriginalStartSessionLaunchReadEvidence.Request decoded;
    try {
      decoded = OriginalStartSessionLaunchReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Complete canonical original StartSession launch request required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.selector().targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Original launch target differs from configured Game Session owner")
              .asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Independent Game Session original launch snapshot required")
              .asRuntimeException());
      return;
    }

    try {
      var retained =
          repository.readByOriginalTuple(
              decoded.selector(), decoded.originalPostAuthorizationTupleBytes());
      if (retained.isEmpty()) {
        observer.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact original StartSession launch association is unavailable")
                .asRuntimeException());
        return;
      }
      var association = retained.orElseThrow();
      var launch =
          new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
              decoded.selector(),
              association.playableStateNamespaceId(),
              association.playableStateScope(),
              association.publicProduction(),
              association.canonicalRealmId(),
              association.catalogRevision(),
              association.currentGameInstanceStatus(),
              association.currentRowVersion(),
              association.launchBindingEvidence());
      var result =
          new OriginalStartSessionLaunchReadEvidence.Result(
              decoded, launch, association.originalStartSessionTuple());
      observer.onNext(OriginalStartSessionLaunchReadGrpcCodec.toResponse(result));
      observer.onCompleted();
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Session original launch owner storage unavailable")
              .asRuntimeException());
    } catch (RuntimeException incompleteOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete exact original launch owner evidence is unavailable")
              .asRuntimeException());
    }
  }
}
