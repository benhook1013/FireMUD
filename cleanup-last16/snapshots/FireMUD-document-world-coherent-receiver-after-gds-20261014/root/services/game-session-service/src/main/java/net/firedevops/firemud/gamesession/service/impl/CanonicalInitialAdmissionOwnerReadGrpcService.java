package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.gamesession.CanonicalInitialAdmissionOwnerGrpcCodec;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionFixtureCoordinator;
import net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository.CanonicalInitialAdmissionReconciliationRequiredException;
import net.firedevops.firemud.gamesession.v1.CanonicalInitialAdmissionOwnerProofReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalInitialAdmissionOwnerProofResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.grpc.server.service.GrpcService;

/**
 * Default-off read-only producer for exact World initial-admission owner reconciliation.
 *
 * <p>The registered runtime surface is limited to the fixed verified run-owned fixture. The
 * two-argument constructor remains available for direct Human-owner tests. This point-in-time read
 * does not acquire or renew a lease or guarantee the proof remains valid through a later transaction
 * owned by another service.
 */
@GrpcService
@ConditionalOnProperty(
    name = "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED", havingValue = "true")
public final class CanonicalInitialAdmissionOwnerReadGrpcService
    extends CanonicalInitialAdmissionOwnerProofReadServiceGrpc
        .CanonicalInitialAdmissionOwnerProofReadServiceImplBase {
  private final CanonicalInitialAdmissionRepository repository;
  private final CanonicalInitialAdmissionOwnerReadWorkloadGuard workloadGuard;
  private RunOwnedInitialAdmissionFixtureCoordinator fixtureCoordinator;
  private boolean fixtureRuntimeOnly;

  public CanonicalInitialAdmissionOwnerReadGrpcService(
      CanonicalInitialAdmissionRepository repository,
      CanonicalInitialAdmissionOwnerReadWorkloadGuard workloadGuard) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.workloadGuard = Objects.requireNonNull(workloadGuard, "workloadGuard");
  }

  /** Runtime registration is fixed-capability-only; the direct Human test constructor stays isolated. */
  @Autowired
  public CanonicalInitialAdmissionOwnerReadGrpcService(
      RunOwnedInitialAdmissionFixtureCoordinator fixtureCoordinator,
      @Value("${firemud.grpc.workload-namespace:}") String workloadNamespace) {
    this.repository = null;
    this.workloadGuard = new CanonicalInitialAdmissionOwnerReadWorkloadGuard(workloadNamespace);
    this.fixtureCoordinator = Objects.requireNonNull(fixtureCoordinator, "fixtureCoordinator");
    this.fixtureRuntimeOnly = true;
  }

  @Override
  public void getCanonicalInitialAdmissionOwnerProof(
      GetCanonicalInitialAdmissionOwnerProofRequest request,
      StreamObserver<GetCanonicalInitialAdmissionOwnerProofResponse> responseObserver) {
    Objects.requireNonNull(responseObserver, "responseObserver");
    RunOwnedInitialAdmissionLaunchEvidence fixedEvidence;
    try {
      workloadGuard.requireWorldManagementOwnerReadCaller();
      fixedEvidence = fixtureRuntimeOnly
          ? fixtureCoordinator.loadFixedVerifiedEvidenceForWorldPeer() : null;
    } catch (AdminAuthorizationException unauthorized) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Owner proof caller is not authorized")
              .asRuntimeException());
      return;
    } catch (IllegalStateException unavailable) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION.withDescription("Fixed fixture evidence is unavailable")
              .asRuntimeException());
      return;
    } catch (RuntimeException invalidFixedEvidence) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION.withDescription("Fixed fixture evidence is invalid")
              .asRuntimeException());
      return;
    }

    if (request == null) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Owner proof request is required")
              .asRuntimeException());
      return;
    }

    HoldIdentity identity;
    try {
      identity = CanonicalInitialAdmissionOwnerGrpcCodec.parseRequest(request);
    } catch (IllegalArgumentException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Owner proof request is malformed")
              .asRuntimeException());
      return;
    }

    try {
      workloadGuard.requireWorldManagementOwnerReadCaller(identity.request().targetNamespace());

      if (fixtureRuntimeOnly
          && identity.request().authorityKind() != AuthorityKind.RUN_OWNED_FIXTURE) {
        throw new AdminAuthorizationException(
            "This owner proof route accepts only its fixed run-owned fixture authority");
      }

      Optional<CanonicalInitialAdmissionOwnerProof> readback;
      if (identity.request().authorityKind() == AuthorityKind.RUN_OWNED_FIXTURE) {
        if (!fixtureRuntimeOnly || fixtureCoordinator == null) {
          responseObserver.onError(
              Status.FAILED_PRECONDITION
                  .withDescription("Fixed fixture owner proof read is disabled")
                  .asRuntimeException());
          return;
        }
        RunOwnedInitialAdmissionLaunchEvidence evidence = Objects.requireNonNull(fixedEvidence);
        if (!evidence.sourceEvidence().targetNamespace().equals(identity.request().targetNamespace())
            || !evidence.sourceEvidence().canonicalTenantId().equals(identity.request().canonicalTenantId())
            || !identity
                .request()
                .fixtureProvenanceDigest()
                .equals(evidence.fixturePolicySeal().evidenceDigest())) {
          throw new IllegalArgumentException(
              "Fixture owner proof selector differs from the fixed verified source");
        }
        readback =
            fixtureCoordinator.readFixedFixtureAdmission(
                evidence,
                identity.request().targetNamespace(),
                identity.request().initialAdmissionRequestId());
      } else {
        readback =
            repository.read(
                identity.request().targetNamespace(),
                identity.request().initialAdmissionRequestId());
      }
      if (readback.isEmpty()) {
        responseObserver.onError(
            Status.FAILED_PRECONDITION
                .withDescription("Exact durable owner proof is not available")
                .asRuntimeException());
        return;
      }
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          toCanonicalProof(identity, readback.get());
      responseObserver.onNext(CanonicalInitialAdmissionOwnerGrpcCodec.toResponse(identity, proof));
      responseObserver.onCompleted();
    } catch (AdminAuthorizationException unauthorized) {
      responseObserver.onError(
          Status.PERMISSION_DENIED
              .withDescription("Owner proof caller is not authorized")
              .asRuntimeException());
    } catch (CanonicalInitialAdmissionReconciliationRequiredException ambiguous) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact durable owner proof is ambiguous")
              .asRuntimeException());
    } catch (IllegalArgumentException mismatchedOwnerReadback) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Exact durable owner proof is ambiguous")
              .asRuntimeException());
    } catch (RuntimeException failure) {
      responseObserver.onError(
          Status.INTERNAL
              .withDescription("Canonical owner proof read failed")
              .asRuntimeException());
    }
  }

  private static GameSessionCanonicalInitialAdmissionOwnerProof toCanonicalProof(
      HoldIdentity identity, CanonicalInitialAdmissionOwnerProof ownerProof) {
    Request request = identity.request();
    if (!request.initialAdmissionRequestId().equals(ownerProof.initialAdmissionRequestId())
        || !request.initialAdmissionRequestDigest().equals(ownerProof.requestDigest())
        || !request.targetNamespace().equals(ownerProof.targetNamespace())
        || !request.canonicalTenantId().equals(ownerProof.canonicalTenantId())
        || !request.worldSlug().equals(ownerProof.worldSlug())
        || !request.realmId().equals(ownerProof.realmId())
        || !request.playableStateNamespaceId().equals(ownerProof.playableStateNamespaceId())
        || !request.playableStateScope().equals(ownerProof.playableStateScope())
        || !request.canonicalGameInstanceId().equals(ownerProof.canonicalGameInstanceId())
        || !request.canonicalVersionId().equals(ownerProof.canonicalVersionId())
        || request.activeLifecycleEpoch() != ownerProof.activeLifecycleEpoch()
        || request.expectedCatalogRevision() != ownerProof.expectedCatalogRevision()
        || !request.initialAdmissionOrigin().name().equals(ownerProof.originKind().name())
        || !Objects.equals(
            request.expectedPriorPointerVersion(), ownerProof.expectedPriorPointerVersion())
        || !identity.holdId().equals(ownerProof.holdId())
        || !identity.holdFence().equals(ownerProof.holdFence())
        || request.authorityKind() != ownerProof.authorityKind()
        || !Objects.equals(
            request.fixtureProvenanceDigest(), ownerProof.fixtureProvenanceDigest())
        || !identity.holdBindingDigest().equals(ownerProof.holdBindingDigest())) {
      throw new IllegalArgumentException("Owner readback changed the requested immutable tuple");
    }
    return new GameSessionCanonicalInitialAdmissionOwnerProof(
        identity,
        GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.valueOf(ownerProof.outcome().name()),
        ownerProof.committedPointerVersion(),
        ownerProof.auditEventId(),
        ownerProof.proofDigest(),
        ownerProof.positiveDurableAbort(),
        ownerProof.terminalAt());
  }
}
