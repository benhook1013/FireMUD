package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AccountPlayerAdmissionEntitlementReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.accountservice.security.AccountPlayerAdmissionEntitlementWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account owner receiver. No component annotation, capacity commit, World hold or
 * gameplay admission.
 */
public final class AccountPlayerAdmissionEntitlementGrpcService
    extends AccountPlayerAdmissionEntitlementReadServiceGrpc
        .AccountPlayerAdmissionEntitlementReadServiceImplBase {
  private final AccountPlayerAdmissionEntitlementOwner owner;
  private final String namespace;
  private final AccountPlayerAdmissionEntitlementWorkloadGuard guard;

  public AccountPlayerAdmissionEntitlementGrpcService(
      AccountPlayerAdmissionEntitlementOwner owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.guard = new AccountPlayerAdmissionEntitlementWorkloadGuard(namespace);
    this.namespace = namespace;
  }

  /**
   * Explicit private composition, requiring real client certificates. Returns an unstarted server.
   */
  public Server createUnregisteredServer(
      String address, KeyManagerFactory keys, TrustManagerFactory trust) {
    var factory =
        new BoundedGrpcServerFactory(
            Objects.requireNonNull(address),
            List.of(),
            Objects.requireNonNull(keys),
            Objects.requireNonNull(trust),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            RuntimePlayerAdmissionEntitlementGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    factory.addService(ServerInterceptors.interceptForward(this, guard));
    return factory.createServer();
  }

  @Override
  public void readPlayerAdmissionEntitlement(
      ReadPlayerAdmissionEntitlementRequest wire,
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer) {
    Objects.requireNonNull(observer);
    final String authenticatedCaller;
    try {
      authenticatedCaller = guard.requireAuthenticatedGameSessionCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    final RuntimePlayerAdmissionEntitlementGrpcCodec.PresentedRequest presented;
    if (wire == null) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    try {
      presented = RuntimePlayerAdmissionEntitlementGrpcCodec.parseRequest(wire);
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    var request = presented.request();
    if (!namespace.equals(request.target().targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED);
      return;
    }
    final ReadPlayerAdmissionEntitlementResponse response;
    try {
      var sourceRequest =
          new AccountPlayerAdmissionEntitlementOwner.Request(
              request.target(), request.connectScopeId(), authenticatedCaller);
      var evidence = owner.evaluate(sourceRequest, presented.initialLoginJwt());
      response = RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(request, evidence);
    } catch (RuntimeException unavailableSource) {
      // No positive evidence, alternate source, or exception details escape an unavailable
      // evaluation.
      fail(observer, Status.UNAVAILABLE);
      return;
    }
    try {
      observer.onNext(response);
    } catch (RuntimeException callbackFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    observer.onCompleted();
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Player-admission entitlement evidence is unavailable")
            .asRuntimeException());
  }
}
