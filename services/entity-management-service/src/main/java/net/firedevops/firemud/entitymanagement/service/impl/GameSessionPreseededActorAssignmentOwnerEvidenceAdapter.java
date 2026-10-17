package net.firedevops.firemud.entitymanagement.service.impl;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.world.PreseededActorAssignmentOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.entitymanagement.client.GameSessionPreseededActorAssignmentOwnerReadClient;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidence;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentOwnerEvidencePort;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentRequest;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;

/** Maps the exact Game Session source proof and one Account staging snapshot for Entity. */
public final class GameSessionPreseededActorAssignmentOwnerEvidenceAdapter
    implements PreseededActorAssignmentOwnerEvidencePort {
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PREFIXED_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");

  private final GameSessionPreseededActorAssignmentOwnerReadClient gameSessionClient;
  private final String workloadNamespace;

  public GameSessionPreseededActorAssignmentOwnerEvidenceAdapter(
      GameSessionPreseededActorAssignmentOwnerReadClient gameSessionClient,
      String workloadNamespace) {
    this.gameSessionClient = Objects.requireNonNull(gameSessionClient, "gameSessionClient");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "Entity Management workload namespace must be one DNS label");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public PreseededActorAssignmentOwnerEvidence resolveCurrentEligibleTarget(
      PreseededActorAssignmentRequest request,
      RuntimeAccountIdentityEvidence accountIdentityEvidence,
      AccountActorStagingEligibilityEvidence stagingEligibilityEvidence) {
    Objects.requireNonNull(request, "request");
    requireExactAccountSnapshots(request, accountIdentityEvidence, stagingEligibilityEvidence);

    var expected = request.expectedTarget();
    if (expected.playableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED) {
      throw new OwnerEvidenceUnavailableException();
    }
    var ownerReadRequest =
        new PreseededActorAssignmentOwnerReadEvidence.Request(
            request.assignmentUuid(),
            request.canonicalAccountUuid(),
            workloadNamespace,
            expected.canonicalTenantUuid(),
            expected.worldSlug(),
            expected.realmUuid(),
            expected.realmSlug(),
            expected.playableStateNamespaceId(),
            "SHARED",
            canonicalUuid(expected.gameInstanceId(), "gameInstanceId"),
            expected.canonicalVersionUuid(),
            expected.catalogRevision(),
            expected.frozenPolicyDigest(),
            expected.publishedReleaseBundleRef());

    PreseededActorAssignmentOwnerReadEvidence source;
    try {
      source = gameSessionClient.getPreseededActorAssignmentOwnerRead(ownerReadRequest);
    } catch (RuntimeException unavailable) {
      throw new OwnerEvidenceUnavailableException(unavailable);
    }
    if (source == null || !ownerReadRequest.equals(source.request())) {
      throw new OwnerEvidenceUnavailableException();
    }
    try {
      return mapSourceAndAccountSnapshot(request, stagingEligibilityEvidence, source);
    } catch (RuntimeException invalid) {
      if (invalid instanceof OwnerEvidenceUnavailableException unavailable) {
        throw unavailable;
      }
      throw new OwnerEvidenceUnavailableException(invalid);
    }
  }

  private PreseededActorAssignmentOwnerEvidence mapSourceAndAccountSnapshot(
      PreseededActorAssignmentRequest request,
      AccountActorStagingEligibilityEvidence staging,
      PreseededActorAssignmentOwnerReadEvidence source) {
    var proof = source.sourceEvidence().gameSessionOwnerProof();
    WorldCanonicalInitialAdmissionHold.Request hold = proof.holdIdentity().request();
    PublishedRealmEntryPolicySetEvidence policySet =
        source.sourceEvidence().publishedPolicySetEvidence();
    PublishedRealmEntryPolicyEvidence selected = source.sourceEvidence().selectedPolicyEvidence();
    String policyDigest = rawHexDigest(selected.policyDigest());
    String ownerProofDigest = rawHexDigest(proof.proofDigest());

    return new PreseededActorAssignmentOwnerEvidence(
        request.assignmentUuid(),
        request.canonicalAccountUuid(),
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        staging.observedAt(),
        staging.membershipAuthorityGeneration(),
        staging.eligibilityDecisionDigest(),
        hold.canonicalTenantId(),
        hold.realmId(),
        hold.worldSlug(),
        selected.policy().realmSlug(),
        hold.canonicalGameInstanceId().toString(),
        hold.expectedCatalogRevision(),
        hold.canonicalVersionId(),
        policyDigest,
        hold.playableStateNamespaceId(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY,
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        staging.authoritySnapshotDigest(),
        ownerProofDigest,
        policySet.publishedReleaseBundleRef());
  }

  private void requireExactAccountSnapshots(
      PreseededActorAssignmentRequest request,
      RuntimeAccountIdentityEvidence identity,
      AccountActorStagingEligibilityEvidence staging) {
    if (identity == null
        || !request.assignmentUuid().equals(identity.requestId())
        || !request.canonicalAccountUuid().equals(identity.canonicalAccountId())
        || !workloadNamespace.equals(identity.targetNamespace())) {
      throw new OwnerEvidenceUnavailableException();
    }
    if (staging == null
        || !request.assignmentUuid().equals(staging.requestId())
        || !request.canonicalAccountUuid().equals(staging.canonicalAccountId())
        || !request.expectedTarget().canonicalTenantUuid().equals(staging.canonicalTenantId())
        || !workloadNamespace.equals(staging.targetNamespace())
        || !identity.accountUuidProvenance().equals(staging.accountUuidProvenance())
        || staging.purpose()
            != AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY
        || staging.currentness()
            != AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION
        || staging.decision() != AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static UUID canonicalUuid(String value, String name) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(name + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static String rawHexDigest(String digest) {
    if (digest != null && PREFIXED_SHA256.matcher(digest).matches()) {
      return digest.substring("sha256:".length());
    }
    if (digest != null && RAW_SHA256.matcher(digest).matches()) {
      return digest;
    }
    throw new IllegalArgumentException("Canonical SHA-256 digest is required");
  }
}
