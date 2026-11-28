package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.v1.ReadRunOwnedFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.ReadRunOwnedFixtureInitialAdmissionOwnerResponse;
import net.firedevops.firemud.gamesession.v1.RunOwnedFixtureInitialAdmissionOwnerReadServiceGrpc;

/** Explicit fixture-only owner read requiring the retained World recipient certificate. */
public final class RunOwnedFixtureInitialAdmissionOwnerReadGrpcService
    extends RunOwnedFixtureInitialAdmissionOwnerReadServiceGrpc
        .RunOwnedFixtureInitialAdmissionOwnerReadServiceImplBase {
  private static final int MAX_REQUEST_BYTES = 64 * 1024;
  private static final String AUTHORITY_KIND = "RUN_OWNED_FIXTURE";

  private final RunOwnedCanonicalLaunchPreparationOwner launchOwner;
  private final CanonicalInitialAdmissionRepository admissions;

  public RunOwnedFixtureInitialAdmissionOwnerReadGrpcService(
      RunOwnedCanonicalLaunchPreparationOwner launchOwner,
      CanonicalInitialAdmissionRepository admissions) {
    this.launchOwner = Objects.requireNonNull(launchOwner, "launchOwner");
    this.admissions = Objects.requireNonNull(admissions, "admissions");
  }

  @Override
  public void readRunOwnedFixtureInitialAdmissionOwner(
      ReadRunOwnedFixtureInitialAdmissionOwnerRequest request,
      StreamObserver<ReadRunOwnedFixtureInitialAdmissionOwnerResponse> observer) {
    RunOwnedInitialAdmissionLaunchEvidence fixed;
    try {
      fixed = launchOwner.loadFixedVerifiedEvidenceForWorldPeer();
    } catch (AdminAuthorizationException unauthorized) {
      observer.onError(
          Status.PERMISSION_DENIED
              .withDescription("Fixed World peer required")
              .asRuntimeException());
      return;
    } catch (RuntimeException unavailable) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Fixed fixture evidence unavailable")
              .asRuntimeException());
      return;
    }

    HoldIdentity identity;
    try {
      if (request == null
          || !request.hasHold()
          || !AUTHORITY_KIND.equals(request.getAuthorityKind())
          || request.getSerializedSize() > MAX_REQUEST_BYTES
          || !request.getUnknownFields().asMap().isEmpty()) {
        throw new IllegalArgumentException("Malformed fixture owner read");
      }
      requireReadCorrelation(request.getReadRequestId(), fixed.fixtureCapability().operationId());
      identity = CanonicalInitialAdmissionOwnerGrpcCodec.parseRequest(request.getHold());
    } catch (IllegalArgumentException malformed) {
      observer.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Malformed fixture owner read")
              .asRuntimeException());
      return;
    }

    try {
      var selected = identity.request();
      String provenance = fixed.fixturePolicySeal().evidenceDigest();
      if (!provenance.equals(request.getFixturePolicySealEvidenceDigest())
          || !fixed.sourceEvidence().targetNamespace().equals(selected.targetNamespace())
          || !fixed.sourceEvidence().canonicalTenantId().equals(selected.canonicalTenantId())
          || !fixed.sourceEvidence().worldSlug().equals(selected.worldSlug())
          || !("run-owned-fixture-" + fixed.fixtureCapability().operationId())
              .equals(selected.initialAdmissionRequestId())
          || selected.initialAdmissionOrigin() != InitialAdmissionOrigin.NO_PRIOR_POINTER) {
        throw new IllegalArgumentException("Hold differs from the fixed fixture source");
      }
      var launch = launchOwner.readFixedFixtureLaunch(fixed);
      if (!launch.gameInstanceUuid().equals(selected.canonicalGameInstanceId())) {
        throw new IllegalArgumentException("Hold differs from the fixed fixture launch");
      }
      var retained =
          admissions
              .readRunOwnedFixture(
                  selected.targetNamespace(), selected.initialAdmissionRequestId(), provenance)
              .orElseThrow(() -> new IllegalStateException("No exact fixture owner proof"));
      var proof =
          CanonicalInitialAdmissionOwnerReadGrpcService.toCanonicalProof(identity, retained);
      observer.onNext(
          ReadRunOwnedFixtureInitialAdmissionOwnerResponse.newBuilder()
              .setReadRequestId(request.getReadRequestId())
              .setAuthorityKind(AUTHORITY_KIND)
              .setFixturePolicySealEvidenceDigest(provenance)
              .setProof(CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(identity, proof))
              .build());
      observer.onCompleted();
    } catch (IllegalArgumentException | IllegalStateException inconsistent) {
      observer.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact fixture owner proof unavailable")
              .asRuntimeException());
    } catch (RuntimeException failure) {
      observer.onError(
          Status.INTERNAL.withDescription("Fixture owner read failed").asRuntimeException());
    }
  }

  private static void requireReadCorrelation(String readRequestId, String fixtureOperationId) {
    UUID correlation = UUID.fromString(readRequestId);
    if (!correlation.toString().equals(readRequestId)
        || correlation.equals(new UUID(0L, 0L))
        || readRequestId.equals(fixtureOperationId)) {
      throw new IllegalArgumentException("Fixture owner read requires a fresh correlation");
    }
  }
}
