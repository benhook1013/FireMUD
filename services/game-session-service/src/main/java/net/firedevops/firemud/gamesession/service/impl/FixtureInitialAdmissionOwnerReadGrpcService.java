package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalInitialAdmissionOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.v1.GameSessionFixtureInitialAdmissionOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerResponse;

/** Unregistered, fixed-capability, exact World-peer fixture-purpose terminal adapter. */
public final class FixtureInitialAdmissionOwnerReadGrpcService
    extends GameSessionFixtureInitialAdmissionOwnerReadServiceGrpc
        .GameSessionFixtureInitialAdmissionOwnerReadServiceImplBase {
  private final RunOwnedCanonicalLaunchPreparationOwner fixedSource;
  private final RunOwnedCanonicalInitialAdmissionOwner owner;

  public FixtureInitialAdmissionOwnerReadGrpcService(
      RunOwnedCanonicalLaunchPreparationOwner fixedSource,
      RunOwnedCanonicalInitialAdmissionOwner owner) {
    this.fixedSource = Objects.requireNonNull(fixedSource);
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public void getFixtureInitialAdmissionOwner(
      GetFixtureInitialAdmissionOwnerRequest request,
      StreamObserver<GetFixtureInitialAdmissionOwnerResponse> observer) {
    Objects.requireNonNull(observer);
    try {
      // Authentication, actual immutable receipt and recipient leaf precede caller-selectable
      // reads.
      var source = fixedSource.loadFixedVerifiedEvidenceForWorldPeer();
      var lookup = FixtureInitialAdmissionGrpcCodec.fromTerminalRequest(request);
      if (lookup.readRequestId().toString().equals(source.fixtureCapability().operationId())) {
        throw new IllegalArgumentException("Fixture operation is not a fresh read correlation");
      }
      if (!source
          .sourceEvidence()
          .targetNamespace()
          .equals(lookup.request().selector().targetNamespace()))
        throw new AdminAuthorizationException("Fixture namespace mismatch");
      var proof =
          owner.readTerminalForWorldPeer(
              lookup.request().selector().targetNamespace(),
              lookup.request().selector().initialAdmissionRequestId());
      if (proof.isEmpty()) {
        observer.onError(
            Status.NOT_FOUND
                .withDescription("Exact fixture attempt is unavailable")
                .asRuntimeException());
        return;
      }
      observer.onNext(FixtureInitialAdmissionGrpcCodec.toTerminalResponse(lookup, proof.get()));
      observer.onCompleted();
    } catch (AdminAuthorizationException denied) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Fixture owner caller is not authorized")
              .asRuntimeException());
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Fixture owner request is malformed")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact fixture owner evidence is unavailable")
              .asRuntimeException());
    }
  }
}
