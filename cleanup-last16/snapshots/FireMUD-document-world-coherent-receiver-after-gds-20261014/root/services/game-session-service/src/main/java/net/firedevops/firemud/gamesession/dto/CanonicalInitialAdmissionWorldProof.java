package net.firedevops.firemud.gamesession.dto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.AuthorityKind;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;

/** Exact World-owned ACTIVE lifecycle and initial-admission hold read returned outside SQL. */
public record CanonicalInitialAdmissionWorldProof(
    String initialAdmissionRequestId,
    String requestDigest,
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID canonicalGameInstanceId,
    UUID canonicalVersionId,
    String lifecycleState,
    long activeLifecycleEpoch,
    CanonicalInitialAdmissionRequest.OriginKind originKind,
    long expectedCatalogRevision,
    Long expectedPriorPointerVersion,
    UUID holdId,
    UUID holdFence,
    String holdBindingDigest,
    AuthorityKind authorityKind,
    String fixtureProvenanceDigest,
    WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence) {
  private static final Pattern WORLD_BINDING_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  /** Existing Human proof shape. */
  public CanonicalInitialAdmissionWorldProof(
      String initialAdmissionRequestId,
      String requestDigest,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      String lifecycleState,
      long activeLifecycleEpoch,
      CanonicalInitialAdmissionRequest.OriginKind originKind,
      long expectedCatalogRevision,
      Long expectedPriorPointerVersion,
      UUID holdId,
      UUID holdFence,
      String holdBindingDigest) {
    this(
        initialAdmissionRequestId,
        requestDigest,
        targetNamespace,
        canonicalTenantId,
        worldSlug,
        realmId,
        playableStateNamespaceId,
        playableStateScope,
        canonicalGameInstanceId,
        canonicalVersionId,
        lifecycleState,
        activeLifecycleEpoch,
        originKind,
        expectedCatalogRevision,
        expectedPriorPointerVersion,
        holdId,
        holdFence,
        holdBindingDigest,
        AuthorityKind.HUMAN,
        null,
        null);
  }

  /** Existing authority-tagged Human proof shape, before fixture lifecycle evidence was added. */
  public CanonicalInitialAdmissionWorldProof(
      String initialAdmissionRequestId,
      String requestDigest,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      String lifecycleState,
      long activeLifecycleEpoch,
      CanonicalInitialAdmissionRequest.OriginKind originKind,
      long expectedCatalogRevision,
      Long expectedPriorPointerVersion,
      UUID holdId,
      UUID holdFence,
      String holdBindingDigest,
      AuthorityKind authorityKind,
      String fixtureProvenanceDigest) {
    this(
        initialAdmissionRequestId,
        requestDigest,
        targetNamespace,
        canonicalTenantId,
        worldSlug,
        realmId,
        playableStateNamespaceId,
        playableStateScope,
        canonicalGameInstanceId,
        canonicalVersionId,
        lifecycleState,
        activeLifecycleEpoch,
        originKind,
        expectedCatalogRevision,
        expectedPriorPointerVersion,
        holdId,
        holdFence,
        holdBindingDigest,
        authorityKind,
        fixtureProvenanceDigest,
        null);
  }

  public CanonicalInitialAdmissionWorldProof {
    Objects.requireNonNull(initialAdmissionRequestId, "initialAdmissionRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    Objects.requireNonNull(canonicalGameInstanceId, "canonicalGameInstanceId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(lifecycleState, "lifecycleState");
    Objects.requireNonNull(originKind, "originKind");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(holdFence, "holdFence");
    Objects.requireNonNull(authorityKind, "authorityKind");
    if (authorityKind == AuthorityKind.HUMAN) {
      if (fixtureProvenanceDigest != null || lifecycleEvidence != null) {
        throw new IllegalArgumentException("Human World proof cannot carry fixture provenance");
      }
    } else if (fixtureProvenanceDigest == null
        || !WORLD_BINDING_DIGEST.matcher(fixtureProvenanceDigest).matches()) {
      throw new IllegalArgumentException("Fixture World proof must carry its policy-seal digest");
    } else if (lifecycleEvidence == null) {
      throw new IllegalArgumentException(
          "Fixture World proof must carry its authenticated lifecycle owner readback");
    }
    if (!WORLD_BINDING_DIGEST
        .matcher(Objects.requireNonNull(holdBindingDigest, "holdBindingDigest"))
        .matches()) {
      throw new IllegalArgumentException("World hold proof has an invalid binding digest");
    }
    if (activeLifecycleEpoch <= 0L
        || expectedCatalogRevision <= 0L
        || !"ACTIVE".equals(lifecycleState)) {
      throw new IllegalArgumentException("World proof must contain an exact positive ACTIVE epoch");
    }
    if (authorityKind == AuthorityKind.RUN_OWNED_FIXTURE) {
      requireFixtureLifecycleMatchesProof(
          lifecycleEvidence,
          targetNamespace,
          canonicalTenantId,
          worldSlug,
          realmId,
          playableStateNamespaceId,
          playableStateScope,
          canonicalGameInstanceId,
          canonicalVersionId,
          activeLifecycleEpoch,
          expectedCatalogRevision,
          fixtureProvenanceDigest);
    }
    if ((originKind == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
            && expectedPriorPointerVersion != null)
        || (originKind == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED
            && (expectedPriorPointerVersion == null || expectedPriorPointerVersion <= 0L))) {
      throw new IllegalArgumentException("World hold proof has an invalid tagged origin");
    }
  }

  public void requireMatches(CanonicalInitialAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    if (!initialAdmissionRequestId.equals(request.initialAdmissionRequestId())
        || !requestDigest.equals(request.requestDigest())
        || !targetNamespace.equals(request.targetNamespace())
        || !canonicalTenantId.equals(request.canonicalTenantId())
        || !worldSlug.equals(request.worldSlug())
        || !realmId.equals(request.realmId())
        || !playableStateNamespaceId.equals(request.playableStateNamespaceId())
        || !playableStateScope.equals(request.playableStateScope())
        || !canonicalGameInstanceId.equals(request.canonicalGameInstanceId())
        || !canonicalVersionId.equals(request.canonicalVersionId())
        || activeLifecycleEpoch != request.activeLifecycleEpoch()
        || originKind != request.originKind()
        || expectedCatalogRevision != request.expectedCatalogRevision()
        || !Objects.equals(expectedPriorPointerVersion, request.expectedPriorPointerVersion())
        || !holdId.equals(request.holdId())
        || !holdFence.equals(request.holdFence())
        || !holdBindingDigest.equals(request.holdBindingDigest())
        || authorityKind != request.authorityKind()
        || !Objects.equals(fixtureProvenanceDigest, request.fixtureProvenanceDigest())) {
      throw new IllegalArgumentException(
          "World ACTIVE/hold proof does not match the exact initial admission request");
    }
    if (authorityKind == AuthorityKind.RUN_OWNED_FIXTURE) {
      requireFixtureLifecycleMatchesRequest(lifecycleEvidence, request);
    }
  }

  private static void requireFixtureLifecycleMatchesProof(
      WorldCanonicalInstanceLifecycleEvidence evidence,
      String targetNamespace,
      UUID canonicalTenantId,
      String worldSlug,
      UUID realmId,
      UUID playableStateNamespaceId,
      String playableStateScope,
      UUID canonicalGameInstanceId,
      UUID canonicalVersionId,
      long activeLifecycleEpoch,
      long expectedCatalogRevision,
      String fixtureProvenanceDigest) {
    var lifecycleRequest = evidence.request();
    var fixture = evidence.fixtureLifecycleProof();
    var association = fixture.gameSessionAssociation();
    if (!"ACTIVE".equals(evidence.lifecycleStatus())
        || evidence.lifecycleEpoch() != activeLifecycleEpoch
        || lifecycleRequest.authorityKind() != AuthorityKind.RUN_OWNED_FIXTURE
        || !targetNamespace.equals(lifecycleRequest.targetNamespace())
        || !canonicalTenantId.equals(lifecycleRequest.canonicalTenantId())
        || !worldSlug.equals(lifecycleRequest.worldSlug())
        || !canonicalGameInstanceId.equals(lifecycleRequest.canonicalGameInstanceId())
        || !playableStateNamespaceId.equals(lifecycleRequest.playableStateNamespaceId())
        || !playableStateScope.equals(lifecycleRequest.playableStateScope())
        || !canonicalVersionId.equals(lifecycleRequest.canonicalVersionId())
        || !fixtureProvenanceDigest.equals(lifecycleRequest.fixtureProvenanceDigest())
        || !fixtureProvenanceDigest.equals(association.fixturePolicySeal().evidenceDigest())
        || !targetNamespace.equals(association.request().targetNamespace())
        || !canonicalTenantId.equals(association.canonicalTenantId())
        || !canonicalGameInstanceId.equals(association.gameInstanceUuid())
        || !canonicalVersionId.equals(association.canonicalVersionId())
        || !realmId.equals(association.canonicalRealmId())
        || !playableStateNamespaceId.equals(association.playableStateNamespaceId())
        || !playableStateScope.equals(
            association.fixturePolicyRequest().capability().stateScope())
        || association.catalogRevision() != expectedCatalogRevision
        || !lifecycleRequest.controlPlaneRequestId()
            .equals(association.request().fixtureOperationId().toString())) {
      throw new IllegalArgumentException(
          "Fixture lifecycle readback differs from the exact World hold and GS owner tuple");
    }
  }

  private static void requireFixtureLifecycleMatchesRequest(
      WorldCanonicalInstanceLifecycleEvidence evidence,
      CanonicalInitialAdmissionRequest request) {
    requireFixtureLifecycleMatchesProof(
        evidence,
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
        request.fixtureProvenanceDigest());
  }
}
