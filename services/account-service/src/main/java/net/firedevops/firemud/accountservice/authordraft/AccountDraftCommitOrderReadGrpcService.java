package net.firedevops.firemud.accountservice.authordraft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountDraftCommitOrderReadServiceGrpc;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderRequest;
import net.firedevops.firemud.account.v1.ReadHeldOriginalCommitOrderResponse;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Standalone Account gRPC endpoint; authentication precedes request decoding and owner reads. */
public final class AccountDraftCommitOrderReadGrpcService
    extends AccountDraftCommitOrderReadServiceGrpc.AccountDraftCommitOrderReadServiceImplBase {
  private final AccountDraftCommitOrderReadService owner;
  private final String trustedNamespace;

  public AccountDraftCommitOrderReadGrpcService(
      AccountDraftCommitOrderReadService owner, String trustedNamespace) {
    this.owner = Objects.requireNonNull(owner, "owner");
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.trustedNamespace = trustedNamespace;
  }

  @Override
  public void readHeldOriginalCommitOrder(
      ReadHeldOriginalCommitOrderRequest request,
      StreamObserver<ReadHeldOriginalCommitOrderResponse> observer) {
    if (!requireAuthenticatedWorldPeer(observer)) return;

    final DraftCommitOrderReadEvidence.Request decoded;
    try {
      decoded = DraftCommitOrderReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical original Account COMMIT_ORDER read is required")
              .asRuntimeException());
      return;
    }
    if (!trustedNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Account COMMIT_ORDER read namespace differs from its peer")
              .asRuntimeException());
      return;
    }

    final ReadHeldOriginalCommitOrderResponse response;
    try {
      owner.requireHeld(decoded);
      response = DraftCommitOrderReadGrpcCodec.toHeldResponse(decoded);
    } catch (StatusRuntimeException failure) {
      observer.onError(sanitized(failure));
      return;
    } catch (RuntimeException failure) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Account COMMIT_ORDER owner read is unavailable")
              .asRuntimeException());
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private boolean requireAuthenticatedWorldPeer(StreamObserver<?> observer) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      observer.onError(
          Status.UNAUTHENTICATED
              .withDescription("Verified workload identity required")
              .asRuntimeException());
      return false;
    }
    String expected = "spiffe://firemud/ns/" + trustedNamespace + "/sa/world-management-service";
    if (expected.equals(peer.uri())) return true;
    observer.onError(
        Status.PERMISSION_DENIED
            .withDescription("Exact same-namespace World workload required")
            .asRuntimeException());
    return false;
  }

  private static StatusRuntimeException sanitized(StatusRuntimeException failure) {
    return switch (Status.fromThrowable(failure).getCode()) {
      case UNAUTHENTICATED ->
          Status.UNAUTHENTICATED
              .withDescription("Verified workload identity required")
              .asRuntimeException();
      case PERMISSION_DENIED ->
          Status.PERMISSION_DENIED
              .withDescription("Exact same-namespace World workload required")
              .asRuntimeException();
      case INVALID_ARGUMENT ->
          Status.INVALID_ARGUMENT
              .withDescription("Canonical COMMIT_ORDER read is required")
              .asRuntimeException();
      case FAILED_PRECONDITION ->
          Status.FAILED_PRECONDITION
              .withDescription("Exact held original Account COMMIT_ORDER is unavailable")
              .asRuntimeException();
      default ->
          Status.UNAVAILABLE
              .withDescription("Account COMMIT_ORDER owner read is unavailable")
              .asRuntimeException();
    };
  }
}
