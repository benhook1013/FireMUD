package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountPublicationAuthorizationReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadGrpcCodec;

/** Standalone endpoint only: peer authentication precedes decoding and persistence lookup. */
public final class AccountPublicationAuthorizationReadGrpcService
    extends AccountPublicationAuthorizationReadServiceGrpc
        .AccountPublicationAuthorizationReadServiceImplBase {
  private final AccountPublicationAuthorizationReadService owner;
  private final String trustedNamespace;

  public AccountPublicationAuthorizationReadGrpcService(
      AccountPublicationAuthorizationReadService owner, String trustedNamespace) {
    this.owner = Objects.requireNonNull(owner);
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readHeldPublicationAuthorization(
      ReadHeldPublicationAuthorizationRequest request,
      StreamObserver<ReadHeldPublicationAuthorizationResponse> observer) {
    try {
      AccountPublicationAuthorizationReadService.requirePeer(trustedNamespace);
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    final net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence
            .Request
        decoded;
    try {
      decoded = AccountPublicationAuthorizationReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical Account publication read required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Account publication namespace differs from peer")
              .asRuntimeException());
      return;
    }
    final ReadHeldPublicationAuthorizationResponse response;
    try {
      owner.requireHeld(decoded);
      response = AccountPublicationAuthorizationReadGrpcCodec.toHeldResponse(decoded);
    } catch (StatusRuntimeException failure) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(failure).getCode())
              .withDescription("Account publication authorization owner read denied or unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Account publication owner read unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }
}
