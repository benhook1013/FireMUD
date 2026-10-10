package net.firedevops.firemud.gamedesign.draft;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.StartSessionPublishedRealmPolicyReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.publication.StartSessionPublishedRealmPolicyReadService;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.v1.GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyRequest;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionPublishedRealmPolicyResponse;
import org.jooq.exception.DataAccessException;

/** Standalone, deliberately unregistered authenticated historical policy-content endpoint. */
public final class GameDesignStartSessionPublishedRealmPolicyReadGrpcService
    extends GameDesignStartSessionPublishedRealmPolicyReadServiceGrpc
        .GameDesignStartSessionPublishedRealmPolicyReadServiceImplBase {
  private final StartSessionTemplateAssociationReadService associationReader;
  private final StartSessionPublishedRealmPolicyReadService publishedPolicyReader;
  private final String workloadNamespace;

  public GameDesignStartSessionPublishedRealmPolicyReadGrpcService(
      StartSessionTemplateAssociationReadService associationReader,
      StartSessionPublishedRealmPolicyReadService publishedPolicyReader,
      String workloadNamespace) {
    this.associationReader = Objects.requireNonNull(associationReader, "associationReader");
    this.publishedPolicyReader =
        Objects.requireNonNull(publishedPolicyReader, "publishedPolicyReader");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Design workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public void readStartSessionPublishedRealmPolicy(
      ReadStartSessionPublishedRealmPolicyRequest request,
      StreamObserver<ReadStartSessionPublishedRealmPolicyResponse> observer) {
    try {
      requirePeer();
      if (SessionContext.hasAuthenticatedCallerContext()) {
        throw Status.PERMISSION_DENIED
            .withDescription("Workload-only published-policy read required")
            .asRuntimeException();
      }
    } catch (StatusRuntimeException denied) {
      observer.onError(denied);
      return;
    }

    try {
      StartSessionPublishedRealmPolicyReadGrpcCodec.requireRequestSerializedSize(request);
    } catch (IllegalArgumentException oversized) {
      observer.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Published-policy request exceeds the local wire bound")
              .asRuntimeException());
      return;
    }

    final StartSessionPublishedRealmPolicyReadGrpcCodec.WireRequest decoded;
    try {
      decoded = StartSessionPublishedRealmPolicyReadGrpcCodec.fromRequest(request);
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Canonical schema-1 published-policy read required")
              .asRuntimeException());
      return;
    }
    if (!workloadNamespace.equals(request.getTargetNamespace())) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Published-policy namespace differs from peer")
              .asRuntimeException());
      return;
    }

    final net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence
            .Result
        authorizationResult;
    try {
      // This concrete read independently confirms Account's original operation before its own SQL.
      authorizationResult = associationReader.read(decoded.authorizationRequest());
    } catch (StatusRuntimeException deniedOrUnavailable) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(deniedOrUnavailable).getCode())
              .withDescription("Exact StartSession authorization read denied or unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Exact StartSession authorization read unavailable")
              .asRuntimeException());
      return;
    }

    final StartSessionPublishedRealmPolicyReadGrpcCodec.ReadRequest authorized;
    try {
      // No policy/source owner query is reachable until the full Account-confirmed association,
      // supplied descriptor, release attestation, and TargetProof agree.
      authorized =
          StartSessionPublishedRealmPolicyReadGrpcCodec.authorize(decoded, authorizationResult);
    } catch (RuntimeException invalidOrConflicting) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact StartSession descriptor or TargetProof is unavailable")
              .asRuntimeException());
      return;
    }

    final StartSessionPublishedRealmPolicyReadService.ReadResult ownerResult;
    try {
      ownerResult =
          publishedPolicyReader.read(
              new StartSessionPublishedRealmPolicyReadService.ReadRequest(
                  authorized.descriptor(), authorized.releaseAttestation(), authorized.target()));
    } catch (StatusRuntimeException deniedOrUnavailable) {
      observer.onError(
          Status.fromCode(Status.fromThrowable(deniedOrUnavailable).getCode())
              .withDescription("Exact published source and policy read denied or unavailable")
              .asRuntimeException());
      return;
    } catch (DataAccessException unavailable) {
      observer.onError(
          Status.UNAVAILABLE
              .withDescription("Published source and policy owner storage unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException invalidOrUnavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact published source or policy evidence is unavailable")
              .asRuntimeException());
      return;
    }

    try {
      ReadStartSessionPublishedRealmPolicyResponse response =
          StartSessionPublishedRealmPolicyReadGrpcCodec.toResponse(
              decoded,
              authorized,
              authorizationResult,
              ownerResult.sourceEvidence(),
              ownerResult.publishedSet());
      observer.onNext(response);
      observer.onCompleted();
    } catch (RuntimeException invalidOrOversized) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Complete published-policy evidence could not be returned")
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
