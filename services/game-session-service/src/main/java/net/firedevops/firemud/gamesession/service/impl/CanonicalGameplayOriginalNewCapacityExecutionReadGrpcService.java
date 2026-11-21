package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.function.Function;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec.Selector;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.OriginalNewCapacityExecutionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReadHistoricalOriginalNewCapacityFinalResponse;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionResponse;

/** Unregistered read-only owner receiver. Registration/PKI/source custody are separate gates. */
public final class CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService
    extends OriginalNewCapacityExecutionReadServiceGrpc
        .OriginalNewCapacityExecutionReadServiceImplBase {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Shares the injected custody owner without exposing it")
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
    respond(
        request,
        observer,
        selector -> {
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
          return OriginalNewCapacityExecutionReadGrpcCodec.toResponse(selector, readback);
        });
  }

  @Override
  public void readHistoricalOriginalNewCapacityFinal(
      ReadOriginalNewCapacityExecutionRequest request,
      StreamObserver<ReadHistoricalOriginalNewCapacityFinalResponse> observer) {
    respond(
        request,
        observer,
        selector ->
            OriginalNewCapacityExecutionReadGrpcCodec.toHistoricalResponse(
                selector, owner.readHistoricalFinal(selector.request(), selector.reservation())));
  }

  private <Response> void respond(
      ReadOriginalNewCapacityExecutionRequest request,
      StreamObserver<Response> observer,
      Function<Selector, Response> read) {
    try {
      guard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    final Selector selector;
    try {
      selector = OriginalNewCapacityExecutionReadGrpcCodec.parseRequest(request);
      if (!namespace.equals(selector.request().targetNamespace()))
        throw new IllegalArgumentException("Namespace differs");
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    try {
      observer.onNext(read.apply(selector));
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
