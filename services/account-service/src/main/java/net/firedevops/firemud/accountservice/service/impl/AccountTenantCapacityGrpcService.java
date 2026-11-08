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
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit private composition only. Other Account RPCs remain UNIMPLEMENTED on this server. */
public final class AccountTenantCapacityGrpcService
    extends AccountServiceGrpc.AccountServiceImplBase {
  private final AccountTenantCapacityOwner owner;
  private final AccountTenantCapacityWorkloadGuard guard;

  public AccountTenantCapacityGrpcService(AccountTenantCapacityOwner owner, String namespace) {
    this.owner = Objects.requireNonNull(owner);
    guard = new AccountTenantCapacityWorkloadGuard(namespace);
  }

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
            TenantCapacityAdmissionGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    factory.addService(ServerInterceptors.interceptForward(this, guard));
    return factory.createServer();
  }

  @Override
  public void commitTenantCapacityAdmission(
      CommitTenantCapacityAdmissionRequest wire,
      StreamObserver<CommitTenantCapacityAdmissionResponse> observer) {
    Objects.requireNonNull(observer);
    final String peer;
    try {
      peer = guard.requireAuthenticatedCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus(), "AUTH_UNAVAILABLE");
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION, "ENTITLEMENT_UNAVAILABLE");
      return;
    }
    final net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request;
    try {
      request = TenantCapacityAdmissionGrpcCodec.parseRequest(wire);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      fail(observer, Status.INVALID_ARGUMENT, "INVALID_ARGUMENT");
      return;
    }
    if (!request.evidence().callerWorkload().equals(peer)) {
      fail(observer, Status.PERMISSION_DENIED, "AUTH_UNAVAILABLE");
      return;
    }
    final CommitTenantCapacityAdmissionResponse result;
    try {
      result = TenantCapacityAdmissionGrpcCodec.toResponse(request, owner.commit(request, peer));
    } catch (CapacityException denied) {
      fail(
          observer,
          "IDEMPOTENCY_CONFLICT".equals(denied.code())
              ? Status.ALREADY_EXISTS
              : "TENANT_BILLING_BLOCKED".equals(denied.code())
                  ? Status.RESOURCE_EXHAUSTED
                  : Status.UNAVAILABLE,
          denied.code());
      return;
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      fail(observer, Status.UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE");
      return;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      fail(observer, Status.INVALID_ARGUMENT, "INVALID_ARGUMENT");
      return;
    } catch (RuntimeException internal) {
      fail(observer, Status.INTERNAL, "ENTITLEMENT_UNAVAILABLE");
      return;
    }
    try {
      observer.onNext(result);
      observer.onCompleted();
    } catch (RuntimeException callback) {
      fail(observer, Status.INTERNAL, "ENTITLEMENT_UNAVAILABLE");
    }
  }

  private static void fail(StreamObserver<?> observer, Status status, String code) {
    observer.onError(
        StatusProto.toStatusRuntimeException(
            com.google.rpc.Status.newBuilder()
                .setCode(status.getCode().value())
                .setMessage("Tenant capacity commitment did not complete")
                .addDetails(
                    Any.pack(
                        ErrorDetail.newBuilder()
                            .setCode(code)
                            .setMessage("Tenant capacity commitment did not complete")
                            .build()))
                .build()));
  }
}
