package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.OriginalNewCapacityExecutionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionResponse;

/** Unregistered read-only owner receiver. Registration/PKI/source custody are separate gates. */
public final class CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService
    extends OriginalNewCapacityExecutionReadServiceGrpc
        .OriginalNewCapacityExecutionReadServiceImplBase {
  private final CanonicalGameplayOriginalNewCapacityExecutionOwner owner;
  private final CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard guard;
  private final String namespace;

  public CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService(
      CanonicalGameplayOriginalNewCapacityExecutionOwner owner,
      CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard guard,
      String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.guard = Objects.requireNonNull(guard);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Namespace required");
    this.namespace = namespace;
  }

  @Override
  public void readOriginalNewCapacityExecution(
      ReadOriginalNewCapacityExecutionRequest request,
      StreamObserver<ReadOriginalNewCapacityExecutionResponse> observer) {
    try {
      guard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    final OriginalNewCapacityExecutionReadGrpcCodec.Selector selector;
    try {
      selector = OriginalNewCapacityExecutionReadGrpcCodec.parseRequest(request);
      if (!namespace.equals(selector.request().targetNamespace()))
        throw new IllegalArgumentException("Namespace differs");
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    try {
      var actual = owner.readExact(selector.request(), selector.reservation());
      var readback =
          new OriginalNewCapacityExecutionReadGrpcCodec.Readback(
              actual.execution(),
              actual.disposition(),
              actual.physicalDigest(),
              actual.receiptDigest(),
              actual.canonicalReadback(),
              actual.currentOwnerId(),
              actual.currentOwnerFence());
      observer.onNext(OriginalNewCapacityExecutionReadGrpcCodec.toResponse(selector, readback));
      observer.onCompleted();
    } catch (RuntimeException unavailable) {
      fail(observer, Status.UNAVAILABLE);
    }
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Original NEW capacity execution is unavailable")
            .asRuntimeException());
  }
}
