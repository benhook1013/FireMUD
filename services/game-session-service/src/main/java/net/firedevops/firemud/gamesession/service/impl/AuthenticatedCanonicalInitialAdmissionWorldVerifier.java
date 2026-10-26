package net.firedevops.firemud.gamesession.service.impl;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.client.WorldCanonicalInitialAdmissionHoldClient;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionIntentSnapshot.IntentState;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionIntentRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reads Game Session's retained intent, then verifies its exact hold and current lifecycle with the
 * authenticated World owner.
 *
 * <p>The World state response is one observation only. Continuous termination exclusion depends on
 * World's durable hold owner keeping every termination path pending until the exact hold reaches an
 * authenticated terminal outcome; this verifier does not establish that owner invariant itself.
 * This type deliberately has no Spring registration or activation flag.
 */
public final class AuthenticatedCanonicalInitialAdmissionWorldVerifier
    implements CanonicalInitialAdmissionWorldVerifier {
  private final WorldCanonicalInitialAdmissionHoldClient worldClient;
  private final CanonicalInitialAdmissionIntentRepository intentRepository;
  private final String workloadNamespace;

  public AuthenticatedCanonicalInitialAdmissionWorldVerifier(
      WorldCanonicalInitialAdmissionHoldClient worldClient,
      CanonicalInitialAdmissionIntentRepository intentRepository,
      String workloadNamespace) {
    this.worldClient = Objects.requireNonNull(worldClient, "worldClient");
    this.intentRepository = Objects.requireNonNull(intentRepository, "intentRepository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Workload namespace must be one canonical DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public CanonicalInitialAdmissionWorldProof verify(CanonicalInitialAdmissionRequest expected) {
    Objects.requireNonNull(expected, "request");
    requireNoAmbientOwnerTransaction();
    if (!workloadNamespace.equals(expected.targetNamespace())) {
      throw new IllegalArgumentException(
          "Initial-admission request must use the configured workload namespace");
    }

    // The repository exposes this as a committed single-statement owner read and forbids an
    // ambient transaction; the later pointer writer independently relocks current sources.
    Optional<CanonicalInitialAdmissionIntentSnapshot> retainedRead =
        intentRepository.read(workloadNamespace, expected.initialAdmissionRequestId());
    CanonicalInitialAdmissionIntentSnapshot retained =
        retainedRead.orElseThrow(
            () -> new IllegalStateException("Retained initial-admission intent is unavailable"));
    HoldIdentity retainedIdentity = requireExactRetainedIntent(expected, retained);

    requireNoAmbientOwnerTransaction();
    HoldIdentity worldIdentity = worldClient.readIdentity(retained.holdRequest());
    requireSameIdentity(retainedIdentity, worldIdentity);

    WorldCanonicalInstanceLifecycleEvidence.Request freshLifecycleRequest =
        withFreshReadCorrelation(retained.lifecycleRequest());
    requireNoAmbientOwnerTransaction();
    WorldCanonicalInitialAdmissionHoldState worldState =
        worldClient.readState(worldIdentity, freshLifecycleRequest);
    requireCurrentPendingActiveState(worldIdentity, freshLifecycleRequest, worldState);

    CanonicalInitialAdmissionWorldProof proof = proofFromWorldState(worldState);
    proof.requireMatches(expected);
    return proof;
  }

  private HoldIdentity requireExactRetainedIntent(
      CanonicalInitialAdmissionRequest expected, CanonicalInitialAdmissionIntentSnapshot retained) {
    if (!workloadNamespace.equals(retained.holdRequest().targetNamespace())
        || !workloadNamespace.equals(retained.sourceBinding().targetNamespace())) {
      throw new IllegalStateException(
          "Retained initial-admission intent belongs to a different workload namespace");
    }
    if (retained.state() != IntentState.HOLD_ATTACHED || retained.holdIdentity() == null) {
      throw new IllegalStateException(
          "Retained initial-admission intent has no attached immutable World hold");
    }

    Request request = retained.holdRequest();
    HoldIdentity identity = retained.holdIdentity();
    if (!Arrays.equals(request.canonicalRequestBytes(), identity.canonicalRequestBytes())) {
      throw new IllegalStateException(
          "Retained World hold identity differs from the canonical Game Session intent");
    }

    CanonicalInitialAdmissionRequest retainedRequest =
        new CanonicalInitialAdmissionRequest(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.activeLifecycleEpoch(),
            request.expectedCatalogRevision(),
            CanonicalInitialAdmissionRequest.OriginKind.valueOf(
                request.initialAdmissionOrigin().name()),
            request.expectedPriorPointerVersion(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            identity.holdId(),
            identity.holdFence(),
            identity.holdBindingDigest());
    if (!retainedRequest.equals(expected)) {
      throw new IllegalStateException(
          "Retained initial-admission intent does not match the complete expected request");
    }

    // Snapshot construction checks the complete hold/lifecycle/source binding, including the
    // visible public catalog revision and the immutable launch-source digests.
    try {
      CanonicalInitialAdmissionIntentSnapshot.requireExactBinding(
          request, retained.lifecycleRequest(), retained.sourceBinding());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "Retained initial-admission intent is not bound to its canonical owner sources",
          malformed);
    }
    return identity;
  }

  private static void requireSameIdentity(HoldIdentity retained, HoldIdentity observed) {
    Objects.requireNonNull(observed, "World hold identity");
    if (!Arrays.equals(retained.canonicalBytes(), observed.canonicalBytes())) {
      throw new IllegalStateException(
          "Authenticated World hold identity differs from the retained Game Session identity");
    }
  }

  private static WorldCanonicalInstanceLifecycleEvidence.Request withFreshReadCorrelation(
      WorldCanonicalInstanceLifecycleEvidence.Request retained) {
    UUID readRequestId;
    do {
      readRequestId = UUID.randomUUID();
    } while (readRequestId.equals(retained.readRequestId()));
    return new WorldCanonicalInstanceLifecycleEvidence.Request(
        retained.schemaVersion(),
        readRequestId,
        retained.targetNamespace(),
        retained.canonicalTenantId(),
        retained.worldSlug(),
        retained.canonicalGameInstanceId(),
        retained.playableStateNamespaceId(),
        retained.playableStateScope(),
        retained.publicProduction(),
        retained.controlPlaneRequestId(),
        retained.canonicalVersionId(),
        retained.expectedDescriptorRequestDigest(),
        retained.expectedDescriptorResultDigest(),
        retained.expectedReleaseAttestationDigest());
  }

  private static void requireCurrentPendingActiveState(
      HoldIdentity requestedIdentity,
      WorldCanonicalInstanceLifecycleEvidence.Request requestedLifecycle,
      WorldCanonicalInitialAdmissionHoldState observed) {
    Objects.requireNonNull(observed, "World hold state");
    if (!Arrays.equals(
        requestedIdentity.canonicalBytes(), observed.holdIdentity().canonicalBytes())) {
      throw new IllegalStateException(
          "Authenticated World hold-state response changed the exact immutable identity");
    }
    if (!requestedLifecycle.equals(observed.lifecycleEvidence().request())) {
      throw new IllegalStateException(
          "Authenticated World hold-state response changed the fresh lifecycle read request");
    }
    if (observed.holdStatus() != HoldStatus.PENDING
        || !"ACTIVE".equals(observed.lifecycleEvidence().lifecycleStatus())
        || observed.lifecycleEvidence().lifecycleEpoch()
            != requestedIdentity.request().activeLifecycleEpoch()) {
      throw new IllegalStateException(
          "Authenticated World hold is not pending at its exact ACTIVE lifecycle epoch");
    }
  }

  private static CanonicalInitialAdmissionWorldProof proofFromWorldState(
      WorldCanonicalInitialAdmissionHoldState worldState) {
    Request worldRequest = worldState.holdIdentity().request();
    return new CanonicalInitialAdmissionWorldProof(
        worldRequest.initialAdmissionRequestId(),
        worldRequest.initialAdmissionRequestDigest(),
        worldRequest.targetNamespace(),
        worldRequest.canonicalTenantId(),
        worldRequest.worldSlug(),
        worldRequest.realmId(),
        worldRequest.playableStateNamespaceId(),
        worldRequest.playableStateScope(),
        worldRequest.canonicalGameInstanceId(),
        worldRequest.canonicalVersionId(),
        worldState.lifecycleEvidence().lifecycleStatus(),
        worldState.lifecycleEvidence().lifecycleEpoch(),
        CanonicalInitialAdmissionRequest.OriginKind.valueOf(
            worldRequest.initialAdmissionOrigin().name()),
        worldRequest.expectedCatalogRevision(),
        worldRequest.expectedPriorPointerVersion(),
        worldState.holdIdentity().holdId(),
        worldState.holdIdentity().holdFence(),
        worldState.holdIdentity().holdBindingDigest());
  }

  private static void requireNoAmbientOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Canonical initial-admission World verification requires no ambient owner transaction");
    }
  }
}
