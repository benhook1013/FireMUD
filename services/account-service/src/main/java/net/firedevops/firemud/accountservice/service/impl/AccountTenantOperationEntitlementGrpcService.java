package net.firedevops.firemud.accountservice.service.impl;

import com.google.protobuf.Any;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.account.v1.AccountTenantOperationEntitlementReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadTenantOperationEntitlementResponse;
import net.firedevops.firemud.accountservice.security.AccountTenantOperationEntitlementWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountTenantOperationEntitlementOwner;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered Account owner receiver. No component annotation, capacity commit, or player gate.
 */
public final class AccountTenantOperationEntitlementGrpcService
    extends AccountTenantOperationEntitlementReadServiceGrpc
        .AccountTenantOperationEntitlementReadServiceImplBase {
  private final AccountTenantOperationEntitlementOwner owner;
  private final String namespace;
  private final AccountTenantOperationEntitlementWorkloadGuard guard;

  public AccountTenantOperationEntitlementGrpcService(
      AccountTenantOperationEntitlementOwner owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.guard = new AccountTenantOperationEntitlementWorkloadGuard(namespace);
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
            RuntimeTenantOperationEntitlementGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    factory.addService(ServerInterceptors.interceptForward(this, guard));
    return factory.createServer();
  }

  @Override
  public void readTenantOperationEntitlement(
      ReadTenantOperationEntitlementRequest wire,
      StreamObserver<ReadTenantOperationEntitlementResponse> observer) {
    Objects.requireNonNull(observer);
    final String authenticatedCaller;
    try {
      authenticatedCaller = guard.requireAuthenticatedWorldCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    final RuntimeTenantOperationEntitlementGrpcCodec.Request request;
    if (wire == null) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    try {
      request = RuntimeTenantOperationEntitlementGrpcCodec.parseRequest(wire);
    } catch (IllegalArgumentException invalid) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED);
      return;
    }
    final ReadTenantOperationEntitlementResponse response;
    try {
      var sourceRequest =
          new AccountTenantOperationEntitlementOwner.Request(
              request.tenantId(), request.requestId(), authenticatedCaller);
      var evidence =
          switch (request.sourceKind()) {
            case PAID_HOSTING -> owner.evaluatePaid(sourceRequest);
            case NON_PAID_DEMO -> owner.evaluateDemo(sourceRequest);
          };
      response = RuntimeTenantOperationEntitlementGrpcCodec.toResponse(request, evidence);
    } catch (AccountTenantOperationEntitlementOwner.SourceUnavailableException
        | AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException
        | DemoTenantEntitlementException
        | org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unavailableSource) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (RuntimeException internalFailure) {
      // Configuration mismatches and unexpected construction/codec failures are not retryable
      // source outages. Never disclose exception messages or internal identifiers.
      fail(observer, Status.INTERNAL);
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
    String message = "Tenant-operation entitlement evidence is unavailable";
    observer.onError(
        StatusProto.toStatusRuntimeException(
            com.google.rpc.Status.newBuilder()
                .setCode(status.getCode().value())
                .setMessage(message)
                .addDetails(
                    Any.pack(
                        ErrorDetail.newBuilder()
                            .setCode(status.getCode().name())
                            .setMessage(message)
                            .build()))
                .build()));
  }
}
