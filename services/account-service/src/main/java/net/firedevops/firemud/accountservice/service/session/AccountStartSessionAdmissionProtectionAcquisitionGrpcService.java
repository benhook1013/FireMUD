package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionInput;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone unregistered receiver: authenticate exact Game Session before request decoding. */
public final class AccountStartSessionAdmissionProtectionAcquisitionGrpcService
    extends AccountStartSessionAdmissionProtectionAcquisitionServiceGrpc
        .AccountStartSessionAdmissionProtectionAcquisitionServiceImplBase {
  private final AccountStartSessionAdmissionProtectionAcquisitionService owner;
  private final String namespace;

  public AccountStartSessionAdmissionProtectionAcquisitionGrpcService(
      AccountStartSessionAdmissionProtectionAcquisitionService owner, String namespace) {
    this.owner =
        Objects.requireNonNull(owner, "admission protection acquisition owner is required");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void acquireOriginalStartSessionAdmissionProtection(
      AcquireOriginalStartSessionAdmissionProtectionRequest request,
      StreamObserver<AcquireOriginalStartSessionAdmissionProtectionResponse> observer) {
    Objects.requireNonNull(observer, "response observer is required");
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
      return;
    }
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !expectedPeer.equals(peer.uri())) {
      observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_REQUEST_BYTES) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    final AccountStartSessionAdmissionProtectionAcquisitionInput input;
    try {
      input =
          AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.fromRequest(
              request, namespace);
    } catch (RuntimeException malformed) {
      observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
      return;
    }

    try {
      var evidence =
          owner.acquire(
              new AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest(
                  input.originalPostAuthorizationTuple(),
                  input.gameSessionOwnerMutationId(),
                  input.gameSessionOwnerAttemptId(),
                  input.gameSessionOwnerFence(),
                  input.worldHoldIdentity()));
      var response =
          AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
              input, namespace, evidence);
      observer.onNext(response);
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      Status.Code code = Status.fromThrowable(failure).getCode();
      if (code != Status.Code.UNAUTHENTICATED
          && code != Status.Code.PERMISSION_DENIED
          && code != Status.Code.INVALID_ARGUMENT
          && code != Status.Code.FAILED_PRECONDITION) {
        code = Status.Code.UNAVAILABLE;
      }
      observer.onError(Status.fromCode(code).asRuntimeException());
    } catch (IllegalArgumentException invalidEvidence) {
      observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
    }
  }
}
