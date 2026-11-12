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
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionRequest;
import net.firedevops.firemud.account.v1.CommitTenantCapacityAdmissionResponse;
import net.firedevops.firemud.accountservice.repository.AccountTenantCapacityRepository.CapacityException;
import net.firedevops.firemud.accountservice.security.AccountTenantCapacityWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountTenantCapacityOwner;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmissionGrpcCodec.Failure;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
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
      observer.onError(Failure.AUTH_UNAVAILABLE.toStatusException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(
          Failure.ENTITLEMENT_UNAVAILABLE.toStatusException(Status.Code.FAILED_PRECONDITION));
      return;
    }
    final net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request request;
    try {
      request = TenantCapacityAdmissionGrpcCodec.parseRequest(wire);
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      observer.onError(Failure.INVALID_ARGUMENT.toStatusException());
      return;
    }
    if (!request.evidence().callerWorkload().equals(peer)) {
      observer.onError(Failure.PERMISSION_DENIED.toStatusException());
      return;
    }
    CommitTenantCapacityAdmissionResponse result;
    try {
      result = TenantCapacityAdmissionGrpcCodec.toResponse(request, owner.commit(request, peer));
    } catch (CapacityException denied) {
      if (!denied.code().isDomainRefusal()) {
        observer.onError(denied.code().toStatusException());
        return;
      }
      result = TenantCapacityAdmissionGrpcCodec.toRefusal(request, denied.code());
    } catch (org.jooq.exception.DataAccessException
        | org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException unavailable) {
      observer.onError(Failure.ENTITLEMENT_UNAVAILABLE.toStatusException());
      return;
    } catch (RuntimeException internal) {
      observer.onError(Status.INTERNAL.asRuntimeException());
      return;
    }
    try {
      observer.onNext(result);
      observer.onCompleted();
    } catch (RuntimeException callback) {
      observer.onError(Status.INTERNAL.asRuntimeException());
    }
  }
}
