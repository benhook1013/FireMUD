package net.firedevops.firemud.gamedesign.publication;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GameDesignAuthoredInitialActorCoreReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreRequest;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;

/**
 * Standalone, unregistered workload-authenticated authored-core projection endpoint.
 *
 * <p>The handler's 128 KiB request check runs after gRPC has received and buffered the message. Any
 * future receiving server composition must enforce the same inbound limit before registering this
 * adapter; the handler guard remains defense in depth.
 */
public final class GrpcAuthoredInitialActorCoreReadService
    extends GameDesignAuthoredInitialActorCoreReadServiceGrpc
        .GameDesignAuthoredInitialActorCoreReadServiceImplBase {
  public static final int MAX_REQUEST_WIRE_BYTES = 128 * 1024;
  private final AuthoredInitialActorCoreReadService service;
  private final String workloadNamespace;

  public GrpcAuthoredInitialActorCoreReadService(
      AuthoredInitialActorCoreReadService service, String workloadNamespace) {
    this.service = Objects.requireNonNull(service, "service");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readAuthoredInitialActorCore(
      ReadAuthoredInitialActorCoreRequest request,
      StreamObserver<ReadAuthoredInitialActorCoreResponse> observer) {
    try {
      requirePeer();
      if (SessionContext.hasAuthenticatedCallerContext()) {
        throw Status.PERMISSION_DENIED
            .withDescription("Workload-only authored-core read required")
            .asRuntimeException();
      }
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }
    if (request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      observer.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Authored-core request exceeds its bounded wire size")
              .asRuntimeException());
      return;
    }

    final ReadRequest decoded;
    try {
      decoded = AuthoredInitialActorCoreReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Exact canonical authored-core replay request required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(decoded.associationRequest().targetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authored-core target namespace differs from peer")
              .asRuntimeException());
      return;
    }
    try {
      observer.onNext(AuthoredInitialActorCoreReadGrpcCodec.toResponse(service.read(decoded)));
      observer.onCompleted();
    } catch (StatusRuntimeException failure) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(failure).getCode())
              .withDescription("Exact authored-core owner read denied or unavailable")
              .asRuntimeException());
    } catch (IllegalArgumentException invalidResult) {
      observer.onError(
          Status.INTERNAL
              .withDescription("Game Design returned invalid authored-core owner evidence")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Game Design authored-core owner read unavailable")
              .asRuntimeException());
    }
  }

  private void requirePeer() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified same-namespace Game Session or Entity workload required")
          .asRuntimeException();
    }
    String expectedPrefix = "spiffe://firemud/ns/" + workloadNamespace + "/sa/";
    if (!workloadNamespace.equals(peer.namespace())
        || !("game-session-service".equals(peer.service())
            || "entity-management-service".equals(peer.service()))
        || !(expectedPrefix + peer.service()).equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session or Entity workload required")
          .asRuntimeException();
    }
  }
}
