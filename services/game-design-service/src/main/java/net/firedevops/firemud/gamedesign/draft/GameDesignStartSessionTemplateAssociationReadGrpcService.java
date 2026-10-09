package net.firedevops.firemud.gamedesign.draft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.v1.GameDesignStartSessionTemplateAssociationReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;

/** Standalone, deliberately unregistered authenticated Game Session association read endpoint. */
public final class GameDesignStartSessionTemplateAssociationReadGrpcService
    extends GameDesignStartSessionTemplateAssociationReadServiceGrpc
        .GameDesignStartSessionTemplateAssociationReadServiceImplBase {
  private final StartSessionTemplateAssociationReadService service;
  private final String workloadNamespace;

  public GameDesignStartSessionTemplateAssociationReadGrpcService(
      StartSessionTemplateAssociationReadService service, String workloadNamespace) {
    this.service = Objects.requireNonNull(service, "service");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readStartSessionTemplateAssociation(
      ReadStartSessionTemplateAssociationRequest request,
      StreamObserver<ReadStartSessionTemplateAssociationResponse> observer) {
    try {
      requirePeer();
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    final StartSessionTemplateAssociationReadEvidence.Request decoded;
    try {
      decoded = StartSessionTemplateAssociationReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical StartSession template association read required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("StartSession association namespace differs from peer")
              .asRuntimeException());
      return;
    }

    try {
      observer.onNext(
          StartSessionTemplateAssociationReadGrpcCodec.toResponse(service.read(decoded)));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(failure).getCode())
              .withDescription("Exact StartSession association read denied or unavailable")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design template association owner read unavailable")
              .asRuntimeException());
    }
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (!("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
        .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }
}
