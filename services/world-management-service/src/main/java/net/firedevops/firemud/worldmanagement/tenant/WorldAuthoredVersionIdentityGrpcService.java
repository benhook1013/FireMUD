package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityEvidence;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionRequest;
import net.firedevops.firemud.worldmanagement.v1.AssociateAuthoredWorldVersionResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldAuthoredVersionIdentityServiceGrpc;

/** Unregistered adapter to the existing immutable World authored-Version association owner. */
public final class WorldAuthoredVersionIdentityGrpcService
    extends WorldAuthoredVersionIdentityServiceGrpc.WorldAuthoredVersionIdentityServiceImplBase {
  private final WorldAuthoredVersionIdentityService identities;
  private final String namespace;

  public WorldAuthoredVersionIdentityGrpcService(
      WorldAuthoredVersionIdentityService identities, String namespace) {
    this.identities = Objects.requireNonNull(identities, "identities");
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical World namespace required");
    }
    this.namespace = namespace;
  }

  @Override
  public void associateAuthoredWorldVersion(
      AssociateAuthoredWorldVersionRequest wire,
      StreamObserver<AssociateAuthoredWorldVersionResponse> observer) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("game-design-service")
        || !peer.isInNamespace(namespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      deny(
          observer,
          Status.PERMISSION_DENIED,
          "Verified same-namespace Game Design workload required");
      return;
    }
    WorldAuthoredVersionIdentityEvidence.Request request;
    try {
      request = WorldAuthoredVersionIdentityGrpcCodec.fromRequest(wire);
    } catch (IllegalArgumentException invalid) {
      deny(
          observer,
          Status.INVALID_ARGUMENT,
          "Canonical World Version association request required");
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED, "World Version association namespace mismatch");
      return;
    }
    try {
      var receipt =
          identities.associate(
              request.targetNamespace(),
              request.canonicalTenantId(),
              request.worldSlug(),
              request.sourceOperationId(),
              request.sourceEvidenceDigest(),
              request.expectedCanonicalVersionId(),
              request.gameDesignVersionId(),
              request.readRequestId());
      var intake = receipt.sourceIntakeReceipt();
      var publicIntake =
          new WorldAuthoredSourceIntakeGrpcCodec.CommittedReceipt(
              intake.schemaVersion(),
              intake.targetNamespace(),
              intake.intakeRequestId(),
              intake.operationId(),
              intake.canonicalTenantId(),
              intake.worldSlug(),
              intake.sourceOperationId(),
              intake.sourceEvidenceDigest(),
              intake.requestDigest(),
              intake.receiptDigest());
      var result =
          new WorldAuthoredVersionIdentityEvidence.Result(
              request, receipt.operationId(), publicIntake, receipt.versionStateEvidence());
      observer.onNext(WorldAuthoredVersionIdentityGrpcCodec.toResponse(result));
      observer.onCompleted();
    } catch (SecurityException denied) {
      deny(observer, Status.PERMISSION_DENIED, "World Version association authorization denied");
    } catch (IllegalArgumentException invalid) {
      deny(observer, Status.INVALID_ARGUMENT, "World Version association input is invalid");
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException unavailable) {
      deny(observer, Status.UNAVAILABLE, "World Version association storage unavailable");
    } catch (RuntimeException failure) {
      deny(
          observer,
          Status.FAILED_PRECONDITION,
          "World Version association has no exact committed proof");
    }
  }

  private static void deny(StreamObserver<?> observer, Status status, String description) {
    observer.onError(status.withDescription(description).asRuntimeException());
  }
}
