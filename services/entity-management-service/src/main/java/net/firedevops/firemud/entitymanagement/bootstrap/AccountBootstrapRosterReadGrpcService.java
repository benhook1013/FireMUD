package net.firedevops.firemud.entitymanagement.bootstrap;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadServiceGrpc;

/** Unregistered receiver. Install its dedicated peer interceptor in every manual composition. */
public final class AccountBootstrapRosterReadGrpcService
    extends AccountBootstrapRosterReadServiceGrpc.AccountBootstrapRosterReadServiceImplBase {
  private final AccountBootstrapRosterReadService service;
  private final String namespace;

  public AccountBootstrapRosterReadGrpcService(
      AccountBootstrapRosterReadService service, String namespace) {
    this.service = Objects.requireNonNull(service, "service");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Fixed workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void readAccountBootstrapRoster(
      AccountBootstrapRosterReadRequest request,
      StreamObserver<AccountBootstrapRosterReadResponse> observer) {
    var peer = AccountBootstrapRosterPeerInterceptor.currentVerifiedPeer();
    if (peer == null) {
      refuse(observer, Status.UNAUTHENTICATED);
      return;
    }
    if (!AccountBootstrapRosterPeerInterceptor.trusted(peer, namespace)
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()) {
      refuse(observer, Status.PERMISSION_DENIED);
      return;
    }
    AccountBootstrapRosterReadResponse response;
    try {
      AccountBootstrapRosterReadService.requireNoAmbientTransaction();
      try {
        AccountBootstrapRosterReadContract.requireRequest(request);
      } catch (IllegalArgumentException invalid) {
        refuse(observer, Status.INVALID_ARGUMENT);
        return;
      }
      if (!namespace.equals(request.getTargetNamespace())) {
        refuse(observer, Status.PERMISSION_DENIED);
        return;
      }
      response = service.read(request);
    } catch (OwnerEvidenceUnavailableException
        | org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | org.springframework.transaction.CannotCreateTransactionException unavailable) {
      refuse(observer, Status.UNAVAILABLE);
      return;
    } catch (UnsupportedOperationException
        | AccountBootstrapRosterReadService.TargetChangedException precondition) {
      refuse(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unexpected) {
      refuse(observer, Status.INTERNAL);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static void refuse(StreamObserver<?> observer, Status status) {
    observer.onError(
        status.withDescription("Account bootstrap roster read refused").asRuntimeException());
  }
}
