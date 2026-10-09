package net.firedevops.firemud.entitymanagement.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/**
 * Maps exact authenticated Game Session and sealed Game Design source evidence to Entity's roster
 * target.
 */
public final class GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter
    implements CanonicalGameplayRosterOwnerEvidencePort {
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private final GameSessionCanonicalGameplayRosterOwnerReadClient gameSessionClient;
  private final String workloadNamespace;

  public GameSessionCanonicalGameplayRosterOwnerEvidenceAdapter(
      GameSessionCanonicalGameplayRosterOwnerReadClient gameSessionClient,
      String workloadNamespace) {
    this.gameSessionClient = Objects.requireNonNull(gameSessionClient, "gameSessionClient");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public CanonicalGameplayRosterOwnerEvidence resolveCurrentTarget(
      CanonicalGameplayRosterReadRequest request) {
    Objects.requireNonNull(request, "request");
    CanonicalGameplayRosterTarget expected = request.expectedTarget();
    if (expected.playableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || expected.entryPolicy() != CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY) {
      throw new IllegalArgumentException(
          "Canonical gameplay roster owner read requires SHARED PRESEEDED_ONLY target");
    }

    CanonicalGameplayRosterOwnerReadEvidence.Request ownerReadRequest =
        new CanonicalGameplayRosterOwnerReadEvidence.Request(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            workloadNamespace,
            expected.tenantUuid(),
            expected.worldSlug(),
            expected.realmUuid(),
            expected.realmSlug(),
            expected.playableStateNamespaceId(),
            "SHARED",
            expected.gameInstanceUuid(),
            expected.canonicalVersionUuid(),
            expected.catalogRevision(),
            expected.pointerVersion(),
            expected.activeWorldEpoch());

    CanonicalGameplayRosterOwnerReadEvidence evidence;
    try {
      evidence = gameSessionClient.getCanonicalGameplayRosterOwnerRead(ownerReadRequest);
    } catch (RuntimeException unavailable) {
      throw new OwnerEvidenceUnavailableException(unavailable);
    }
    if (evidence == null || !ownerReadRequest.equals(evidence.request())) {
      throw new OwnerEvidenceUnavailableException();
    }

    try {
      return mapCompleteEvidence(request, ownerReadRequest, evidence);
    } catch (RuntimeException invalid) {
      if (invalid instanceof OwnerEvidenceUnavailableException unavailable) {
        throw unavailable;
      }
      throw new OwnerEvidenceUnavailableException(invalid);
    }
  }

  private CanonicalGameplayRosterOwnerEvidence mapCompleteEvidence(
      CanonicalGameplayRosterReadRequest request,
      CanonicalGameplayRosterOwnerReadEvidence.Request ownerReadRequest,
      CanonicalGameplayRosterOwnerReadEvidence evidence) {
    if (!request.requestUuid().equals(evidence.request().requestUuid())
        || !request.canonicalAccountUuid().equals(evidence.request().canonicalAccountUuid())) {
      throw new IllegalArgumentException("Game Session evidence changed request/account binding");
    }
    GameSessionCanonicalInitialAdmissionOwnerProof proof = evidence.gameSessionOwnerProof();
    if (proof == null
        || proof.outcome() != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
        || proof.committedPointerVersion() == null
        || proof.committedPointerVersion() <= 0L
        || proof.auditEventId() == null
        || proof.auditEventId() <= 0L
        || proof.positiveDurableAbort()
        || proof.terminalAt() == null) {
      throw new IllegalArgumentException("Complete terminal COMMITTED Game Session proof required");
    }

    WorldCanonicalInitialAdmissionHold.Request holdRequest = proof.holdIdentity().request();
    requireOwnerProofMatches(ownerReadRequest, holdRequest, proof);

    PublishedRealmEntryPolicySetEvidence policySet = evidence.publishedPolicySetEvidence();
    if (policySet != null) {
      // Reverify the complete set; its sealed release digest stays bound here, not in a caller
      // echo.
      policySet = policySet.requireValidDigest();
    }
    if (policySet == null
        || policySet.policyCount() < 1
        || policySet.policyCount() != policySet.policies().size()
        || !policySet.target().canonicalTenantId().equals(holdRequest.canonicalTenantId())
        || !policySet.target().canonicalVersionId().equals(holdRequest.canonicalVersionId())
        || policySet.versionNumber() <= 0
        || policySet.publicationVersionStateEpoch() <= 0L
        || !hasPrefixedDigest(policySet.publishedReleaseBundleDigest())
        || !hasPrefixedDigest(policySet.policySetDigest())) {
      throw new IllegalArgumentException(
          "Complete sealed Game Design publication evidence required");
    }

    List<PublishedRealmEntryPolicyEvidence> exactPolicies =
        policySet.policies().stream()
            .filter(
                candidate ->
                    candidate.policy().worldSlug().equals(holdRequest.worldSlug())
                        && candidate.policy().realmSlug().equals(ownerReadRequest.realmSlug()))
            .toList();
    if (exactPolicies.size() != 1) {
      throw new IllegalArgumentException(
          "Exactly one sealed policy for the exact realm is required");
    }
    PublishedRealmEntryPolicyEvidence selectedPolicy = exactPolicies.getFirst();
    if (!selectedPolicy.equals(evidence.selectedPolicyEvidence())
        || !selectedPolicy.hasValidDigest(policySet)) {
      throw new IllegalArgumentException("Selected sealed realm policy digest is invalid");
    }
    RealmEntryPolicy policy = selectedPolicy.policy();
    if (!policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || !policy.worldSlug().equals(holdRequest.worldSlug())
        || !policy.realmSlug().equals(ownerReadRequest.realmSlug())) {
      throw new IllegalArgumentException(
          "Sealed Game Design route is not visible public-production SHARED PRESEEDED_ONLY");
    }

    // This is the Game Session owner's independently validated current pointer projection digest.
    String pointerSnapshotDigest = evidence.admissionPointerSnapshotDigest();
    if (!isRawDigest(pointerSnapshotDigest)) {
      throw new IllegalArgumentException(
          "Source-owned admission pointer snapshot digest is invalid");
    }
    // Entity stores the exact terminal proof digest as raw hex; the proof itself is prefixed.
    String ownerProofDigest = rawHexDigest(proof.proofDigest());
    String policyDigest = rawHexDigest(selectedPolicy.policyDigest());

    CanonicalGameplayRosterTarget resolvedTarget =
        new CanonicalGameplayRosterTarget(
            holdRequest.canonicalTenantId(),
            holdRequest.realmId(),
            holdRequest.worldSlug(),
            policy.realmSlug(),
            holdRequest.canonicalGameInstanceId(),
            holdRequest.expectedCatalogRevision(),
            proof.committedPointerVersion(),
            holdRequest.activeLifecycleEpoch(),
            holdRequest.canonicalVersionId(),
            policyDigest,
            policySet.publishedReleaseBundleRef(),
            pointerSnapshotDigest,
            ownerProofDigest,
            holdRequest.playableStateNamespaceId(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);

    return new CanonicalGameplayRosterOwnerEvidence(
        evidence.request().requestUuid(),
        evidence.request().canonicalAccountUuid(),
        resolvedTarget,
        Instant.now());
  }

  private static void requireOwnerProofMatches(
      CanonicalGameplayRosterOwnerReadEvidence.Request request,
      WorldCanonicalInitialAdmissionHold.Request hold,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    if (!request.targetNamespace().equals(hold.targetNamespace())
        || !request.canonicalTenantUuid().equals(hold.canonicalTenantId())
        || !request.worldSlug().equals(hold.worldSlug())
        || !request.realmUuid().equals(hold.realmId())
        || !request.playableStateNamespaceUuid().equals(hold.playableStateNamespaceId())
        || !request.playableStateScope().equals(hold.playableStateScope())
        || !request.canonicalGameInstanceUuid().equals(hold.canonicalGameInstanceId())
        || !request.canonicalVersionUuid().equals(hold.canonicalVersionId())
        || request.expectedCatalogRevision() != hold.expectedCatalogRevision()
        || request.expectedPointerVersion() != proof.committedPointerVersion()
        || request.expectedActiveWorldEpoch() != hold.activeLifecycleEpoch()) {
      throw new IllegalArgumentException(
          "Game Session proof changed the exact route tuple or counters");
    }
  }

  private static String rawHexDigest(String digest) {
    if (digest != null && PREFIXED_SHA256.matcher(digest).matches()) {
      return digest.substring("sha256:".length());
    }
    if (isRawDigest(digest)) {
      return digest;
    }
    throw new IllegalArgumentException("Canonical SHA-256 digest is required");
  }

  private static boolean hasPrefixedDigest(String digest) {
    return digest != null && PREFIXED_SHA256.matcher(digest).matches();
  }

  private static boolean isRawDigest(String digest) {
    return digest != null && RAW_SHA256.matcher(digest).matches();
  }
}
