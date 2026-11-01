package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ResolveCurrentTenantCreationAuthorizationResponse;
import net.firedevops.firemud.account.v1.TenantCreationCurrentAuthorizationReadServiceGrpc.TenantCreationCurrentAuthorizationReadServiceImplBase;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationCurrentAuthorizationOwner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.CurrentTenantCreationAuthorizationGrpcCodec;

/** Standalone unregistered current-original authorization read for the exact Game Design peer. */
public final class TenantCreationCurrentAuthorizationReadGrpcService
    extends TenantCreationCurrentAuthorizationReadServiceImplBase {
  private final AccountTenantCreationCurrentAuthorizationOwner owner;
  private final String workloadNamespace;

  public TenantCreationCurrentAuthorizationReadGrpcService(
      AccountTenantCreationCurrentAuthorizationOwner owner, String workloadNamespace) {
    this.owner = Objects.requireNonNull(owner);
    this.workloadNamespace = Objects.requireNonNull(workloadNamespace);
  }

  private boolean isGameDesignPeer() {
    var peer = GrpcPeerIdentity.current();
    return peer != null && GrpcPeerIdentity.isValidNamespace(workloadNamespace)
        && peer.uri().equals("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-design-service");
  }

  @Override
  public void resolveCurrentTenantCreationAuthorization(
      ResolveCurrentTenantCreationAuthorizationRequest request,
      StreamObserver<ResolveCurrentTenantCreationAuthorizationResponse> responseObserver) {
    if (SessionContext.hasAuthenticatedCallerContext()
        || !isGameDesignPeer()) {
      responseObserver.onError(Status.PERMISSION_DENIED
          .withDescription("Exact Game Design workload without caller context required")
          .asRuntimeException());
      return;
    }
    final CurrentTenantCreationAuthorizationGrpcCodec.Request decoded;
    try {
      decoded = CurrentTenantCreationAuthorizationGrpcCodec.decodeRequest(request);
      if (!workloadNamespace.equals(decoded.binding().targetNamespace())) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      responseObserver.onError(Status.INVALID_ARGUMENT
          .withDescription("Exact original creation binding required").asRuntimeException());
      return;
    }
    try {
      var authorized = owner.resolve(decoded.binding());
      if (!decoded.binding().equals(authorized.binding())) throw new IllegalStateException();
      responseObserver.onNext(CurrentTenantCreationAuthorizationGrpcCodec.response(
          decoded.readRequestId(), authorized.binding(), authorized.validBeforeEpochMillis()));
      responseObserver.onCompleted();
    } catch (IllegalArgumentException | IllegalStateException unavailable) {
      responseObserver.onError(Status.FAILED_PRECONDITION
          .withDescription("Current original creation authorization unavailable").asRuntimeException());
    } catch (RuntimeException unavailable) {
      responseObserver.onError(Status.UNAVAILABLE
          .withDescription("Account owner unavailable").asRuntimeException());
    }
  }
}
